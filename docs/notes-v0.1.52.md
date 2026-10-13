# DailyWeather v0.1.52 修复日志

> 日期：2026-10-12（夜）
> 目标：按用户决策「直接去掉 Canvas 层，全部使用 GL 渲染」重构天气动效，
> 并修复全天气动效审核中发现的问题。

## 1. Canvas 特效层整体删除（全 GL 渲染）

审核结论：GL 场景（62 预设）与 Canvas 特效层**完全重叠**——每种天气都在被画两遍：

| 天气 | GL 预设已有 | 被删掉的 Canvas 重复层 |
|---|---|---|
| 晴日 | sun_main + sun_halo + 3D 云 | SunnyDayEffect 太阳 + PNG 前景云 |
| 晴夜 | particle_star + particle_meteor | ClearNightEffect 星星/流星 + PNG 云 |
| 多云/阴 | 3D 云 | PartlyCloudy/PNG 前景云 |
| 雨 | particle_rainfront + particle_rain 双层 | RainEffect 雨丝贴图 + PNG 云 |
| 雷 | rainfront/rain + thunder_0..4 雷帧 | ThunderEffect 雨丝 + 闪光 + PNG 云 |
| 雪/雨夹雪 | particle_snow（+rain） | SnowEffect/SleetEffect + PNG 云 |
| 雾/霾/风 | fog / wind quad 实体 | FogEffect 雾带 / WindEffect 速度线 |
| 沙尘 | particle_dust | FogEffect 雾带 |

删除文件：WeatherEffects.kt / WeatherEffectOverlay.kt / SpriteSky.kt / SpriteClouds.kt /
SpriteAssets.kt / ParticleSprites.kt / NoiseHaze.kt + assets/skyframes + assets/cloudsprites
+ drawable weather_rain_streak_mgl.png。主屏天空栈现在只有：
`OppoGlSceneLayer（GL 全场景）→ 空 hazeSource（顶栏/卡片毛玻璃采样源）→ Column 内容`。
玻璃雨滴卡（AGSL，非 Canvas 天气层）保留。

连带处理：
- 全屏降雨折射 RenderEffect 一并移除——它挂在 hazeSource 上，而 GL TextureView 不在
  离屏合成里，折射扭的只是一层透明特效（空转 GPU）。
- 「天气动效」设置开关改接 GL `paused`：关掉 = 停帧但预设切换/尺寸变化仍补一帧静态画面
  （OppoGlSceneView 新增 needsFrame，停帧不再等于黑屏）。
- LocalWeatherEffectDrawEnabled / LocalEffectQuality 两个 CompositionLocal 随消费方删除。
- 子页（城市管理/搜索）天空从 PNG 精灵帧换成 skyStopsFor 静态三段渐变（不需要动效）。
- DailyWeatherApp 去掉 ParticleSprites/SpriteAssets 预热（省电省启动）。
- defaultIntensity() 从被删的 WeatherEffectOverlay 移到 WeatherScene.kt（雨滴卡兜底仍在用）。

## 2. GL 闪电帧动画重建（雷天 GL 里原本完全不亮）

根因：presets.json 转换器把 thunder_N 实体的材质 uniform（u_alpha/u_flashStrength/
u_lightStrength/u_DissolveValue）连同 animations 一起丢了——着色器 `fragColor.a = u_alpha`
缺省为 0 → 5 条雷帧永远不可见。

按参考文档 21_2 预设的 thunder_animator 关键帧重建（OppoSceneRenderer）：
- 5 个 thunder_N 是 5 条不同形态的 bolt（各有独立 u_texLight0），不是序列帧；
- 每 4.5s 一个周期：前 0.885s 跑双峰包络（0→0.3@0.07→0.8@0.32→0.2@0.38→0.9@0.47→0），
  其余静默；每轮换一条 bolt，避免 5 条叠画；
- u_lightStrength 0.53/0.72、u_DissolveValue（frame0=1）、u_resolution 等静态数据按参考补齐。

## 验证要点（实机）
1. 雨天：只有 GL 双层雨（前景大滴 + 远景细丝），不再有 Canvas 雨丝叠加；
2. 雷天：闪电按 ~4.5s 双峰节律闪现，每轮形态不同；
3. 晴夜：GL 星星 + 流星，无 Canvas 星点叠加；
4. 设置里关「天气动效」：天空定格但不黑屏；切天气仍换静帧；
5. 子页（城市管理/搜索）背景为静态天空渐变，无 PNG 云帧。
