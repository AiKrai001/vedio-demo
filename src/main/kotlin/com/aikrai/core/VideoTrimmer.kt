package com.aikrai.core

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Collectors

/**
 * 视频批量裁剪服务。
 *
 * 功能点：
 * - 支持按文件夹批量截取视频的头部、尾部及组合片段；
 * - 默认输出至根目录下的 res 文件夹，可自定义输出根目录；
 * - 使用 CPU 核心数构建线程池并行处理；
 * - 提供进度回调与详细报告，便于 UI 展示。
 */
class VideoTrimmer {
  private val log = LoggerFactory.getLogger(VideoTrimmer::class.java)

  /** 裁剪模式：支持多种常见场景。 */
  enum class TrimMode {
    /** 移除开头指定时长 */
    HEAD,

    /** 移除结尾指定时长 */
    TAIL,

    /** 同时移除开头与结尾的指定时长 */
    HEAD_TAIL,

    /** 仅保留给定时间范围的片段 */
    KEEP_RANGE
  }

  /**
   * 批量裁剪指定目录下所有支持的视频文件。
   *
   * @param rootDir 根目录
   * @param mode 裁剪模式
   * @param primaryDurationMillis 主参数：对 HEAD/TAIL 表示裁剪时长、KEEP_RANGE 表示起始时间
   * @param secondaryDurationMillis 辅参数：HEAD_TAIL 表示尾部裁剪时长、KEEP_RANGE 表示结束时间
   * @param ffmpegExecutable 可选 ffmpeg 路径，留空自动解析
   * @param outputDirectory 可选输出根目录，留空默认 root/res
   * @param progressReporter 进度回调
   * @return 任务报告，包含成功/跳过/失败统计与明细
   */
  fun trimAll(
    rootDir: Path,
    mode: TrimMode,
    primaryDurationMillis: Long,
    secondaryDurationMillis: Long = 0L,
    ffmpegExecutable: String? = null,
    outputDirectory: Path? = null,
    progressReporter: ((TrimProgress) -> Unit)? = null
  ): TrimReport {
    require(primaryDurationMillis >= 0) { "时间参数必须为非负值" }
    require(secondaryDurationMillis >= 0) { "时间参数必须为非负值" }
    when (mode) {
      TrimMode.HEAD -> require(primaryDurationMillis > 0) { "裁剪头部时长必须大于 0" }
      TrimMode.TAIL -> require(primaryDurationMillis > 0) { "裁剪尾部时长必须大于 0" }
      TrimMode.HEAD_TAIL -> {
        require(primaryDurationMillis > 0) { "头部裁剪时长必须大于 0" }
        require(secondaryDurationMillis > 0) { "尾部裁剪时长必须大于 0" }
      }
      TrimMode.KEEP_RANGE -> {
        require(secondaryDurationMillis > primaryDurationMillis) { "结束时间必须大于开始时间" }
      }
    }

    val normalizedRoot = rootDir.toAbsolutePath().normalize()
    if (!Files.exists(normalizedRoot) || !Files.isDirectory(normalizedRoot)) {
      log.warn("根目录 {} 不存在或不是有效文件夹，直接返回", normalizedRoot)
      return TrimReport.empty()
    }

    val effectiveOutputRoot = outputDirectory?.let { raw ->
      val normalized = raw.toAbsolutePath().normalize()
      if (Files.exists(normalized) && !Files.isDirectory(normalized)) {
        throw IllegalArgumentException("输出目录必须是有效的文件夹路径")
      }
      Files.createDirectories(normalized)
      normalized
    } ?: run {
      val defaultDir = normalizedRoot.resolve("res").normalize()
      Files.createDirectories(defaultDir)
      defaultDir
    }

    val outputRootNormalized = effectiveOutputRoot.toAbsolutePath().normalize()

    val videoFiles = Files.walk(normalizedRoot).use { stream ->
      stream.filter { Files.isRegularFile(it) }
        .map { it.toAbsolutePath().normalize() }
        .filter { !it.startsWith(outputRootNormalized) }
        .filter { hasSupportedExtension(it) }
        .sorted()
        .collect(Collectors.toList())
    }

    if (videoFiles.isEmpty()) {
      log.info("目录 {} 下未找到可裁剪的视频文件", normalizedRoot)
      return TrimReport.empty()
    }

    val ffmpegPath = FfmpegSupport.resolveFfmpegExecutable(ffmpegExecutable)
    val ffprobePath = FfmpegSupport.resolveFfprobeExecutable(ffmpegExecutable)

    val workerCount = determineWorkerCount(videoFiles.size)
    log.info(
      "准备裁剪 {} 个视频，线程数 {}，输出根目录 {}",
      videoFiles.size,
      workerCount,
      outputRootNormalized
    )

    val executor = Executors.newFixedThreadPool(workerCount)
    var trimmedCount = 0
    var skippedCount = 0
    val failures = mutableListOf<TrimFailure>()

    try {
      val completionService = ExecutorCompletionService<TrimOutcome>(executor)
      videoFiles.forEach { file ->
        completionService.submit(Callable {
          processSingleFile(
            input = file,
            normalizedRoot = normalizedRoot,
            outputRoot = outputRootNormalized,
            mode = mode,
            primaryDurationMillis = primaryDurationMillis,
            secondaryDurationMillis = secondaryDurationMillis,
            ffmpegPath = ffmpegPath,
            ffprobePath = ffprobePath
          )
        })
      }

      val completed = AtomicInteger(0)
      repeat(videoFiles.size) {
        val outcome = try {
          completionService.take().get()
        } catch (ex: InterruptedException) {
          Thread.currentThread().interrupt()
          throw IllegalStateException("裁剪任务被中断", ex)
        } catch (ex: ExecutionException) {
          val cause = ex.cause ?: ex
          val reason = cause.message ?: cause.javaClass.simpleName
          log.error("并发裁剪任务失败: {}", reason, cause)
          val failure = TrimOutcome(
            inputFile = Path.of("unknown"),
            status = TrimProgress.Status.FAILED,
            detail = reason,
            outputFile = null
          )
          val done = completed.incrementAndGet()
          progressReporter?.invoke(
            TrimProgress(
              totalFiles = videoFiles.size,
              completedFiles = done,
              currentFile = failure.inputFile,
              status = failure.status,
              detail = failure.detail,
              outputFile = failure.outputFile
            )
          )
          failures += TrimFailure(failure.inputFile, reason)
          return@repeat
        }

        when (outcome.status) {
          TrimProgress.Status.TRIMMED -> trimmedCount += 1
          TrimProgress.Status.SKIPPED -> skippedCount += 1
          TrimProgress.Status.FAILED -> failures += TrimFailure(outcome.inputFile, outcome.detail ?: "未知原因")
        }

        val done = completed.incrementAndGet()
        progressReporter?.invoke(
          TrimProgress(
            totalFiles = videoFiles.size,
            completedFiles = done,
            currentFile = outcome.inputFile,
            status = outcome.status,
            detail = outcome.detail,
            outputFile = outcome.outputFile
          )
        )
      }
    } finally {
      executor.shutdown()
    }

    return TrimReport(
      totalFileCount = videoFiles.size,
      trimmedCount = trimmedCount,
      skippedCount = skippedCount,
      failureDetails = failures.toList()
    )
  }

  /**
   * 处理单个视频文件，返回裁剪结果。
   */
  private fun processSingleFile(
    input: Path,
    normalizedRoot: Path,
    outputRoot: Path,
    mode: TrimMode,
    primaryDurationMillis: Long,
    secondaryDurationMillis: Long,
    ffmpegPath: String,
    ffprobePath: String
  ): TrimOutcome {
    val durationMillis = probeDurationMillis(input, ffprobePath)
    val normalizedDuration = durationMillis ?: -1L

    if (mode == TrimMode.HEAD && normalizedDuration > 0 && primaryDurationMillis >= normalizedDuration) {
      return TrimOutcome(
        inputFile = input,
        status = TrimProgress.Status.SKIPPED,
        detail = "视频时长不足以裁剪指定头部长度",
        outputFile = null
      )
    }

    val outputFile = resolveOutputFile(input, normalizedRoot, outputRoot)

    return try {
      when (mode) {
        TrimMode.HEAD -> {
          trimHead(input, outputFile, ffmpegPath, primaryDurationMillis)
        }
        TrimMode.TAIL -> {
          if (normalizedDuration <= 0) {
            return TrimOutcome(
              inputFile = input,
              status = TrimProgress.Status.SKIPPED,
              detail = "未能获取视频时长，无法裁剪尾部",
              outputFile = null
            )
          }
          val keepDuration = normalizedDuration - primaryDurationMillis
          if (keepDuration <= 0) {
            return TrimOutcome(
              inputFile = input,
              status = TrimProgress.Status.SKIPPED,
              detail = "视频时长不足以裁剪指定尾部长度",
              outputFile = null
            )
          }
          trimTail(input, outputFile, ffmpegPath, keepDuration)
        }
        TrimMode.HEAD_TAIL -> {
          if (normalizedDuration <= 0) {
            return TrimOutcome(
              inputFile = input,
              status = TrimProgress.Status.SKIPPED,
              detail = "未能获取视频时长，无法同时裁剪头尾",
              outputFile = null
            )
          }
          val keepDuration = normalizedDuration - primaryDurationMillis - secondaryDurationMillis
          if (keepDuration <= 0) {
            return TrimOutcome(
              inputFile = input,
              status = TrimProgress.Status.SKIPPED,
              detail = "裁剪后剩余时长不大于 0，已跳过",
              outputFile = null
            )
          }
          trimSegment(input, outputFile, ffmpegPath, primaryDurationMillis, keepDuration)
        }
        TrimMode.KEEP_RANGE -> {
          val start = primaryDurationMillis
          val end = secondaryDurationMillis
          if (end <= start) {
            return TrimOutcome(
              inputFile = input,
              status = TrimProgress.Status.SKIPPED,
              detail = "结束时间必须大于开始时间",
              outputFile = null
            )
          }
          if (normalizedDuration > 0 && end > normalizedDuration) {
            return TrimOutcome(
              inputFile = input,
              status = TrimProgress.Status.SKIPPED,
              detail = "结束时间超出视频总时长",
              outputFile = null
            )
          }
          val keepDuration = end - start
          if (keepDuration <= 0) {
            return TrimOutcome(
              inputFile = input,
              status = TrimProgress.Status.SKIPPED,
              detail = "保留时长不大于 0，已跳过",
              outputFile = null
            )
          }
          trimSegment(input, outputFile, ffmpegPath, start, keepDuration)
        }
      }
      TrimOutcome(
        inputFile = input,
        status = TrimProgress.Status.TRIMMED,
        detail = "裁剪完成",
        outputFile = outputFile
      )
    } catch (ex: Exception) {
      Files.deleteIfExists(outputFile)
      TrimOutcome(
        inputFile = input,
        status = TrimProgress.Status.FAILED,
        detail = ex.message ?: ex.javaClass.simpleName,
        outputFile = null
      )
    }
  }

  /**
   * 移除开头片段。
   */
  private fun trimHead(input: Path, output: Path, ffmpegPath: String, trimDurationMillis: Long) {
    val command = listOf(
      ffmpegPath,
      "-hide_banner",
      "-loglevel", "error",
      "-y",
      "-ss", formatDuration(trimDurationMillis),
      "-i", input.toString(),
      "-c", "copy",
      "-avoid_negative_ts", "make_zero",
      output.toString()
    )
    runProcess(command, input, "裁剪开头失败")
  }

  /**
   * 移除尾部片段，保留指定时长。
   */
  private fun trimTail(input: Path, output: Path, ffmpegPath: String, keepDurationMillis: Long) {
    if (keepDurationMillis <= 0) {
      throw IllegalArgumentException("裁剪后剩余时长必须大于 0")
    }
    val command = listOf(
      ffmpegPath,
      "-hide_banner",
      "-loglevel", "error",
      "-y",
      "-i", input.toString(),
      "-t", formatDuration(keepDurationMillis),
      "-c", "copy",
      output.toString()
    )
    runProcess(command, input, "裁剪尾部失败")
  }

  /**
   * 提取从指定起点开始、持续指定时长的片段。
   */
  private fun trimSegment(
    input: Path,
    output: Path,
    ffmpegPath: String,
    startOffsetMillis: Long,
    keepDurationMillis: Long
  ) {
    if (keepDurationMillis <= 0) {
      throw IllegalArgumentException("保留时长必须大于 0")
    }
    val command = mutableListOf(
      ffmpegPath,
      "-hide_banner",
      "-loglevel", "error",
      "-y"
    )
    if (startOffsetMillis > 0) {
      command += listOf("-ss", formatDuration(startOffsetMillis))
    }
    command += listOf(
      "-i", input.toString(),
      "-t", formatDuration(keepDurationMillis),
      "-c", "copy",
      "-avoid_negative_ts", "make_zero",
      output.toString()
    )
    runProcess(command, input, "裁剪片段失败")
  }

  /**
   * 运行 ffmpeg 命令并处理超时/非零退出码。
   */
  private fun runProcess(command: List<String>, input: Path, errorPrefix: String) {
    val process = ProcessBuilder(command)
      .redirectErrorStream(true)
      .start()

    val output = process.inputStream.bufferedReader().use { it.readText() }
    if (!process.waitFor(1, TimeUnit.HOURS)) {
      process.destroyForcibly()
      throw IllegalStateException("$errorPrefix：ffmpeg 处理超时")
    }
    val exitCode = process.exitValue()
    if (exitCode != 0) {
      log.error("{} - 输入文件 {}，退出码 {}\n{}", errorPrefix, input, exitCode, output)
      throw IllegalStateException("$errorPrefix：ffmpeg 退出码 $exitCode")
    }
  }

  /**
   * 解析 ffprobe 时长（毫秒）。
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
      val output = process.inputStream.bufferedReader().use { it.readText().trim() }
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
   * 构建输出文件路径，保持相对目录结构，必要时追加序号避免覆盖。
   */
  private fun resolveOutputFile(input: Path, normalizedRoot: Path, outputRoot: Path): Path {
    val parent = input.parent ?: normalizedRoot
    val relative = if (parent.startsWith(normalizedRoot)) {
      normalizedRoot.relativize(parent)
    } else {
      null
    }
    val targetDir = when {
      relative == null -> outputRoot
      relative.toString().isEmpty() -> outputRoot
      else -> outputRoot.resolve(relative)
    }
    Files.createDirectories(targetDir)

    val fileName = input.fileName?.toString() ?: input.toString()
    val base = fileName.substringBeforeLast('.', fileName)
    val extension = fileName.substringAfterLast('.', "mp4")
    var candidate = targetDir.resolve("$base.$extension").normalize()
    var index = 1
    val inputNormalized = input.toAbsolutePath().normalize()
    while (Files.exists(candidate) || candidate == inputNormalized) {
      candidate = targetDir.resolve("$base-$index.$extension").normalize()
      index += 1
    }
    return candidate
  }

  private fun hasSupportedExtension(path: Path): Boolean {
    val name = path.fileName?.toString() ?: return false
    val ext = name.substringAfterLast('.', "").lowercase(Locale.getDefault())
    return ext in SUPPORTED_EXTENSIONS
  }

  private fun determineWorkerCount(taskCount: Int): Int {
    val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    return cores.coerceAtMost(taskCount.coerceAtLeast(1))
  }

  private fun formatDuration(millis: Long): String {
    val clamped = millis.coerceAtLeast(0L)
    val hours = clamped / 3_600_000
    val minutes = (clamped % 3_600_000) / 60_000
    val seconds = (clamped % 60_000) / 1_000
    val ms = clamped % 1_000
    return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, seconds, ms)
  }

  /**
   * 单文件裁剪内部结果。
   */
  private data class TrimOutcome(
    val inputFile: Path,
    val status: TrimProgress.Status,
    val detail: String?,
    val outputFile: Path?
  )

  /**
   * 裁剪失败详情。
   */
  data class TrimFailure(
    val inputFile: Path,
    val reason: String
  )

  /**
   * 进度快照。
   */
  data class TrimProgress(
    val totalFiles: Int,
    val completedFiles: Int,
    val currentFile: Path,
    val status: Status,
    val detail: String?,
    val outputFile: Path?
  ) {
    enum class Status {
      TRIMMED,
      SKIPPED,
      FAILED
    }
  }

  /**
   * 总体报告。
   */
  data class TrimReport(
    val totalFileCount: Int,
    val trimmedCount: Int,
    val skippedCount: Int,
    val failureDetails: List<TrimFailure>
  ) {
    val failureCount: Int get() = failureDetails.size

    companion object {
      fun empty() = TrimReport(0, 0, 0, emptyList())
    }
  }

  companion object {
    private val SUPPORTED_EXTENSIONS = setOf(
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
  }
}
