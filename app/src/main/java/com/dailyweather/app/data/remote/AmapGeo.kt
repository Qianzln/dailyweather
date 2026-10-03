package com.dailyweather.app.data.remote

import android.util.Log
import com.dailyweather.app.util.CoordinateTransform
import org.json.JSONObject

/**
 * 高德 Web 服务（经 CloudProxy，GCJ-02 坐标系）：逆地理四级名 + 行政区划搜索。
 * 坐标约定沿用 wui 实测结论：进高德前 WGS-84 → GCJ-02（否则区县系统性错位约 540m）。
 */
class AmapGeo(private val proxy: CloudProxy) {

    /** 城市四级名：city 为主名（对齐南风 cityName/districtName/poiName/streetName 的能力面）。 */
    data class PlaceName(val city: String, val district: String, val detail: String)

    val configured: Boolean get() = proxy.isConfigured

    /**
     * 逆地理命名。城市名取法（wui AmapGeocoder 同规则）：
     * `district` 以「市」结尾时用它（县级市落在 district），否则用 `city`、再退 `province`。
     */
    suspend fun regeoName(latitude: Double, longitude: Double): PlaceName? {
        // 高德 Web 服务只覆盖国内；境外坐标先本地拒掉，别白花一次配额（wui 实测同规则）。
        if (latitude !in 3.0..54.0 || longitude !in 73.0..136.0) return null
        val gcj = CoordinateTransform.wgs84ToGcj02(latitude, longitude)
        val body = runCatching {
            proxy.get(
                "/geo/amap-regeo",
                mapOf(
                    "location" to "%.6f,%.6f".format(gcj.longitude, gcj.latitude),
                    "extensions" to "all",
                    "radius" to "200",
                ),
            )
        }.onFailure { Log.w(TAG, "amap-regeo 失败: ${it.message}") }.getOrNull() ?: return null
        val root = JSONObject(body)
        if (root.optString("status") != "1") {
            Log.w(TAG, "amap-regeo status=${root.optString("status")}")
            return null
        }
        val regeocode = root.optJSONObject("regeocode") ?: return null
        val comp = regeocode.optJSONObject("addressComponent") ?: return null
        val province = str(comp, "province")
        val city = str(comp, "city")
        val district = str(comp, "district")
        val township = str(comp, "township")
        val neighborhood = comp.optJSONObject("neighborhood")?.let { str(it, "name") } ?: ""
        val aoi = nearestName(regeocode.optJSONArray("aois"))
        val poi = nearestName(regeocode.optJSONArray("pois"))
        val community = neighborhood.ifBlank { aoi.ifBlank { poi } }

        val cityName = when {
            district.endsWith("市") -> district
            city.isNotBlank() -> city
            else -> province
        }
        val detail = listOf(township, community).filter { it.isNotBlank() }.joinToString("")
        return PlaceName(cityName.ifBlank { "我的位置" }, district, detail)
    }

    /**
     * 行政区划搜索（对齐 wui CitySearch 的主路）：config/district 按名字查行政区划，
     * center 为「lng,lat」——返回 (名称, 纬度, 经度)。
     */
    suspend fun searchDistrict(keyword: String): List<Triple<String, Double, Double>> {
        val body = proxy.get(
            "/geo/amap-district",
            mapOf("keywords" to keyword, "subdistrict" to "0", "extensions" to "base"),
        )
        val root = JSONObject(body)
        if (root.optString("status") != "1") {
            throw IllegalStateException("amap-district status=${root.optString("status")}")
        }
        val districts = root.optJSONArray("districts") ?: return emptyList()
        return buildList {
            for (i in 0 until districts.length()) {
                val d = districts.optJSONObject(i) ?: continue
                val name = d.optString("name") ?: continue
                val center = d.optString("center").split(",")
                if (center.size != 2) continue
                val lng = center[0].toDoubleOrNull() ?: continue
                val lat = center[1].toDoubleOrNull() ?: continue
                add(Triple(name, lat, lng))
            }
        }
    }

    /** 高德的空字段回来是 `[]` 而不是 `""`，所以只认真字符串，别把数组首元素当名字。 */
    private fun str(obj: JSONObject, key: String): String =
        (obj.opt(key) as? String)?.trim().orEmpty()

    /** aois/pois 里取距离最近的 name 字段。 */
    private fun nearestName(arr: org.json.JSONArray?): String {
        arr ?: return ""
        var best: Pair<Double, String>? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name", "")
            if (name.isBlank()) continue
            val dist = o.optJSONObject("distance")?.optString("value")?.toDoubleOrNull() ?: 0.0
            if (best == null || dist < best.first) best = dist to name
        }
        return best?.second ?: ""
    }

    companion object {
        private const val TAG = "AmapGeo"
    }
}
