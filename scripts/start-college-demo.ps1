<#
One-command college demo startup. Checks only: Docker, MySQL, backend,
frontend, Ollama + model, auth, corpus 20, corpus-1 graph fallback,
debate 4, report 1, chat, Glass Box. Prints READY / PARTIAL / BLOCKED.

Thin wrapper over scripts/showcase-healthcheck.ps1 with demo-friendly
output and no inference. Never deletes volumes, never overwrites data,
never prints credentials or tokens.
#>
param(
  [string]$Username = '',
  [string]$Password = ''
)

$ErrorActionPreference = 'Continue'
if ([string]::IsNullOrWhiteSpace($Username) -or [string]::IsNullOrWhiteSpace($Password)) {
  Write-Host 'Supply -Username and -Password (a VERIFIER account).' -ForegroundColor Red
  exit 2
}

$root = Split-Path -Parent $PSScriptRoot
Write-Host ''
Write-Host 'PRISM college demo startup' -ForegroundColor White
Write-Host '==========================' -ForegroundColor DarkGray

Write-Host '[1/3] Docker stack...' -ForegroundColor Cyan
docker compose --project-directory $root up -d 2>&1 | Select-Object -Last 2 | Out-Null
Start-Sleep -Seconds 20
$up = @(docker ps --format '{{.Names}} {{.Status}}' 2>&1 | Where-Object { $_ -match 'Up' }).Count
Write-Host ("  containers up: {0} (need 3)" -f $up) -ForegroundColor $(if ($up -ge 3) { 'Green' } else { 'Red' })

Write-Host '[2/3] Ollama...' -ForegroundColor Cyan
try {
  $t = Invoke-RestMethod -Uri 'http://127.0.0.1:11435/api/tags' -TimeoutSec 8
  $has = @($t.models | ForEach-Object { $_.name }) -contains 'qwen2.5:7b'
  Write-Host ("  qwen2.5:7b present: {0}" -f $has) -ForegroundColor $(if ($has) { 'Green' } else { 'Yellow' })
} catch { Write-Host '  ollama unreachable (persisted artifacts still demoable)' -ForegroundColor Yellow }

Write-Host '[3/3] Full health check...' -ForegroundColor Cyan
& (Join-Path $root 'scripts\showcase-healthcheck.ps1') -Username $Username -Password $Password
exit $LASTEXITCODE
