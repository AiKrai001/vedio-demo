package com.aikrai.ui.merge

import com.aikrai.ui.common.UiConstants
import com.aikrai.ui.common.UiUtils.findExistingDirectory
import com.aikrai.ui.common.UiUtils.resolveDefaultRootPath
import com.aikrai.ui.common.UiUtils.showDirectoryChooserSafely
import javafx.collections.FXCollections
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.*
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.DirectoryChooser
import javafx.stage.Stage

/**
 * 合并页视图：仅承担 UI 组件的创建与布局，事件回调交由控制器。
 *
 * 视图不包含业务逻辑，便于独立测试与维护。
 */
class MergeView(
  private val stage: Stage,
  private val controller: MergeController
) {
  /**
   * 创建合并页的根内容节点。
   *
   * @return 可直接嵌入到 Tab 的 JavaFX 节点
   */
  fun createContent(): Node {
    val rootField = TextField(resolveDefaultRootPath()).apply {
      promptText = "选择待合并视频的根目录"
    }
    val rootBrowseButton = Button("浏览…")

    val outputField = TextField().apply {
      promptText = "可选：合并结果输出目录，留空表示写回原目录"
    }
    val outputBrowseButton = Button("选择…")

    val formatCombo = ComboBox(FXCollections.observableArrayList("mp4", "mkv")).apply {
      value = UiConstants.DEFAULT_FORMAT
    }
    val ffmpegField = TextField().apply {
      promptText = "可选：自定义 ffmpeg 路径，不填使用内置"
    }
    val logArea = TextArea().apply {
      isEditable = false
      prefRowCount = 14
      isWrapText = true
      styleClass += "log-area"
    }
    val progressBar = ProgressBar(0.0).apply {
      prefWidth = 420.0
      maxWidth = Double.MAX_VALUE
      styleClass += "accent-progress-bar"
    }
    val statusLabel = Label("等待开始").apply { styleClass += "status-label" }
    val startButton = Button("开始合并").apply { styleClass += "primary-button" }
    val clearLogButton = Button("清空日志").apply { styleClass += "ghost-button" }

    rootBrowseButton.setOnAction {
      val chooser = DirectoryChooser().apply {
        title = "选择合并根目录"
        findExistingDirectory(rootField.text, System.getProperty("user.home"))?.let { initialDirectory = it }
      }
      val selected = showDirectoryChooserSafely(chooser, stage)
      if (selected != null) rootField.text = selected.absolutePath
    }

    outputBrowseButton.setOnAction {
      val chooser = DirectoryChooser().apply {
        title = "选择输出目录"
        findExistingDirectory(outputField.text, rootField.text, System.getProperty("user.home"))?.let { initialDirectory = it }
      }
      val selected = showDirectoryChooserSafely(chooser, stage)
      if (selected != null) outputField.text = selected.absolutePath
    }

    startButton.setOnAction {
      controller.startMerge(rootField, outputField, formatCombo, ffmpegField, logArea, progressBar, statusLabel, startButton)
    }
    clearLogButton.setOnAction { logArea.clear() }

    val formGrid = GridPane().apply {
      styleClass += "form-grid"
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

    val buttonBar = HBox(12.0, startButton, clearLogButton).apply {
      styleClass += "toolbar"
      alignment = Pos.CENTER_LEFT
    }

    val logContainer = VBox().apply {
      children += logArea
      VBox.setVgrow(logArea, Priority.ALWAYS)
    }

    val progressRow = HBox(10.0, Label("进度"), progressBar).apply {
      alignment = Pos.CENTER_LEFT
      HBox.setHgrow(progressBar, Priority.ALWAYS)
    }

    val bottomPane = VBox(8.0).apply {
      styleClass += "card-section"
      children += progressRow
      children += statusLabel
    }

    val formSection = VBox(12.0).apply {
      styleClass += "card-section"
      children += formGrid
      children += buttonBar
    }

    val logSection = VBox(12.0).apply {
      styleClass += "card-section"
      children += Label("运行日志").apply { styleClass += "section-title" }
      children += logContainer
      VBox.setVgrow(logContainer, Priority.ALWAYS)
    }

    val container = VBox(20.0).apply {
      styleClass += "app-card"
      maxWidth = Double.MAX_VALUE
      prefWidth = Double.MAX_VALUE
      children += formSection
      children += Region().apply { styleClass += "separator-line" }
      children += logSection
      children += Region().apply { styleClass += "separator-line" }
      children += bottomPane
      VBox.setVgrow(logSection, Priority.ALWAYS)
    }
    // 按需加载合并页样式
    javaClass.getResource("/styles/merge.css")?.toExternalForm()?.let { css ->
      container.stylesheets += css
    }
    return container
  }
}
