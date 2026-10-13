# DailyWeather v0.1.57 修复日志

> 日期：2026-10-13
> 分支：master（B 线紧急修复）
> 目标：修复夜间场景不生效——18:20 已入夜却显示日落粉紫背景。

## 现象（用户实机反馈）

- 时间：10-13 18:20，地点：丽金广场（招远市），天气：晴
- 错误：主屏显示粉紫渐变背景（日落配色），无任何天气元素
- 正确：应为深蓝夜空场景（深蓝底+月亮+星星+夜云，对照南风同地点同时刻）
- 日落卡显示正常：日落 17:26 / 日出 06:03

## 根因

**daily[0] 是「昨天」**——Providers.kt:146 把 yesterday 实况补在日卡首行：
```kotlin
val dailyWithYesterday = if (yesterday != null) listOf(yesterday) + daily else daily
```

而 `SceneBridge.oppoSceneKeyOf` 用 `daily.firstOrNull()` 取"今天"的日出日落：
- 昨天实况的 `sunRise`/`sunSet` 字段格式若非 ISO（`hourOf` 期望 `substring(11,16)`），解析失败返回空串
- `rise/set = null` → 走 `fallbackSlot(18) = 4`（日落档）
- `resolve(1, 4) = 1_4_sunny-sunset`（Color0=紫蓝 0.18,0.17,0.49 → Color4=粉橙 0.83,0.55,45）→ 粉紫背景

18:20 已入夜 54 分钟，正确应为 `slot 6` → `1_6_sunny-night`（深蓝 0.02,0.02,0.13 → 0.13,0.20,0.36）。

## 修复

`SceneBridge.oppoSceneKeyOf` 和 `SunCard` 都改为**按日期匹配今天**，兜底再取首项：

```kotlin
val todayDate = LocalDate.ofInstant(Instant.ofEpochMilli(nowEpochMs), zone).toString()
val today = snapshot?.daily?.firstOrNull { it.date == todayDate }
    ?: snapshot?.daily?.firstOrNull()
```

## 验证

- [x] assembleDebug 构建通过（2m06s）
- [x] assembleVersionedRelease 构建通过（产出 dailyweather-v0.1.57.apk）
- [ ] 实机：18:20 晴夜显示深蓝夜空（1_6_sunny-night），不再是粉紫日落
- [ ] 实机：日落卡显示今天的日出日落时间
- [ ] 实机：时段切换（17:26 日落前后）场景平滑过渡

## 关联

- C 线审查报告：docs/C_LINE_REVIEW_V0.1.56.md
- D 线性能优化：docs/notes-v0.1.56.md
- 本修复同时解决了日落卡显示昨天时间的问题
