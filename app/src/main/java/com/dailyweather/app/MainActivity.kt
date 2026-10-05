package com.dailyweather.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import com.dailyweather.app.di.AppContainer
import com.dailyweather.app.ui.screens.AirQualityDetailScreen
import com.dailyweather.app.ui.screens.CityListScreen
import com.dailyweather.app.ui.screens.CitySearchScreen
import com.dailyweather.app.ui.screens.HourlyDetailScreen
import com.dailyweather.app.ui.screens.MinutelyPrecipScreen
import com.dailyweather.app.ui.screens.SettingsScreen
import com.dailyweather.app.ui.screens.SettingsSubScreen
import com.dailyweather.app.ui.screens.TyphoonDetailScreen
import com.dailyweather.app.ui.screens.WeatherScreen
import com.dailyweather.app.ui.theme.DailyWeatherTheme
import com.dailyweather.app.viewmodel.WeatherViewModel

/** 单 Activity + Compose 宿主。
 *
 * 导航动画（对齐南风 v4.3.51 手势/点击过渡）：
 * - 前进（入栈）：新页从右侧滑入（320dp 全宽，300ms），旧页 fadeOut。
 * - 返回（出栈）：旧页从左侧滑回，新页 fade。
 * - Hero 温度数字切换用滑入+淡入（已在 WeatherScreen 内完成）。
 * - 卡片错峰入场 60ms 间隔（已在 WeatherScreen 内完成）。
 */
class MainActivity : ComponentActivity() {

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        // SplashScreen：必须在 super.onCreate 之前安装（API<31 兼容路径依赖此次安装）。
        installSplashScreen()
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

    /** 首页卡片的详情子屏（逐小时 / 空气质量 / 分钟级降水）。 */
    data class Detail(val kind: DetailKind) : Screen
}

enum class DetailKind { HOURLY, AIR_QUALITY, MINUTELY, TYPHOON }

@Composable
private fun AppNavigation(vm: WeatherViewModel, blueTheme: Boolean, onLocate: () -> Unit) {
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Weather)) }
    // 记录前一次栈深度，用于判断前进/后退方向。
    var prevStackSize by remember { mutableStateOf(1) }
    val isForward = stack.size > prevStackSize
    prevStackSize = stack.size

    val current = stack.last()
    val pop: () -> Unit = { if (stack.size > 1) stack = stack.dropLast(1) }
    BackHandler(enabled = stack.size > 1) { pop() }

    AnimatedContent(
        targetState = current,
        transitionSpec = {
            // 前进：新页从右侧滑入 + fade，旧页淡出
            // 返回：旧页滑回左侧，新页淡入
            val durationMs = 300
            val enter: androidx.compose.animation.EnterTransition = if (isForward) {
                slideInHorizontally(initialOffsetX = { it }) + fadeIn()
            } else {
                slideInHorizontally(initialOffsetX = { -it }) + fadeIn()
            }
            enter.togetherWith(fadeOut())
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
                onOpenDetail = { kind -> stack = stack + Screen.Detail(kind) },
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
            is Screen.Detail -> {
                val snapshots by vm.snapshotCache.collectAsState()
                val city = vm.cityFor(vm.selectedCityId)
                val snap = snapshots[vm.selectedCityId] ?: snapshots[city?.id]
                val zone = city?.zone ?: java.time.ZoneId.systemDefault()
                when (screen.kind) {
                    DetailKind.HOURLY -> HourlyDetailScreen(snapshot = snap ?: return@AnimatedContent, zone = zone, onBack = pop)
                    DetailKind.AIR_QUALITY -> AirQualityDetailScreen(snapshot = snap ?: return@AnimatedContent, onBack = pop)
                    DetailKind.MINUTELY -> MinutelyPrecipScreen(snapshot = snap ?: return@AnimatedContent, onBack = pop)
                    DetailKind.TYPHOON -> TyphoonDetailScreen(vm = vm, snapshot = snap, onBack = pop)
                }
            }
        }
    }
}
