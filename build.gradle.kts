// 根构建文件——只声明插件版本（应用在 :app）。
// 与 D:\DEV\wui 同一已验证工具链：AGP 9.x 内置 Kotlin，
// 不允许再 apply org.jetbrains.kotlin.android（会与内置 Kotlin 冲突）。
// Compose 编译器经 org.jetbrains.kotlin.plugin.compose 接入，
// 版本必须跟随 AGP 内置 Kotlin（2.2.10 @ AGP 9.4.0）。
plugins {
    id("com.android.application") version "9.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
