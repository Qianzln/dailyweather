# ASCII-only. Upload the dailyweather release APK to GitHub Releases (token pulled from the
# Windows Credential Manager into a memory variable only — never a file, never printed).
param(
  [Parameter(Mandatory)][string]$Tag,
  [Parameter(Mandatory)][string]$Apk,
  [string]$Notes = '',
  [string]$Repo = 'Qianzln/dailyweather'
)
$ErrorActionPreference = 'Stop'

$tok = & (Join-Path $PSScriptRoot 'read_token.ps1')
if (-not $tok) { Write-Host 'NO TOKEN: no GitHub credential in the Credential Manager'; exit 2 }

$env:GH_TOKEN = $tok
$env:GH_REPO = $Repo
$env:GH_TAG = $Tag
$env:GH_APK = $Apk
if ($Notes) { $env:GH_NOTES = $Notes } else { Remove-Item env:GH_NOTES -ErrorAction SilentlyContinue }

$node = Get-Command node -ErrorAction SilentlyContinue
if (-not $node) { Write-Host 'node not found on PATH'; exit 3 }

& node (Join-Path $PSScriptRoot 'upload_release.mjs')
exit $LASTEXITCODE
