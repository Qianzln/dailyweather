package com.dailyweather.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache

/** assets 位图（天气图标/太阳）解码缓存——组件 RemoteViews 与 Compose 共用。 */
object AssetBitmaps {

    private val cache = LruCache<String, Bitmap>(64)

    fun weatherIcon(context: Context, assetName: String, blueTheme: Boolean): Bitmap? {
        val dir = if (blueTheme) "weather_blue" else "weather"
        return decode(context, "$dir/$assetName.png")
    }

    fun sun(context: Context, assetName: String): Bitmap? = decode(context, "sun/$assetName.png")

    private fun decode(context: Context, path: String): Bitmap? {
        cache.get(path)?.let { return it }
        return runCatching {
            context.assets.open(path).use { BitmapFactory.decodeStream(it) }
        }.getOrNull()?.also { cache.put(path, it) }
    }
}
