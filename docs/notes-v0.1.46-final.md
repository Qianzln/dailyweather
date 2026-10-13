# DailyWeather v0.1.46-fixed 修复日志（最终版）

> 日期：2026-01-16
> 目标：修复云朵不显示 + 顶栏颜色跳动

## 根因分析

### 问题1：云朵不显示
**根因**：SpriteSkyBackground 中 Canvas 使用 drawWithContent { drawContent(); drawRect(..., BlendMode.DstIn) } 
包装。DstIn blend mode 以 composition 内容（即 hazeSource 的离屏 renderNode）为 destination，
当外层 hazeSource 开启了离屏合成时，DstIn 的目标被截断/置空，导致整个云帧被裁成透明。

**修复**：移除 drawWithContent 包装和 DstIn 蒙版，改为直接 Canvas(Modifier.fillMaxSize()) 绘制云帧。
同时移除了相关的 CompositingStrategy.Offscreen 和未使用的 import。

### 问题2：顶栏颜色跳动
**根因（三层叠加）**：

1. **Animatable key 缺失**：ememberWeatherCloudiness 创建 Animatable 时用了 emember { Animatable(...) } 
   没有把 target 作为 key。当 snapshot 变化导致 target 变化时，Animatable 不重建，
   而是从旧值继续动画到新目标——这在实际中导致旧动画残留、新旧值竞争。

2. **rememberSaveable key 缺失**：ememberWeatherKindState 的 ememberSaveable { mutableStateOf(snapshotKind) } 
   没有把 snapshotKind 作为 key。当数据源变化（如晴→多云）时，滞回的 kind 状态不重置，
   仍保持旧的档位，直到云量达标才切换。

3. **双重滞回冲突**：SceneBridge.stabilizeKind() + rememberWeatherKindState() 各做一套防抖，
   在边界情况下产生冲突，反而增加了跳变概率。

**修复**：
- ememberWeatherCloudiness：emember(target) { Animatable(...) } — target 变化时重建
- ememberWeatherKindState：ememberSaveable(snapshotKind) { mutableStateOf(snapshotKind) } — 
  数据变化时重置滞回状态
- WeatherScreen：emember(kindSticky, cloudiness, scenePhase) — 只在稳定维度变化时重建 scene

## 修改文件
1. SpriteSky.kt — 移除 drawWithContent+DstIn，简化 Canvas 包装，清理未使用 import
2. WeatherKindState.kt — Animatable 用 target 作 key；rememberSaveable 用 snapshotKind 作 key
3. WeatherScreen.kt — scene 的 remember key 改为三个稳定值
