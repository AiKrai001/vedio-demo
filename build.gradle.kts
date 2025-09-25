import java.io.File
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Sync
import org.gradle.jvm.tasks.Jar

plugins {
  kotlin("jvm") version "1.9.25"
  application
  id("org.openjfx.javafxplugin") version "0.1.0"
}

group = "com.aikrai"
version = "0.0.1-SNAPSHOT"
description = "vedio-demo"

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(21)
  }
}

repositories {
  mavenCentral()
}

dependencies {
  implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
  // 编译期引入 FFmpeg Java 包（提供 org.bytedeco.ffmpeg.* 类）
  implementation("org.bytedeco:ffmpeg:6.1.1-1.5.10")
  // 运行期仅打入 Windows x86_64 的原生可执行/库，避免其他平台包
  runtimeOnly("org.bytedeco:ffmpeg:6.1.1-1.5.10:windows-x86_64")
  implementation("org.slf4j:slf4j-simple:2.0.16")
  implementation("io.github.mkpaz:atlantafx-base:2.0.1")

  testImplementation("org.jetbrains.kotlin:kotlin-test")
  testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.0")
}


javafx {
  version = "21.0.2"
  modules = listOf("javafx.controls")
}

application {
  mainClass.set("com.aikrai.VideoMergeAppKt")
}

kotlin {
  compilerOptions {
    freeCompilerArgs.addAll("-Xjsr305=strict")
  }
}

tasks.withType<Test> {
  useJUnitPlatform()
}

tasks.register<JavaExec>("runCli") {
  group = "application"
  description = "运行命令行合并工具"
  mainClass.set("com.aikrai.VideoMergeCliKt")
  classpath = sourceSets.main.get().runtimeClasspath
}

val runtimeClasspath = configurations.runtimeClasspath
val jarTask = tasks.named<Jar>("jar")

val prepareJpackage by tasks.registering(Sync::class) {
  group = "distribution"
  description = "收集 jpackage 所需的所有依赖"
  dependsOn("build")

  into(layout.buildDirectory.dir("jpackage/input"))

  from({ jarTask.get().archiveFile.get().asFile })
  from({ runtimeClasspath.get().files })
}

fun sanitizeVersion(raw: String): String =
  Regex("\\d+(\\.\\d+)*").find(raw)?.value ?: "1.0.0"

val packageAppImage by tasks.registering(Exec::class) {
  group = "distribution"
  description = "使用 jpackage 生成免安装的 Windows 可执行目录"
  dependsOn(prepareJpackage)

  val javaHome = System.getenv("JAVA_HOME") ?: System.getProperty("java.home")
  val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
  val jpackageExecutable = File(javaHome, if (isWindows) "bin/jpackage.exe" else "bin/jpackage")

  doFirst {
    require(jpackageExecutable.exists()) {
      "未找到 jpackage，可确认 JAVA_HOME 指向包含 jpackage 的 JDK。"
    }
    val outputDir = layout.buildDirectory.dir("jpackage/output").get().asFile
    val existingImage = File(outputDir, "VideoMergeTool")
    if (existingImage.exists()) {
      existingImage.deleteRecursively()
    }
    outputDir.mkdirs()
  }

  val inputDir = layout.buildDirectory.dir("jpackage/input").get().asFile
  val outputDir = layout.buildDirectory.dir("jpackage/output").get().asFile
  val mainJar = jarTask.get().archiveFile.get().asFile
  val jmodsDir = File(javaHome, "jmods")

  val modulePath = listOf(jmodsDir.absolutePath, inputDir.absolutePath)
    .joinToString(File.pathSeparator)

  val args = listOf(
    jpackageExecutable.absolutePath,
    "--type", "app-image",
    "--name", "VideoMergeTool",
    "--app-version", sanitizeVersion(version.toString()),
    "--input", inputDir.absolutePath,
    "--main-jar", mainJar.name,
    "--main-class", "com.aikrai.VideoMergeAppKt",
    "--dest", outputDir.absolutePath,
    "--module-path", modulePath,
    "--add-modules", "javafx.controls,javafx.graphics,javafx.base",
    "--java-options", "-Dfile.encoding=UTF-8"
  )

  commandLine(*args.toTypedArray())
}

val packageInstaller by tasks.registering(Exec::class) {
  group = "distribution"
  description = "使用 jpackage 生成单文件安装程序 (exe)"
  dependsOn(packageAppImage)

  val javaHome = System.getenv("JAVA_HOME") ?: System.getProperty("java.home")
  val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
  val jpackageExecutable = File(javaHome, if (isWindows) "bin/jpackage.exe" else "bin/jpackage")

  doFirst {
    require(jpackageExecutable.exists()) {
      "未找到 jpackage，可确认 JAVA_HOME 指向包含 jpackage 的 JDK。"
    }
    val outputDir = layout.buildDirectory.dir("jpackage/installer").get().asFile
    if (!outputDir.exists()) {
      outputDir.mkdirs()
    }
  }

  val outputDir = layout.buildDirectory.dir("jpackage/installer").get().asFile
  val appImageDir = layout.buildDirectory.dir("jpackage/output/VideoMergeTool").get().asFile

  val args = listOf(
    jpackageExecutable.absolutePath,
    "--type", "exe",
    "--name", "VideoMergeTool",
    "--app-version", sanitizeVersion(version.toString()),
    "--app-image", appImageDir.absolutePath,
    "--dest", outputDir.absolutePath,
    "--win-shortcut",
    "--win-menu"
  )

  commandLine(*args.toTypedArray())
}

