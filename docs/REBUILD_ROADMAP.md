# 阶段二路线图（每日天气 → 1:1 对齐南风）

阶段一（骨架 + 主流程 + 7 组件 + 3 通知线 + 视觉验收）已完成。
**数据源已接通（2026-10-02 实测）**：彩云主源 + 和风/小米备源 + 高德命名/搜索，
全部经微风天气同款 CloudBase 云代理（`cloudbase_proxy_url`），APK 零凭据。

## P0 数据深化（key 已通，继续打磨）

1. ~~彩云 token~~：共享签名已通；个人 token（BYOK）只需填 `keys.properties: caiyun_token`
   （可解锁 dailysteps>3 的更多天）
2. **城市时区显示**：小时条按设备时区格式化，模拟器 UTC 下与北京时间错位——
   引入按城市的时区格式化（对齐南风 cityTimeContext）
3. **AQI 深化**：彩云综合响应已带 air_quality 全污染物（pm10/o3/no2/so2/co），
   扩展 AQI 卡片；`/weather/xiaomi` 亦带 6 项污染物可交叉
4. **预警全量**：alert.content 已入库，按类别/发布时间做去重推送（对接 UrgentNotificationWorker）
5. **minutely/短临**：云代理路由需加 `hourly=precipitation` 等字段白名单与
   `/weather/caiyun-minutely`（或直接放宽 hourly/daily 字段列表透传）

## P1 存储与 DI 换装（拓扑不变）

- `WeatherStore`（DataStore 归一化快照）→ **Room**：`cities` + `weather` 两表
- `AppContainer` 手写 DI → **Hilt**；`org.json` → **Moshi codegen**（可选）
- 换装原则：一次只换一层，UI/组件/通知订阅面不动

## P2 体验对齐南风的核心专项

- **场景引擎**：程序化云/雨 → `WeatherEffectOverlay` 全量（云三层级 + 粒子雨雪
  + 窗雨 + 闪电 + FPS 分档 + crossfade）
- **组件渲染管线**：数据签名按需渲染、定位占位态、按尺寸独立任务
- **通知**：Android 16 流体云升格卡（promoted ongoing）、每日富文本、预警去重
- **组件真机验收**：桌面逐个放置 7 规格

## P3 功能补全（南风功能面）

- 朝晚霞（glow）、潮汐（tide）、生活建议仪表盘、台风路径、雷达地图
  （云代理已有雷达帧路由 `/weather/radar-*`，wui 侧已打通，可直接复用）
- 会员/账号、检查更新（云代理 `/weather/app/latest`）、TTS 播报、日历/时钟联动

## 已知小账

- 城市列表无拖拽排序/长按删除；首页未做下拉刷新
- 搜索结果与已存在城市可重复添加（同 id 幂等）
- 设置页开关未接「定位关闭 → 停止定位刷新」运行时联动
- 48h 卡 LazyRow 无滚动渐隐提示；AQI 卡"缺数据"已如实占位（2026-10-02 修）
- 温度区间条 weight 已做正数钳制（真实数据 min==全局 min 时曾崩溃，已修）
