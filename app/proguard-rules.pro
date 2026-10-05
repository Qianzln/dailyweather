# DailyWeather - R8/ProGuard 规则
# 原则：保留所有 data class（JSON 反序列化依赖反射字段访问）和 Widget Provider。
# 不启用混淆时（isMinifyEnabled=false），此文件无效果，仅作为文档保留。

# ===== 数据模型（JSON 序列化/反序列化）=====
# WeatherSnapshot 及其所有嵌套类：SnapshotCodec、XiaomiProvider、CaiyunParser
# 都通过 optDouble/optString/optInt 等反射读取字段名，必须保留字段。
-keep class com.dailyweather.app.data.model.WeatherSnapshot { *; }
-keepclassmembers class com.dailyweather.app.data.model.WeatherSnapshot {
    public <init>(...);
}
-keep class com.dailyweather.app.data.model.WeatherSnapshot$* { *; }

# City 数据类（WeatherStore.fromJson 通过反射调用 fromJson 静态方法）
-keep class com.dailyweather.app.data.City { *; }
-keepclassmembers class com.dailyweather.app.data.City {
    public static com.dailyweather.app.data.City fromJson(org.json.JSONObject);
}

# SnapshotCodec（反射解析 JSON → WeatherSnapshot）
-keep class com.dailyweather.app.data.model.SnapshotCodec { *; }

# Fixture（调试 fixture 加载）
-keep class com.dailyweather.app.data.Fixture { *; }

# ===== 远程 provider =====
-keep class com.dailyweather.app.data.remote.XiaomiProvider { *; }
-keep class com.dailyweather.app.data.remote.CaiyunCloudProvider { *; }
-keep class com.dailyweather.app.data.remote.CloudProxy { *; }
-keep class com.dailyweather.app.data.remote.RadarGateway { *; }

# ===== 组件（RemoteViews 通过 findViewById 找 id，不能混淆 id；
#     但 WidgetRenderer 方法签名必须保留以防 R8 内联丢失）=====
-keep class com.dailyweather.app.widget.WidgetRenderer {
    public static *;
}
-keep class com.dailyweather.app.widget.WeatherWidgetProviders { *; }
-keep class com.dailyweather.app.widget.WidgetSyncWorker { *; }

# ===== AppContainer / DI =====
-keep class com.dailyweather.app.di.AppContainer { *; }

# ===== ViewModel =====
-keep class com.dailyweather.app.viewmodel.WeatherViewModel { *; }

# ===== MainActivity =====
-keep class com.dailyweather.app.MainActivity { *; }

# ===== Compose 相关：保留所有 @Composable 函数签名 =====
# R8 在 minifyEnabled=true 时会对 Compose 代码做激进内联，
# 保留入口点可避免"找不到方法"运行时崩溃。
-keep,allowobfuscation @interface androidx.compose.runtime.Composable
-keep,allowobfuscation @interface androidx.compose.runtime.Immutable
-keep,allowobfuscation @interface androidx.compose.runtime.Repeatable
-keep,allowobfuscation @interface androidx.compose.runtime.Stable
-keep,allowobfuscation @interface androidx.compose.runtime.Companion
-keep,allowobfuscation @interface androidx.compose.runtime.NonRestartableComposable
-keep,allowobfuscation @interface androidx.compose.runtime.ReadOnlyComposable

# ===== 通知相关 =====
-keep class com.dailyweather.app.notification.* { *; }

# ===== TTS =====
-keep class com.dailyweather.app.util.TtsSpeaker { *; }

# ===== 场景引擎 =====
-keep class com.dailyweather.app.scene.* { *; }

# ===== 避免混淆 kotlinx.coroutines 内部类 =====
-keep class kotlinx.coroutines.** { *; }

# ===== 避免混淆 org.json（非我们的代码，但用反射读字段）=====
-dontwarn org.json.**
