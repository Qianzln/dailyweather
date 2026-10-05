// 根构建文件——只声明插件版本（应用在 :app）。
// AGP 9.x 允许使用独立 Kotlin 插件版本（不再强制使用内置版本）。
// Compose 编译器经 org.jetbrains.kotlin.plugin.compose 接入。
plugins {
    id("com.android.application") version "9.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}
