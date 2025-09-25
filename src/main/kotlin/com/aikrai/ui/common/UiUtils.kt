package com.aikrai.ui.common

import javafx.application.Platform
import javafx.scene.control.Alert
import javafx.scene.control.TextArea
import javafx.stage.DirectoryChooser
import javafx.stage.Stage
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * UI 层通用工具方法：日志追加、消息弹窗、目录选择、路径解析等。
 *
 * 注意：该工具类不依赖具体 UI 页面，便于各视图/控制器复用。
 */
object UiUtils {

  /**
   * 追加带时间戳的消息到日志区，并滚动到底部。
   *
   * @param logArea 目标文本区域
   * @param message 需要追加的文本内容
   */
  fun appendLog(logArea: TextArea, message: String) {
    val timestamp = java.time.LocalDateTime.now().format(UiConstants.TIME_FORMATTER)
    Platform.runLater {
      logArea.appendText("[$timestamp] $message\n")
      logArea.scrollTop = Double.MAX_VALUE
    }
  }

  /**
   * 在 JavaFX 线程安全地展示 Alert 弹窗。
   *
   * @param type 弹窗类型
   * @param title 标题
   * @param content 内容
   */
  fun showAlert(type: Alert.AlertType, title: String, content: String) {
    Platform.runLater {
      Alert(type).apply {
        this.title = title
        headerText = null
        contentText = content
      }.showAndWait()
    }
  }

  /**
   * 尝试根据多个候选字符串解析并返回存在的目录。
   *
   * @param candidates 候选路径字符串
   * @return 首个存在的目录 File，若都无效则返回 null
   */
  fun findExistingDirectory(vararg candidates: String?): File? {
    for (candidate in candidates) {
      if (candidate.isNullOrBlank()) continue
      try {
        val path = Path.of(candidate).normalize()
        if (Files.exists(path) && Files.isDirectory(path)) {
          return path.toFile()
        }
      } catch (_: InvalidPathException) {
        // 忽略非法路径
      }
    }
    return null
  }

  /**
   * 安全打开目录选择器，处理非法初始目录导致的异常。
   *
   * @param chooser 目录选择器
   * @param stage 所属窗口
   * @return 用户选择的目录 File，若失败或取消则为 null
   */
  fun showDirectoryChooserSafely(chooser: DirectoryChooser, stage: Stage): File? {
    return try {
      chooser.showDialog(stage)
    } catch (ex: IllegalArgumentException) {
      showAlert(Alert.AlertType.ERROR, "目录选择失败", ex.message ?: "请选择有效的文件夹")
      null
    }
  }

  /**
   * 解析默认根目录（不存在则回退到用户目录）。
   *
   * @return 默认路径字符串
   */
  fun resolveDefaultRootPath(): String {
    return try {
      val defaultPath = Path.of(UiConstants.DEFAULT_ROOT_PATH)
      if (Files.exists(defaultPath) && Files.isDirectory(defaultPath)) {
        defaultPath.toString()
      } else {
        System.getProperty("user.home")
      }
    } catch (_: Exception) {
      System.getProperty("user.home")
    }
  }
}
