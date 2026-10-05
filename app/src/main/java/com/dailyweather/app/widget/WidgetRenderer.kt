package com.dailyweather.app.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import android.widget.RemoteViews
import com.dailyweather.app.MainActivity
import com.dailyweather.app.R
import com.dailyweather.app.data.City
import com.dailyweather.app.data.model.WeatherSnapshot
import com.dailyweather.app.ui.components.AssetBitmaps
import com.dailyweather.app.ui.components.SkyconMap
import com.dailyweather.app.util.Lunar
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 组件渲染器（对齐南风 WidgetSyncWorker 的渲染半区）：
 * - 按快照+城市填充 RemoteViews；
 * - 背景为按昼夜生成的渐变位图；
 * - 主题切换 weather_blue 图标套系。
 */
object WidgetRenderer {

    fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** 昼夜渐变背景位图（远端组件不支持 Brush，用位图）。 */
    fun backgroundBitmap(isDay: Boolean, w: Int = 480, h: Int = 480): Bitmap {
        val top = if (isDay) Color.rgb(0x2E, 0x7B, 0xD6) else Color.rgb(0x0B, 0x17, 0x33)
        val bottom = if (isDay) Color.rgb(0x9C, 0xC6, 0xF0) else Color.rgb(0x1D, 0x31, 0x58)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        return bmp
    }

    private fun icon(context: Context, skycon: String, blue: Boolean): Bitmap? =
        AssetBitmaps.weatherIcon(context, SkyconMap.asset(skycon), blue)

    private fun dayLabel(date: String): String = runCatching {
        val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date) ?: return@runCatching "—"
        when {
            SimpleDateFormat("yyyyMMdd", Locale.US).format(d) ==
                SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) -> "今天"
            else -> SimpleDateFormat("EEE", Locale.CHINA).format(d)
        }
    }.getOrDefault("—")

    fun small(context: Context, city: City?, snap: WeatherSnapshot?, blue: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_small)
        views.setImageViewBitmap(R.id.widget_bg, backgroundBitmap(isDayNow()))
        views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
        // 定位占位态：定位城市无快照视为定位中。
        if (snap == null) {
            val isLocating = city?.isCurrentLocation == true
            views.setTextViewText(R.id.widget_temp, if (isLocating) "··°" else "--°")
            views.setTextViewText(R.id.widget_desc, if (isLocating) "定位中..." else "等待数据")
            return views
        }
        views.setTextViewText(R.id.widget_temp, "${snap.currentTemp.toInt()}°")
        views.setTextViewText(R.id.widget_desc, SkyconMap.desc(snap.currentSkycon))
        icon(context, snap.currentSkycon, blue)?.let { views.setImageViewBitmap(R.id.widget_icon, it) }
        snap.hourly.take(3).forEachIndexed { idx, p ->
            when (idx) {
                0 -> {
                    views.setTextViewText(R.id.widget_hour_label_1, hourLabel(p.time))
                    views.setTextViewText(R.id.widget_hour_temp_1, "${p.temperature.toInt()}°")
                    icon(context, p.skycon, blue)?.let { views.setImageViewBitmap(R.id.widget_hour_icon_1, it) }
                }
                1 -> {
                    views.setTextViewText(R.id.widget_hour_label_2, hourLabel(p.time))
                    views.setTextViewText(R.id.widget_hour_temp_2, "${p.temperature.toInt()}°")
                    icon(context, p.skycon, blue)?.let { views.setImageViewBitmap(R.id.widget_hour_icon_2, it) }
                }
                2 -> {
                    views.setTextViewText(R.id.widget_hour_label_3, hourLabel(p.time))
                    views.setTextViewText(R.id.widget_hour_temp_3, "${p.temperature.toInt()}°")
                    icon(context, p.skycon, blue)?.let { views.setImageViewBitmap(R.id.widget_hour_icon_3, it) }
                }
            }
        }
        return views
    }

    /** AQI 组件（南风 widget_aqi 双图法：数值/等级全部渲染进位图，布局只有两个 ImageView）。
     *  前景位图：半透明深色底 + 左侧数值大字/等级 + 右侧五段光谱（绿→黄→橙→红→紫）+ 白点指针。 */
    fun aqi(context: Context, city: City?, snap: WeatherSnapshot?, blue: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_aqi)
        views.setImageViewBitmap(R.id.widget_aqi_bg, backgroundBitmap(isDayNow()))
        views.setOnClickPendingIntent(R.id.widget_aqi_root, openAppIntent(context))
        views.setImageViewBitmap(R.id.widget_aqi_spectrum, aqiSpectrumBitmap(snap?.realtime?.aqi ?: 0))
        return views
    }

    /** AQI 光谱前景位图。aqi=0 时画占位（数值 "—"）。 */
    fun aqiSpectrumBitmap(aqi: Int): Bitmap {
        val w = 420; val h = 120
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(Color.argb(110, 0, 0, 0))

        val sx = 156f; val sw = w - sx - 24f; val sy = 56f; val sh = 18f
        val bands = intArrayOf(
            0xFF4CD964.toInt(), 0xFFFFCC00.toInt(), 0xFFFF9500.toInt(),
            0xFFFF3B30.toInt(), 0xFFAF52DE.toInt(),
        )
        val seg = sw / bands.size
        for (i in bands.indices) {
            paint.color = bands[i]
            canvas.drawRoundRect(sx + i * seg, sy, sx + (i + 1) * seg - 2f, sy + sh, 8f, 8f, paint)
        }

        // 指针：AQI 0-300 线性映射到光谱条。
        val t = (aqi.coerceIn(0, 300)) / 300f
        paint.color = Color.WHITE
        canvas.drawCircle(sx + t * sw, sy + sh / 2f, 9f, paint)

        paint.color = Color.WHITE
        if (aqi > 0) {
            paint.textSize = 46f
            paint.isFakeBoldText = true
            canvas.drawText("$aqi", 22f, 58f, paint)
            paint.textSize = 18f
            paint.isFakeBoldText = false
            canvas.drawText(aqiLevel(aqi), 25f, 90f, paint)
        } else {
            paint.textSize = 22f
            canvas.drawText("AQI —", 30f, 74f, paint)
        }
        return bmp
    }

    private fun aqiLevel(aqi: Int): String = when {
        aqi <= 50 -> "优"
        aqi <= 100 -> "良"
        aqi <= 150 -> "轻度污染"
        aqi <= 200 -> "中度污染"
        aqi <= 300 -> "重度污染"
        else -> "严重污染"
    }

    fun twoByOne(context: Context, city: City?, snap: WeatherSnapshot?, blue: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_2x1)
        views.setImageViewBitmap(R.id.widget_bg, backgroundBitmap(isDayNow()))
        views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
        if (snap == null) {
            views.setTextViewText(R.id.widget_2x1_temp, "--°")
        } else {
            views.setTextViewText(R.id.widget_2x1_temp, "${snap.currentTemp.toInt()}° ${SkyconMap.desc(snap.currentSkycon)}")
            icon(context, snap.currentSkycon, blue)?.let { views.setImageViewBitmap(R.id.widget_2x1_icon, it) }
        }
        return views
    }

    fun fourByTwo(context: Context, city: City?, snap: WeatherSnapshot?, blue: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_4x2)
        views.setImageViewBitmap(R.id.widget_4x2_bg, backgroundBitmap(isDayNow()))
        views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
        views.setTextViewText(R.id.widget_city, city?.name ?: "未定位")
        if (snap == null) {
            views.setTextViewText(R.id.widget_weather_desc, "等待数据")
            return views
        }
        views.setTextViewText(R.id.widget_weather_desc, SkyconMap.desc(snap.currentSkycon))
        views.setTextViewText(
            R.id.widget_temp_range,
            "${snap.daily.firstOrNull()?.tempMin?.toInt() ?: 0}° / ${snap.daily.firstOrNull()?.tempMax?.toInt() ?: 0}°",
        )
        icon(context, snap.currentSkycon, blue)?.let { views.setImageViewBitmap(R.id.widget_weather_icon, it) }
        snap.daily.take(3).forEachIndexed { i, d ->
            val idLabel = when (i) {
                0 -> R.id.widget_day_label_1
                1 -> R.id.widget_day_label_2
                else -> R.id.widget_day_label_3
            }
            val idIcon = when (i) {
                0 -> R.id.widget_day_icon_1
                1 -> R.id.widget_day_icon_2
                else -> R.id.widget_day_icon_3
            }
            val idRange = when (i) {
                0 -> R.id.widget_day_range_1
                1 -> R.id.widget_day_range_2
                else -> R.id.widget_day_range_3
            }
            views.setTextViewText(idLabel, dayLabel(d.date))
            views.setTextViewText(idRange, "${d.tempMin.toInt()}/${d.tempMax.toInt()}°")
            icon(context, d.skycon, blue)?.let { views.setImageViewBitmap(idIcon, it) }
        }
        return views
    }

    fun medium(context: Context, city: City?, snap: WeatherSnapshot?, blue: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_medium)
        views.setImageViewBitmap(R.id.widget_bg, backgroundBitmap(isDayNow()))
        views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
        views.setTextViewText(R.id.widget_city, city?.name ?: "未定位")
        if (snap == null) {
            views.setTextViewText(R.id.widget_temp, "--°")
            return views
        }
        val rt = snap.realtime
        views.setTextViewText(R.id.widget_temp, "${snap.currentTemp.toInt()}°")
        views.setTextViewText(R.id.widget_desc, SkyconMap.desc(snap.currentSkycon))
        icon(context, snap.currentSkycon, blue)?.let { views.setImageViewBitmap(R.id.widget_weather_icon, it) }
        views.setTextViewText(R.id.widget_wind, rt?.let { "风 ${it.windSpeed.toInt()}km/h" } ?: "—")
        views.setTextViewText(R.id.widget_humidity, rt?.let { "湿 ${(it.humidity * 100).toInt()}%" } ?: "—")
        views.setTextViewText(R.id.widget_aqi, if (rt != null && rt.aqi > 0) "AQI ${rt.aqi}" else "—")
        views.setTextViewText(R.id.widget_uv, if (rt != null && rt.uvIndex > 0) "UV ${rt.uvIndex.toInt()}" else "—")
        return views
    }

    fun ios(context: Context, city: City?, snap: WeatherSnapshot?, blue: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_ios)
        views.setImageViewBitmap(R.id.widget_bg, backgroundBitmap(isDayNow()))
        views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
        views.setTextViewText(R.id.widget_ios_city, city?.name ?: "未定位")
        if (snap == null) {
            views.setTextViewText(R.id.widget_ios_temp, "--°")
            return views
        }
        views.setTextViewText(R.id.widget_ios_temp, "${snap.currentTemp.toInt()}°")
        val d0 = snap.daily.firstOrNull()
        views.setTextViewText(
            R.id.widget_ios_summary,
            listOfNotNull(
                SkyconMap.desc(snap.currentSkycon),
                d0?.let { "最高 ${it.tempMax.toInt()}° 最低 ${it.tempMin.toInt()}°" },
                rtWind(snap),
            ).joinToString("\n"),
        )
        val ids = listOf(
            Triple(R.id.widget_ios_day_name_1, R.id.widget_ios_day_icon_1, R.id.widget_ios_day_min_1 to R.id.widget_ios_day_max_1),
            Triple(R.id.widget_ios_day_name_2, R.id.widget_ios_day_icon_2, R.id.widget_ios_day_min_2 to R.id.widget_ios_day_max_2),
            Triple(R.id.widget_ios_day_name_3, R.id.widget_ios_day_icon_3, R.id.widget_ios_day_min_3 to R.id.widget_ios_day_max_3),
            Triple(R.id.widget_ios_day_name_4, R.id.widget_ios_day_icon_4, R.id.widget_ios_day_min_4 to R.id.widget_ios_day_max_4),
            Triple(R.id.widget_ios_day_name_5, R.id.widget_ios_day_icon_5, R.id.widget_ios_day_min_5 to R.id.widget_ios_day_max_5),
        )
        snap.daily.take(5).forEachIndexed { i, d ->
            val (nameId, iconId, minMaxId) = ids[i]
            views.setTextViewText(nameId, dayLabel(d.date).take(2))
            icon(context, d.skycon, blue)?.let { views.setImageViewBitmap(iconId, it) }
            views.setTextViewText(minMaxId.first, "${d.tempMin.toInt()}°")
            views.setTextViewText(minMaxId.second, "${d.tempMax.toInt()}°")
        }
        return views
    }

    fun hourly(context: Context, city: City?, snap: WeatherSnapshot?, blue: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_hourly)
        views.setImageViewBitmap(R.id.widget_bg, backgroundBitmap(isDayNow()))
        views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
        views.setTextViewText(R.id.widget_hourly_city, city?.name ?: "未定位")
        if (snap == null) {
            views.setTextViewText(R.id.widget_hourly_temp, "--°")
            return views
        }
        views.setTextViewText(R.id.widget_hourly_temp, "${snap.currentTemp.toInt()}°")
        views.setTextViewText(R.id.widget_hourly_desc, SkyconMap.desc(snap.currentSkycon))
        icon(context, snap.currentSkycon, blue)?.let { views.setImageViewBitmap(R.id.widget_hourly_weather_icon, it) }
        val d0 = snap.daily.firstOrNull()
        views.setTextViewText(R.id.widget_hourly_hi, "${d0?.tempMax?.toInt() ?: 0}°")
        views.setTextViewText(R.id.widget_hourly_lo, "${d0?.tempMin?.toInt() ?: 0}°")
        snap.hourly.take(4).forEachIndexed { i, p ->
            val labelId = listOf(R.id.widget_hourly_hour_1, R.id.widget_hourly_hour_2, R.id.widget_hourly_hour_3, R.id.widget_hourly_hour_4)[i]
            val iconId = listOf(R.id.widget_hourly_icon_1, R.id.widget_hourly_icon_2, R.id.widget_hourly_icon_3, R.id.widget_hourly_icon_4)[i]
            val tempId = listOf(R.id.widget_hourly_temp_1, R.id.widget_hourly_temp_2, R.id.widget_hourly_temp_3, R.id.widget_hourly_temp_4)[i]
            views.setTextViewText(labelId, hourLabel(p.time))
            views.setTextViewText(tempId, "${p.temperature.toInt()}°")
            icon(context, p.skycon, blue)?.let { views.setImageViewBitmap(iconId, it) }
        }
        return views
    }

    fun week(context: Context, city: City?, snap: WeatherSnapshot?, blue: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_week)
        views.setImageViewBitmap(R.id.widget_week_bg, backgroundBitmap(isDayNow()))
        views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
        // 顶部农历（今天）
        val lunarToday = Lunar.lunarText(Calendar.getInstance()) ?: ""
        views.setTextViewText(R.id.widget_week_lunar, lunarToday)
        val days = snap?.daily.orEmpty().take(7)
        days.forEachIndexed { i, d ->
            val dayId = when (i) {
                0 -> R.id.widget_week_day_1; 1 -> R.id.widget_week_day_2; 2 -> R.id.widget_week_day_3
                3 -> R.id.widget_week_day_4; 4 -> R.id.widget_week_day_5; 5 -> R.id.widget_week_day_6
                else -> R.id.widget_week_day_7
            }
            val labelId = when (i) {
                0 -> R.id.widget_week_label_1; 1 -> R.id.widget_week_label_2; 2 -> R.id.widget_week_label_3
                3 -> R.id.widget_week_label_4; 4 -> R.id.widget_week_label_5; 5 -> R.id.widget_week_label_6
                else -> R.id.widget_week_label_7
            }
            // 逐日农历
            val lunarDayId = when (i) {
                0 -> R.id.widget_week_lunar_1; 1 -> R.id.widget_week_lunar_2; 2 -> R.id.widget_week_lunar_3
                3 -> R.id.widget_week_lunar_4; 4 -> R.id.widget_week_lunar_5; 5 -> R.id.widget_week_lunar_6
                else -> R.id.widget_week_lunar_7
            }
            views.setTextViewText(dayId, dayLabel(d.date))
            views.setTextViewText(labelId, "${d.tempMin.toInt()}/${d.tempMax.toInt()}°")
            // 解析日期并计算农历
            val solarDate = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(d.date) }.getOrNull()
            if (solarDate != null) {
                val cal = Calendar.getInstance()
                cal.time = solarDate
                val lunar = Lunar.lunarText(cal)
                views.setTextViewText(lunarDayId, lunar ?: "")
            } else {
                views.setTextViewText(lunarDayId, "")
            }
        }
        return views
    }

    private fun rtWind(snap: WeatherSnapshot): String? =
        snap.realtime?.let { "风 ${it.windSpeed.toInt()}km/h" }

    private fun hourLabel(epochSeconds: Long): String =
        SimpleDateFormat("H时", Locale.CHINA).format(Date(epochSeconds * 1000))

    private fun isDayNow(): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return h in 6 until 19
    }
}
