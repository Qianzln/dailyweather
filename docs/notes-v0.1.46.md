# DailyWeather v0.1.46 修复日志

> 日期：2026-01-16
> 目标：修复云朵不显示 + 顶栏颜色跳动

## 本次修复

### 问题1：云朵未显示
**根因**：SpriteSkyBackground 中的 Canvas 使用了 CompositingStrategy.Offscreen + BlendMode.DstIn（顶部20%渐隐蒙版），这两者都与外层 hazeSource 布局冲突，导致帧内容被裁剪或不可见。
**修复**：移除 Offscreen 合成策略和 DstIn 蒙版，直接使用 Canvas(Modifier.fillMaxSize()) 绘制云帧。

### 问题2：顶栏颜色跳动
**根因**：WeatherScreen 中 al scene = remember(sceneRaw, kindSticky, cloudiness) 的 key 包含 sceneRaw，而 sceneRaw 随每次快照刷新重建 → AnimatedSkyGradient 重绘 → 天空渐变闪烁。
**修复**：将 scene 的 remember key 改为 (kindSticky, cloudiness, scenePhase)，只在稳定维度变化时才重建 scene，避免快照刷新导致的天空重绘。

### 技术细节
- ememberScene 的 key 现在是三个稳定值：稳定的 kind、渐变云量、时段
- 云帧渲染简化为单层 Canvas，无中间合成步骤
- 夜间 phase tint 保留了低 alpha 深蓝层以维持夜空氛围
