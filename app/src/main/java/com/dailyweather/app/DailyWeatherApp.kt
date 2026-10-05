package com.dailyweather.app

import android.app.Application
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.launch
import com.dailyweather.app.data.Units
import com.dailyweather.app.di.AppContainer
import com.dailyweather.app.notification.DailyWeatherNotificationWorker
import com.dailyweather.app.notification.NotificationChannels
import com.dailyweather.app.notification.PersistentWeatherWorker
import com.dailyweather.app.widget.WidgetSyncWorker
import java.util.concurrent.TimeUnit

/** 应用入口：容器初始化 + 通知渠道 + 后台任务网格。 */
class DailyWeatherApp : Application() {

    private val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate,
    )

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        NotificationChannels.ensureAll(this)
        // 冷启动预热（对齐南风 SkyPulseApp 的"天气资源预热"）：
        // 1) 程序化粒子（辉光/雨滴流星/24 帧雪花）——后台线程生成位图；
        // 2) 南风 PNG 精灵（11 张天空帧 + 8 张云精灵）——IO 线程解码进 LruCache。
        // 首帧组合时缓存已热，主线程只做 cache.get，动画零卡顿进入。
        com.dailyweather.app.scene.ParticleSprites.prewarm(this)
        com.dailyweather.app.scene.SpriteAssets.prewarm(this)
        scheduleBackgroundWork()
        syncUnits()
        Log.i(TAG, "DailyWeatherApp ready")
    }

    /** WorkManager 任务网格（对齐南风：widget_periodic / notification_periodic）。 */
    private fun scheduleBackgroundWork() {
        val wm = WorkManager.getInstance(this)
        WidgetSyncWorker.enqueuePeriodic(this)
        DailyWeatherNotificationWorker.schedule(this)
        wm.enqueueUniquePeriodicWork(
            "weather_notification_periodic",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PersistentWeatherWorker>(30, TimeUnit.MINUTES).build(),
        )
    }

    /** 单位设置 → [Units]。快照存的始终是上游原值，换算只在显示层发生一次。 */
    private fun syncUnits() {
        appScope.launch {
            container.settings.temperatureUnit.collect { Units.temperature = it }
        }
        appScope.launch { container.settings.windUnit.collect { Units.wind = it } }
        appScope.launch { container.settings.pressureUnit.collect { Units.pressure = it } }
        appScope.launch { container.settings.precipitationUnit.collect { Units.precipitation = it } }
    }

    companion object {
        private const val TAG = "DailyWeatherApp"
    }
}
