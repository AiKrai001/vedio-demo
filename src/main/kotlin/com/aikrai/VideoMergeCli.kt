package com.aikrai

import com.aikrai.core.VideoConverter
import com.aikrai.core.VideoMerger
import java.nio.file.Path

/**
 * 简单命令行入口：支持 ts 目录合并与视频格式转换。
 *
 * 用法示例：
 * - 合并：
 *   java -jar app.jar merge --root D:/videos --format mp4 --output D:/out --ffmpeg C:/ffmpeg/bin/ffmpeg.exe
 * - 转换：
 *   java -jar app.jar convert --inputs a.mp4 b.mkv --format webm --output D:/out
 */
fun main(args: Array<String>) {
  if (args.isEmpty() || args[0] in listOf("-h", "--help", "help")) {
    printHelp()
    return
  }

  when (args[0].lowercase()) {
    "merge" -> runMerge(args.drop(1))
    "convert" -> runConvert(args.drop(1))
    else -> {
      println("未知命令: ${args[0]}")
      printHelp()
    }
  }
}

private fun runMerge(args: List<String>) {
  val options = parseOptions(args)
  val root = options["--root"]
  if (root == null) {
    println("缺少 --root 参数")
    return
  }
  val format = options["--format"] ?: "mp4"
  val output = options["--output"]
  val ffmpeg = options["--ffmpeg"]

  val merger = VideoMerger()
  println("[merge] root=$root format=$format output=${output ?: "同源目录"} ffmpeg=${ffmpeg ?: "auto"}")
  val report = merger.mergeAll(
    rootDir = Path.of(root),
    outputFormat = format,
    ffmpegExecutable = ffmpeg,
    outputRootDir = output?.let { Path.of(it) }
  ) { progress ->
    val suffix = progress.detail?.let { " - $it" } ?: ""
    println("[${progress.completedDirectories}/${progress.totalDirectories}] ${progress.currentDirectory.fileName} ${progress.status}$suffix")
  }

  println("合并完成：共 ${report.leafDirectoryCount} 个目录，成功 ${report.mergedDirectoryCount}，跳过 ${report.skippedDirectoryCount}，失败 ${report.failureCount}")
  if (report.failureCount > 0) {
    report.failureDetails.forEach { f -> println("  失败目录 ${f.directory}: ${f.reason}") }
  }
}

private fun runConvert(args: List<String>) {
  val options = parseOptions(args)
  val inputs = options["--inputs"]?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() }
  if (inputs.isNullOrEmpty()) {
    println("缺少 --inputs 参数（空格分隔多个文件），或文件列表为空")
    return
  }
  val format = options["--format"] ?: "mp4"
  val output = options["--output"]
  val ffmpeg = options["--ffmpeg"]

  val converter = VideoConverter()
  println("[convert] inputs=${inputs.size} format=$format output=${output ?: "同源目录"} ffmpeg=${ffmpeg ?: "auto"}")
  val report = converter.convertAll(
    inputs = inputs.map { Path.of(it) },
    targetFormat = format,
    ffmpegExecutable = ffmpeg,
    outputDirectory = output?.let { Path.of(it) }
  ) { progress ->
    val suffix = progress.detail?.let { " - $it" } ?: ""
    println("[${progress.completedFiles}/${progress.totalFiles}] ${progress.currentFile.fileName} ${progress.status}$suffix")
  }

  println("转换完成：共 ${report.totalFileCount} 个文件，成功 ${report.successCount}，失败 ${report.failureCount}")
  if (report.failureCount > 0) {
    report.failureDetails.forEach { f -> println("  失败文件 ${f.inputFile.fileName}: ${f.reason}") }
  }
}

/**
 * 解析命令行形如 --key value 的参数。
 * 对于 --inputs 支持多值：遇到下一个 --key 前的所有值都归到 inputs，并以换行拼接。
 */
private fun parseOptions(args: List<String>): Map<String, String> {
  val map = mutableMapOf<String, String>()
  var i = 0
  while (i < args.size) {
    val token = args[i]
    if (!token.startsWith("--")) { i++; continue }
    val key = token
    val values = mutableListOf<String>()
    var j = i + 1
    while (j < args.size && !args[j].startsWith("--")) { values += args[j]; j++ }
    if (key == "--inputs" && values.isNotEmpty()) {
      map[key] = values.joinToString("\n")
    } else if (values.isNotEmpty()) {
      map[key] = values.first()
    } else {
      map[key] = "true"
    }
    i = j
  }
  return map
}

private fun printHelp() {
  println(
    """
    用法:
      merge   --root <目录> [--format mp4|mkv] [--output <目录>] [--ffmpeg <路径>]
      convert --inputs <文件...> [--format <容器>] [--output <目录>] [--ffmpeg <路径>]

    说明:
      --root     合并的根目录（递归处理其下叶子目录）
      --inputs   待转换文件列表，空格分隔多个
      --format   输出容器格式，默认 mp4
      --output   统一输出目录，不指定则回写到源目录
      --ffmpeg   ffmpeg 可执行路径，不指定则自动解析
    """.trimIndent()
  )
}
