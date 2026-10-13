# DailyWeather v0.1.54 修复日志

> 日期：2026-10-13
> 目标：对照 skypulse-v4.4.2-复刻资料全集（原始 pack.json + 资源）全量核对动画与资源引用。

## 引用核对结果（全部通过）

| 项 | 结果 |
|---|---|
| 场景/实体/transform/uniform | 64/64 场景、全部实体与原 pack.json **零差异** |
| 贴图 | 44/44 被引用、存在且与原包**字节一致** |
| 渲染着色器 | 32/32 存在且字节一致 |
| compute 粒子着色器 | 95/95 存在且字节一致（particle/ → compute/ 路径映射正确） |
| scene_map 组表 | 全部键可解析、无悬空引用 |

## 动画核对与修正

原包动画全清单（MainScene + 各场景）：
- 太阳光晕/月亮 fadeIn/fadeOut（事件动画，静态数据 u_Alpha=1 已正确显示）
- **thunder_animator**（雷闪，唯一带实质关键帧的动画）
- **MainScene 入场淡入**：cloud_fade(1.1s)/sun_fade/moon_fade/star_fade/wind_fade/fog_fade/lensFlares_fade
- exprRecord 全局变量（BGColor*/CloudGamma/灯光方向）——引擎级动态重调色钩子，预设已烘焙终值，不需复刻
- 雾/霾/风/暴 quads 靠 `u_time × _SpeedN × speedScale` 滚动——数据完整，本就走时间驱动 ✅
- 云无位移动画（v0.1.53 已重建平移漂移）✅

修正 1 — **雷闪对齐原版**：v0.1.52 的"每轮换一条 bolt"实现错了——原版 5 个 thunder_N
是**同位同时**叠画的 5 种 bolt 形态，共享同一组关键帧；补上了被漏掉的
`u_DissolveValue` 溶解显形动画（1→0.6→0 @0.42s）。

修正 2 — **场景入场淡入**：换场景时各元素（云/太阳/月亮/星/雾/风 quad）从 0 淡入 0.9s
（原名各异的总 alpha uniform 统一乘 fade 系数：_AlphaValue/u_Alpha/_Alpha/glowAlpha/
particleAlpha；背景不淡、雷帧走自己包络、粒子自带 fadeinTime）。此前我们是瞬时硬切。

## 验证要点（实机）
1. 雷天：闪电是多层 bolt 同时双峰闪 + 溶解显形，不再是单条轮换；
2. 切天气/换城市/冷启动：云、太阳等元素 0.9s 内柔和淡入，不硬切；
3. 雾/霾/风/暴场景应有横向滚动动效（u_time 驱动）。
