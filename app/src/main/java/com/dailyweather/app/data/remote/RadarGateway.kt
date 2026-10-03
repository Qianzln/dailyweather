package com.dailyweather.app.data.remote

import com.dailyweather.app.data.remote.CloudProxy
import org.json.JSONObject

/**
 * 雷达数据出口 —— 客户端只认这一个接口。
 *
 * 实现**只能**走 [CloudProxy]（全 App 唯一网络出口）。这里刻意不提供"直接给个 URL 去取"
 * 的能力：客户端连上游主机名都不知道，也就无从拼出任何非白名单地址。
 * 函数侧只回时刻，路径与主机留在服务端。
 */
class RadarGateway(private val proxy: CloudProxy) {

    data class RadarMetadata(
        val generatedAt: Long,
        val frameTimes: List<Long>,
        val frameIntervalSec: Long,
        val sizePx: Int,
        val zoom: Int,
        val colorScheme: Int,
    )

    /** 这一帧取不到（上游滚动窗口把它换掉了）。不是"网络故障"，别混成一类。 */
    class RadarFrameUnavailable(message: String) : Exception(message)

    suspend fun metadata(): RadarMetadata {
        val body = proxy.get(PATH_META, emptyMap())
        val o = JSONObject(body)
        val arr = o.optJSONArray("frames")
        val times = ArrayList<Long>(if (arr == null) 0 else arr.length())
        for (i in 0 until (arr?.length() ?: 0)) {
            val t = arr.optJSONObject(i)?.optLong("time", -1L) ?: -1L
            if (t > 0) times += t
        }
        val tile = o.optJSONObject("tile")
        return RadarMetadata(
            generatedAt = o.optLong("generatedAt", 0L),
            frameTimes = times.sorted(),
            frameIntervalSec = o.optLong("frameIntervalSec", 600L),
            sizePx = tile?.optInt("sizePx", 512) ?: 512,
            zoom = tile?.optInt("zoom", 6) ?: 6,
            colorScheme = tile?.optInt("colorScheme", 2) ?: 2,
        )
    }

    /** 取一帧 PNG。[epochSec] 必须来自 [metadata.frameTimes]。 */
    suspend fun frame(epochSec: Long, lat: Double, lon: Double): ByteArray {
        val (code, bytes) = proxy.getBytesWithStatus(
            PATH_FRAME,
            mapOf("frame" to epochSec.toString(), "lat" to round2(lat), "lon" to round2(lon)),
        )
        if (code == 404) throw RadarFrameUnavailable("frame $epochSec 已不在可用列表")
        if (code !in 200..299) throw IllegalStateException("雷达帧 HTTP $code")
        return bytes
    }

    /** 取覆盖遮罩（透明 = 有雷达覆盖）。 */
    suspend fun coverage(lat: Double, lon: Double): ByteArray =
        proxy.getBytes(
            PATH_COVERAGE,
            mapOf("lat" to round2(lat), "lon" to round2(lon)),
        )

    private fun round2(v: Double): String {
        val r = Math.round(v * 100.0) / 100.0
        return if (r == 0.0) "0.00" else String.format(java.util.Locale.US, "%.2f", r)
    }

    companion object {
        // 挂在已有的 /weather 前缀下：网关只透传 /weather 与 /geo。
        private const val PATH_META = "/weather/radar/meta"
        private const val PATH_FRAME = "/weather/radar/frame"
        private const val PATH_COVERAGE = "/weather/radar/coverage"
    }
}
