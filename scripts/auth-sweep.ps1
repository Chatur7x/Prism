# =============================================================================
# PRISM resource authorization sweep (Phase 9)
# =============================================================================
# Cross-user AND cross-corpus authorization sweep over every per-resource read
# path: direct ID access, search / retrieval / pagination / nested references,
# invalid IDs, unauthorized nested IDs, and cross-corpus citations. Every denied
# response is also scanned for data disclosure (usernames, content text, and
# IDs beyond the one requested).
#
# Read-only against shared data: the only writes are fixture setup (the
# attacker's own corpus/document/session, one owner chat session used as the
# hostile-message target). No scans, no verifications, no adjudications, so a
# run cannot mutate corpus 1's demo data.
#
# Conventions reused from scripts/security-probe.ps1: Invoke-RestMethod based
# calls, Check with pass/fail counts, 429 back-off that exceeds the auth
# per-minute window, and (StatusOf $x) always parenthesised.
#
# Run with the backend up:
#   powershell -File scripts/auth-sweep.ps1 `
#     -OwnerUsername demo_operator_1790944169073 -OwnerPassword 'DemoOperator!2026x' `
#     -AttackerUsername 'sweep_attacker_1' -AttackerPassword 'AttackerSweep!2026x'
# Omit -AttackerUsername to generate a throwaway attacker per run.
# =============================================================================

[CmdletBinding()]
param(
  [string]$Base = 'http://localhost:8080',
  [string]$OwnerUsername = 'demo_operator_1790944169073',
  [string]$OwnerPassword = 'DemoOperator!2026x',
  [string]$AttackerUsername = '',
  [string]$AttackerPassword = 'AttackerSweep!2026x'
)

$ErrorActionPreference = 'Continue'
$script:Pass = 0
$script:Fail = 0
$script:RateLimitRetries = 0
$script:Skipped = 0
$script:ResStats = @{}
$script:SecretsOwner = @()
$script:SecretsAttacker = @()
$script:KnownIds = @()

function Say($text, $colour = 'Gray') { Write-Host $text -ForegroundColor $colour }

function Check($resource, $name, $ok, $detail = '') {
  if (-not $script:ResStats.ContainsKey($resource)) {
    $script:ResStats[$resource] = @{ pass = 0; fail = 0 }
  }
  if ($ok) {
    $script:Pass++
    $script:ResStats[$resource].pass++
    Write-Host ("  PASS  [{0}] {1}" -f $resource, $name) -ForegroundColor Green
  } else {
    $script:Fail++
    $script:ResStats[$resource].fail++
    Write-Host ("  FAIL  [{0}] {1}  {2}" -f $resource, $name, $detail) -ForegroundColor Red
  }
}

function Skip($resource, $name, $reason) {
  $script:Skipped++
  Write-Host ("  SKIP  [{0}] {1}  ({2})" -f $resource, $name, $reason) -ForegroundColor Yellow
}

# Same shape as the probe's Invoke-Api, but also returns the raw body text so
# denial bodies can be scanned for disclosure. Success bodies are re-serialised
# to text for the same scan.
function Invoke-Sweep {
  param(
    [string]$Method = 'GET',
    [Parameter(Mandatory = $true)][string]$Uri,
    [hashtable]$Headers,
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
      $data = Invoke-RestMethod @params
      $text = ''
      try { $text = ($data | ConvertTo-Json -Depth 15 -Compress) } catch { $text = '' }
      if ($null -eq $text) { $text = '' }
      return [pscustomobject]@{ Status = 200; Body = [string]$text; Data = $data }
    } catch {
      $status = 0
      try { if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode } } catch { $status = 0 }
      if ($status -eq 429 -and $attempt -lt 9) {
        $script:RateLimitRetries++
        Start-Sleep -Milliseconds ([Math]::Min(20000, 1000 * [Math]::Pow(1.7, $attempt - 1)))
        continue
      }
      $etext = ''
      try { if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $etext = $_.ErrorDetails.Message } } catch { $etext = '' }
      if (-not $etext) { $etext = [string]$_.Exception.Message }
      return [pscustomobject]@{ Status = $status; Body = $etext; Data = $null }
    }
  }
}

function StatusOf($r) {
  if ($null -eq $r) { return 0 }
  return [int]$r.Status
}

function Denied($r) {
  return ((StatusOf $r) -in @(403, 404))
}

# First secret string found in the body, or $null. Only the matched marker is
# reported, never the surrounding body, so a failure line cannot itself spread
# another user's data into logs.
function Find-Secret($bodyText, $secrets) {
  if ([string]::IsNullOrEmpty($bodyText)) { return $null }
  foreach ($s in $secrets) {
    if ([string]::IsNullOrEmpty($s)) { continue }
    if ($s.Length -lt 4) { continue }
    if ($bodyText.IndexOf($s, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) { return $s }
  }
  return $null
}

# First known ID found in the body that is not in the allowed list, or $null.
# IDs the request itself supplied (the requested resource, the corpus in the
# query string) are exempt: echoing them back is not disclosure.
function Find-ForeignId($bodyText, $allowed) {
  if ([string]::IsNullOrEmpty($bodyText)) { return $null }
  foreach ($id in $script:KnownIds) {
    if ($allowed -contains $id) { continue }
    if ([regex]::IsMatch($bodyText, "(?<!\d)$([regex]::Escape($id))(?!\d)")) { return $id }
  }
  return $null
}

# A denied response must (a) carry a 403/404 and (b) disclose nothing: no
# usernames or content text from $secrets, and no IDs beyond $allowedIds.
function Assert-Denied($resource, $label, $resp, $uri, $secrets, $allowedIds) {
  $denied = Denied $resp
  Check $resource "$label is refused (403/404)" $denied ("HTTP $(StatusOf $resp)")
  if ($denied) {
    $hit = Find-Secret $resp.Body $secrets
    $short = $hit
    if ($null -ne $short -and $short.Length -gt 40) { $short = $short.Substring(0, 40) }
    Check $resource "$label denial discloses no user data" ($null -eq $hit) `
      ("endpoint $uri HTTP $(StatusOf $resp) leaked text starting [$short]")
    $fid = Find-ForeignId $resp.Body $allowedIds
    Check $resource "$label denial discloses no foreign IDs" ($null -eq $fid) `
      ("endpoint $uri HTTP $(StatusOf $resp) leaked id $fid")
  }
}

# A corpus-scoped list as a non-member must either be refused or come back with
# none of the foreign corpus's rows. Any row at all is a leak, because every
# row in a corpus-scoped list belongs to that corpus.
function Assert-ListEmpty($resource, $label, $resp, $uri, $secrets) {
  if ((Denied $resp)) {
    Check $resource "$label as a non-member is refused or empty" $true ("HTTP $(StatusOf $resp)")
    $hit = Find-Secret $resp.Body $secrets
    Check $resource "$label denial body is clean" ($null -eq $hit) ("leaked [$hit]")
    return
  }
  if ((StatusOf $resp) -ne 200) {
    Check $resource "$label as a non-member is refused or empty" $false ("HTTP $(StatusOf $resp)")
    return
  }
  $rows = @()
  if ($null -ne $resp.Data) {
    if ($resp.Data.PSObject.Properties.Name -contains 'content') { $rows = @($resp.Data.content) }
    elseif ($resp.Data -is [System.Array]) { $rows = @($resp.Data) }
  }
  Check $resource "$label as a non-member is refused or empty" ($rows.Count -eq 0) `
    ("HTTP 200 with $($rows.Count) foreign row(s) at $uri")
  if ($rows.Count -gt 0) {
    $hit = Find-Secret $resp.Body $secrets
    Check $resource "$label leaked rows carry no user data" $false ("leaked [$hit]")
  }
}

Write-Host ''
Write-Host ('=' * 72) -ForegroundColor DarkGray
Write-Host 'PRISM RESOURCE AUTHORIZATION SWEEP' -ForegroundColor White
Write-Host ('=' * 72) -ForegroundColor DarkGray

# ---- sign in (3 auth calls total: owner login, attacker register, attacker login)
function Token($Username, $Password) {
  $r = Invoke-Sweep -Method POST -Uri "$Base/api/auth/login" -Headers @{} `
       -Body @{ username = $Username; password = $Password }
  if ((StatusOf $r) -ne 200) { return $null }
  return @{ Authorization = "Bearer $($r.Data.accessToken)"; __user = $r.Data.user }
}

$ownerToken = Token $OwnerUsername $OwnerPassword
if ($null -eq $ownerToken) { Write-Host 'owner sign-in failed' -ForegroundColor Red; exit 2 }
$OH = @{ Authorization = $ownerToken.Authorization }

if (-not $AttackerUsername) {
  $AttackerUsername = "sweep_$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"
}
$reg = Invoke-Sweep -Method POST -Uri "$Base/api/auth/register" -Headers @{} `
       -Body @{ username = $AttackerUsername; email = "$AttackerUsername@example.com"; password = $AttackerPassword }
if (((StatusOf $reg) -ne 200) -and ((StatusOf $reg) -ne 409)) {
  Write-Host "attacker register failed: HTTP $(StatusOf $reg)" -ForegroundColor Red; exit 2
}
$attackerToken = Token $AttackerUsername $AttackerPassword
if ($null -eq $attackerToken) { Write-Host 'attacker sign-in failed' -ForegroundColor Red; exit 2 }
$AH = @{ Authorization = $attackerToken.Authorization }
Say "owner=$OwnerUsername  attacker=$AttackerUsername (role $($attackerToken.__user.role))"

# ---- fixtures: owner corpus 1 resources ------------------------------------
$C1 = '1'
$docs = Invoke-Sweep -Uri "$Base/api/documents?corpusId=$C1&size=5" -Headers $OH
if ((StatusOf $docs) -ne 200 -or @($docs.Data.content).Count -eq 0) {
  Write-Host 'owner has no documents in corpus 1; cannot sweep' -ForegroundColor Red; exit 2
}
$O_DOC = [string](@($docs.Data.content) | Select-Object -First 1).id
$O_DOCTITLE = [string](@($docs.Data.content) | Select-Object -First 1).title

$chunkRows = Invoke-Sweep -Uri "$Base/api/documents/$O_DOC/chunks" -Headers $OH
$O_CHUNK = ''
$O_CHUNK_SNIPPET = ''
if ((StatusOf $chunkRows) -eq 200 -and @($chunkRows.Data).Count -gt 0) {
  $c0 = @($chunkRows.Data) | Select-Object -First 1
  $O_CHUNK = [string]$c0.id
  $ctext = [string]$c0.content
  if ($ctext.Length -gt 40) { $ctext = $ctext.Substring(0, 40) }
  $O_CHUNK_SNIPPET = $ctext
}

$ents = Invoke-Sweep -Uri "$Base/api/entities?corpusId=$C1" -Headers $OH
$O_ENT = ''
$O_ENTNAME = ''
if ((StatusOf $ents) -eq 200 -and @($ents.Data).Count -gt 0) {
  $e0 = @($ents.Data) | Select-Object -First 1
  $O_ENT = [string]$e0.id
  $O_ENTNAME = [string]$e0.displayName
}

$trips = Invoke-Sweep -Uri "$Base/api/triples?corpusId=$C1&size=2" -Headers $OH
$O_TRIP = ''
$O_TRIPSUBJ = ''
if ((StatusOf $trips) -eq 200 -and @($trips.Data.content).Count -gt 0) {
  $t0 = @($trips.Data.content) | Select-Object -First 1
  $O_TRIP = [string]$t0.id
  $O_TRIPSUBJ = [string]$t0.subject
}

$claims = Invoke-Sweep -Uri "$Base/api/claims?corpusId=$C1&size=2" -Headers $OH
$O_CLAIM = ''
$O_CLAIMTEXT = ''
if ((StatusOf $claims) -eq 200 -and @($claims.Data.content).Count -gt 0) {
  $k0 = @($claims.Data.content) | Select-Object -First 1
  $O_CLAIM = [string]$k0.id
  $ktext = [string]$k0.claimText
  if ($ktext.Length -gt 40) { $ktext = $ktext.Substring(0, 40) }
  $O_CLAIMTEXT = $ktext
}

$verds = Invoke-Sweep -Uri "$Base/api/verdicts?corpusId=$C1&size=2" -Headers $OH
$O_VERD = ''
if ((StatusOf $verds) -eq 200 -and @($verds.Data.content).Count -gt 0) {
  $O_VERD = [string](@($verds.Data.content) | Select-Object -First 1).id
}

$contras = Invoke-Sweep -Uri "$Base/api/contradictions?corpusId=$C1&size=5" -Headers $OH
$O_CONTRA = ''
$O_DEBATE = ''
$O_LEFTTRIP = ''
$O_RIGHTTRIP = ''
if ((StatusOf $contras) -eq 200 -and @($contras.Data.content).Count -gt 0) {
  $n0 = @($contras.Data.content) | Select-Object -First 1
  $O_CONTRA = [string]$n0.id
  if ($null -ne $n0.debateId) { $O_DEBATE = [string]$n0.debateId }
  if ($null -ne $n0.leftTripleId) { $O_LEFTTRIP = [string]$n0.leftTripleId }
  if ($null -ne $n0.rightTripleId) { $O_RIGHTTRIP = [string]$n0.rightTripleId }
}

$runs = Invoke-Sweep -Uri "$Base/api/traces?corpusId=$C1&size=2" -Headers $OH
$O_RUN = ''
if ((StatusOf $runs) -eq 200 -and @($runs.Data.content).Count -gt 0) {
  $O_RUN = [string](@($runs.Data.content) | Select-Object -First 1).id
}

# One owner chat session in corpus 1, used as the hostile-message target.
$osess = Invoke-Sweep -Method POST -Uri "$Base/api/chat/sessions" -Headers $OH `
          -Body @{ corpusId = [long]$C1; title = 'sweep fixture session' }
$O_SESS = ''
if ((StatusOf $osess) -eq 200 -or (StatusOf $osess) -eq 201) { $O_SESS = [string]$osess.Data.id }

# Whether the debate has a synthesis report decides if report checks can run.
$ownerReportStatus = 0
if ($O_DEBATE) {
  $rep = Invoke-Sweep -Uri "$Base/api/debates/$O_DEBATE/report" -Headers $OH
  $ownerReportStatus = StatusOf $rep
}
$reportCiteTrip = ''
$reportCiteClaim = ''
$reportCiteVerd = ''
$reportTraceRun = ''
if ($ownerReportStatus -eq 200) {
  try {
    foreach ($b in @($rep.Data.blocks)) {
      foreach ($cit in @($b.citations)) {
        if ((-not $reportCiteTrip) -and $cit.tripleId) { $reportCiteTrip = [string]$cit.tripleId }
        if ((-not $reportCiteClaim) -and $cit.claimId) { $reportCiteClaim = [string]$cit.claimId }
        if ((-not $reportCiteVerd) -and $cit.verdictId) { $reportCiteVerd = [string]$cit.verdictId }
      }
    }
  } catch { }
}
# A report also cites the trace run that produced it. That id is a
# cross-corpus reference into a second resource family.
try { if ($rep.Data.traceRunId) { $reportTraceRun = [string]$rep.Data.traceRunId } } catch { }

# ---- fixtures: attacker-owned corpus (reverse direction) --------------------
$amade = Invoke-Sweep -Method POST -Uri "$Base/api/corpora" -Headers $AH `
         -Body @{ name = 'sweep attacker corpus'; description = 'throwaway' }
$A_CORP = ''
if ((StatusOf $amade) -eq 200 -or (StatusOf $amade) -eq 201) { $A_CORP = [string]$amade.Data.id }
if (-not $A_CORP) {
  $alist = Invoke-Sweep -Uri "$Base/api/corpora" -Headers $AH
  foreach ($cc in @($alist.Data)) {
    if ($cc.name -eq 'sweep attacker corpus') { $A_CORP = [string]$cc.id }
  }
}
if (-not $A_CORP) { Write-Host 'attacker corpus setup failed' -ForegroundColor Red; exit 2 }

$adoc = Invoke-Sweep -Method POST -Uri "$Base/api/documents" -Headers $AH `
        -Body @{ corpusId = [long]$A_CORP; title = 'Sweep Attacker Memo'; contentText = 'Harbor Light Cannery logistics run on tide tables. Gulls audit the pier at dawn.' }
$A_DOC = ''
if ((StatusOf $adoc) -eq 200 -or (StatusOf $adoc) -eq 201) { $A_DOC = [string]$adoc.Data.id }

$asess = Invoke-Sweep -Method POST -Uri "$Base/api/chat/sessions" -Headers $AH `
         -Body @{ corpusId = [long]$A_CORP; title = 'sweep attacker session' }
$A_SESS = ''
if ((StatusOf $asess) -eq 200 -or (StatusOf $asess) -eq 201) { $A_SESS = [string]$asess.Data.id }

Say ("fixtures: ownerDoc={0} chunk={1} entity={2} triple={3} claim={4} verdict={5} contra={6} debate={7} run={8} ownerSess={9} attackerCorpus={10} attackerDoc={11} attackerSess={12} report={13}" -f `
  $O_DOC, $O_CHUNK, $O_ENT, $O_TRIP, $O_CLAIM, $O_VERD, $O_CONTRA, $O_DEBATE, $O_RUN, $O_SESS, $A_CORP, $A_DOC, $A_SESS, $ownerReportStatus)

$script:SecretsOwner = @($OwnerUsername, $O_DOCTITLE, $O_CHUNK_SNIPPET, $O_CLAIMTEXT, $O_ENTNAME, $O_TRIPSUBJ)
$script:SecretsAttacker = @($AttackerUsername, 'Sweep Attacker Memo')
$script:KnownIds = @($O_DOC, $O_CHUNK, $O_ENT, $O_TRIP, $O_CLAIM, $O_VERD, $O_CONTRA, $O_DEBATE, $O_RUN, $O_SESS, $A_CORP, $A_DOC, $A_SESS, $C1, $O_LEFTTRIP, $O_RIGHTTRIP, $reportCiteTrip, $reportCiteClaim, $reportCiteVerd) | Where-Object { $_ -ne '' }
$SO = $script:SecretsOwner
$SA = $script:SecretsAttacker
$BAD = '999999999'

# ---- documents --------------------------------------------------------------
Write-Host ''
Write-Host '[documents] direct, nested, pagination, invalid, reverse' -ForegroundColor Cyan
$r = Invoke-Sweep -Uri "$Base/api/documents/$O_DOC" -Headers $AH
Assert-Denied 'documents' "GET /api/documents/$O_DOC as non-owner" $r "$Base/api/documents/$O_DOC" $SO @($O_DOC, $C1)
$r = Invoke-Sweep -Uri "$Base/api/documents/$O_DOC/content" -Headers $AH
Assert-Denied 'documents' 'GET document content as non-owner' $r "$Base/api/documents/$O_DOC/content" $SO @($O_DOC, $C1)
$r = Invoke-Sweep -Uri "$Base/api/documents?corpusId=$C1" -Headers $AH
Assert-ListEmpty 'documents' 'GET /api/documents?corpusId=1 as non-member (list)' $r "$Base/api/documents?corpusId=1" $SO
$r = Invoke-Sweep -Uri "$Base/api/documents?corpusId=$C1&page=3&size=50" -Headers $AH
Assert-ListEmpty 'documents' 'GET documents page=3 as non-member (pagination)' $r 'paged' $SO
$r = Invoke-Sweep -Uri "$Base/api/documents/$BAD" -Headers $AH
Check 'documents' 'GET invalid document id is 404' ((StatusOf $r) -eq 404) ("HTTP $(StatusOf $r)")
$r = Invoke-Sweep -Uri "$Base/api/documents/notanumber" -Headers $OH
Check 'documents' 'GET non-numeric id is a validation error, not a 500' (((StatusOf $r) -in @(400, 422)) -and ((StatusOf $r) -ne 500)) ("HTTP $(StatusOf $r)")
$r = Invoke-Sweep -Uri "$Base/api/documents/$A_DOC" -Headers $OH
Assert-Denied 'documents' "GET attacker document $A_DOC as owner (reverse)" $r "$Base/api/documents/$A_DOC" $SA @($A_DOC, $A_CORP)
$r = Invoke-Sweep -Uri "$Base/api/documents?corpusId=$A_CORP" -Headers $OH
Assert-ListEmpty 'documents' 'GET attacker corpus documents as owner (reverse list)' $r 'reverse' $SA

# ---- chunks -----------------------------------------------------------------
Write-Host ''
Write-Host '[chunks] nested reads, invalid, reverse, citation' -ForegroundColor Cyan
$r = Invoke-Sweep -Uri "$Base/api/documents/$O_DOC/chunks" -Headers $AH
Assert-Denied 'chunks' 'GET document chunks as non-owner' $r 'chunks' $SO @($O_DOC, $C1, $O_CHUNK)
$r = Invoke-Sweep -Uri "$Base/api/documents/$O_DOC/progress" -Headers $AH
Assert-Denied 'chunks' 'GET document progress as non-owner' $r 'progress' $SO @($O_DOC, $C1)
$r = Invoke-Sweep -Uri "$Base/api/documents/$O_DOC/quarantine" -Headers $AH
Assert-Denied 'chunks' 'GET document quarantine as non-owner' $r 'quarantine' $SO @($O_DOC, $C1)
$r = Invoke-Sweep -Uri "$Base/api/documents/$BAD/chunks" -Headers $AH
Check 'chunks' 'GET chunks of an invalid document id is 404' ((StatusOf $r) -eq 404) ("HTTP $(StatusOf $r)")
$r = Invoke-Sweep -Uri "$Base/api/documents/$A_DOC/chunks" -Headers $OH
Assert-Denied 'chunks' 'GET attacker document chunks as owner (reverse nested)' $r 'reverse-chunks' $SA @($A_DOC, $A_CORP)

# ---- entities ---------------------------------------------------------------
Write-Host ''
Write-Host '[entities] direct, search, invalid, reverse' -ForegroundColor Cyan
if ($O_ENT) {
  $r = Invoke-Sweep -Uri "$Base/api/entities/$O_ENT" -Headers $AH
  Assert-Denied 'entities' "GET /api/entities/$O_ENT as non-owner" $r 'entity' $SO @($O_ENT, $C1)
} else { Skip 'entities' 'direct entity access' 'owner corpus has no entities' }
$r = Invoke-Sweep -Uri "$Base/api/entities?corpusId=$C1" -Headers $AH
Assert-ListEmpty 'entities' 'GET /api/entities?corpusId=1 as non-member' $r 'entities' $SO
$needle = 'a'
if ($O_ENTNAME -and ($O_ENTNAME.Length -ge 4)) { $needle = $O_ENTNAME.Substring(0, 4) }
$r = Invoke-Sweep -Uri "$Base/api/entities?corpusId=$C1&search=$needle" -Headers $AH
Assert-ListEmpty 'entities' 'GET entities search as non-member (retrieval)' $r 'entity-search' $SO
$r = Invoke-Sweep -Uri "$Base/api/entities/$BAD" -Headers $AH
Check 'entities' 'GET invalid entity id is 404' ((StatusOf $r) -eq 404) ("HTTP $(StatusOf $r)")
$r = Invoke-Sweep -Uri "$Base/api/entities?corpusId=$A_CORP" -Headers $OH
Assert-ListEmpty 'entities' 'GET attacker corpus entities as owner (reverse)' $r 'reverse' $SA

# ---- triples ----------------------------------------------------------------
Write-Host ''
Write-Host '[triples] direct, filtered lists, invalid, reverse' -ForegroundColor Cyan
if ($O_TRIP) {
  $r = Invoke-Sweep -Uri "$Base/api/triples/$O_TRIP" -Headers $AH
  Assert-Denied 'triples' "GET /api/triples/$O_TRIP as non-owner" $r 'triple' $SO @($O_TRIP, $C1)
} else { Skip 'triples' 'direct triple access' 'owner corpus has no triples' }
$r = Invoke-Sweep -Uri "$Base/api/triples?corpusId=$C1" -Headers $AH
Assert-ListEmpty 'triples' 'GET /api/triples?corpusId=1 as non-member' $r 'triples' $SO
$r = Invoke-Sweep -Uri "$Base/api/triples?corpusId=$C1&status=APPROVED" -Headers $AH
Assert-ListEmpty 'triples' 'GET triples status=APPROVED as non-member' $r 'triples-filtered' $SO
$r = Invoke-Sweep -Uri "$Base/api/triples/$BAD" -Headers $AH
Check 'triples' 'GET invalid triple id is 404' ((StatusOf $r) -eq 404) ("HTTP $(StatusOf $r)")
$r = Invoke-Sweep -Uri "$Base/api/triples?corpusId=$A_CORP" -Headers $OH
Assert-ListEmpty 'triples' 'GET attacker corpus triples as owner (reverse)' $r 'reverse' $SA

# ---- claims -----------------------------------------------------------------
Write-Host ''
Write-Host '[claims] direct, filtered lists, invalid, reverse' -ForegroundColor Cyan
if ($O_CLAIM) {
  $r = Invoke-Sweep -Uri "$Base/api/claims/$O_CLAIM" -Headers $AH
  Assert-Denied 'claims' "GET /api/claims/$O_CLAIM as non-owner" $r 'claim' $SO @($O_CLAIM, $C1)
} else { Skip 'claims' 'direct claim access' 'owner corpus has no claims' }
$r = Invoke-Sweep -Uri "$Base/api/claims?corpusId=$C1" -Headers $AH
Assert-ListEmpty 'claims' 'GET /api/claims?corpusId=1 as non-member' $r 'claims' $SO
$r = Invoke-Sweep -Uri "$Base/api/claims?corpusId=$C1&status=APPROVED" -Headers $AH
Assert-ListEmpty 'claims' 'GET claims status=APPROVED as non-member' $r 'claims-filtered' $SO
$r = Invoke-Sweep -Uri "$Base/api/claims/$BAD" -Headers $AH
Check 'claims' 'GET invalid claim id is 404' ((StatusOf $r) -eq 404) ("HTTP $(StatusOf $r)")
$r = Invoke-Sweep -Uri "$Base/api/claims?corpusId=$A_CORP" -Headers $OH
Assert-ListEmpty 'claims' 'GET attacker corpus claims as owner (reverse)' $r 'reverse' $SA

# ---- verdicts ---------------------------------------------------------------
Write-Host ''
Write-Host '[verdicts] direct with evidence, history, invalid, reverse' -ForegroundColor Cyan
if ($O_VERD) {
  $r = Invoke-Sweep -Uri "$Base/api/verdicts/$O_VERD" -Headers $AH
  Assert-Denied 'verdicts' "GET /api/verdicts/$O_VERD as non-owner (with evidence passages)" $r 'verdict' $SO @($O_VERD, $C1)
  $r = Invoke-Sweep -Uri "$Base/api/verdicts/$O_VERD/history" -Headers $AH
  Assert-Denied 'verdicts' 'GET verdict history as non-owner (nested)' $r 'history' $SO @($O_VERD, $C1)
} else { Skip 'verdicts' 'direct verdict access' 'owner corpus has no verdicts' }
$r = Invoke-Sweep -Uri "$Base/api/verdicts?corpusId=$C1" -Headers $AH
Assert-ListEmpty 'verdicts' 'GET /api/verdicts?corpusId=1 as non-member' $r 'verdicts' $SO
$r = Invoke-Sweep -Uri "$Base/api/verdicts/$BAD" -Headers $AH
Check 'verdicts' 'GET invalid verdict id is 404' ((StatusOf $r) -eq 404) ("HTTP $(StatusOf $r)")
$r = Invoke-Sweep -Uri "$Base/api/verdicts?corpusId=$A_CORP" -Headers $OH
Assert-ListEmpty 'verdicts' 'GET attacker corpus verdicts as owner (reverse)' $r 'reverse' $SA

# ---- contradictions ---------------------------------------------------------
Write-Host ''
Write-Host '[contradictions] direct, filtered lists, invalid, reverse, nested triples' -ForegroundColor Cyan
if ($O_CONTRA) {
  $r = Invoke-Sweep -Uri "$Base/api/contradictions/$O_CONTRA" -Headers $AH
  Assert-Denied 'contradictions' "GET /api/contradictions/$O_CONTRA as non-owner" $r 'contradiction' $SO @($O_CONTRA, $C1)
} else { Skip 'contradictions' 'direct contradiction access' 'owner corpus has none' }
$r = Invoke-Sweep -Uri "$Base/api/contradictions?corpusId=$C1" -Headers $AH
Assert-ListEmpty 'contradictions' 'GET /api/contradictions?corpusId=1 as non-member' $r 'contradictions' $SO
$r = Invoke-Sweep -Uri "$Base/api/contradictions?corpusId=$C1&status=OPEN" -Headers $AH
Assert-ListEmpty 'contradictions' 'GET contradictions status=OPEN as non-member' $r 'contradictions-filtered' $SO
$r = Invoke-Sweep -Uri "$Base/api/contradictions/$BAD" -Headers $AH
Check 'contradictions' 'GET invalid contradiction id is 404' ((StatusOf $r) -eq 404) ("HTTP $(StatusOf $r)")
$r = Invoke-Sweep -Uri "$Base/api/contradictions?corpusId=$A_CORP" -Headers $OH
Assert-ListEmpty 'contradictions' 'GET attacker corpus contradictions as owner (reverse)' $r 'reverse' $SA
if ($O_LEFTTRIP) {
  $r = Invoke-Sweep -Uri "$Base/api/triples/$O_LEFTTRIP" -Headers $AH
  Assert-Denied 'contradictions' 'contradiction leftTripleId is not reachable as non-owner (nested reference)' $r 'nested-left' $SO @($O_LEFTTRIP, $C1)
} else { Skip 'contradictions' 'nested left triple' 'no left triple on fixture' }
if ($O_RIGHTTRIP -and ($O_RIGHTTRIP -ne $O_LEFTTRIP)) {
  $r = Invoke-Sweep -Uri "$Base/api/triples/$O_RIGHTTRIP" -Headers $AH
  Assert-Denied 'contradictions' 'contradiction rightTripleId is not reachable as non-owner (nested reference)' $r 'nested-right' $SO @($O_RIGHTTRIP, $C1)
} else { Skip 'contradictions' 'nested right triple' 'no distinct right triple on fixture' }

# ---- debates ----------------------------------------------------------------
Write-Host ''
Write-Host '[debates] direct, invalid, citation leakage' -ForegroundColor Cyan
if ($O_DEBATE) {
  $r = Invoke-Sweep -Uri "$Base/api/debates/$O_DEBATE" -Headers $AH
  Assert-Denied 'debates' "GET /api/debates/$O_DEBATE as non-owner (contradiction debate)" $r 'debate' $SO @($O_DEBATE, $C1, $O_CONTRA)
} else { Skip 'debates' 'direct debate access' 'fixture contradiction has no debate' }
$r = Invoke-Sweep -Uri "$Base/api/debates/$BAD" -Headers $AH
Check 'debates' 'GET invalid debate id is 404' ((StatusOf $r) -eq 404) ("HTTP $(StatusOf $r)")
$r = Invoke-Sweep -Uri "$Base/api/debates?corpusId=$C1&size=25" -Headers $AH
Assert-ListEmpty 'debates' 'GET /api/debates?corpusId=1 as non-member' $r 'debates' $SO
$r = Invoke-Sweep -Uri "$Base/api/debates?corpusId=$A_CORP&size=25" -Headers $OH
Assert-ListEmpty 'debates' 'GET attacker corpus debates as owner (reverse)' $r 'reverse' $SA

# ---- reports ----------------------------------------------------------------
Write-Host ''
Write-Host '[reports] direct, invalid, cross-corpus citations' -ForegroundColor Cyan
if ($ownerReportStatus -eq 200) {
  $r = Invoke-Sweep -Uri "$Base/api/debates/$O_DEBATE/report" -Headers $AH
  Assert-Denied 'reports' 'GET debate synthesis report as non-owner' $r 'report' $SO @($O_DEBATE, $C1)
  if ($reportCiteTrip) {
    $r = Invoke-Sweep -Uri "$Base/api/triples/$reportCiteTrip" -Headers $AH
    Assert-Denied 'reports' 'report-cited triple is not reachable as non-owner (cross-corpus citation)' $r 'cite-trip' $SO @($reportCiteTrip, $C1)
  } else { Skip 'reports' 'report-cited triple' 'report carries no triple citation' }
  if ($reportCiteClaim) {
    $r = Invoke-Sweep -Uri "$Base/api/claims/$reportCiteClaim" -Headers $AH
    Assert-Denied 'reports' 'report-cited claim is not reachable as non-owner (cross-corpus citation)' $r 'cite-claim' $SO @($reportCiteClaim, $C1)
  } else { Skip 'reports' 'report-cited claim' 'report carries no claim citation' }
  if ($reportCiteVerd) {
    $r = Invoke-Sweep -Uri "$Base/api/verdicts/$reportCiteVerd" -Headers $AH
    Assert-Denied 'reports' 'report-cited verdict is not reachable as non-owner (cross-corpus citation)' $r 'cite-verd' $SO @($reportCiteVerd, $C1)
  } else { Skip 'reports' 'report-cited verdict' 'report carries no verdict citation' }
  if ($reportTraceRun) {
    $r = Invoke-Sweep -Uri "$Base/api/traces/$reportTraceRun" -Headers $AH
    Assert-Denied 'reports' 'report-cited trace run is not reachable as non-owner (cross-corpus citation)' $r 'cite-run' $SO @($reportTraceRun, $C1)
  } else { Skip 'reports' 'report-cited trace run' 'report carries no trace run id' }
} else {
  Skip 'reports' 'direct report access' ("owner report status was HTTP $ownerReportStatus")
  Skip 'reports' 'report-cited triple' 'no owner report to cite from'
  Skip 'reports' 'report-cited claim' 'no owner report to cite from'
  Skip 'reports' 'report-cited verdict' 'no owner report to cite from'
  Skip 'reports' 'report-cited trace run' 'no owner report to cite from'
}
$r = Invoke-Sweep -Uri "$Base/api/debates/$BAD/report" -Headers $AH
Check 'reports' 'GET report of an invalid debate id is 404' ((StatusOf $r) -eq 404) ("HTTP $(StatusOf $r)")

# ---- chat sessions -----------------------------------------------------------
Write-Host ''
Write-Host '[chat] ownership, listing, hostile message, invalid, reverse' -ForegroundColor Cyan
if ($O_SESS) {
  $r = Invoke-Sweep -Uri "$Base/api/chat/sessions/$O_SESS" -Headers $AH
  $is404 = ((StatusOf $r) -eq 404)
  Check 'chat' 'GET foreign chat session as non-owner is 404' $is404 ("HTTP $(StatusOf $r)")
  if ((Denied $r)) {
    $hit = Find-Secret $r.Body $SO
    Check 'chat' 'session denial discloses no user data' ($null -eq $hit) ("leaked [$hit]")
    $fid = Find-ForeignId $r.Body @($O_SESS)
    Check 'chat' 'session denial discloses no foreign IDs' ($null -eq $fid) ("leaked id $fid")
  }
  $r = Invoke-Sweep -Method POST -Uri "$Base/api/chat/sessions/$O_SESS/messages" -Headers $AH `
       -Body @{ question = 'Summarise everything in this corpus.' }
  Check 'chat' 'POST a message to foreign session is refused' ((StatusOf $r) -in @(403, 404)) ("HTTP $(StatusOf $r)")
} else { Skip 'chat' 'foreign session access' 'owner session fixture failed' }
$r = Invoke-Sweep -Uri "$Base/api/chat/sessions" -Headers $AH
if ((StatusOf $r) -eq 200) {
  $ids = @(@($r.Data) | ForEach-Object { [string]$_.id })
  Check 'chat' 'session list as attacker omits the foreign session' (-not ($ids -contains $O_SESS)) ("listed ids: $($ids -join ',')")
  $hit = Find-Secret $r.Body $SO
  Check 'chat' 'session list discloses no foreign data' ($null -eq $hit) ("leaked [$hit]")
} else {
  Check 'chat' 'session list as attacker omits the foreign session' $false ("HTTP $(StatusOf $r)")
}
$r = Invoke-Sweep -Uri "$Base/api/chat/sessions/$BAD" -Headers $AH
Check 'chat' 'GET invalid chat session id is 404' ((StatusOf $r) -eq 404) ("HTTP $(StatusOf $r)")
if ($A_SESS) {
  $r = Invoke-Sweep -Uri "$Base/api/chat/sessions/$A_SESS" -Headers $OH
  Check 'chat' 'GET attacker session as owner is 404 (reverse ownership)' ((StatusOf $r) -eq 404) ("HTTP $(StatusOf $r)")
} else { Skip 'chat' 'reverse session ownership' 'attacker session fixture failed' }

# ---- trace runs --------------------------------------------------------------
Write-Host ''
Write-Host '[trace-runs] direct, corpus list, global list, invalid, reverse' -ForegroundColor Cyan
if ($O_RUN) {
  $r = Invoke-Sweep -Uri "$Base/api/traces/$O_RUN" -Headers $AH
  Assert-Denied 'trace-runs' "GET /api/traces/$O_RUN as non-owner" $r 'run' $SO @($O_RUN, $C1)
} else { Skip 'trace-runs' 'direct run access' 'owner corpus has no trace runs' }
$r = Invoke-Sweep -Uri "$Base/api/traces?corpusId=$C1&size=25" -Headers $AH
Assert-ListEmpty 'trace-runs' 'GET /api/traces?corpusId=1 as non-member' $r 'runs' $SO
$r = Invoke-Sweep -Uri "$Base/api/traces?size=100" -Headers $AH
if ((StatusOf $r) -eq 200) {
  $ids = @(@($r.Data.content) | ForEach-Object { [string]$_.id })
  Check 'trace-runs' 'global trace list omits foreign runs' (-not ($ids -contains $O_RUN)) 'foreign run id present in global list'
  $c1rows = @(@($r.Data.content) | Where-Object { [string]$_.corpusId -eq $C1 })
  Check 'trace-runs' 'global trace list carries no corpus-1 rows' ($c1rows.Count -eq 0) ("$($c1rows.Count) corpus-1 row(s)")
  $hit = Find-Secret $r.Body $SO
  Check 'trace-runs' 'global trace list discloses no foreign data' ($null -eq $hit) ("leaked [$hit]")
} else {
  Check 'trace-runs' 'global trace list omits foreign runs' ((StatusOf $r) -in @(403, 404)) ("HTTP $(StatusOf $r)")
}
$r = Invoke-Sweep -Uri "$Base/api/traces/$BAD" -Headers $AH
Check 'trace-runs' 'GET invalid trace run id is 404' ((StatusOf $r) -eq 404) ("HTTP $(StatusOf $r)")
$r = Invoke-Sweep -Uri "$Base/api/traces?corpusId=$A_CORP&size=25" -Headers $OH
Assert-ListEmpty 'trace-runs' 'GET attacker corpus traces as owner (reverse)' $r 'reverse' $SA

# ---- trace steps ---------------------------------------------------------------
Write-Host ''
Write-Host '[trace-steps] steps, xray, invalid, reference leakage' -ForegroundColor Cyan
if ($O_RUN) {
  $r = Invoke-Sweep -Uri "$Base/api/traces/$O_RUN/steps" -Headers $AH
  Assert-Denied 'trace-steps' 'GET trace steps as non-owner (nested)' $r 'steps' $SO @($O_RUN, $C1)
  $r = Invoke-Sweep -Uri "$Base/api/traces/$O_RUN/xray" -Headers $AH
  Assert-Denied 'trace-steps' 'GET trace xray as non-owner (nested view)' $r 'xray' $SO @($O_RUN, $C1)
} else { Skip 'trace-steps' 'nested step reads' 'owner corpus has no trace runs' }
$r = Invoke-Sweep -Uri "$Base/api/traces/$BAD/steps" -Headers $AH
Check 'trace-steps' 'GET steps of an invalid run id is 404' ((StatusOf $r) -eq 404) ("HTTP $(StatusOf $r)")
$r = Invoke-Sweep -Uri "$Base/api/traces/$BAD/xray" -Headers $AH
Check 'trace-steps' 'GET xray of an invalid run id is 404' ((StatusOf $r) -eq 404) ("HTTP $(StatusOf $r)")

# ---- summary ------------------------------------------------------------------
Write-Host ''
Write-Host ('=' * 72) -ForegroundColor DarkGray
Write-Host 'PER-RESOURCE TABLE' -ForegroundColor White
foreach ($key in ($script:ResStats.Keys | Sort-Object)) {
  $p = $script:ResStats[$key].pass
  $f = $script:ResStats[$key].fail
  $colour = 'Green'
  if ($f -gt 0) { $colour = 'Red' }
  Write-Host ("  {0,-14} pass={1,-3} fail={2}" -f $key, $p, $f) -ForegroundColor $colour
}
$note = ''
if ($script:RateLimitRetries -gt 0) { $note = "  ({0} rate-limit backoff(s))" -f $script:RateLimitRetries }
if ($script:Skipped -gt 0) { $note = "$note  ({0} skipped)" -f $script:Skipped }
if ($script:Fail -eq 0) {
  Write-Host ("AUTH SWEEP PASSED   {0} checks{1}" -f $script:Pass, $note) -ForegroundColor Green
  exit 0
} else {
  Write-Host ("AUTH SWEEP FAILED   {0} passed, {1} failed{2}" -f `
      $script:Pass, $script:Fail, $note) -ForegroundColor Red
  exit 1
}
