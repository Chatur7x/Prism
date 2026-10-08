# Finish what seed-pdf-demo.ps1 could not: approve remaining claims, verify, scan.
# The seeder's approve loop counts a 429 as "refused" without retry, and its
# 1h JWT expired before verify/scan. This paces approvals slowly with backoff
# and re-logs in when the token dies, then runs verify-all and the scan.
[CmdletBinding()]
param(
  [string]$Base = 'http://localhost:8080',
  [string]$Username = '',
  [string]$Password = '',
  [long]$CorpusId = 1,
  [int]$Verifications = 60,
  [int]$GapMs = 4000
)

function Say($t, $c = 'Gray') { Write-Host $t -ForegroundColor $c }
function Req($Uri, $Headers, $Method = 'GET', $Body = $null) {
  $p = @{ Uri = $Uri; Method = $Method; Headers = $Headers; TimeoutSec = 120 }
  if ($null -ne $Body) { $p.ContentType = 'application/json'; $p.Body = ($Body | ConvertTo-Json -Compress) }
  try { return @{ ok = $true; data = (Invoke-RestMethod @p) } }
  catch {
    $s = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
    return @{ ok = $false; status = $s; msg = $_.Exception.Message }
  }
}
function SignIn {
  $r = Req "$Base/api/auth/login" @{} 'POST' @{ username = $Username; password = $Password }
  if (-not $r.ok) { Say "login failed: $($r.msg)" 'Red'; exit 1 }
  return @{ Authorization = "Bearer $($r.data.accessToken)" }
}

$h = SignIn
$approved = 0; $refused = @()
for ($round = 0; $round -lt 40; $round++) {
  $q = Req "$Base/api/approval-queue?corpusId=$CorpusId&size=300" $h
  if (-not $q.ok) { $h = SignIn; continue }
  $claims = @($q.data.claims)
  if ($claims.Count -eq 0) { break }
  Say "round ${round}: $($claims.Count) claims pending"
  foreach ($c in $claims) {
    $r = Req "$Base/api/claims/$($c.id)/approve" $h 'POST' @{}
    if ($r.ok) { $approved++ }
    elseif ($r.status -eq 429) { Start-Sleep -Milliseconds 8000; $r2 = Req "$Base/api/claims/$($c.id)/approve" $h 'POST' @{}; if ($r2.ok) { $approved++ } else { $refused += $c.id } }
    elseif ($r.status -eq 401) { $h = SignIn; $r3 = Req "$Base/api/claims/$($c.id)/approve" $h 'POST' @{}; if ($r3.ok) { $approved++ } else { $refused += $c.id } }
    else { $refused += $c.id; Say "claim $($c.id) refused: $($r.status) $($r.msg)" 'Yellow' }
    Start-Sleep -Milliseconds $GapMs
  }
}
Say "approved=$approved refused=$($refused.Count)" 'Cyan'

$v = Req "$Base/api/claims/verify-all?corpusId=$CorpusId&limit=$Verifications" $h 'POST'
if (-not $v.ok -and $v.status -eq 401) { $h = SignIn; $v = Req "$Base/api/claims/verify-all?corpusId=$CorpusId&limit=$Verifications" $h 'POST' }
Say ("verify-all: " + ($v.data | ConvertTo-Json -Compress -Depth 3)) 'Cyan'

$s = Req "$Base/api/contradictions/scan?corpusId=$CorpusId" $h 'POST'
if (-not $s.ok -and $s.status -eq 401) { $h = SignIn; $s = Req "$Base/api/contradictions/scan?corpusId=$CorpusId" $h 'POST' }
Say ("scan: " + ($s.data | ConvertTo-Json -Compress -Depth 3)) 'Cyan'
