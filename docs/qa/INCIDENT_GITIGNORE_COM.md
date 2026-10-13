# 事故记录：.gitignore `com/` 误伤 GL 引擎入库

记录人：Lens · 2026-10-13 18:05

## 事件概述

`.gitignore` 第 28 行的 `com/` 规则**不带前导斜杠**，会递归匹配任意深度的 `com/` 目录，
把整个 `app/src/main/java/com/dailyweather/app/scene/oppo/`（GL 引擎）以及另外 4 个 UI/通知文件
排除在 git 追踪之外。这些文件自始至终将**从未入库**（master 上 0 个），只在本地工作区存在。

## 根因

- 规则 `com/` 本意是排除根目录下的某个 `com/` 输出目录；
- gitignore 语义：不带 `/` 前缀的目录模式在**任意层级**生效；
- 正确写法：`/com/`（只匹配根目录）。

## 影响面（本次恢复共 12 个文件，3142 行）

| 类别 | 文件 |
|---|---|
| GL 引擎（8） | scene/oppo/OppoScene.kt、OppoSceneKey.kt、gl/OppoGlCore.kt、gl/OppoGlSceneView.kt、gl/OppoParticleSystem.kt、gl/OppoSceneRenderer.kt、gl/OppoShaderLibrary.kt、gl/OppoTextureCache.kt |
| UI/通知（4） | WeatherChangeAlertWorker.kt、HomeControls.kt、LocalHaze.kt、GlassTheme.kt |

附带风险：v0.1.52 全 GL 化以来的所有 GL 引擎演进都只在单机工作区，GitHub 远端无任何备份；
若本机磁盘损坏则 GL 引擎全部丢失。

## 修复

- 修复提交：**`6788476f`**（"修复 .gitignore 误伤：com/ → /com/，恢复 GL 引擎等 12 个文件追踪"）
- Lens 验证：`git ls-tree -r master -- app/src/main/java/com/dailyweather/app/scene/oppo/` 确认 8 个文件全部在库 ✔

## ⚠️ 遗留问题（验证时发现，已闭环）

**master 上已提交的 .gitignore 仍是 `com/`**（6788476f 只 force-add 了文件、漏提交规则本身）。
Meteor 推送前发现该漏项，补提交 **ac20d847**（`com/` → `/com/`），并推送到 origin（`f58c0eef..ac20d847`）。

Lens 复核（2026-10-13 18:25）：
- ac20d847 起 .gitignore 第 31 行确认为 `/com/` ✔
- 12 个文件全部在库，且工作区与 master 逐字节一致（diff 为空）✔
- fdacc567 / 合并提交 286b3900 把分支整理合回 master，C 线闭环 ✔

## 教训

1. 基线提交（99f0d774）前未做 `git status` / `check-ignore` 全量核对，导致漏追踪长期潜伏；
2. 新增 gitignore 规则一律带 `/` 前缀并验证 `git check-ignore -v` 范围；
3. 基线入库时用 `git ls-files | wc -l` 对照工作区文件数做完整性校验。
