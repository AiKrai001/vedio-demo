package com.aikrai.ui.common

import java.time.format.DateTimeFormatter

/**
 * UI 层常量定义。
 *
 * 说明：仅存放与 UI 展示相关的常量，如默认格式、可选格式与时间格式化器，
 * 避免与核心业务常量混淆。
 */
object UiConstants {
  /** 默认根目录（留空表示回退到用户目录） */
  const val DEFAULT_ROOT_PATH: String = ""

  /** 默认视频输出格式 */
  const val DEFAULT_FORMAT: String = "mp4"

  /** 转换功能支持的容器格式（与 VideoConverter 支持集合保持一致） */
  val CONVERSION_FORMATS: List<String> = listOf("mp4", "mkv", "mov", "avi", "webm", "flv")

  /** 日志时间格式 */
  val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
}
