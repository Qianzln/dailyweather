# DailyWeather v0.1.51 修复日志

> 日期：2026-10-12（夜）
> 目标：收尾 v0.1.50 之后遗留的四个实机问题——玻璃雨滴卡水珠形态、小雨被当暴雨、
> 首装授权后定位不刷新、冷启动 GL 天空不亮/云层卡顿。

## 1. 玻璃雨滴卡：水珠从"灰白斑点"改为玻璃轮廓光

实机截图（小雨、夜间）里卡片上是一坨坨不透明灰圆，完全不像水。根因在
`RainRefractionShader.HIGHLIGHT`：把水珠 mask `c` 直接当 alpha，填了一块
冷灰色（夜间 base = 0.60,0.70,0.85 × 0.55）——实心贴片，不是水珠。

改法（`RainRefractionShader.kt`）：
- 对 `Drops()` 做双向有限差分得水珠**法线**；
- `hi` = 左上入射光的镜面高光（pow 6）→ 水珠上缘一道亮弧；
- `shade` = 右下缘玻璃暗边（pow 3）；
- `body` 只给 0.06 的极淡冷色（透明水，不是灰漆）；
- alpha = `hi*0.60 + shade*0.22 + body*0.06`，整体透明度大幅下降。

卡片水珠尺寸：`applyHighlightUniforms` 新增 `dropSizeMul`（默认 1.0，全屏折射不变），
`GlassRaindropsCard` 传 1.6——MIUI 的 Drops 网格按全屏标定，176dp 卡上珠子偏大偏糊。

## 2. 降水强度语义：mm/h 取代降水概率

`SceneBridge.intensityOf` 旧实现取"未来两小时最高降水**概率**"→ 小雨 + 96% 概率
被算成 0.96：玻璃卡出「暴雨倾盆」文案、水珠满密度、全屏折射按暴雨强度扭曲。

新实现按小时降水量 mm/h：`sqrt(mm/40)`，下雨保底 0.18；
2.5 mm/h≈0.25（细雨拂窗）、8 mm/h≈0.45（雨打窗棂）、≥22 mm/h≈0.75（暴雨倾盆）。
数据源缺 mm/h 时退化为概率 × 0.35。全屏折射的兜底强度 0.55 → 0.30。

## 3. 首装授权后定位不刷新（退出重进才生效）

根因链：
- `MainActivity.locationPermission` 回调是**空 lambda**——用户点「允许」后无任何动作；
- `WeatherViewModel` init 里 `locateAndRefresh()` 跑在权限弹窗**之前**，必失败；
- 位移监听 `startMoveWatcher` 只在 init 的 settings 收集器里注册，首装时权限未到 → 永不注册。

改法：
- VM 提前到 `onCreate` 创建（权限回调要用）；回调授予时调
  `vm.onLocationPermissionGranted()`（起位移监听 + 补定位刷新，幂等）；
- `onResume` 补检（用户从系统设置授权后切回的场景）；
- `locateAndRefresh()` 遇刷新进行中不再丢弃请求：`pendingLocate` 排队，
  `refreshCurrent/locateAndRefresh` 收尾时 `drainPendingLocate()` 补跑。

## 4. 冷启动 GL 天空不亮 + 启动/帧率优化

- **场景键失步**：`oppoSceneKeyOf` 在 `scene_map.json`（IO 线程异步解析）未就绪时返回
  null，而旧 `remember(snapshot, scenePhase, kindSticky, cityZone) { mutableStateOf(...) }`
  的键不含"资源就绪"→ null 被永久记住，整页纯蓝底，只有手动刷新才重算。
  现在 `OppoSceneKey` 暴露 `readyFlow: StateFlow<Boolean>`，WeatherScreen
  `collectAsState` 后纳入 remember 键；另加 30s 分钟 ticker，让日出/日落换档不刷新也跟上。
- **帧率接线**：GL `targetFps` 从 `effectQuality.fps.ambient`（120Hz 屏=60，半速率）
  改为 `fps.cloud`（跟满设备刷新率），云层噪声位移不再"一顿一顿"。
- **首帧提速**：渲染器首帧只上传当前预设用到的贴图，其余 40+ 张每帧 4 张分块补
  （`drainPreloadQueue`）；旧 `preloadAll()` 在 GL 线程阻塞几百毫秒，进页面云层"愣一下"。

## 验证要点（实机）
1. 小雨夜间：玻璃卡是细密轮廓光水珠 + 「细雨拂窗」文案，不再是灰斑 + 「暴雨倾盆」；
2. 卸载重装：授权弹窗点「允许」后立刻定位出当前城市天气，无需退出重进；
3. 冷启动进首页：1 秒内 GL 天空/云层亮起（不再纯蓝底等手动刷新）；
4. 120Hz 屏云层漂移平滑；首帧不卡顿。
