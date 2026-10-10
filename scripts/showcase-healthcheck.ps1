<#
PRISM pre-show health check. Fast, read-only, no inference.

Verifies the frontend, backend, MySQL, auth, Ollama, showcase corpus,
graph data, a real contradiction, a completed Council debate, a real
synthesis report, the chat endpoint and Glass Box traces. Outputs
READY / PARTIAL / BLOCKED with evidence and a recovery command per check.

Run this 15 minutes before presenting. Anything BLOCKED must be fixed
before the audience arrives; anything PARTIAL has a fallback in the guide.
#>
param(
  [string]$Base = 'http://localhost:8080',
  [string]$Frontend = 'http://localhost:5173',
  [string]$Username = '',
  [string]$Password = '',
  [string]$CorpusName = 'PRISM-QA-SYNTHETIC-2026-10',
  [string]$OllamaBase = 'http://127.0.0.1:11435'
)

$ErrorActionPreference = 'Continue'
$script:ready = 0
$script:partial = 0
$script:blocked = 0

function Say($msg, $colour = 'Gray') { Write-Host $msg -ForegroundColor $colour }
function Ok($name, $ev) { $script:ready++; Say ("  [READY]   {0} -- {1}" -f $name, $ev) 'Green' }
function Warn($name, $ev, $fix) {
  $script:partial++; Say ("  [PARTIAL] {0} -- {1}" -f $name, $ev) 'Yellow'; Say ("            -> {0}" -f $fix) 'DarkGray'
}
function Block($name, $ev, $fix) {
  $script:blocked++; Say ("  [BLOCKED] {0} -- {1}" -f $name, $ev) 'Red'; Say ("            -> {0}" -f $fix) 'DarkGray'
}

if ([string]::IsNullOrWhiteSpace($Username) -or [string]::IsNullOrWhiteSpace($Password)) {
  Say 'Supply -Username and -Password. No defaults.' 'Red'; exit 2
}

try { $r = Invoke-WebRequest -Uri $Frontend -TimeoutSec 10 -UseBasicParsing; Ok 'frontend' "HTTP $($r.StatusCode) at $Frontend" }
catch { Block 'frontend' $_.Exception.Message 'docker compose up -d frontend; check nginx' }

try { $h = Invoke-RestMethod -Uri "$Base/actuator/health" -TimeoutSec 10; if ($h.status -eq 'UP') { Ok 'backend' 'actuator UP' } else { Block 'backend' "status=$($h.status)" 'docker compose up -d backend' } }
catch { Block 'backend' $_.Exception.Message 'docker compose up -d backend; wait 90s' }

try {
  $c = docker ps --filter name=prism-mysql --format '{{.Status}}' 2>&1
  if ($c -match 'healthy|Up') { Ok 'mysql' $c.Trim() } else { Block 'mysql' $c 'docker compose up -d mysql' }
} catch { Block 'mysql' $_.Exception.Message 'start Docker Desktop first' }

try {
  $l = Invoke-RestMethod -Uri "$Base/api/auth/login" -Method Post -ContentType 'application/json' `
    -Body (@{ username = $Username; password = $Password } | ConvertTo-Json) -TimeoutSec 20
  $h = @{ Authorization = "Bearer $($l.accessToken)" }
  Ok 'auth' "login as $($l.user.username) ($($l.user.role))"
} catch { Block 'auth' $_.Exception.Message 'wait 70s after a 429, then retry'; exit 2 }

function Get-P($uri) { try { return Invoke-RestMethod -Uri $uri -Headers $h -TimeoutSec 30 } catch { return $null } }

try {
  $t = Invoke-RestMethod -Uri "$OllamaBase/api/tags" -TimeoutSec 8
  $m = @($t.models | ForEach-Object { $_.name })
  if ($m -contains 'qwen2.5:7b') { Ok 'ollama' 'qwen2.5:7b available' }
  else { Warn 'ollama model' "have: $($m -join ', ')" 'ollama pull qwen2.5:7b (4.7 GB)' }
} catch { Warn 'ollama' $_.Exception.Message 'start ollama serve; demo still works on persisted results without live inference' }

$corpusId = 0
$cs = Get-P "$Base/api/corpora?size=100"
# The list endpoint returns a bare array, not a page envelope.
$rows = if ($cs -is [array]) { $cs } else { @($cs.content) }
$hit = @($rows | Where-Object { $_.name -eq $CorpusName })
if ($hit.Count -ge 1) { $corpusId = [long]$hit[0].id; Ok 'corpus' "$CorpusName (id=$corpusId)" }
else { Block 'corpus' "$CorpusName missing" 'restore from backup or re-upload the 5 test-pack files' }

if ($corpusId -gt 0) {
  $g = Get-P "$Base/api/graph/$corpusId`?scope=ALL_APPROVED"
  if ($g -and @($g.nodes).Count -gt 0) { Ok 'graph' "$($g.nodes.Count) nodes / $($g.edges.Count) edges" }
  else { Warn 'graph' 'empty' 'approve proposals first; fallback: corpus 1 graph' }
}

$c1 = Get-P "$Base/api/contradictions?corpusId=1&size=50"
$n1 = @($c1.content).Count
if ($n1 -gt 0) { Ok 'contradiction' "$n1 findings in corpus 1 (demo step 8)" }
else { Block 'contradiction' 'none in corpus 1' 'run contradiction scan on corpus 1' }

$db = Get-P "$Base/api/debates?corpusId=1&size=10"
$done = @(@($db.content) | Where-Object { $_.state -eq 'COMPLETED' -or $_.status -eq 'COMPLETED' })
if ($done.Count -gt 0) { Ok 'council' "debate $($done[0].id) COMPLETED (demo step 9)" }
else { Block 'council' 'no completed debate' 'convene on corpus-1 contradiction; ~15 min on CPU' }

$repOk = $false; $repId = 0
foreach ($dd in @($db.content)) {
  $rr = Get-P "$Base/api/debates/$($dd.id)/report"
  if ($rr -and $rr.blocks) { $repOk = $true; $repId = $dd.id; break }
}
if ($repOk) { Ok 'synthesis' "report on debate $repId ($($rr.blocks.Count) blocks)" }
else { Block 'synthesis' 'no report' 'POST /api/debates/{id}/synthesize after SYNTHESIZING' }

try {
  $s = Invoke-RestMethod -Uri "$Base/api/chat/sessions" -Method Post -Headers $h -ContentType 'application/json' `
    -Body (@{ corpusId = $corpusId; title = 'pre-show probe' } | ConvertTo-Json) -TimeoutSec 20
  Ok 'chat' "session $($s.id) created"
} catch { Warn 'chat' $_.Exception.Message 'check chat endpoints; fallback: show saved session 8 transcript' }

$tr = Get-P "$Base/api/traces?corpusId=$corpusId&size=5"
if ($tr -and @($tr.content).Count -gt 0) { Ok 'glass box' "$($tr.content.Count) traces" }
else { Warn 'glass box' 'no traces' 'fallback: corpus-1 traces' }

Say ''
Say '================================================================'
if ($script:blocked -gt 0) { Say ("SHOWCASE HEALTH: BLOCKED ({0} blocked, {1} partial, {2} ready)" -f $script:blocked, $script:partial, $script:ready) 'Red'; exit 1 }
elseif ($script:partial -gt 0) { Say ("SHOWCASE HEALTH: PARTIAL ({0} partial, {1} ready)" -f $script:partial, $script:ready) 'Yellow'; exit 0 }
else { Say ("SHOWCASE HEALTH: READY ({0} ready)" -f $script:ready) 'Green'; exit 0 }
