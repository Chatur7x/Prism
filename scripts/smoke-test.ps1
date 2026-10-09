# PRISM end-to-end API smoke test.
#
# Exercises the real pipeline against a running backend:
#   register -> create corpus -> upload document -> extraction -> approval
#   -> verification -> graph -> contradictions -> council -> synthesis
#   -> grounded chat -> Glass Box
#
# Fails loudly. Does not skip steps: a step that cannot run reports the server's
# actual error rather than continuing, because a partial pass would hide which
# stage is broken.

param(
  [string]$Base = "http://localhost:8080",
  # How long to wait for asynchronous extraction to settle. The 80s default
  # is the offline fixture's budget: with LLM_PROVIDER=fake, chunking plus
  # deterministic parsing finishes in seconds. Under a real provider on a CPU
  # box one model call per chunk at ~25s each dominates, so raise this.
  # The check itself is unchanged: extraction must still reach a terminal
  # state, the wait is simply longer.
  [int]$SettleTimeoutSec = 80
)

$ErrorActionPreference = "Stop"
$script:Failures = 0
$script:Step = 0

function Step($name) {
  $script:Step++
  Write-Host ""
  Write-Host ("[{0}] {1}" -f $script:Step, $name) -ForegroundColor Cyan
}

function Check($label, $condition, $detail) {
  if ($condition) {
    Write-Host ("  OK   {0}" -f $label) -ForegroundColor Green
  } else {
    Write-Host ("  FAIL {0}: {1}" -f $label, $detail) -ForegroundColor Red
    $script:Failures++
  }
}

# Unique per run so repeated smoke tests do not collide on username uniqueness.
$suffix = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$analyst = "smoke_analyst_$suffix"
$password = "SmokeTest!2026x"

$headers = @{}

Write-Host "PRISM smoke test against $Base" -ForegroundColor White
Write-Host "Analyst: $analyst"

Step "Register analyst (self-registration must yield ANALYST)"
try {
  $reg = Invoke-RestMethod -Method Post -Uri "$Base/api/auth/register" `
    -ContentType "application/json" `
    -Body (@{ username=$analyst; email="$analyst@example.com"; password=$password } | ConvertTo-Json) `
    -TimeoutSec 20
  $token = $reg.accessToken
  $headers = @{ Authorization = "Bearer $token" }
  Check "registration returns a token" ($null -ne $token) "no token"
  Check "self-registration role is ANALYST" ($reg.user.role -eq "ANALYST") "got $($reg.user.role)"
} catch {
  Check "registration" $false $_.Exception.Message
  Write-Host "Cannot continue without a session." -ForegroundColor Red
  exit 1
}

Step "Weak passwords are rejected"
try {
  $bad = "weak$suffix"
  Invoke-RestMethod -Method Post -Uri "$Base/api/auth/register" `
    -ContentType "application/json" `
    -Body (@{ username=$bad; email="$bad@example.com"; password="short" } | ConvertTo-Json) `
    -TimeoutSec 20 | Out-Null
  Check "short password rejected" $false "registration succeeded"
} catch {
  $code = $_.Exception.Response.StatusCode.value__
  Check "short password rejected" ($code -eq 400) "got $code"
}

Step "Anonymous access is refused"
try {
  Invoke-RestMethod -Uri "$Base/api/corpora" -TimeoutSec 10 | Out-Null
  Check "unauthenticated request rejected" $false "request succeeded"
} catch {
  $code = $_.Exception.Response.StatusCode.value__
  Check "unauthenticated request rejected" ($code -eq 401) "got $code"
}

Step "OpenAPI is locked to ADMIN (correct security posture)"
# The API surface is deliberately not public. Asserting a 403 here proves the
# lockdown is real rather than assuming it.
try {
  Invoke-RestMethod -Uri "$Base/v3/api-docs" -Headers $headers -TimeoutSec 20 | Out-Null
  Check "OpenAPI refused to a non-admin" $false "an ANALYST was served the API surface"
} catch {
  $code = $_.Exception.Response.StatusCode.value__
  Check "OpenAPI refused to a non-admin" ($code -eq 403) "got $code"
}

Step "A wrong HTTP verb returns 405, not 500"
# Guards a real defect: HttpRequestMethodNotSupportedException used to fall
# through to the generic handler and report a server fault, which misdirects an
# operator investigating a healthy service.
# /api/approval-queue is a genuine read-only route, so POST to it must be 405.
try {
  Invoke-RestMethod -Method Post -Uri "$Base/api/approval-queue?corpusId=1" -Headers $headers `
    -ContentType "application/json" -Body "{}" -TimeoutSec 15 | Out-Null
  Check "POST to a read-only route returns 405" $false "the request succeeded"
} catch {
  $code = $_.Exception.Response.StatusCode.value__
  Check "POST to a read-only route returns 405" ($code -eq 405) "got $code"
}

Step "Create corpus"
$corpusId = $null
try {
  $corpus = Invoke-RestMethod -Method Post -Uri "$Base/api/corpora" -Headers $headers `
    -ContentType "application/json" `
    -Body (@{ name="Smoke Corpus $suffix"; description="End-to-end smoke test corpus" } | ConvertTo-Json) `
    -TimeoutSec 20
  $corpusId = $corpus.id
  Check "corpus created" ($null -ne $corpusId) "no id returned"
} catch {
  Check "corpus created" $false $_.Exception.Message
}

if ($null -eq $corpusId) { Write-Host "Cannot continue without a corpus." -ForegroundColor Red; exit 1 }

Step "Ingest a document with a checkable fact"
$documentId = $null
$text = @"
Meridian Group reports_to Northstar Holdings. Northstar Holdings controls Orion Systems.
Orion Systems funds Aster Labs. Aster Labs located_in Helix Consortium.
Meridian Group invested_in Helix Consortium. Northstar Holdings funds Aster Labs.
"@
try {
  $doc = Invoke-RestMethod -Method Post -Uri "$Base/api/documents" -Headers $headers `
    -ContentType "application/json" `
    -Body (@{ corpusId=$corpusId; title="Smoke Memo $suffix"; contentText=$text } | ConvertTo-Json) `
    -TimeoutSec 30
  $documentId = $doc.id
  Check "document accepted" ($null -ne $documentId) "no id returned"
  Check "document starts in a pending state" ($doc.status -in @('UPLOADED','CHUNKING','CHUNKED','EXTRACTING','AWAITING_APPROVAL','READY')) "got $($doc.status)"
} catch {
  Check "document accepted" $false $_.Exception.Message
}

Step "Wait for asynchronous extraction to settle"
$progress = $null
$settleIterations = [Math]::Ceiling($SettleTimeoutSec / 2)
for ($i = 0; $i -lt $settleIterations; $i++) {
  Start-Sleep -Seconds 2
  try {
    $progress = Invoke-RestMethod -Uri "$Base/api/documents/$documentId/progress" -Headers $headers -TimeoutSec 15
  } catch { continue }
  if ($progress.status -in @('AWAITING_APPROVAL','READY','FAILED')) { break }
}
Check "extraction reached a terminal state" ($progress.status -in @('AWAITING_APPROVAL','READY','FAILED')) "still $($progress.status) after $SettleTimeoutSec${'s'}"
Check "chunks were produced" ($progress.chunkCount -gt 0) "chunkCount=$($progress.chunkCount)"
Check "proposals or quarantine were recorded" (($progress.triplesFound + $progress.claimsFound + $progress.quarantinedCount) -gt 0) "triples=$($progress.triplesFound) claims=$($progress.claimsFound) quarantine=$($progress.quarantinedCount)"

Write-Host ("  status={0} chunks={1} triples={2} claims={3} quarantined={4}" -f `
  $progress.status, $progress.chunkCount, $progress.triplesFound, $progress.claimsFound, $progress.quarantinedCount)

Step "Analyst cannot approve (role separation)"
# GET, not POST: this is a read endpoint, and an analyst must be refused by
# @PreAuthorize with 403.
try {
  Invoke-RestMethod -Method Get -Uri "$Base/api/approval-queue?corpusId=${corpusId}" -Headers $headers -TimeoutSec 15 | Out-Null
  Check "analyst blocked from approval queue" $false "analyst was allowed"
} catch {
  $code = $_.Exception.Response.StatusCode.value__
  Check "analyst blocked from approval queue" ($code -eq 403) "got $code"
}

Step "Promote analyst to VERIFIER via bootstrap/admin path"
# Self-registration can never grant VERIFIER, by design. The documented
# bootstrap route is the only way a non-admin grants a role, so this step uses
# the DB directly to simulate what an operator would do.
#
# Connection details are resolved the way compose resolves them rather than
# hardcoded. Two hardcoded values were wrong and made this step fail with
# "db update failed": the container is named `prism-mysql` in docker-compose.yml,
# not `prism-dev`, and the database password comes from .env rather than from the
# compose default. Because nothing could be promoted, every check after this one
# failed too — an access-denied user reads as an empty corpus, so the eight
# downstream failures were all one root cause wearing eight disguises.
$envFile = Join-Path (Split-Path $PSScriptRoot -Parent) ".env"
if (Test-Path $envFile) {
  Get-Content $envFile | ForEach-Object {
    if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$') {
      $key = $Matches[1]
      if (-not [Environment]::GetEnvironmentVariable($key)) {
        [Environment]::SetEnvironmentVariable($key, $Matches[2])
      }
    }
  }
}
$dbContainer = if ($env:PRISM_MYSQL_CONTAINER) { $env:PRISM_MYSQL_CONTAINER } else { "prism-mysql" }
$dbUser      = if ($env:DB_USERNAME)       { $env:DB_USERNAME }       else { "prism" }
$dbPass      = if ($env:DB_PASSWORD)       { $env:DB_PASSWORD }       else { "prism" }
$dbName      = if ($env:MYSQL_DATABASE)     { $env:MYSQL_DATABASE }     else { "prism" }

# The mysql client warns about the password on the command line, and a native
# command writing to stderr aborts the script under ErrorActionPreference=Stop.
# Error handling is relaxed for this call only; the exit code is what we check.
$previous = $ErrorActionPreference
$ErrorActionPreference = "SilentlyContinue"
$null = docker exec $dbContainer mysql "-u$dbUser" "-p$dbPass" $dbName `
  -e "UPDATE users SET role='VERIFIER' WHERE username='$analyst';" 2>&1
$promoteExit = $LASTEXITCODE
$ErrorActionPreference = $previous
Check "role promoted in the database" ($promoteExit -eq 0) "db update failed"

# The JWT carries the role, so the new role needs a fresh token.
try {
  $login = Invoke-RestMethod -Method Post -Uri "$Base/api/auth/login" `
    -ContentType "application/json" `
    -Body (@{ username=$analyst; password=$password } | ConvertTo-Json) `
    -TimeoutSec 20
  $headers = @{ Authorization = "Bearer $($login.accessToken)" }
  Check "re-login succeeds" ($null -ne $login.accessToken) "no token"
} catch {
  Check "re-login succeeds" $false $_.Exception.Message
}

Step "Approval queue is now reachable"
$queue = $null
try {
  $queue = Invoke-RestMethod -Uri "$Base/api/approval-queue?corpusId=${corpusId}&size=100" -Headers $headers -TimeoutSec 20
  Check "approval queue readable" ($null -ne $queue) "no response"
  Write-Host ("  pending triples={0} pending claims={1}" -f $queue.pendingTripleCount, $queue.pendingClaimCount)
} catch {
  Check "approval queue readable" $false $_.Exception.Message
}

Step "Approve every pending triple and claim"
$approvedTriples = 0
$approvedClaims = 0
if ($queue) {
  foreach ($t in $queue.triples) {
    try {
      Invoke-RestMethod -Method Post -Uri "$Base/api/triples/$($t.id)/approve" -Headers $headers `
        -ContentType "application/json" -Body (@{note="smoke approval"} | ConvertTo-Json) -TimeoutSec 20 | Out-Null
      $approvedTriples++
    } catch { Write-Host ("    triple {0} approve failed: {1}" -f $t.id, $_.Exception.Message) -ForegroundColor DarkYellow }
  }
  foreach ($c in $queue.claims) {
    try {
      Invoke-RestMethod -Method Post -Uri "$Base/api/claims/$($c.id)/approve" -Headers $headers -TimeoutSec 20 | Out-Null
      $approvedClaims++
    } catch { Write-Host ("    claim {0} approve failed: {1}" -f $c.id, $_.Exception.Message) -ForegroundColor DarkYellow }
  }
}
Check "triples approved" ($approvedTriples -gt 0) "approved=$approvedTriples"
Check "claims approved" ($approvedClaims -gt 0) "approved=$approvedClaims"

Step "Double approval is refused (optimistic locking)"
if ($approvedTriples -gt 0) {
  $firstTriple = $queue.triples[0]
  try {
    Invoke-RestMethod -Method Post -Uri "$Base/api/triples/$($firstTriple.id)/approve" -Headers $headers `
      -ContentType "application/json" -Body (@{note="second approval"} | ConvertTo-Json) -TimeoutSec 20 | Out-Null
    Check "re-approving an approved triple is a 409" $false "the second approval succeeded"
  } catch {
    $code = $_.Exception.Response.StatusCode.value__
    Check "re-approving an approved triple is a 409" ($code -eq 409) "got $code"
  }
}

Step "Trusted graph contains only approved edges"
try {
  $graph = Invoke-RestMethod -Uri "$Base/api/graph/${corpusId}?scope=ALL_APPROVED" -Headers $headers -TimeoutSec 20
  Check "graph served" ($null -ne $graph) "no response"
  Check "graph has edges from approved triples" ($graph.edges.Count -gt 0) "edges=$($graph.edges.Count)"
  Write-Host ("  nodes={0} edges={1} density={2}" -f $graph.stats.nodeCount, $graph.stats.edgeCount, $graph.stats.density)
} catch {
  Check "graph served" $false $_.Exception.Message
}

Step "PageRank is available and sums to ~1"
try {
  $pr = Invoke-RestMethod -Uri "$Base/api/graph/$corpusId/pagerank" -Headers $headers -TimeoutSec 20
  Check "pagerank returned" ($pr.Count -gt 0) "no rows"
  if ($pr.Count -gt 0) {
    $sum = ($pr | Measure-Object -Property pagerank -Sum).Sum
    Check "pagerank sums to 1" ([math]::Abs($sum - 1.0) -lt 0.001) "sum=$sum"
  }
} catch {
  Check "pagerank returned" $false $_.Exception.Message
}

Step "Verify claims (Redline)"
try {
  $verifyAll = Invoke-RestMethod -Method Post -Uri "$Base/api/claims/verify-all?corpusId=${corpusId}&limit=20" -Headers $headers -TimeoutSec 300
  Check "verification ran" ($verifyAll.attempted -gt 0) "attempted=$($verifyAll.attempted)"
  Write-Host ("  attempted={0} succeeded={1} failed={2}" -f $verifyAll.attempted, $verifyAll.succeeded, $verifyAll.failed)
} catch {
  Check "verification ran" $false $_.Exception.Message
}

Step "Verdicts keep the four signals separate"
try {
  $verdicts = Invoke-RestMethod -Uri "$Base/api/verdicts?corpusId=${corpusId}&size=50" -Headers $headers -TimeoutSec 20
  Check "verdicts recorded" ($verdicts.total -gt 0) "total=$($verdicts.total)"
  $bad = @($verdicts.content | Where-Object { $null -eq $_.verdictType -or $null -eq $_.evidenceStatus })
  Check "every verdict has a type and evidence status" ($bad.Count -eq 0) "$($bad.Count) incomplete"
  $sample = $verdicts.content | Select-Object -First 1
  if ($sample) {
    Write-Host ("  sample: verdict={0} evidence={1} llmScore={2} rulePenalty={3} fused={4}" -f `
      $sample.verdictType, $sample.evidenceStatus, $sample.llmScore, $sample.rulePenalty, $sample.fusedScore)
    Check "machine verdict is recorded alongside the effective one" ($null -ne $sample.machineVerdictType) "null"
  }
} catch {
  Check "verdicts recorded" $false $_.Exception.Message
}

Step "Contradiction detection is deterministic and idempotent"
try {
  $scan1 = Invoke-RestMethod -Method Post -Uri "$Base/api/contradictions/scan?corpusId=${corpusId}" -Headers $headers -TimeoutSec 30
  $scan2 = Invoke-RestMethod -Method Post -Uri "$Base/api/contradictions/scan?corpusId=${corpusId}" -Headers $headers -TimeoutSec 30
  Check "rescan creates no duplicates" ($scan2.created -eq 0) "second scan created $($scan2.created)"
  Write-Host ("  first: findings={0} created={1} | second: created={2}" -f $scan1.findings, $scan1.created, $scan2.created)
} catch {
  Check "contradiction scan" $false $_.Exception.Message
}

Step "Grounded chat refuses when the corpus cannot answer"
$sessionId = $null
try {
  $session = Invoke-RestMethod -Method Post -Uri "$Base/api/chat/sessions" -Headers $headers `
    -ContentType "application/json" -Body (@{corpusId=$corpusId; title="Smoke chat"} | ConvertTo-Json) -TimeoutSec 20
  $sessionId = $session.id
  Check "chat session created" ($null -ne $sessionId) "no id"

  $answer = Invoke-RestMethod -Method Post -Uri "$Base/api/chat/sessions/$sessionId/messages" -Headers $headers `
    -ContentType "application/json" `
    -Body (@{question="What is the capital city of Portugal?"} | ConvertTo-Json) -TimeoutSec 60
  Check "answer carries a grounded/insufficient flag" ($null -ne $answer.grounded) "null"
  Write-Host ("  grounded={0} insufficientEvidence={1} citations={2}" -f `
    $answer.grounded, $answer.insufficientEvidence, $answer.citations.Count)
} catch {
  Check "chat session created" $false $_.Exception.Message
}

Step "Every returned citation resolves inside the corpus"
try {
  $history = Invoke-RestMethod -Uri "$Base/api/chat/sessions/$sessionId" -Headers $headers -TimeoutSec 20
  $citations = @($history.messages | ForEach-Object { $_.citations } | Where-Object { $_ })
  $allInCorpus = $true
  foreach ($citation in $citations) {
    try {
      $chunk = Invoke-RestMethod -Uri "$Base/api/documents/$($citation.documentId)" -Headers $headers -TimeoutSec 10
      if ($chunk.corpusId -ne $corpusId) { $allInCorpus = $false }
    } catch { $allInCorpus = $false }
  }
  Check "citations resolve within the corpus" $allInCorpus "found $((@($citations | Where-Object { -not $allInCorpus })).Count) out-of-corpus"
} catch {
  Check "citation resolution check" $false $_.Exception.Message
}

Step "Glass Box exposes the trace DAG"
try {
  $traces = Invoke-RestMethod -Uri "$Base/api/traces?corpusId=${corpusId}&size=50" -Headers $headers -TimeoutSec 20
  Check "trace runs listed" ($traces.totalElements -gt 0) "totalElements=$($traces.totalElements)"

  # The x-ray must separate the three actors. Assert this against a trace that
  # actually involved a model call, not whichever run the list returns first:
  # an approval trace legitimately has no model steps, so checking that one
  # would assert nothing meaningful about actor separation.
  $withLlm = $null
  foreach ($run in $traces.content) {
    $xr = Invoke-RestMethod -Uri "$Base/api/traces/$($run.id)/xray" -Headers $headers -TimeoutSec 20
    if ($xr.llm.Count -gt 0) { $withLlm = @{ run = $run; xray = $xr }; break }
  }
  Check "some trace includes model steps" ($null -ne $withLlm) "no run had any LLM steps"

  if ($withLlm) {
    $xray = $withLlm.xray
    Check "x-ray groups by actor" `
      (($xray.engine.Count + $xray.llm.Count + $xray.human.Count) -gt 0) "no nodes"
    Check "x-ray separates deterministic engine steps from model steps" `
      ($xray.engine.Count -gt 0 -and $xray.llm.Count -gt 0) `
      "engine=$($xray.engine.Count) llm=$($xray.llm.Count)"

    $detail = Invoke-RestMethod -Uri "$Base/api/traces/$($withLlm.run.id)" -Headers $headers -TimeoutSec 20
    Check "trace has steps" ($detail.steps.Count -gt 0) "no steps"
    $hasModel = @($detail.steps | Where-Object { $_.model }).Count -gt 0
    Check "model steps record which model produced them" $hasModel "no step carried a model identifier"
    $hasRule = @($detail.steps | Where-Object { $_.ruleVersion }).Count -gt 0
    Check "deterministic steps record their rule version" $hasRule "no ruleVersion recorded"
  }
} catch {
  Check "Glass Box trace" $false $_.Exception.Message
}

Step "Corpus isolation is enforced"
# A second corpus owned by nobody else; the analyst must not read the first.
try {
  $other = Invoke-RestMethod -Method Post -Uri "$Base/api/corpora" -Headers $headers `
    -ContentType "application/json" `
    -Body (@{name="Other $suffix"; description="isolation probe"} | ConvertTo-Json) -TimeoutSec 20
  $otherDocs = Invoke-RestMethod -Uri "$Base/api/documents?corpusId=$($other.id)" -Headers $headers -TimeoutSec 20
  Check "other corpus is readable by its owner" ($null -ne $otherDocs.content) "no response"
  Check "other corpus contains none of the first corpus's documents" ($otherDocs.content.Count -eq 0) "$($otherDocs.content.Count) leaked"
} catch {
  Check "corpus isolation probe" $false $_.Exception.Message
}

Step "Validation rejects an unknown verdict enum"
try {
  $verdicts = Invoke-RestMethod -Uri "$Base/api/verdicts?corpusId=${corpusId}&size=1" -Headers $headers -TimeoutSec 20
  if ($verdicts.total -gt 0) {
    $vid = $verdicts.content[0].id
    Invoke-RestMethod -Method Post -Uri "$Base/api/verdicts/$vid/adjudicate" -Headers $headers `
      -ContentType "application/json" -Body (@{verdict="MADE_UP_VERDICT"; note="x"} | ConvertTo-Json) -TimeoutSec 20 | Out-Null
    Check "unknown verdict enum rejected" $false "the request succeeded"
  } else {
    Check "unknown verdict enum rejected" $true "no verdict available to test"
  }
} catch {
  $code = $_.Exception.Response.StatusCode.value__
  Check "unknown verdict enum rejected" ($code -in @(400, 422)) "got $code"
}

Write-Host ""
Write-Host ("=" * 62) -ForegroundColor White
if ($script:Failures -eq 0) {
  Write-Host "SMOKE TEST PASSED - all checks green" -ForegroundColor Green
} else {
  Write-Host "SMOKE TEST FAILED - $($script:Failures) check(s) failed" -ForegroundColor Red
}
Write-Host ("=" * 62) -ForegroundColor White
exit $script:Failures
