package com.aikrai

import atlantafx.base.theme.PrimerLight
import com.aikrai.core.VideoMerger
import com.aikrai.core.VideoConverter
import com.aikrai.ui.convert.ConvertController
import com.aikrai.ui.convert.ConvertView
import com.aikrai.ui.merge.MergeController
import com.aikrai.ui.merge.MergeView
import com.aikrai.ui.window.WindowHeader
import javafx.application.Application
import javafx.scene.Scene
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.layout.BorderPane
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import javafx.stage.Stage
import javafx.stage.StageStyle

/**
 * 程序入口（便于 IDE 直接运行 GUI）。
 *
 * @param args 启动参数
 */
fun main(args: Array<String>) {
  Application.launch(VideoMergeApp::class.java, *args)
}

/**
 * 应用入口：仅负责窗口与全局容器装配，页面具体实现拆分至各自视图与控制器。
 */
class VideoMergeApp : Application() {
  /** 核心：批量合并 ts 切片 */
  private val videoMerger = VideoMerger()
  /** 核心：视频格式转换 */
  private val videoConverter = VideoConverter()

  /**
   * 初始化主窗口与页面容器，设置全局样式与圆角裁剪。
   *
   * @param primaryStage JavaFX 主舞台
   */
  override fun start(primaryStage: Stage) {
    setUserAgentStylesheet(PrimerLight().userAgentStylesheet)
    primaryStage.initStyle(StageStyle.TRANSPARENT)
    primaryStage.title = "视频处理工具"

    val mergeViewNode = MergeView(primaryStage, MergeController(videoMerger)).createContent()
    val convertViewNode = ConvertView(primaryStage, ConvertController(videoConverter)).createContent()

    val mergeTab = Tab("ts批量合并").apply {
      content = mergeViewNode
      isClosable = false
    }
    val convertTab = Tab("格式转换").apply {
      content = convertViewNode
      isClosable = false
    }

    val tabPane = TabPane().apply {
      tabs.addAll(mergeTab, convertTab)
      tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE
      styleClass += "app-tab-pane"
    }

    val content = VBox().apply {
      styleClass += "app-container"
      children += tabPane
      VBox.setVgrow(tabPane, Priority.ALWAYS)
    }

    val clipRect = Rectangle(960.0, 680.0).apply {
      arcWidth = 24.0
      arcHeight = 24.0
    }

    val root = BorderPane().apply {
      styleClass += "app-background"
      top = WindowHeader.createWindowHeader(primaryStage, clipRect)
      center = content
      clip = clipRect
    }

    val scene = Scene(root, 960.0, 680.0)
    scene.fill = Color.TRANSPARENT
    clipRect.widthProperty().bind(scene.widthProperty())
    clipRect.heightProperty().bind(scene.heightProperty())
    javaClass.getResource("/styles/base.css")?.toExternalForm()?.let { scene.stylesheets += it }

    primaryStage.minWidth = 900.0
    primaryStage.minHeight = 640.0
    primaryStage.scene = scene
    primaryStage.show()
  }
}
