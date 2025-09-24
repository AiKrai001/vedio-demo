package com.aikrai

import org.bytedeco.ffmpeg.ffmpeg
import org.bytedeco.javacpp.Loader
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * 视频合并服务：负责扫描叶子目录，按名称排序合并 .ts 切片。
 */
class VideoMerger {
  private val log = LoggerFactory.getLogger(VideoMerger::class.java)

  fun mergeAll(
    rootDir: Path,
    outputFormat: String,
    ffmpegExecutable: String? = null,
    outputRootDir: Path? = null,
    progressReporter: ((MergeProgress) -> Unit)? = null
  ): MergeReport {
    val normalizedRoot = rootDir.toAbsolutePath().normalize()
    val format = outputFormat.lowercase()
    require(format in SUPPORTED_FORMATS) {
      "输出格式仅支持: ${SUPPORTED_FORMATS.joinToString(", ")}"
    }

    if (!Files.exists(normalizedRoot)) {
      log.warn("根目录 {} 不存在，跳过合并", normalizedRoot)
      return MergeReport.empty()
    }

    if (!Files.isDirectory(normalizedRoot)) {
      log.warn("根路径 {} 不是目录，跳过合并", normalizedRoot)
      return MergeReport.empty()
    }

    val normalizedOutputRoot = outputRootDir?.let { raw ->
      val normalized = raw.toAbsolutePath().normalize()
      if (Files.exists(normalized) && !Files.isDirectory(normalized)) {
        throw IllegalArgumentException("输出目录必须是有效的文件夹路径")
      }
      Files.createDirectories(normalized)
      normalized
    }

    val leafDirectories = collectLeafDirectories(normalizedRoot)
    if (leafDirectories.isEmpty()) {
      log.info("根目录 {} 未找到叶子目录，结束", normalizedRoot)
      return MergeReport.empty()
    }

    val ffmpegPath = resolveFfmpegExecutable(ffmpegExecutable)
    log.info("使用 ffmpeg 可执行文件: {}", ffmpegPath)
    normalizedOutputRoot?.let { log.info("目标输出根目录: {}", it) }

    val totalLeafCount = leafDirectories.size
    val workerCount = determineWorkerCount(totalLeafCount)
    log.info("准备并发合并，线程数 {}，待处理目录 {}", workerCount, totalLeafCount)

    var mergedCount = 0
    var skippedCount = 0
    val failures = mutableListOf<MergeFailure>()

    val executor = Executors.newFixedThreadPool(workerCount)
    try {
      val completionService = ExecutorCompletionService<TaskOutcome>(executor)
      leafDirectories.forEach { directory ->
        completionService.submit(Callable {
          processDirectory(directory, normalizedRoot, normalizedOutputRoot, format, ffmpegPath)
        })
      }

      val completedCount = AtomicInteger(0)
      repeat(totalLeafCount) {
        val outcome = try {
          completionService.take().get()
        } catch (ex: InterruptedException) {
          Thread.currentThread().interrupt()
          throw IllegalStateException("合并任务被中断", ex)
        } catch (ex: ExecutionException) {
          val cause = ex.cause ?: ex
          val reason = cause.message ?: cause.javaClass.simpleName
          log.error("并发任务执行失败: {}", reason, cause)
          val failure = MergeFailure(UNKNOWN_PATH, reason)
          failures += failure
          val done = completedCount.incrementAndGet()
          progressReporter?.invoke(
            MergeProgress(
              totalDirectories = totalLeafCount,
              completedDirectories = done,
              currentDirectory = UNKNOWN_PATH,
              status = MergeProgress.Status.FAILED,
              detail = reason,
              outputFile = null
            )
          )
          return@repeat
        }

        mergedCount += outcome.mergedDelta
        skippedCount += outcome.skippedDelta
        outcome.failure?.let { failures += it }

        val done = completedCount.incrementAndGet()
        progressReporter?.invoke(
          MergeProgress(
            totalDirectories = totalLeafCount,
            completedDirectories = done,
            currentDirectory = outcome.directory,
            status = outcome.status,
            detail = outcome.detail,
            outputFile = outcome.outputFile
          )
        )
      }
    } finally {
      executor.shutdown()
    }

    return MergeReport(
      leafDirectoryCount = totalLeafCount,
      mergedDirectoryCount = mergedCount,
      skippedDirectoryCount = skippedCount,
      failureDetails = failures.toList()
    )
  }

  private fun determineWorkerCount(totalLeafCount: Int): Int {
    val processors = try {
      Runtime.getRuntime().availableProcessors()
    } catch (ex: Exception) {
      log.warn("无法读取 CPU 核心数，回退到默认线程数 {}", DEFAULT_MAX_THREADS, ex)
      -1
    }

    val desired = if (processors > 0) processors else DEFAULT_MAX_THREADS
    return desired
      .coerceAtLeast(1)
      .coerceAtMost(totalLeafCount.coerceAtLeast(1))
  }

  private fun processDirectory(
    directory: Path,
    normalizedRoot: Path,
    normalizedOutputRoot: Path?,
    format: String,
    ffmpegPath: String
  ): TaskOutcome {
    var targetFile: Path? = null
    return try {
      val tsFiles = gatherTsFiles(directory)
      if (tsFiles.isEmpty()) {
        log.debug("目录 {} 未发现 .ts 切片，跳过", directory)
        TaskOutcome(
          directory = directory,
          status = MergeProgress.Status.SKIPPED,
          detail = "未发现 .ts 切片",
          outputFile = null,
          mergedDelta = 0,
          skippedDelta = 1,
          failure = null
        )
      } else {
        val computedTarget = normalizedOutputRoot?.let { targetRoot ->
          val relative = normalizedRoot.relativize(directory)
          val parent = if (relative.nameCount <= 1) {
            targetRoot
          } else {
            targetRoot.resolve(relative.subpath(0, relative.nameCount - 1))
          }
          Files.createDirectories(parent)
          parent.resolve("${directory.fileName}.$format")
        } ?: directory.resolve("${directory.fileName}.$format")

        targetFile = computedTarget

        if (Files.exists(computedTarget)) {
          log.info("目录 {} 已存在目标文件 {}，跳过", directory, computedTarget)
          TaskOutcome(
            directory = directory,
            status = MergeProgress.Status.SKIPPED,
            detail = "目标文件已存在",
            outputFile = computedTarget,
            mergedDelta = 0,
            skippedDelta = 1,
            failure = null
          )
        } else {
          runFfmpegConcat(tsFiles, computedTarget, format, ffmpegPath)
          log.info("目录 {} 合并完成，生成 {}", directory, computedTarget)
          TaskOutcome(
            directory = directory,
            status = MergeProgress.Status.MERGED,
            detail = "输出到 ${computedTarget.toAbsolutePath()}",
            outputFile = computedTarget,
            mergedDelta = 1,
            skippedDelta = 0,
            failure = null
          )
        }
      }
    } catch (ex: Exception) {
      val reason = ex.message ?: ex.javaClass.simpleName
      log.error("合并目录 {} 失败: {}", directory, reason, ex)
      targetFile?.let { Files.deleteIfExists(it) }
      TaskOutcome(
        directory = directory,
        status = MergeProgress.Status.FAILED,
        detail = reason,
        outputFile = targetFile,
        mergedDelta = 0,
        skippedDelta = 0,
        failure = MergeFailure(directory, reason)
      )
    }
  }

  private fun collectLeafDirectories(rootDir: Path): List<Path> {
    Files.walk(rootDir).use { stream ->
      return stream.filter { Files.isDirectory(it) }
        .sorted()
        .filter { isLeafDirectory(it) }
        .toList()
    }
  }

  private fun isLeafDirectory(directory: Path): Boolean {
    Files.list(directory).use { children ->
      return !children.anyMatch { Files.isDirectory(it) }
    }
  }

  private fun gatherTsFiles(directory: Path): List<Path> {
    Files.list(directory).use { stream ->
      return stream.filter { Files.isRegularFile(it) && it.fileName.toString().lowercase().endsWith(".ts") }
        .sorted(FILE_NAME_COMPARATOR)
        .toList()
    }
  }

  private fun runFfmpegConcat(tsFiles: List<Path>, outputFile: Path, format: String, ffmpegPath: String) {
    val concatFile = Files.createTempFile("video-merge-", ".txt")
    try {
      val lines = tsFiles.map { path ->
        val normalized = path.toAbsolutePath().normalize().toString().replace("\\", "\\\\")
        "file '$normalized'"
      }
      Files.write(concatFile, lines, StandardCharsets.UTF_8)

      Files.createDirectories(outputFile.parent)

      val command = mutableListOf(
        ffmpegPath,
        "-hide_banner",
        "-loglevel",
        "info",
        "-f",
        "concat",
        "-safe",
        "0",
        "-i",
        concatFile.toString(),
        "-c",
        "copy"
      )
      if (format == "mp4") {
        command.addAll(listOf("-bsf:a", "aac_adtstoasc", "-movflags", "+faststart"))
      }
      command.add(outputFile.toString())

      val process = ProcessBuilder(command)
        .redirectErrorStream(true)
        .start()

      process.inputStream.bufferedReader().useLines { outputLines ->
        outputLines.forEach { line ->
          if (line.isNotBlank()) {
            log.info("[ffmpeg] {}", line)
          }
        }
      }

      if (!process.waitFor(1, TimeUnit.HOURS)) {
        process.destroyForcibly()
        throw IllegalStateException("ffmpeg 处理超时")
      }

      val exitCode = process.exitValue()
      if (exitCode != 0) {
        throw IllegalStateException("ffmpeg 退出码 $exitCode")
      }
    } finally {
      Files.deleteIfExists(concatFile)
    }
  }

  private fun resolveFfmpegExecutable(explicitExecutable: String?): String {
    explicitExecutable?.takeIf { it.isNotBlank() }?.let { return it }
    System.getenv("FFMPEG_PATH")?.takeIf { it.isNotBlank() }?.let { return it }

    return try {
      Loader.load(ffmpeg::class.java)
    } catch (ex: Throwable) {
      log.warn("未能加载内置 ffmpeg，可执行程序将回退为命令 {}", DEFAULT_FFMPEG_COMMAND, ex)
      DEFAULT_FFMPEG_COMMAND
    }
  }

  private data class TaskOutcome(
    val directory: Path,
    val status: MergeProgress.Status,
    val detail: String?,
    val outputFile: Path?,
    val mergedDelta: Int,
    val skippedDelta: Int,
    val failure: MergeFailure?
  )

  data class MergeReport(
    val leafDirectoryCount: Int,
    val mergedDirectoryCount: Int,
    val skippedDirectoryCount: Int,
    val failureDetails: List<MergeFailure>
  ) {
    val failureCount: Int get() = failureDetails.size

    companion object {
      fun empty() = MergeReport(0, 0, 0, emptyList())
    }
  }

  data class MergeFailure(
    val directory: Path,
    val reason: String
  )

  data class MergeProgress(
    val totalDirectories: Int,
    val completedDirectories: Int,
    val currentDirectory: Path,
    val status: Status,
    val detail: String?,
    val outputFile: Path?
  ) {
    enum class Status {
      MERGED,
      SKIPPED,
      FAILED
    }
  }

  private fun naturalSortKey(name: String): List<Any> = NATURAL_CHUNK_REGEX.findAll(name)
    .map {
      it.value.toLongOrNull() ?: it.value.lowercase()
    }
    .toList()

  private val FILE_NAME_COMPARATOR = Comparator<Path> { first, second ->
    val firstKey = naturalSortKey(first.fileName.toString())
    val secondKey = naturalSortKey(second.fileName.toString())
    val limit = min(firstKey.size, secondKey.size)

    for (index in 0 until limit) {
      val firstSegment = firstKey[index]
      val secondSegment = secondKey[index]
      val compare = when {
        firstSegment is Long && secondSegment is Long -> firstSegment.compareTo(secondSegment)
        firstSegment is Long -> -1
        secondSegment is Long -> 1
        else -> (firstSegment as String).compareTo(secondSegment as String)
      }
      if (compare != 0) {
        return@Comparator compare
      }
    }

    firstKey.size.compareTo(secondKey.size)
  }

  companion object {
    private val SUPPORTED_FORMATS = setOf("mp4", "mkv")
    private const val DEFAULT_FFMPEG_COMMAND = "ffmpeg"
    private const val DEFAULT_MAX_THREADS = 2
    private val NATURAL_CHUNK_REGEX = Regex("\\d+|\\D+")
    private val UNKNOWN_PATH: Path = Path.of("unknown")
  }
}
