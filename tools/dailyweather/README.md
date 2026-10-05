# 每日天气 · GitHub 推送工具（独立于微云天气）

本目录是**每日天气（`com.dailyweather.app`）自己的**推送工具，目标仓库
`Qianzln/dailyweather`，与微云天气（`Qianzln/weather-ui` + `D:\DEV\_gh_*.mjs`）完全独立，
不复用其仓库、脚本或凭据目标。

## 为什么走 GitHub API 而不是 `git push`

本机出口代理会间歇性拒绝到 `github.com:443` 的 git 协议隧道（502），`git push` 不可用；
`api.github.com` 可达。所以推送走 **GitHub Git Data API**：把本地 commit 链重放到远端，
逐 commit 复原其 tree/commit（保留原 author/committer 身份与时间戳），使每个 SHA 精确复现；
远端 ref 因此保持 fast-forward，本地与远端完全一致。

## 凭据

token 从 **Windows 凭据管理器** 读取（目标 `GitHub - https://api.github.com/Qianzln`，
`repo,user,workflow` 权限），**从不写进任何文件、也不打印**：

- `read_token.ps1` — 从凭据管理器取 token 值到 stdout（供调用方捕获进内存变量）
- 注入方式是 `push.ps1` 把 token 设进**进程环境变量** `GH_TOKEN`，`gh_push.mjs` 只读
  `process.env.GH_TOKEN`。token 全程只在内存。

## 用法

```powershell
# 增量推送（本地 HEAD 领先远端 main 的 commit）
powershell -ExecutionPolicy Bypass -File tools/dailyweather/push.ps1

# 全链覆盖（replay 本地全部 commit 并 force main；用于新建仓库后首次推送）
powershell -ExecutionPolicy Bypass -File tools/dailyweather/push.ps1 -ForceAll

# 需要时指定 tip/base
powershell -ExecutionPolicy Bypass -File tools/dailyweather/push.ps1 -To <sha> -From <sha>
```

## 新建仓库

```powershell
powershell -ExecutionPolicy Bypass -File tools/dailyweather/create_repo.ps1          # 默认 Qianzln/dailyweather
powershell -ExecutionPolicy Bypass -File tools/dailyweather/create_repo.ps1 -Repo Qianzln/x -Public
```

> `create_repo.ps1` 以 `auto_init=true` 建库。若不带 `auto_init`，仓库 git 后端保持
> "empty" 状态，所有 Git Data API 端点返回 `409 Git Repository is empty`、Contents API
> 返回 404，推送脚本无锚点可用。务必用 auto_init。

## 行为与限制

- 每个 SHA 复现后校验一致才移动/创建 ref；任何一个不一致即拒绝（`exit 6`）。
- 增量模式要求远端 `main` 是本地 tip 的祖先，否则拒绝（需要 force 的场景用 `-ForceAll`）。
- 大 commit（>100 文件）自动把 tree 分批（≤100 条目/请求）累积到最终 tree，绕过
  git/trees API 单请求上限。
- 首次（`-ForceAll`）会把 `auto_init` 建库产生的初始 commit 用本地全链覆盖掉，
  最终 `main` 精确等于本地 HEAD。
