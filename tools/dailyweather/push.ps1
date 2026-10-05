# One-shot push for the dailyweather repo: pull the token from the Windows Credential
# Manager (into a memory variable only — never a file, never printed) and run the
# independent API push script.
param(
  [string]$To = '',
  [string]$From = '',
  [string]$Repo = 'Qianzln/dailyweather',
  [switch]$ForceAll
)
$ErrorActionPreference = 'Stop'

$tok = & (Join-Path $PSScriptRoot 'read_token.ps1')
if (-not $tok) { Write-Host 'NO TOKEN: no GitHub credential in the Credential Manager'; exit 2 }

$env:GH_TOKEN = $tok
$env:GH_REPO = $Repo
if ($To)     { $env:GH_TO = $To }
if ($From)   { $env:GH_FROM = $From }
if ($ForceAll) { $env:GH_FORCE_ALL = '1' } else { Remove-Item env:GH_FORCE_ALL -ErrorAction SilentlyContinue }

$node = Get-Command node -ErrorAction SilentlyContinue
if (-not $node) { Write-Host 'node not found on PATH'; exit 3 }

$script = Join-Path $PSScriptRoot 'gh_push.mjs'
& node $script
exit $LASTEXITCODE
