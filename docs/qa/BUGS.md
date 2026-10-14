# 每日天气 Bug 清单（QA 登记）

> 维护：Meteor（2026-10-13 起接管，原 QA 岗 Lens 已撤销）。状态机：待修复 → 修复中 → 待实机验证 → 已验证关闭（用户实机验收，通过后更新 SCENE_CHECKLIST 对应行状态）。

## B1 🔴 夜间场景不生效：晴夜显示粉紫渐变，而非深蓝夜空

- **状态**：待实机验证
- **登记**：2026-10-13（Chief 转用户实机报告）
- **版本/设备**：v0.1.56 实机（5G 安卓，GLES ≥3.2 机型）
- **现象**：18:20（当天日落 17:26 之后 54 分钟）首页天空呈**粉紫渐变**，无月亮、无星星、无云；逐小时图标已是月亮（kind/phase 链路夜间感知正常）。同机同时刻南风天气（18:26）为深蓝夜空 + 明月 + 夜云（正确参照）。
- **证据**：`docs/qa/evidence/b1-wrong-v0.1.56.jpg`（本 App，错误）、`docs/qa/evidence/b1-ref-nanfeng.jpg`（南风，正确参考）

### QA 预分析（只读审查结论，供 Meteor 定位）

**已排除**：
- 时段槽位数学正确：18:20 = 1100min ≥ 日落(1046)+30 → `timeSlot` 必返回 6（夜），`OppoSceneKey.kt:116`。
- 数据源提供了正确日出日落（截图日落卡片 17:26 / 日出 06:03）。
- 问题集中在「场景键解析 → GL 出图」这一段。

**嫌疑排序**：
1. **S1：`fallbackSlot` 兜底被触发**。`SceneBridge.kt:154-172`：`snapshot.daily.firstOrNull()` 的 sunrise/sunset 解析为 null 时走 `fallbackSlot(18)` = 槽位 4（日落）→ 键 = `1_4_sunny-sunset`（粉紫渐变，且无月亮/粒子）——与截图吻合。（注意截图每日列表首项是「昨天 10月12日」，若 `daily.firstOrNull()` 取到的是昨天或其日晷字段为空，即中此招。）
2. **S2：键对但贴图全挂**。云/月/星实体在贴图未就绪时静默跳过（`OppoSceneRenderer.kt:337` `tex == 0 → continue`），只剩背景渐变 quad。若 logcat 显示键 = `1_6_sunny-night` 但无月亮星点，查 `OppoTextureCache` 加载失败/竞态。
3. **S3：GL 层未出图 + 未识别来源的兜底渐变**。主屏 WeatherScreen 未见全屏 Compose 渐变背景（`SkyPalette` 只分 NIGHT/非 NIGHT），若 S1/S2 均不中，需 Meteor 定位粉紫渐变的实际出处（含 `paused`/「天气动效」开关状态异常的可能）。

**一条命令定位**：
```
adb logcat -s SceneBridge:D OppoSceneKey:D OppoSceneRenderer:I OppoTextureCache:D
```
看 `SceneBridge oppoSceneKey: skycon=… slot=… key=…` 一行即可分流：slot=4 → S1；slot=6 且 key=1_6 → S2/S3。

### 修复记录（2026-10-13，a518f057 / v0.1.57）

- **根因确认：S1 完全命中**。Providers 把 yesterday 实况补在日卡首行（`daily[0]`=昨天），而 `SceneBridge.oppoSceneKeyOf` 和 `SunCard` 都用 `daily.firstOrNull()` 取「今天」拿到昨天——昨天 sunRise/sunSet 解析失败（非 ISO 格式）→ rise/set=null → `fallbackSlot(18)`=4 → `resolve(1,4)`=`1_4_sunny-sunset`（粉紫日落）。
- **修复**：按日期匹配今天（`LocalDate.now(zone).toString()`），兜底再取首项；日落卡显示昨天时间的同根因问题一并修复。
- **构建验证**：assembleDebug + assembleVersionedRelease 均通过；APK `release/dailyweather-v0.1.57.apk`（14.76MB）。

**验收标准**：实机日落后显示 `1_6_sunny-night`：深蓝夜空 + 月亮 + 星点粒子；日落卡显示当天日落时间（非昨天）。验收人：用户实机确认，通过后更新 `SCENE_CHECKLIST.md` 的 `1_6` 行状态并在此关闭本单。

---

（暂无其他登记项）
