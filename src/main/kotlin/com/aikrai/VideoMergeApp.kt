package com.aikrai

import javafx.application.Application
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.concurrent.Task
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.layout.BorderPane
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.DirectoryChooser
import javafx.stage.Stage
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class VideoMergeApp : Application() {
  private val videoMerger = VideoMerger()
  private var currentTask: Task<VideoMerger.MergeReport>? = null

  override fun start(primaryStage: Stage) {
    val rootField = TextField(resolveDefaultRootPath())
    val rootBrowseButton = Button("浏览…")

    val outputField = TextField().apply {
      promptText = "可选：合并结果输出目录，留空表示写回原目录"
    }
    val outputBrowseButton = Button("选择…")

    val formatCombo = ComboBox(FXCollections.observableArrayList("mp4", "mkv")).apply {
      value = DEFAULT_FORMAT
    }
    val ffmpegField = TextField().apply {
      promptText = "可选：自定义 ffmpeg 路径，不填使用内置"
    }
    val logArea = TextArea().apply {
      isEditable = false
      prefRowCount = 14
      isWrapText = true
    }
    val progressBar = ProgressBar(0.0).apply {
      prefWidth = 420.0
      maxWidth = Double.MAX_VALUE
    }
    val statusLabel = Label("等待开始")
    val startButton = Button("开始合并")
    val clearLogButton = Button("清空日志")

    rootBrowseButton.setOnAction {
      val chooser = DirectoryChooser().apply {
        title = "选择合并根目录"
        findExistingDirectory(rootField.text, System.getProperty("user.home"))?.let { initialDirectory = it }
      }
      val selected = showDirectoryChooserSafely(chooser, primaryStage)
      if (selected != null) {
        rootField.text = selected.absolutePath
      }
    }

    outputBrowseButton.setOnAction {
      val chooser = DirectoryChooser().apply {
        title = "选择输出目录"
        findExistingDirectory(outputField.text, rootField.text, System.getProperty("user.home"))?.let {
          initialDirectory = it
        }
      }
      val selected = showDirectoryChooserSafely(chooser, primaryStage)
      if (selected != null) {
        outputField.text = selected.absolutePath
      }
    }

    startButton.setOnAction {
      startMerge(rootField, outputField, formatCombo, ffmpegField, logArea, progressBar, statusLabel, startButton)
    }
    clearLogButton.setOnAction { logArea.clear() }

    val formGrid = GridPane().apply {
      hgap = 10.0
      vgap = 10.0
      padding = Insets(15.0, 15.0, 5.0, 15.0)
      add(Label("根目录"), 0, 0)
      add(rootField, 1, 0)
      add(rootBrowseButton, 2, 0)

      add(Label("输出目录"), 0, 1)
      add(outputField, 1, 1)
      add(outputBrowseButton, 2, 1)

      add(Label("输出格式"), 0, 2)
      add(formatCombo, 1, 2)

      add(Label("ffmpeg 路径"), 0, 3)
      add(ffmpegField, 1, 3)
    }
    rootField.prefWidth = 420.0
    GridPane.setHgrow(rootField, Priority.ALWAYS)
    GridPane.setHgrow(outputField, Priority.ALWAYS)
    GridPane.setHgrow(formatCombo, Priority.ALWAYS)
    GridPane.setHgrow(ffmpegField, Priority.ALWAYS)

    val buttonBar = HBox(10.0, startButton, clearLogButton).apply {
      alignment = Pos.CENTER_LEFT
      padding = Insets(0.0, 15.0, 10.0, 15.0)
    }

    val logContainer = VBox().apply {
      padding = Insets(0.0, 15.0, 0.0, 15.0)
      children += Label("运行日志")
      children += logArea
      VBox.setVgrow(logArea, Priority.ALWAYS)
    }

    val bottomPane = VBox(6.0).apply {
      padding = Insets(10.0, 15.0, 15.0, 15.0)
      children += HBox(10.0, Label("进度"), progressBar).apply {
        alignment = Pos.CENTER_LEFT
        HBox.setHgrow(progressBar, Priority.ALWAYS)
      }
      children += statusLabel
    }

    val rootPane = BorderPane().apply {
      top = VBox(5.0, formGrid, buttonBar)
      center = logContainer
      bottom = bottomPane
    }

    val scene = Scene(rootPane, 780.0, 580.0)
    primaryStage.title = "TS 视频合并工具"
    primaryStage.scene = scene
    primaryStage.show()
  }

  private fun startMerge(
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

    val format = formatCombo.value ?: DEFAULT_FORMAT
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

      val error = task.exception
      appendLog(logArea, "任务失败：${error?.message ?: error?.javaClass?.simpleName ?: "未知错误"}")
    }

    Thread(task).apply {
      isDaemon = true
      name = "video-merge-task"
      start()
    }
    currentTask = task
  }

  private fun findExistingDirectory(vararg candidates: String?): File? {
    for (candidate in candidates) {
      if (candidate.isNullOrBlank()) {
        continue
      }
      try {
        val path = Path.of(candidate).normalize()
        if (Files.exists(path) && Files.isDirectory(path)) {
          return path.toFile()
        }
      } catch (_: InvalidPathException) {
        // ignore invalid candidate
      }
    }
    return null
  }

  private fun showDirectoryChooserSafely(chooser: DirectoryChooser, stage: Stage): File? {
    return try {
      chooser.showDialog(stage)
    } catch (ex: IllegalArgumentException) {
      showAlert(Alert.AlertType.ERROR, "目录选择失败", ex.message ?: "请选择有效的文件夹")
      null
    }
  }

  private fun appendLog(logArea: TextArea, message: String) {
    val timestamp = LocalDateTime.now().format(TIME_FORMATTER)
    Platform.runLater {
      logArea.appendText("[$timestamp] $message\n")
      logArea.scrollTop = Double.MAX_VALUE
    }
  }

  private fun showAlert(type: Alert.AlertType, title: String, content: String) {
    Platform.runLater {
      Alert(type).apply {
        this.title = title
        headerText = null
        contentText = content
      }.showAndWait()
    }
  }

  private fun resolveDefaultRootPath(): String {
    return try {
      val defaultPath = Path.of(DEFAULT_ROOT_PATH)
      if (Files.exists(defaultPath) && Files.isDirectory(defaultPath)) {
        defaultPath.toString()
      } else {
        System.getProperty("user.home")
      }
    } catch (_: Exception) {
      System.getProperty("user.home")
    }
  }

  companion object {
    private const val DEFAULT_ROOT_PATH = ""
    private const val DEFAULT_FORMAT = "mp4"
    private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
  }
}
