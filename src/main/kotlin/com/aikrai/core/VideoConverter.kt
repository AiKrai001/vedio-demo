package com.aikrai.core

import com.aikrai.core.FfmpegSupport
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * 视频格式转换服务：顺序处理多个输入文件，支持常见容器格式（mp4/mkv/mov/avi/webm/flv）。
 *
 * 设计要点：
 * - 多策略尝试：先尝试“封装复制”，失败则退化到带编码的策略；
 * - 编码器可用性缓存：减少重复检测开销；
 * - 过程日志与进度回调便于 UI 反馈与排障。
 */
class VideoConverter {
  private val log = LoggerFactory.getLogger(VideoConverter::class.java)
  private val encoderAvailability = mutableMapOf<String, Boolean>()

  /**
   * 批量转换多个输入文件至指定容器格式。
   *
   * @param inputs 输入文件列表
   * @param targetFormat 目标容器格式（如 mp4/mkv 等）
   * @param ffmpegExecutable 可选 ffmpeg 路径，空则自动解析
   * @param outputDirectory 可选统一输出目录，空则与源文件同目录
   * @param progressReporter 进度回调（每个文件多次：处理中/成功/失败）
   * @return 转换总报告（成功/失败统计与明细）
   */
  fun convertAll(
    inputs: List<Path>,
    targetFormat: String,
    ffmpegExecutable: String? = null,
    outputDirectory: Path? = null,
    progressReporter: ((ConversionProgress) -> Unit)? = null
  ): ConversionReport {
    val normalizedInputs = inputs.map { it.toAbsolutePath().normalize() }
    if (normalizedInputs.isEmpty()) {
      log.info("未提供待转换文件，直接返回空报告")
      return ConversionReport.empty()
    }

    val normalizedFormat = targetFormat.lowercase()
    require(normalizedFormat in SUPPORTED_FORMATS) {
      "输出格式仅支持: ${SUPPORTED_FORMATS.joinToString(", ")}"
    }

    normalizedInputs.forEach { input ->
      require(Files.exists(input) && Files.isRegularFile(input)) {
        "文件 ${input.toAbsolutePath()} 不存在或不是普通文件"
      }
    }

    val normalizedOutputDir = outputDirectory?.let { raw ->
      val normalized = raw.toAbsolutePath().normalize()
      if (Files.exists(normalized) && !Files.isDirectory(normalized)) {
        throw IllegalArgumentException("输出目录必须是有效的文件夹路径")
      }
      Files.createDirectories(normalized)
      normalized
    }

    val ffmpegPath = FfmpegSupport.resolveFfmpegExecutable(ffmpegExecutable)
    val ffprobePath = FfmpegSupport.resolveFfprobeExecutable(ffmpegExecutable)
    // 预探测每个文件时长（毫秒），供按总时长计算进度条
    val durationsMs: Map<Path, Long> = normalizedInputs.associateWith { probeDurationMillis(it, ffprobePath) ?: 0L }
    val totalDurationMs: Long = durationsMs.values.sum()
    val durationFallback: Boolean = totalDurationMs <= 0L

    var convertedCount = 0
    var skippedCount = 0
    val failures = mutableListOf<ConversionFailure>()
    // 已处理的总时长（毫秒），在每个文件完成后累加；进行中文件通过 ffmpeg 输出实时更新
    var processedDurationMsSoFar = 0L

    normalizedInputs.forEachIndexed { index, input ->
      if (Thread.currentThread().isInterrupted) {
        throw InterruptedException("转换任务被中断")
      }

      val detectedFormat = detectFormat(input, ffprobePath) ?: guessFromExtension(input)
      progressReporter?.invoke(
        ConversionProgress(
          totalFiles = normalizedInputs.size,
          completedFiles = index,
          currentFile = input,
          status = ConversionProgress.Status.PROCESSING,
          detail = detectedFormat?.let { "识别到格式: $it" },
          detectedFormat = detectedFormat,
          outputFile = null
        )
      )

      val targetFile = resolveTargetFile(input, normalizedOutputDir, normalizedFormat)

      try {
        val currentFileDuration = durationsMs[input] ?: 0L
        if (!durationFallback && currentFileDuration > 0L) {
          // 在转换过程中解析 ffmpeg 输出中的时间进度，驱动“按时长”的进度条
          runConversion(ffmpegPath, input, targetFile, normalizedFormat) { timeMs ->
            val clamped = if (currentFileDuration <= 0L) timeMs else minOf(timeMs, currentFileDuration)
            val processed = processedDurationMsSoFar + clamped
            progressReporter?.invoke(
              ConversionProgress(
                totalFiles = normalizedInputs.size,
                completedFiles = index,
                currentFile = input,
                status = ConversionProgress.Status.PROCESSING,
                detail = null,
                detectedFormat = detectedFormat,
                outputFile = null,
                totalDurationMillis = totalDurationMs,
                processedDurationMillis = processed
              )
            )
          }
        } else {
          // 无法按时长衡量（未探测到或时长为 0），按文件数回退
          runConversion(ffmpegPath, input, targetFile, normalizedFormat, onTime = null)
        }
        convertedCount += 1
        // 文件完成后，累加该文件时长（回退模式不累加）
        processedDurationMsSoFar = if (durationFallback) 0L else (processedDurationMsSoFar + (durationsMs[input] ?: 0L))
        progressReporter?.invoke(
          ConversionProgress(
            totalFiles = normalizedInputs.size,
            completedFiles = index + 1,
            currentFile = input,
            status = ConversionProgress.Status.CONVERTED,
            detail = "输出到 ${targetFile.toAbsolutePath()}",
            detectedFormat = detectedFormat,
            outputFile = targetFile
          )
        )
      } catch (ex: Exception) {
        failures += ConversionFailure(input, ex.message ?: ex.javaClass.simpleName)
        Files.deleteIfExists(targetFile)
        progressReporter?.invoke(
          ConversionProgress(
            totalFiles = normalizedInputs.size,
            completedFiles = index + 1,
            currentFile = input,
            status = ConversionProgress.Status.FAILED,
            detail = ex.message,
            detectedFormat = detectedFormat,
            outputFile = null
          )
        )
      }
    }

    return ConversionReport(
      totalFileCount = normalizedInputs.size,
      successCount = convertedCount,
      skippedCount = skippedCount,
      failureDetails = failures.toList()
    )
  }

  /**
   * 使用 ffprobe 探测输入文件的容器格式。
   *
   * @param input 输入文件
   * @param ffprobePath ffprobe 可执行路径
   * @return 探测到的格式名（小写），失败返回 null
   */
  private fun detectFormat(input: Path, ffprobePath: String): String? {
    return try {
      val command = listOf(
        ffprobePath,
        "-v",
        "error",
        "-show_entries",
        "format=format_name",
        "-of",
        "default=noprint_wrappers=1:nokey=1",
        input.toString()
      )
      val process = ProcessBuilder(command)
        .redirectErrorStream(true)
        .start()

      val output = process.inputStream.bufferedReader().use(BufferedReader::readText).trim()
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        log.warn("探测文件 {} 格式超时", input)
        return null
      }
      if (process.exitValue() != 0) {
        log.warn("探测文件 {} 格式失败，退出码 {}", input, process.exitValue())
        return null
      }
      output.takeIf { it.isNotBlank() }?.split(',')?.first()?.trim()?.lowercase()
    } catch (ex: Exception) {
      log.warn("探测文件 {} 格式失败: {}", input, ex.message)
      null
    }
  }

  /**
   * 使用 ffprobe 探测媒体总时长（毫秒）。
   * 优先读取容器层 format.duration，解析失败返回 null。
   */
  private fun probeDurationMillis(input: Path, ffprobePath: String): Long? {
    return try {
      val command = listOf(
        ffprobePath,
        "-v", "error",
        "-show_entries", "format=duration",
        "-of", "default=noprint_wrappers=1:nokey=1",
        input.toString()
      )
      val process = ProcessBuilder(command)
        .redirectErrorStream(true)
        .start()
      val output = process.inputStream.bufferedReader().use(BufferedReader::readText).trim()
      if (!process.waitFor(20, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return null
      }
      if (process.exitValue() != 0) return null
      val seconds = output.toDoubleOrNull() ?: return null
      (seconds * 1000).toLong().coerceAtLeast(0L)
    } catch (_: Exception) {
      null
    }
  }

  /**
   * 通过文件扩展名推测容器格式（作为探测失败时的兜底）。
   *
   * @param input 输入文件
   * @return 扩展名（小写），无法推测返回 null
   */
  private fun guessFromExtension(input: Path): String? {
    val name = input.fileName?.toString() ?: return null
    val dotIndex = name.lastIndexOf('.')
    if (dotIndex <= 0 || dotIndex == name.length - 1) {
      return null
    }
    return name.substring(dotIndex + 1).lowercase()
  }

  /**
   * 计算输出文件路径，避免与原文件同名冲突（必要时追加递增后缀）。
   *
   * @param input 源文件
   * @param outputDir 统一输出目录（可空）
   * @param targetFormat 目标扩展名
   * @return 可安全写入的目标路径
   */
  private fun resolveTargetFile(input: Path, outputDir: Path?, targetFormat: String): Path {
    val parent = outputDir ?: input.parent ?: input.toAbsolutePath().parent
    Files.createDirectories(parent)
    val originalName = input.fileName?.toString() ?: input.toString()
    val base = originalName.substringBeforeLast('.', originalName)
    var candidate = parent.resolve("$base.$targetFormat").normalize()
    var index = 1
    val originalAbsolute = input.toAbsolutePath().normalize()
    while (Files.exists(candidate) || candidate == originalAbsolute) {
      candidate = parent.resolve("$base-$index.$targetFormat").normalize()
      index += 1
    }
    return candidate
  }

  /**
   * 执行一组转换策略，直至成功或所有策略失败。
   *
   * @param ffmpegPath ffmpeg 可执行路径
   * @param input 输入文件
   * @param output 目标文件
   * @param targetFormat 目标容器格式
   * @throws IllegalStateException 所有策略均失败时抛出
   */
  private fun runConversion(ffmpegPath: String, input: Path, output: Path, targetFormat: String, onTime: ((Long) -> Unit)? = null) {
    val strategies = conversionStrategies(targetFormat)
    val errorMessages = mutableListOf<String>()

    for (strategy in strategies) {
      val missingEncoders = strategy.requiredEncoders.filterNot { isEncoderAvailable(ffmpegPath, it) }
      if (missingEncoders.isNotEmpty()) {
        log.warn("跳过转换策略 {}，缺少编码器 {}", strategy.description, missingEncoders.joinToString(","))
        errorMessages += "策略${strategy.description}缺少编码器${missingEncoders.joinToString("/")}"
        continue
      }

      log.info("尝试转换策略: {}", strategy.description)
      Files.deleteIfExists(output)
      try {
        executeConversion(ffmpegPath, input, output, strategy.arguments, onTime)
        log.info("转换策略 {} 成功", strategy.description)
        return
      } catch (ex: Exception) {
        Files.deleteIfExists(output)
        val message = ex.message ?: ex.javaClass.simpleName
        log.warn("转换策略 {} 失败: {}", strategy.description, message)
        errorMessages += "策略${strategy.description}失败:$message"
      }
    }

    val combined = errorMessages.joinToString("; ")
    throw IllegalStateException(if (combined.isBlank()) "未找到可用的转换策略" else combined)
  }

  /**
   * 调用 ffmpeg 执行一次具体的转换命令。
   *
   * @param ffmpegPath ffmpeg 可执行路径
   * @param input 输入文件
   * @param output 输出文件
   * @param codecArgs 编解码与封装参数
   */
  private fun executeConversion(ffmpegPath: String, input: Path, output: Path, codecArgs: List<String>, onTime: ((Long) -> Unit)? = null) {
    val command = mutableListOf(
      ffmpegPath,
      "-hide_banner",
      "-loglevel",
      "info",
      "-y",
      "-i",
      input.toString()
    )
    // 输出可解析的进度到标准输出，便于按时长更新 UI
    command.addAll(listOf("-progress", "pipe:1", "-nostats"))
    command.addAll(codecArgs)
    command.add(output.toString())

    val process = ProcessBuilder(command)
      .redirectErrorStream(true)
      .start()

    val timeRegex = Regex("time=([0-9]{2}):([0-9]{2}):([0-9]{2}\\.?[0-9]*)")
    val outTimeMsRegex = Regex("^out_time_ms=(\\d+)")
    InputStreamReader(process.inputStream).use { reader ->
      reader.buffered().useLines { lines ->
        lines.filter { it.isNotBlank() }
          .forEach { line ->
            log.info("[ffmpeg-convert] {}", line)
            if (onTime != null) {
              val outMs = outTimeMsRegex.find(line)?.groupValues?.getOrNull(1)?.toLongOrNull()
              if (outMs != null) {
                onTime.invoke(outMs)
              } else {
                val m = timeRegex.find(line)
                if (m != null) {
                  val hh = m.groupValues[1].toIntOrNull() ?: 0
                  val mm = m.groupValues[2].toIntOrNull() ?: 0
                  val ss = m.groupValues[3].toDoubleOrNull() ?: 0.0
                  val millis = ((hh * 3600 + mm * 60) * 1000L) + (ss * 1000).toLong()
                  onTime.invoke(millis)
                }
              }
            }
          }
      }
    }

    if (!process.waitFor(2, TimeUnit.HOURS)) {
      process.destroyForcibly()
      throw IllegalStateException("ffmpeg 转换超时")
    }

    val exitCode = process.exitValue()
    if (exitCode != 0) {
      throw IllegalStateException("ffmpeg 退出码 $exitCode")
    }
  }

  /**
   * 根据容器格式生成一组候选转换策略（按优先级从轻到重）。
   *
   * @param targetFormat 目标容器格式
   * @return 策略列表，调用方依次尝试
   */
  private fun conversionStrategies(targetFormat: String): List<CodecStrategy> = when (targetFormat) {
    "mp4" -> listOf(
      CodecStrategy(
        description = "封装复制",
        arguments = listOf("-c:v", "copy", "-c:a", "copy", "-movflags", "+faststart")
      ),
      CodecStrategy(
        description = "H264/AAC 编码",
        arguments = listOf(
          "-c:v", "libx264",
          "-preset", "medium",
          "-crf", "23",
          "-c:a", "aac",
          "-b:a", "192k",
          "-movflags", "+faststart"
        ),
        requiredEncoders = listOf("libx264", "aac")
      )
    )

    "mkv" -> listOf(
      CodecStrategy(
        description = "封装复制",
        arguments = listOf("-c:v", "copy", "-c:a", "copy")
      ),
      CodecStrategy(
        description = "H264/AAC 编码",
        arguments = listOf(
          "-c:v", "libx264",
          "-preset", "medium",
          "-crf", "23",
          "-c:a", "aac",
          "-b:a", "192k"
        ),
        requiredEncoders = listOf("libx264", "aac")
      )
    )

    "mov" -> listOf(
      CodecStrategy(
        description = "封装复制",
        arguments = listOf("-c:v", "copy", "-c:a", "copy")
      ),
      CodecStrategy(
        description = "H264/AAC 编码",
        arguments = listOf(
          "-c:v", "libx264",
          "-preset", "medium",
          "-crf", "23",
          "-c:a", "aac",
          "-b:a", "192k"
        ),
        requiredEncoders = listOf("libx264", "aac")
      )
    )

    "avi" -> listOf(
      CodecStrategy(
        description = "MPEG4/MP3 编码",
        arguments = listOf(
          "-c:v", "mpeg4",
          "-q:v", "5",
          "-c:a", "mp3",
          "-b:a", "192k"
        )
      )
    )

    "webm" -> listOf(
      CodecStrategy(
        description = "VP9/Opus 编码",
        arguments = listOf(
          "-c:v", "libvpx-vp9",
          "-b:v", "1M",
          "-c:a", "libopus"
        ),
        requiredEncoders = listOf("libvpx-vp9", "libopus")
      )
    )

    "flv" -> listOf(
      CodecStrategy(
        description = "H264/AAC 编码",
        arguments = listOf(
          "-c:v", "libx264",
          "-preset", "veryfast",
          "-crf", "26",
          "-c:a", "aac",
          "-b:a", "160k"
        ),
        requiredEncoders = listOf("libx264", "aac")
      )
    )

    else -> listOf(
      CodecStrategy(
        description = "封装复制",
        arguments = listOf("-c", "copy")
      )
    )
  }

  /**
   * 判断 ffmpeg 当前是否可用指定编码器（结果带缓存）。
   *
   * @param ffmpegPath ffmpeg 可执行路径
   * @param encoder 编码器名称（如 libx264、aac）
   * @return 可用返回 true
   */
  private fun isEncoderAvailable(ffmpegPath: String, encoder: String): Boolean {
    val key = "${ffmpegPath.lowercase()}|${encoder.lowercase()}"
    return encoderAvailability.getOrPut(key) {
      try {
        val process = ProcessBuilder(
          ffmpegPath,
          "-hide_banner",
          "-loglevel",
          "error",
          "-h",
          "encoder=$encoder"
        ).redirectErrorStream(true).start()

        val completed = process.waitFor(10, TimeUnit.SECONDS)
        val exitCode = if (completed) process.exitValue() else {
          process.destroyForcibly()
          -1
        }
        process.inputStream.bufferedReader().use { it.readText() }
        exitCode == 0
      } catch (ex: Exception) {
        log.warn("检测编码器 {} 可用性失败: {}", encoder, ex.message)
        false
      }
    }
  }

  /**
   * 转换策略：描述一次 ffmpeg 调用需要的参数与依赖的编码器。
   */
  private data class CodecStrategy(
    val description: String,
    val arguments: List<String>,
    val requiredEncoders: List<String> = emptyList()
  )

  /**
   * 转换失败详情：记录失败文件与原因。
   */
  data class ConversionFailure(
    val inputFile: Path,
    val reason: String?
  )

  /**
   * 转换进度快照：用于 UI 实时反馈。
   */
  data class ConversionProgress(
    val totalFiles: Int,
    val completedFiles: Int,
    val currentFile: Path,
    val status: Status,
    val detail: String?,
    val detectedFormat: String?,
    val outputFile: Path?,
    // 新增：基于时长的进度支持（毫秒）。若 totalDurationMillis<=0 则表示按文件数回退
    val totalDurationMillis: Long = 0,
    val processedDurationMillis: Long = 0
  ) {
    /**
     * 文件转换状态。
     */
    enum class Status {
      PROCESSING,
      CONVERTED,
      FAILED
    }
  }

  /**
   * 转换任务总报告：包含任务规模、成功/失败统计与明细。
   */
  data class ConversionReport(
    val totalFileCount: Int,
    val successCount: Int,
    val skippedCount: Int,
    val failureDetails: List<ConversionFailure>
  ) {
    val failureCount: Int get() = failureDetails.size

    companion object {
      fun empty() = ConversionReport(0, 0, 0, emptyList())
    }
  }

  companion object {
    /** 支持输出的容器格式集合 */
    private val SUPPORTED_FORMATS = setOf("mp4", "mkv", "mov", "avi", "webm", "flv")
  }
}


