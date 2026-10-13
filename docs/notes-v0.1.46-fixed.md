# DailyWeather v0.1.46-fixed 修复日志

> 日期：2026-01-16
> 目标：修复云朵不显示 + 顶栏颜色跳动

## 问题根因分析

### 问题1：云朵不显示
**根因**：SpriteSkyBackground 中 Canvas 使用 drawWithContent { drawContent(); drawRect(..., BlendMode.DstIn) } 
包装，DstIn blend mode 以 content（即 Canvas 自身之前的绘制内容）为 destination，
当与外层 hazeSource 的 offscreen renderNode 组合时，DstIn 的目标被截断/置空，
导致整个云帧被裁成透明。
**修复**：移除 drawWithContent 包装和 DstIn 蒙版，直接使用 Canvas(Modifier.fillMaxSize()) 绘制云帧。

### 问题2：顶栏颜色跳动
**根因（双重滞回冲突）**：
1. ememberWeatherCloudiness 创建 Animatable 时**没有用 target 作为 remember key**，
   导致 snapshot 变化时 Animatable 状态不重置，新旧动画竞争
2. ememberWeatherKindState 的 ememberSaveable { mutableStateOf(snapshotKind) } 
   **没有把 snapshotKind 作为 key**，导致 kind 状态在数据源变化时不重置
3. SceneBridge.stabilizeKind() + ememberWeatherKindState() 双重滞回叠加，
   反而在边界情况下产生冲突

**修复**：
1. ememberWeatherCloudiness：emember(target) { Animatable(...) } — target 变化时创建新动画并立即钉住
2. ememberWeatherKindState：ememberSaveable(snapshotKind) { mutableStateOf(snapshotKind) } — 
   snapshotKind 变化时重置滞回状态
3. WeatherScreen：emember(kindSticky, cloudiness, scenePhase) 替代 emember(sceneRaw, ...) —
   只在稳定维度变化时重建 scene

## 修改文件
- SpriteSky.kt — 移除 drawWithContent+DstIn 包装
- WeatherKindState.kt — 修复 Animatable 和 rememberSaveable 的 key
- WeatherScreen.kt — 稳定 scene remember key
