# =============================================================================
# Contract drift check: live backend JSON vs the frontend's TypeScript types
# =============================================================================
# The frontend types were transcribed from the OpenAPI document. For endpoints
# that return a typed record that works. For the 21 endpoints that return an
# untyped Map, OpenAPI says only "object" -- so those types were written by hand
# and can drift silently, and a drifted field reads as `undefined` in the UI,
# which in an audit tool looks like "the system has no data" rather than "the
# client asked for the wrong field".
#
# This script asks the running backend for real responses and compares the
# returned keys against the interfaces in frontend/src/api/types.ts. It reports
# keys the server sends that the type does not declare, and fields the type
# declares that the server never sends.
#
# Usage:
#   powershell -File scripts/contract-check.ps1 -Username <u> -Password <p>
# =============================================================================

[CmdletBinding()]
param(
  [string]$Base = 'http://localhost:8080',
  [string]$Username = '',
  [string]$Password = 'DemoOperator!2026x',
  # Without this the script takes the first corpus the account can see, which may
  # be one with no approved knowledge -- in which case every graph endpoint returns
  # an empty array and the run has nothing meaningful to compare. Pass -CorpusId 1
  # (or the seeded corpus) to check a corpus that actually has a graph.
  [long]$CorpusId = 0
)

$ErrorActionPreference = 'Continue'
$script:Pass = 0
$script:Fail = 0
$script:Info = 0

function Say($t, $c = 'Gray') { Write-Host $t -ForegroundColor $c }
function Check($n, $ok, $d = '') {
  if ($ok) { $script:Pass++; Write-Host ("  PASS  {0}" -f $n) -ForegroundColor Green }
  else { $script:Fail++; Write-Host ("  FAIL  {0}  {1}" -f $n, $d) -ForegroundColor Red }
}

$script:RateLimitRetries = 0
function Invoke-Api {
  param([string]$Uri, [hashtable]$Headers, [string]$Method = 'GET', [object]$Body = $null)
  $params = @{ Uri = $Uri; Method = $Method; Headers = $Headers; TimeoutSec = 60 }
  if ($null -ne $Body) { $params.ContentType = 'application/json'; $params.Body = ($Body | ConvertTo-Json -Depth 8) }
  for ($attempt = 1; $attempt -le 6; $attempt++) {
    try { return Invoke-RestMethod @params }
    catch {
      $s = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
      if ($s -eq 429 -and $attempt -lt 6) { $script:RateLimitRetries++; Start-Sleep -Seconds 3; continue }
      return [pscustomobject]@{ __status = $s }
    }
  }
}

# ---- TypeScript interfaces, parsed out of the source ------------------------
$typesPath = Join-Path $PSScriptRoot '..\frontend\src\api\types.ts'
$ts = [System.IO.File]::ReadAllText($typesPath)

function Get-TsFields([string]$name) {
  # Matches `export interface Name ... {` and collects the property names,
  # stopping at the matching closing brace.
  $m = [regex]::Match($ts, "(?s)export\s+interface\s+$name\b[^{]*\{(.*?)\n\}")
  if (-not $m.Success) { return $null }
  $names = @()
  foreach ($line in ($m.Groups[1].Value -split "`n")) {
    $p = [regex]::Match($line, '^\s*([A-Za-z_][A-Za-z0-9_]*)\??\s*:')
    if ($p.Success) { $names += $p.Groups[1].Value }
  }
  return $names
}

function Get-Keys($obj) {
  if ($null -eq $obj) { return @() }
  if ($obj -is [System.Array]) {
    if ($obj.Count -eq 0) { return @('<empty array>') }
    return $obj[0].PSObject.Properties.Name
  }
  return $obj.PSObject.Properties.Name
}

function Get-TsRequiredFields([string]$name) {
  # The fields declared WITHOUT `?`. Under the backend's NON_NULL serialisation
  # an optional field may legitimately be absent, so only required ones can
  # contradict the server.
  $m = [regex]::Match($ts, "(?s)export\s+interface\s+$name\b[^{]*\{(.*?)\n\}")
  if (-not $m.Success) { return $null }
  $names = @()
  foreach ($line in ($m.Groups[1].Value -split "`n")) {
    $p = [regex]::Match($line, '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*:')
    if ($p.Success) { $names += $p.Groups[1].Value }
  }
  return $names
}

Write-Host ''
Write-Host ('=' * 72) -ForegroundColor DarkGray
Write-Host 'CONTRACT DRIFT CHECK' -ForegroundColor White
Write-Host ('=' * 72) -ForegroundColor DarkGray

if (-not $Username) { Say 'no -Username supplied' 'Yellow'; exit 2 }
$login = Invoke-Api -Uri "$Base/api/auth/login" -Headers @{} -Method POST -Body @{ username = $Username; password = $Password }
if ($null -eq $login -or $login.PSObject.Properties.Name -contains '__status') {
  Say 'sign-in failed' 'Red'; exit 2
}
$h = @{ Authorization = "Bearer $($login.accessToken)" }
$corpora = Invoke-Api -Uri "$Base/api/corpora" -Headers $h
if ($CorpusId -gt 0) {
  $corpus = @($corpora) | Where-Object { $_.id -eq $CorpusId } | Select-Object -First 1
  if ($null -eq $corpus) { Say "corpus $CorpusId not visible to $Username" 'Red'; exit 2 }
} else {
  $corpus = @($corpora) | Select-Object -First 1
}
if ($null -eq $corpus) { Say 'no corpus available' 'Yellow'; exit 2 }
$cid = $corpus.id
Say "user=$Username corpus=$cid ('$($corpus.name)')"
if ($CorpusId -eq 0) {
  Say "note: picked the first visible corpus. Pass -CorpusId to check one that has approved knowledge." 'DarkGray'
}

$docs = Invoke-Api -Uri "$Base/api/documents?corpusId=$cid&size=5" -Headers $h
$doc = @($docs.content) | Select-Object -First 1
$docId = if ($null -ne $doc) { $doc.id } else { 0 }

# Each entry: label, live call, TS interface the frontend declares for it.
$cases = @(
  @{ label = 'corpora list';        uri = "$Base/api/corpora";                                             ts = 'Corpus' },
  @{ label = 'corpora statistics';  uri = "$Base/api/corpora/${cid}/statistics";                            ts = 'CorpusStatistics' },
  @{ label = 'approval queue';      uri = "$Base/api/approval-queue?corpusId=${cid}&size=5";               ts = 'ApprovalQueue' },
  @{ label = 'triples list';        uri = "$Base/api/triples?corpusId=${cid}&page=0&size=5";               ts = 'TriplePage' },
  @{ label = 'claims list';         uri = "$Base/api/claims?corpusId=${cid}&page=0&size=5";                ts = 'ClaimPage' },
  @{ label = 'verdicts list';       uri = "$Base/api/verdicts?corpusId=${cid}&page=0&size=5";              ts = 'VerdictPage' },
  @{ label = 'contradictions list'; uri = "$Base/api/contradictions?corpusId=${cid}&size=5";               ts = 'ContradictionPage' },
  @{ label = 'trace list';          uri = "$Base/api/traces?corpusId=${cid}&page=0&size=5";               ts = 'TraceRunPage' },
  @{ label = 'graph view';          uri = "$Base/api/graph/${cid}";                                        ts = 'GraphView' },
  @{ label = 'entities list';       uri = "$Base/api/entities?corpusId=${cid}";                             ts = 'Entity' },
  @{ label = 'pagerank';            uri = "$Base/api/graph/${cid}/pagerank?limit=5";                        ts = 'PageRankRow' },
  @{ label = 'communities';         uri = "$Base/api/graph/${cid}/communities";                             ts = 'CommunityRow' }
)

# The three collection endpoints that previously disagreed with each other.
#
# GET /api/documents returned a bare array, GET /api/admin/users returned a
# hand-built map with a different key set, and the quarantine listing returned
# {total, content}. All three now return PageResponse<T>. Checking the envelope
# here is what stops them drifting apart again: the previous contract run
# reported 18/18 clean while the admin page was reading .length off an object
# and rendering its empty state for every user, because this endpoint was not
# among the cases.
$PageEnvelope = @('content', 'page', 'size', 'totalElements', 'totalPages', 'hasNext')
$pageCases = @(
  @{ label = 'documents page';    uri = "$Base/api/documents?corpusId=${cid}&size=5" },
  @{ label = 'admin users page'; uri = "$Base/api/admin/users?page=0&size=5" }
)
if ($docId -gt 0) {
  $pageCases += @{ label = 'quarantine page'; uri = "$Base/api/documents/${docId}/quarantine?size=5" }
}
foreach ($case in $pageCases) {
  $body = Invoke-Api -Uri $case.uri -Headers $h
  $status = if ($body.PSObject.Properties.Name -contains '__status') { [int]$body.__status } else { 200 }
  if ($status -ne 200) {
    # An endpoint the caller may not reach cannot be checked, and must not be
    # reported as drift. /api/admin/users is ADMIN-gated, so a VERIFIER run sees
    # 403 -- and calling that "missing all six envelope fields" would be a false
    # alarm that trains people to ignore this check.
    Say ("  skip  {0,-22} HTTP {1}, not reachable as this account" -f $case.label, $status) 'DarkGray'
    continue
  }
  $missing = @()
  foreach ($field in $PageEnvelope) {
    if (-not ($body.PSObject.Properties.Name -contains $field)) { $missing += $field }
  }
  if ($missing.Count -eq 0) {
    Check "$($case.label) envelope is the standard page shape" $true "all 6 fields present"
  } else {
    Check "$($case.label) envelope is the standard page shape" $false "missing: $($missing -join ', ')"
  }
}
if ($docId -gt 0) {
  $cases += @(
    @{ label = 'document detail';   uri = "$Base/api/documents/${docId}";                                  ts = 'DocumentRow' },
    @{ label = 'document content';  uri = "$Base/api/documents/${docId}/content";                          ts = 'DocumentContent' },
    @{ label = 'document progress'; uri = "$Base/api/documents/${docId}/progress";                         ts = 'DocumentProgress' },
    @{ label = 'document chunks';   uri = "$Base/api/documents/${docId}/chunks";                          ts = 'DocumentChunk' }
  )
}
$tracePage = Invoke-Api -Uri "$Base/api/traces?corpusId=${cid}&page=0&size=5" -Headers $h
$traceRow = @()
if ($tracePage -and ($tracePage.PSObject.Properties.Name -contains 'content')) {
  $traceRow = @($tracePage.content | Where-Object { $_.status -ne 'RUNNING' } | Select-Object -First 1)
}
if (-not $traceRow) { $traceRow = @($tracePage.content | Select-Object -First 1) }
if ($traceRow.Count -gt 0 -and $null -ne $traceRow[0] -and $traceRow[0].id) {
  $tid = $traceRow[0].id
  $cases += @(
    @{ label = 'trace detail';      uri = "$Base/api/traces/${tid}";                                      ts = 'TraceRunDetail' },
    @{ label = 'trace x-ray';       uri = "$Base/api/traces/${tid}/xray";                                 ts = 'XRayView' }
  )
}

Write-Host ''
Write-Host 'server sends keys the TypeScript type does not declare' -ForegroundColor Cyan
$missingType = @()
foreach ($c in $cases) {
  $live = Invoke-Api -Uri $c.uri -Headers $h
  if ($null -eq $live -or $live.PSObject.Properties.Name -contains '__status') {
    $script:Info++
    Say ("  skip  {0} (HTTP {1})" -f $c.label, $(if ($live) { $live.__status } else { 0 })) 'DarkGray'
    continue
  }
  $declared = Get-TsFields $c.ts
  if ($null -eq $declared) {
    $script:Info++
    Say ("  note  no TS interface named {0} for {1}" -f $c.ts, $c.label) 'DarkGray'
    continue
  }
  # An empty collection is not drift. There is nothing to compare against, so a
  # field check over zero rows can only produce false alarms -- and a check that
  # cries wolf on an empty graph is a check people stop reading.
  #
  # This fired for real: the prose evaluation corpus has no approved triples, so
  # /api/graph/{id}/pagerank correctly returns [], and the run reported "server
  # sends: <empty array>" as drift. The graph being empty is a fact about the
  # corpus, not about the contract.
  if ($live -is [array] -and $live.Count -eq 0) {
    $script:Info++
    Say ("  skip  {0,-22} returned an empty collection; no rows to compare" -f $c.label) 'DarkGray'
    continue
  }
  $actual = Get-Keys $live
  $undeclared = @($actual | Where-Object { $declared -notcontains $_ })
  if ($undeclared.Count -gt 0) {
    $missingType += ("{0}: {1}" -f $c.label, ($undeclared -join ', '))
    Say ("  DRIFT {0,-22} server sends: {1}" -f $c.label, ($undeclared -join ', ')) 'Yellow'
  } else {
    $script:Pass++
    Say ("  ok    {0,-22} {1} field(s) all declared" -f $c.label, $declared.Count) 'DarkGray'
  }
}
if ($missingType.Count -gt 0) { $script:Fail += $missingType.Count }

Write-Host ''
Write-Host 'TypeScript type declares fields the server never sends' -ForegroundColor Cyan
# Only REQUIRED fields count as ghosts.
#
# The backend serialises with NON_NULL, so an optional TS field (`foo?`) being
# absent is the documented contract, not drift -- it means the value was null.
# Flagging those would report every nullable field in the API as a defect and
# bury the ones that matter. A required field (`foo: T`) that the server never
# sends is a genuine contradiction: the type promises it is always present.
$ghost = @()
foreach ($c in $cases) {
  $live = Invoke-Api -Uri $c.uri -Headers $h
  if ($null -eq $live -or $live.PSObject.Properties.Name -contains '__status') { continue }
  $required = Get-TsRequiredFields $c.ts
  if ($null -eq $required) { continue }
  $actual = Get-Keys $live
  if ($actual -contains '<empty array>') { continue }
  $absent = @($required | Where-Object { $actual -notcontains $_ })
  if ($absent.Count -gt 0) {
    $ghost += ("{0}: {1}" -f $c.label, ($absent -join ', '))
    Say ("  GHOST {0,-22} required but never sent: {1}" -f $c.label, ($absent -join ', ')) 'Yellow'
  }
}
if ($ghost.Count -gt 0) { $script:Fail += $ghost.Count }

Write-Host ''
Write-Host ('=' * 72) -ForegroundColor DarkGray
if ($script:Fail -eq 0) {
  Write-Host ("CONTRACT CHECK CLEAN   {0} endpoint(s) agreed, {1} skipped" -f $script:Pass, $script:Info) -ForegroundColor Green
  exit 0
} else {
  Write-Host ("CONTRACT CHECK FOUND DRIFT   {0} clean, {1} problem(s)" -f $script:Pass, $script:Fail) -ForegroundColor Red
  exit 1
}