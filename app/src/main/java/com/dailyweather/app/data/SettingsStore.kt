package com.dailyweather.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "daily_settings")

/** 设置项（对齐南风设置页的功能分组：通知 / 外观 / 单位 / 定位显示 / 插件 / 语音）。 */
class SettingsStore(private val context: Context) {

    private val ds = context.settingsDataStore

    // ---- 开关 ----

    val locationEnabled: Flow<Boolean> = bool(LOCATION_ENABLED, true)
    val allowBackgroundLocation: Flow<Boolean> = bool(LOCATION_BACKGROUND, false)
    val weatherBlueIcons: Flow<Boolean> = bool(WEATHER_BLUE, false)
    val weatherMotion: Flow<Boolean> = bool(WEATHER_MOTION, true)

    val dailyMorningNotification: Flow<Boolean> = bool(DAILY_MORNING, true)
    val dailyEveningNotification: Flow<Boolean> = bool(DAILY_EVENING, true)
    val urgentNotification: Flow<Boolean> = bool(URGENT, true)
    val persistentNotification: Flow<Boolean> = bool(PERSISTENT, false)
    val tempChangeNotification: Flow<Boolean> = bool(TEMP_CHANGE, false)
    val windNotification: Flow<Boolean> = bool(WIND_ALERT, false)
    val extremeNotification: Flow<Boolean> = bool(EXTREME_ALERT, true)
    val speechEnabled: Flow<Boolean> = bool(SPEECH_ENABLED, false)

    suspend fun setLocationEnabled(v: Boolean) = setBool(LOCATION_ENABLED, v)
    suspend fun setAllowBackgroundLocation(v: Boolean) = setBool(LOCATION_BACKGROUND, v)
    suspend fun setWeatherBlueIcons(v: Boolean) = setBool(WEATHER_BLUE, v)
    suspend fun setWeatherMotion(v: Boolean) = setBool(WEATHER_MOTION, v)
    suspend fun setDailyMorning(v: Boolean) = setBool(DAILY_MORNING, v)
    suspend fun setDailyEvening(v: Boolean) = setBool(DAILY_EVENING, v)
    suspend fun setUrgent(v: Boolean) = setBool(URGENT, v)
    suspend fun setPersistent(v: Boolean) = setBool(PERSISTENT, v)
    suspend fun setTempChange(v: Boolean) = setBool(TEMP_CHANGE, v)
    suspend fun setWind(v: Boolean) = setBool(WIND_ALERT, v)
    suspend fun setExtreme(v: Boolean) = setBool(EXTREME_ALERT, v)
    suspend fun setSpeechEnabled(v: Boolean) = setBool(SPEECH_ENABLED, v)

    // ---- 单位 ----

    val temperatureUnit: Flow<String> = string(TEMP_UNIT, "C")
    val windUnit: Flow<String> = string(WIND_UNIT, "KMH")
    val pressureUnit: Flow<String> = string(PRESSURE_UNIT, "HPA")
    val precipitationUnit: Flow<String> = string(PRECIP_UNIT, "MM")

    suspend fun setTemperatureUnit(v: String) = setString(TEMP_UNIT, v)
    suspend fun setWindUnit(v: String) = setString(WIND_UNIT, v)
    suspend fun setPressureUnit(v: String) = setString(PRESSURE_UNIT, v)
    suspend fun setPrecipitationUnit(v: String) = setString(PRECIP_UNIT, v)

    /** 通知划除后的静默截止时间（对齐南风「划掉流体云胶囊 6 小时内不再自动弹出」）。 */
    suspend fun setLiveUpdateSuppressedUntil(ts: Long) = ds.edit { it[LIVE_SUPPRESSED_UNTIL] = ts }
    suspend fun liveUpdateSuppressedUntil(): Long = ds.data.first()[LIVE_SUPPRESSED_UNTIL] ?: 0L

    // ---- 通用键访问：卡片显隐/排序/材质/颜色模式这类「键多而浅」的设置走这里 ----

    fun stringFlow(keyName: String, default: String): Flow<String> =
        ds.data.map { it[stringPreferencesKey(keyName)] ?: default }

    suspend fun setString(keyName: String, v: String) = ds.edit { it[stringPreferencesKey(keyName)] = v }

    fun boolFlow(keyName: String, default: Boolean): Flow<Boolean> =
        ds.data.map { it[booleanPreferencesKey(keyName)] ?: default }

    suspend fun setBool(keyName: String, v: Boolean) = ds.edit { it[booleanPreferencesKey(keyName)] = v }

    /** 主页可见卡片（按用户排序）。所有卡片默认全部可见，无会员限制。 */
    val homeCardsFlow: Flow<List<HomeCardId>> = ds.data.map { p ->
        val saved = p[stringPreferencesKey(SettingsKeys.CARD_ORDER)]
            ?.split(",")?.mapNotNull { runCatching { HomeCardId.valueOf(it) }.getOrNull() }
            .orEmpty()
        val ordered = (saved + HomeCards.defaultOrder).distinct()
        ordered.filter { id ->
            val key = when (id) {
                HomeCardId.AQI -> SettingsKeys.SHOW_AQI
                HomeCardId.HOURLY -> SettingsKeys.SHOW_HOURLY48
                HomeCardId.SUN -> SettingsKeys.SHOW_SUN
                HomeCardId.DETAIL -> SettingsKeys.SHOW_DETAIL
                HomeCardId.LIFE -> SettingsKeys.SHOW_LIFE
                else -> null
            } ?: return@filter true
            p[booleanPreferencesKey(key)] ?: HomeCards.defaultVisible(id)
        }
    }

    private fun bool(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>, default: Boolean) =
        ds.data.map { it[key] ?: default }

    private suspend fun setBool(
        key: androidx.datastore.preferences.core.Preferences.Key<Boolean>,
        v: Boolean,
    ) = ds.edit { it[key] = v }

    private fun string(key: androidx.datastore.preferences.core.Preferences.Key<String>, default: String) =
        ds.data.map { it[key] ?: default }

    private suspend fun setString(
        key: androidx.datastore.preferences.core.Preferences.Key<String>,
        v: String,
    ) = ds.edit { it[key] = v }

    private companion object {
        val LOCATION_ENABLED = booleanPreferencesKey("location_enabled")
        val LOCATION_BACKGROUND = booleanPreferencesKey("location_background")
        val WEATHER_BLUE = booleanPreferencesKey("weather_blue")
        val WEATHER_MOTION = booleanPreferencesKey("weather_motion")
        val DAILY_MORNING = booleanPreferencesKey("daily_weather_morning")
        val DAILY_EVENING = booleanPreferencesKey("daily_weather_evening")
        val URGENT = booleanPreferencesKey("weather_alerts")
        val PERSISTENT = booleanPreferencesKey("weather_live")
        val TEMP_CHANGE = booleanPreferencesKey("notify_temp_change")
        val WIND_ALERT = booleanPreferencesKey("notify_wind")
        val EXTREME_ALERT = booleanPreferencesKey("notify_extreme")
        val SPEECH_ENABLED = booleanPreferencesKey("speech_enabled")
        val TEMP_UNIT = stringPreferencesKey("unit_temperature")
        val WIND_UNIT = stringPreferencesKey("unit_wind")
        val PRESSURE_UNIT = stringPreferencesKey("unit_pressure")
        val PRECIP_UNIT = stringPreferencesKey("unit_precipitation")
        val LIVE_SUPPRESSED_UNTIL = longPreferencesKey("weather_live_suppressed_until")
    }
}
