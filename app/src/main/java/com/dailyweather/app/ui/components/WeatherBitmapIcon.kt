package com.dailyweather.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext

/** 天气图标（weather / weather_blue 两套，Meteocons 命名、MIT）。 */
@Composable
fun WeatherBitmapIcon(
    assetName: String,
    blueTheme: Boolean,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val bitmap = remember(assetName, blueTheme) {
        AssetBitmaps.weatherIcon(context, assetName, blueTheme)
    }
    if (bitmap != null) {
        Image(
            painter = BitmapPainter(bitmap.asImageBitmap()),
            contentDescription = contentDescription,
            modifier = modifier,
        )
    }
}
