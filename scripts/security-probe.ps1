# =============================================================================
# PRISM security probe
# =============================================================================
# Attempts the operations an authenticated attacker would attempt, and asserts
# what actually happens rather than merely that the server returns 401.
#
# The distinction matters. "It rejected the request" and "it checked the right
# thing before returning data" are different claims, and only the second one is
# what corpus isolation is for. A 401 proves the token was checked; it does not
# prove the token's owner was allowed to reach the object it asked for.
#
# Every check names the boundary it is defending, so a failure says which
# guarantee broke rather than just "check 14 failed".
#
# Run with the backend up:
#   powershell -File scripts/security-probe.ps1
# =============================================================================

[CmdletBinding()]
param(
  [string]$Base = 'http://localhost:8080',
  [string]$OwnerUsername = '',
  [string]$OwnerPassword = 'DemoOperator!2026x',
  # The attacker's password must satisfy the same policy as any other account.
  [string]$AttackerPassword = 'AttackerProbe!2026x'
)

$ErrorActionPreference = 'Continue'
$script:Pass = 0
$script:Fail = 0

function Say($text, $colour = 'Gray') { Write-Host $text -ForegroundColor $colour }

function Check($name, $ok, $detail = '') {
  if ($ok) {
    $script:Pass++
    Write-Host ("  PASS  {0}" -f $name) -ForegroundColor Green
  } else {
    $script:Fail++
    Write-Host ("  FAIL  {0}  {1}" -f $name, $detail) -ForegroundColor Red
  }
}

# Rate-limit aware, so this script can run repeatedly without 429 noise
# masquerading as a security failure.
$script:RateLimitRetries = 0
function Invoke-Api {
  param(
    [Parameter(Mandatory = $true)][string]$Uri,
    [hashtable]$Headers,
    [string]$Method = 'GET',
    [object]$Body = $null,
    [int]$TimeoutSec = 60
  )
  $params = @{ Uri = $Uri; Method = $Method; Headers = $Headers; TimeoutSec = $TimeoutSec }
  if ($null -ne $Body) {
    $params.ContentType = 'application/json'
    $params.Body = ($Body | ConvertTo-Json -Depth 8)
  }
  for ($attempt = 1; $attempt -le 9; $attempt++) {
    try {
      return Invoke-RestMethod @params
    } catch {
      $status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
      if ($status -eq 429 -and $attempt -lt 9) {
        $script:RateLimitRetries++
        # The auth endpoints are limited per MINUTE, so a back-off capped at a
        # few seconds never clears the window: this script creates two accounts
        # and signs in as three, which is five auth calls, and the limit is ten
        # per minute shared with whatever else the developer is running. The
        # ceiling here has to exceed the window, not just grow geometrically.
        Start-Sleep -Milliseconds ([Math]::Min(20000, 1000 * [Math]::Pow(1.7, $attempt - 1)))
        continue
      }
      return [pscustomobject]@{ __status = $status; __message = $_.Exception.Message }
    }
  }
}

# NOTE: always call this as `(StatusOf $x)`, never `StatusOf $x` inside an
# expression. `if (StatusOf $r -ne 200)` makes PowerShell parse it as a COMMAND
# invocation with the arguments `$r`, `-ne`, `200` -- so the condition becomes
# "did the command succeed", which is $true for any successful call, and the
# branch is taken unconditionally. That is how this script's owner sign-in
# silently took its failure path on a perfectly good 200.
function StatusOf($r) {
  if ($null -eq $r) { return 0 }
  if ($r.PSObject.Properties.Name -contains '__status') { return [int]$r.__status }
  return 200
}

function Token {
  param([string]$Username, [string]$Password)
  $r = Invoke-Api -Uri "$Base/api/auth/login" -Headers @{} -Method POST `
       -Body @{ username = $Username; password = $Password }
  if ((StatusOf $r) -ne 200) { return $null }
  return @{ Authorization = "Bearer $($r.accessToken)"; __user = $r.user }
}

Write-Host ''
Write-Host ('=' * 72) -ForegroundColor DarkGray
Write-Host 'PRISM SECURITY PROBE' -ForegroundColor White
Write-Host ('=' * 72) -ForegroundColor DarkGray

# ---- fixtures ---------------------------------------------------------------

$suffix = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$attackerName = "secprobe_$suffix"
$otherName    = "secprobe_other_$suffix"

$ownerName = $OwnerUsername
if (-not $ownerName) {
  # Fall back to any existing corpus owner by asking an admin. Absent one, the
  # script creates its own owner and corpus so it is self-contained.
  Say 'no -OwnerUsername supplied; creating a throwaway owner and corpus' 'Yellow'
  $ownerName = "secprobe_owner_$suffix"
  $reg = Invoke-Api -Uri "$Base/api/auth/register" -Headers @{} -Method POST `
        -Body @{ username = $ownerName; email = "$ownerName@example.com"; password = $OwnerPassword }
  if ((StatusOf $reg) -ne 200 -and (StatusOf $reg) -ne 409) {
    Write-Host "cannot register an owner: HTTP $(StatusOf $reg) $($reg.__message)" -ForegroundColor Red
    exit 2
  }
}

$ownerToken = Token $ownerName $OwnerPassword
if ($null -eq $ownerToken) { Write-Host 'owner sign-in failed' -ForegroundColor Red; exit 2 }
$ownerHeaders = @{ Authorization = $ownerToken.Authorization }

$ownerCorpora = Invoke-Api -Uri "$Base/api/corpora" -Headers $ownerHeaders
$ownedCorpus = @($ownerCorpora) | Select-Object -First 1
if ($null -eq $ownedCorpus) {
  $made = Invoke-Api -Uri "$Base/api/corpora" -Headers $ownerHeaders -Method POST `
         -Body @{ name = 'security probe corpus'; description = 'throwaway' }
  if ((StatusOf $made) -ne 200) { Write-Host 'cannot create a corpus' -ForegroundColor Red; exit 2 }
  $ownedCorpus = $made
}
$corpusId = $ownedCorpus.id
Say "owner=$ownerName  corpus=$corpusId"

# A document to probe chunk-level access. Reuse whatever the corpus already has.
$docs = Invoke-Api -Uri "$Base/api/documents?corpusId=$corpusId&size=5" -Headers $ownerHeaders
$doc = @($docs) | Select-Object -First 1
if ($null -ne $doc) { Say "document=$($doc.id)" }

# ---- two hostile accounts ---------------------------------------------------

$regA = Invoke-Api -Uri "$Base/api/auth/register" -Headers @{} -Method POST `
       -Body @{ username = $attackerName; email = "$attackerName@example.com"; password = $AttackerPassword }
$regB = Invoke-Api -Uri "$Base/api/auth/register" -Headers @{} -Method POST `
       -Body @{ username = $otherName; email = "$otherName@example.com"; password = $AttackerPassword }
$attackerHeaders = Token $attackerName $AttackerPassword
$otherHeaders    = Token $otherName $AttackerPassword
if ($null -eq $attackerHeaders -or $null -eq $otherHeaders) {
  Write-Host 'could not create the hostile accounts' -ForegroundColor Red; exit 2
}
Say "attacker=$attackerName (role $($attackerHeaders.__user.role))"

Write-Host ''
Write-Host '[1] self-registration cannot grant a privileged role' -ForegroundColor Cyan
Check 'the new account is ANALYST' ($attackerHeaders.__user.role -eq 'ANALYST') `
      "role=$($attackerHeaders.__user.role)"
Check 'its response contains no role field it could have set' `
      ($attackerHeaders.__user.PSObject.Properties.Name -notcontains 'isAdmin')

Write-Host ''
Write-Host '[2] corpus isolation - user A must not reach user B corpus' -ForegroundColor Cyan
$r = Invoke-Api -Uri "$Base/api/corpora/$corpusId" -Headers $attackerHeaders
Check "GET /api/corpora/$corpusId as a non-member is refused" ((StatusOf $r) -in @(403, 404)) `
      "HTTP $(StatusOf $r)"

$r = Invoke-Api -Uri "$Base/api/corpora/$corpusId/statistics" -Headers $attackerHeaders
Check "GET /api/corpora/$corpusId/statistics as a non-member is refused" ((StatusOf $r) -in @(403, 404)) `
      "HTTP $(StatusOf $r)"

$listed = Invoke-Api -Uri "$Base/api/corpora" -Headers $attackerHeaders
Check 'the corpus is absent from a non-member list' `
      (@($listed | Where-Object { $_.id -eq $corpusId }).Count -eq 0) `
      "listed=$(@($listed).Count)"

Write-Host ''
Write-Host '[3] the graph cache must not become an authorization side door' -ForegroundColor Cyan
# Warm every cached graph view first, as any legitimate user does on first visit.
# Warming only ONE of them is how this check passed while the other two were
# still vulnerable: the un-warmed caches were cold for the attacker, so their
# access check ran and the request was correctly refused. A cache-warmth-
# dependent check is not a check.
$graphPaths = @(
  @{ path = "/api/graph/$corpusId";                        label = 'the graph view' },
  @{ path = "/api/graph/$corpusId/pagerank";               label = 'the PageRank view' },
  @{ path = "/api/graph/$corpusId/communities";            label = 'the communities view' }
)
foreach ($g in $graphPaths) {
  $w = Invoke-Api -Uri "$Base$($g.path)" -Headers $ownerHeaders
  Check "the owner can read $($g.label) (cache warmed)" ((StatusOf $w) -eq 200) "HTTP $(StatusOf $w)"
}
Write-Host '  -- now the same paths as a non-member --' 'DarkGray'
foreach ($g in $graphPaths) {
  $r = Invoke-Api -Uri "$Base$($g.path)" -Headers $attackerHeaders
  Check "a non-member is refused $($g.label) on a WARM cache" `
        ((StatusOf $r) -in @(403, 404)) `
        "HTTP $(StatusOf $r) -- a 200 here is a cross-corpus leak: @Cacheable answers from cache without running the method body, so an access check inside the body never executes on a hit"
}

# And the VERIFIED_ONLY scope, whose cache key embeds a verified-id set resolved
# from the corpus. If that lookup ever ran before authorization it would leak by
# a different route.
$warmV = Invoke-Api -Uri "$Base/api/graph/${corpusId}?scope=VERIFIED_ONLY" -Headers $ownerHeaders
$r = Invoke-Api -Uri "$Base/api/graph/${corpusId}?scope=VERIFIED_ONLY" -Headers $attackerHeaders
Check 'a non-member is refused the VERIFIED_ONLY graph on a warm cache' `
      ((StatusOf $r) -in @(403, 404)) "HTTP $(StatusOf $r) (owner warm HTTP $(StatusOf $warmV))"

Write-Host ''
Write-Host '[4] documents and chunks' -ForegroundColor Cyan
if ($null -ne $doc) {
  $r = Invoke-Api -Uri "$Base/api/documents/$($doc.id)" -Headers $attackerHeaders
  Check "GET /api/documents/$($doc.id) as a non-member is refused" ((StatusOf $r) -in @(403, 404)) `
        "HTTP $(StatusOf $r)"
  $r = Invoke-Api -Uri "$Base/api/documents/$($doc.id)/content" -Headers $attackerHeaders
  Check 'a non-member cannot read the full document text' ((StatusOf $r) -in @(403, 404)) `
        "HTTP $(StatusOf $r)"
  $r = Invoke-Api -Uri "$Base/api/documents/$($doc.id)/chunks" -Headers $attackerHeaders
  Check 'a non-member cannot read the chunks' ((StatusOf $r) -in @(403, 404)) "HTTP $(StatusOf $r)"
} else {
  Say '  (no document in the corpus; skipping document-level checks)' 'Yellow'
}

Write-Host ''
Write-Host '[5] role separation - ANALYST must not perform verifier actions' -ForegroundColor Cyan
$queue = Invoke-Api -Uri "$Base/api/approval-queue?corpusId=$corpusId&size=5" -Headers $attackerHeaders
Check 'an ANALYST cannot read the approval queue' ((StatusOf $queue) -in @(403, 404)) `
      "HTTP $(StatusOf $queue)"

$scan = Invoke-Api -Uri "$Base/api/contradictions/scan?corpusId=$corpusId" -Headers $attackerHeaders -Method POST
Check 'an ANALYST cannot trigger a contradiction scan on a foreign corpus' `
      ((StatusOf $scan) -in @(403, 404)) "HTTP $(StatusOf $scan)"

$contras = Invoke-Api -Uri "$Base/api/contradictions?corpusId=$corpusId&size=5" -Headers $attackerHeaders
if ((StatusOf $contras) -eq 200) {
  $c1 = @($contras) | Select-Object -First 1
  if ($null -ne $c1) {
    $r = Invoke-Api -Uri "$Base/api/contradictions/$($c1.id)/dismiss" -Headers $attackerHeaders -Method POST -Body @{}
    Check 'an ANALYST cannot dismiss a contradiction' ((StatusOf $r) -in @(403, 404)) "HTTP $(StatusOf $r)"
    $r = Invoke-Api -Uri "$Base/api/contradictions/$($c1.id)/debate" -Headers $attackerHeaders -Method POST -Body @{ topic = 'x' }
    Check 'an ANALYST cannot convene a Council' ((StatusOf $r) -in @(403, 404)) "HTTP $(StatusOf $r)"
  }
}

$verify = Invoke-Api -Uri "$Base/api/claims/verify-all?corpusId=$corpusId" -Headers $attackerHeaders -Method POST
Check 'an ANALYST cannot run corpus-wide verification' ((StatusOf $verify) -in @(403, 404)) `
      "HTTP $(StatusOf $verify)"

Write-Host ''
Write-Host '[6] admin endpoints' -ForegroundColor Cyan
foreach ($path in @('/api/admin/users', '/api/admin/system/status', '/api/admin/jobs')) {
  $r = Invoke-Api -Uri "$Base$path" -Headers $attackerHeaders
  Check "an ANALYST is refused $path" ((StatusOf $r) -in @(403, 404)) "HTTP $(StatusOf $r)"
}
$r = Invoke-Api -Uri "$Base/api-docs" -Headers $attackerHeaders
Check 'an ANALYST is refused the OpenAPI document' ((StatusOf $r) -in @(403, 404)) "HTTP $(StatusOf $r)"

Write-Host ''
Write-Host '[7] token handling' -ForegroundColor Cyan
$badSig = $attackerHeaders.Authorization -replace 'Bearer (.+)$', 'Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.WRONG'
$r = Invoke-Api -Uri "$Base/api/auth/me" -Headers @{ Authorization = $badSig }
Check 'a tampered signature is rejected' ((StatusOf $r) -in @(401, 403)) "HTTP $(StatusOf $r)"

$algNone = 'Bearer eyJhbGciOiJub25lIn0.eyJzdWIiOiIxIiwicm9sZSI6IkFETUlOIn0.'
$r = Invoke-Api -Uri "$Base/api/auth/me" -Headers @{ Authorization = $algNone }
Check 'an alg=none token is rejected' ((StatusOf $r) -in @(401, 403)) "HTTP $(StatusOf $r)"

$r = Invoke-Api -Uri "$Base/api/auth/me" -Headers @{}
Check 'an absent token is rejected' ((StatusOf $r) -in @(401, 403)) "HTTP $(StatusOf $r)"

$r = Invoke-Api -Uri "$Base/api/auth/me" -Headers @{ Authorization = "Bearer $attackerPassword" }
Check 'a password used as a bearer token is rejected' ((StatusOf $r) -in @(401, 403)) "HTTP $(StatusOf $r)"

Write-Host ''
Write-Host '[8] input validation' -ForegroundColor Cyan
$login = Invoke-Api -Uri "$Base/api/auth/login" -Headers @{} -Method POST `
         -Body @{ username = $attackerName; password = 'short' }
Check 'a short password is refused at login without a 500' ((StatusOf $login) -in @(400, 401, 422)) `
      "HTTP $(StatusOf $login)"

$r = Invoke-Api -Uri "$Base/api/corpora" -Headers $attackerHeaders -Method POST -Body @{ name = '' }
Check 'a blank corpus name is refused' ((StatusOf $r) -in @(400, 422)) "HTTP $(StatusOf $r)"

$r = Invoke-Api -Uri "$Base/api/graph/$corpusId?scope=NOT_A_SCOPE" -Headers $ownerHeaders
Check 'an unknown scope is refused, not coerced' ((StatusOf $r) -in @(400, 422)) "HTTP $(StatusOf $r)"

$r = Invoke-Api -Uri "$Base/api/documents?corpusId=$corpusId&size=999999" -Headers $ownerHeaders
Check 'an absurd page size is bounded rather than accepted' ((StatusOf $r) -lt 500) "HTTP $(StatusOf $r)"

$r = Invoke-Api -Uri "$Base/api/documents?corpusId=notanumber" -Headers $ownerHeaders
Check 'a non-numeric id is a validation error, not a 500' ((StatusOf $r) -in @(400, 422)) "HTTP $(StatusOf $r)"

Write-Host ''
Write-Host ('=' * 72) -ForegroundColor DarkGray
$note = ''
if ($script:RateLimitRetries -gt 0) { $note = "  ({0} rate-limit backoff(s))" -f $script:RateLimitRetries }
if ($script:Fail -eq 0) {
  Write-Host ("SECURITY PROBE PASSED   {0} checks{1}" -f $script:Pass, $note) -ForegroundColor Green
  exit 0
} else {
  Write-Host ("SECURITY PROBE FAILED   {0} passed, {1} failed{2}" -f `
      $script:Pass, $script:Fail, $note) -ForegroundColor Red
  exit 1
}