# =============================================================================
# PRISM verification-quality evaluation
# =============================================================================
# Scores the production judge against a hand-labelled gold set
# (eval/verification-gold-v1.json): claim text -> expected machine verdict.
#
# Each gold item is resolved to a real claim by exact text match inside the
# corpus, then verified through the REAL production endpoint
# (POST /api/claims/verify) -- the same path the UI's Verify button uses, with
# server-side retrieval, rule analysis, judge model call and fusion. Nothing
# here bypasses or simulates the pipeline.
#
# Items that do not resolve to exactly one APPROVED claim are SKIPPED and
# reported, never scored. Only APPROVED claims can be verified; anything else
# throws stateConflict, so scoring a non-APPROVED claim is impossible by design.
#
# Usage:
#   powershell -File scripts/verify-eval.ps1 -Username <v> -Password '<p>' -CorpusId 1
#   powershell -File scripts/verify-eval.ps1 -Username <v> -Password '<p>' -CorpusId 1 -RequireRealModel -JudgeModel 'qwen2.5:7b'
#
# -RequireRealModel refuses to report when the backend runs the fake provider,
# mirroring llm-eval.ps1: exit 4 with REAL_MODEL_EXECUTION_REQUIRED. A fixture
# run can never be mistaken for a model run.
#
# -JudgeModel only labels the run record; it MUST match the backend's
# LLM_JUDGE_MODEL or the record is a lie. The script echoes both so a mismatch
# is visible.
#
# Exit codes: 0 evaluated, 2 setup problem, 4 real model required but fake.
# =============================================================================

[CmdletBinding()]
param(
  [string]$Base = 'http://localhost:8080',
  [string]$Username = '',
  [string]$Password = '',
  [long]$CorpusId = 0,
  [string]$GoldSetPath = '',
  [string]$ReportPath = '',
  [string]$JudgeModel = 'qwen2.5:7b',
  [switch]$RequireRealModel
)

$ErrorActionPreference = 'Continue'

function Say($text, $colour = 'Gray') { Write-Host $text -ForegroundColor $colour }

$repo = Split-Path -Parent $PSScriptRoot
if (-not $GoldSetPath) { $GoldSetPath = Join-Path $repo 'eval\verification-gold-v1.json' }
if (-not (Test-Path $GoldSetPath)) { Say "gold set not found: $GoldSetPath" 'Red'; exit 2 }
if ([string]::IsNullOrWhiteSpace($Username) -or [string]::IsNullOrWhiteSpace($Password)) {
  Say 'supply -Username and -Password.' 'Yellow'; exit 2
}

function Invoke-Prism {
  param([string]$Uri, [hashtable]$Headers, [string]$Method = 'GET', $Body = $null)
  $p = @{ Uri = $Uri; Method = $Method; Headers = $Headers; TimeoutSec = 300 }
  if ($null -ne $Body) { $p.ContentType = 'application/json'; $p.Body = ($Body | ConvertTo-Json -Depth 8 -Compress) }
  try { return Invoke-RestMethod @p }
  catch {
    $s = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
    return [pscustomobject]@{ __status = $s; __message = $_.Exception.Message }
  }
}
function StatusOf($r) {
  if ($null -eq $r) { return 0 }
  if ($r.PSObject.Properties.Name -contains '__status') { return [int]$r.__status }
  return 200
}

$gold = [System.IO.File]::ReadAllText((Resolve-Path $GoldSetPath).Path) | ConvertFrom-Json

$login = Invoke-Prism -Uri "$Base/api/auth/login" -Headers @{} -Method POST -Body @{ username = $Username; password = $Password }
if ((StatusOf $login) -ne 200) { Say 'sign-in failed' 'Red'; exit 2 }
$h = @{ Authorization = "Bearer $($login.accessToken)" }
if ($login.user.role -notin @('VERIFIER', 'ADMIN')) { Say "this account is $($login.user.role); evaluation is verifier-gated" 'Red'; exit 2 }

# Provider gate: /api/admin/system/status carries offlineTestMode, so a fixture
# run is refused BEFORE any verdict is recorded rather than reported with an
# asterisk afterwards. That endpoint is ADMIN-only, so a verifier caller gets
# 403; fall back to the backend's own boot log, where LlmModeReporter prints
# the active provider unambiguously.
$isFake = $true
$providerSource = 'default (assumed fake)'
try {
  $status = Invoke-Prism -Uri "$Base/api/admin/system/status" -Headers $h
  if ((StatusOf $status) -eq 200 -and $null -ne $status.offlineTestMode) {
    $isFake = [bool]$status.offlineTestMode
    $providerSource = 'admin system status'
  } else {
    $log = docker logs prism-backend 2>&1 | Select-String -Pattern 'LLM [Pp][Rr][Oo][Vv][Ii][Dd][Ee][Rr][ :=]+(\w[\w-]*)' | Select-Object -Last 1
    if ($log -and $log.Matches[0].Groups[1].Value -ne 'fake') { $isFake = $false; $providerSource = 'backend boot log' }
    elseif ($log) { $providerSource = 'backend boot log' }
  }
} catch { }
Say "provider gate: $(if ($isFake) { 'FAKE' } else { 'REAL' }) via $providerSource" 'DarkGray'
if ($RequireRealModel -and $isFake) {
  Write-Host 'REAL_MODEL_EXECUTION_REQUIRED' -ForegroundColor Yellow
  Say 'Backend runs the fake provider. Set LLM_PROVIDER=openai (or any OpenAI-compatible' 'Yellow'
  Say 'endpoint), restart the backend, and re-run. Nothing was measured.' 'Yellow'
  exit 4
}

# Resolve every gold claim text to a claim id first, so a resolution failure
# never leaves a half-scored run that looks complete.
$claims = Invoke-Prism -Uri "$Base/api/claims?corpusId=$CorpusId&size=500" -Headers $h
if ((StatusOf $claims) -ne 200) { Say 'could not list claims' 'Red'; exit 2 }
$byText = @{}
function Norm-ClaimText([string]$s) {
  # The extractor terminates claim texts with a period; the gold set records
  # them without one. Normalise both sides identically (trim, strip trailing
  # periods, collapse whitespace) so a punctuation difference is not scored
  # as a missing claim. Matching remains exact after normalisation: no
  # fuzzy, substring, or alias resolution.
  if ($null -eq $s) { return '' }
  $t = $s.Trim().TrimEnd('.').Trim()
  return ($t -replace '\s+', ' ')
}
foreach ($c in @($claims.content)) {
  $t = Norm-ClaimText "$($c.claimText)"
  if (-not $byText.ContainsKey($t)) { $byText[$t] = @() }
  $byText[$t] += $c
}

$items = @()
$skipped = @()
foreach ($g in @($gold.items)) {
  $hits = $byText[(Norm-ClaimText "$($g.claimText)")]
  if ($null -eq $hits -or @($hits).Count -eq 0) { $skipped += [pscustomobject]@{ id = $g.id; reason = 'claim text not found in corpus' }; continue }
  if (@($hits).Count -gt 1) { $skipped += [pscustomobject]@{ id = $g.id; reason = "ambiguous: $(@($hits).Count) claims share the text" }; continue }
  $c = @($hits)[0]
  if ($c.status -ne 'APPROVED') { $skipped += [pscustomobject]@{ id = $g.id; reason = "claim $($c.id) has status $($c.status); only APPROVED claims are verified" }; continue }
  $items += [pscustomobject]@{ gold = $g; claimId = [long]$c.id }
}

Say ''
Say "gold items : $(@($gold.items).Count) ($($gold.datasetVersion))"
Say "scorable   : $($items.Count)"
Say "skipped    : $($skipped.Count)"
foreach ($s in $skipped) { Say "  $($s.id): $($s.reason)" 'DarkGray' }

$results = @()
foreach ($it in $items) {
  $t0 = Get-Date
  $out = Invoke-Prism -Uri "$Base/api/claims/verify" -Headers $h -Method POST -Body @{ claimId = $it.claimId }
  $ms = [int]((Get-Date) - $t0).TotalMilliseconds
  $expected = "$($it.gold.expectedVerdict)"
  if ((StatusOf $out) -ne 200) {
    $results += [pscustomobject]@{ id = $it.gold.id; claimId = $it.claimId; expected = $expected;
      actual = 'ERROR'; match = $false; latencyMs = $ms; evidenceCount = -1; note = $out.__message }
    continue
  }
  $actual = "$($out.machineVerdictType)"
  $isMatch = ($actual -eq $expected)
  $results += [pscustomobject]@{ id = $it.gold.id; claimId = $it.claimId; expected = $expected;
    actual = $actual; match = $isMatch; latencyMs = $ms;
    evidenceCount = [int]$out.evidenceCount; llmScore = $out.llmScore; rulePenalty = $out.rulePenalty; fusedScore = $out.fusedScore }
  Say "  $($it.gold.id): expected $expected, got $actual $(if ($isMatch) { 'MATCH' } else { 'MISS' })" `
    $(if ($isMatch) { 'DarkGray' } else { 'Red' })
}

$scored = @($results | Where-Object { $_.actual -ne 'ERROR' })
$hits = @($scored | Where-Object { $_.match }).Count
$acc = if ($scored.Count -gt 0) { [math]::Round($hits / $scored.Count, 4) } else { $null }
# Per-category accuracy where the sample supports it; categories are never
# collapsed into one number.
$catOf = @{}
foreach ($it in $items) { $catOf[$it.gold.id] = "$($it.gold.category)" }
$byCat = @{}
foreach ($grp in ($scored | Group-Object { $catOf[$_.id] })) {
  $m = @($grp.Group | Where-Object { $_.match }).Count
  $byCat[$grp.Name] = @{ n = $grp.Count; matches = $m; accuracy = [math]::Round($m / $grp.Count, 4) }
}

$run = [pscustomobject]@{
  runId = [guid]::NewGuid().ToString()
  executedAt = (Get-Date).ToUniversalTime().ToString('o')
  datasetVersion = "$($gold.datasetVersion)"
  corpusId = $CorpusId
  provider = $(if ($isFake) { 'fake' } else { 'real' })
  judgeModel = $JudgeModel
  testMode = $isFake
  accuracy = $acc
  scored = $scored.Count
  matches = $hits
  skipped = @($skipped | ForEach-Object { @{ id = $_.id; reason = $_.reason } })
  byCategory = $byCat
  perItem = @($results | ForEach-Object { @{ id = $_.id; claimId = $_.claimId; expected = $_.expected;
    actual = $_.actual; match = $_.match; latencyMs = $_.latencyMs; evidenceCount = $_.evidenceCount } })
  datasetLimitation = 'Small hand-labelled set against one fictional corpus. Indicates behaviour; does not establish it.'
}

Say ''
Say ('=' * 74) -ForegroundColor DarkGray
if ($isFake) {
  Say 'PROVIDER: FAKE / TEST MODE -- FIXTURE VERDICTS, NOT MODEL JUDGEMENT' 'Yellow'
  Say '  These figures prove the harness and the pipeline work.' 'Yellow'
  Say '  They say NOTHING about any model. Do not quote them as model quality.' 'Yellow'
} else {
  Say 'PROVIDER: real inference' 'Green'
}
Say "accuracy: $(if ($null -eq $acc) { 'n/a (nothing scored)' } else { "$hits/$($scored.Count) = $acc" })"
Say ('=' * 74) -ForegroundColor DarkGray

if (-not $ReportPath) {
  $dir = Join-Path $repo 'eval\runs'
  if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
  $ReportPath = Join-Path $dir ("verification-{0}-{1}.json" -f $gold.datasetVersion, (Get-Date -Format 'yyyyMMdd-HHmmss'))
}
$run | ConvertTo-Json -Depth 8 | Set-Content -Path $ReportPath -Encoding utf8
Say "run record : $ReportPath"
exit 0
