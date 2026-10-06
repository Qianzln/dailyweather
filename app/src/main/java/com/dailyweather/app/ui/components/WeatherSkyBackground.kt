package com.dailyweather.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.dailyweather.app.scene.AnimatedSkyGradient
import com.dailyweather.app.scene.SceneBridge
import com.dailyweather.app.ui.theme.LocalSky
import com.dailyweather.app.ui.theme.SkyPalette
import com.dailyweather.app.viewmodel.WeatherViewModel

/**
 * 子页（城市管理 / 城市搜索）的天气天空背景：跟主界面同一条渲染管线
 * （[AnimatedSkyGradient] 渐变底 + 精灵云帧 + 时段 tint），按**当前所选城市**的
 * 天气渲染，替代原来写死的纯蓝窗底（`launcher_bg`）——纯色不随天气变，观感生硬。
 *
 * 同时把 [LocalSky] 同步为该场景的调色板，页面里的 GlassCard（半透明卡面，
 * 天空可透出）与文字色随天空自适应；再叠一层"上重下轻"的暗角 scrim 保证
 * 顶部标题/返回键在明亮天空下仍可读。
 */
@Composable
fun WeatherSkyBackground(vm: WeatherViewModel, content: @Composable () -> Unit) {
    val cities by vm.cities.collectAsState()
    val snapshots by vm.snapshotCache.collectAsState()
    val city = vm.cityFor(vm.selectedCityId)
        ?: cities.firstOrNull { it.isCurrentLocation }
        ?: cities.firstOrNull()
    val snapshot = snapshots[vm.selectedCityId] ?: snapshots[city?.id]
    val zone = city?.zone ?: java.time.ZoneId.systemDefault()
    val scene = remember(snapshot, zone) { SceneBridge.stateFor(snapshot, zone) }
    val sky = SkyPalette.of(scene)

    Box(modifier = Modifier.fillMaxSize()) {
        // 天空（渐变 + 云帧 + 时段 tint），与主界面一致。
        AnimatedSkyGradient(scene, Modifier.matchParentSize())
        // 可读性暗角：顶部最重（压住状态栏 + 标题），向下渐隐；晴天亮天空也保证白字可读。
        Canvas(Modifier.matchParentSize()) {
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color(0x59000000),
                    0.35f to Color(0x1A000000),
                    1f to Color(0x00000000),
                    startY = 0f,
                    endY = size.height,
                ),
            )
        }
        CompositionLocalProvider(LocalSky provides sky) {
            content()
        }
    }
}
