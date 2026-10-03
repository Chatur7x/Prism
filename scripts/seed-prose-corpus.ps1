# =============================================================================
# Load the prose evaluation corpus into a running PRISM
# =============================================================================
# The canonical demo corpus states every fact as `Subject predicate Object`, so
# it cannot measure extraction from natural prose: a model scores near 100% by
# pattern matching. This loads eval/prose-corpus/ -- prose that exercises
# pronouns, multi-clause sentences, passive voice, negation, hedging, temporal
# statements, several relations per sentence, irrelevant information, and text
# shaped like instructions.
#
# Uploaded through the real multipart endpoint, deliberately, so the real
# SentenceChunker and the real extraction path both run. Chunking the files here
# would measure a different chunker than the one the application uses, and any
# number produced that way would be about the wrong thing.
#
# Usage:
#   powershell -File scripts/seed-prose-corpus.ps1 -Username <u> -Password '<p>'
#
# Stops before the human approval gate. Extraction quality does not depend on
# approval, and approving the prose corpus would mix it into the demo corpus's
# contradiction and graph state.
# =============================================================================

[CmdletBinding()]
param(
  [string]$Base = 'http://localhost:8080',
  [string]$Username = '',
  [string]$Password = '',
  [string]$CorpusName = 'Prose evaluation corpus',
  [switch]$ForceReupload
)

$ErrorActionPreference = 'Continue'
function Say($text, $colour = 'Gray') { Write-Host $text -ForegroundColor $colour }

$repo = Split-Path -Parent $PSScriptRoot
$corpusDir = Join-Path $repo 'eval\prose-corpus'
$goldPath = Join-Path $repo 'eval\prose-gold-v1.json'

if (-not (Test-Path $corpusDir)) { Say "prose corpus not found: $corpusDir" 'Red'; exit 2 }
if (-not (Test-Path $goldPath)) { Say "gold set not found: $goldPath" 'Red'; exit 2 }
if ([string]::IsNullOrWhiteSpace($Username) -or [string]::IsNullOrWhiteSpace($Password)) {
  Say 'supply -Username and -Password. An empty credential produces a 400 that reads like a' 'Yellow'
  Say 'server fault rather than a missing argument.' 'Yellow'
  exit 2
}

function Invoke-Prism {
  param([string]$Uri, [hashtable]$Headers, [string]$Method = 'GET', $Body = $null)
  $p = @{ Uri = $Uri; Method = $Method; Headers = $Headers; TimeoutSec = 180 }
  if ($null -ne $Body) { $p.ContentType = 'application/json'; $p.Body = ($Body | ConvertTo-Json -Depth 8) }
  try { return Invoke-RestMethod @p }
  catch {
    $s = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
    if ($s -eq 429) { return [pscustomobject]@{ __status = 429 } }
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
Write-Host 'PROSE EVALUATION CORPUS' -ForegroundColor White
Write-Host ('=' * 74) -ForegroundColor DarkGray

$memos = Get-ChildItem $corpusDir -Filter '*.md' | Sort-Object Name
$gold = [System.IO.File]::ReadAllText((Resolve-Path $goldPath).Path) | ConvertFrom-Json
Say ''
Say "corpus files : $($memos.Count)"
Say "gold version : $($gold.datasetVersion)"
Say "labelled     : $(@($gold.documents | ForEach-Object { $_.sentences }).Count) sentences across $(@($gold.documents).Count) documents"
Say ''
Say 'NOTE: the gold set states facts in prose, so scores here measure extraction'
Say 'from natural prose. That is a different question from the canonical demo' 'Yellow'
Say "corpus, whose limitation is recorded in the gold set's own header." 'Yellow'

# Auth is rate-limited to 10/minute; only one sign-in happens here.
$login = Invoke-Prism -Uri "$Base/api/auth/login" -Headers @{} -Method POST `
        -Body (@{ username = $Username; password = $Password })
if ((StatusOf $login) -ne 200) { Say "sign-in failed: HTTP $(StatusOf $login)" 'Red'; exit 2 }
$token = $login.accessToken
$headers = @{ Authorization = "Bearer $token" }
Say "signed in as $($login.user.username) ($($login.user.role))"

# ---- corpus ----------------------------------------------------------------
$corpusId = 0
try {
  $mine = Invoke-Prism -Uri "$Base/api/corpora" -Headers $headers
  foreach ($c in @($mine)) {
    if ($c.name -eq $CorpusName) { $corpusId = [int]$c.id; break }
  }
} catch { }

if ($corpusId -eq 0) {
  $corpus = Invoke-Prism -Uri "$Base/api/corpora" -Headers $headers -Method POST -Body @{
    name        = $CorpusName
    description = 'Fictional report prose for extraction-quality evaluation. See eval/prose-gold-v1.json.'
  }
  if ((StatusOf $corpus) -ne 200 -and (StatusOf $corpus) -ne 201) {
    Say "could not create the corpus: HTTP $(StatusOf $corpus) $($corpus.__message)" 'Red'; exit 2
  }
  $corpusId = [int]$corpus.id
  Say "created corpus #$corpusId '$CorpusName'" 'Green'
} else {
  Say "reusing corpus #$corpusId '$CorpusName'" 'Green'
}

# ---- upload ----------------------------------------------------------------
# Token via a curl config file rather than the command line, so it never
# appears in the process argument list where another process could read it.
$curlConfig = Join-Path ${env:TEMP} "prism-prose-curl.cfg"
[System.IO.File]::WriteAllText($curlConfig, "header = `"Authorization: Bearer $token`"`n")

$existing = @{}
try {
  $have = Invoke-Prism -Uri "$Base/api/documents?corpusId=$corpusId&size=500" -Headers $headers
  foreach ($d in @($have.content)) {
    if ($d.originalFilename) { $existing[$d.originalFilename] = $d.id }
  }
} catch { }

$toUpload = @($memos | Where-Object { $ForceReupload -or -not $existing.ContainsKey($_.Name) })
if ($toUpload.Count -eq 0) {
  Say ''
  Say "all $($memos.Count) memos already present; nothing to upload." 'Yellow'
  Say 'pass -ForceReupload to add them again.' 'DarkGray'
} else {
  Say ''
  Say "uploading $($toUpload.Count) memo(s) through the real multipart endpoint" 'Cyan'
  foreach ($memo in $toUpload) {
    $stem = $memo.BaseName -replace '^\d+-', ''
    $title = ($stem -replace '-', ' ')
    $title = $title.Substring(0, 1).ToUpperCase() + $title.Substring(1)
    $json = & curl.exe -s --config $curlConfig -X POST "$Base/api/documents" `
      -F "corpusId=$corpusId" -F "title=$title" -F "file=@$($memo.FullName);type=text/markdown" 2>&1
    try {
      $r = $json | ConvertFrom-Json
      if ($null -eq $r.id) { throw "no id in response: $json" }
      Say ("  queued {0,-44} doc #{1}" -f $memo.Name, $r.id) 'DarkGray'
    } catch {
      Say "  FAILED $($memo.Name) -> $json" 'Red'
    }
  }
}
Remove-Item $curlConfig -ErrorAction SilentlyContinue

# ---- wait for ingestion ----------------------------------------------------
Say ''
Say 'waiting for background ingestion (chunking + extraction per memo)' 'Cyan'
$docIds = @()
try {
  $docs = Invoke-Prism -Uri "$Base/api/documents?corpusId=$corpusId&size=200" -Headers $headers
  $docIds = @($docs.content | ForEach-Object { $_.id })
} catch { }

$settled = $false
for ($i = 0; $i -lt 60; $i++) {
  Start-Sleep -Seconds 2
  $inFlight = 0; $quarantined = 0
  foreach ($id in $docIds) {
    try {
      $p = Invoke-Prism -Uri "$Base/api/documents/$id/progress" -Headers $headers
      if ($p.status -notin @('AWAITING_APPROVAL', 'READY', 'FAILED')) { $inFlight++ }
      $quarantined += [int]$p.quarantinedCount
    } catch { $inFlight++ }
  }
  Write-Host ("    {0,3}s  in-flight={1}  quarantined={2}" -f ($i * 2), $inFlight, $quarantined) -ForegroundColor DarkGray
  if ($inFlight -eq 0 -and $docIds.Count -gt 0) { $settled = $true; break }
}

if (-not $settled) { Say ''; Say 'ingestion did not settle within 120s' 'Red'; exit 3 }

Say ''
Say "ingestion settled."
Say ''
Say 'Next: measure extraction against the hand-written labels.'
Say "  powershell -File scripts/llm-eval.ps1 -Username <v> -Password '<p>' -CorpusId $corpusId -Dataset prose"
Say ''
Say 'Against the offline provider this proves the harness runs end to end and'
Say 'says nothing about model quality. For a real model, set LLM_PROVIDER=openai'
Say 'and the run will refuse to report fixture numbers as model numbers.'
Say ''
Write-Host ('=' * 74) -ForegroundColor DarkGray
Write-Host "CORPUS_ID=$corpusId" -ForegroundColor White
Write-Host "USERNAME=$Username" -ForegroundColor White
Write-Host ('=' * 74) -ForegroundColor DarkGray
exit 0