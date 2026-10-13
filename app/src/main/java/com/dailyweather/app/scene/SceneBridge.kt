package com.dailyweather.app.scene

import com.dailyweather.app.data.Fixture
import com.dailyweather.app.data.model.WeatherSnapshot
import java.util.Calendar

/**
 * 快照 → 场景状态。首页只认 [WeatherSceneState]，不再自己判时段、判天气。
 *
 * 时段分桶照南风场景键里 `skycon_08h_20h` / `skycon_20h_32h` 的取向：
 * 清晨与傍晚是独立档，不是「白天」暗一点的版本。
 */
object SceneBridge {

    /**
     * kind 滞回（防"翻牌"）：数据源在相邻刷新里会在 CLEAR/PARTLY_CLOUDY/CLOUDY/WIND 之间
     * 来回跳（同一天的"晴""晴转多云""风"），每翻一次，天空帧/云精灵/tint 整套跟着变 →
     * 顶部天空"一会儿深一会儿浅"。规则：
     * - 新 kind 需**连续两次**快照确认才生效（单次翻牌被压住）；
     * - 降水/雾类（RAIN/THUNDER/SNOW/SLEET/FOG）从有到无、从无到有**立即生效**（安全优先）；
     * - 同一 pending 挂起超过 10 分钟也放行（避免一直压着真实天气变化）。
     * 进程内按 cityKey 记忆，两个消费面（主屏/子页天空）共用同一份。
     */
    private class StickyKind {
        var stable = WeatherKind.CLEAR
        var pending: WeatherKind? = null
        var pendingSinceMs = 0L
        var pendingCount = 0
    }

    private val stickyKinds = java.util.concurrent.ConcurrentHashMap<String, StickyKind>()

    private val IMMEDIATE_KINDS = setOf(
        WeatherKind.RAIN, WeatherKind.THUNDER,
        WeatherKind.SNOW, WeatherKind.SLEET, WeatherKind.FOG, WeatherKind.DUST,
    )
    private val PENDING_TIMEOUT_MS = 10 * 60_000L

    private fun stabilizeKind(cityKey: String, fresh: WeatherKind, nowMs: Long): WeatherKind {
        val s = stickyKinds.getOrPut(cityKey) {
            StickyKind().also { it.stable = fresh }
        }
        if (fresh == s.stable) {
            s.pending = null; s.pendingCount = 0
            return s.stable
        }
        if (fresh in IMMEDIATE_KINDS || s.stable in IMMEDIATE_KINDS) {
            s.stable = fresh; s.pending = null; s.pendingCount = 0
            return fresh
        }
        if (s.pending == fresh) {
            s.pendingCount++
            if (s.pendingCount >= 2 || nowMs - s.pendingSinceMs >= PENDING_TIMEOUT_MS) {
                s.stable = fresh; s.pending = null; s.pendingCount = 0
                return s.stable
            }
            return s.stable
        }
        s.pending = fresh
        s.pendingSinceMs = nowMs
        s.pendingCount = 1
        return s.stable
    }

    fun phaseOf(cal: Calendar): SkyPhase = when (Fixture.hourOverride ?: cal.get(Calendar.HOUR_OF_DAY)) {
        in 5..8 -> SkyPhase.MORNING
        in 9..16 -> SkyPhase.DAY
        in 17..19 -> SkyPhase.EVENING
        else -> SkyPhase.NIGHT
    }

    fun kindOf(skycon: String): WeatherKind = when {
        skycon.startsWith("THUNDER") -> WeatherKind.THUNDER
        skycon.contains("SLEET") -> WeatherKind.SLEET
        skycon.contains("SNOW") -> WeatherKind.SNOW
        skycon.contains("RAIN") || skycon.contains("DRIZZLE") || skycon.contains("SHOWER") -> WeatherKind.RAIN
        skycon == "CLOUDY" || skycon == "OVERCAST" -> WeatherKind.CLOUDY
        skycon.startsWith("PARTLY_CLOUDY") -> WeatherKind.PARTLY_CLOUDY
        skycon.startsWith("CLEAR") -> WeatherKind.CLEAR
        skycon.contains("HAZE") || skycon.contains("FOG") || skycon.contains("MIST") -> WeatherKind.FOG
        skycon.contains("DUST") || skycon.contains("SAND") -> WeatherKind.DUST
        skycon.contains("WIND") || skycon.contains("GALE") -> WeatherKind.WIND
        else -> WeatherKind.PARTLY_CLOUDY
    }

    /**
     * 降水强度 0–1：按**未来两小时的小时降水量（mm/h）**，不是降水概率。
     *
     * ⚠️ 历史教训（2026-10-12 实机截图）：用概率当强度时，「小雨 + 96% 概率」被算成
     * 0.96 → 玻璃雨滴卡出「暴雨倾盆」文案 + 满密度水珠、全屏折射也按暴雨强度扭曲。
     * 概率只表达"下不下"，不表达"下多大"；强度必须看 mm/h。
     *
     * 分档（对齐气象小雨/中雨/大雨界值）：2.5 mm/h ≈ 0.25、8 mm/h ≈ 0.45、
     * 16 mm/h ≈ 0.63、≥40 mm/h = 1.0；下雨时保底 0.18（小雨也要有可见水珠）。
     * 数据源缺 mm/h（全 0）时退化为概率 × 0.35，避免雨景完全静止。
     */
    fun intensityOf(snapshot: WeatherSnapshot?, nowEpochMs: Long): Float {
        val snap = snapshot ?: return 0f
        val window = snap.hourly
            .filter { it.time in nowEpochMs until nowEpochMs + 2 * 3600_000L }
            .ifEmpty { snap.hourly.take(2) }
        val mm = window.maxOfOrNull { it.precipitationMm.toFloat() } ?: 0f
        if (mm > 0.05f) return kotlin.math.sqrt(mm / 40f).coerceIn(0f, 1f).coerceAtLeast(0.18f)
        val pop = window.maxOfOrNull { it.precipitationProbability.toFloat() } ?: 0f
        return (pop / 100f * 0.35f).coerceIn(0f, 0.35f)
    }

    /**
     * 日照进度（对应南风 SunPose）：当前时刻在日出→日落区间里的位置 0–1。
     * 夜间/缺数据返回 null，太阳不画。
     */
    fun sunProgressOf(snapshot: WeatherSnapshot?, zone: java.time.ZoneId, nowEpochMs: Long): Float? {
        val today = snapshot?.daily?.firstOrNull() ?: return null
        if (today.sunrise.isBlank() || today.sunset.isBlank()) return null
        val rise = minutesOf(today.sunrise) ?: return null
        val set = minutesOf(today.sunset) ?: return null
        if (set <= rise) return null
        val now = java.time.LocalTime.ofInstant(java.time.Instant.ofEpochMilli(nowEpochMs), zone)
        val nowMin = now.hour * 60 + now.minute + now.second / 60f
        return ((nowMin - rise) / (set - rise).toFloat())
            .takeIf { it in 0f..1f }
    }

    /** "HH:mm" → 当天第几分钟；解析失败返回 null。 */
    private fun minutesOf(s: String): Int? {
        val parts = s.take(5).split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val m = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return h * 60 + m
    }

    /** 风强 0–1：以 60 km/h 为满档。 */
    fun windOf(snapshot: WeatherSnapshot?): Float =
        ((snapshot?.realtime?.windSpeed ?: 0.0) / 60.0).toFloat().coerceIn(0.05f, 1f)

    /**
     * 南风 v4.3.99 的 OPPO 场景键（62 个预设之一）。整套映射见 [com.dailyweather.app.scene.oppo.OppoSceneKey]。
     *
     * 与 [stateFor] 的 9 种 [WeatherKind] **并存**：后者继续服务现有 Compose 渲染器（回退路径），
     * 前者服务 GLES 场景渲染。两条路径共用同一份快照，互不依赖。
     *
     * 时段槽位用**真实日出日落**算（`OppoSceneKey.timeSlot`），不是按小时硬切——
     * 这是南风 `w7/z0.d` 的口径，也是北京冬天 16:50 就进日落档、夏天 19:30 才进的原因。
     * 取不到日晷数据时退化为按小时分桶（见 [fallbackSlot]），保证不返回 null。
     */
    fun oppoSceneKeyOf(
        snapshot: WeatherSnapshot?,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
        latitude: Double? = null,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): String? {
        if (!com.dailyweather.app.scene.oppo.OppoSceneKey.ready) return null

        // ⚠️ daily[0] 可能是「昨天」（Providers 把 yesterday 实况补在日卡首行），
        // 直接 firstOrNull() 会用昨天的日出日落算今天的时段——若昨天数据缺 sunRise/sunSet，
        // rise/set 解析失败 → fallbackSlot(18)=4 → 18:20 已入夜却显示日落粉紫（B1 bug）。
        // 修复：按日期匹配今天，兜底再取首项。
        val todayDate = java.time.LocalDate.ofInstant(java.time.Instant.ofEpochMilli(nowEpochMs), zone).toString()
        val today = snapshot?.daily?.firstOrNull { it.date == todayDate }
            ?: snapshot?.daily?.firstOrNull()
        val rise = minutesOf(today?.sunrise.orEmpty())
        val set = minutesOf(today?.sunset.orEmpty())
        val date = java.time.LocalDate.ofInstant(java.time.Instant.ofEpochMilli(nowEpochMs), zone)
        // 视觉校准（debug）：fixture 的 `hour` 必须在这里也生效。
        // `oppoSceneKeyOf` 不经过 [phaseOf]，所以单靠 phaseOf 那处覆盖不了场景键——
        // 实测后果：8 个 fixture 全部落在「夜」档（系统时区 UTC 18:xx = 夜），
        // 白天场景永远验不到。`Fixture.hourOverride` 只在 debug 被写入，release 恒 null。
        val nowReal = java.time.LocalTime.ofInstant(java.time.Instant.ofEpochMilli(nowEpochMs), zone)
        val now = com.dailyweather.app.data.Fixture.hourOverride
            ?.let { java.time.LocalTime.of(it.coerceIn(0, 23), 0) }
            ?: nowReal

        val slot = com.dailyweather.app.scene.oppo.OppoSceneKey.polarSlot(latitude, date)
            ?: if (rise != null && set != null) {
                com.dailyweather.app.scene.oppo.OppoSceneKey.timeSlot(now.hour * 60 + now.minute, rise, set)
            } else {
                fallbackSlot(now.hour)
            }

        val rt = snapshot?.realtime
        val skycon = snapshot?.currentSkycon.orEmpty()
        val precipMm = snapshot?.hourly?.firstOrNull()?.precipitationMm
        val hasThunder = snapshot?.alerts?.any { it.title.contains("雷") } == true
        // 数据源不提供云量：按天气码反推一个代理值，让 1/55 与 56/58 的区分仍然有效。
        val cloud = cloudrateProxyOf(skycon)
        val hazeOk = com.dailyweather.app.scene.oppo.OppoSceneKey.isHazeCondition(
            humidity = rt?.humidity,
            pm25 = rt?.pm25,
            windKmh = rt?.windSpeed,
        )
        val key = com.dailyweather.app.scene.oppo.OppoSceneKey.select(
            skycon = skycon,
            slot = slot,
            cloudrate = cloud,
            precipMmPerHour = precipMm,
            hasThunderAlert = hasThunder,
            hazeOk = hazeOk,
        )
        android.util.Log.d("SceneBridge", "oppoSceneKey: skycon=$skycon slot=$slot cloud=$cloud key=$key")
        return key
    }

    /** 无日晷数据时的小时→槽位退化映射（与 [com.dailyweather.app.scene.oppo.OppoSceneKey.timeSlot] 的分段意图一致）。 */
    private fun fallbackSlot(hour: Int): Int = when (hour) {
        5 -> 0          // 黎明
        6, 7, 8 -> 1    // 清晨
        9, 10, 11 -> 2  // 正午前
        12, 13, 14 -> 2
        15, 16 -> 3     // 午后
        17, 18 -> 4     // 日落
        19 -> 5         // 傍晚
        else -> 6       // 夜
    }

    /**
     * 云量的代理值。上游（小米 wtr-v3 / 彩云）没有直接给 0–1 的云量，
     * 用天气码反推：晴≈5%、晴转多云≈20%、阴/多云≈85%。
     * 只影响 `1/55`（少云/有云）与 `56/58`（多云/少云）的选择，不影响天空配色。
     *
     * ⚠️ **顺序敏感**：`PARTLY_CLOUDY` 必须排在 `contains("CLOUDY")` 之前。
     * 原写法把 `contains("CLOUDY")` 放前面，`PARTLY_CLOUDY_DAY` 会先命中它 →
     * `startsWith("PARTLY_CLOUDY")` 那支成了**死代码**，「少云 58」永远轮不到，
     * `partly_day` 与 `cloudy_day` 两个 fixture 会选到同一个场景键（实测确认）。
     */
    private fun cloudrateProxyOf(skycon: String): Double {
        val s = skycon.uppercase()
        return when {
            // 少云：必须 < CLOUD_LOW(0.30)，否则 cloudyGroup 判成 56（4 朵）而不是 58（2 朵）。
            s.startsWith("PARTLY_CLOUDY") -> 0.20
            s.contains("OVERCAST") || s.contains("CLOUDY") -> 0.85
            s.contains("HAZE") || s.contains("FOG") || s.contains("MIST") -> 0.90
            else -> 0.05
        }
    }

    fun stateFor(
        snapshot: WeatherSnapshot?,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
        cityKey: String = "default",
    ): WeatherSceneState {
        val rawKind = kindOf(snapshot?.currentSkycon ?: "")
        // 相位必须按城市当地时间判：设备在别的时区时，默认时区会把白天判成夜。
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone(zone))
        val nowMs = System.currentTimeMillis()
        val kind = stabilizeKind(cityKey, rawKind, nowMs)
        val wet = kind == WeatherKind.RAIN || kind == WeatherKind.THUNDER ||
            kind == WeatherKind.SNOW || kind == WeatherKind.SLEET
        return WeatherSceneState(
            kind = kind,
            phase = phaseOf(cal),
            intensity = if (wet) intensityOf(snapshot, snapshot?.serverTime ?: 0L) else 0f,
            wind = windOf(snapshot),
            sunProgress = sunProgressOf(snapshot, zone, nowMs),
        )
    }
}

