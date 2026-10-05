# 南风天气 (SkyPulse) v4.3.64 逆向分析报告

> **分析时间**: 2026-10-04
> **分析方式**: APK 解压 + aapt2 资源提取 + DEX 字符串/类名提取
> **目的**: 为每日天气 (Daily Weather) 的一比一复刻提供参考

---

## 一、基础信息

| 项目 | 南风 v4.3.64 | 每日天气 v0.1.11 |
|------|-------------|-----------------|
| 包名 | com.skypulse.weather | com.dailyweather.app |
| versionCode | 1964 | 11 |
| minSdk | 26 | 26 |
| targetSdk | 35 | 36 |
| compileSdk | 37 | 37 |
| APK 体积 | ~13MB | ~3.1MB (R8) |
| DEX 文件 | 3 个 | 1 个 |
| 资源文件 | 481 个 | ~30 个 |

---

## 二、天气动画引擎 (重点)

### 2.1 南风方案：预渲染 PNG 精灵图

南风使用**预渲染的 PNG 精灵帧序列**，分为三大类：

#### 天空背景帧 (weather_sky_*)
| 资源 | 大小 | 用途 |
|------|------|------|
| weather_sky_cumulus_01~08 | 264-330KB | 晴天/多云天空，8帧循环动画 |
| weather_sky_wisp_01~03 | 118-151KB | 薄云天空，3帧循环 |

> **素材结构逆向结论（实测像素分析）**：
> - 帧尺寸 720×808 = **上下两个 720×404 子图拼贴**；
> - 上半为不透明云场：云体编码在 **R 通道**（≈128 为天空基准，R>128 为云，亮部=云心、渐变=云缘），
>   G 通道为次级细节，B 通道为边缘光/轮廓层（大幅偏差）；
> - 下半为透明底的精灵式云（与 weather_cloud_* 同构）；
> - 全帧 43.9% 像素 alpha=0。
> 直接整幅贴图会得到乱彩/色偏（已实测翻车三次），正确做法是 **R 通道 alpha 提取 + 色彩矩阵上色**。

#### 云层精灵 (weather_cloud_*)
| 资源 | 大小 | 用途 |
|------|------|------|
| weather_cloud_cumulus_day_v1/v2/v3 | 66-84KB | 积云精灵，3个变体（纯灰度+alpha） |
| weather_cloud_cumulus_v1 | 113KB | 积云（夜间预烘焙暗色版） |
| weather_cloud_bank_day_v1/v2 | 78KB | 层积云（白天），2帧动画 |
| weather_cloud_bank_v2 | 123KB | 层积云（夜间） |
| weather_cloud_wisp_v1 | 89KB | 薄云精灵 |

> **云精灵实测**：R=G=B 三通道完全一致（纯灰度），63.3% 全透明——白天版白云、
> 夜间版预烘焙暗色，**不需要运行时 ColorFilter 染色**，昼/夜各取对应素材即可。

#### 降水精灵
| 资源 | 大小 | 用途 |
|------|------|------|
| weather_rain_streak_mgl | 735B | 雨滴条精灵（20×68 灰度+alpha，SrcIn tint 换色） |

### 2.2 每日天气：1:1 复刻南风精灵方案 ✅（v0.1.12）

每日天气**已完整复刻南风的 PNG 精灵架构**，旧的程序化云引擎
（CloudBaker / CloudSpecies / CloudSpriteCache / CloudLightRig，共 512 行）已删除：

| 南风实现 | 每日天气复刻 | 状态 |
|---------|-------------|------|
| `weather_sky_*` 8+3 帧序列 | `assets/skyframes/`（原样拷贝）+ `SpriteSkyBackground`（2.4s/帧 + 700ms Crossfade 轮播） | ✅ |
| R 通道云体 + 光照上色 | `cloudColorMatrix`（alpha = (R-128)×2 提取云形，RGB=tint 色） | ✅ |
| 光照档（昼/夜/天气 tint） | `cloudTint`（晴暖白 #F2EDE5 / 阴雨灰 #8A9098 / 夜暗灰 #6E7076）+ `phaseTint`/`weatherTint` 渐变叠加 | ✅ |
| `weather_cloud_*` 精灵组合 | `assets/cloudsprites/`（8 张原样拷贝）+ `spriteCloudsFor` 数据表（每天气一组：数量/高度带/尺寸带/alpha/速度） | ✅ |
| 视差漂移 + 边缘羽化 | `drawSpriteCloudLayer`（seed 哈希稳定布局，wrap 回卷 + 边界 alpha 淡入淡出） | ✅ |
| `weather_rain_streak_mgl` 雨丝 | `res/drawable-nodpi/` 原版 + `BlendMode.SrcIn` tint 换色（昼亮白/夜冷蓝） | ✅ |
| 粒子代码生成（雨滴流星/雪花/辉光） | `ParticleSprites`（保留——与南风"粒子全部代码生成"结论同构） | ✅ |
| 资源预热 | `SpriteAssets.prewarm`（IO 线程解码 11 帧+8 精灵，对齐 SkyPulseApp 预热） | ✅ |

### 2.3 对比

| 维度 | 南风 | 每日天气（复刻后） |
|------|------|------------------|
| 天空背景 | PNG 帧序列轮播 | ✅ 同（南风原素材） |
| 云层 | PNG 精灵组合漂移 | ✅ 同（南风原素材） |
| 降水 | 雨丝素材+程序化流星/雪花 | ✅ 同 |
| 光照 | 素材预烘焙 × 光照档 | ✅ 同（colorMatrix 复现） |
| APK 增量 | — | +2.6MB（11 帧 + 8 精灵 + 1 雨丝） |

**复刻过程中的关键实测结论**（都是"想当然会翻车"的点）：
1. 天空帧不是照片，是通道编码图——必须 R 通道 alpha 提取，直接贴图乱彩；
2. `Modifier.matchParentSize()` 只在 Box 直接子项合法——Crossfade content 内用
   `fillMaxSize()`，否则尺寸算 0、帧静默不渲染；
3. 大 PNG 必须异步预热——主线程同步解码 11 张 2.3MB 帧必 ANR；
4. 夜/雨 tint 不能过重——80% 不透明度会盖平云纹理，实测 55% 上限。

---

## 三、数据模型架构

### 3.1 南风模型清单 (从 DEX 提取)

**核心天气模型** (与每日天气对齐):
- `RealtimeWeather` ✅ 对齐
- `DailyForecast` / `DailyTemperature` / `DailySkycon` / `DailyWind` / `DailyPrecipitation` ✅ 对齐
- `HourlyForecast` / `HourlyValue` / `HourlyWind` / `HourlyUvItem` / `HourlyAqiValue` ✅ 对齐
- `MinutelyForecast` ✅ 对齐
- `AirQuality` / `AirQualityIndex` / `AirQualityDescription` ✅ 对齐
- `Alert` / `CaiyunAlert` / `AlertContent` / `AlertLocalizedData` ✅ 对齐（每日天气有 Alert）
- `City` ✅ 对齐

**每日天气缺少的模型** (可考虑补齐):
| 南风模型 | 用途 | 优先级 |
|---------|------|--------|
| `GlowBbox` / `GlowPeriod` / `GlowPredictDate` | 彩云极光预报 | 低 |
| `TideDay` / `TidePort` | 潮汐数据 | 低 |
| `CaiyunLifeIndexDay` / `CaiyunLifeIndexItem` | 生活指数（穿衣/洗车/晾晒等） | 中 |
| `MojiAqiCommon` / `MojiAqiDetailItem` | 墨迹天气 AQI 详情 | 低（我们已用小米源） |

### 3.2 存储架构

南风使用 **Room 数据库** (`AppDatabase`)，每日天气使用 **DataStore**：
- 南风：SQLite + Room 注解，支持 Room 自动迁移
- 每日天气：DataStore Preferences，零依赖
- **结论**: 当前规模 DataStore 足够，无需换装

---

## 四、小组件架构

### 4.1 南风组件清单 (8个)

| 组件 | 尺寸 | 特色 |
|------|------|------|
| `WeatherWidgetProvider` (small) | 1x1 | 温度+天气图标 |
| `WeatherWidget2x1Provider` | 2x1 | 图标+温度+高低 |
| `WeatherWidgetMediumProvider` | 2x2 | 详细天气信息 |
| `WeatherWidget4x2Provider` | 4x2 | 含日/小时预报 |
| `WeatherWidgetIOSProvider` | 2x2 | iOS 风格圆角 |
| `WeatherWidgetHourlyProvider` | 4x2 | 24小时趋势 |
| `WeatherWidgetWeekProvider` | 4x2 | 7日预报 |
| `WeatherWidgetAqiProvider` | 2x1 | AQI 专项 |

### 4.2 组件布局关键参数

```
共同结构:
├── FrameLayout (clipChildren=true, clipToPadding=false)
│   ├── FrameLayout (clipToOutline=true, background=widget_rounded_bg)
│   │   ├── ImageView (背景图, scaleType=centerCrop)
│   │   └── LinearLayout (内容层)
│   │       ├── 温度: 42sp / 26sp (大字/中字)
│   │       ├── 图标: 32dp (2x1)
│   │       ├── 边距: 12-16dp (内边距), 8-14dp (外边距)
│   │       └── TextClock: 42sp (week组件用系统时钟)
```

### 4.3 每日天气对比

每日天气当前有:
- `WeatherWidgetProviders.kt` + `WidgetRenderer.kt`
- 支持: hourly, week, small, medium, ios
- **缺少**: aqi 专项组件、4x2 大组件

---

## 五、通知系统

### 5.1 南风通知架构

| Worker | 用途 |
|--------|------|
| `DailyWeatherNotificationWorker` | 每日天气推送 |
| `PersistentWeatherWorker` | 常驻天气通知 |
| `UrgentNotificationWorker` | 紧急预警推送 |
| `WeatherNotificationWorker` | 通用天气通知 |
| `LiveUpdateDismissReceiver` | 实时更新关闭 |

### 5.2 每日天气通知

- `Notifications.kt` + `UrgentNotificationWorker`
- 已对齐：urgent 去重推送
- **可增强**: 常驻天气通知 (对应 `PersistentWeatherWorker`)

---

## 六、UI 对比

### 6.1 南风资源清单

**天气图标** (assets/weather/):
- 15 个图标：clear-day/night, partly-cloudy-day/night, overcast, rain, drizzle, extreme-rain, thunderstorms-rain, snow, fog, haze, wind, rain-snow, night-drizzle
- 与每日天气完全一致 ✅

**Lucide 图标** (assets/lucide/):
- 39 个图标，与每日天气完全一致 ✅

**SF Symbols** (assets/sf/):
- 21 个生活指数图标：allergens, car, camera, fan, fish, fork.knife, heart, jacket, pills, sailboat, sun.max, sunrise, tshirt, umbrella, wineglass 等
- 每日天气已对齐 ✅

**Sun 资源** (assets/sun/):
- sun.png, moon.png, sunrise.png, sunset.png, sunrise-yellow.png, sunset-yellow.png
- 每日天气已有类似资源 ✅

### 6.2 启动画面

南风有完整的 Splash Screen：
- `splash_screen_view` 布局
- `splash_gradient` 渐变背景
- `splash_icon_transparent` 透明图标
- 使用 `windowSplashScreen*` 属性（Android 12+ SplashScreen API）

**每日天气缺少启动画面**，可考虑添加。

### 6.3 圆角与间距

从组件布局提取的南风间距参数：
```
内边距 (padding):   12-16dp
外边距 (margin):    8-14dp
图标大小:           32dp (2x1), 42dp (small)
温度字号:           42sp (大), 26sp (中)
组件圆角:           clipToOutline=true (由 drawable 定义)
背景:               widget_rounded_bg (圆角 drawable)
```

每日天气当前参数 (Tokens.kt)：
```
CardRadius:         22dp
SettingsCardRadius: 28dp
ScreenMargin:       16dp
CardGap:            10dp
```

**结论**: 我们的间距和圆角参数与南风基本一致，无需大改。

---

## 七、库依赖对比

### 7.1 南风核心库 (从 DEX 识别)

| 库 | 用途 | 每日天气状态 |
|----|------|------------|
| androidx.compose | UI | ✅ 已使用 |
| androidx.room | 数据库 | ❌ 用 DataStore |
| androidx.work | 后台任务 | ✅ 已使用 |
| io.github.kyant0:backdrop-android | Liquid Glass 背景 | ✅ 已使用 |
| io.github.kyant0:shapes-android | 形状动画 | ✅ 已使用 |
| okhttp3 / retrofit | 网络 | ❌ 用 CloudProxy |
| gson | JSON | ❌ 用 JSON org.json |
| play-services-location | 定位 | ❌ 用高德定位 |
| play-services-tasks | 任务 | ❌ 不需要 |

---

## 八、改进建议 (优先级排序)

### P0 - 高优先级 (立即做)

1. **启动画面 (Splash Screen)**
   - 南风有完整的 SplashScreen API 实现
   - 可添加：蓝色渐变 + 云朵图标 + 渐变消失动画
   - 提升首次启动体验

2. **AQI 专项小组件**
   - 南风有独立的 `WeatherWidgetAqiProvider`
   - 每日天气缺少，可补充一个 2x1 AQI 组件

3. **生活指数增强**
   - 南风有 `CaiyunLifeIndexDay` 等详细生活指数
   - 每日天气的 `LifeAdviceFrom` 已实现，但数据来源可扩展

### P1 - 中优先级 (近期做)

4. **天空背景预烘焙帧**
   - 引入 2-3 张晴天/多云天空 PNG 作为底层
   - 粒子层动态叠加
   - 提升写实感，同时保持灵活性

5. **常驻天气通知**
   - 对应南风的 `PersistentWeatherWorker`
   - 通知栏常驻显示当前天气

6. **4x2 大型小组件**
   - 补充 `WeatherWidget4x2Provider`
   - 包含日预报+小时预报

### P2 - 低优先级 (后续做)

7. **极光/潮汐数据模型**
   - `GlowBbox` / `TideDay` 等
   - 国内用户不太需要

8. **墨迹天气 AQI 源**
   - 我们已有小米源 AQI
   - 可考虑多源互补

---

## 九、关键文件索引

提取的关键文件：
```
analysis/aapt2_resources.txt        - 完整资源清单 (5292行)
analysis/dex_packages.txt           - DEX 类名清单 (512个)
analysis/drawables.txt              - Drawable 资源列表
analysis/strings_raw.txt            - 提取的字符串
res_raw/res/                        - 完整 res 资源
res_raw/assets/weather/             - 天气图标 (15个)
res_raw/assets/lucide/              - Lucide 图标 (39个)
res_raw/assets/sf/                  - SF Symbols (21个)
```

---

## 十、结论

南风天气 v4.3.64 的核心差异集中在**天气动画引擎**（PNG 精灵 vs 程序化粒子）和**数据模型覆盖**（极光/潮汐/生活指数）。在 UI 层面，我们的参数已与南风基本一致。

**建议路线**:
1. 先补齐功能缺口（启动画面、AQI 组件、生活指数增强）
2. 再考虑动画引擎升级（预烘焙+动态混合）
3. 保持程序化方案的核心优势（轻量、灵活），只在外层引入预烘焙帧增强质感
