package com.dailyweather.app.util

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WGS-84 ⇄ GCJ-02 互转（业界通用公开偏移算法，与主流地图 SDK 的
 * CoordinateConverter 同源）。
 *
 * 坐标系约定沿用微风天气实测结论：
 * - 高德 regeo / district 期望 **GCJ-02**（不转会系统性偏移约 540m，区县错位）；
 * - 彩云 / 和风 / 小米天气接口用 **WGS-84 原始值**；
 * - 系统 LocationManager 按 Android 契约返回 WGS-84。
 *
 * [Point] 纬度在前，与 Location.getLatitude 口径一致，避免经纬序写反。
 */
object CoordinateTransform {

    data class Point(val latitude: Double, val longitude: Double)

    private const val INVERSE_ITERATIONS = 3

    fun wgs84ToGcj02(latitude: Double, longitude: Double): Point {
        if (outOfChina(latitude, longitude)) return Point(latitude, longitude)
        val dLat = transformLat(longitude - 105.0, latitude - 35.0)
        val dLng = transformLng(longitude - 105.0, latitude - 35.0)
        val radLat = latitude / 180.0 * Math.PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        val offsetLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI)
        val offsetLng = (dLng * 180.0) / (A / sqrtMagic * cos(radLat) * Math.PI)
        return Point(latitude + offsetLat, longitude + offsetLng)
    }

    fun gcj02ToWgs84(latitude: Double, longitude: Double): Point {
        if (outOfChina(latitude, longitude)) return Point(latitude, longitude)
        var lat = latitude
        var lng = longitude
        repeat(INVERSE_ITERATIONS) {
            val gcj = wgs84ToGcj02(lat, lng)
            lat += latitude - gcj.latitude
            lng += longitude - gcj.longitude
        }
        return Point(lat, lng)
    }

    private fun outOfChina(lat: Double, lng: Double): Boolean =
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y +
            0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * Math.PI) + 320 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return ret
    }

    private const val A = 6378245.0 // 克拉索夫斯基椭球长半轴
    private const val EE = 0.00669342162296594326
}
