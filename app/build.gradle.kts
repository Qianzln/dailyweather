import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---- 仓库外凭据（keys.properties 已 gitignore）------------------------------------------
// 彩云 token、云代理 URL 等外部凭据只进本地文件，不进仓库、不进 VCS。
// 未配置时对应功能显示"数据源待配置"，不崩溃。
val keys = Properties().apply {
    val f = rootProject.file("keys.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// ---- release 正式签名 ---------------------------------------------------------------------------
// 与 wui 同一口径（签名守门）：
// - assembleDebug   → 永远能编（debug 签名）；
// - assembleRelease → 没有正式签名**直接失败**（verifyReleaseSigning），绝不产出
//   "debug 签名的 release"废包。密钥库与口令都在仓库外 keys.properties + keystore/。
val releaseStoreFile = rootProject.file(keys.getProperty("release_keystore_path", "keystore/dailyweather-release.jks"))
val releaseStorePassword = keys.getProperty("release_keystore_password", "")
val releaseKeyAlias = keys.getProperty("release_key_alias", "dailyweather")
val hasReleaseSigning = releaseStoreFile.exists() && releaseStorePassword.isNotBlank()

// 首个正式版先不混淆（依赖零反射、体积不敏感）；上架版再开 minify 并补规则。
android {
    namespace = "com.dailyweather.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dailyweather.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 19
        versionName = "0.1.20"
        buildConfigField("String", "CAIYUN_TOKEN", "\"${keys.getProperty("caiyun_token", "")}\"")
        buildConfigField("String", "AMAP_KEY", "\"${keys.getProperty("amap_key", "")}\"")
        // CloudBase 云代理（与微风天气同一已打通链路）：彩云/和风/小米/高德唯一出口
        buildConfigField("String", "CLOUDBASE_PROXY_URL", "\"${keys.getProperty("cloudbase_proxy_url", "")}\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = keys.getProperty("release_key_password", releaseStorePassword)
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// 签名守门：没有正式签名时 assembleRelease 直接失败，不产出废包。
tasks.register("verifyReleaseSigning") {
    doLast {
        if (!hasReleaseSigning) {
            throw GradleException(
                "release 未签名：keys.properties 缺 release_keystore_password 或密钥库文件不存在" +
                    "（${releaseStoreFile}）。拒绝产出 unsigned/debug 签名的 release。"
            )
        }
    }
}
tasks.matching { it.name == "validateSigningRelease" }.configureEach {
    dependsOn("verifyReleaseSigning")
}

dependencies {
    // 依赖集与 D:\DEV\wui 完全同源（本地 Gradle 缓存已验证），
    // Room/Hilt/Retrofit 在阶段二换装（当前用零依赖等价实现，拓扑一致）。
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-util")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    // Liquid Glass —— Kyant0/AndroidLiquidGlass
    implementation("io.github.kyant0:backdrop-android:2.0.1")
    implementation("io.github.kyant0:shapes-android:1.2.1")
}
