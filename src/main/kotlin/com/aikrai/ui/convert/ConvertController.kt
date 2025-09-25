package com.aikrai.ui.convert

import com.aikrai.core.VideoConverter
import com.aikrai.ui.common.UiConstants
import com.aikrai.ui.common.UiUtils.appendLog
import com.aikrai.ui.common.UiUtils.showAlert
import javafx.concurrent.Task
import javafx.scene.control.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * 转换页控制器：承载开始转换的执行方法与进度回调绑定。
 *
 * 仅依赖核心服务 `VideoConverter` 与通用 UI 工具，不依赖具体视图布局。
 */
class ConvertController(private val videoConverter: VideoConverter) {

  /** 当前正在运行的转换任务（避免重复启动） */
  private var currentTask: Task<VideoConverter.ConversionReport>? = null

  /**
   * 校验表单参数并启动转换任务，过程中绑定/解绑进度与状态。
   *
   * @param fileList 待转换文件列表
   * @param formatCombo 输出格式下拉框
   * @param outputField 输出目录输入框
   * @param ffmpegField ffmpeg 路径输入框
   * @param logArea 日志区域
   * @param progressBar 进度条
   * @param statusLabel 状态标签
   * @param startButton 开始按钮（任务期间禁用）
   */
  fun startConversion(
    fileList: ListView<Path>,
    formatCombo: ComboBox<String>,
    outputField: TextField,
    ffmpegField: TextField,
    logArea: TextArea,
    progressBar: ProgressBar,
    statusLabel: Label,
    startButton: Button
  ) {
    if (currentTask?.isRunning == true) {
      showAlert(Alert.AlertType.INFORMATION, "任务运行中", "请等待当前转换任务完成。")
      return
    }

    val inputs = fileList.items.map { it.toAbsolutePath().normalize() }
    if (inputs.isEmpty()) {
      showAlert(Alert.AlertType.WARNING, "列表为空", "请先添加需要转换的视频文件。")
      return
    }

    val outputPath = try {
      outputField.text.trim().takeIf { it.isNotEmpty() }?.let { Path.of(it).normalize() }
    } catch (ex: InvalidPathException) {
      showAlert(Alert.AlertType.ERROR, "输出路径无效", "请输入合法的输出目录路径。\n${ex.message}")
      return
    }

    outputPath?.let { target ->
      if (Files.exists(target) && !Files.isDirectory(target)) {
        showAlert(Alert.AlertType.ERROR, "输出路径非法", "输出路径已存在但不是目录。")
        return
      }
      try {
        Files.createDirectories(target)
      } catch (ex: IOException) {
        showAlert(Alert.AlertType.ERROR, "输出目录创建失败", ex.message ?: "未知错误")
        return
      }
    }

    val format = formatCombo.value ?: UiConstants.DEFAULT_FORMAT
    val ffmpegPath = ffmpegField.text.trim().takeIf { it.isNotEmpty() }

    appendLog(
      logArea,
      "开始转换：文件数 ${inputs.size}，目标格式 $format，输出目录 ${outputPath?.toAbsolutePath() ?: "同源目录"}"
    )

    val task = object : Task<VideoConverter.ConversionReport>() {
      override fun call(): VideoConverter.ConversionReport {
        updateProgress(0.0, inputs.size.toDouble().coerceAtLeast(1.0))
        updateMessage("任务初始化…")
        val report = videoConverter.convertAll(inputs, format, ffmpegPath, outputPath) { progress ->
          val total = progress.totalFiles.coerceAtLeast(1)
          if (progress.totalDurationMillis > 0) {
            val denom = progress.totalDurationMillis.toDouble()
            val numer = progress.processedDurationMillis.coerceAtLeast(0).toDouble().coerceAtMost(denom)
            updateProgress(numer, denom)
          } else {
            updateProgress(progress.completedFiles.toLong().toDouble(), total.toLong().toDouble())
          }
          val statusText = when (progress.status) {
            VideoConverter.ConversionProgress.Status.PROCESSING -> "处理中"
            VideoConverter.ConversionProgress.Status.CONVERTED -> "已完成"
            VideoConverter.ConversionProgress.Status.FAILED -> "失败"
          }
          val detailSuffix = progress.detail?.let { " - $it" } ?: ""
          updateMessage("${progress.completedFiles}/${progress.totalFiles} $statusText$detailSuffix")
          if (progress.status != VideoConverter.ConversionProgress.Status.PROCESSING || detailSuffix.isNotEmpty()) {
            appendLog(
              logArea,
              "${progress.currentFile.fileName}: $statusText$detailSuffix"
            )
          }
        }
        if (report.totalFileCount == 0) {
          updateProgress(1.0, 1.0)
          updateMessage("未处理任何文件")
        }
        return report
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
      statusLabel.text = "转换完成"
      startButton.isDisable = false
      currentTask = null

      val report = task.get()
      appendLog(
        logArea,
        "任务完成：合计 ${report.totalFileCount} 个文件，成功 ${report.successCount}，失败 ${report.failureCount}"
      )
      if (report.failureCount > 0) {
        report.failureDetails.forEach { failure ->
          appendLog(logArea, "失败文件 ${failure.inputFile.fileName}: ${failure.reason}")
        }
      }
    }

    task.setOnFailed {
      progressBar.progressProperty().unbind()
      statusLabel.textProperty().unbind()
      progressBar.progress = 0.0
      statusLabel.text = "转换失败"
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
      name = "video-convert-task"
      start()
    }
    currentTask = task
  }
}
