package com.aikrai.ui.window

import javafx.application.Platform
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.Tooltip
import javafx.scene.input.MouseButton
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import javafx.scene.shape.Rectangle

/**
 * 无框窗口的自定义标题栏组件，包含标题、拖拽、最小化/最大化/关闭控制。
 *
 * 该组件仅负责窗口控制区 UI 与行为封装，便于在应用根布局中复用。
 */
object WindowHeader {
  /** 鼠标按下点与窗口左上角的水平偏移量 */
  private var dragOffsetX = 0.0
  /** 鼠标按下点与窗口左上角的垂直偏移量 */
  private var dragOffsetY = 0.0

  /**
   * 创建窗口标题栏。
   *
   * @param stage 目标窗口
   * @param clipRect 根节点裁剪圆角矩形，随最大化状态动态调整圆角
   * @return 含标题与控制按钮的标题栏容器
   */
  fun createWindowHeader(stage: Stage, clipRect: Rectangle): HBox {
    val titleLabel = Label(stage.title).apply {
      styleClass += "window-title"
      textProperty().bind(stage.titleProperty())
    }

    val titleRegion = StackPane().apply {
      styleClass += "window-drag-region"
      alignment = Pos.CENTER_LEFT
      children += titleLabel
      HBox.setHgrow(this, Priority.ALWAYS)
    }

    val minimizeButton = createWindowButton("−", "最小化").apply {
      setOnAction { stage.isIconified = true }
    }

    val maximizeButton = createWindowButton(if (stage.isMaximized) "▣" else "□", "最大化").apply {
      styleClass += "maximize"
      setOnAction { stage.isMaximized = !stage.isMaximized }
    }

    val closeButton = createWindowButton("×", "关闭").apply {
      styleClass += "close"
      setOnAction { Platform.exit() }
    }

    stage.maximizedProperty().addListener { _, _, maximized ->
      maximizeButton.text = if (maximized) "▣" else "□"
      val arc = if (maximized) 0.0 else 24.0
      clipRect.arcWidth = arc
      clipRect.arcHeight = arc
    }
    if (stage.isMaximized) {
      clipRect.arcWidth = 0.0
      clipRect.arcHeight = 0.0
    }

    bindWindowDrag(titleRegion, stage)

    return HBox(6.0).apply {
      styleClass += "window-header"
      alignment = Pos.CENTER_LEFT
      children += titleRegion
      children += minimizeButton
      children += maximizeButton
      children += closeButton
    }
  }

  /**
   * 创建标题栏按钮（带统一样式与提示）。
   *
   * @param symbol 文本符号
   * @param tooltipText 提示文本
   */
  private fun createWindowButton(symbol: String, tooltipText: String): javafx.scene.control.Button {
    return Button(symbol).apply {
      styleClass += "window-control-button"
      isFocusTraversable = false
      tooltip = Tooltip(tooltipText)
    }
  }

  /**
   * 绑定标题区拖拽/双击最大化行为。
   *
   * @param region 可拖拽区域
   * @param stage 目标窗口
   */
  private fun bindWindowDrag(region: Region, stage: Stage) {
    region.setOnMousePressed { event ->
      if (event.button != MouseButton.PRIMARY) return@setOnMousePressed
      dragOffsetX = event.sceneX
      dragOffsetY = event.sceneY
    }

    region.setOnMouseDragged { event ->
      if (event.button != MouseButton.PRIMARY || stage.isMaximized) return@setOnMouseDragged
      stage.x = event.screenX - dragOffsetX
      stage.y = event.screenY - dragOffsetY
    }

    region.setOnMouseClicked { event ->
      if (event.button == MouseButton.PRIMARY && event.clickCount == 2 && stage.isResizable) {
        stage.isMaximized = !stage.isMaximized
      }
    }
  }
}
