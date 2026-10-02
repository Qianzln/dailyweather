package com.dailyweather.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/** 7 个规格共用一个 Provider 基类（对齐南风 widget 包的 7 个 Provider 注册面）。 */
open class BaseWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetSyncWorker.enqueueOneTime(context, "widget_update")
    }

    override fun onEnabled(context: Context) {
        WidgetScheduler.onFirstInstance(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        WidgetSyncWorker.cancelIfNoInstances(context)
    }
}

class WeatherWidgetProvider : BaseWidgetProvider()

class WeatherWidget2x1Provider : BaseWidgetProvider()

class WeatherWidget4x2Provider : BaseWidgetProvider()

class WeatherWidgetMediumProvider : BaseWidgetProvider()

class WeatherWidgetIOSProvider : BaseWidgetProvider()

class WeatherWidgetHourlyProvider : BaseWidgetProvider()

class WeatherWidgetWeekProvider : BaseWidgetProvider()
