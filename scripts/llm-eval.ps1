# =============================================================================
# PRISM extraction-quality evaluation
# =============================================================================
# Runs the production extraction path over a hand-checked gold set and reports
# precision, recall, F1, malformed rate and quarantine rate for the CONFIGURED
# provider.
#
# IMPORTANT, and not boilerplate:
#
#   Every figure this script prints was produced against the demo corpus, which
#   states every extractable fact in canonical `Subject predicate Object` form.
#   These numbers measure transcription and entity resolution. They do NOT measure
#   extraction from natural prose, and must not be quoted as extraction accuracy.
#   The report carries that caveat in a `corpusLimitation` field for exactly this
#   reason; please do not strip it.
#
# Usage:
#   powershell -File scripts/llm-eval.ps1 -Username u -Password '...' [-CorpusId 1]
#
# For a REAL model, set before starting the backend:
#   $env:LLM_PROVIDER   = 'openai'
#   $env:LLM_BASE_URL   = 'https://your-endpoint/v1'
#   $env:LLM_API_KEY    = '<key>'          # never committed, never logged
#   $env:LLM_EXTRACT_MODEL = 'your-model'
# For the offline provider, set LLM_PROVIDER=fake instead. The script prints which
# one is active so a fixture run can never be mistaken for a model run.
#
# Exit codes: 0 evaluated, 2 setup problem, 3 the provider refused every chunk.
# =============================================================================

[CmdletBinding()]
param(
  [string]$Base = 'http://localhost:8080',
  [string]$Username = '',
  [string]$Password = '',
  [long]$CorpusId = 0,
  [string]$GoldSetPath = '',
  [string]$ReportPath = '',
  [ValidateSet('canonical', 'prose')]
  [string]$Dataset = 'canonical',
  [switch]$RequireRealModel
)

$ErrorActionPreference = 'Continue'

function Say($text, $colour = 'Gray') { Write-Host $text -ForegroundColor $colour }

$repo = Split-Path -Parent $PSScriptRoot
if (-not $GoldSetPath) {
  if ($Dataset -eq 'prose') {
    $GoldSetPath = Join-Path $repo 'eval\prose-gold-v1.json'
  } else {
    $GoldSetPath = Join-Path $repo 'eval\gold-extraction.tsv'
  }
}
if (-not (Test-Path $GoldSetPath)) {
  Say "gold set not found: $GoldSetPath" 'Red'
  exit 2
}
if (-not (Test-Path $GoldSetPath)) { exit 2 }
if ([string]::IsNullOrWhiteSpace($Username) -or [string]::IsNullOrWhiteSpace($Password)) {
  Say 'supply -Username and -Password. An empty credential produces a 400 that looks like a '
  Say 'server fault rather than a missing argument.' 'Yellow'
  exit 2
}

# Auth endpoints are limited per minute and this script signs in once, so no
# back-off loop is needed here -- unlike the scripts that create accounts.
function Invoke-Prism {
  param([string]$Uri, [hashtable]$Headers, [string]$Method = 'GET', [string]$Body)
  # One provider call per chunk, issued sequentially, so wall time is roughly
  # (chunks x per-call latency). Against a CPU-local 7B model that is tens of
  # minutes. This ceiling only bounds how long the script WAITS; it changes
  # nothing about provider timeouts, retries or rate limiting.
  $p = @{ Uri = $Uri; Method = $Method; Headers = $Headers; TimeoutSec = 3600 }
  if ($Body) { $p.ContentType = 'application/json'; $p.Body = $Body }
  try { return Invoke-RestMethod @p }
  catch {
    $s = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
    # The body matters here. A 412 carrying REAL_MODEL_EXECUTION_REQUIRED is the
    # harness working correctly, and that token lives in the response body -- which
    # Invoke-RestMethod discards on a non-2xx status. Without reading it back the
    # script can only report "HTTP 412", which loses the only fact that matters.
    $parsed = $null
    if ($_.Exception.Response) {
      try {
        $stream = $_.Exception.Response.GetResponseStream()
        if ($null -ne $stream) {
          $reader = New-Object System.IO.StreamReader($stream)
          $body = $reader.ReadToEnd()
          $reader.Close()
          if ($body) { $parsed = $body | ConvertFrom-Json }
        }
      } catch { }
    }
    # A parsed error body must still carry its status. Returning it bare makes
    # StatusOf() fall through to 200, which prints a vacuous all-blank report
    # instead of failing -- a failure dressed up as a result.
    if ($null -ne $parsed) {
      $parsed | Add-Member -NotePropertyName '__status' -NotePropertyValue $s -Force
      $parsed | Add-Member -NotePropertyName '__message' -NotePropertyValue $_.Exception.Message -Force
      return $parsed
    }
    return [pscustomobject]@{ __status = $s; __message = $_.Exception.Message }
  }
}
function StatusOf($r) {
  if ($null -eq $r) { return 0 }
  if ($r.PSObject.Properties.Name -contains '__status') { return [int]$r.__status }
  return 200
}

Write-Host ''
Write-Host ('=' * 74) -ForegroundColor DarkGray
Write-Host ("PRISM EXTRACTION EVALUATION -- {0}" -f $Dataset.ToUpper()) -ForegroundColor White
Write-Host ('=' * 74) -ForegroundColor DarkGray

$login = Invoke-Prism -Uri "$Base/api/auth/login" -Headers @{} -Method POST `
        -Body (@{ username = $Username; password = $Password } | ConvertTo-Json -Compress)
if ((StatusOf $login) -ne 200) {
  Say "sign-in failed: HTTP $(StatusOf $login)" 'Red'
  exit 2
}
$h = @{ Authorization = "Bearer $($login.accessToken)" }
$role = $login.user.role
if ($role -notin @('VERIFIER', 'ADMIN')) {
  Say "this account is $role; evaluation is verifier-gated" 'Red'
  exit 2
}

if ($CorpusId -le 0) {
  $corpora = Invoke-Prism -Uri "$Base/api/corpora" -Headers $h
  $first = @($corpora) | Select-Object -First 1
  if ($null -eq $first) { Say 'no corpus available' 'Red'; exit 2 }
  $CorpusId = $first.id
}

# .NET reader, not Get-Content -Raw: in PowerShell 5.1 the latter attaches
# PSPath/PSDrive notes to the string and ConvertTo-Json then serialises those
# notes instead of the text, producing a body the server rejects with a 400 that
# looks nothing like the real cause.
$gold = [System.IO.File]::ReadAllText((Resolve-Path $GoldSetPath).Path)

# The two datasets measure different things and go to different endpoints, so
# they are not one parameterised call with a branch inside.
if ($Dataset -eq 'prose') {
  $describeUri = "$Base/api/llm-evaluation/prose/gold-set"
  $measureUri = "$Base/api/llm-evaluation/prose/extraction?corpusId=$CorpusId&requireRealModel=$($RequireRealModel.IsPresent)"
} else {
  $describeUri = "$Base/api/llm-evaluation/gold-set"
  $measureUri = "$Base/api/llm-evaluation/extraction?corpusId=$CorpusId"
}

# Ask what the labels contain before spending a single provider call.
$desc = Invoke-Prism -Uri $describeUri -Headers $h -Method POST `
        -Body (@{ goldSet = $gold } | ConvertTo-Json -Compress)
Say ''
Say "gold set      : $($GoldSetPath)"
if ($Dataset -eq 'prose') {
  Say "  version     : $($desc.datasetVersion)"
  Say "  documents   : $($desc.goldDocuments)"
  Say "  sentences   : $($desc.labelledSentences), of which $($desc.negativeSentences) expect nothing extracted ($($desc.negativesArePercentOfLabelled)%)"
  Say "  expected    : $($desc.expectedTriples) triples, $($desc.expectedClaims) claims"
  Say "  predicates  : $($desc.predicatesExercised) of 28 registered"
} else {
  Say "  rows        : $($desc.goldRows)"
  Say "  documents   : $($desc.goldDocuments)"
  Say "  predicates  : $($desc.predicatesExercised) of 28 registered"
}
Say ''
Say 'CORPUS LIMITATION (quote this with any figure below):' 'Yellow'
Say "  $($(if ($Dataset -eq 'prose') { $desc.datasetLimitation } else { $desc.corpusLimitation }))" 'Yellow'

Say ''
Say 'measuring... one provider call per chunk carrying a gold sentence.' 'Cyan'
Say 'this costs money against a real provider and takes a while.' 'DarkGray'

$report = Invoke-Prism -Uri $measureUri -Headers $h `
          -Method POST -Body (@{ goldSet = $gold } | ConvertTo-Json -Compress)
# Checked before the status test, because a 412 error body carries `status`
# rather than `__status`, so StatusOf would report it as a success.
if (($report.PSObject.Properties.Name -contains 'status') -and
    $report.status -eq 'REAL_MODEL_EXECUTION_REQUIRED') {
  Write-Host ''
  Write-Host '========================================================================' -ForegroundColor DarkGray
  Write-Host 'REAL_MODEL_EXECUTION_REQUIRED' -ForegroundColor Yellow
  Write-Host '========================================================================' -ForegroundColor DarkGray
  Say ''
  Say $report.reason 'Yellow'
  Say ''
  Say 'To run it for real:' 'Cyan'
  Say '  $env:LLM_PROVIDER      = ''openai''    # or any OpenAI-compatible endpoint'
  Say '  $env:LLM_BASE_URL      = ''https://your-endpoint/v1'''
  Say '  $env:LLM_API_KEY       = ''<key>'''    # never committed, never logged'
  Say '  $env:LLM_EXTRACT_MODEL = ''your-model'''
  Say '  # restart the backend, then:'
  Say ("  powershell -File scripts/llm-eval.ps1 -Username <v> -Password '<p>' -CorpusId {0} -Dataset {1} -RequireRealModel" -f $CorpusId, $Dataset) 'Cyan'
  Say ''
  Say 'Nothing was measured. This is NOT a passing run.' 'Red'
  exit 4
}

if ((StatusOf $report) -ne 200) {
  Say "evaluation failed: HTTP $(StatusOf $report) $($report.__message)" 'Red'
  exit 2
}

$isFake = ($report.provider -eq 'fake')
Say ''
Say ('=' * 74) -ForegroundColor DarkGray
if ($isFake) {
  Say 'PROVIDER: FAKE / TEST MODE -- DETERMINISTIC FIXTURE, NOT A MODEL' -Yellow
  Say '  These figures prove the harness and the pipeline work.' 'Yellow'
  Say '  They say NOTHING about any model. Do not quote them as model quality.' -Yellow
} else {
  Say "PROVIDER: $($report.provider)  (real inference)" -Green
}
Say ('=' * 74) -ForegroundColor DarkGray
Say ''
Say 'CONFIGURATION -- enough to reproduce this run'
Say ("  provider        : {0}" -f $report.provider)
Say ("  model           : {0}" -f $report.model)
Say ("  prompt version  : {0}" -f $report.promptVersion)
Say ("  temperature     : {0}" -f $report.temperature)
Say ("  max tokens      : {0}" -f $report.maxTokens)
Say ("  max retries     : {0}" -f $report.maxRetries)
Say ("  executed at     : {0}" -f $report.executedAt)
Say ("  gold rows       : {0} in {1} document(s)" -f $report.goldRows, $report.goldDocuments)
Say ("  chunks measured : {0}" -f $report.chunksEvaluated)
Say ''
Say 'EXTRACTION'
Say ("  triples   P={0:N4}  R={1:N4}  F1={2:N4}   (tp {3}, fp {4}, fn {5})" -f `
    $report.triplePrecision, $report.tripleRecall, $report.tripleF1, `
    $report.triples.truePositive, $report.triples.falsePositive, $report.triples.falseNegative)
Say ("  claims    P={0:N4}  R={1:N4}  F1={2:N4}   (tp {3}, fp {4}, fn {5})" -f `
    $report.claimPrecision, $report.claimRecall, $report.claimF1, `
    $report.claims.truePositive, $report.claims.falsePositive, $report.claims.falseNegative)

# Totals, because precision alone does not say how much was asked for. A reader
# seeing P=0.90 has no idea whether that was 10 triples or 100.
if ($Dataset -eq 'prose') {
  Say ''
  Say 'RAW TOTALS'
  $t = $report.tripleTotals
  Say ("  triples  expected={0}  predicted={1}  correct={2}  missed={3}  incorrect={4}" -f `
      $t.expected, $t.predicted, $t.correct, $t.missed, $t.incorrect)
  $c = $report.claimTotals
  Say ("  claims   expected={0}  predicted={1}  correct={2}  missed={3}  incorrect={4}" -f `
      $c.expected, $c.predicted, $c.correct, $c.missed, $c.incorrect)
  Say ("  labelled sentences={0}, of which {1} expect nothing to be extracted ({2}%)" -f `
      $report.labelledSentences, $report.negativeSentences,
      [int](100 * $report.negativeSentences / [Math]::Max($report.labelledSentences, 1)))
  Say ("  chunks evaluated={0}, of which {1} expect nothing (precision is observed there)" -f `
      $report.chunksEvaluated, $report.chunksWithoutExpectation)
}
Say ''
Say 'OUTPUT HEALTH'
Say ("  malformed rate   : {0:N4}  ({1} of {2})" -f $report.malformedRate, $report.malformedOutputs, $report.chunksEvaluated)
Say ("  quarantine rate  : {0:N4}  ({1} of {2})" -f $report.quarantineRate, $report.quarantined, $report.chunksEvaluated)
if ($Dataset -eq 'prose') {
  Say ("  ungrounded rate  : {0:N4}  ({1} of {2})" -f $report.ungroundedRate, $report.ungroundedQuarantines, $report.chunksEvaluated)
  Say '    (a model citing a sentence absent from the source; reported apart from the'
  Say '     quarantine rate because it is a grounding fault, not a formatting one)'
}
Say ("  provider errors  : {0}" -f $report.providerErrors)
Say ("  retries exhausted: {0}" -f $report.transientErrorsExhausted)
if ($null -ne $report.PSObject.Properties['unknownPredicatesRejected']) {
  Say ("  unknown predicate: {0}" -f $report.unknownPredicatesRejected)
}
Say ("  total latency    : {0} ms" -f $report.totalLatencyMs)
Say ''
Say 'MATCHING RULE'
Say "  $($report.matchingRule)"

$refused = @($report.chunks | Where-Object { -not $_.parsed })
if ($refused.Count -gt 0) {
  Say ''
  Say "REFUSED CHUNKS ($($refused.Count)) -- listed so a low score can be explained" 'DarkGray'
  $refused | Select-Object -First 10 | ForEach-Object {
    "    chunk {0,-5} {1,-24} attempt {2}  {3}" -f $_.chunkId, $_.quarantineReason, $_.attempt, $_.error
  }
}

if (-not $ReportPath) {
  $ReportPath = Join-Path $repo ("eval\report-{0}-{1}-{2}.json" -f $Dataset, $report.provider, `
      (Get-Date -Format 'yyyyMMdd-HHmmss'))
}
[System.IO.File]::WriteAllText($ReportPath, ($report | ConvertTo-Json -Depth 8), (New-Object System.Text.UTF8Encoding($false)))
Say ''
Say "report written: $ReportPath"

if ($report.chunksEvaluated -eq 0) {
  Say ''
  Say 'NOTHING WAS MEASURED: no chunk in this corpus carried a gold sentence.' 'Red'
  Say 'Check that the corpus was seeded from demo/corpus and that -CorpusId points at it.' 'Red'
  exit 2
}
if ($report.quarantined -eq $report.chunksEvaluated) {
  Say ''
  Say 'EVERY chunk was refused, so no triple was compared. The score above is vacuous.' 'Red'
  exit 3
}
exit 0
