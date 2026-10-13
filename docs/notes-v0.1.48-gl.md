# DailyWeather v0.1.48 GL优化日志

> 日期：2026-01-16
> 目标：修复卡片透明度和天气场景选择

## 变更

### 1. 卡片透明度优化
- **GlassTheme.kt**: CARD_HAZE_FILL_ALPHA 从 0.32f 降至 0.22f
  - 更透明，GL云层透出更多
- **Tokens.kt**: CardFill alpha从0xB8(72%)降至0x59(35%)
  - 配合Haze模式，整体玻璃感更强
- **Theme.kt**: cardFill的alpha从CARD_SOLID_FILL_ALPHA(0.78)改为固定0.35f
  - 无Haze时也更通透

### 2. GL渲染帧率解锁
- **OppoGlSceneView.kt**: 	argetFps默认值从60改为0（不限）
- **WeatherScreen.kt**: 	argetFps下限从24改为1
  - GL线程以设备原生刷新率（120/144Hz）运行
  - 云层漂移更流畅自然

### 3. 卡片间距调整
- **Tokens.kt**: CardGap 从 22dp 降至 16dp
  - 平衡GL云层背景的视觉密度

### 4. Debug日志
- **OppoSceneKey.kt**: select()函数添加Log.d输出skycon/slot/group/key
  - 便于诊断场景选择问题

## 问题排查

### 阴天显示晴天云层？
可能原因：
1. 数据源返回的skycon不是OVERCAST而是其他值
2. ixture.hourOverride导致时段(slot)错误
3. GL场景切换有缓存/延迟

### 解决方案
- 查看logcat中OppoSceneKey的debug输出
- 确认数据源currentSkycon的实际值
- 检查ixture配置中的hour字段
