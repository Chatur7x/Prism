<#
.SYNOPSIS
  Seeds a second demo corpus from real PDF files.

.DESCRIPTION
  `scripts/seed-demo.ps1` uploads 24 markdown memos and its figures are cited
  throughout the README and docs/evaluation.md. This script deliberately does
  not touch that corpus. Adding to it would falsify published numbers, so the PDF
  set lands in its own corpus with its own planted findings.

  It exists because PRISM's PDF path is a real code path -- PDFBox 3 extraction
  in FileTextExtractor -- and it should be demonstrated against a file this
  repository produced, not merely asserted to work.

  Generate the files first:

      python scripts/make-demo-pdfs.py

  What the corpus is built to show:

    * contradictions on genuinely single-valued relations (reports_to,
      parent_organization, subsidiary_of)
    * a BOUNDED violation: sources_from asserted four times against a maximum
      of three
    * multi-valued relations that must NOT be flagged, which is the half that
      matters -- an engine that flags everything scores perfectly on a corpus of
      conflicts and is useless in production
    * one document that yields nothing at all

.PARAMETER ApproveAll
  Drive every pending triple and claim through the human gate.

.PARAMETER Verifications
  How many claims to send to the verification path.

.EXAMPLE
  powershell -File scripts/seed-pdf-demo.ps1 -Username <u> -Password '<p>' -ApproveAll -Verifications 20
#>
[CmdletBinding()]
param(
  [string]$Base = 'http://localhost:8080',
  [string]$CorpusName = 'Delacroix Group PDF dossier',
  [string]$Username = '',
  [string]$Password = '',
  [switch]$ApproveAll,
  [int]$Verifications = 0,
  [switch]$ForceReupload
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Say($msg, $colour = 'Gray') { Write-Host $msg -ForegroundColor $colour }

# Some list endpoints answer with a bare JSON array and some with the page
# envelope. Under StrictMode, reaching for `.content` on a bare array throws
# rather than returning null, so the shape has to be probed rather than assumed.
# /api/corpora and /api/approval-queue answer bare; /api/documents envelopes.
#
# Defined up here rather than beside its first use: a function inside a later
# `if` block does not exist when an earlier block calls it, and PowerShell
# reports that as "not recognized" rather than as a definition-order problem.
function As-Array($value) {
  if ($null -eq $value) { return @() }
  if ($value -is [System.Array]) { return @($value) }
  if ($value.PSObject.Properties.Name -contains 'content') { return @($value.content) }
  return @($value)
}

# As-Array emits into the pipeline, so an empty result arrives as $null rather
# than as an empty array -- and $null.Count throws under StrictMode. Callers
# that read .Count wrap the call in @(), which restores array-ness.
function As-Countable($value) { return , @(As-Array $value) }

$repoRoot = Split-Path -Parent $PSScriptRoot
$corpusDir = Join-Path $repoRoot 'demo\pdf-corpus'

if (-not (Test-Path $corpusDir)) {
  Say "missing $corpusDir - run: python scripts/make-demo-pdfs.py" 'Red'
  exit 1
}

$pdfs = @(Get-ChildItem $corpusDir -Filter '*.pdf' | Sort-Object Name)
if ($pdfs.Count -eq 0) {
  Say "no PDFs in $corpusDir - run: python scripts/make-demo-pdfs.py" 'Red'
  exit 1
}
Say "found $($pdfs.Count) PDFs in demo\pdf-corpus" 'DarkGray'

# ---- credentials ----------------------------------------------------------

if ([string]::IsNullOrWhiteSpace($Username) -or [string]::IsNullOrWhiteSpace($Password)) {
  $Username = if ($env:PRISM_USERNAME) { $env:PRISM_USERNAME } else { $Username }
  $Password = if ($env:PRISM_PASSWORD) { $env:PRISM_PASSWORD } else { $Password }
}
if ([string]::IsNullOrWhiteSpace($Username) -or [string]::IsNullOrWhiteSpace($Password)) {
  Say 'supply -Username and -Password, or set PRISM_USERNAME and PRISM_PASSWORD.' 'Red'
  exit 1
}

# ---- retry helper ---------------------------------------------------------
#
# Auth is rate limited to 10 requests a minute and the approval endpoints are
# limited too. A Council-sized run makes well over a hundred calls, so without
# back-off this passes only when it is the first thing to touch the API -- a
# flaky test rather than a reliable one.
function Invoke-Prism {
  param(
    [Parameter(Mandatory = $true)][string]$Uri,
    [string]$Method = 'GET',
    $Body = $null,
    [int]$MaxAttempts = 8
  )
  $attempt = 0
  while ($true) {
    $attempt++
    try {
      $params = @{
        Uri = $Uri; Method = $Method; Headers = $script:headers
        TimeoutSec = 60; ErrorAction = 'Stop'
      }
      if ($null -ne $Body) {
        $params.ContentType = 'application/json'
        $params.Body = ($Body | ConvertTo-Json -Depth 8)
      }
      return Invoke-RestMethod @params
    } catch {
      # StrictMode makes $_.Exception.Response unsafe to touch directly: for
      # several exception types that property does not exist at all, and
      # reading it throws a second error that hides the real failure.
      $status = 0
      $hasResponse = $_.Exception.PSObject.Properties.Name -contains 'Response'
      if ($hasResponse) {
        $response = $_.Exception.Response
        if ($null -ne $response) {
          try { $status = [int]$response.StatusCode } catch { $status = 0 }
        }
      }
      if ($status -eq 429 -and $attempt -lt $MaxAttempts) {
        $wait = [Math]::Min(30, 2 * $attempt)
        Start-Sleep -Seconds $wait
        continue
      }
      throw
    }
  }
}

# ---- session --------------------------------------------------------------

# Declared before the first call, not after the login: Invoke-Prism reads
# $script:headers, and under StrictMode an unset variable throws rather than
# evaluating as empty. The login call therefore runs with an empty header set,
# which is exactly right -- it is the one unauthenticated request in the script.
$script:headers = @{}

try {
  $login = Invoke-Prism -Uri "$Base/api/auth/login" -Method Post -Body @{ username = $Username; password = $Password }
} catch {
  Say "login failed: $($_.Exception.Message)" 'Red'
  exit 1
}
$script:headers = @{ Authorization = "Bearer $($login.accessToken)" }
Say "signed in as $($Username)" 'DarkGray'

# ---- corpus ---------------------------------------------------------------

$corpusId = $null
try {
  $existing = Invoke-Prism -Uri "$Base/api/corpora?size=200"
  foreach ($c in (As-Array $existing)) {
    if ($c.name -eq $CorpusName) { $corpusId = [int]$c.id; break }
  }
} catch {
  Say "could not list corpora: $($_.Exception.Message)" 'Yellow'
}

if ($null -eq $corpusId) {
  $created = Invoke-Prism -Uri "$Base/api/corpora" -Method Post -Body @{
    name = $CorpusName
    description = 'Fictional group records delivered as real PDF files, for demonstrating the PDFBox extraction path. See scripts/make-demo-pdfs.py.'
  }
  $corpusId = [int]$created.id
  Say "created corpus $corpusId '$CorpusName'" 'Cyan'
} else {
  Say "reusing corpus $corpusId '$CorpusName'" 'DarkGray'
}

# ---- upload ---------------------------------------------------------------
#
# curl.exe rather than Invoke-RestMethod: Windows PowerShell 5.1 will not
# assemble a multipart/form-data body from a byte array, the 'file' part never
# reaches the server, and every upload fails with a confusing 400.
#
# The bearer token goes in a curl --config file rather than on the command line
# so it never appears in the process argument list where another process on the
# machine could read it.
$curlConfig = Join-Path ${env:TEMP} 'prism-pdf-curl.cfg'
[System.IO.File]::WriteAllText($curlConfig, "header = `"Authorization: Bearer $($login.accessToken)`"`n")

$have = @{}
try {
  $docs = Invoke-Prism -Uri "$Base/api/documents?corpusId=${corpusId}&size=500"
  foreach ($d in (As-Array $docs)) {
    if ($d.originalFilename) { $have[$d.originalFilename] = $d.id }
  }
} catch {
  Say "could not list existing documents: $($_.Exception.Message)" 'Yellow'
}

if ($ForceReupload) { $have = @{} }

Say "uploading through POST /api/documents (multipart, application/pdf)"
$uploaded = @()
foreach ($pdf in $pdfs) {
  if ($have.ContainsKey($pdf.Name)) {
    Say ("  already present {0,-42} doc #{1}" -f $pdf.Name, $have[$pdf.Name]) 'DarkGray'
    $uploaded += $have[$pdf.Name]
    continue
  }
  $stem = $pdf.BaseName -replace '^\d+-', ''
  $title = $stem -replace '-', ' '
  $title = $title.Substring(0, 1).ToUpper() + $title.Substring(1)

  $json = & curl.exe -s --config $curlConfig -X POST "$Base/api/documents" `
    -F "corpusId=$corpusId" `
    -F "title=$title" `
    -F "file=@$($pdf.FullName);type=application/pdf" 2>&1

  try {
    $result = $json | ConvertFrom-Json
    if ($null -eq $result.id) { throw "no id in response: $json" }
    $uploaded += [int]$result.id
    Say ("  queued {0,-42} doc #{1}" -f $pdf.Name, $result.id) 'DarkGray'
  } catch {
    Say "  FAILED $($pdf.Name) -> $json" 'Red'
  }
}
Remove-Item $curlConfig -ErrorAction SilentlyContinue

if ($uploaded.Count -eq 0) { Say 'no PDF was accepted; aborting.' 'Red'; exit 1 }

# ---- wait for ingestion ---------------------------------------------------

Say ''
Say 'waiting for background ingestion (PDFBox extraction, chunking, extraction)'
$settled = $false
for ($attempt = 0; $attempt -lt 150; $attempt++) {
  Start-Sleep -Seconds 3
  $pending = 0; $triples = 0; $claims = 0; $quarantined = 0; $chunks = 0
  try {
    $docs = Invoke-Prism -Uri "$Base/api/documents?corpusId=${corpusId}&size=200"
    foreach ($doc in (As-Array $docs)) {
      try {
        $p = Invoke-Prism -Uri "$Base/api/documents/$($doc.id)/progress"
        $status = [string]$p.status
        # Terminal states are enumerated positively rather than by excluding the
        # two that happened to be in flight. The first version of this loop
        # counted only PENDING and RUNNING as unfinished, so UPLOADED and
        # EXTRACTING counted as settled and the script reported totals while
        # seven of ten documents were still being ingested. A settle check that
        # passes on unknown states is not a settle check.
        if ($status -notin @('AWAITING_APPROVAL', 'APPROVED', 'REJECTED', 'COMPLETED', 'FAILED')) {
          $pending++
        }
        $triples += [int]$p.triplesFound
        $claims += [int]$p.claimsFound
        $quarantined += [int]$p.quarantinedCount
        $chunks += [int]$p.chunkCount
      } catch {
        $pending++
      }
    }
    if ($pending -eq 0) { $settled = $true; break }
  } catch {
    # transient; keep polling
  }
}

if (-not $settled) { Say 'ingestion did not settle within the time budget; numbers below are partial.' 'Yellow' }

Say ''
Say "documents $($docs.totalElements)  chunks $chunks  triples $triples  claims $claims  quarantined $quarantined"

# Per-document breakdown, because a corpus-level total cannot tell you which
# document produced nothing -- and the silent document is the one worth seeing.
Say ''
foreach ($doc in (As-Array $docs)) {
  try {
    $p = Invoke-Prism -Uri "$Base/api/documents/$($doc.id)/progress"
    Say ("  {0,-46} {1,-8} chunks={2,-3} triples={3,-3} claims={4,-3} quar={5}" -f `
      $doc.originalFilename, $p.status, $p.chunkCount, $p.triplesFound, $p.claimsFound, $p.quarantinedCount)
  } catch {
    Say ("  {0,-46} progress unavailable" -f $doc.originalFilename) 'DarkGray'
  }
}

# ---- the human gate -------------------------------------------------------

if ($ApproveAll) {
  Say ''
  Say 'driving the human approval gate (this is the step that makes it trusted)'

  # The approval queue answers a third shape: {"triples":[...],"claims":[...]}.
  # Neither a bare array nor the page envelope, so neither of the two helpers
  # above applies and reading it generically silently yields a single object --
  # which is how the first run of this script reported "1 pending proposal"
  # while 46 triples and 96 claims were waiting.
  $queue = Invoke-Prism -Uri "$Base/api/approval-queue?corpusId=${corpusId}&size=300"
  $queueTriples = As-Countable $queue.triples
  $queueClaims = As-Countable $queue.claims
  Say "  pending: $($queueTriples.Count) triples, $($queueClaims.Count) claims"

  $triplesApproved = 0
  $claimsApproved = 0
  $refused = 0

  foreach ($t in $queueTriples) {
    try {
      Invoke-Prism -Uri "$Base/api/triples/$($t.id)/approve" -Method Post -Body @{} | Out-Null
      $triplesApproved++
    } catch {
      # Counted, not retried. The quarantine path exists so an unapprovable
      # proposal cannot be promoted by pressing the button harder, and a silent
      # retry loop would hide exactly the case worth seeing.
      $refused++
    }
  }

  foreach ($c in $queueClaims) {
    try {
      Invoke-Prism -Uri "$Base/api/claims/$($c.id)/approve" -Method Post | Out-Null
      $claimsApproved++
    } catch {
      $refused++
    }
  }

  Say "  approved $triplesApproved triples, $claimsApproved claims, $refused refused"
}

if ($Verifications -gt 0) {
  Say ''
  Say "verifying up to $Verifications claims through the deterministic path"
  try {
    $result = Invoke-Prism -Uri "$Base/api/claims/verify-all?corpusId=${corpusId}&limit=${Verifications}" -Method Post
    Say ("  verified {0}" -f ($result | ConvertTo-Json -Compress -Depth 4))
  } catch {
    Say "  verification failed: $($_.Exception.Message)" 'Red'
  }
}

# ---- contradictions -------------------------------------------------------

Say ''
Say 'running the deterministic contradiction scan'
try {
  $scan = Invoke-Prism -Uri "$Base/api/contradictions/scan?corpusId=${corpusId}" -Method Post
  Say ("  scan: " + ($scan | ConvertTo-Json -Compress -Depth 4))
} catch {
  Say "  scan failed: $($_.Exception.Message)" 'Red'
}

$findings = Invoke-Prism -Uri "$Base/api/contradictions?corpusId=${corpusId}&size=100"
$list = As-Countable $findings
Say "  findings: $($list.Count)"
foreach ($f in $list) {
  $kind = 'RELATION'
  if ($f.PSObject.Properties.Name -contains 'violationType' -and $f.violationType) { $kind = [string]$f.violationType }
  $subject = if ($f.PSObject.Properties.Name -contains 'subjectName') { $f.subjectName } else { '' }
  $object = if ($f.PSObject.Properties.Name -contains 'objectName') { $f.objectName } else { '' }
  Say ("    [{0,-18}] {1} {2} {3}" -f $kind, $subject, $f.predicate, $object) 'Yellow'
}

Say ''
Say 'Done. Numbers above are measured, not predicted.' 'Cyan'