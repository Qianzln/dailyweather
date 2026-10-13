# 南风 v4.3.99 一比一复刻差距核对报告

> 核对基准：`D:\01 项目\100 个人文件\天气\skypulse\extracted\`（SkyPulse v4.4.2 提取产物）
> 当前版本：每日天气 v0.1.42
> 核对日期：2026-01

---

## 0. 执行摘要

| 层级 | 状态 | 覆盖率 | 备注 |
|------|------|--------|------|
| L1 视觉同源（天空/云/雨/雪/雾/晴/夜） | 🟡 大部分完成 | ~75% | 粒子层缺失，场景键有重建 |
| L2 GLES 全移植（compute + SSBO） | 🟡 框架完成 | ~80% | 需 GLES 3.2 真机验证 |
| L3 1:1 像素级一致 | ⛔ 不可达 | 0% | 官方声明不做 pixel-for-pixel |
| L4 完整 62 场景预设 | 🟡 8 场景验证通过 | ~13% | 其余 54 场景未验证 |

**结论**：核心渲染管线已可用，但在以下方面仍有明显差距：
1. **GLES 3.2 粒子系统**（所有设备降级为纯 GL，无雨滴/雪花/流星 GPU 粒子）
2. **场景键选择器**（`groupFor` 是重建的，非原版源码）
3. **部分场景特效**（meteor orbit/flare 未实现，沙尘粒子缺失）
4. **毛玻璃 UI 参数**（本次优化中已调整，仍需真机目视校准）

---

## 1. 资源完整性核对

### 1.1 贴图（textures）

| 类别 | 提取数量 | 项目已有 | 差距 |
|------|---------|---------|------|
| 云基础贴图（cloud_a/b/c/d） | 18 | 18 | ✅ 完整 |
| 云 Normal Map | 9 | 9 | ✅ 完整 |
| 太阳系列（sun/sun_halo） | 8 | 8 | ✅ 完整 |
| 月亮 | 1 | 1 | ✅ 完整 |
| 星空（star/star-noise） | 2 | 2 | ✅ 完整 |
| 粒子精灵（rain-p/snow-p/star3） | 3 | 3 | ✅ 完整 |
| 闪电（thunder/flash） | 6 | 6 | ✅ 完整 |
| 风/噪声（wind/noise/blue-noise） | 3 | 3 | ✅ 完整 |
| **合计** | **50** | **50** | ✅ 完整 |

**结论**：所有 OPPO 原生贴图已完整提取并纳入 `assets/oppo/textures/`。

### 1.2 着色器（shader）

| 类型 | 提取数量 | 项目已有 | 状态 |
|------|---------|---------|------|
| GLSL Fragment Shader | 32 | 32 | ✅ 完整 |
| HLSL Compute Shader | 95 | ~30 | 🟡 部分 |
| 顶点着色器 | 1（共用） | 1 + fallback | ✅ 完整 |

**差距说明**：
- 95 个 compute shader 中，只有约 30 个被引用在 pack.json 的场景实体里
- 粒子相关的 compute（`particle/*.cs`）已移植，其余效果 compute 可能未全部导入

### 1.3 场景预设（pack.json）

```
scenes:    64    （62 可选用预设 + 0_0_empty + MainScene）
```

**当前项目验证通过的 8 场景**：
| fixture | 场景键 | 状态 |
|---------|--------|------|
| clear_day | `1_3_sunny-afternoon` | ✅ |
| clear_night | `1_6_sunny-night` | ✅ |
| partly_day | `58_3_cloudy-afternoon` | ✅ |
| cloudy_day | `56_3_cloudy-afternoon` | ✅ |
| rain_day | `6_2_L-rain-noon` | ✅ |
| thunder_day | `21_2_thunder-noon` | ✅ |
| snow_day | `10_2_L-snow-noon` | ✅ |
| fog_day | `17_2_fog-noon` | ✅ |

**未验证的 54 场景**：其余时段槽位、强度等级、特殊天气组合。

---

## 2. 渲染管线差距分析

### 2.1 天空背景层（SpriteSky.kt）

| 组件 | 南风实现 | 每日天气 | 差距 |
|------|---------|---------|------|
| 渐变底 | 5 段渐变（_Color0~4） | 3 段渐变 | 🟡 简化版 |
| 积云帧（白天） | cumulus_01~08 轮播 | 同 | ✅ |
| 积云帧（夜间） | 无帧，纯深色渐变 | 跳过帧 + 深色渐变 | ✅ |
| 噪声云层 | GLSL FBM 连续演化 | AGSL FBM（API 33+） | 🟡 实现不同但效果接近 |
| tint 层 | phaseTint + weatherTint | 同架构 | ✅ |

**本次修复**：
- ✅ 夜间跳过积云帧（`skipFrames = state.isNight`）
- ✅ 夜间禁用噪声层（避免浅灰蓝 tint 遮挡星空）
- ✅ 夜间 phaseTint 改为空列表（不再叠加蓝色 tint）

### 2.2 粒子系统（OppoParticleSystem.kt）

| 能力 | 要求 | 每日天气 | 真机状态 |
|------|------|---------|---------|
| 粒子 compute shader | GLES 3.2 + GL_ARB_compute_shader | ✅ 已实现 | 🟡 模拟器仅 3.1，降级为空 |
| SSBO 双缓冲 | OpenGL 3.0+ | ✅ 已实现 | ✅ 可用 |
| 顶点属性绑定 | location 0~4 | ✅ 已实现 | ✅ 可用 |
| 雨滴精灵 | `rain-p.png` | ✅ 有贴图 | 🟡 无 GPU 粒子时降级为静态 |
| 雪花精灵 | `snow-p.png` | ✅ 有贴图 | 🟡 同上 |
| 流星粒子 | `star3.png` | ✅ 有贴图 | ⛔ 未实现 |

**遗留问题**：
1. **meteor orbit/flare 动画未做** — 南风 `g.java:880` 特判为 `meteors` 列表
2. **雾/沙尘场景无粒子层** — 降级后画面偏空，需 CPU 兜底或真机验证
3. **粒子密度在卡片上被放大 5 倍** — 已修复（`dropScaleFor(height)`），但需真机确认

### 2.3 天气效果层（WeatherEffects.kt）

| 效果 | 实现方式 | 状态 |
|------|---------|------|
| 星星（晴夜） | CPU 粒子 + 流星 | ✅ |
| 星星（多云夜） | CPU 粒子 | ✅ |
| 雨（GLES） | GPU compute + SSBO | 🟡 仅 3.2+ |
| 雨（降级） | CPU 粒子 | ✅ |
| 雪（GLES） | GPU compute + SSBO | 🟡 仅 3.2+ |
| 雪（降级） | CPU 粒子 | ✅ |
| 雷暴闪光 | CPU 定时绘制 | ✅ |
| 雾 | CPU 噪声 + tint | ✅ |
| 流星 | CPU 随机生成 | ✅ |

---

## 3. UI/卡片层差距分析

### 3.1 毛玻璃参数（GlassTheme.kt）

| 参数 | 南风 v4.3.99 | 每日天气（修改前） | 每日天气（修改后） |
|------|-------------|------------------|------------------|
| CardBlurRadius | 22dp | 22dp | **26dp** |
| CARD_NOISE_FACTOR | 0.035 | 0.035 | **0.04** |
| CARD_HAZE_FILL_ALPHA | ~0.35（推断） | 0.45 | **0.32** |
| ControlBlurRadius | 16dp | 16dp | 16dp（未改） |

**说明**：南风的 `CARD_HAZE_FILL_ALPHA` 未直接导出，按截图反推约 0.35。本应用从 0.45 降至 0.32，更通透。

### 3.2 卡片间距（Tokens.kt）

| 参数 | 南风实测 | 每日天气（修改前） | 每日天气（修改后） |
|------|---------|------------------|------------------|
| CardGap | ~8dp（25px/@3x） | 14dp | **18dp** |

### 3.3 卡片圆角（Tokens.kt）

| 卡片类型 | 南风 | 每日天气 | 状态 |
|---------|------|---------|------|
| 大卡片（首页主卡） | 28dp | 28dp | ✅ |
| 中等卡片（详情） | 18dp | 18dp | ✅ |
| 小卡片（按钮/标签） | 12dp | 12dp | ✅ |
| 微型卡片（进度条） | 6dp | 6dp | ✅ |

### 3.4 日落/日出滑块

| 组件 | 南风 | 每日天气 | 状态 |
|------|------|---------|------|
| 可拖拽进度指示器 | ✅ | ✅ | ✅ |
| 白天太阳暖白亮核 | ✅ | ✅ | ✅ |
| 夜间月牙 + 伴星 | ✅ | ✅ | ✅ |
| 已走段暗线 (α=0.22) | ✅ | ✅ | ✅ |
| 未来段亮线 (α=0.55) | ✅ | ✅ | ✅ |

---

## 4. 场景键选择器差距

### 4.1 已照抄原版的逻辑

| 函数 | 来源 | 状态 |
|------|------|------|
| `timeSlot` | `w7/z0.java:157-195` | ✅ 逐行照抄 |
| `polarSlot` | `w7/z0.java:157-195` | ✅ 逐行照抄 |
| `isHazeCondition` | `w7/z0.java:157-195` | ✅ 从 smali 还原 |
| `resolve` | `w7/p0.java` 静态块 | ✅ 照抄数据表 |

### 4.2 重建的逻辑（非原版源码）

| 函数 | 说明 | 风险 |
|------|------|------|
| `groupFor` | 782 指令反编译失败，按参数名+阈值重建 | 🟡 边界情况可能偏离 |
| `cloudrateProxyOf` | 死代码修复（v0.1.41 已修） | ✅ 已修 |

**关键阈值**：
```kotlin
const val PRECIP_L = 0.5   // 小雨下限
const val PRECIP_M = 2.5   // 中雨下限
const val PRECIP_H = 8.0   // 大雨下限
const val CLOUD_LOW  = 0.30 // 少云上限
const val CLOUD_HIGH = 0.75 // 多云下限
```

---

## 5. 遗留待办事项

### 5.1 P0 — 必须修复

| # | 项 | 说明 | 状态 |
|---|-----|------|------|
| 1 | **GL 粒子真机验证** | 需 GLES 3.2 真机，当前仅模拟器（3.1） | 🟡 待验 |
| 2 | **meteor orbit/flare** | 南风 `g.java:880` 特判，未实现 | ⛔ 待做 |

### 5.2 P1 — 重要但未阻塞

| # | 项 | 说明 | 状态 |
|---|-----|------|------|
| 3 | **雾/沙尘 CPU 兜底** | 无 GLES 3.2 时粒子缺失，画面偏空 | 🟡 待评估 |
| 4 | **54 场景验证** | 仅 8 场景通过 fixture 测试 | 🟡 待验 |
| 5 | **glGetUniformLocation 缓存** | 每帧调用未缓存，性能项 | 🟡 待优化 |

### 5.3 P2 — 优化项

| # | 项 | 说明 | 状态 |
|---|-----|------|------|
| 6 | **毛玻璃参数微调** | 0.32 可能过透，需真机目视确认 | 🟡 待验 |
| 7 | **卡片间距微调** | 18dp 可能偏大，需对比南风截图 | 🟡 待验 |
| 8 | **夜间星空 tint 校准** | 刚才修复了 phaseTint，需验证星星可见度 | 🟡 待验 |

---

## 6. 构建与部署说明

### 6.1 构建命令

```bash
cd D:\DEV\dailyweather
.\gradlew.bat :app:assembleDebug
```

APK 位置：`app/build/outputs/apk/debug/app-debug.apk`

### 6.2 版本化构建（推荐）

```bash
.\gradlew.bat :app:assembleVersionedDebug -PversionCode=43 -PversionName=0.1.43
```

输出：`app/build/outputs/apk/versioned/dailyweather-v0.1.43.apk`

### 6.3 单场景 fixture 测试

```bash
adb shell pm clear com.dailyweather.app
adb shell pm grant com.dailyweather.app android.permission.ACCESS_FINE_LOCATION
adb shell am start -S -n com.dailyweather.app/.MainActivity --es fixture clear_night
```

---

## 7. 参考文档

| 文档 | 位置 |
|------|------|
| 南风 v4.3.99 资源包验真报告 | `docs/南风v4.3.99资源包验真与1比1复刻评估.md` |
| v0.1.41 8 场景视觉校准报告 | `docs/v0.1.41-8场景视觉校准报告.md` |
| 南风 v4.3.79 对齐重构计划 | `docs/南风v4.3.79对齐重构计划.md` |
| 提取产物清单 | `D:\01 项目\100 个人文件\天气\skypulse\extracted\FILE_LIST.md` |
| OPPO 场景渲染引擎 | `D:\01 项目\100 个人文件\天气\skypulse\extracted\oppo_weather_assets\oppo_weather\README.md` |

---

## 8. 本次修复内容（v0.1.43）

### 8.1 夜间星空修复（SpriteSky.kt）

```kotlin
// 修复前：夜间仍绘制噪声层 + 高 alpha tint，导致星空被遮挡
// 修复后：
if (!state.isNight) {
    NoiseHazeLayer(state, Modifier.matchParentSize(), clock)
}
val skipFrames = state.isNight
if (!skipFrames) { /* 白天积云帧 */ }
```

`phaseTint(NIGHT)` 从 55% alpha 深蓝改为空列表，让深色渐变底 + 星星完整可见。

### 8.2 毛玻璃通透度优化（GlassTheme.kt）

| 参数 | 修改前 | 修改后 |
|------|--------|--------|
| CardBlurRadius | 22dp | **26dp** |
| CARD_NOISE_FACTOR | 0.035 | **0.04** |
| CARD_HAZE_FILL_ALPHA | 0.45 | **0.32** |

### 8.3 卡片间距优化（Tokens.kt）

| 参数 | 修改前 | 修改后 |
|------|--------|--------|
| CardGap | 14dp | **18dp** |

---

*报告生成时间：2026-01*
*下次核对建议：真机安装 v0.1.43 后对比南风 v4.3.99 截图*
