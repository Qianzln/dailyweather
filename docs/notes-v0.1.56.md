# DailyWeather v0.1.56 修复日志

> 日期：2026-10-13
> 分支：perf/line-d（三轨并行 D 线：性能与包体优化）
> 目标：包体瘦身 + GL 渲染 CPU 开销治理 + 动态帧率省电。

## 前置里程碑：git 基线

v0.1.43~v0.1.55 共 11 个版本此前全部未提交（裸奔在工作树）。本线开工前完成：
- 基线提交 `99f0d774`（master），tag `v0.1.55-baseline`，已推送 origin。
- `.gitignore` 补全：`android-sdk-w/`（5.1GB）/`01 复刻内容/`（252MB 逆向资料）/
  `skypulse_refs/`/`.gradle_local/`/构建日志等，暂存区复核 402 文件零禁区混入。

## 基线测量（v0.1.55 APK 构成，解压态）

| 构成 | 体积 | 占比 |
|---|---|---|
| assets | 14.18 MB | 75.1% |
| ├─ oppo/textures | 9.40 MB | — |
| ├─ oppo_widget（100 webp+3 png+manifest） | ~3.4 MB | — |
| ├─ oppo/presets.json | 0.76 MB | — |
| └─ oppo/compute | 0.39 MB | — |
| classes.dex | 3.27 MB | 19.9%（root 合计） |
| res + resources.arsc | 0.78 + 0.47 MB | ~4.4% |

安装包压缩态：15.96 MB。
帧率基线：GL `targetFps = fps.cloud` 跟满刷新率（120Hz 屏 = 120fps 连续渲染），
所有场景同档，无按内容降帧。实机 gfxinfo/耗电基线见文末「待实机测量」。

## 改动

### 1. 包体（build.gradle.kts）
- `isShrinkResources = true`（配合已开的 minify，R8 后剥未引用资源）
- `resConfigs("zh")`：项目无 values-xx 目录（默认 values 即中文），
  剥掉第三方库 100+ locale 字符串，resources.arsc 瘦身
- 版本号：versionCode 56 / versionName 0.1.56
- **未动** `assets/oppo_widget/`（3.4MB 代码零引用，用户明确保留，不删）

### 2. uniform location 缓存（GL CPU 开销）
`glGetUniformLocation` 是驱动侧字符串哈希查询。旧实现每帧每实体每 uniform 都查一次
（渲染器 uniform 循环 + 光照结构体 ~40 次/实体 + 粒子 3 个 program + 贴图 sampler +
白色兜底），全场景每帧数百次 JNI 字符串调用。

改法：`OppoShaderLibrary.Program` 新增 `loc(name)` —— HashMap 按名缓存
（不存在的名字缓存 -1，不重复问驱动）。仅 GL 线程访问，无需并发结构。
渲染器 / uploadLights / 粒子 uploadDeclared / 贴图绑定 / 白色兜底全部切换到查表。

### 3. 每帧堆分配治理
- 雷帧动画：旧实现每帧 `e.uniforms + mapOf(...)` 复制整个 uniform map + 4×floatArrayOf；
  改为循环后 4 个值直接 `glUniform` 覆盖上传，零分配。
- 场景淡入：旧 `applySceneFade` 在 0.9s 窗口内每帧复制 HashMap；
  改为 uniform 循环内对 alpha 类名字直接乘 fade 系数，零分配（该方法已删）。

### 4. 动态帧率（WeatherScreen）
按当前预设内容决定 GL 帧率上限：
- 有 GPU 粒子（雨/雪/星/沙尘）或雷帧实体 → 维持 `fps.cloud` 满刷新率档；
- 纯天空+云场景（晴/多云/阴/雾/风）→ 30fps。
  云漂移是 150~300s 穿屏的慢速位移，30fps 与满帧观感一致。
判定走 `OppoSceneRegistry.preset(key).entities.any { particle != null || thunder != null }`，
不硬编码组号，预设数据变了自动跟随。

## 验证

- [x] assembleVersionedRelease 构建通过（见 APK 体积对比）
- [ ] 实机：雷天闪电形态/频率不变（叠画+溶解，4.5s 周期）
- [ ] 实机：切场景 0.9s 淡入不变
- [ ] 实机：晴天/多云 30fps 下云漂移观感无变化；雨/雪/雷满帧
- [ ] 实机 gfxinfo 帧率对比（v0.1.55 vs v0.1.56）
