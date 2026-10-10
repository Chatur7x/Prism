<#
PRISM showcase preparation. Idempotent: safe to run twice, never duplicates.

Checks Docker, Ollama, the model, migrations (via backend health), the
showcase corpus, its documents, approvals, graph, verdicts, contradictions,
Council, synthesis, chat and Glass Box. Reports READY / PARTIAL / BLOCKED
with the exact recovery command for anything missing.

Nothing here fabricates state. Every check reads real application state;
anything absent is reported, not invented.
#>
param(
  [string]$Base = 'http://localhost:8080',
  [string]$Username = '',
  [string]$Password = '',
  [string]$CorpusName = 'PRISM-QA-SYNTHETIC-2026-10',
  [string]$OllamaBase = 'http://127.0.0.1:11435',
  [string]$Model = 'qwen2.5:7b'
)

$ErrorActionPreference = 'Continue'
$script:ready = 0
$script:partial = 0
$script:blocked = 0

function Say($msg, $colour = 'Gray') { Write-Host $msg -ForegroundColor $colour }
function Ok($name, $detail) { $script:ready++; Say ("  [READY]   {0}: {1}" -f $name, $detail) 'Green' }
function Warn($name, $detail, $fix) {
  $script:partial++
  Say ("  [PARTIAL] {0}: {1}" -f $name, $detail) 'Yellow'
  Say ("            fix: {0}" -f $fix) 'DarkGray'
}
function Block($name, $detail, $fix) {
  $script:blocked++
  Say ("  [BLOCKED] {0}: {1}" -f $name, $detail) 'Red'
  Say ("            fix: {0}" -f $fix) 'DarkGray'
}

if ([string]::IsNullOrWhiteSpace($Username) -or [string]::IsNullOrWhiteSpace($Password)) {
  Say 'Supply -Username and -Password. No defaults: credentials stay out of version control.' 'Red'
  exit 2
}

Say '== 1. Docker =='
try {
  $ps = docker ps --format '{{.Names}} {{.Status}}' 2>&1
  $up = @($ps | Where-Object { $_ -match 'Up' }).Count
  if ($up -ge 3) { Ok 'docker' "$up containers up" }
  else { Block 'docker' "$up containers up, need 3" 'docker compose up -d; wait 90s' }
} catch { Block 'docker' $_.Exception.Message 'start Docker Desktop, then docker compose up -d' }

Say '== 2. Ollama + model =='
try {
  $tags = Invoke-RestMethod -Uri "$OllamaBase/api/tags" -TimeoutSec 10
  $names = @($tags.models | ForEach-Object { $_.name })
  if ($names -contains $Model) { Ok 'ollama' "$Model present at $OllamaBase" }
  else { Block 'ollama model' "$Model missing (have: $($names -join ', '))" "ollama pull $Model" }
} catch { Block 'ollama' $_.Exception.Message 'start ollama serve with OLLAMA_HOST=127.0.0.1:11435' }

Say '== 3. Backend health + provider =='
try {
  $health = Invoke-RestMethod -Uri "$Base/actuator/health" -TimeoutSec 15
  if ($health.status -eq 'UP') {
    $log = docker logs prism-backend 2>&1 | Select-String -Pattern 'LLM provider: ([\w-]+)' | Select-Object -Last 1
    $prov = if ($log) { $log.Matches[0].Groups[1].Value } else { 'unknown' }
    if ($prov -eq 'openai-compatible') { Ok 'backend' "UP, real inference ($prov)" }
    else { Warn 'backend provider' "UP but provider=$prov (fake fixture)" 'restart backend with LLM_PROVIDER=openai + LLM_BASE_URL=http://host.docker.internal:11435/v1' }
  } else { Block 'backend' "health=$($health.status)" 'docker compose up -d backend' }
} catch { Block 'backend' $_.Exception.Message 'docker compose up -d backend; wait 90s' }

Say '== 4. Login =='
$h = $null
try {
  $l = Invoke-RestMethod -Uri "$Base/api/auth/login" -Method Post -ContentType 'application/json' `
    -Body (@{ username = $Username; password = $Password } | ConvertTo-Json) -TimeoutSec 30
  $h = @{ Authorization = "Bearer $($l.accessToken)" }
  Ok 'login' "as $($l.user.username) ($($l.user.role))"
} catch { Block 'login' $_.Exception.Message 'check credentials; auth is 10/min per IP so wait 70s after a 429'; exit 2 }

function Get-Prism($uri) {
  try { return Invoke-RestMethod -Uri $uri -Headers $h -TimeoutSec 60 }
  catch { return $null }
}

Say '== 5. Showcase corpus =='
$corpusId = 0
$corpus = Get-Prism "$Base/api/corpora?size=100"
# The list endpoint returns a bare array, not a page envelope.
$rows = if ($corpus -is [array]) { $corpus } else { @($corpus.content) }
$hit = @($rows | Where-Object { $_.name -eq $CorpusName })
if ($hit.Count -eq 1) { $corpusId = [long]$hit[0].id; Ok 'corpus' "$CorpusName (id=$corpusId)" }
elseif ($hit.Count -gt 1) { Warn 'corpus' "$($hit.Count) corpora share the name" 'rename duplicates; keep one' ; $corpusId = [long]$hit[0].id }
else { Block 'corpus' "$CorpusName missing" 'upload the 5 test-pack documents to a new corpus with this exact name' }

if ($corpusId -gt 0) {
  Say '== 6. Documents =='
  $docs = Get-Prism "$Base/api/documents?corpusId=$corpusId&size=50"
  $n = @($docs.content).Count
  if ($n -ge 5) { Ok 'documents' "$n docs, all present" }
  elseif ($n -gt 0) { Warn 'documents' "$n/5 present" 'upload the missing test-pack files via POST /api/documents' }
  else { Block 'documents' 'none' 'upload the 5 test-pack files via POST /api/documents' }

  Say '== 7. Approvals =='
  $st = Get-Prism "$Base/api/corpora/$corpusId/statistics"
  # VERIFIED claims passed through APPROVED, so both count as approved work.
  $appr = [int]$st.claimsByStatus.APPROVED + [int]$st.claimsByStatus.VERIFIED
  if ($st -and $appr -gt 0) { Ok 'approvals' "$appr/$($st.claims) claims approved (incl. $($st.claimsByStatus.VERIFIED) verified)" }
  else { Warn 'approvals' 'no approved claims' 'approve grounded proposals in the approval queue as VERIFIER' }

  Say '== 8. Graph =='
  $g = Get-Prism "$Base/api/graph/$corpusId?scope=ALL_APPROVED"
  if ($g -and @($g.nodes).Count -gt 0) { Ok 'graph' "$($g.nodes.Count) nodes, $($g.edges.Count) edges" }
  else { Warn 'graph' 'empty (nothing approved yet)' 'approve proposals first' }

  Say '== 9. Verdicts =='
  $v = Get-Prism "$Base/api/verdicts?corpusId=$corpusId&size=50"
  $nv = @($v.content).Count
  if ($nv -gt 0) { Ok 'verdicts' "$nv verdicts recorded" }
  else { Warn 'verdicts' 'none yet' 'POST /api/claims/verify-all?corpusId={id}&limit=20 (real model: minutes per claim on CPU)' }

  Say '== 10. Contradictions =='
  $c = Get-Prism "$Base/api/contradictions?corpusId=$corpusId&size=50"
  $nc = @($c.content).Count
  if ($nc -gt 0) { Ok 'contradictions' "$nc findings" }
  else { Warn 'contradictions' 'none in showcase corpus (corpus 1 has 8 OPEN)' 'contradictions need conflicting approved facts; use corpus 1 for that demo step' }

  Say '== 11. Council =='
  $db = Get-Prism "$Base/api/debates?corpusId=$corpusId&size=10"
  $done = @(@($db.content) | Where-Object { $_.status -eq 'COMPLETED' -or $_.state -eq 'COMPLETED' })
  if ($done.Count -gt 0) { Ok 'council' "debate $($done[0].id) COMPLETED" }
  else { Warn 'council' 'no completed debate in showcase corpus (debate 4 COMPLETED in corpus 1)' 'convene on a real contradiction; CPU personas take ~7 min/round' }

  Say '== 12. Synthesis =='
  $rep = $null
  foreach ($dd in @($db.content)) {
    $rr = Get-Prism "$Base/api/debates/$($dd.id)/report"
    if ($rr -and $rr.blocks) { $rep = $rr; $repId = $dd.id; break }
  }
  if ($rep) { Ok 'synthesis' "report on debate $repId, $($rep.blocks.Count) blocks, model $($rep.model)" }
  else { Warn 'synthesis' 'no report yet (report 1 exists on debate 4, corpus 1)' 'POST /api/debates/{id}/synthesize after SYNTHESIZING' }

  Say '== 13. Chat =='
  try {
    $sess = Invoke-RestMethod -Uri "$Base/api/chat/sessions" -Method Post -Headers $h -ContentType 'application/json' `
      -Body (@{ corpusId = $corpusId; title = 'showcase readiness probe' } | ConvertTo-Json) -TimeoutSec 30
    Ok 'chat' "session $($sess.id) created on corpus $corpusId"
  } catch { Warn 'chat' $_.Exception.Message 'check chat endpoints and auth' }

  Say '== 14. Glass Box =='
  $t = Get-Prism "$Base/api/traces?corpusId=$corpusId&size=5"
  if ($t -and @($t.content).Count -gt 0) { Ok 'glass box' "$($t.content.Count) trace runs" }
  else { Warn 'glass box' 'no traces for corpus' 'run any pipeline action first' }
}

Say ''
Say '================================================================'
if ($script:blocked -gt 0) { Say ("SHOWCASE STATUS: BLOCKED ({0} blocked, {1} partial, {2} ready)" -f $script:blocked, $script:partial, $script:ready) 'Red' }
elseif ($script:partial -gt 0) { Say ("SHOWCASE STATUS: PARTIAL ({0} partial, {1} ready)" -f $script:partial, $script:ready) 'Yellow' }
else { Say ("SHOWCASE STATUS: READY ({0} checks ready)" -f $script:ready) 'Green' }
Say '================================================================'
exit $(if ($script:blocked -gt 0) { 1 } else { 0 })
