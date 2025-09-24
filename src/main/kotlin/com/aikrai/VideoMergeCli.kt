package com.aikrai

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
  val logger = LoggerFactory.getLogger("VideoMergeCli")
  val options = parseOptions(args.toList())

  val rootPath = try {
    (options["--root"]?.let { Path.of(it) } ?: Path.of(DEFAULT_ROOT_PATH)).normalize()
  } catch (ex: InvalidPathException) {
    logger.error("根目录路径无效: {}", ex.message)
    exitProcess(1)
  }

  val format = options["--format"]?.lowercase() ?: DEFAULT_FORMAT
  val ffmpeg = options["--ffmpeg"]

  val outputPath = options["--output"]?.let {
    try {
      Path.of(it).normalize()
    } catch (ex: InvalidPathException) {
      logger.error("输出目录路径无效: {}", ex.message)
      exitProcess(1)
    }
  }

  if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
    logger.error("根目录 {} 不存在或不是目录", rootPath)
    exitProcess(1)
  }

  outputPath?.let { target ->
    if (Files.exists(target) && !Files.isDirectory(target)) {
      logger.error("输出路径 {} 不是目录", target)
      exitProcess(1)
    }
    try {
      Files.createDirectories(target)
    } catch (ex: Exception) {
      logger.error("创建输出目录失败: {}", ex.message)
      exitProcess(1)
    }
  }

  logger.info(
    "开始合并：根目录 {}，目标格式 {}，输出目录 {}",
    rootPath.toAbsolutePath(),
    format,
    outputPath?.toAbsolutePath() ?: "同源目录"
  )

  val merger = VideoMerger()
  val report = try {
    merger.mergeAll(rootPath, format, ffmpeg, outputPath) { progress ->
      val detail = progress.detail?.let { " - $it" } ?: ""
      logger.info(
        "进度 {}/{} {}{}",
        progress.completedDirectories,
        progress.totalDirectories,
        progress.currentDirectory.fileName,
        detail
      )
    }
  } catch (ex: IllegalArgumentException) {
    logger.error("参数错误: {}", ex.message)
    exitProcess(1)
  } catch (ex: Exception) {
    logger.error("执行失败", ex)
    exitProcess(1)
  }

  logger.info(
    "任务完成：扫描 {} 个目录，成功 {}，跳过 {}，失败 {}",
    report.leafDirectoryCount,
    report.mergedDirectoryCount,
    report.skippedDirectoryCount,
    report.failureCount
  )

  if (report.failureCount > 0) {
    report.failureDetails.forEach { failure ->
      logger.error("目录 {} 合并失败：{}", failure.directory, failure.reason)
    }
  }

  exitProcess(if (report.failureCount == 0) 0 else 1)
}

private fun parseOptions(args: List<String>): Map<String, String> {
  val result = mutableMapOf<String, String>()
  var index = 0
  while (index < args.size) {
    val arg = args[index]
    if (arg.startsWith("--")) {
      if (arg.contains('=')) {
        val (key, value) = arg.split('=', limit = 2)
        result[key] = value
      } else {
        if (index + 1 < args.size && !args[index + 1].startsWith("--")) {
          result[arg] = args[index + 1]
          index++
        } else {
          result[arg] = "true"
        }
      }
    }
    index++
  }
  return result
}

private const val DEFAULT_ROOT_PATH = "D:\\download1"
private const val DEFAULT_FORMAT = "mp4"
