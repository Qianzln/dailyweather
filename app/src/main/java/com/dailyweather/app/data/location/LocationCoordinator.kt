package com.dailyweather.app.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.dailyweather.app.data.SettingsStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * 定位编排（对齐南风 `LocationManager`/`LocationRequestCoordinator` 的骨架）：
 * 系统 LocationManager 的 GPS + Network 双通道并行，先到先用，超时后 Passive 兜底。
 * 高德 SDK（AmapLocationProvider）在 key 注入后作为第一优先引擎插入。
 */
class LocationCoordinator(
    private val context: Context,
    private val settings: SettingsStore,
) {

    data class Fix(
        val longitude: Double,
        val latitude: Double,
        val accuracy: Float,
        val provider: String,
        val timestamp: Long = System.currentTimeMillis(),
    )

    private val lastKnown = AtomicReference<Fix?>(null)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun lastFix(): Fix? = lastKnown.get()

    /** 一次定位请求；`timeoutMs` 内 GPS/Network 任一先到即返回，全超时尝试 Passive。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun requestFix(timeoutMs: Long = 15_000L): Fix? {
        if (!hasPermission()) {
            Log.w(TAG, "无定位权限，跳过定位")
            return null
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = buildList {
            if (lm.allProviders.contains(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
            if (lm.allProviders.contains(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
        }
        Log.i(TAG, "NativeLocation: 启动 GPS & Network 双通道并行定位, providers=$providers")
        val fix = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listener = LocationListener { loc ->
                    if (cont.isActive) {
                        Log.i(TAG, "NativeLocation: 并行定位成功: provider=${loc.provider}")
                        cont.resume(loc.toFix())
                    }
                }
                try {
                    providers.forEach { p ->
                        lm.requestLocationUpdates(p, 0L, 0f, listener, Looper.getMainLooper())
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "NativeLocation: 注册监听失败 ${e.message}")
                    if (cont.isActive) cont.resume(null)
                }
                cont.invokeOnCancellation {
                    runCatching { providers.forEach { p -> lm.removeUpdates(listener) } }
                }
            }
        }
        runCatching {
            val all = LocationListener { }
            providers.forEach { p -> lm.removeUpdates(all) }
        }
        if (fix != null) {
            lastKnown.set(fix)
            return fix
        }
        // Passive 兜底
        val passive = if (lm.allProviders.contains(LocationManager.PASSIVE_PROVIDER)) {
            lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
        } else null
        Log.i(TAG, "NativeLocation: 并行定位超时，Passive 兜底=${passive != null}")
        return passive?.toFix()?.also { lastKnown.set(it) }
    }

    private fun Location.toFix() = Fix(longitude, latitude, accuracy, provider ?: "unknown")

    private var moveWatcher: LocationListener? = null
    private var watching = false

    /**
     * 持续位移监听：系统只在移动 [thresholdMeters] 米（默认 500m）时才回调，省电。
     * 位置跨城/跨区移动时触发 [onMove]，由调用方决定是否重新逆地理 + 刷新天气。
     * 与单次 [requestFix] 独立——requestFix 是"要一个当前定位"，这个是"位置变了通知我"。
     */
    fun startMoveWatcher(onMove: (Fix) -> Unit, thresholdMeters: Double = 500.0) {
        if (!hasPermission() || watching) return
        watching = true
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = LocationListener { loc ->
            val last = lastKnown.get()
            val fix = loc.toFix()
            if (last == null || distanceMeters(last, fix) >= thresholdMeters) {
                lastKnown.set(fix)
                Log.i(TAG, "位置移动 ${last?.let { distanceMeters(it, fix) }?.toInt() ?: 0}m → 触发刷新")
                runCatching { onMove(fix) }
            }
        }
        moveWatcher = listener
        runCatching {
            val providers = buildList {
                if (lm.allProviders.contains(LocationManager.PASSIVE_PROVIDER)) add(LocationManager.PASSIVE_PROVIDER)
                if (lm.allProviders.contains(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
                if (lm.allProviders.contains(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
            }
            providers.forEach { p ->
                // minTime 1s + minDistance threshold：让系统只在真实位移时回调，不空转。
                lm.requestLocationUpdates(p, 1_000L, thresholdMeters.toFloat(), listener, Looper.getMainLooper())
            }
            Log.i(TAG, "位移监听已启动，providers=${providers.size}")
        }.onFailure { Log.w(TAG, "位移监听注册失败 ${it.message}") }
    }

    fun stopMoveWatcher() {
        moveWatcher?.let { l ->
            runCatching {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                for (p in listOf(LocationManager.PASSIVE_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
                    if (lm.allProviders.contains(p)) runCatching { lm.removeUpdates(l) }
                }
            }
        }
        moveWatcher = null
        watching = false
    }

    /** 两点距离（米）；用于「距离上次缓存位置仅为 x 米」的短距离短路。 */
    fun distanceMeters(a: Fix, b: Fix): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val s = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(a.latitude)) * Math.cos(Math.toRadians(b.latitude)) *
            Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return 2 * r * Math.asin(Math.sqrt(s))
    }

    companion object {
        private const val TAG = "LocationCoordinator"

        /** 默认占位坐标（对齐南风「避免北京首帧」的占位语义）：(0,0) 视为未定位。 */
        fun isPlaceholder(lng: Double, lat: Double): Boolean = abs(lng) < 1e-6 && abs(lat) < 1e-6
    }
}
