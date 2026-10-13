# DailyWeather v0.1.47-GL 重构日志

> 日期：2026-01-16
> 目标：用 OPPO GL 3D 场景引擎替换 SpriteSky PNG 精灵帧方案

## 变更

### 架构变更：天空渲染路径切换
- **之前**：AnimatedSkyGradient → SpriteSkyBackground（PNG 精灵帧 + NoiseHaze FBM 噪声云）
- **之后**：OppoGlSceneLayer（OPPO COE GLES 3D 引擎）为主渲染路径，SpriteSky PNG 为 GL 不可用时的兜底

### 修改文件
1. **WeatherScreen.kt** — 引入 OppoGlSceneLayer，glReady=false 时回退到 AnimatedSkyGradient
2. **Tokens.kt** — CardGap 从 18dp → 22dp，卡片间距更有呼吸感
3. **NoiseHaze.kt** — CLOUDY 强度从 0.24 → 0.32，让 FBM 噪声云更明显
4. **SpriteSky.kt** — Canvas 改用 graphicsLayer { alpha=1f } 确保 hazeSource 内尺寸正确

### OPPO GL 引擎集成要点
- OppoGlSceneLayer 是 TextureView 封装，内容绘制进父 View 画布 → hazeSource 可采样
- OppoSceneRegistry.prewarm() 在 DailyWeatherApp.onCreate() 中已调用（IO 线程解析 presets.json）
- OppoSceneKey.prewarm() 同样已调用（场景键映射表）
- GL 不可用时（GLES < 3.0 / 模拟器）自动降级到 SpriteSky PNG 方案
- 粒子层（WeatherEffectHost）独立于 GL 场景，叠加在天空层之上

## 复刻资料对照
| 项目 | 当前 | 复刻 |
|---|---|---|
| assets/oppo/ | 175 文件 | oppo_weather/ 174 文件 |
| compute shaders | 95 | 95 |
| shader glsl | 32 | 32 |
| textures | 44 | 44 |
| presets.json | ✅ 预处理版 | pack.json 原版 |

两套资源一致，OPPO GL 3D 引擎完整可用。
