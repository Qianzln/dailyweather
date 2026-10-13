# DailyWeather v0.1.55 修复日志

> 日期：2026-10-13
> 目标：卡片间距全页面统一（用户反馈"有些过大有些过小"）。

## 审核发现：卡外间距全 App 一共 5 种

| 位置 | 原间距 | 问题 |
|---|---|---|
| 首页卡列 | 16dp（CardGap） | 基准值 |
| 首页底部来源行 | 16 + 8 = 24dp | 额外叠了 padding(top=8dp)，过大 |
| 城市管理列表 | 10dp | 过小 |
| 城市搜索结果 | 10dp | 过小 |
| 设置页 | 14dp | 偏差 |
| 详情页（逐小时/空气/分钟降水） | 12dp | 偏差 |

## 修复

全部统一为 `Tokens.CardGap`（16dp，即首页现行值）：
- WeatherScreen：来源行去掉额外 8dp padding；
- CityListScreen / CitySearchScreen：10dp → CardGap；
- SettingsScreen：14dp → CardGap；
- WeatherDetailScreens：外层卡列 12dp → CardGap
  （AQI 卡内部「六项污染物」的 12dp 是卡内内容行距，非卡间距，未动）。

不动项：顶部呼吸位 56dp、底部手势条留白 24dp、卡内 padding（HomeCardPadding 14/12）
均与卡间距无关，保持原样。
