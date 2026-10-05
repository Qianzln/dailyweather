# 南风天气 (SkyPulse) v4.3.64 界面 UI 与动画专项分析

> 补充报告（2026-10-05）。基础逆向见 `SKYPULSE_V4.3.64_ANALYSIS.md`。
> 数据来源：`skypulse-v4.3.64.apk` 资源表 + `classes*.dex` 全量反汇编（dexdump），
> 方法类名被 R8 全量混淆（compose 库类亦为短名），故本报告以"资源事实 + 字符串证据 + 反汇编常量"为准。

## 一、核心结论（先看这个）

1. **南风 App 主体是 100% Jetpack Compose，没有任何界面级 XML 布局**。
   `layout/` 26 个资源里只有：8 个桌面小组件 + 8 个对应 preview + `splash_screen_view` +
   `custom_dialog` + 7 个系统通知模板（`notification_*`）+ 2 个 ime 测试（debug 依赖）。
   主界面/详情/设置全部是代码里的 Composable —— 想 1:1 抠 UI 只能靠真机截图逐卡对齐（项目已基本完成此路线）。
2. **动画全部在 Compose 层**。`animator/` 只有 6 个 `fragment_*`（androidx.navigation 库自带，
   不代表南风实际在用 Fragment 动画）。dex 字符串确认南风调用了：
   `AnimatedContent` / `Crossfade` / `AnimatedVisibility` / `animateColorAsState` /
   `animateDpAsState` / `InfiniteTransition.animateFloat`（都是 Compose animation 库的
   debug 源码位置字符串，出现即被引用）。
3. **场景（云/降水）是"数据规格驱动"**：一个全局配置持有类 `u7.ji` 持有
   **7 个云层规格**（`u7.ib`，一个枚举类型 + count + 8 个 float）+ **6 个降水/粒子规格**
   （`u7.qb`，3 int + 8 float + 颜色）+ **26 个每天气场景配置**（`Ld8/k`，字段 v~N，
   即晴/多云/阴/雨/雷/雪/雾/风等每套场景一套配色/参数）。
4. **当前每日天气最大的 UI 差距不是卡片，而是"小组件布局差异"**：南风 `widget_small`
   是 **3 行逐小时**结构（时间/图标/温度 ×3 行），我们 small 是单行结构 —— 见 §三。

## 二、界面栈（推断 + 资源佐证）

```
MainActivity (单 Activity)
├── 主界面（Compose，Home 屏）
│   ├── 天空背景层（精灵帧/云/降水/噪声，见动画 §四.1）
│   ├── 滚动卡片流：Hero 温度 → 短临降水 → 预警 → 48h → 7日
│   │   ├── AQI → 日出日落 → 生活建议 → 台风雷达等（截图逐卡对齐已验收）
│   └── 顶部：城市名胶囊 + 设置入口（右上）；下拉刷新（PullToRefresh）
├── 详情子屏（逐小时/空气质量/分钟级降水/台风路径 —— 与每日天气同名子屏一致）
├── 城市列表 / 城市搜索 / 城市管理（拖拽排序 + 长按删除）
└── 设置（定位/通知/外观/数据源/关于；检查更新）
```

资源佐证：`splash_screen_view`（core-splashscreen 启动图）、`custom_dialog`
（唯一非组件对话框 layout）、无其它 layout。图标体系 83 个 drawable：`weather_*` 天气图标
（assets/weather + weather_blue 双主题，与每日天气一致）+ `lucide` + `sf`。

### 2.1 小组件清单（南风 8 个 vs 每日天气 7+week）

| 南风 | 每日天气 | 备注 |
|------|---------|------|
| widget_small | small | **结构不同**（见下） |
| widget_2x1 | 2x1 | 一致（背景图 + 图标 + 温度） |
| widget_4x2 | 4x2 | 一致（顶部时钟块 + 3 数据块） |
| widget_medium | medium | 一致（顶部 + 2 行数据 + 5 格小时） |
| widget_ios | ios | 一致（顶部 + 5 行三列） |
| widget_aqi | (aqi 卡) | 双 ImageView（背景 + 前景光谱图） |
| widget_hourly | hourly | 一致（顶部卡 + 5 格） |
| widget_week | week | 一致（TextClock 大钟 + 6 列 + 底部行） |

**南风 widget_small 真实结构**（APK dump，非单行）：
```
FrameLayout (clipChildren)
└── FrameLayout (clipToOutline, 圆角背景 widget_rounded_bg)
    ├── ImageView (背景图, centerCrop)
    └── LinearLayout (内容)
        ├── 顶部：温度大字行 (TextView 42sp 级 + 图标 42dp)
        └── 3 × LinearLayout（逐小时）
            ├── TextView（时间）
            ├── ImageView（天气图标）
            └── TextView（温度）
```
→ 每日天气 small 组件如需 1:1，应从"单行"改成"顶部温度 + 3 行逐小时"。

## 三、组件的 UI 通用参数（APK 实测）

```
公共结构:  FrameLayout(clipChildren=true, clipToPadding=false)
            └─ FrameLayout(clipToOutline=true, background=widget_rounded_bg)
                 ├─ ImageView(scaleType=centerCrop)   ← 背景图
                 └─ LinearLayout 内容层
温度大字:   42sp（small/medium 顶部）、26sp（次级行、hourly 温度）
图标:       32dp（2x1）、42dp（small）
边距:       内 padding 12-16dp，外 margin 8-14dp
时钟:       widget_week/widget_4x2 用系统 TextClock（42sp 级）
```

## 四、动画体系

### 4.1 场景动画：南风全部是"规格 + 素材"驱动（v4.3.64 逆向参数表）

**云层规格 `u7.ib(type, count, 8×float, 1024)` —— 7 档完整参数（dex 常量）：**

| 字段 | 类型 | count | float 参数（逆序读出，语义为 高度带/尺寸/速度/α 组合） |
|------|------|-------|------|
| d | PHOTO_CUMULUS | **9** | 0.55, 0.60, 0.02, 0.22, 0.88, 0.10, 3.0, 4.0 |
| e | PHOTO_WISP | **3** | 0.80, 0.70, 0.005, 0.06, 0.32, 0.20, … |
| f | (晴日积云) | **9** | 0.60, 0.70, 0.0, 0.18, 0.94, … |
| g | (晴日云组) | **9** | 0.55, 0.60, 0.02, 0.22, 0.88, 0.10, … |
| i | STRATUS | **6** | 1.08, 0.58, 0.0, 0.10, 0.42, 0.28, … |
| j | CUMULUS | **7** | 1.08, 0.58, 0.02, 0.10, 0.55, 0.30, … |
| k | (强云) | **7** | 1.02, 0.56, …, 0.12, …, 0.24 |
| l | CIRRUS | **5** | 0.72, 0.62, 0.0, 0.10, 0.42, 0.22 |
| m | (近景) | **5** | 0.85, 0.65, …, 0.08, …, 0.20 |
| n | (薄云) | **3** | 0.85, 0.65, …, 0.08, …, 0.20 |

关键量级（每日天气 v0.1.13 前为 1-3 朵/层、速度 0.01-0.03、尺寸 0.3-0.6）：
**count 3~9、速度 0.005~0.02 屏宽/秒（极慢）、首参数 0.55~1.08（大）、alpha 0.42~0.94（高）**。
→ 已在 2026-10-05 落成 `SpriteClouds.spriteCloudsFor` 新版数据表。

**降水/粒子规格 `u7.qb(3×int, 8×float, color)` —— 6 档（dex 常量）：**

| 字段 | int 三元组 | float 关键值 | 推断 |
|------|-----------|-------------|------|
| p | (42, 8, 66) | 0.22, 0.20, 0.55, 0.40 | 大雨档（雨丝长 42px? / 宽 8 / 数量密度 66） |
| q | (34, 6, 54) | 0.30, 0.70 | 中雨档 |
| r | (27, 5, 42) | 0.42, 0.26, 0.85, 0.60 | 小雨档 |
| s | (19, 4, 30) | 0.55, 0.30, 0.60 | 毛毛雨/雪档 |
| t/u | (…) | 0.22/0.20/0.55/0.40 变体 | 雪/混合档 |

→ 降雨按档位缩密度（int 三元组），每日天气按 `precipitation` 强度分档的方向与此一致。

### 4.2 UI 动画：南风使用面（dex 字符串证据）

| 动画 API | 证据 | 每日天气对应 |
|----------|------|-------------|
| `AnimatedContent` | `AnimatedContent.kt:773/854` 等 | Hero 温度 AnimatedContent ✅ |
| `Crossfade` | `Crossfade.kt:55/102` | 场景切帧 Crossfade 900ms ✅ |
| `AnimatedVisibility` | `AnimatedVisibility.kt:131/678` | 卡片入场/页面切换（部分） |
| `animateColorAsState` | `SingleValueAnimation.kt:61` | 卡片颜色随天空动态 ✅ |
| `animateDpAsState` | `AnimateAsState.kt:123` | 组件尺寸/圆角过渡 |
| `InfiniteTransition.animateFloat` | `InfiniteTransition.kt:296` | 云/雨时钟（rememberEffectClock 等价物 ✅） |

**7 个 fragment animator**（`fragment_close_enter` 等）为 androidx.navigation 默认动画，
南风若用 Compose Navigation 则属库自带，无自定义价值。

### 4.3 组件动画

8 个组件 layout 均为静态视图 + 背景图（无 XML 动画资源）；preview 与正式共用同一 layout。
动画在渲染器（WorkManager 刷新 → 数据按签名更新），与每日天气"按数据签名按需渲染"一致。

## 五、南风 vs 每日天气 差距矩阵（2026-10-05）

| 项目 | 南风 | 每日天气 | 状态 |
|------|------|---------|------|
| 主界面卡片流 | 动态卡片 + 天空色联动 α0.78 | 同 | ✅ 已对齐 |
| 场景云 | 7 规格 / count 3-9 / 极慢速 | v0.1.13 后已按表重排 + 新素材 | ✅ 2026-10-05 |
| 场景噪声 | （程序中确认有程序化云类型）| NoiseHaze AGSL | 🟡 方向一致 |
| UI 动画 API 面 | AC/CF/AV/color/dp/infinite | 覆盖 90% | ✅ 基本对齐 |
| widget_small | **顶部温度 + 3 行逐小时** | 单行 | ❌ 待改 |
| widget_aqi | 双图（背景+光谱前景） | 光谱卡内嵌 | 🟡 细节 |
| widget_4x2/week | 用系统 TextClock | 自绘文本时钟 | 🟡 等宽字体细节 |
| 夜间云 | 预烘焙暗素材 + tint | 同 | ✅ |

## 六、建议（按价值排序）

1. **small 组件改造成"顶部温度 + 3 行逐小时"**（结构已 dump 完整，可直接照抄 XML 树）。
2. **widget_4x2/week 使用 TextClock**（系统时钟自动时区/格式，省同步边界 bug）。
3. 云参数表 v2 已按 §4.1 落地；后续可把 26 个 `Ld8/k` 每天气配置（颜色/粒子组合）做印象级对齐
   （真机截图逐卡调色，工程量大、收益边际，非必须）。
4. `custom_dialog` 值得看一眼：南风唯一自定义对话框（可能是更新日志/权限引导），
   如每日天气有对应弹窗可对齐其圆角/配色。
