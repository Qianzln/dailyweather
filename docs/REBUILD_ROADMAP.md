# 阶段二路线图（每日天气 → 1:1 对齐南风）

阶段一（骨架 + 主流程 + 7 组件 + 3 通知线 + 视觉验收）已完成。
**数据源已接通（2026-10-02 实测）**：彩云主源 + 和风/小米备源 + 高德命名/搜索，
全部经微风天气同款 CloudBase 云代理（`cloudbase_proxy_url`），APK 零凭据。

## P0 数据深化（key 已通，继续打磨）

1. ~~彩云 token~~：共享签名已通；个人 token（BYOK）只需填 `keys.properties: caiyun_token`
   （可解锁 dailysteps>3 的更多天）
2. ~~**城市时区显示**~~（2026-10-03 完成）：`City.timeZoneId` + `zone`（空回退设备时区）；
   小时条/日卡/场景相位/小米源昼夜判定全部按城市当地时间。搜索的中国城市固定
   `Asia/Shanghai`，定位城市按中国范围框判定
3. ~~**AQI 深化**~~（2026-10-03 完成）：`Realtime` 增加 pm10/o3/no2/so2/co，彩云/小米
   双源解析 + SnapshotCodec 持久化 + AQI 卡六项污染物网格
4. ~~**预警全量**~~（2026-10-03 完成）：UrgentNotificationWorker 对接 RefreshManager
   刷新成功回调（unique work），按 城市+标题+pubTime 在 DataStore 账本去重，
   已取消预警不推，单轮最多 3 条
5. **minutely/短临**（部分完成）：小时条降水概率改从 `hourly.precipitation[].probability`
   取数（共享 key 综合响应的真实来源）；云函数新增 `/weather/caiyun-minutely` 路由
   （`/minutely.json`，BYOK 可用）。注意：**共享 key 不含分钟级降水数据**
   （`/minutely.json` 只回 `{"primary":0}`），分钟级曲线卡要等 BYOK 个人 token；
   彩云上游 404 会以 Flask HTML 透传，排查路由问题时注意与平台 404 区分

## P1 存储与 DI 换装（拓扑不变）

- `WeatherStore`（DataStore 归一化快照）→ **Room**：`cities` + `weather` 两表
- `AppContainer` 手写 DI → **Hilt**；`org.json` → **Moshi codegen**（可选）
- 换装原则：一次只换一层，UI/组件/通知订阅面不动

## P2 体验对齐南风的核心专项

- ~~**场景引擎**~~（2026-10-03 基本完成）：程序化粒子精灵工厂（辉光/雨滴流星/
  24 帧自旋雪花，`ParticleSprites`）+ 冷启动预热；特效分派补齐 SLEET 雨雪混合、
  风线、太阳光晕（SunPose 日照弧）、晴夜流星、闪电折线+分支；省电模式与
  「天气动效」开关接入绘制开关（Low 档降级）。分钟级降水曲线卡仍等 BYOK
- **组件渲染管线**：数据签名按需渲染、定位占位态、按尺寸独立任务
- **通知**：Android 16 流体云升格卡（promoted ongoing）、每日富文本（预警去重已完成见 P0-4）
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
