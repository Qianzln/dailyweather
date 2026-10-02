package com.dailyweather.app.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dailyweather.app.DailyWeatherApp
import com.dailyweather.app.data.model.WeatherSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 组件同步/渲染 Worker（对齐南风 `WidgetSyncWorker` + `WidgetScheduler`）：
 * - onetime：放置组件/数据更新后的即时渲染；
 * - periodic：30 分钟兜底刷新（同步模式：可带网络刷新；渲染模式：只重绘）；
 * - 无活跃实例时取消共享任务（cancelIfNoInstances）。
 */
class WidgetSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as? DailyWeatherApp ?: return@withContext Result.failure()
        val container = app.container
        val renderOnly = inputData.getBoolean(KEY_RENDER_ONLY, false)
        Log.i(TAG, "doWork: [步骤1] 读取城市与缓存, renderOnly=$renderOnly")

        val cities = container.cityRepository.cities.first()
        if (cities.isEmpty()) {
            Log.i(TAG, "doWork: 无任何活跃城市，直接渲染空态")
        }

        // 主刷新：当前定位城市或第一个城市（渲染模式跳过网络）
        if (!renderOnly) {
            val primary = cities.firstOrNull { it.isCurrentLocation } ?: cities.firstOrNull()
            if (primary != null) {
                Log.i(TAG, "doWork: [步骤3] 请求后台同步, reason=${inputData.getString(KEY_REASON) ?: "periodic"}")
                container.refreshManager.refresh(primary, "widget_sync")
            }
        }

        renderAll(applicationContext)
        Log.i(TAG, "doWork: 渲染完成")
        Result.success()
    }

    companion object {
        const val TAG = "WidgetSyncWorker"
        const val KEY_RENDER_ONLY = "render_only"
        const val KEY_REASON = "reason"

        /** 所有已放置实例的渲染（对齐南风 updateAll）。 */
        suspend fun renderAll(context: Context) {
            val app = context as? DailyWeatherApp ?: (context.applicationContext as DailyWeatherApp)
            val container = app.container
            val blue = container.settings.weatherBlueIcons.first()
            val cities = container.cityRepository.cities.first()
            val primary = cities.firstOrNull { it.isCurrentLocation } ?: cities.firstOrNull()
            val snap: WeatherSnapshot? = primary?.let { container.weatherRepository.cached(it.id) }

            val providers = listOf(
                WeatherWidgetProvider::class.java,
                WeatherWidget2x1Provider::class.java,
                WeatherWidget4x2Provider::class.java,
                WeatherWidgetMediumProvider::class.java,
                WeatherWidgetIOSProvider::class.java,
                WeatherWidgetHourlyProvider::class.java,
                WeatherWidgetWeekProvider::class.java,
            )
            val awm = AppWidgetManager.getInstance(context)
            providers.forEach { cls ->
                val ids = awm.getAppWidgetIds(ComponentName(context, cls))
                ids.forEach { id -> WidgetScheduler.renderOne(context, cls, id, primary, snap, blue) }
            }
        }

        fun enqueueOneTime(context: Context, reason: String, renderOnly: Boolean = false) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "weather_widget_onetime",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<WidgetSyncWorker>()
                    .setInputData(workDataOf(KEY_RENDER_ONLY to renderOnly, KEY_REASON to reason))
                    .build(),
            )
        }

        fun enqueuePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "weather_widget_periodic",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WidgetSyncWorker>(30, TimeUnit.MINUTES).build(),
            )
        }

        fun cancelIfNoInstances(context: Context) {
            val awm = AppWidgetManager.getInstance(context)
            val anyActive = listOf(
                WeatherWidgetProvider::class.java, WeatherWidget2x1Provider::class.java,
                WeatherWidget4x2Provider::class.java, WeatherWidgetMediumProvider::class.java,
                WeatherWidgetIOSProvider::class.java, WeatherWidgetHourlyProvider::class.java,
                WeatherWidgetWeekProvider::class.java,
            ).any { cls -> awm.getAppWidgetIds(ComponentName(context, cls)).isNotEmpty() }
            if (!anyActive) {
                Log.i(TAG, "cancelIfNoInstances: 无任何活跃 widget，已取消共享任务")
                WorkManager.getInstance(context).cancelUniqueWork("weather_widget_periodic")
            }
        }
    }
}

/** 任务调度器（对齐南风 WidgetScheduler 的轻量面）。 */
object WidgetScheduler {

    fun renderOne(
        context: Context,
        cls: Class<out BroadcastReceiver>,
        appWidgetId: Int,
        city: com.dailyweather.app.data.City?,
        snap: WeatherSnapshot?,
        blue: Boolean,
    ) {
        val awm = AppWidgetManager.getInstance(context)
        val views = when (cls.simpleName) {
            "WeatherWidget2x1Provider" -> WidgetRenderer.twoByOne(context, city, snap, blue)
            "WeatherWidget4x2Provider" -> WidgetRenderer.fourByTwo(context, city, snap, blue)
            "WeatherWidgetMediumProvider" -> WidgetRenderer.medium(context, city, snap, blue)
            "WeatherWidgetIOSProvider" -> WidgetRenderer.ios(context, city, snap, blue)
            "WeatherWidgetHourlyProvider" -> WidgetRenderer.hourly(context, city, snap, blue)
            "WeatherWidgetWeekProvider" -> WidgetRenderer.week(context, city, snap, blue)
            else -> WidgetRenderer.small(context, city, snap, blue)
        }
        runCatching { awm.updateAppWidget(appWidgetId, views) }
            .onFailure { Log.w(WidgetSyncWorker.TAG, "updateAll: 单实例渲染失败 widgetId=$appWidgetId, ${it.message}") }
    }

    /** 首个实例放置 → 建周期任务；最后实例移除 → 清理（对齐 onEnabled/onDisabled 生命周期）。 */
    fun onFirstInstance(context: Context) {
        Log.i(WidgetSyncWorker.TAG, "onEnabled: 首个小组件实例被放置")
        WidgetSyncWorker.enqueuePeriodic(context)
        WidgetSyncWorker.enqueueOneTime(context, "widget_enabled")
    }
}
