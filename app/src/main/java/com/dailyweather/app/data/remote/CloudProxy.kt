package com.dailyweather.app.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * CloudBase 云函数 weatherProxy —— App 的唯一外部网络出口。
 *
 * 与微风天气 wui 的同名对象同构（链路已实测打通）：彩云/和风/小米/高德全部经
 * 此云函数直连上游，凭据只存在于云函数环境变量，APK 零 Key。云函数把上游的
 * HTTP 状态码与正文**原样透传**，因此「非 2xx 抛错 + 上游业务字段判定」的语义
 * 与直连一致。
 *
 * BaseUrl 来自 `BuildConfig.CLOUDBASE_PROXY_URL`（keys.properties 的
 * `cloudbase_proxy_url`，已 gitignore）。留空 = 未配置：Provider 抛诚实错误，
 * ProviderChain 照常降级，页面不炸，也不伪造数据。
 */
class CloudProxy(baseRaw: String) {

    private val baseURL: String = baseRaw.trim().removeSuffix("/")

    val isConfigured: Boolean get() = baseURL.isNotBlank()

    suspend fun get(path: String, params: Map<String, String> = emptyMap()): String =
        fetchRaw(path, params, "application/json")

    suspend fun getBytes(path: String, params: Map<String, String> = emptyMap()): ByteArray =
        fetchBytes(path, params, "image/*")

    /** 取字节数组及其状态码；404 用于"该帧已不在可用列表"这类语义判定。 */
    suspend fun getBytesWithStatus(path: String, params: Map<String, String> = emptyMap()): Pair<Int, ByteArray> =
        withContext(Dispatchers.IO) {
            require(isConfigured) { "云代理未配置（keys.properties 的 cloudbase_proxy_url 为空）" }
            val (code, bytes) = doFetch(path, params, "image/*")
            code to bytes
        }

    private suspend fun fetchRaw(path: String, params: Map<String, String>, accept: String): String =
        withContext(Dispatchers.IO) {
            require(isConfigured) { "云代理未配置（keys.properties 的 cloudbase_proxy_url 为空）" }
            val (code, bytes) = doFetch(path, params, accept)
            if (code !in 200..299) {
                Log.w(TAG, "GET $path → HTTP $code")
                throw ProxyHttpException("云代理 HTTP $code ($path)")
            }
            bytes.decodeToString()
        }

    private suspend fun fetchBytes(path: String, params: Map<String, String>, accept: String): ByteArray =
        withContext(Dispatchers.IO) {
            require(isConfigured) { "云代理未配置（keys.properties 的 cloudbase_proxy_url 为空）" }
            val (code, bytes) = doFetch(path, params, accept)
            if (code !in 200..299) {
                Log.w(TAG, "GET $path → HTTP $code")
                throw ProxyHttpException("云代理 HTTP $code ($path)")
            }
            bytes
        }

    /** 共享的 HTTP 连接逻辑。返回 (status code, body bytes)。 */
    private fun doFetch(path: String, params: Map<String, String>, accept: String): Pair<Int, ByteArray> {
        val query = params.entries.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }
        val url = URL(baseURL + path + if (query.isEmpty()) "" else "?$query")
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", accept)
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            code to bytes
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "CloudProxy"
        const val TIMEOUT_MS = 10_000
    }
}

class ProxyNotConfiguredException(message: String) : IllegalStateException(message)
class ProxyHttpException(message: String) : IllegalStateException(message)
