package com.dailyweather.app.repository

import com.dailyweather.app.data.City
import com.dailyweather.app.data.SettingsStore
import com.dailyweather.app.data.WeatherStore
import kotlinx.coroutines.flow.Flow

/** 城市管理（对齐南风 `CityRepository` + `ManageCityUseCase` 的职责面）。 */
class CityRepository(
    private val weatherStore: WeatherStore,
    private val settings: SettingsStore,
) {

    val cities: Flow<List<City>> = weatherStore.citiesFlow

    suspend fun add(city: City): List<City> {
        val list = weatherStore.getCities()
        if (list.any { samePlace(it, city) }) return list
        if (city.isCurrentLocation) {
            // 只保留一个"当前定位"城市
            val cleaned = list.map { if (it.isCurrentLocation) it.copy(isCurrentLocation = false) else it }
            val next = (cleaned + city.copy(sortOrder = cleaned.size)).sortedBy { it.sortOrder }
            weatherStore.saveCities(next)
            return next
        }
        val next = list + city.copy(sortOrder = list.size)
        weatherStore.saveCities(next)
        return next
    }

    suspend fun remove(cityId: String): List<City> {
        val list = weatherStore.getCities().filter { it.id != cityId }
        weatherStore.saveCities(list)
        weatherStore.removeCity(cityId)
        return list
    }

    suspend fun setBookmarked(cityId: String, bookmarked: Boolean): List<City> {
        val list = weatherStore.getCities().map {
            if (it.id == cityId) it.copy(isBookmarked = bookmarked) else it
        }
        weatherStore.saveCities(list)
        return list
    }

    /** 拖拽排序后的重排。 */
    suspend fun reorder(orderedIds: List<String>): List<City> {
        val byId = weatherStore.getCities().associateBy { it.id }
        val list = orderedIds.mapNotNull { byId[it] }
            .mapIndexed { index, city -> city.copy(sortOrder = index) }
        // 未在列表中的城市（理论上没有）追加保底
        val missing = weatherStore.getCities().filter { c -> orderedIds.none { it == c.id } }
        weatherStore.saveCities(list + missing.mapIndexed { i, c -> c.copy(sortOrder = list.size + i) })
        return list + missing
    }

    suspend fun upsertCurrentLocation(
        lng: Double,
        lat: Double,
        displayName: String,
        cityName: String = "",
        districtName: String = "",
    ): City {
        val id = "current"
        val city = City(
            id = id, name = displayName, longitude = lng, latitude = lat,
            isCurrentLocation = true, sortOrder = 0,
            cityName = cityName.ifBlank { displayName },
            districtName = districtName,
            coordinateVerified = true,
            // 定位城市的设备时区未必与所在地一致（模拟器/出国场景）：中国境内直接用北京时间。
            timeZoneId = if (lng in 73.0..135.0 && lat in 3.0..54.0) "Asia/Shanghai" else "",
        )
        val list = weatherStore.getCities()
        val existing = list.firstOrNull { it.id == id }
        val next = if (existing != null) {
            list.map { if (it.id == id) city.copy(sortOrder = it.sortOrder, isBookmarked = it.isBookmarked) else it }
        } else {
            add(city)
            weatherStore.getCities()
        }
        weatherStore.saveCities(next)
        return city
    }

    suspend fun currentLocationCity(): City? = weatherStore.getCities().firstOrNull { it.isCurrentLocation }

    suspend fun byId(cityId: String): City? = weatherStore.getCities().firstOrNull { it.id == cityId }

    private fun samePlace(a: City, b: City): Boolean {
        val eps = 5e-4 // ~50m
        return a.isCurrentLocation == b.isCurrentLocation &&
            kotlin.math.abs(a.longitude - b.longitude) < eps &&
            kotlin.math.abs(a.latitude - b.latitude) < eps
    }
}
