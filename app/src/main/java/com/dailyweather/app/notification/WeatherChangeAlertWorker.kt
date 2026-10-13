package com.dailyweather.app.notification

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dailyweather.app.DailyWeatherApp
import com.dailyweather.app.R
import kotlinx.coroutines.flow.first
import kotlin.math.abs

private val Context.changeAlertStore by preferencesDataStore(name = "weather_change_alert")

/**
 * 天气变化提醒 Worker —— 消费三个此前「只拨不生效」的通知开关：
 * `notify_temp_change`（温度骤变）/ `notify_wind`（大风）/ `notify_extreme`（极端天气）。
 *
 * 对齐南风 `WeatherSettings` 的 `tempChangeAlert` / `windAlert`（南风另有 rainAlert / typhoonAlert /
 * glowAlert，那三项依赖尚不存在的数据源，见重构计划 P5）。
 *
 * 语义：
 * - **温度骤变**：以「当日首次读取到的气温」为基线，|当前 − 基线| ≥ 5 ℃ 才提醒（每天至多一次）。
 *   刻意不用「与上次刷新比」——那样逐次 1℃ 的缓变永远攒不到阈值。
 * - **大风**：`windSpeed ≥ 39 km/h`（约 6 级 / 10.8 m/s），每天至多一次。
 * - **极端**：当日最高 ≥ 35℃ 或最低 ≤ −10℃，每天至多一次。
 *
 * 触发时机：由 `AppContainer.onRefreshSuccess` 在每次刷新成功后入队（与预警检查同路），
 * 不是周期任务——没有新数据就没有比较的意义。
 */
class WeatherChangeAlertWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? DailyWeatherApp ?: return Result.failure()
        val container = app.container
        val settings = container.settings

        val wantTemp = settings.tempChangeNotification.first()
        val wantWind = settings.windNotification.first()
        val wantExtreme = settings.extremeNotification.first()
        if (!wantTemp && !wantWind && !wantExtreme) return Result.success()

        // 复用「气象预警」渠道：同属需要立刻看到的高优先级提醒。
        if (!NotificationChannels.enabled(applicationContext, NotificationChannels.CHANNEL_URGENT)) {
            Log.i(TAG, "预警渠道不可用，跳过天气变化提醒")
            return Result.success()
        }

        val city = container.cityRepository.primaryCity()
            ?: return Result.success()
        val snap = container.weatherRepository.cached(city.id) ?: return Result.success()
        val rt = snap.realtime
        val today = java.time.LocalDate.now(city.zone).toString()

        val pending = mutableListOf<Triple<String, String, String>>() // (去重键, 标题, 正文)

        if (rt != null) {
            if (wantTemp) {
                val baseline = baselineTemp(city.id, today)
                if (baseline == null) {
                    setBaselineTemp(city.id, today, rt.temperature)
                } else {
                    val delta = rt.temperature - baseline
                    if (abs(delta) >= TEMP_DELTA_C) {
                        pending += Triple(
                            "${city.id}|temp|$today",
                            "${city.name} 气温变化",
                            "较今日早先${if (delta > 0) "升高" else "下降"} ${abs(delta).toInt()}°，" +
                                "当前 ${rt.temperature.toInt()}°",
                        )
                    }
                }
            }
            if (wantWind && rt.windSpeed >= WIND_ALERT_KMH) {
                pending += Triple(
                    "${city.id}|wind|$today",
                    "${city.name} 大风提醒",
                    "当前风力 ${rt.windSpeed.toInt()} km/h，注意防范",
                )
            }
        }

        if (wantExtreme) {
            val day = snap.daily.firstOrNull { it.date.take(10) == today } ?: snap.daily.firstOrNull()
            if (day != null) {
                when {
                    day.tempMax >= EXTREME_HOT_C -> pending += Triple(
                        "${city.id}|hot|$today",
                        "${city.name} 高温提醒",
                        "今日最高 ${day.tempMax.toInt()}°，注意防暑",
                    )
                    day.tempMin <= EXTREME_COLD_C -> pending += Triple(
                        "${city.id}|cold|$today",
                        "${city.name} 低温提醒",
                        "今日最低 ${day.tempMin.toInt()}°，注意保暖",
                    )
                }
            }
        }

        if (pending.isEmpty()) return Result.success()

        val seen = ChangeAlertState.seenKeys(applicationContext)
        val fresh = pending.filter { it.first !in seen }
        if (fresh.isEmpty()) return Result.success()

        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        var failed = 0
        fresh.take(MAX_PER_ROUND).forEachIndexed { i, (_, title, text) ->
            runCatching {
                nm.notify(
                    NOTIF_ID_BASE + i,
                    NotificationCompat.Builder(applicationContext, NotificationChannels.CHANNEL_URGENT)
                        .setSmallIcon(R.drawable.ic_widget_thermometer)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                        .setContentIntent(NotificationChannels.appIntent(applicationContext))
                        .setAutoCancel(true)
                        .build(),
                )
            }.onFailure {
                Log.w(TAG, "发送失败: ${it.message}")
                failed++
            }
        }
        if (failed == fresh.take(MAX_PER_ROUND).size) return Result.failure()

        ChangeAlertState.markSeen(applicationContext, pending.map { it.first }.toSet())
        Log.i(TAG, "天气变化提醒: fresh=${fresh.size}")
        return Result.success()
    }

    private suspend fun baselineTemp(cityId: String, day: String): Double? =
        ChangeAlertState.get(applicationContext, "base_$cityId")?.let { raw ->
            val parts = raw.split("|")
            if (parts.size == 2 && parts[0] == day) parts[1].toDoubleOrNull() else null
        }

    private suspend fun setBaselineTemp(cityId: String, day: String, temp: Double) =
        ChangeAlertState.put(applicationContext, "base_$cityId", "$day|$temp")

    companion object {
        private const val TAG = "WeatherChangeAlert"
        private const val NOTIF_ID_BASE = 1010
        private const val MAX_PER_ROUND = 2

        /** 气温骤变阈值（℃）：当日首次读数为基线，偏差超过它才提醒。 */
        const val TEMP_DELTA_C = 5.0

        /** 大风阈值（km/h）：约 6 级（10.8 m/s）。 */
        const val WIND_ALERT_KMH = 39.0

        /** 极端高温（℃）：国标高温预警线。 */
        const val EXTREME_HOT_C = 35.0

        /** 极端低温（℃）。 */
        const val EXTREME_COLD_C = -10.0
    }
}

/** 天气变化提醒的去重账本 + 当日温度基线（独立 DataStore，避免与预警账本互相影响）。 */
private object ChangeAlertState {

    private const val MAX_KEYS = 120
    private val KEY_SEEN = stringSetPreferencesKey("seen_keys")

    suspend fun seenKeys(context: Context): Set<String> =
        context.changeAlertStore.data.first()[KEY_SEEN] ?: emptySet()

    suspend fun markSeen(context: Context, keys: Set<String>) {
        context.changeAlertStore.edit { p ->
            // 与 AlertDedup 同口径：LinkedHashSet 保插入序，超限只淘汰最旧的。
            val merged = LinkedHashSet<String>(p[KEY_SEEN] ?: emptySet())
            merged.addAll(keys)
            p[KEY_SEEN] = if (merged.size > MAX_KEYS) {
                merged.toList().takeLast(MAX_KEYS).toSet()
            } else {
                merged
            }
        }
    }

    suspend fun get(context: Context, key: String): String? =
        context.changeAlertStore.data.first()[stringPreferencesKey(key)]

    suspend fun put(context: Context, key: String, value: String) {
        context.changeAlertStore.edit { it[stringPreferencesKey(key)] = value }
    }
}
