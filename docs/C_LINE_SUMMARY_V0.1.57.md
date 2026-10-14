# C 线代码审查与重构总结（v0.1.57）

> 日期：2026-10-14
> 分支：master（C 线重构已合回）
> 范围：GL 渲染与场景代码（OppoSceneRenderer/OppoShaderLibrary/OppoScene/OppoSceneKey）

## 已完成重构

### 1. SceneAnimator 抽离（9801f407）
- 新建 `SceneAnimator.kt`：雷闪包络/云漂移/场景淡入抽成纯函数
- OppoSceneRenderer 删除内联实现，只调用 SceneAnimator 方法
- 零行为变化：数值逻辑与原版完全一致，仅代码结构优化

### 2. alphaUniformNames 自动推导（fdacc567）
- Program 链接时按命名模式自动识别 alpha 类 uniform（含 "alpha" 且 FLOAT/BOOL）
- 渲染器不再硬编码 5 个名字，着色器新增自动跟随

### 3. parseEntity null 保护（fdacc567）
- pos/scale/rot 走带保护的 floatArrayOf（原直接调顶层函数，null 即 NPE）

### 4. readyFlow 公开（fdacc567）
- UI 侧可 collectAsState 响应式消费场景组表就绪状态

## 已回滚

### WeatherScreen 拆分（1172 行 → 3 文件）
- 手工提取 import 不全导致大量编译错误，已回滚
- 需要更仔细的分析，留到后续专项

## 待后续专项

### 1. groupFor smali 校准
- p0.java 的 `b(...)` 方法反编译失败（782 条指令），当前是重建实现
- 需要啃 05-smali 反编译的 p0.smali 逐指令校准
- 优先级：中（当前实现已对齐大部分场景）

### 2. GlobalScope 治理
- OppoSceneRegistry.prewarm 和 OppoSceneKey.prewarm 用 GlobalScope.launch
- 需要协调 DI（AppContainer），改动面大
- 优先级：低（功能正常，仅架构债务）

### 3. WeatherScreen 拆分
- 1172 行单文件 → 3 个文件（主框架/卡片组件/小组件）
- 需要仔细分析 import 依赖
- 优先级：低（功能正常，仅代码组织）

## 验证记录

- assembleDebug 构建通过（多次）
- assembleVersionedRelease 构建通过（v0.1.57，14.76MB）
- 实机验证待用户反馈（B1 夜间场景修复）

## 关联文档

- C 线审查报告：docs/C_LINE_REVIEW_V0.1.56.md
- B1 修复记录：docs/notes-v0.1.57.md
- D 线性能优化：docs/notes-v0.1.56.md
