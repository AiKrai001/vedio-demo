import org.gradle.api.tasks.JavaExec

plugins {
  kotlin("jvm") version "1.9.25"
  application
  id("org.openjfx.javafxplugin") version "0.1.0"
}

group = "com.aikrai"
version = "0.0.1-SNAPSHOT"
description = "vedio-pj"

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
  implementation("org.bytedeco:javacv-platform:1.5.10")
  implementation("org.slf4j:slf4j-simple:2.0.16")

  testImplementation("org.jetbrains.kotlin:kotlin-test")
  testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.0")
}

javafx {
  version = "21.0.2"
  modules = listOf("javafx.controls")
}

application {
  mainClass.set("com.aikrai.VideoMergeGuiLauncherKt")
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
