package com.dailyweather.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import com.dailyweather.app.di.AppContainer
import com.dailyweather.app.ui.screens.CityListScreen
import com.dailyweather.app.ui.screens.CitySearchScreen
import com.dailyweather.app.ui.screens.SettingsScreen
import com.dailyweather.app.ui.screens.SettingsSubScreen
import com.dailyweather.app.ui.screens.WeatherScreen
import com.dailyweather.app.ui.theme.DailyWeatherTheme
import com.dailyweather.app.viewmodel.WeatherViewModel

/** 单 Activity + Compose 宿主（对齐南风 MainActivity；导航 = v3.35 简洁 slide/fade 基线）。 */
class MainActivity : ComponentActivity() {

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // 天空底色恒为深蓝，状态栏/导航栏图标强制浅色（深色图标在蓝底上不可读）
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val container = (application as DailyWeatherApp).container
        // 视觉校准入口：adb shell am start -n .../.MainActivity --es fixture rain_day
        intent?.getStringExtra("fixture")?.let { com.dailyweather.app.data.Fixture.state = it }
        locationPermission.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
        setContent {
            val vm = remember { WeatherViewModel(container) }
            val blue by vm.weatherBlue.collectAsState()
            DailyWeatherTheme {
                AppNavigation(vm, blueTheme = blue, onLocate = { vm.locateAndRefresh() })
            }
        }
    }
}

private sealed interface Screen {
    data object Weather : Screen
    data object CityList : Screen
    data object Search : Screen
    data object Settings : Screen
    data class Sub(val title: String) : Screen
}

@Composable
private fun AppNavigation(vm: WeatherViewModel, blueTheme: Boolean, onLocate: () -> Unit) {
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Weather)) }
    val current = stack.last()
    val pop: () -> Unit = { if (stack.size > 1) stack = stack.dropLast(1) }
    BackHandler(enabled = stack.size > 1) { pop() }

    AnimatedContent(
        targetState = current,
        transitionSpec = {
            // v3.35 基线：进入从底部轻滑入 + fade，退出 fade
            (slideInVertically(initialOffsetY = { it / 8 }) + fadeIn())
                .togetherWith(fadeOut())
        },
        label = "nav",
    ) { screen ->
        when (screen) {
            Screen.Weather -> WeatherScreen(
                vm = vm,
                blueTheme = blueTheme,
                onOpenCityList = { stack = stack + Screen.CityList },
                onOpenSearch = { stack = stack + Screen.Search },
                onOpenSettings = { stack = stack + Screen.Settings },
                onLocate = onLocate,
            )
            Screen.CityList -> CityListScreen(
                vm = vm,
                onBack = pop,
                onOpenSearch = { stack = stack + Screen.Search },
                onPickCity = { id -> vm.selectCity(id); pop() },
            )
            Screen.Search -> CitySearchScreen(vm = vm, onBack = pop)
            Screen.Settings -> SettingsScreen(
                vm = vm,
                onBack = pop,
                onOpenSub = { title -> stack = stack + Screen.Sub(title) },
            )
            is Screen.Sub -> SettingsSubScreen(
                title = screen.title, vm = vm, onBack = pop,
                onOpenSub = { t -> stack = stack + Screen.Sub(t) },
            )
        }
    }
}
