package com.aikrai.ui.concat

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
import javafx.util.Callback
import java.nio.file.Path

/**
 * 视频拼接页视图：负责构建 UI 组件与布局，交互事件交由控制器处理。
 */
class ConcatView(
  private val stage: Stage,
  private val controller: ConcatController
) {

  /**
   * 构建拼接页的根内容节点。
   */
  fun createContent(): Node {
    val directoryField = TextField(resolveDefaultRootPath()).apply {
      promptText = "选择待拼接视频所在目录"
    }
    val browseButton = Button("浏览…")
    val refreshButton = Button("刷新列表").apply { styleClass += "ghost-button" }

    val fileList = ListView<Path>(FXCollections.observableArrayList()).apply {
      placeholder = Label("请先选择目录，列表按字典序显示视频文件")
      cellFactory = Callback<ListView<Path>, ListCell<Path>> {
        object : ListCell<Path>() {
          override fun updateItem(item: Path?, empty: Boolean) {
            super.updateItem(item, empty)
            if (empty || item == null) {
              text = ""
              tooltip = null
            } else {
              text = item.fileName?.toString() ?: item.toString()
              tooltip = Tooltip(item.toAbsolutePath().toString())
            }
          }
        }
      }
      prefHeight = 220.0
      styleClass += "list-surface"
    }

    fun refreshList() {
      controller.refreshFileList(directoryField, fileList)
    }

    browseButton.setOnAction {
      val chooser = DirectoryChooser().apply {
        title = "选择拼接目录"
        findExistingDirectory(directoryField.text, System.getProperty("user.home"))?.let { initialDirectory = it }
      }
      val selected = showDirectoryChooserSafely(chooser, stage)
      if (selected != null) {
        directoryField.text = selected.absolutePath
        refreshList()
      }
    }

    refreshButton.setOnAction { refreshList() }
    directoryField.setOnAction { refreshList() }

    val formatCombo = ComboBox(FXCollections.observableArrayList(UiConstants.CONVERSION_FORMATS)).apply {
      value = UiConstants.DEFAULT_FORMAT
    }
    val ffmpegField = TextField().apply { promptText = "可选：自定义 ffmpeg 路径，不填使用默认解析" }

    val logArea = TextArea().apply {
      isEditable = false
      prefRowCount = 12
      isWrapText = true
      styleClass += "log-area"
    }
    val progressBar = ProgressBar(0.0).apply {
      prefWidth = 420.0
      maxWidth = Double.MAX_VALUE
      styleClass += "accent-progress-bar"
    }
    val statusLabel = Label("等待开始").apply { styleClass += "status-label" }
    val startButton = Button("开始拼接").apply { styleClass += "primary-button" }
    val clearLogButton = Button("清空日志").apply { styleClass += "ghost-button" }

    startButton.setOnAction {
      controller.startConcatenate(
        directoryField,
        formatCombo,
        ffmpegField,
        fileList,
        logArea,
        progressBar,
        statusLabel,
        startButton
      )
    }
    clearLogButton.setOnAction { logArea.clear() }

    val directoryRow = GridPane().apply {
      styleClass += "form-grid"
      add(Label("拼接目录"), 0, 0)
      add(directoryField, 1, 0)
      add(browseButton, 2, 0)
    }
    GridPane.setHgrow(directoryField, Priority.ALWAYS)

    val formatRow = GridPane().apply {
      styleClass += "form-grid"
      add(Label("输出格式"), 0, 0)
      add(formatCombo, 1, 0)
    }
    GridPane.setHgrow(formatCombo, Priority.ALWAYS)

    val ffmpegRow = GridPane().apply {
      styleClass += "form-grid"
      add(Label("ffmpeg 路径"), 0, 0)
      add(ffmpegField, 1, 0)
    }
    GridPane.setHgrow(ffmpegField, Priority.ALWAYS)

    val controlsSection = VBox(12.0).apply {
      styleClass += "card-section"
      children += directoryRow
      children += HBox(12.0, refreshButton).apply {
        styleClass += "toolbar"
        alignment = Pos.CENTER_LEFT
      }
      children += formatRow
      children += ffmpegRow
      children += HBox(12.0, startButton, clearLogButton).apply {
        styleClass += "toolbar"
        alignment = Pos.CENTER_LEFT
      }
    }

    val listSection = VBox(12.0).apply {
      styleClass += "card-section"
      children += Label("待拼接文件").apply { styleClass += "section-title" }
      children += fileList
      VBox.setVgrow(fileList, Priority.ALWAYS)
    }

    val logSection = VBox(12.0).apply {
      styleClass += "card-section"
      children += Label("运行日志").apply { styleClass += "section-title" }
      children += logArea
      VBox.setVgrow(logArea, Priority.ALWAYS)
    }

    val progressRow = HBox(10.0, Label("进度"), progressBar).apply {
      alignment = Pos.CENTER_LEFT
      HBox.setHgrow(progressBar, Priority.ALWAYS)
    }

    val bottomSection = VBox(8.0).apply {
      styleClass += "card-section"
      children += progressRow
      children += statusLabel
    }

    val container = VBox(20.0).apply {
      styleClass += "app-card"
      maxWidth = Double.MAX_VALUE
      prefWidth = Double.MAX_VALUE
      children += controlsSection
      children += Region().apply { styleClass += "separator-line" }
      children += listSection
      children += Region().apply { styleClass += "separator-line" }
      children += logSection
      children += Region().apply { styleClass += "separator-line" }
      children += bottomSection
      VBox.setVgrow(listSection, Priority.ALWAYS)
      VBox.setVgrow(logSection, Priority.ALWAYS)
    }

    refreshList()

    javaClass.getResource("/styles/concat.css")?.toExternalForm()?.let { css ->
      container.stylesheets += css
    }

    return ScrollPane(container).apply {
      styleClass += "app-scroll-pane"
      hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
      vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
      isFitToWidth = true
      viewportBoundsProperty().addListener { _, _, bounds ->
        container.minHeight = bounds.height
      }
    }
  }
}
