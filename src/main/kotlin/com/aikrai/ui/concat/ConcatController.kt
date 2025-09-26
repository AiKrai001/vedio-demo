package com.aikrai.ui.concat

import com.aikrai.core.VideoConcatenator
import com.aikrai.ui.common.UiConstants
import com.aikrai.ui.common.UiUtils.appendLog
import com.aikrai.ui.common.UiUtils.showAlert
import javafx.concurrent.Task
import javafx.scene.control.*
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * 视频拼接页控制器：负责表单校验、任务管理与日志/进度联动。
 */
class ConcatController(private val videoConcatenator: VideoConcatenator) {

  /** 当前正在运行的拼接任务（避免重复启动） */
  private var currentTask: Task<VideoConcatenator.ConcatenateReport>? = null

  /**
   * 根据输入框的目录刷新文件列表预览。
   */
  fun refreshFileList(directoryField: TextField, fileList: ListView<Path>) {
    val raw = directoryField.text.trim()
    if (raw.isEmpty()) {
      fileList.items.clear()
      return
    }
    val directory = try {
      Path.of(raw).normalize()
    } catch (_: InvalidPathException) {
      fileList.items.clear()
      return
    }
    if (!Files.exists(directory) || !Files.isDirectory(directory)) {
      fileList.items.clear()
      return
    }
    val files = videoConcatenator.collectVideoFiles(directory)
    fileList.items.setAll(files)
  }

  /**
   * 启动拼接流程：校验输入并将耗时任务交由后台线程执行。
   */
  fun startConcatenate(
    directoryField: TextField,
    formatCombo: ComboBox<String>,
    ffmpegField: TextField,
    fileList: ListView<Path>,
    logArea: TextArea,
    progressBar: ProgressBar,
    statusLabel: Label,
    startButton: Button
  ) {
    if (currentTask?.isRunning == true) {
      showAlert(Alert.AlertType.INFORMATION, "任务运行中", "请等待当前拼接任务完成。")
      return
    }

    val directory = try {
      Path.of(directoryField.text.trim()).normalize()
    } catch (ex: InvalidPathException) {
      showAlert(Alert.AlertType.ERROR, "路径无效", "请输入合法的目录路径。\n${ex.message}")
      return
    }

    if (!Files.exists(directory) || !Files.isDirectory(directory)) {
      showAlert(Alert.AlertType.ERROR, "路径不存在", "请选择有效的文件夹。")
      return
    }

    val files = videoConcatenator.collectVideoFiles(directory)
    if (files.isEmpty()) {
      showAlert(Alert.AlertType.WARNING, "无可拼接文件", "该目录下未找到支持的视频文件。")
      fileList.items.clear()
      return
    }

    val format = formatCombo.value ?: UiConstants.DEFAULT_FORMAT
    val ffmpegPath = ffmpegField.text.trim().takeIf { it.isNotEmpty() }

    fileList.items.setAll(files)
    appendLog(
      logArea,
      "开始拼接：目录 ${directory.toAbsolutePath()}，目标格式 $format，文件数 ${files.size}"
    )

    val task = object : Task<VideoConcatenator.ConcatenateReport>() {
      override fun call(): VideoConcatenator.ConcatenateReport {
        updateProgress(0.0, 1.0)
        updateMessage("任务初始化…")
        return videoConcatenator.concatenateDirectory(directory, format, ffmpegPath) { progress ->
          if (progress.totalDurationMillis > 0) {
            val denom = progress.totalDurationMillis.toDouble().coerceAtLeast(1.0)
            val numer = progress.processedDurationMillis.coerceAtLeast(0L).toDouble().coerceAtMost(denom)
            updateProgress(numer, denom)
          }

          progress.detail?.let { detail ->
            appendLog(logArea, detail)
          }

          when (progress.status) {
            VideoConcatenator.ConcatenateProgress.Status.PROCESSING -> {
              updateMessage(progress.detail ?: "处理中…")
            }
            VideoConcatenator.ConcatenateProgress.Status.COMPLETED -> {
              updateMessage("拼接完成")
              appendLog(logArea, "拼接完成，输出文件 ${progress.outputFile?.toAbsolutePath() ?: "未知"}")
            }
            VideoConcatenator.ConcatenateProgress.Status.FAILED -> {
              updateMessage("拼接失败")
              appendLog(logArea, "拼接失败：${progress.detail ?: "未知原因"}")
            }
          }
        }
      }
    }

    task.setOnRunning {
      startButton.isDisable = true
      progressBar.progressProperty().bind(task.progressProperty())
      statusLabel.textProperty().bind(task.messageProperty())
    }

    task.setOnSucceeded {
      progressBar.progressProperty().unbind()
      statusLabel.textProperty().unbind()
      progressBar.progress = 1.0
      statusLabel.text = "拼接完成"
      startButton.isDisable = false
      currentTask = null

      val report = task.get()
      if (report.success) {
        val output = report.outputFile?.toAbsolutePath()
        appendLog(logArea, "任务完成：成功拼接 ${report.totalFileCount} 个文件 -> ${output ?: "未知输出"}")
      } else {
        val failure = report.failure
        appendLog(
          logArea,
          "任务失败：${failure?.reason ?: "未知原因"}"
        )
      }
    }

    task.setOnFailed {
      progressBar.progressProperty().unbind()
      statusLabel.textProperty().unbind()
      progressBar.progress = 0.0
      statusLabel.text = "拼接失败"
      startButton.isDisable = false
      currentTask = null

      val error = task.exception
      appendLog(logArea, "任务失败：${error?.message ?: error?.javaClass?.simpleName ?: "未知错误"}")
    }

    task.setOnCancelled {
      progressBar.progressProperty().unbind()
      statusLabel.textProperty().unbind()
      progressBar.progress = 0.0
      statusLabel.text = "任务已取消"
      startButton.isDisable = false
      currentTask = null
    }

    Thread(task).apply {
      isDaemon = true
      name = "video-concat-task"
      start()
    }
    currentTask = task
  }
}
