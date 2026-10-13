# C 线代码审查报告（v0.1.56，GL 渲染与场景代码）

> 审查日期：2026-10-13
> 审查范围：`app/src/main/java/com/dailyweather/app/scene/oppo/gl/` + `scene/oppo/`
> 前置：D 线已修复 `glGetUniformLocation` 每帧查询（Program.loc 缓存）、雷帧 uniforms map 复制、applySceneFade HashMap 复制。本文只列 D 线未覆盖的新发现。

## 审查结论总表

| # | 位置 | 问题 | 优先级 | 是否可自动修复 |
|---|---|---|---|---|
| 1 | `OppoSceneRenderer.kt:354` | `alphaUniformNames` 硬编码与着色器脱耦 | 🔴 高 | ✅ 自动 |
| 2 | `OppoScene.kt:275-278` | `pos/scale/rot` 的 `floatArrayOf(optJSONArray(...))` 无 null 保护 | 🟠 中 | ✅ 自动 |
| 3 | `OppoSceneRegistry.prewarm` | `GlobalScope.launch` 生命周期泄漏 | 🟠 中 | ⚠️ 需协调 |
| 4 | `OppoSceneKey.kt:51` | `readyFlow` 私有，UI 侧无法响应式消费 | 🟡 低 | ✅ 自动 |
| 5 | `OppoSceneRenderer.kt:220` | `drainPreloadQueue` 每帧空跑分支 | 🟡 低 | ✅ 自动 |
| 6 | `OppoSceneRenderer.kt:223` | `ArrayList(4)` 每帧分配 | 🟡 低 | ✅ 自动 |
| 7 | `OppoSceneRenderer.kt:350` | `QuadMesh.draw()` 每实体调一次，draw call 冗余 | 🟢 建议 | ❌ 需 instancing，大改 |

## 详细说明

### #1 `alphaUniformNames` 硬编码（高优先级）

**现状**：`OppoSceneRenderer.kt:354`
```kotlin
private val alphaUniformNames = setOf("_AlphaValue", "u_Alpha", "_Alpha", "glowAlpha", "particleAlpha")
```

**问题**：这 5 个名字来自对着色器的经验观察，与 GLSL 源代码解耦。如果未来新增一种带 alpha 的着色器（比如新的雾 shader 用 `u_GlobalAlpha`），这里不会自动识别，淡入逻辑对它无效。

**建议**：在 `OppoShaderLibrary.Program.build()` 时按命名模式自动识别 alpha 类 uniform，存入 `Program.alphaUniformNames`。识别规则：
- 名字包含 `Alpha` 或 `alpha`（大小写不敏感）且不以 `u_AlphaBlend` 等混合参数开头
- 类型为 `FLOAT`、`VEC1`（不存在）、`BOOL`

这样着色器一变，alpha 识别自动跟上。

### #2 `parseEntity` 的 pos/scale/rot 无 null 保护（中优先级）

**现状**：`OppoScene.kt:275-278`
```kotlin
pos = floatArrayOf(o.optJSONArray("pos")),
scale = floatArrayOf(o.optJSONArray("scale")),
rot = floatArrayOf(o.optJSONArray("rot")),
```

`parseUniforms` 里的 `floatArrayOf(a: JSONArray?)` 有 null 保护（返回 `FloatArray(3)`），但 entity 的这三个字段**直接传给了顶层 `floatArrayOf` 函数**（即 Kotlin 标准库的 `floatArrayOf(vararg values: Float)`），传入 null 会 NPE。

目前 62 个预设、560 个实体都来自 `presets.json`，理论上字段完整。但：
- 数据异常时静默崩溃，没有日志
- 与 `parseUniforms` 的防御风格不一致

**建议**：把 entity 的 pos/scale/rot 也走 `parseUniforms` 里的 `floatArrayOf(a: JSONArray?)` 保护版（或显式加 null 检查）。

### #3 `GlobalScope.launch` 生命周期泄漏（中优先级）

**现状**：`OppoScene.kt:208` 与 `OppoSceneKey.kt:59`
```kotlin
kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) { load(app) }
```

**问题**：GlobalScope 不受任何 lifecycle 约束。如果 Activity/Fragment 被销毁后协程还在跑（IO 线程加载 JSON），会持有 Context 引用导致内存泄漏。虽然 JSON 加载很快（<100ms），但这不符合协程最佳实践。

**建议**：改为在 `Application.onCreate` 时启动，或在 DI 层传入一个带 lifecycle 的 scope。这需要改 `AppContainer` 的注入方式，改动面稍大——**建议作为 B/C 线的后续工作单独跟进**。

### #4 `readyFlow` 私有（低优先级）

**现状**：`OppoSceneKey.kt:51`
```kotlin
private val _readyFlow = MutableStateFlow(false)
val readyFlow: StateFlow<Boolean> = _readyFlow  // 对外只暴露只读接口
```

`readyFlow` 是 private，UI 侧无法 collect。当前依赖 `remember(oppoAssetsReady, ...)` 键隐式重算，功能正常但不响应式。

**建议**：把 `readyFlow` 改为 public，让 `WeatherScreen` 侧可以直接 `collectAsState(oppoSceneKey.readyFlow)` 响应。同时保留 `remember` 作为兜底（避免遗漏场景）。

### #5 / #6 `drainPreloadQueue` 微小优化（低优先级）

两处都在 `OppoSceneRenderer.kt`：
- L220：每帧都调 `drainPreloadQueue()`，即使队列空时也进函数体
- L223：每次调用 `ArrayList<String>(4)` 分配

合在一起改成：
```kotlin
private fun drainPreloadQueue() {
    if (preloadQueue.isEmpty()) return
    val batch = mutableListOf<String>()  // 或复用固定数组
    repeat(4) { if (preloadQueue.isNotEmpty()) batch += preloadQueue.removeFirst() }
    if (batch.isNotEmpty()) OppoTextureCache.preload(batch)
}
```
或更激进：直接把 `ArrayList(4)` 改成预分配的固定数组 `var drainBuf = ArrayDeque(4)` 复用。

## #7 `QuadMesh.draw()` 冗余 draw call（建议，不做）

**现状**：每实体调一次 `QuadMesh.draw()`，全场景最多 ~15 次 draw call/帧。

原版 OPPO COE 也是按实体 draw（不同实体 blend mode 不同）。可以用 instancing 把相同 blend 的实体合并，但这需要：
- 改写 QuadMesh 支持批量 draw
- 保证同 batch 内 blend 一致
- 处理不同 shader 间的切换

**结论**：收益低（现代 GPU 能轻松吞吐 100+ draw call/帧），风险高（可能引入 bug）。留作技术储备，不在这个周期做。

## 实施计划

按优先级执行 #1→#2→#4→#5/#6，#3 作为独立 issue 跟踪，#7 搁置。

---
*审查者：Meteor（D 线并行 C 线审查），基于 v0.1.56 工作树*
