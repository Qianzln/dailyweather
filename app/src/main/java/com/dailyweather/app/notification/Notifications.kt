package com.dailyweather.app.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dailyweather.app.DailyWeatherApp
import com.dailyweather.app.MainActivity
import com.dailyweather.app.R
import com.dailyweather.app.ui.components.SkyconMap
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** 通知渠道与权限守门（对齐南风 notification 包的渠道语义）。 */
object NotificationChannels {

    const val CHANNEL_DAILY = "daily_forecast"
    const val CHANNEL_URGENT = "weather_alerts"
    const val CHANNEL_LIVE = "weather_live"

    fun ensureAll(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DAILY, "每日天气", NotificationManager.IMPORTANCE_DEFAULT),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_URGENT, "气象预警", NotificationManager.IMPORTANCE_HIGH),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_LIVE, "实时天气胶囊", NotificationManager.IMPORTANCE_MIN),
        )
    }

    fun enabled(context: Context, channelId: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = nm.getNotificationChannel(channelId) ?: return false
            if (ch.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    fun appIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

/** 常驻天气胶囊：Android 16（API 36+）promoted-ongoing 升格卡，低版本回退普通常驻通知。 */
object PersistentWeatherNotification {

    const val NOTIFICATION_ID = 1001

    /** Android 16（Baklava）将常驻通知升格为 promoted ongoing（流体云卡片）。 */
    private const val FLAG_PROMOTED_ONGOING = 0x00020000 // Notification.FLAG_PROMOTED_ONGOING

    fun post(context: Context, title: String, text: String) {
        val builder = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_LIVE)
            .setSmallIcon(R.drawable.ic_widget_thermometer)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(NotificationChannels.appIntent(context))
            .setDeleteIntent(dismissIntent(context))
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Android 16 及以上且系统允许 promoted ongoing 时，给常驻胶囊加升格标记。
        // NotificationCompat.Builder.setFlag 私有，这里用反射给最终 Notification 补该位。
        val notification = builder.build()
        if (Build.VERSION.SDK_INT >= 36 && nm.canPostPromotedNotifications()) {
            runCatching {
                val f = android.app.Notification::class.java.getField("flags")
                f.setInt(notification, (f.getInt(notification) or FLAG_PROMOTED_ONGOING))
            }
        }
        nm.notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
    }

    /** 划除回调 → 6 小时内不再自动弹出（对齐南风 LiveUpdateDismissReceiver 语义）。 */
    private fun dismissIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 2,
            Intent(context, LiveUpdateDismissReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

class LiveUpdateDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as DailyWeatherApp
        kotlinx.coroutines.runBlocking {
            app.container.settings.setLiveUpdateSuppressedUntil(System.currentTimeMillis() + 6 * 3600_000L)
        }
    }
}

/** 每日天气通知（早/晚档由调度器决定）。 */
class DailyWeatherNotificationWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as DailyWeatherApp
        val container = app.container
        // 「早晚天气通知」开关真正生效：关闭即不再发这一档。
        val evening = inputData.getBoolean(KEY_EVENING, false)
        val enabled = if (evening) {
            container.settings.dailyEveningNotification.first()
        } else {
            container.settings.dailyMorningNotification.first()
        }
        Log.i(TAG, "doWork: 每日通知任务, evening=$evening, enabled=$enabled")
        if (!enabled) return Result.success()
        val primary = container.cityRepository.currentLocationCity()
            ?: container.cityRepository.cities.first().firstOrNull()
            ?: return Result.success()
        val snap = container.weatherRepository.cached(primary.id)
            ?: return Result.success()
        if (!NotificationChannels.enabled(applicationContext, NotificationChannels.CHANNEL_DAILY)) {
            Log.i(TAG, "doWork: 通知渠道已禁用，跳过")
            return Result.success()
        }
        val title = "${primary.name} · ${snap.currentTemp.toInt()}° ${SkyconMap.desc(snap.currentSkycon)}"
        val text = snap.daily.firstOrNull()?.let {
            "今日 ${it.tempMin.toInt()}~${it.tempMax.toInt()}°，${snap.forecastKey.take(40)}"
        } ?: snap.forecastKey
        val notification = NotificationCompat.Builder(applicationContext, NotificationChannels.CHANNEL_DAILY)
            .setSmallIcon(R.drawable.ic_widget_thermometer)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(NotificationChannels.appIntent(applicationContext))
            .build()
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(if (evening) 1003 else 1002, notification)
        return Result.success()
    }

    companion object {
        const val TAG = "DailyNotifWorker"
        const val KEY_EVENING = "evening"

        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            val morning = PeriodicWorkRequestBuilder<DailyWeatherNotificationWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(initialDelayTo(7, 30), TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_EVENING to false))
                .build()
            val evening = PeriodicWorkRequestBuilder<DailyWeatherNotificationWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(initialDelayTo(19, 30), TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_EVENING to true))
                .build()
            wm.enqueueUniquePeriodicWork("daily_weather_morning", ExistingPeriodicWorkPolicy.KEEP, morning)
            wm.enqueueUniquePeriodicWork("daily_weather_evening", ExistingPeriodicWorkPolicy.KEEP, evening)
        }

        private fun initialDelayTo(hour: Int, minute: Int): Long {
            val now = java.util.Calendar.getInstance()
            val target = (now.clone() as java.util.Calendar).apply {
                set(java.util.Calendar.HOUR_OF_DAY, hour)
                set(java.util.Calendar.MINUTE, minute)
                set(java.util.Calendar.SECOND, 0)
                if (before(now)) add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
            return ((target.timeInMillis - now.timeInMillis) / 60000L).coerceAtLeast(1)
        }
    }
}

/** 预警通知（按 城市+标题+发布时间 去重：同一条预警只推一次）。 */
class UrgentNotificationWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as DailyWeatherApp
        val container = app.container
        // 「气象预警推送」开关真正生效：关闭即整轮跳过。
        if (!container.settings.urgentNotification.first()) return Result.success()
        val primary = inputData.getString(KEY_CITY_ID)?.let { container.cityRepository.byId(it) }
            ?: container.cityRepository.currentLocationCity()
            ?: container.cityRepository.cities.first().firstOrNull()
            ?: return Result.success()
        val snap = container.weatherRepository.cached(primary.id)
        val alerts = snap?.alerts.orEmpty()
            // 已取消/解除的预警不推；来源没给状态的（旧缓存）照推。
            .filter { a -> a.title.isNotBlank() && !a.status.contains("cancel", true) && "取消" !in a.status }
        if (alerts.isEmpty() || !NotificationChannels.enabled(applicationContext, NotificationChannels.CHANNEL_URGENT)) {
            return Result.success()
        }
        val seen = AlertDedup.seenKeys(applicationContext)
        val fresh = alerts.filter { alertKey(primary.id, it) !in seen }
        if (fresh.isEmpty()) return Result.success()
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        fresh.take(MAX_PER_ROUND).forEachIndexed { i, a ->
            nm.notify(
                NOTIF_ID_BASE + i,
                NotificationCompat.Builder(applicationContext, NotificationChannels.CHANNEL_URGENT)
                    .setSmallIcon(R.drawable.ic_widget_thermometer)
                    .setContentTitle(a.title.ifBlank { "气象预警" })
                    .setContentText(a.description.take(80))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(a.description))
                    .setContentIntent(NotificationChannels.appIntent(applicationContext))
                    .setAutoCancel(true)
                    .build(),
            )
        }
        // 当前全量都记为已见：漏掉的老预警不再补推，只推真正新增的。
        AlertDedup.markSeen(applicationContext, alerts.map { alertKey(primary.id, it) }.toSet())
        Log.i(TAG, "预警推送: fresh=${fresh.size} 共${alerts.size}")
        return Result.success()
    }

    private fun alertKey(cityId: String, a: com.dailyweather.app.data.model.WeatherSnapshot.AlertItem): String =
        "$cityId|${a.title}|${a.pubTime}"

    companion object {
        const val KEY_CITY_ID = "cityId"
        const val NOTIF_ID_BASE = 1004
        const val MAX_PER_ROUND = 3
        private const val TAG = "UrgentAlert"
    }
}

/** 预警去重账本：DataStore 里记已推过的 城市|标题|发布时间 集合。 */
object AlertDedup {

    private const val MAX_KEYS = 100
    private val KEY_SEEN = stringSetPreferencesKey("seen_keys")

    private val Context.alertDedupStore by preferencesDataStore(name = "alert_dedup")

    suspend fun seenKeys(context: Context): Set<String> =
        context.alertDedupStore.data.first()[KEY_SEEN] ?: emptySet()

    suspend fun markSeen(context: Context, keys: Set<String>) {
        context.alertDedupStore.edit { p ->
            val merged = (p[KEY_SEEN] ?: emptySet()) + keys
            p[KEY_SEEN] = if (merged.size > MAX_KEYS) merged.drop(merged.size - MAX_KEYS).toSet() else merged
        }
    }
}

/** 开关关闭时立即撤销已展示的早晚档与预警通知（常驻胶囊另走 PersistentWeatherNotification.cancel）。 */
fun cancelDailyAndUrgent(context: Context) {
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.cancel(1002) // 早档
    nm.cancel(1003) // 晚档
    for (i in 0 until UrgentNotificationWorker.MAX_PER_ROUND) {
        nm.cancel(UrgentNotificationWorker.NOTIF_ID_BASE + i)
    }
}

/** 常驻胶囊观察 Worker：同步当前天气到通知（阶段一每 30 分钟；实时化在阶段二）。 */
class PersistentWeatherWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as DailyWeatherApp
        val container = app.container
        val settings = container.settings
        // 「常驻天气」开关真正生效：关闭即撤销常驻胶囊并跳过本轮。
        val enabled = settings.persistentNotification.first()
        if (!enabled) {
            PersistentWeatherNotification.cancel(applicationContext)
            return Result.success()
        }
        // 划除胶囊后 6h 静默窗口内不自动再弹。
        if (settings.liveUpdateSuppressedUntil() > System.currentTimeMillis()) return Result.success()
        val primary = container.cityRepository.currentLocationCity()
            ?: container.cityRepository.cities.first().firstOrNull()
            ?: return Result.success()
        val snap = container.weatherRepository.cached(primary.id)
        if (snap != null) {
            PersistentWeatherNotification.post(
                applicationContext,
                "${primary.name} ${snap.currentTemp.toInt()}°",
                "${SkyconMap.desc(snap.currentSkycon)} · ${snap.forecastKey.take(30)}",
            )
        }
        return Result.success()
    }
}
