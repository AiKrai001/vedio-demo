package com.aikrai.core

import org.bytedeco.ffmpeg.ffmpeg
import org.bytedeco.ffmpeg.ffprobe
import org.bytedeco.javacpp.Loader
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * 提供 ffmpeg/ffprobe 路径解析能力，便于在不同模块共享。
 */
/**
 * ffmpeg/ffprobe 可执行路径解析工具。
 *
 * 设计目标：
 * - 统一解析优先级：显式入参 > 环境变量 > 内置 Loader > 系统命令名回退。
 * - 跨平台兼容：Windows/Unix 均可正常定位，必要时从同目录推导兄弟可执行文件。
 */
object FfmpegSupport {
  private val log = LoggerFactory.getLogger(FfmpegSupport::class.java)
  /** 系统 PATH 中的 ffmpeg 命令名（当无法定位内置二进制时回退使用） */
  private const val DEFAULT_FFMPEG_COMMAND = "ffmpeg"
  /** 系统 PATH 中的 ffprobe 命令名（当无法定位内置二进制时回退使用） */
  private const val DEFAULT_FFPROBE_COMMAND = "ffprobe"

  /**
   * 解析 ffmpeg 可执行程序路径。
   *
   * @param explicitExecutable 外部显式传入的 ffmpeg 路径，优先使用
   * @return 可用于 `ProcessBuilder` 调用的 ffmpeg 命令/路径
   */
  fun resolveFfmpegExecutable(explicitExecutable: String?): String {
    explicitExecutable?.takeIf { it.isNotBlank() }?.let { return it }
    System.getenv("FFMPEG_PATH")?.takeIf { it.isNotBlank() }?.let { return it }

    return try {
      Loader.load(ffmpeg::class.java)
    } catch (ex: Throwable) {
      log.warn("未能加载内置 ffmpeg，可执行程序将回退为命令 {}", DEFAULT_FFMPEG_COMMAND, ex)
      DEFAULT_FFMPEG_COMMAND
    }
  }

  /**
   * 解析 ffprobe 可执行程序路径。
   *
   * 解析顺序：优先根据显式传入的 ffmpeg 路径尝试推导同目录的 ffprobe；
   * 其次读取 FFPROBE_PATH 环境变量；最后尝试加载内置 ffprobe 或回退为命令名。
   *
   * @param explicitFfmpegExecutable 若提供，则尝试基于其推导同目录的 ffprobe
   * @return 可用于 `ProcessBuilder` 调用的 ffprobe 命令/路径
   */
  fun resolveFfprobeExecutable(explicitFfmpegExecutable: String?): String {
    explicitFfmpegExecutable?.takeIf { it.isNotBlank() }?.let { explicit ->
      runCatching { deriveSiblingExecutable(Path.of(explicit), "ffprobe") }
        .getOrNull()
        ?.let { return it }
    }
    System.getenv("FFPROBE_PATH")?.takeIf { it.isNotBlank() }?.let { return it }

    return try {
      Loader.load(ffprobe::class.java)
    } catch (ex: Throwable) {
      log.warn("未能加载内置 ffprobe，可执行程序将回退为命令 {}", DEFAULT_FFPROBE_COMMAND, ex)
      DEFAULT_FFPROBE_COMMAND
    }
  }

  /**
   * 在与给定 ffmpeg 可执行文件相同目录下，推导名为 `targetName` 的兄弟可执行文件路径。
   *
   * @param ffmpegPath 已解析出的 ffmpeg 路径
   * @param targetName 目标可执行文件基础名（如 "ffprobe"）
   * @return 若存在，则返回完整路径，否则返回 null
   */
  private fun deriveSiblingExecutable(ffmpegPath: Path, targetName: String): String? {
    val parent = ffmpegPath.parent ?: return null
    val sourceName = ffmpegPath.fileName.toString()
    val extension = if (sourceName.endsWith(".exe", ignoreCase = true)) ".exe" else ""
    val candidates = buildList {
      if (sourceName.contains("ffmpeg", ignoreCase = true)) {
        add(sourceName.replace("ffmpeg", targetName, ignoreCase = true))
      }
      add("$targetName$extension")
    }

    for (candidate in candidates.distinct()) {
      val resolved = parent.resolve(candidate)
      if (Files.exists(resolved) && Files.isRegularFile(resolved)) {
        return resolved.toString()
      }
    }
    return null
  }
}
