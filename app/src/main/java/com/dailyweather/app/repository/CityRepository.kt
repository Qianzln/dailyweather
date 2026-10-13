package com.dailyweather.app.repository

import com.dailyweather.app.data.City
import com.dailyweather.app.data.SettingsStore
import com.dailyweather.app.data.WeatherStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 城市管理（对齐南风 `CityRepository` + `ManageCityUseCase` 的职责面）。 */
class CityRepository(
    private val weatherStore: WeatherStore,
    private val settings: SettingsStore,
) {

    /**
     * 城市列表的写锁：这些操作都是「读整包 JSON → 改 → 写回」的读改写，
     * 并发时（定位自动补城 + 用户加/删/拖拽）会互相覆盖丢更新。
     * 阶段二换 Room 后由事务 + 乐观锁接管。
     */
    private val writeMutex = Mutex()

    val cities: Flow<List<City>> = weatherStore.citiesFlow

    suspend fun add(city: City): List<City> = writeMutex.withLock {
        val list = weatherStore.getCities()
        if (list.any { samePlace(it, city) }) return@withLock list
        if (city.isCurrentLocation) {
            // 只保留一个"当前定位"城市
            val cleaned = list.map { if (it.isCurrentLocation) it.copy(isCurrentLocation = false) else it }
            val next = (cleaned + city.copy(sortOrder = cleaned.size)).sortedBy { it.sortOrder }
            weatherStore.saveCities(next)
            return@withLock next
        }
        val next = list + city.copy(sortOrder = list.size)
        weatherStore.saveCities(next)
        next
    }

    suspend fun remove(cityId: String): List<City> = writeMutex.withLock {
        val list = weatherStore.getCities().filter { it.id != cityId }
        weatherStore.saveCities(list)
        weatherStore.removeCity(cityId)
        list
    }

    suspend fun setBookmarked(cityId: String, bookmarked: Boolean): List<City> = writeMutex.withLock {
        val list = weatherStore.getCities().map {
            if (it.id == cityId) it.copy(isBookmarked = bookmarked) else it
        }
        weatherStore.saveCities(list)
        list
    }

    /** 拖拽排序后的重排。 */
    suspend fun reorder(orderedIds: List<String>): List<City> = writeMutex.withLock {
        val byId = weatherStore.getCities().associateBy { it.id }
        val list = orderedIds.mapNotNull { byId[it] }
            .mapIndexed { index, city -> city.copy(sortOrder = index) }
        // 未在列表中的城市（理论上没有）追加保底
        val missing = weatherStore.getCities().filter { c -> orderedIds.none { it == c.id } }
        weatherStore.saveCities(list + missing.mapIndexed { i, c -> c.copy(sortOrder = list.size + i) })
        list + missing
    }

    suspend fun upsertCurrentLocation(
        lng: Double,
        lat: Double,
        displayName: String,
        cityName: String = "",
        districtName: String = "",
    ): City = writeMutex.withLock {
        val id = "current"
        val city = City(
            id = id, name = displayName, longitude = lng, latitude = lat,
            isCurrentLocation = true, sortOrder = 0,
            cityName = cityName.ifBlank { displayName },
            districtName = districtName,
            coordinateVerified = true,
            // 定位城市的设备时区未必与所在地一致（模拟器/出国场景）。
            // 历史上这里对境外坐标写空串，导致 City.zone 回退设备时区，与数据源的
            // 当地 sunrise/sunset 混用 → 时段槽位选错（已实测）。改为一律按坐标推断。
            timeZoneId = City.inferZoneId(lng, lat),
        )
        val list = weatherStore.getCities()
        val existing = list.firstOrNull { it.id == id }
        val next = if (existing != null) {
            list.map { if (it.id == id) city.copy(sortOrder = it.sortOrder, isBookmarked = it.isBookmarked) else it }
        } else {
            val cleaned = list.map { if (it.isCurrentLocation) it.copy(isCurrentLocation = false) else it }
            cleaned + city.copy(sortOrder = cleaned.size)
        }
        weatherStore.saveCities(next)
        city
    }

    suspend fun currentLocationCity(): City? = weatherStore.getCities().firstOrNull { it.isCurrentLocation }

    /** 返回当前定位城市，无则返回列表第一个。 */
    suspend fun primaryCity(): City? =
        currentLocationCity() ?: weatherStore.getCities().firstOrNull()

    suspend fun byId(cityId: String): City? = weatherStore.getCities().firstOrNull { it.id == cityId }

    private fun samePlace(a: City, b: City): Boolean {
        val eps = 5e-4 // ~50m
        return a.isCurrentLocation == b.isCurrentLocation &&
            kotlin.math.abs(a.longitude - b.longitude) < eps &&
            kotlin.math.abs(a.latitude - b.latitude) < eps
    }
}
