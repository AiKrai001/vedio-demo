package com.aikrai.core

import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 通用视频拼接服务：将同一目录下的多个视频按字典序拼接为单个输出文件。
 *
 * 特性说明：
 * - 支持常见视频容器（mp4/mkv/mov/avi/webm/flv），自动根据目标格式选择编码策略；
 * - 自动排序（字典序），无需用户手动调整顺序；
 * - 内置编码器可用性检测与多策略回退（优先尝试封装复制，失败后转为重编码）；
 * - 提供进度回调，基于全部输入时长估算处理进度；
 * - 输出文件默认位于源目录，命名与目录同名；如已存在则追加序号避免覆盖。
 */
class VideoConcatenator {
  private val log = LoggerFactory.getLogger(VideoConcatenator::class.java)
  private val encoderAvailability = mutableMapOf<String, Boolean>()

  /**
   * 拼接指定目录下的所有支持视频文件，并输出为目标格式。
   */
  fun concatenateDirectory(
    directory: Path,
    targetFormat: String,
    ffmpegExecutable: String? = null,
    progressReporter: ((ConcatenateProgress) -> Unit)? = null
  ): ConcatenateReport {
    val normalizedDir = directory.toAbsolutePath().normalize()
    if (!Files.exists(normalizedDir) || !Files.isDirectory(normalizedDir)) {
      log.warn("目录 {} 不存在或不是有效文件夹，跳过拼接", normalizedDir)
      return ConcatenateReport.empty()
    }

    val normalizedFormat = targetFormat.lowercase(Locale.getDefault())
    require(normalizedFormat in SUPPORTED_OUTPUT_FORMATS) {
      "输出格式仅支持: ${SUPPORTED_OUTPUT_FORMATS.joinToString(", ")}"
    }

    val videoFiles = collectVideoFiles(normalizedDir)
    if (videoFiles.isEmpty()) {
      log.info("目录 {} 下未找到可拼接的视频文件", normalizedDir)
      return ConcatenateReport.empty()
    }

    val ffmpegPath = FfmpegSupport.resolveFfmpegExecutable(ffmpegExecutable)
    val ffprobePath = FfmpegSupport.resolveFfprobeExecutable(ffmpegExecutable)
    val totalDuration = videoFiles.sumOf { probeDurationMillis(it, ffprobePath) ?: 0L }

    val concatListFile = buildConcatListFile(videoFiles)
    val outputFile = resolveOutputFile(normalizedDir, normalizedFormat)

    progressReporter?.invoke(
      ConcatenateProgress(
        totalFiles = videoFiles.size,
        totalDurationMillis = totalDuration,
        processedDurationMillis = 0L,
        status = ConcatenateProgress.Status.PROCESSING,
        detail = "准备执行 ffmpeg",
        strategyDescription = null,
        outputFile = outputFile
      )
    )

    val strategies = codecStrategies(normalizedFormat)
    var lastError: Exception? = null

    try {
      for (strategy in strategies) {
        if (strategy.requiredEncoders.any { !isEncoderAvailable(ffmpegPath, it) }) {
          log.warn("跳过策略 '{}'，编码器不可用", strategy.description)
          continue
        }

        progressReporter?.invoke(
          ConcatenateProgress(
            totalFiles = videoFiles.size,
            totalDurationMillis = totalDuration,
            processedDurationMillis = 0L,
            status = ConcatenateProgress.Status.PROCESSING,
            detail = "使用策略：${strategy.description}",
            strategyDescription = strategy.description,
            outputFile = outputFile
          )
        )

        try {
          runConcatenate(
            ffmpegPath = ffmpegPath,
            concatListFile = concatListFile,
            outputFile = outputFile,
            strategy = strategy,
            fileCount = videoFiles.size,
            totalDurationMillis = totalDuration,
            progressReporter = progressReporter
          )

          progressReporter?.invoke(
            ConcatenateProgress(
              totalFiles = videoFiles.size,
              totalDurationMillis = totalDuration,
              processedDurationMillis = totalDuration,
              status = ConcatenateProgress.Status.COMPLETED,
              detail = null,
              strategyDescription = strategy.description,
              outputFile = outputFile
            )
          )

          return ConcatenateReport(
            totalFileCount = videoFiles.size,
            outputFile = outputFile,
            strategyDescription = strategy.description,
            failure = null
          )
        } catch (ex: Exception) {
          lastError = ex as? Exception ?: Exception(ex)
          log.warn("策略 '{}' 失败: {}", strategy.description, ex.message)
        }
      }
    } finally {
      Files.deleteIfExists(concatListFile)
    }

    val reason = lastError?.message ?: "所有拼接策略均失败"
    progressReporter?.invoke(
      ConcatenateProgress(
        totalFiles = videoFiles.size,
        totalDurationMillis = totalDuration,
        processedDurationMillis = 0L,
        status = ConcatenateProgress.Status.FAILED,
        detail = reason,
        strategyDescription = null,
        outputFile = null
      )
    )

    return ConcatenateReport(
      totalFileCount = videoFiles.size,
      outputFile = null,
      strategyDescription = null,
      failure = ConcatenateFailure(normalizedDir, reason, lastError)
    )
  }

  /**
   * 收集目录下的可拼接视频文件（仅扫描第一层），并按字典序排序。
   */
  fun collectVideoFiles(directory: Path): List<Path> {
    if (!Files.exists(directory) || !Files.isDirectory(directory)) {
      return emptyList()
    }
    return Files.list(directory).use { stream ->
      stream.filter { Files.isRegularFile(it) }
        .filter { hasSupportedExtension(it) }
        .sorted { first, second ->
          first.fileName.toString().lowercase(Locale.getDefault())
            .compareTo(second.fileName.toString().lowercase(Locale.getDefault()))
        }
        .toList()
    }
  }

  private fun hasSupportedExtension(path: Path): Boolean {
    val name = path.fileName?.toString() ?: return false
    val ext = name.substringAfterLast('.', "").lowercase(Locale.getDefault())
    return ext in SUPPORTED_INPUT_EXTENSIONS
  }

  private fun resolveOutputFile(directory: Path, format: String): Path {
    val baseName = directory.fileName?.toString()?.ifBlank { "output" } ?: "output"
    var candidate = directory.resolve("$baseName.$format").normalize()
    var index = 1
    while (Files.exists(candidate)) {
      candidate = directory.resolve("$baseName-$index.$format").normalize()
      index += 1
    }
    return candidate
  }

  private fun buildConcatListFile(files: List<Path>): Path {
    val tempFile = Files.createTempFile("video-concat-", ".txt")
    val content = files.joinToString(separator = System.lineSeparator()) { path ->
      val escaped = path.toString().replace("'", "'\\''")
      "file '$escaped'"
    }
    Files.writeString(tempFile, content, StandardCharsets.UTF_8)
    return tempFile
  }

  private fun runConcatenate(
    ffmpegPath: String,
    concatListFile: Path,
    outputFile: Path,
    strategy: CodecStrategy,
    fileCount: Int,
    totalDurationMillis: Long,
    progressReporter: ((ConcatenateProgress) -> Unit)?
  ) {
    val command = mutableListOf(
      ffmpegPath,
      "-hide_banner",
      "-y",
      "-f",
      "concat",
      "-safe",
      "0",
      "-i",
      concatListFile.toString()
    )
    command += strategy.arguments
    command += outputFile.toString()

    log.info("执行 ffmpeg 拼接：{}", command.joinToString(" "))

    val process = ProcessBuilder(command)
      .redirectErrorStream(true)
      .start()

    process.inputStream.bufferedReader().use { reader ->
      consumeFfmpegOutput(reader, fileCount, totalDurationMillis, progressReporter, strategy.description, outputFile)
    }

    if (!process.waitFor(2, TimeUnit.HOURS)) {
      process.destroyForcibly()
      throw IllegalStateException("ffmpeg 拼接超时")
    }

    val exitCode = process.exitValue()
    if (exitCode != 0) {
      throw IllegalStateException("ffmpeg 退出码 $exitCode")
    }
  }

  private fun consumeFfmpegOutput(
    reader: BufferedReader,
    fileCount: Int,
    totalDurationMillis: Long,
    progressReporter: ((ConcatenateProgress) -> Unit)?,
    strategyDescription: String,
    outputFile: Path
  ) {
    reader.lineSequence().forEach { line ->
      if (line.isBlank()) return@forEach
      log.info("[ffmpeg] {}", line)
      val timeIndex = line.indexOf("time=")
      if (timeIndex >= 0) {
        val timeToken = line.substring(timeIndex + 5).takeWhile { !it.isWhitespace() }
        val millis = parseFfmpegTime(timeToken)
        if (millis >= 0 && totalDurationMillis > 0) {
          val clamped = millis.coerceAtMost(totalDurationMillis)
          progressReporter?.invoke(
            ConcatenateProgress(
              totalFiles = fileCount,
              totalDurationMillis = totalDurationMillis,
              processedDurationMillis = clamped,
              status = ConcatenateProgress.Status.PROCESSING,
              detail = null,
              strategyDescription = strategyDescription,
              outputFile = outputFile
            )
          )
        }
      }
    }
  }

  private fun parseFfmpegTime(token: String): Long {
    val normalized = token.replace(',', '.')
    val parts = normalized.split(':')
    if (parts.size != 3) return -1L
    val hours = parts[0].toLongOrNull() ?: return -1L
    val minutes = parts[1].toLongOrNull() ?: return -1L
    val secondPart = parts[2]
    val seconds = secondPart.substringBefore('.', secondPart).toLongOrNull() ?: return -1L
    val fraction = secondPart.substringAfter('.', "0")
    val millis = runCatching { ("0.$fraction").toDouble() * 1000 }.getOrDefault(0.0)
    return (((hours * 60) + minutes) * 60 + seconds) * 1000 + millis.toLong()
  }

  private fun probeDurationMillis(file: Path, ffprobePath: String): Long? {
    return try {
      val process = ProcessBuilder(
        ffprobePath,
        "-v",
        "error",
        "-show_entries",
        "format=duration",
        "-of",
        "default=noprint_wrappers=1:nokey=1",
        file.toAbsolutePath().toString()
      ).redirectErrorStream(true).start()

      val output = process.inputStream.bufferedReader().use { it.readLine() }
      if (!process.waitFor(10, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return null
      }
      if (process.exitValue() != 0) return null
      output?.toDoubleOrNull()?.let { (it * 1000).toLong().coerceAtLeast(0L) }
    } catch (ex: Exception) {
      log.warn("读取时长失败：{}", ex.message)
      null
    }
  }

  private fun codecStrategies(format: String): List<CodecStrategy> = when (format) {
    "mp4", "mkv", "mov" -> listOf(
      CodecStrategy(
        description = "封装复制（快速）",
        arguments = listOf("-c", "copy")
      ),
      CodecStrategy(
        description = "H264/AAC 重编码",
        arguments = listOf(
          "-c:v", "libx264",
          "-preset", "veryfast",
          "-crf", "23",
          "-c:a", "aac",
          "-b:a", "192k"
        ),
        requiredEncoders = listOf("libx264", "aac")
      )
    )

    "avi" -> listOf(
      CodecStrategy(
        description = "MPEG4/MP3 重编码",
        arguments = listOf(
          "-c:v", "mpeg4",
          "-qscale:v", "5",
          "-c:a", "libmp3lame",
          "-b:a", "192k"
        ),
        requiredEncoders = listOf("mpeg4", "libmp3lame")
      )
    )

    "webm" -> listOf(
      CodecStrategy(
        description = "VP9/Opus 重编码",
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
        description = "H264/AAC 重编码",
        arguments = listOf(
          "-c:v", "libx264",
          "-preset", "veryfast",
          "-crf", "24",
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

  private fun isEncoderAvailable(ffmpegPath: String, encoder: String): Boolean {
    val key = "${ffmpegPath.lowercase(Locale.getDefault())}|${encoder.lowercase(Locale.getDefault())}"
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

  private data class CodecStrategy(
    val description: String,
    val arguments: List<String>,
    val requiredEncoders: List<String> = emptyList()
  )

  data class ConcatenateProgress(
    val totalFiles: Int,
    val totalDurationMillis: Long,
    val processedDurationMillis: Long,
    val status: Status,
    val detail: String?,
    val strategyDescription: String?,
    val outputFile: Path?
  ) {
    enum class Status {
      PROCESSING,
      COMPLETED,
      FAILED
    }
  }

  data class ConcatenateFailure(
    val directory: Path,
    val reason: String,
    val cause: Throwable?
  )

  data class ConcatenateReport(
    val totalFileCount: Int,
    val outputFile: Path?,
    val strategyDescription: String?,
    val failure: ConcatenateFailure?
  ) {
    val success: Boolean get() = outputFile != null && failure == null

    companion object {
      fun empty() = ConcatenateReport(0, null, null, null)
    }
  }

  companion object {
    private val SUPPORTED_INPUT_EXTENSIONS = setOf(
      "mp4",
      "mkv",
      "mov",
      "avi",
      "webm",
      "flv",
      "ts",
      "m4v",
      "mpg",
      "mpeg",
      "wmv"
    )
    private val SUPPORTED_OUTPUT_FORMATS = setOf("mp4", "mkv", "mov", "avi", "webm", "flv")
  }
}
