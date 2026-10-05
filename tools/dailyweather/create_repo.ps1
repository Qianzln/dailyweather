# ASCII-only. Create the dailyweather GitHub repo WITH auto_init so its git backend is
# immediately usable by the GitHub Git Data API. (A repo created without auto_init stays
# "empty" — every git Data API endpoint returns 409 'Git Repository is empty', and the
# Contents API 404s on it, so the API-based push has nothing to hook onto.)
# Token is pulled from the Windows Credential Manager into a memory variable only.
param([string]$Repo = 'Qianzln/dailyweather', [switch]$Public, [string]$Description = 'Daily Weather (每日天气) Android app — 1:1 South-Wind replica')
$ErrorActionPreference = 'Stop'
$tok = & (Join-Path $PSScriptRoot 'read_token.ps1')
if (-not $tok) { Write-Host 'NO TOKEN'; exit 2 }
$H = @{ Authorization = ('Bearer ' + $tok); 'User-Agent' = 'dailyweather-create'; 'Accept' = 'application/vnd.github+json' }

$existing = try { (Invoke-WebRequest ('https://api.github.com/repos/' + $Repo) -Headers $H -UseBasicParsing) } catch { $null }
if ($existing) {
  $r = $existing.Content | ConvertFrom-Json
  Write-Host ('repo ' + $Repo + ' already exists default=' + $r.default_branch + ' empty=' + $r.empty + ' — nothing to do')
  exit 0
}

Write-Host ('creating ' + $Repo + ' (auto_init) ...')
$body = @{ name = ($Repo.Split('/')[1]); private = (-not $Public); auto_init = $true; description = $Description; gitignores = 'Android' } | ConvertTo-Json
$resp = Invoke-WebRequest 'https://api.github.com/user/repos' -Method POST -Headers $H -Body $body -UseBasicParsing
$repo = $resp.Content | ConvertFrom-Json
Write-Host ('created ' + $repo.full_name + ' default=' + $repo.default_branch + ' empty=' + $repo.empty + ' private=' + $repo.private)
Write-Host ('clone_url = ' + $repo.clone_url)
