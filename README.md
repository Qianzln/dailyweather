# 每日天气（Daily Weather）

对标南风天气（SkyPulse v4.3.36）的一比一架构复刻。技术解析报告见
`D:\01 项目\100 个人文件\天气\SkyPulse_v4.3.36_南风天气_完整解析报告.md`。

## 构建

环境沿用本机 wui 工具链（JDK17 / SDK / Gradle 9.6.0 全在 `D:\DEV`）：

```bat
run_build.bat assembleDebug
```

- 真身路径：`D:\DEV\dailyweather`（ADR-009：纯 ASCII 路径构建）
- `D:\01 项目\100 个人文件\天气\每日天气` 是指向它的 junction
- 产物：`app\build\outputs\apk\debug\app-debug.apk`

## 凭据（不入库）

在项目根目录 `keys.properties`（参考 `keys.properties.example`）：

```properties
caiyun_token=彩云天气token
amap_key=高德Web服务key
```

未配置时：定位/城市/组件照常工作，天气数据显示「等待数据…」与降级提示，不崩溃。

## 当前状态（阶段一 + 数据源接通，2026-10-02）

**数据源已接通并模拟器实测出真数据**：全部天气/地理请求经 CloudBase 云函数
`weatherProxy`（与微风天气同一已打通链路），凭据只在云函数环境变量，APK 零 Key：

- **彩云 v2.6 综合天气（主源）**：`/weather/caiyun`（HMAC 签名在云端；`caiyun_token`
  可选 BYOK 走个人额度）；响应透传，解析对齐 v2.6 真实结构（result 包裹 + 扁平数值
  + ISO 时间），实测北京 17° 多云夜/日出 07:08/48h/五日全出
- **和风天气（备源）**：`qweather-now` / `qweather-72h` / `qweather-7d`，
  iconCode→skycon 映射复用图标链路；AQI/预警不可用（诚实置空）
- **小米天气（第三源）**：`/weather/xiaomi-geo` 反查 locationKey → `/weather/xiaomi`
  综合天气；{value,unit} 包裹字段已适配
- **高德 Web 服务**：`/geo/amap-regeo`（四级命名，WGS-84→GCJ-02 转换在
  `CoordinateTransform`）+ `/geo/amap-district`（城市搜索主路，内置 49 城拼音兜底）
- ProviderChain：彩云失败自动降级和风、再降级小米；缓存为归一化快照
  （SnapshotCodec）+ 来源标签，下游（UI/组件/通知）对上游无感

已实现并模拟器验收通过：

- 单 Activity + Compose 首页（天空渐变 + 程序化云/雨场景、温度 Hero、
  短临降水/预警卡、48 小时条、7 日温度区间条、AQI、日出日落）
- 城市列表 / 城市搜索（高德 key 注入后走高德，内置 49 城拼音兜底）
- 系统定位（GPS+Network 并行、Passive 兜底、5 秒去重）
- RefreshManager（按城市加锁 + 预算 + 补充数据预算位）
- 彩云 v2.6 直连（模型对齐其 `Caiyun*` 族；`org.json` 解析，零三方依赖）
- 7 套桌面组件（small/2x1/4x2/medium/ios/hourly/week，week 带农历）+
  渲染器 + WorkManager 周期任务
- 通知：每日早晚报 / 预警 / 常驻胶囊 + 划除 6 小时静默
- 设置页（定位/通知/外观/数据源状态/关于）
- 图标：自研 SVG 解析引擎渲染 Lucide（MIT）+ Meteocons 双套天气位图（MIT）

## 阶段二路线

见 `docs/REBUILD_ROADMAP.md`。
