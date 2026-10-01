# Focused probe: exercise one endpoint and print the exact status and body.
# Uses Invoke-WebRequest via a helper that survives PowerShell 5.1's
# non-interactive-mode quirk around error responses.
param(
  [Parameter(Mandatory = $true)][string]$Username,
  [Parameter(Mandatory = $true)][string]$Password,
  # Requests separated by ';;' so each stays one argument regardless of spaces:
  #   "GET /api/corpora/14;;POST /api/triples/1/approve {`"note`":`"x`"}"
  [Parameter(Mandatory = $true)][string]$Requests
)

$ErrorActionPreference = 'Continue'
$Base = 'http://localhost:8080'

$body = @{ username = $Username; password = $Password } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri "$Base/api/auth/login" `
  -ContentType 'application/json' -Body $body -TimeoutSec 20
$headers = @{ Authorization = "Bearer $($login.accessToken)" }
Write-Host "signed in as $($login.user.username) (id $($login.user.id), role $($login.user.role))"

foreach ($req in ($Requests -split ';;')) {
  $req = $req.Trim()
  if (-not $req) { continue }
  $parts = $req -split ' ', 3
  $method = $parts[0]
  $path = $parts[1]
  $payload = if ($parts.Count -gt 2) { $parts[2] } else { $null }

  try {
    if ($method -eq 'GET') {
      $resp = Invoke-RestMethod -Method Get -Uri "$Base$path" -Headers $headers -TimeoutSec 30
    } elseif ($null -ne $payload) {
      $resp = Invoke-RestMethod -Method $method -Uri "$Base$path" -Headers $headers `
        -ContentType 'application/json' -Body $payload -TimeoutSec 60
    } else {
      $resp = Invoke-RestMethod -Method $method -Uri "$Base$path" -Headers $headers -TimeoutSec 60
    }
    $text = $resp | ConvertTo-Json -Depth 4 -Compress
    Write-Host "$method $path -> 200" -ForegroundColor Green
    Write-Host ("    " + $text.Substring(0, [Math]::Min(400, $text.Length))) -ForegroundColor DarkGray
  } catch {
    $status = 'ERR'
    $text = $_.Exception.Message
    if ($_.Exception.Response) {
      $status = [int]$_.Exception.Response.StatusCode
      try {
        $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
        $text = $reader.ReadToEnd()
      } catch { }
    }
    Write-Host "$method $path -> $status" -ForegroundColor Red
    Write-Host ("    " + $text) -ForegroundColor DarkGray
  }
}
