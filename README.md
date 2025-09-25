# 视频处理工具（TS 合并 + 格式转换）

<div style="display: flex; justify-content: space-between;">
<img src="https://pixel-oss.aikrai.com/picgo/20250925155920592.png" width="48%" alt="ts视频合并页">
<img src="https://pixel-oss.aikrai.com/picgo/20250925155941972.png" width="48%" alt="视频格式转换页">
</div>

一个基于 Kotlin/JavaFX 的轻量级桌面工具，提供 TS 切片批量合并与常见视频格式转换能力，并配套命令行入口，支持在 Windows上运行（需 JDK 21+）。

## 功能特性

- TS 批量合并（并发处理叶子目录，自动自然序排序）
- 视频格式转换（mp4/mkv/mov/avi/webm/flv，按策略自动回退）
- 自定义 ffmpeg/ffprobe 路径（显式传参/环境变量/内置/系统命令自动解析）
- 图形界面（JavaFX）：拖拽添加、可调日志区、扁平化样式
- 命令行（CLI）：适合脚本化批处理与集成

## 技术栈

- Kotlin/JVM（JDK 21）
- JavaFX（atlantafx-base 主题）
- FFmpeg/FFprobe（通过 JavaCV 平台二进制或系统安装）
- Gradle 构建

## 快速开始

### 环境要求

- JDK 21（含 jpackage 可选）
- Gradle（本仓库未包含 wrapper，可使用系统 Gradle）
- 可选：本机安装 ffmpeg/ffprobe，或使用内置(JavaCV)二进制

### 构建

```bash
# 构建（忽略测试）
gradle build -x test
```

### 运行 GUI

```bash
# 以 JavaFX GUI 方式运行
gradle run
```

### 运行 CLI

```bash
# 合并：递归扫描 root 下叶子目录，收集并合并 .ts 文件
gradle runCli --args="merge --root D:/videos --format mp4 --output D:/out --ffmpeg C:/ffmpeg/bin/ffmpeg.exe"

# 转换：将多个输入文件转换为指定容器格式（可选输出目录）
gradle runCli --args="convert --inputs D:/a.mp4 D:/b.mkv --format webm --output D:/out"
```

- `--ffmpeg` 可省略，程序将按“显式入参 > 环境变量 > 内置 > 系统命令名”解析
- 若未指定 `--output`，结果将写回源目录（并避免覆盖同名）

## 包结构与模块划分

核心服务（无 UI 依赖）

- `com.aikrai.core.FfmpegSupport`：ffmpeg/ffprobe 路径解析与推导
- `com.aikrai.core.VideoMerger`：TS 批量合并服务（并发、自然序排序、报告）
- `com.aikrai.core.VideoConverter`：视频格式转换服务（多策略回退、编码器可用性缓存）

界面层（JavaFX）

- `com.aikrai.VideoMergeApp`：应用主入口（GUI 装配）
- `com.aikrai.ui.window.WindowHeader`：无框窗口标题栏组件
- `com.aikrai.ui.merge.MergeView` / `MergeController`：合并页视图/控制器
- `com.aikrai.ui.convert.ConvertView` / `ConvertController`：转换页视图/控制器
- `com.aikrai.ui.common.UiConstants` / `UiUtils`：UI 常量与通用工具

命令行入口

- `com.aikrai.VideoMergeCli`：CLI 主程序（merge/convert 两类命令）

### 主要文件/目录

```
src/
├─ main/
│  ├─ kotlin/
│  │  └─ com/aikrai/
│  │     ├─ VideoMergeApp.kt                # GUI 入口
│  │     ├─ VideoMergeCli.kt                # CLI 入口
│  │     ├─ core/
│  │     │  ├─ FfmpegSupport.kt            # ffmpeg/ffprobe 路径解析
│  │     │  ├─ VideoMerger.kt              # TS 合并核心
│  │     │  └─ VideoConverter.kt           # 格式转换核心
│  │     └─ ui/
│  │        ├─ common/                     # UI 通用常量/工具
│  │        ├─ window/                      # 窗口标题栏组件
│  │        ├─ merge/                       # 合并页视图/控制器
│  │        └─ convert/                     # 转换页视图/控制器
│  └─ resources/
│     └─ styles/
│        ├─ base.css                        # 全局基础样式（已从 app.css 重命名）
│        ├─ merge.css                       # 合并页样式（预留位）
│        └─ convert.css                     # 转换页样式
└─ test/
```

## 运行时配置

- 环境变量
  - `FFMPEG_PATH`：自定义 ffmpeg 可执行路径
  - `FFPROBE_PATH`：自定义 ffprobe 可执行路径
- GUI 表单
  - 可直接在页面中填写 ffmpeg 路径与输出目录
- CLI 选项
  - `--ffmpeg <path>`：显式指定 ffmpeg
  - `--output <dir>`：统一输出目录

解析优先级：显式入参 > 环境变量 > 内置(JavaCV) > 系统命令名（`ffmpeg`/`ffprobe`）

## 打包与分发（可选）

本项目内置 jpackage 任务用于生成可分发的应用镜像/安装包（需 JDK 含 jpackage）。

```bash
# 准备依赖（聚合 Jar 与运行时依赖）
gradle build -x test

# 生成 app-image 目录（免安装可运行目录）
gradle packageAppImage

# 生成 Windows 安装包（.exe），需在 Windows 上执行
gradle packageInstaller
```

产物位置：`build/jpackage/output/VideoMergeTool/`（app-image）与 `build/jpackage/installer/`（安装包）。

## 使用建议与最佳实践

- TS 合并前请确保切片按自然序命名（如 1.ts、2.ts、10.ts），工具会自动正确排序
- 转换优先尝试“封装复制”，失败再回退到重编码策略，以尽量保留质量、提升速度
- 日志区支持拖拽调整高度（双击还原），方便查看
- 大批量任务建议优先使用 CLI 以便脚本集成

## 常见问题排查（FAQ）

- 找不到 ffmpeg/ffprobe
  - 方案：GUI/CLI 显式指定路径；或设置环境变量；或安装到系统 PATH
- 合并/转换失败，提示编码器缺失
  - 方案：安装含对应编码器的 ffmpeg 发行版；或选择“封装复制”策略（mp4/mkv 等）
- Windows 路径包含空格
  - 方案：CLI 传参时用引号包裹路径

## 版本信息

- JDK: 21
- Kotlin: 1.9.25（Gradle 插件）
- JavaFX: 21.0.2
- JavaCV: 1.5.10
