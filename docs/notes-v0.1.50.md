# DailyWeather v0.1.50 修复日志

> 日期：2026-01-16
> 目标：修复GL场景选择逻辑和卡片透明度

## 修复内容

### 1. currentSkycon fallback逻辑修复
- 原逻辑：ealtime?.skycon ?: hourly.firstOrNull()?.skycon ?: "CLEAR_DAY"
- 新逻辑：依次检查realtime → hourly首点 → daily首点 → 时段推断
- 夜间/阴天/雨天各自有对应的fallback，不再硬编码CLEAR_DAY

### 2. GL场景选择逻辑修复
- cloudrateProxyOf("MODERATE_HAZE") 从 0.75 提高到 0.90
- 确保HAZE场景正确映射到霾/雾场景而不是晴天
- 添加了debug日志：Log.d("SceneBridge", "oppoSceneKey: skycon= slot= cloud= key=")

### 3. 卡片透明度优化
- CARD_HAZE_FILL_ALPHA: 0.32 → 0.22（Haze模式下）
- CardFill alpha: 0xB8(72%) → 0x59(35%)（无Haze模式）
- cardFill alpha: 0.78 → 0.35f（Theme.kt）

### 4. 卡片间距调整
- CardGap: 22dp → 16dp（平衡云层背景密度）

## 验证要点
1. 阴天场景应显示多层云纹理，不是晴天
2. 卡片应有磨砂玻璃效果，云层透出
3. 卡片间距16dp，更紧凑
4. 云层应缓慢漂移（120Hz刷新率）
