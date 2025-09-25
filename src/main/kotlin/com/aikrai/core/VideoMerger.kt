package com.aikrai.core

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
 * 视频合并服务。
 *
 * 职责：
 * - 深度遍历根目录，识别“叶子目录”（不再包含子目录）。
 * - 在叶子目录内收集并按自然序排序 .ts 切片，使用 ffmpeg concat 合并为目标容器（mp4/mkv）。
 * - 并发处理多个叶子目录，报告进度、跳过和失败详情。
 */
class VideoMerger {
  private val log = LoggerFactory.getLogger(VideoMerger::class.java)

  /**
   * 扫描并合并根目录下所有叶子目录中的 .ts 切片。
   *
   * @param rootDir 合并的根目录
   * @param outputFormat 输出容器格式，仅支持 `mp4`/`mkv`
   * @param ffmpegExecutable 可选的 ffmpeg 可执行程序路径（为空则自动解析）
   * @param outputRootDir 可选的统一输出根目录；为空表示输出至原目录
   * @param progressReporter 进度回调（线程安全调用），便于 UI 更新
   * @return 合并结果报告（目录数、成功/跳过/失败统计及明细）
   */
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

  /**
   * 根据 CPU 核心数与任务数估算并发线程数量。
   *
   * @param totalLeafCount 待处理叶子目录总数
   * @return 实际使用的工作线程数，最少 1，最多不超过任务数
   */
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

  /**
   * 处理单个叶子目录：收集 .ts、计算目标文件、调用 ffmpeg 合并并返回结果。
   *
   * @param directory 叶子目录
   * @param normalizedRoot 根目录（已标准化）
   * @param normalizedOutputRoot 目标输出根目录（可空）
   * @param format 目标格式（mp4/mkv）
   * @param ffmpegPath 可执行 ffmpeg 路径
   * @return 单目录任务结果（成功/跳过/失败及信息）
   */
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
          // 当指定统一输出根目录时：
          // - 计算从 root 到当前 ts 目录的相对路径
          // - 去掉最后一层（存放 .ts 的那一层目录）
          // - 在其父目录下输出“<叶子目录名>.<格式>”
          val relative = normalizedRoot.relativize(directory)
          val parent = if (relative.nameCount <= 1) {
            targetRoot
          } else {
            targetRoot.resolve(relative.subpath(0, relative.nameCount - 1))
          }
          Files.createDirectories(parent)
          parent.resolve("${directory.fileName}.$format")
        } ?: run {
          // 未指定统一输出目录时：也遵循“不要再保留 .ts 所在那一层目录”的规则，
          // 将合并后的文件放到父目录下，文件名为“<叶子目录名>.<格式>”。
          val parent = directory.parent ?: directory
          Files.createDirectories(parent)
          parent.resolve("${directory.fileName}.$format")
        }

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

  /**
   * 收集指定根目录下的所有叶子目录（不包含子目录）。
   *
   * @param rootDir 根目录
   * @return 有序的叶子目录列表
   */
  private fun collectLeafDirectories(rootDir: Path): List<Path> {
    Files.walk(rootDir).use { stream ->
      return stream.filter { Files.isDirectory(it) }
        .sorted()
        .filter { isLeafDirectory(it) }
        .toList()
    }
  }

  /**
   * 判断目录是否为叶子目录（其下不包含子目录）。
   *
   * @param directory 待判定目录
   * @return 是叶子目录返回 true
   */
  private fun isLeafDirectory(directory: Path): Boolean {
    Files.list(directory).use { children ->
      return !children.anyMatch { Files.isDirectory(it) }
    }
  }

  /**
   * 收集并自然序排序目录下的所有 .ts 文件。
   *
   * @param directory 目标目录
   * @return 自然序排序后的 .ts 文件列表
   */
  private fun gatherTsFiles(directory: Path): List<Path> {
    Files.list(directory).use { stream ->
      return stream.filter { Files.isRegularFile(it) && it.fileName.toString().lowercase().endsWith(".ts") }
        .sorted(FILE_NAME_COMPARATOR)
        .toList()
    }
  }

  /**
   * 通过 ffmpeg concat 协议合并切片文件。
   *
   * @param tsFiles 按顺序排列的 .ts 切片
   * @param outputFile 目标输出文件
   * @param format 输出格式（影响部分参数，如 mp4 追加 faststart）
   * @param ffmpegPath ffmpeg 可执行路径
   * @throws IllegalStateException ffmpeg 超时或退出码非 0 时抛出
   */
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

  /**
   * 解析 ffmpeg 路径，封装至本类便于测试/替换。
   *
   * @param explicitExecutable 外部显式传入路径
   * @return 最终用于执行的 ffmpeg 路径
   */
  private fun resolveFfmpegExecutable(explicitExecutable: String?): String =
    FfmpegSupport.resolveFfmpegExecutable(explicitExecutable)

  /**
   * 内部任务结果：用于汇总单个目录处理的增量信息与状态。
   */
  private data class TaskOutcome(
    val directory: Path,
    val status: MergeProgress.Status,
    val detail: String?,
    val outputFile: Path?,
    val mergedDelta: Int,
    val skippedDelta: Int,
    val failure: MergeFailure?
  )

  /**
   * 合并任务总报告：包含任务规模、成功/跳过/失败统计与明细。
   */
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

  /**
   * 合并失败详情：记录失败目录与原因。
   */
  data class MergeFailure(
    val directory: Path,
    val reason: String
  )

  /**
   * 合并进度快照：用于 UI 实时反馈。
   */
  data class MergeProgress(
    val totalDirectories: Int,
    val completedDirectories: Int,
    val currentDirectory: Path,
    val status: Status,
    val detail: String?,
    val outputFile: Path?
  ) {
    /**
     * 目录处理状态。
     */
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
    /** 支持输出的容器格式集合 */
    private val SUPPORTED_FORMATS = setOf("mp4", "mkv")
    /** 默认最大并发线程数（当无法获取 CPU 核心数时使用） */
    private const val DEFAULT_MAX_THREADS = 2
    /** 自然排序用的切分正则：连续数字或非数字片段 */
    private val NATURAL_CHUNK_REGEX = Regex("\\d+|\\D+")
    /** 占位路径：用于异常情况下的进度上报 */
    private val UNKNOWN_PATH: Path = Path.of("unknown")
  }
}
