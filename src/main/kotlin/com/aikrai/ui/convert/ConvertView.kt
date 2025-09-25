package com.aikrai.ui.convert

import com.aikrai.ui.common.UiConstants
import com.aikrai.ui.common.UiUtils.findExistingDirectory
import com.aikrai.ui.common.UiUtils.showDirectoryChooserSafely
import javafx.collections.FXCollections
import javafx.geometry.Pos
import javafx.scene.Cursor
import javafx.scene.Node
import javafx.scene.control.*
import javafx.scene.input.TransferMode
import javafx.scene.layout.*
import javafx.stage.DirectoryChooser
import javafx.stage.FileChooser
import javafx.stage.Stage
import java.io.File
import java.nio.file.Path
import java.util.Locale

/**
 * 转换页视图：仅负责 UI 组件与布局；执行逻辑交给控制器。
 *
 * 视图不包含业务逻辑，便于独立测试与维护。
 */
class ConvertView(
  private val stage: Stage,
  private val controller: ConvertController
) {
  /**
   * 创建转换页的根内容节点。
   *
   * @return 可直接嵌入到 Tab 的 JavaFX 节点
   */
  fun createContent(): Node {
    val fileList = ListView<Path>(FXCollections.observableArrayList()).apply {
      selectionModel.selectionMode = SelectionMode.MULTIPLE
      placeholder = Label("请选择待转换的视频文件")
      prefHeight = 220.0
      styleClass += "list-surface"
    }
    val addButton = Button("添加文件…").apply { styleClass += "primary-button" }
    val removeButton = Button("移除选中").apply { styleClass += "ghost-button" }
    val clearButton = Button("清空列表").apply { styleClass += "ghost-button" }

    val dragSupportedExtensions = (UiConstants.CONVERSION_FORMATS + listOf("ts")).map { it.lowercase(Locale.ROOT) }.toSet()

    fun normalize(path: Path): Path = path.toAbsolutePath().normalize()

    fun addFilesToList(files: List<File>) {
      if (files.isEmpty()) return
      val existing = fileList.items.map { normalize(it) }.toMutableSet()
      files.asSequence()
        .filter { it.isFile }
        .map { file -> file to file.extension.lowercase(Locale.ROOT) }
        .filter { (_, ext) -> ext.isNotEmpty() && dragSupportedExtensions.contains(ext) }
        .map { (file, _) -> normalize(file.toPath()) }
        .filter { existing.add(it) }
        .forEach { fileList.items.add(it) }
    }

    addButton.setOnAction {
      val chooser = FileChooser().apply {
        title = "选择待转换视频"
        extensionFilters.add(
          FileChooser.ExtensionFilter(
            "常见视频格式",
            "*.mp4",
            "*.mkv",
            "*.mov",
            "*.avi",
            "*.webm",
            "*.flv",
            "*.ts"
          )
        )
      }
      val selected = chooser.showOpenMultipleDialog(stage)
      if (!selected.isNullOrEmpty()) addFilesToList(selected)
    }

    removeButton.setOnAction {
      val selectedItems = fileList.selectionModel.selectedItems.toList()
      fileList.items.removeAll(selectedItems)
    }

    clearButton.setOnAction { fileList.items.clear() }

    fileList.setOnDragOver { event ->
      val dragboard = event.dragboard
      if (dragboard.hasFiles() && dragboard.files.any { it.isFile && dragSupportedExtensions.contains(it.extension.lowercase(Locale.ROOT)) }) {
        event.acceptTransferModes(TransferMode.COPY)
      }
      event.consume()
    }

    fileList.setOnDragDropped { event ->
      val dragboard = event.dragboard
      if (dragboard.hasFiles()) {
        addFilesToList(dragboard.files)
        event.isDropCompleted = true
      } else {
        event.isDropCompleted = false
      }
      event.consume()
    }

    val outputField = TextField().apply { promptText = "可选：转换结果输出目录，不填写回原目录" }
    val outputBrowseButton = Button("选择…")

    outputBrowseButton.setOnAction {
      val chooser = DirectoryChooser().apply {
        title = "选择输出目录"
        findExistingDirectory(outputField.text, System.getProperty("user.home"))?.let { initialDirectory = it }
      }
      val selected = showDirectoryChooserSafely(chooser, stage)
      if (selected != null) outputField.text = selected.absolutePath
    }

    val formatCombo = ComboBox(FXCollections.observableArrayList(UiConstants.CONVERSION_FORMATS)).apply {
      value = UiConstants.DEFAULT_FORMAT
    }
    val ffmpegField = TextField().apply { promptText = "可选：自定义 ffmpeg 路径，不填使用内置" }

    val logArea = TextArea().apply {
      isEditable = false
      prefRowCount = 12
      minHeight = 140.0
      prefHeight = 220.0
      prefWidth = Double.MAX_VALUE
      maxWidth = Double.MAX_VALUE
      isWrapText = true
      styleClass += "log-area"
    }
    val progressBar = ProgressBar(0.0).apply {
      prefWidth = 420.0
      maxWidth = Double.MAX_VALUE
      styleClass += "accent-progress-bar"
    }
    val statusLabel = Label("等待开始").apply { styleClass += "status-label" }
    val startButton = Button("开始转换").apply { styleClass += "primary-button" }
    val clearLogButton = Button("清空日志").apply { styleClass += "ghost-button" }
    clearLogButton.setOnAction { logArea.clear() }

    startButton.setOnAction {
      controller.startConversion(fileList, formatCombo, outputField, ffmpegField, logArea, progressBar, statusLabel, startButton)
    }

    val listToolbar = HBox(10.0, addButton, removeButton, clearButton).apply {
      styleClass += "toolbar"
      alignment = Pos.CENTER_LEFT
    }

    val listContainer = VBox(12.0).apply {
      styleClass += "card-section"
      children += Label("待转换文件").apply { styleClass += "section-title" }
      children += listToolbar
      children += fileList
      VBox.setVgrow(fileList, Priority.ALWAYS)
    }

    val formGrid = GridPane().apply {
      styleClass += "form-grid"
      add(Label("输出目录"), 0, 0)
      add(outputField, 1, 0)
      add(outputBrowseButton, 2, 0)

      add(Label("输出格式"), 0, 1)
      add(formatCombo, 1, 1)

      add(Label("ffmpeg 路径"), 0, 2)
      add(ffmpegField, 1, 2)
    }
    GridPane.setHgrow(outputField, Priority.ALWAYS)
    GridPane.setHgrow(formatCombo, Priority.ALWAYS)
    GridPane.setHgrow(ffmpegField, Priority.ALWAYS)

    val formSection = VBox(12.0).apply {
      styleClass += "card-section"
      children += formGrid
    }

    val buttonBar = HBox(12.0, startButton, clearLogButton).apply {
      styleClass += "toolbar"
      alignment = Pos.CENTER_LEFT
    }

    val logResizeHandle = Region().apply {
      styleClass += "log-resize-handle"
      cursor = Cursor.V_RESIZE
      prefHeight = 8.0
      maxWidth = Double.MAX_VALUE
    }

    val minLogHeight = 140.0
    val maxLogHeight = 1200.0
    logArea.minHeight = minLogHeight
    logArea.maxHeight = maxLogHeight
    var dragStartY = 0.0
    var dragStartHeight = logArea.prefHeight

    logResizeHandle.setOnMousePressed { event ->
      dragStartY = event.screenY
      dragStartHeight = logArea.prefHeight.takeIf { it > 0 } ?: logArea.height
      event.consume()
    }

    logResizeHandle.setOnMouseDragged { event ->
      val delta = event.screenY - dragStartY
      val newHeight = (dragStartHeight + delta).coerceIn(minLogHeight, maxLogHeight)
      logArea.prefHeight = newHeight
      event.consume()
    }

    logResizeHandle.setOnMouseClicked { event ->
      if (event.clickCount == 2) {
        logArea.prefHeight = 220.0
        event.consume()
      }
    }

    val logContainer = VBox().apply {
      styleClass += "log-area-container"
      spacing = 0.0
      maxWidth = Double.MAX_VALUE
      children += logArea
      children += logResizeHandle
    }

    val logSection = VBox(12.0).apply {
      styleClass += "card-section"
      children += Label("运行日志").apply { styleClass += "section-title" }
      children += logContainer
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

    val content = VBox(20.0).apply {
      styleClass += "app-card"
      maxWidth = Double.MAX_VALUE
      prefWidth = Double.MAX_VALUE
      children += listContainer
      children += Region().apply { styleClass += "separator-line" }
      children += formSection
      children += buttonBar
      children += Region().apply { styleClass += "separator-line" }
      children += logSection
      children += Region().apply { styleClass += "separator-line" }
      children += bottomSection
      VBox.setVgrow(listContainer, Priority.ALWAYS)
    }

    // 按需加载转换页样式
    javaClass.getResource("/styles/convert.css")?.toExternalForm()?.let { css ->
      content.stylesheets += css
    }

    return ScrollPane(content).apply {
      styleClass += "app-scroll-pane"
      hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
      vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
      isFitToWidth = true
      viewportBoundsProperty().addListener { _, _, bounds ->
        content.minHeight = bounds.height
      }
    }
  }
}
