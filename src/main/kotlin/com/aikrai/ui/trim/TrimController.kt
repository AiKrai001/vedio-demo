package com.aikrai.ui.trim

import com.aikrai.core.VideoTrimmer
import com.aikrai.ui.common.UiUtils.appendLog
import com.aikrai.ui.common.UiUtils.showAlert
import javafx.concurrent.Task
import javafx.scene.control.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Locale

/**
 * 批量裁剪页面控制器：负责校验输入、触发核心服务并与 UI 组件联动。
 */
class TrimController(private val videoTrimmer: VideoTrimmer) {

  /** 当前正在运行的裁剪任务（避免重复启动） */
  private var currentTask: Task<VideoTrimmer.TrimReport>? = null

  /**
   * 时间输入控件集合，便于统一读取时分秒毫秒。
   */
  data class TimeFieldGroup(
    val hourSpinner: Spinner<Int>,
    val minuteSpinner: Spinner<Int>,
    val secondSpinner: Spinner<Int>,
    val milliSpinner: Spinner<Int>
  )

  /**
   * 启动批量裁剪流程：完成表单校验、任务创建与进度绑定。
   */
  fun startTrimming(
    rootField: TextField,
    outputField: TextField,
    ffmpegField: TextField,
    modeCombo: ComboBox<VideoTrimmer.TrimMode>,
    primaryFields: TimeFieldGroup,
    secondaryFields: TimeFieldGroup,
    logArea: TextArea,
    progressBar: ProgressBar,
    statusLabel: Label,
    startButton: Button
  ) {
    if (currentTask?.isRunning == true) {
      showAlert(Alert.AlertType.INFORMATION, "任务运行中", "请等待当前裁剪任务完成。")
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

    val mode = modeCombo.value ?: VideoTrimmer.TrimMode.HEAD
    val primaryMillis = primaryFields.toMillis()
    val secondaryMillis = secondaryFields.toMillis()

    val validationError = validateDurations(mode, primaryMillis, secondaryMillis)
    if (validationError != null) {
      showAlert(Alert.AlertType.WARNING, "时间参数无效", validationError)
      return
    }

    val ffmpegPath = ffmpegField.text.trim().takeIf { it.isNotEmpty() }

    val targetOutput = outputPath ?: rootPath.resolve("res").normalize()
    when (mode) {
      VideoTrimmer.TrimMode.HEAD -> {
        appendLog(
          logArea,
          "开始裁剪：目录 ${rootPath.toAbsolutePath()}，模式 ${mode.humanLabel()}，移除头部 ${formatDuration(primaryMillis)}，输出目录 $targetOutput"
        )
      }
      VideoTrimmer.TrimMode.TAIL -> {
        appendLog(
          logArea,
          "开始裁剪：目录 ${rootPath.toAbsolutePath()}，模式 ${mode.humanLabel()}，移除尾部 ${formatDuration(primaryMillis)}，输出目录 $targetOutput"
        )
      }
      VideoTrimmer.TrimMode.HEAD_TAIL -> {
        appendLog(
          logArea,
          "开始裁剪：目录 ${rootPath.toAbsolutePath()}，模式 ${mode.humanLabel()}，移除头部 ${formatDuration(primaryMillis)} 与尾部 ${formatDuration(secondaryMillis)}，输出目录 $targetOutput"
        )
      }
      VideoTrimmer.TrimMode.KEEP_RANGE -> {
        appendLog(
          logArea,
          "开始裁剪：目录 ${rootPath.toAbsolutePath()}，模式 ${mode.humanLabel()}，保留区间 ${formatDuration(primaryMillis)} ~ ${formatDuration(secondaryMillis)}，输出目录 $targetOutput"
        )
      }
    }

    val task = object : Task<VideoTrimmer.TrimReport>() {
      override fun call(): VideoTrimmer.TrimReport {
        updateProgress(0.0, 1.0)
        updateMessage("任务初始化…")
        val report = videoTrimmer.trimAll(
          rootDir = rootPath,
          mode = mode,
          primaryDurationMillis = primaryMillis,
          secondaryDurationMillis = when (mode) {
            VideoTrimmer.TrimMode.HEAD, VideoTrimmer.TrimMode.TAIL -> 0L
            else -> secondaryMillis
          },
          ffmpegExecutable = ffmpegPath,
          outputDirectory = outputPath
        ) { progress ->
          val total = progress.totalFiles.coerceAtLeast(1)
          updateProgress(progress.completedFiles.toDouble(), total.toDouble())
          val statusText = when (progress.status) {
            VideoTrimmer.TrimProgress.Status.TRIMMED -> "已裁剪"
            VideoTrimmer.TrimProgress.Status.SKIPPED -> "已跳过"
            VideoTrimmer.TrimProgress.Status.FAILED -> "失败"
          }
          val detailSuffix = progress.detail?.let { " - $it" } ?: ""
          updateMessage("${progress.completedFiles}/$total $statusText$detailSuffix")
          val outputHint = progress.outputFile?.fileName?.let { " -> $it" } ?: ""
          appendLog(
            logArea,
            "${progress.currentFile.fileName}: $statusText$detailSuffix$outputHint"
          )
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
      statusLabel.text = "裁剪完成"
      startButton.isDisable = false
      currentTask = null

      val report = task.get()
      appendLog(
        logArea,
        "任务完成：共处理 ${report.totalFileCount} 个文件，成功 ${report.trimmedCount}，跳过 ${report.skippedCount}，失败 ${report.failureCount}"
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
      statusLabel.text = "裁剪失败"
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
      name = "video-trim-task"
      start()
    }
    currentTask = task
  }

  private fun validateDurations(
    mode: VideoTrimmer.TrimMode,
    primaryMillis: Long,
    secondaryMillis: Long
  ): String? {
    return when (mode) {
      VideoTrimmer.TrimMode.HEAD -> if (primaryMillis <= 0L) "请设置大于 0 的头部裁剪时长。" else null
      VideoTrimmer.TrimMode.TAIL -> if (primaryMillis <= 0L) "请设置大于 0 的尾部裁剪时长。" else null
      VideoTrimmer.TrimMode.HEAD_TAIL -> when {
        primaryMillis <= 0L -> "头部裁剪时长必须大于 0。"
        secondaryMillis <= 0L -> "尾部裁剪时长必须大于 0。"
        else -> null
      }
      VideoTrimmer.TrimMode.KEEP_RANGE -> when {
        primaryMillis < 0L -> "开始时间必须大于等于 0。"
        secondaryMillis <= primaryMillis -> "结束时间必须大于开始时间。"
        else -> null
      }
    }
  }

  private fun TimeFieldGroup.toMillis(): Long {
    val hours = hourSpinner.safeValue()
    val minutes = minuteSpinner.safeValue()
    val seconds = secondSpinner.safeValue()
    val millis = milliSpinner.safeValue()
    return (((hours * 60L + minutes) * 60L) + seconds) * 1000L + millis
  }

  private fun Spinner<Int>.safeValue(): Long {
    return (valueFactory?.value ?: 0).coerceAtLeast(0).toLong()
  }

  private fun formatDuration(millis: Long): String {
    val clamped = millis.coerceAtLeast(0L)
    val hours = clamped / 3_600_000
    val minutes = (clamped % 3_600_000) / 60_000
    val seconds = (clamped % 60_000) / 1_000
    val restMillis = clamped % 1_000
    return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, seconds, restMillis)
  }

  private fun VideoTrimmer.TrimMode.humanLabel(): String = when (this) {
    VideoTrimmer.TrimMode.HEAD -> "裁剪头部"
    VideoTrimmer.TrimMode.TAIL -> "裁剪尾部"
    VideoTrimmer.TrimMode.HEAD_TAIL -> "裁剪头尾"
    VideoTrimmer.TrimMode.KEEP_RANGE -> "取中间部分"
  }
}
