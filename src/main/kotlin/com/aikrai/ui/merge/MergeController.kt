package com.aikrai.ui.merge

import com.aikrai.core.VideoMerger
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
 * 合并页控制器：承载开始合并的执行方法与进度回调绑定。
 *
 * 仅依赖核心服务 `VideoMerger` 与通用 UI 工具，不依赖具体视图布局。
 */
class MergeController(private val videoMerger: VideoMerger) {

  /** 当前正在运行的合并任务（避免重复启动） */
  private var currentTask: Task<VideoMerger.MergeReport>? = null

  /**
   * 校验表单参数并启动合并任务，过程中绑定/解绑进度与状态。
   *
   * @param rootField 根目录输入框
   * @param outputField 输出目录输入框
   * @param formatCombo 输出格式下拉框
   * @param ffmpegField ffmpeg 路径输入框
   * @param logArea 日志区域
   * @param progressBar 进度条
   * @param statusLabel 状态标签
   * @param startButton 开始按钮（任务期间禁用）
   */
  fun startMerge(
    rootField: TextField,
    outputField: TextField,
    formatCombo: ComboBox<String>,
    ffmpegField: TextField,
    logArea: TextArea,
    progressBar: ProgressBar,
    statusLabel: Label,
    startButton: Button
  ) {
    if (currentTask?.isRunning == true) {
      showAlert(Alert.AlertType.INFORMATION, "任务运行中", "请等待当前任务完成。")
      return
    }

    val rootPath = try {
      Path.of(rootField.text.trim()).normalize()
    } catch (ex: InvalidPathException) {
      showAlert(Alert.AlertType.ERROR, "路径无效", "请输入合法的根目录路径。\n${ex.message}")
      return
    }

    if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
      showAlert(Alert.AlertType.ERROR, "路径不存在", "根目录不存在或不是文件夹。")
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
      "开始合并：根目录 ${rootPath.toAbsolutePath()}，目标格式 $format，输出目录 ${outputPath?.toAbsolutePath() ?: "同源目录"}"
    )

    val task = object : Task<VideoMerger.MergeReport>() {
      override fun call(): VideoMerger.MergeReport {
        updateProgress(0.0, 1.0)
        updateMessage("任务初始化…")
        val report = videoMerger.mergeAll(rootPath, format, ffmpegPath, outputPath) { progress ->
          val total = progress.totalDirectories.coerceAtLeast(1)
          updateProgress(progress.completedDirectories.toLong(), total.toLong())
          val statusText = when (progress.status) {
            VideoMerger.MergeProgress.Status.MERGED -> "已合并"
            VideoMerger.MergeProgress.Status.SKIPPED -> "已跳过"
            VideoMerger.MergeProgress.Status.FAILED -> "失败"
          }
          val detailSuffix = progress.detail?.let { " - $it" } ?: ""
          updateMessage("${progress.completedDirectories}/$total $statusText$detailSuffix")
          appendLog(
            logArea,
            "${progress.currentDirectory.fileName}: $statusText$detailSuffix"
          )
        }
        if (report.leafDirectoryCount == 0) {
          updateProgress(1.0, 1.0)
          updateMessage("无可合并目录")
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
      statusLabel.text = "合并完成"
      startButton.isDisable = false
      currentTask = null

      val report = task.get()
      appendLog(
        logArea,
        "任务完成：扫描 ${report.leafDirectoryCount} 个目录，成功 ${report.mergedDirectoryCount}，跳过 ${report.skippedDirectoryCount}，失败 ${report.failureCount}"
      )
      if (report.failureCount > 0) {
        report.failureDetails.forEach { failure ->
          appendLog(logArea, "失败目录 ${failure.directory}: ${failure.reason}")
        }
      }
    }

    task.setOnFailed {
      progressBar.progressProperty().unbind()
      statusLabel.textProperty().unbind()
      progressBar.progress = 0.0
      statusLabel.text = "任务失败"
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
      name = "video-merge-task"
      start()
    }
    currentTask = task
  }
}
