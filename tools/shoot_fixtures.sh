#!/usr/bin/env bash
# 视觉校准：逐个 fixture 启动 App 并截图，同时抓每个场景的 GL 日志。
#
# 用法：
#   export MSYS_NO_PATHCONV=1
#   ADB=/path/to/adb bash tools/shoot_fixtures.sh [输出目录]
#
# 前置：已装 **debug** 包（fixture 注入被 BuildConfig.DEBUG 门禁，release 下恒 null）。

set -u
ADB="${ADB:-D:/DEV/android-sdk/platform-tools/adb.exe}"
PKG="com.dailyweather.app"
OUT="${1:-C:/Users/qiankun/AppData/Local/Temp/dw_fixtures}"
WAIT="${WAIT:-16}"  # 单场景等待秒数（GL 预热 ~1s + 定位 ~5s + fixture 刷新）
mkdir -p "$OUT"

# 权限（`pm clear` 会重置运行时权限，所以每个场景都要重新授予）。
# ⚠️ 必须先授予定位权限：fixture 只在 `WeatherRepository.refresh(city)` 里注入，
# 而 refresh 需要一个"城市"；无定位权限 → App 停在「还没有城市」→ 永不刷新 →
# fixture 形同未装，8 个场景会全部落到同一个默认键。
grant_perms() {
  for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION POST_NOTIFICATIONS; do
    timeout 20 "$ADB" shell pm grant "$PKG" "android.permission.$p" >/dev/null 2>&1
  done
}

FIXTURES=(clear_day clear_night partly_day cloudy_day rain_day thunder_day snow_day fog_day)

echo "输出目录: $OUT"
for f in "${FIXTURES[@]}"; do
  # ⚠️ `pm clear`：fixture 快照会被 `WeatherRepository.refresh()` **持久化到本地**
  # （`weatherStore.putSnapshot(city.id, "fixture:$name", fx)`）。不清数据的话，
  # 下一个场景冷启动会先读上一场景的残影快照并可能"够新不刷新" →
  # **截到的是上一个场景**（实测 cloudy_day 排在 partly_day 后时错成 58）。
  timeout 30 "$ADB" shell pm clear "$PKG" >/dev/null 2>&1
  grant_perms
  # `-S`：先 force-stop 再启动。Activity 是 singleTask，进程还活着时 `am start`
  # 只把旧实例提到前台、onCreate 不跑 → `--es fixture` 被忽略。
  timeout 20 "$ADB" logcat -c >/dev/null 2>&1
  timeout 30 "$ADB" shell am start -S -n "$PKG/.MainActivity" --es fixture "$f" >/dev/null 2>&1
  sleep "$WAIT"
  timeout 40 "$ADB" exec-out screencap -p > "$OUT/shot_$f.png" 2>/dev/null
  {
    echo "########## $f ##########"
    timeout 25 "$ADB" logcat -d 2>/dev/null | grep -E "Oppo|WeatherScreen|fixture|FATAL|AndroidRuntime|RainRefractionShader" | tail -16
  } > "$OUT/log_$f.txt"
  sz=$(stat -c%s "$OUT/shot_$f.png" 2>/dev/null || echo 0)
  key=$(grep -oE "场景键切换: [^ ]+ -> [^ ]+" "$OUT/log_$f.txt" | tail -1 | sed 's/.*-> //')
  crash=$(grep -cE "FATAL EXCEPTION" "$OUT/log_$f.txt")
  injected=$(grep -cE "fixture=$f 已注入" "$OUT/log_$f.txt")
  note=""
  [ "$crash" -gt 0 ] && note="**崩溃**"
  [ "$injected" -eq 0 ] && note="$note **未注入**"
  echo "$f -> ${sz}B  $key  $note"
done
echo "完成"
