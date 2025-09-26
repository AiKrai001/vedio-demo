package com.aikrai.ui.trim

import com.aikrai.core.VideoTrimmer
import com.aikrai.ui.common.UiUtils.findExistingDirectory
import com.aikrai.ui.common.UiUtils.resolveDefaultRootPath
import com.aikrai.ui.common.UiUtils.showDirectoryChooserSafely
import javafx.collections.FXCollections
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.*
import javafx.scene.control.SpinnerValueFactory.IntegerSpinnerValueFactory
import javafx.scene.layout.*
import javafx.stage.DirectoryChooser
import javafx.stage.Stage
import javafx.util.Callback
import javafx.util.StringConverter
import javafx.util.converter.IntegerStringConverter

/**
 * 批量裁剪视图：负责构建 UI 组件与布局，交互交由控制器处理。
 */
class TrimView(
  private val stage: Stage,
  private val controller: TrimController
) {

  /**
   * 创建批量裁剪页的根节点。
   */
  fun createContent(): Node {
    val rootField = TextField(resolveDefaultRootPath()).apply {
      promptText = "选择待裁剪视频的根目录"
    }
    val rootBrowseButton = Button("浏览…")

    val outputField = TextField().apply {
      promptText = "可选：输出目录，留空默认写入根目录下 res"
    }
    val outputBrowseButton = Button("选择…")

    val ffmpegField = TextField().apply {
      promptText = "可选：自定义 ffmpeg 路径，不填使用内置"
    }

    val modeCombo = ComboBox(FXCollections.observableArrayList(VideoTrimmer.TrimMode.values().toList())).apply {
      value = VideoTrimmer.TrimMode.HEAD
      converter = object : StringConverter<VideoTrimmer.TrimMode>() {
        override fun toString(mode: VideoTrimmer.TrimMode?): String = when (mode) {
          VideoTrimmer.TrimMode.HEAD -> "裁剪头部"
          VideoTrimmer.TrimMode.TAIL -> "裁剪尾部"
          VideoTrimmer.TrimMode.HEAD_TAIL -> "裁剪头尾"
          VideoTrimmer.TrimMode.KEEP_RANGE -> "取中间部分"
          null -> ""
        }

        override fun fromString(string: String?): VideoTrimmer.TrimMode = when (string) {
          "裁剪尾部" -> VideoTrimmer.TrimMode.TAIL
          "裁剪头尾" -> VideoTrimmer.TrimMode.HEAD_TAIL
          "取中间部分" -> VideoTrimmer.TrimMode.KEEP_RANGE
          else -> VideoTrimmer.TrimMode.HEAD
        }
      }
      buttonCell = createModeCell()
      cellFactory = Callback { createModeCell() }
    }

    val primaryHours = createSpinner(0, 999)
    val primaryMinutes = createSpinner(0, 59)
    val primarySeconds = createSpinner(0, 59)
    val primaryMillis = createSpinner(0, 999)
    val primaryTimeBox = createTimeBox(primaryHours, primaryMinutes, primarySeconds, primaryMillis)

    val secondaryHours = createSpinner(0, 999)
    val secondaryMinutes = createSpinner(0, 59)
    val secondarySeconds = createSpinner(0, 59)
    val secondaryMillis = createSpinner(0, 999)
    val secondaryTimeBox = createTimeBox(secondaryHours, secondaryMinutes, secondarySeconds, secondaryMillis)

    val primaryLabel = Label()
    val secondaryLabel = Label()

    fun refreshModeView(mode: VideoTrimmer.TrimMode) {
      when (mode) {
        VideoTrimmer.TrimMode.HEAD -> {
          primaryLabel.text = "头部裁剪时长"
          secondaryLabel.text = ""
          secondaryTimeBox.isVisible = false
          secondaryTimeBox.isManaged = false
          secondaryLabel.isVisible = false
          secondaryLabel.isManaged = false
        }
        VideoTrimmer.TrimMode.TAIL -> {
          primaryLabel.text = "尾部裁剪时长"
          secondaryLabel.text = ""
          secondaryTimeBox.isVisible = false
          secondaryTimeBox.isManaged = false
          secondaryLabel.isVisible = false
          secondaryLabel.isManaged = false
        }
        VideoTrimmer.TrimMode.HEAD_TAIL -> {
          primaryLabel.text = "头部裁剪时长"
          secondaryLabel.text = "尾部裁剪时长"
          secondaryTimeBox.isVisible = true
          secondaryTimeBox.isManaged = true
          secondaryLabel.isVisible = true
          secondaryLabel.isManaged = true
        }
        VideoTrimmer.TrimMode.KEEP_RANGE -> {
          primaryLabel.text = "区间开始时间"
          secondaryLabel.text = "区间结束时间"
          secondaryTimeBox.isVisible = true
          secondaryTimeBox.isManaged = true
          secondaryLabel.isVisible = true
          secondaryLabel.isManaged = true
        }
      }
    }

    refreshModeView(modeCombo.value)
    modeCombo.valueProperty().addListener { _, _, newMode -> refreshModeView(newMode ?: VideoTrimmer.TrimMode.HEAD) }

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
    val startButton = Button("开始裁剪").apply { styleClass += "primary-button" }
    val clearLogButton = Button("清空日志").apply { styleClass += "ghost-button" }

    rootBrowseButton.setOnAction {
      val chooser = DirectoryChooser().apply {
        title = "选择根目录"
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
      controller.startTrimming(
        rootField = rootField,
        outputField = outputField,
        ffmpegField = ffmpegField,
        modeCombo = modeCombo,
        primaryFields = TrimController.TimeFieldGroup(primaryHours, primaryMinutes, primarySeconds, primaryMillis),
        secondaryFields = TrimController.TimeFieldGroup(secondaryHours, secondaryMinutes, secondarySeconds, secondaryMillis),
        logArea = logArea,
        progressBar = progressBar,
        statusLabel = statusLabel,
        startButton = startButton
      )
    }
    clearLogButton.setOnAction { logArea.clear() }

    val formGrid = GridPane().apply {
      styleClass += "form-grid"
      hgap = 8.0
      vgap = 12.0
      add(Label("根目录"), 0, 0)
      add(rootField, 1, 0)
      add(rootBrowseButton, 2, 0)

      add(Label("输出目录"), 0, 1)
      add(outputField, 1, 1)
      add(outputBrowseButton, 2, 1)

      add(Label("裁剪模式"), 0, 2)
      add(modeCombo, 1, 2)

      add(primaryLabel, 0, 3)
      add(primaryTimeBox, 1, 3)

      add(secondaryLabel, 0, 4)
      add(secondaryTimeBox, 1, 4)

      add(Label("ffmpeg 路径"), 0, 5)
      add(ffmpegField, 1, 5)
    }
    GridPane.setHgrow(rootField, Priority.ALWAYS)
    GridPane.setHgrow(outputField, Priority.ALWAYS)
    GridPane.setHgrow(modeCombo, Priority.ALWAYS)
    GridPane.setHgrow(primaryTimeBox, Priority.ALWAYS)
    GridPane.setHgrow(secondaryTimeBox, Priority.ALWAYS)
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

    val bottomSection = VBox(8.0).apply {
      styleClass += "card-section"
      children += progressRow
      children += statusLabel
    }

    val container = VBox(20.0).apply {
      styleClass += "app-card"
      maxWidth = Double.MAX_VALUE
      prefWidth = Double.MAX_VALUE
      children += formSection
      children += Region().apply { styleClass += "separator-line" }
      children += logSection
      children += Region().apply { styleClass += "separator-line" }
      children += bottomSection
      VBox.setVgrow(logSection, Priority.ALWAYS)
    }

    javaClass.getResource("/styles/trim.css")?.toExternalForm()?.let { css ->
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

  private fun createModeCell(): ListCell<VideoTrimmer.TrimMode> {
    return object : ListCell<VideoTrimmer.TrimMode>() {
      override fun updateItem(item: VideoTrimmer.TrimMode?, empty: Boolean) {
        super.updateItem(item, empty)
        text = if (empty || item == null) {
          ""
        } else {
          when (item) {
            VideoTrimmer.TrimMode.HEAD -> "裁剪头部"
            VideoTrimmer.TrimMode.TAIL -> "裁剪尾部"
            VideoTrimmer.TrimMode.HEAD_TAIL -> "裁剪头尾"
            VideoTrimmer.TrimMode.KEEP_RANGE -> "取中间部分"
          }
        }
      }
    }
  }

  private fun createSpinner(min: Int, max: Int): Spinner<Int> {
    val spinner = Spinner<Int>()
    val valueFactory = IntegerSpinnerValueFactory(min, max, min)
    spinner.valueFactory = valueFactory
    spinner.isEditable = true
    spinner.prefWidth = 90.0
    val converter = IntegerStringConverter()
    val formatter = TextFormatter(converter, min) { change ->
      if (change.controlNewText.isEmpty()) {
        change
      } else if (change.controlNewText.matches(Regex("\\d+"))) {
        val value = change.controlNewText.toInt()
        if (value in min..max) change else null
      } else {
        null
      }
    }
    spinner.editor.textFormatter = formatter
    formatter.valueProperty().addListener { _, _, newValue ->
      if (newValue != null) {
        valueFactory.value = newValue
      }
    }
    spinner.focusedProperty().addListener { _, _, focused ->
      if (!focused) {
        val text = spinner.editor.text
        if (text.isEmpty()) {
          valueFactory.value = min
          spinner.editor.text = min.toString()
        }
      }
    }
    return spinner
  }

  private fun createTimeBox(
    hourSpinner: Spinner<Int>,
    minuteSpinner: Spinner<Int>,
    secondSpinner: Spinner<Int>,
    milliSpinner: Spinner<Int>
  ): HBox {
    val box = HBox(8.0)
    box.children += hourSpinner
    box.children += Label("小时")
    box.children += minuteSpinner
    box.children += Label("分钟")
    box.children += secondSpinner
    box.children += Label("秒")
    box.children += milliSpinner
    box.children += Label("毫秒")
    box.alignment = Pos.CENTER_LEFT
    return box
  }
}

