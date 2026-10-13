# C 线审查报告：refactor/line-c 重构 #1-#4（commit 7009bafe / v0.1.57）

> 审查人：Lens（QA）｜ 初审：2026-10-13 17:50 ｜ 复核：2026-10-13 19:45
> 对象：`refactor/line-c` @ `7009bafe`（初审）；master @ `286b3900`（复核）
> 方法：git diff 审查 + 磁盘工作树核对 + 着色器/preset 数据全量交叉核对 + 红线专项检查 + 克隆编译验收
> 前置文件：Meteor 的 v0.1.56 审查报告 `docs/C_LINE_REVIEW_V0.1.56.md`（本报告 #1-#4 即对应其 #1/#2/#4/#5+#6）

## 总体结论：✅ **通过**（复核后；附 1 项低危实机观察项）

- 初审结论为「不通过（阻断）」：提交空壳 + 12 个核心源文件未入库。
- 复核：Meteor 已在 master 修复（`6788476f` 补录 12 文件 + `ac20d847` 修规则本身），验收命令全部通过，**全新克隆 assembleDebug 构建成功（3m40s，exit 0）**。阻断解除。
- 历史注记（不追改）：`fdacc567`（v0.1.57 重构）仍是「空壳提交信息」——真实代码 diff 落在 6788476f 的补录提交里，提交信息与 diff 不一致的问题在历史上留痕。记为流程教训，「commit 后 git show --stat 自查」已立规（Meteor 执行、Lens 抽查）。

| # | 项 | 磁盘实现 | git 入库 | 判定 |
|---|----|---------|---------|------|
| F1 | .gitignore 裸 `com/` 规则吞掉核心源码 | — | — | ✅ 已修复并验证 |
| F2 | 提交内容与提交信息不符 | — | — | ✅ 内容已入库（历史留痕见上） |
| #1 | alphaUniformNames 自动推导 | ✅ 已实现 | ✅ | 🟢 通过（附 1 项观察） |
| #2 | parseEntity null 保护 | ⚠️ 前提不成立 | — | ⚪ 已撤销（Chief 裁决 2026-10-13） |
| #3 | readyFlow 公开 | ✅ 已实现 | ✅ | 🟢 通过 |
| #4 | drainPreloadQueue 优化 | ✅ 已实现 | ✅ | 🟢 通过 |

---

## F1 ✅ .gitignore 裸 `com/` 规则导致核心源码整体不入库（已修复并验证）

**初审发现**：
- `.gitignore` 第 31 行 `com/`（无前导斜杠）匹配任意层级的 `com` 目录 → `app/src/main/java/com/` 整棵子树被忽略。
- 磁盘 61 个 .kt，git 只跟踪 49 个；被吞 12 个：`scene/oppo/` 全部 8 个（OppoScene/OppoSceneKey/gl/OppoGlCore/gl/OppoGlSceneView/gl/OppoParticleSystem/gl/OppoSceneRenderer/gl/OppoShaderLibrary/gl/OppoTextureCache）+ `notification/WeatherChangeAlertWorker.kt` + `ui/components/HomeControls.kt`、`LocalHaze.kt` + `ui/theme/GlassTheme.kt`。
- 引入时点：`99f0d774`（v0.1.55 基线）自身写入该规则——「全量现状入库」从第一天起就缺这 12 个文件。
- 后果：GitHub 克隆缺全部引擎源码无法编译；磁盘是唯一副本；v0.1.56 的 GL 优化同样没落库。

**复核验收（2026-10-13 19:45，全部通过）**：
| 验收项 | 命令 | 结果 |
|---|---|---|
| 规则修复 | `.gitignore:31` | ✅ `com/` → `/com/`（ac20d847） |
| 12 文件补录 | `git show --stat 6788476f` | ✅ 12 文件 +3142 行（与初审清单逐一对应） |
| 忽略残留 | `git ls-files --others -i --exclude-standard` | ✅ app/src/main 下无 .kt 被忽略 |
| 跟踪数=磁盘数 | `git ls-files` vs 磁盘 | ✅ 61 = 61 |
| 关键符号在库 | `git grep master` | ✅ alphaUniformNames / drainPreloadQueue / val readyFlow 均在 master HEAD |
| 克隆可编译 | 全新克隆（751 文件/61 kt/410 assets）+ `assembleDebug` | ✅ BUILD SUCCESSFUL in 3m40s（仅既有风格警告） |

## F2 ✅ 空壳提交问题（内容已入库，历史留痕不追改）

- 初审：`7009bafe` 实际 diff 仅 .gitignore +3 行，提交信息声称的 5 项代码改动均不在仓库。
- 复核：代码内容已随 6788476f 全量入库并验证；但重提后的 `fdacc567`（v0.1.57）**仍是空壳信息**（diff 仍只有 .gitignore +3 行，真实改动在 6788476f 内）。Chief 裁定不改写历史，故标记为「内容已修复、历史留痕」。
- 流程改进：「commit 后 `git show --stat` 自查」已立规（Meteor 执行、Lens 抽查）。ac20d847 的提交信息自证该规则已在起作用（主动承认 6788476f 漏提交规则本身并补上）。

---

## 逐项审查（针对磁盘实现 + Meteor 报告原文）

### #1 alphaUniformNames 自动推导 —— 🟢 通过（附 1 项观察）

- 实现位置：`OppoShaderLibrary.kt:59-64`，Program 构建时按「类型 FLOAT/BOOL 且名字含 alpha（不区分大小写）」自动推导；`OppoSceneRenderer.kt:309` 淡入处改用 `prog.alphaUniformNames`，旧硬编码 5 名集合已删。
- 对全量 32 个着色器资产逐一核对（`assets/oppo/shader/*.glsl` 中的 alpha 类 uniform 声明）：
  - 旧 5 名（`_AlphaValue`/`u_Alpha`/`_Alpha`/`glowAlpha`/`particleAlpha`）全部被新规则覆盖 ✓
  - 新捕获 `texAlpha`（415afc…/6c5a…/7a9a… 三个粒子着色器）：**双重无害** —— 粒子实体走 `particles.draw()` 不进 drawEntity 的 uniform 循环；且 presets.json 的实体数据里根本没有 texAlpha 键，不会被上传。
  - 新捕获 `_AlphaProgress`（9d623d35… 风层着色器，`fragColor.a *= _Alpha * _AlphaProgress`，两个都是 alpha 语义）：数据实体 #157/#159/#233/#235（wind 层）带此 uniform，涉及预设 `19_2/19_6/20_2/20_6`（大风/风暴·昼夜）。行为差异：入场淡入 0.9s 内该层有效 alpha 从 ×fade 变为 ×fade²，**风层起步会比以前略慢**。
    → 🟡 **存疑（低危）**：请用户实机看一眼大风/风暴场景切换时的淡入观感；无异议即维持，有异议再对 `_AlphaProgress` 加排除。（已收录 `SCENE_CHECKLIST.md` 19/20 组备注）
- Meteor 报告建议的「排除 `u_AlphaBlend` 类混合参数前缀」未实现：当前 32 个着色器没有此类命名，无实况风险，记为后续防御项（不阻塞）。

### #2 parseEntity null 保护 —— ⚪ 已撤销（Chief 裁决 2026-10-13）

- Meteor 报告原文：`OppoScene.kt:275-278` 的 pos/scale/rot「直接传给了顶层 `floatArrayOf`（stdlib vararg），传入 null 会 NPE」。
- QA 核对结论：**该前提不成立**。`OppoScene.kt:379` 存在 `private fun floatArrayOf(a: JSONArray?, defaultDim: Int = 3): FloatArray`（null → `FloatArray(3)`），与 parseEntity（:262）同文件同作用域。275-277 行的调用在 Kotlin 重载解析下**本来就**绑定到这个保护版——stdlib 的 `floatArrayOf(vararg Float)` 根本无法接受 `JSONArray?` 实参，若真绑到它根本编译不过。
- 磁盘 275-277 行与报告引用的「现状」逐字一致 → 所谓「修复」在磁盘上无任何变化（也无需变化）。
- **Chief 裁决（2026-10-13）：QA 举证成立，该项从 changelog 撤销。**

### #3 readyFlow 公开 —— 🟢 通过

- `OppoSceneKey.kt:51-58`：`_readyFlow` 私有 + `readyFlow: StateFlow<Boolean>` 公开，`prewarm` 完成时置 true（:80）。与 v0.1.51「冷启动场景键永久 null」修复意图一致。
- 消费方 `WeatherScreen.kt:162-163` 已 `collectAsState` 并进入 `remember` 键 ✓。

### #4 drainPreloadQueue 优化 —— 🟢 通过

- `OppoSceneRenderer.kt:220-225`：空队列早退 + `ArrayList<String>(4)` + `repeat(4)` 取队首 + 批量 preload，与报告建议形态一致。
- 「等价改写其实不等价」坑已核：提交信息自证曾误写 `mutableListOf(4)`（无容量参数，推导成 `MutableList<Int>`，加入 String 即类型错误 = 编译失败），磁盘已是修正版 ✓。
- 遗留微分配（drain 期间每帧一个 `ArrayList(4)`，约 10 帧结束）：可忽略，不追。

---

## 红线专项检查（全部通过）

| 红线 | 结果 | 证据 |
|---|---|---|
| OppoGlSceneLayer 必须与 hazeSource Box 平级 | ✅ | WeatherScreen.kt:204-223：外层 Box → OppoGlSceneLayer（210）+ hazeSource Box（223）兄弟并列，注释自证（208-209） |
| Canvas 特效层（v0.1.52 已删）不得重建 | ✅ | SpriteSky / SpriteClouds / NoiseHaze / WeatherEffectOverlay / WeatherEffects 全仓库零匹配 |
| 降水强度按 mm/h 不用概率 | ✅ | SceneBridge.kt:97-106：`sqrt(mm/40)` 保底 0.18；OppoSceneKey 分档 2.5/8/16 mm/h |
| CLOUDY→组 5，PARTLY_CLOUDY_*→cloudyGroup | ✅ | OppoSceneKey.kt:221-222 |
| 全 App 卡间距 Tokens.CardGap=16dp | ✅ | Tokens.kt:23 `val CardGap = 16.dp`；首页/详情/城市管理/搜索/设置 5 处全部 `spacedBy(Tokens.CardGap)` |

## 其他观察（不阻塞，仅记录）

- `OppoSceneRenderer.kt:270-279` 云漂移重建：`e.pos[0]`/`e.pos[1]` 无 getOrElse 保护；`floatArrayOf` 在 JSONArray 长度 <3 时会产出短数组。当前 presets.json 数据完好（v0.1.54 已核 64 场景零差异），无实况风险，属防御性可选。
- `GlobalScope.launch`（OppoSceneKey.kt:65）生命周期问题：Meteor 报告 #3 已列为后续独立项，维持不动。
- 工具坑（审查过程中踩到，供团队知悉）：本仓库 .gitignore 的裸 `com/` 规则曾让 ripgrep 系工具（grep/glob）从 `app/src/main` 以上层级搜索时**静默跳过** `java/com/` 下全部文件；仓库级内容搜索请用 `Select-String` 或把搜索根定到 com 目录之下。（规则修复后此坑失效，留档备查。）

## 复核状态跟踪

| 项 | 状态 | 证据 |
|---|---|---|
| F1 12 文件补录 + 规则修复 | ✅ 已验证 | 见 F1 验收表（2026-10-13） |
| F2 内容入库 | ✅ 已验证 | 同上；fdacc567 空壳信息留痕不追改 |
| 克隆可编译 | ✅ 已验证 | 克隆 751 文件，assembleDebug 3m40s exit 0 |
| 风场景 fade² 淡入观感 | ⏳ 待用户实机 | SCENE_CHECKLIST.md 19/20 组 |
| docs/qa/ 文档入库 | ⏳ 待办（Meteor 提交时顺手 `git add docs/qa`） | 当前工作树 `?? docs/qa/` |

---
*Lens（QA）｜ 初审 2026-10-13 17:50，复核 2026-10-13 19:45 ｜ 边界声明：代码只读，本报告为全部产出*
