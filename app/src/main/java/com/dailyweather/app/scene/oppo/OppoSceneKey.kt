package com.dailyweather.app.scene.oppo

import android.content.Context
import android.util.Log
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate

/**
 * 场景键选择：把（实况天气 × 时段 × 云量 × 降水强度）解析成 `pack.json` 的 62 个预设键之一。
 *
 * ## 哪些是**照抄原版**、哪些是**重建**
 *
 * | 部分 | 状态 | 依据 |
 * |---|---|---|
 * | [timeSlot] 时段槽位 | ✅ **逐行照抄** | 南风 `w7/z0.java` 的 `d(minute, sunrise, sunset)` |
 * | [polarSlot] 极昼极夜 | ✅ **逐行照抄** | `w7/z0.java` 的 `a(latitude, date)` |
 * | [isHazeCondition] 霾形成条件 | ✅ **逐行照抄**（从 smali 还原） | `w7/z0.java` 的 `b(humidity, pm25, windKmh)` |
 * | [resolve] 组号+槽位 → 键 | ✅ **照抄数据表** | `w7/p0.java` 静态块派生的 `groups: Map<组, Map<槽位, 键>>` |
 * | [groupFor] 天气 → 组号 | ⚠️ **重建** | `w7/p0.java` 的 `b(...)`（782 指令）**反编译失败**，无法还原 |
 *
 * ### ⚠️ 关于 [groupFor] 的诚实说明
 * 南风选择组号的那个方法（`p0.b(skycon, phase, sunPose, hasThunderAlert, timeSlot, cloudrate, unusualClouds)`）
 * 反编译产物里是 `Method dump skipped, instructions count: 782`，**拿不到源码**。
 * 所以这一层是按「参数名 + 组表 + 各组预设的可见差异」重建的，阈值抽成了常量便于实机校正：
 *
 * - **降水强度分档**（雨 6/7/8/9、雪 10/11/12）：按小时降水率 mm/h 分 L/M/H/T 四档。
 * - **云量分档**（1/55、56/58/101）：实测 `1` 与 `55` 的 5 段天空色**完全相同**，只差云的朵数
 *   （`1`=2 朵、`55`=3 朵）→ 故按云量切换；`58`=2 朵、`56`=4 朵、`101`=4 朵且专供早晚 → 同理。
 * - **霾**：走 [isHazeCondition] 门槛（湿度 60–80%、PM2.5 20–100、风速 0.3–5.5 m/s），不满足则落雾。
 */
object OppoSceneKey {

    private const val TAG = "OppoSceneKey"
    private const val ASSET = "oppo/scene_map.json"

    /** 组号 → (槽位 → 场景键)。数据来自 `p0.java`，不是猜的。 */
    @Volatile
    private var groups: Map<Int, Map<Int, String>> = emptyMap()

    val ready: Boolean get() = groups.isNotEmpty()

    /**
     * [ready] 的可观察版本：组表在 IO 线程加载完成后发 true。
     *
     * 为什么需要：`oppoSceneKeyOf` 在未就绪时返回 null，而 Compose 侧
     * `remember(snapshot, …)` 的键不含"资源就绪"——冷启动后场景键会**一直**是 null，
     * GL 天空不亮，直到手动刷新才重算（2026-10-12 实机复现：进页面只有纯蓝底）。
     * 有了 StateFlow，UI 侧 collectAsState 即可在就绪瞬间重算场景键。
     */
    private val _readyFlow = kotlinx.coroutines.flow.MutableStateFlow(false)

    /**
     * 组表就绪的可观察流。UI 侧可用 `collectAsState(oppoSceneKey.readyFlow)` 响应式消费；
     * 未就绪时值为 false，就绪后自动切 true（冷启动场景键永久 null 问题的根治手段）。
     * 同时保留 [ready] 属性给非响应式代码直接使用。
     */
    val readyFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = _readyFlow

    val allKeys: List<String> get() = groups.values.flatMap { it.values }

    fun prewarm(context: Context) {
        if (ready) return
        val app = context.applicationContext
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val text = app.assets.open(ASSET).bufferedReader().use { it.readText() }
                val g = JSONObject(text).getJSONObject("groups")
                val out = LinkedHashMap<Int, Map<Int, String>>()
                val gk = g.keys()
                while (gk.hasNext()) {
                    val gs = gk.next()
                    val slotObj = g.getJSONObject(gs)
                    val slots = LinkedHashMap<Int, String>()
                    val sk = slotObj.keys()
                    while (sk.hasNext()) { val s = sk.next(); slots[s.toInt()] = slotObj.getString(s) }
                    gs.toIntOrNull()?.let { out[it] = slots }
                }
                groups = out
                _readyFlow.value = true
                Log.i(TAG, "已加载 ${out.size} 个组 / ${out.values.sumOf { it.size }} 个键")
            }.onFailure { Log.e(TAG, "场景组表加载失败：${it.message}", it) }
        }
    }

    // ==================== 照抄原版：时段槽位 ====================

    /**
     * 时段槽位 0..6，逐行照抄 `w7/z0.java:157-195`。
     *
     * `0`=黎明(日出前 30min) `1`=清晨 `2`=正午前 `3`=午后 `4`=日落前 `5`=傍晚(日落后 30min) `6`=夜。
     * 白昼不足 2 小时（高纬冬季）时只返回 `2`/`6` 两档。
     *
     * @param minute 当天已过的分钟数（0..1439）
     * @param sunrise 日出分钟数
     * @param sunset 日落分钟数
     */
    fun timeSlot(minute: Int, sunrise: Int, sunset: Int): Int {
        if (sunrise == sunset) return 2
        if (sunset < sunrise) {
            // 跨日（极地或数据异常）：夜在 [sunset, sunrise) 之外
            return if (minute < sunset || minute >= sunrise) 2 else 6
        }
        val dayLen = sunset - sunrise
        if (dayLen <= 120) {
            return if (minute >= sunrise && minute < sunset) 2 else 6
        }
        val d10 = Math.round(0.1f * dayLen)
        val half = sunrise + Math.round(dayLen * 0.5f)
        return when {
            minute >= sunrise - 30 && minute < sunrise -> 0
            minute >= sunrise && minute < sunrise + d10 -> 1
            minute >= sunrise + d10 && minute < half -> 2
            minute >= half && minute < sunset - d10 -> 3
            minute >= sunset - d10 && minute < sunset -> 4
            minute < sunset || minute >= sunset + 30 -> 6
            else -> 5
        }
    }

    // ==================== 照抄原版：极昼极夜 ====================

    /**
     * 极区（|纬度| ≥ 66.34°）直接返回 `2`（极昼）或 `6`（极夜），逐行照抄 `w7/z0.java:19-41`。
     * 非极区返回 null，交给 [timeSlot]。
     */
    fun polarSlot(latitude: Double?, date: LocalDate): Int? {
        if (latitude == null) return null
        val abs = kotlin.math.abs(latitude)
        if (abs < 66.34 || abs > 90.0) return null
        val summer = !date.isBefore(LocalDate.of(date.year, 3, 22)) &&
            date.isBefore(LocalDate.of(date.year, 9, 23))
        val north = latitude >= 0.0
        return if (summer == north) 2 else 6
    }

    // ==================== 照抄原版：霾形成条件 ====================

    /**
     * 是否满足霾的形成条件，从 `w7/z0.java` 的 `b()` smali 还原：
     * ```
     * 湿度 ∈ [60, 81) %      （入参是 0..1 的比值，乘 100 取整）
     * PM2.5 ∈ [20, 101)      （未提供则跳过该项）
     * 风速 ∈ [0.3, 5.5) m/s  （入参是 km/h，除以 3.6）
     * ```
     * 风速下限 0.3 表示必须有风（否则是雾不是霾），上限 5.5 表示风太大吹散。
     */
    fun isHazeCondition(humidity: Double?, pm25: Double?, windKmh: Double?): Boolean {
        val h = humidity?.takeIf { it in 0.0..1.0 }?.let { (it * 100).toInt() } ?: return false
        if (h < 60 || h >= 81) return false
        if (pm25 != null) {
            val p = pm25.takeIf { it >= 0.0 }?.toInt() ?: return false
            if (p < 20 || p >= 101) return false
        }
        val w = windKmh?.takeIf { it >= 0.0 }?.let { it / 3.6 } ?: return false
        if (w < 0.3 || w >= 5.5) return false
        return true
    }

    // ==================== 照抄组表：解析键 ====================

    /**
     * 在指定组里取与 [slot] 最接近的可用槽位对应的键。
     *
     * 组表是权威数据（照抄 `p0.java`）：凡只有 `{2, 6}` 两档的天气族（雨雪雾霾沙尘风雷），
     * 白天各时段一律落到 `2`、夜间落到 `6`，与原版一致。
     */
    fun resolve(group: Int, slot: Int): String? {
        val slots = groups[group] ?: return null
        slots[slot]?.let { return it }
        // 取最近的可用槽位：白天侧（<=5）优先降级到 2，夜间优先 6，距离相同取更小的槽位。
        return slots.keys.minWithOrNull(
            compareBy({ kotlin.math.abs(it - slot) }, { it })
        )?.let { slots[it] }
    }

    // ==================== 重建：天气 → 组号 ====================

    /** 降水强度分档（mm/h，小时降水率）。 */
    private const val RAIN_L_MM = 2.5
    private const val RAIN_M_MM = 8.0
    private const val RAIN_H_MM = 16.0

    /** 云量分档（0..1）。低于 [CLOUD_LOW] 视为少云，高于 [CLOUD_HIGH] 视为多云。 */
    private const val CLOUD_LOW = 0.30
    private const val CLOUD_HIGH = 0.60

    /**
     * 天气 → 组号。**这一层是重建的**（原方法反编译失败，详见类注释）。
     *
     * @param skycon 上游天气码（小米 wtr-v3 / 彩云），大小写不敏感
     * @param slot [timeSlot] 的结果
     * @param cloudrate 云量 0..1，未知传 null
     * @param precipMmPerHour 小时降水率 mm/h，未知传 null
     * @param hasThunderAlert 是否有雷电预警
     * @param hazeOk [isHazeCondition] 的结果
     */
    fun groupFor(
        skycon: String,
        slot: Int,
        cloudrate: Double?,
        precipMmPerHour: Double?,
        hasThunderAlert: Boolean,
        hazeOk: Boolean,
    ): Int {
        val s = skycon.uppercase()
        return when {
            s.startsWith("THUNDER") -> 21                       // 雷暴
            s.contains("HAIL") -> if (s.contains("RAIN")) 18 else 2   // 雨夹冰雹 / 冰雹
            s.contains("SLEET") -> 16                           // 雨夹雪
            s.contains("SNOW") -> snowGroup(precipMmPerHour)     // 雪 10/11/12
            s.contains("RAIN") || s.contains("DRIZZLE") || s.contains("SHOWER") ->
                if (hasThunderAlert) 20 else rainGroup(precipMmPerHour)   // 暴风雨 / 雨 6/7/8/9
            s.contains("DUST") || s.contains("SAND") -> 14      // 沙尘
            s.contains("HAZE") -> if (hazeOk) 15 else 17        // 霾（条件不满足落雾）
            s.contains("FOG") || s.contains("MIST") -> 17       // 雾
            s.contains("WIND") || s.contains("GALE") -> 19      // 大风
            // 阴：OVERCAST 或彩云/小米词表的 CLOUDY（小米码 2=阴 → CLOUDY，
            // SkyconMap 文案=阴）。旧写法把 CLOUDY 分进多云族 56（蓝底亮天），
            // 实机观感"文字是阴、背景是晴天"（2026-10-13 反馈）。
            s.contains("OVERCAST") || s == "CLOUDY" -> 5
            s.contains("CLOUDY") -> cloudyGroup(slot, cloudrate) // 多云 56/58/101（PARTLY_CLOUDY_*）
            else -> sunnyGroup(slot, cloudrate)                  // 晴 1/55（含 dawn/sunset 的多云变体）
        }
    }

    private fun rainGroup(mm: Double?): Int = when (bin(mm)) {
        0 -> 6; 1 -> 7; 2 -> 8; else -> 9
    }

    private fun snowGroup(mm: Double?): Int = when (bin(mm)) {
        0 -> 10; 1 -> 11; else -> 12
    }

    /** null → 0（最轻档）；按 mm/h 分 0..3。 */
    private fun bin(mm: Double?): Int {
        if (mm == null) return 0
        return when {
            mm < RAIN_L_MM -> 0
            mm < RAIN_M_MM -> 1
            mm < RAIN_H_MM -> 2
            else -> 3
        }
    }

    /**
     * 晴族：`1`（少云）或 `55`（有云）。
     * 实测两者 5 段天空色**完全相同**，只差云朵数（`1`=2 朵 / `55`=3 朵）。
     */
    private fun sunnyGroup(slot: Int, cloudrate: Double?): Int {
        val c = cloudrate ?: return 1
        return if (c >= CLOUD_LOW) 55 else 1
    }

    /**
     * 多云族：`58`（2 朵云）/ `56`（4 朵云）/ `101`（4 朵云，专供黎明与日落）。
     * 实测三者天空色各不相同，且 `101` 的温度与"多云"一致——所以它是"多云 + 早晚"。
     */
    private fun cloudyGroup(slot: Int, cloudrate: Double?): Int {
        val c = cloudrate ?: CLOUD_HIGH
        if ((slot == 0 || slot == 4) && c >= CLOUD_HIGH) return 101
        return if (c >= CLOUD_LOW) 56 else 58
    }

    /** 一步到位的入口。返回 null 表示组表未就绪或该组无可用槽位（调用方应保持上一帧）。 */
    fun select(
        skycon: String,
        slot: Int,
        cloudrate: Double? = null,
        precipMmPerHour: Double? = null,
        hasThunderAlert: Boolean = false,
        hazeOk: Boolean = false,
    ): String? {
        val group = groupFor(skycon, slot, cloudrate, precipMmPerHour, hasThunderAlert, hazeOk)
        val key = resolve(group, slot)
        Log.d("OppoSceneKey", "skycon=$skycon slot=$slot group=$group key=$key")
        return key
    }
}
