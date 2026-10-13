package com.dailyweather.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * 8 个规格共用一个 Provider 基类。
 *
 * 生命周期对齐南风 `widget/a.java` 的五个回调，一个不少：
 * - `onUpdate`          — 系统周期（`updatePeriodMillis`）/手动刷新 → 入队一次同步
 * - `onEnabled`         — 首个实例放置 → 建周期任务 + 立即渲染
 * - `onDisabled`        — 该类最后一个实例移除 → 清理
 * - `onDeleted`         — 单个实例删除 → 无活跃实例时取消共享任务
 * - `onReceive(BOOT)`   — 开机重启 → 重排周期任务（WorkManager 任务开机后不保证仍在）
 * - `onAppWidgetOptionsChanged` — 尺寸/位置变化 → 只重绘（不联网）
 *
 * 此前只有 `onUpdate`/`onEnabled`/`onDeleted` 三个，缺 BOOT 与 options 两个回调。
 */
open class BaseWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        Log.i(TAG, "onUpdate: 系统触发 widgetIds=${appWidgetIds.joinToString(",")}")
        WidgetSyncWorker.enqueueOneTime(context, "widget_update")
    }

    override fun onEnabled(context: Context) {
        Log.i(TAG, "onEnabled: 首个实例被放置")
        WidgetScheduler.onFirstInstance(context)
    }

    override fun onDisabled(context: Context) {
        Log.i(TAG, "onDisabled: 该类型最后一个实例被移除")
        WidgetSyncWorker.cancelIfNoInstances(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        Log.i(TAG, "onDeleted: 实例被删除 widgetIds=${appWidgetIds.joinToString(",")}")
        WidgetSyncWorker.cancelIfNoInstances(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        Log.i(TAG, "onAppWidgetOptionsChanged: 尺寸/位置变化 widgetId=$appWidgetId")
        // 只重绘：拉取尺寸相关的布局，不必再联网（数据由周期任务保证新鲜度）。
        WidgetSyncWorker.enqueueOneTime(context, "options_changed", renderOnly = true)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "onReceive: 收到 BOOT_COMPLETED，重排周期任务")
            // WorkManager 的周期任务在设备重启后可能已丢失，需要重新入队并渲染一次。
            WidgetScheduler.onFirstInstance(context)
            return
        }
        Log.d(TAG, "onReceive: 忽略非 BOOT 广播 action=${intent.action}")
    }

    private companion object {
        const val TAG = "BaseWidgetProvider"
    }
}

class WeatherWidgetProvider : BaseWidgetProvider()

class WeatherWidget2x1Provider : BaseWidgetProvider()

class WeatherWidget4x2Provider : BaseWidgetProvider()

class WeatherWidgetMediumProvider : BaseWidgetProvider()

class WeatherWidgetIOSProvider : BaseWidgetProvider()

class WeatherWidgetHourlyProvider : BaseWidgetProvider()

class WeatherWidgetWeekProvider : BaseWidgetProvider()

/** AQI 光谱组件（南风 widget_aqi，4×1）。 */
class WeatherWidgetAqiProvider : BaseWidgetProvider()
