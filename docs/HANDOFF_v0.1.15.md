# 每日天气（Daily Weather）交接摘要 — 2026-10-05 · v0.1.15

> 用途：把本轮工作流转到新对话。新对话开始时先读这份 + `docs/SKYPULSE_V4.3.64_UI_ANIMATION.md` + 记忆索引。

## 环境与构建
- 项目真身：`D:\DEV\dailyweather`（`D:\01 项目\100 个人文件\天气\每日天气` 是指向它的 junction）
- 包名 `com.dailyweather.app`；对标"南风天气 SkyPulse"的 1:1 复刻
- 构建：`cmd //c run_build.bat assembleDebug` / `assembleRelease`（JDK17/SDK/Gradle9.6 在 D:\DEV）
- 模拟器：`emulator-5554`（AVD `wui_api36`）在线；adb 在 `D:\DEV\android-sdk\platform-tools\adb.exe`
- fixture 注入验证：`adb shell am start -n com.dailyweather.app/.MainActivity --es fixture partly_day`（8 种：clear_day/clear_night/partly_day/cloudy_day/rain_day/thunder_day/snow_day/fog_day）
- 版本：**v0.1.15（versionCode 14）**

## 本轮（v0.1.15）做的 4 项修复
1. **上滑毛玻璃方框**：`scene/WeatherBackground.kt` 的 `TopProgressiveGlass` —— blur 22f→13f、玻璃带底部 24dp 圆角、底部 35% 融入天空色渐隐（用 `LocalSky.skyBottom`）。*视觉待真机确认。*
2. **定位随位置刷新**：`data/location/LocationCoordinator.kt` 新增 `startMoveWatcher(onMove, thresholdMeters=500)`（Passive+Network+GPS，位移>500m 才回调，省电）；`WeatherViewModel` 在 `locationEnabled` 开启时启动 watcher，onMove → `refreshManager.refreshCurrentLocation("location-moved")`。已用模拟器 geo fix 位移验证 logcat 触发。
3. **图标比例 v4**：`res/drawable/ic_launcher_foreground.xml` + `splash_icon.xml` —— 太阳放大（核心 r13/光晕 r18）、云朵收敛（5 圆 r10-13）、group 缩放 1.18→1.10。*待真机看桌面图标。*
4. **检查更新走本 App 仓库**：`di/AppContainer.checkLatestUpdate()` 直连 `api.github.com/repos/Qianzln/dailyweather/releases/latest`（匿名，public 仓库），不再走共享云函数 `/weather/app/latest`（那会混入微风的版本）；新增 `downloadUpdateApk`（host 白名单仅 github.com/githubusercontent.com）；`WeatherViewModel.installUpdate()` 下载到应用私有目录 + FileProvider + ACTION_VIEW 安装；`AndroidManifest` 加 `REQUEST_INSTALL_PACKAGES` + FileProvider + `res/xml/provider_paths.xml`；设置页加"下载并安装 vX"行（仅 `updateHasNewer` 时显示）。已验证读到 v0.1.14（dailyweather 的）而非微风。

## GitHub 推送链（每日天气独立，别用微风的）
- **仓库：`Qianzln/dailyweather`（现为 public）**。GitHub 账号 `Qianzln`。
- ⚠️ 早期误建过一个 `Qianzln/daily-weather`（带连字符、empty、git 后端未初始化、无 admin 权限删不掉）——**弃用，以 `dailyweather` 为准**。
- 工具集 `tools/dailyweather/`（已入库）：
  - `read_token.ps1` — 从 **Windows 凭据管理器** 目标 `GitHub - https://api.github.com/Qianzln`（scopes repo,user,workflow）取 token，只输出到 stdout，**不落盘不打印**
  - `gh_push.mjs` — GitHub Git Data API 重放 commit 链（本机 git push 被出口代理 502 拦，api.github.com 可达）；支持增量 / `-ForceAll`（全链 force main）/ 大 commit 分批建树
  - `push.ps1` — 一键（取 token 注入 `GH_TOKEN` env → 跑 node）
  - `create_repo.ps1` — 以 `auto_init=true` 建库（否则 git 后端 empty，git API 全 409、Contents 404，推不动）
  - `release.ps1` / `upload_release.mjs` — 建 GitHub Release + 传 APK（幂等）
- **推送用法**：`powershell -ExecutionPolicy Bypass -File tools/dailyweather/push.ps1 -ForceAll`（全链最稳）
- **已知坑**：增量模式偶发 tree SHA 不匹配（`5fa5513e→5492df09` 那种），根因未深究；**统一用 `-ForceAll` 全链重放**（20 commit 全 SHA 一致、MATCH=true 验证过）
- **发版**：`release.ps1 -Tag vX -Apk <path> -Notes <path>`；当前最新 release **v0.1.15**
  下载直链：`https://github.com/Qianzln/dailyweather/releases/download/v0.1.15/daily-weather-0.1.15.apk`

## 场景/UI 架构现状（本轮之前已做，供背景）
- 场景引擎 = 南风 PNG 精灵方案：`scene/SpriteSky`（天空帧轮播，已"帧流化"：3.2s+1.1s+连续漂移+呼吸缩放）、`SpriteClouds`（数据表 `spriteCloudsFor`，按南风逆向参数重排：每层 3-6 朵/慢速/大尺寸）、`ParticleSprites`、`NoiseHaze`（AGSL 噪声云，升级为带明暗）
- **按南风"天气×特效组件"组织**：`scene/WeatherEffects.kt` 的 `WeatherEffectHost` + 每天气一个 Effect（`SunnyDayEffect/ClearNightEffect/CloudyEffect/FogEffect/RainEffect/ThunderEffect/SnowEffect/SleetEffect/WindEffect`）
- UI 跟随天气：`ui/theme` 的 `SkyPalette.of(scene)` 派生卡片/文字色（卡片 alpha 0.78 跟天空）
- 8 个桌面组件（small 已改南风 3 行逐小时；新增 AQI 光谱卡 4×1）

## 遗留 / 待办
- [ ] 任务 1（模糊方框）与任务 3（图标比例）**需真机/装机看观感**，如不合意回来调参（`TopProgressiveGlass` 的 blur/渐隐；图标 xml 的圆半径/缩放）
- [ ] 仓库当前 **public**（源码+APK 都可匿名下载）。要收回源码 → `PATCH /repos/Qianzln/dailyweather {private:true}`（可用 `push.ps1` 同款 token），改回后 release 直链需登录
- [ ] 遗留的 `Qianzln/daily-weather`（empty 仓）删不掉（现 token 无 admin:repo），需有 admin 权限的令牌清理
- [ ] Mimosa 在提交时提示"本批扫描未完整覆盖"——需要时跑一次完整项目安全审计
- [ ] `release/` 目录被 .gitignore 排除（APK 只经 GitHub release 分发）

## 关键文件索引
- `app/src/main/java/com/dailyweather/app/scene/WeatherBackground.kt`（模糊）
- `app/src/main/java/com/dailyweather/app/data/location/LocationCoordinator.kt`（定位）
- `app/src/main/java/com/dailyweather/app/di/AppContainer.kt`（更新检查/下载）
- `app/src/main/res/drawable/ic_launcher_foreground.xml` + `splash_icon.xml`（图标）
- `tools/dailyweather/`（推送/release 工具）
- `docs/SKYPULSE_V4.3.64_UI_ANIMATION.md`（南风 UI/动画逆向：组件清单、云规格参数、各天气特效映射）
