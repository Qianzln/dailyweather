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
        // 1) 南风 v4.3.99 的 OPPO 场景图（64 预设 / 560 实体 / 725 KB JSON）——IO 线程解析一次。
        //    未就绪时渲染器保持上一帧场景，不会白屏。
        com.dailyweather.app.scene.oppo.OppoSceneRegistry.prewarm(this)
        // 4) 场景键映射表（组号 → 时段 → 键，来自南风 w7/p0 的静态表 + w7/z0 的时段算法）。
        com.dailyweather.app.scene.oppo.OppoSceneKey.prewarm(this)
        scheduleBackgroundWork()
        syncUnits()
        Log.i(TAG, "DailyWeatherApp ready")
    }

    /** WorkManager 任务网格（对齐南风：widget_periodic / notification_periodic）。
     *  全部带「有网才跑」约束——离线时任务挂起而不是空跑一轮。
     *  Widget 周期任务移到 [WidgetSyncWorker.onFirstInstance] 里按需注册，
     *  无桌面组件时不白耗电。 */
    private fun scheduleBackgroundWork() {
        val wm = WorkManager.getInstance(this)
        DailyWeatherNotificationWorker.schedule(this)
        wm.enqueueUniquePeriodicWork(
            "weather_notification_periodic",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PersistentWeatherWorker>(30, TimeUnit.MINUTES)
                .setConstraints(com.dailyweather.app.notification.networkConstraints())
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    com.dailyweather.app.notification.RETRY_BACKOFF_SECONDS,
                    TimeUnit.SECONDS,
                )
                .build(),
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
