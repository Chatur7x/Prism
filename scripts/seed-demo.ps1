# PRISM demo corpus seeder.
#
# Drives the fictional demo corpus through the real HTTP API — no database
# writes, no fixtures. That matters: a seeder that inserts rows directly would
# happily create data the application itself could not produce, and the demo
# would then prove nothing.
#
# The corpus is designed to exercise every stage and every failure mode:
#
#   * 24 memos, so ingestion spans many background jobs
#   * four planted contradictions on SINGLE-cardinality predicates
#     (reports_to, headquartered_in, chief_executive, parent_organization), each
#     stated twice with different values in two separate memos
#   * one bounded-cardinality violation: a supplier set larger than the
#     registry's declared cap
#   * many MULTI-cardinality relations stated more than once, which must NOT be
#     flagged — this is what proves the predicate registry is doing real work
#   * one deliberately uninformative memo, so grounded chat has realistic text
#     to reject and can demonstrate a refusal rather than a fabricated answer
#
# Deliberately ASCII-only. Windows PowerShell 5.1 reads a .ps1 as ANSI unless it
# carries a UTF-8 BOM, so a non-ASCII literal here would be silently mangled into
# mojibake before it ever reached the API. Keeping the script ASCII avoids a class
# of bug that looks like a server-side encoding fault.
#
# By default it stops before approval, leaving the human gate for the operator.
# Pass -ApproveAll to drive approvals and verification as well.
param(
  [string]$Base = 'http://localhost:8080',
  [string]$CorpusName = 'Meridian Group demo corpus',
  [string]$Username = '',
  [string]$Password = '',
  # Reuse an existing corpus instead of creating and populating a new one.
  # Lets -ApproveAll drive the human gate over memos that are already ingested.
  [long]$CorpusId = 0,
  [switch]$ApproveAll,
  [int]$Verifications = 0,
  [switch]$ForceReupload
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$corpusDir = Join-Path $root 'demo\corpus'

function Say($msg, $colour = 'Gray') { Write-Host $msg -ForegroundColor $colour }

# ---- rate-limit aware request helper --------------------------------------

# PRISM rate-limits its write endpoints, and it should: bulk approval is exactly
# the kind of privileged operation worth bounding. A seeder that simply ignores
# that is not a valid demonstration of anything, so it backs off the way a real
# client would rather than being special-cased.
$script:rateLimitRetries = 0

function Invoke-Prism {
  param(
    [Parameter(Mandatory = $true)][string]$Uri,
    [string]$Method = 'GET',
    [object]$Body = $null,
    [int]$MaxAttempts = 8
  )

  for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
    try {
      $params = @{
        Uri        = $Uri
        Method     = $Method
        Headers    = $headers
        TimeoutSec = 900
      }
      if ($null -ne $Body) {
        $params.ContentType = 'application/json'
        $params.Body = ($Body | ConvertTo-Json -Depth 6)
      }
      return Invoke-RestMethod @params
    } catch {
      $status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
      if ($status -eq 429 -and $attempt -lt $MaxAttempts) {
        $script:rateLimitRetries++
        # Honour Retry-After when the server sends one, else exponential backoff
        # capped at 8s. Jitter stops concurrent seeds from re-colliding.
        $wait = [Math]::Min(8000, 250 * [Math]::Pow(2, $attempt - 1))
        try {
          $header = $_.Exception.Response.Headers['Retry-After']
          if ($header) {
            $parsed = 0
            if ([int]::TryParse(($header | Select-Object -First 1), [ref]$parsed)) {
              $wait = [Math]::Max($wait, $parsed * 1000)
            }
          }
        } catch { }
        Start-Sleep -Milliseconds ($wait + (Get-Random -Minimum 0 -Maximum 120))
        continue
      }
      throw
    }
  }
  throw "rate limit not cleared after $MaxAttempts attempts"
}

# ---- authenticate ---------------------------------------------------------

if (-not $Username) {
  $suffix = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
  $Username = "demo_operator_$suffix"
  $Password = 'DemoOperator!2026x'
  $register = @{
    username = $Username
    email    = "$Username@example.com"
    password = $Password
  } | ConvertTo-Json
  $created = Invoke-RestMethod -Method Post -Uri "$Base/api/auth/register" `
    -ContentType 'application/json' -Body $register -TimeoutSec 30
  Say "created $Username (role $($created.user.role))" 'Yellow'
  Say "PRISM grants no self-registration elevated rights, so approval steps" 'DarkGray'
  Say "will fail with 403 until an administrator promotes this account." 'DarkGray'
}

$login = Invoke-RestMethod -Method Post -Uri "$Base/api/auth/login" `
  -ContentType 'application/json' `
  -Body (@{ username = $Username; password = $Password } | ConvertTo-Json) -TimeoutSec 30
$headers = @{ Authorization = "Bearer $($login.accessToken)" }
Say "signed in as $($login.user.username) ($($login.user.role))"

$isVerifier = $login.user.role -in @('VERIFIER', 'ADMIN')

# ---- corpus ---------------------------------------------------------------

$reuseExisting = $CorpusId -gt 0
if ($reuseExisting) {
  $corpusId = $CorpusId
  try {
    $existing = Invoke-RestMethod -Uri "$Base/api/corpora/${corpusId}" -Headers $headers -TimeoutSec 30
    $CorpusName = $existing.name
  } catch {
    Say "corpus #$corpusId is not readable by ${Username}: $($_.Exception.Message)" 'Red'
    return
  }
  Say "reusing existing corpus #$corpusId '$CorpusName'" 'Green'
} else {
  $corpus = Invoke-RestMethod -Method Post -Uri "$Base/api/corpora" -Headers $headers `
    -ContentType 'application/json' `
    -Body (@{
      name        = $CorpusName
      description = 'Fictional group records with deliberate contradictions, for a full PRISM demonstration.'
    } | ConvertTo-Json) -TimeoutSec 30
  $corpusId = $corpus.id
  Say "corpus #$corpusId '$CorpusName'" 'Green'
}

# ---- upload ---------------------------------------------------------------

if ($reuseExisting) {
  Say 'skipping upload: -CorpusId was supplied, so the memos are already ingested.' 'DarkGray'
  $curlConfig = $null
  $uploadedIds = @()
} else {

# Uploads go through curl.exe rather than Invoke-RestMethod's -Body.
#
# Two reasons, both practical:
#   * Windows PowerShell 5.1 will not assemble a multipart/form-data body
#     correctly from a byte array; the 'file' part never reaches the server and
#     every upload fails with a confusing 400/500.
#   * The bearer token is passed via a curl --config file rather than on the
#     command line, so it never appears in the process argument list where any
#     other process on the machine could read it.
$curlConfig = Join-Path ${env:TEMP} "prism-seed-curl.cfg"
# The bearer token is passed via a curl --config file rather than on the
# command line, so it never appears in the process argument list where any other
# process on the machine could read it. (`-H @file` looks like it would do this
# and does not: curl has no such option and treats the @ as a literal path.)
[System.IO.File]::WriteAllText($curlConfig, "header = `"Authorization: Bearer $($login.accessToken)`"`n")

$memos = Get-ChildItem $corpusDir -Filter '*.md' | Sort-Object Name

# Idempotence guard.
#
# This script was not idempotent: running it twice uploaded all 24 memos again,
# leaving 48 documents for 24 files. That silently doubles every count in the
# documentation and makes the demo corpus ambiguous -- two identical copies of
# the same memo competing in retrieval. The server dedupes triples by content
# hash but not documents, so nothing upstream caught it.
#
# Rather than change upload semantics for every caller, the seeder checks what is
# already there. Documents are identified by original filename, which is the one
# thing that survives a re-upload.
$existing = @{}
try {
  $have = Invoke-RestMethod -Method Get -Uri "$Base/api/documents?corpusId=$corpusId&size=500" `
         -Headers @{ Authorization = "Bearer $($login.accessToken)" } -TimeoutSec 60
  foreach ($d in @($have)) {
    if ($d.originalFilename) { $existing[$d.originalFilename] = $d.id }
  }
} catch {
  Say "could not list existing documents: $($_.Exception.Message)" 'Yellow'
}

if ($existing.Count -eq $memos.Count) {
  Say ''
  Say "corpus $corpusId already holds all $($memos.Count) memos; skipping upload." 'Yellow'
  Say '  Pass -ForceReupload to add them again, or drop the corpus and re-run.' 'Yellow'
  $uploadedIds = @($existing.Values)
} else {
  if ($existing.Count -gt 0) {
    Say ''
    Say "corpus holds $($existing.Count) of $($memos.Count) memos; uploading the rest." 'Yellow'
  }
  Say "uploading $($memos.Count) memos through the real multipart endpoint (MD extraction)"

  $uploadedIds = @()
  foreach ($memo in $memos) {
    if ($existing.ContainsKey($memo.Name)) {
      Say ("  already present {0,-36} doc #{1}" -f $memo.Name, $existing[$memo.Name]) 'DarkGray'
      $uploadedIds += $existing[$memo.Name]
      continue
    }
    # "01-meridian-q1-board-memo" -> "Meridian q1 board memo"
    $stem = $memo.BaseName -replace '^\d+-', ''
    $title = ($stem -replace '-', ' ')
    $title = $title.Substring(0, 1).ToUpper() + $title.Substring(1)

    $json = & curl.exe -s --config $curlConfig -X POST "$Base/api/documents" `
      -F "corpusId=$corpusId" `
      -F "title=$title" `
      -F "file=@$($memo.FullName);type=text/markdown" 2>&1

    try {
      $result = $json | ConvertFrom-Json
      if ($null -eq $result.id) { throw "no id in response: $json" }
      $uploadedIds += $result.id
      Say ("  queued {0,-46} doc #{1}" -f $memo.Name, $result.id) 'DarkGray'
    } catch {
      Say "  FAILED $($memo.Name) -> $json" 'Red'
    }
  }
}
Remove-Item $curlConfig -ErrorAction SilentlyContinue

if ($uploadedIds.Count -eq 0 -and -not $reuseExisting) {
  Say 'no memo was accepted; aborting before the polling loop.' 'Red'
  return
}
}

# ---- wait for ingestion ---------------------------------------------------

Say ''
Say 'waiting for background ingestion to settle (chunking + extraction per memo)'

# Progress is summed per document rather than read from a corpus statistics
# endpoint: that endpoint is verifier-gated, because its pending counts are the
# operational picture of the human gate, and this script's first run is
# deliberately an ANALYST so the gate stays visible.
$pending = 0
$triples = 0
$claims = 0
$quarantined = 0
$chunks = 0
$settled = $false

for ($attempt = 0; $attempt -lt 150; $attempt++) {
  Start-Sleep -Seconds 3

  # Deliberately NOT wrapped in @(). Windows PowerShell 5.1 returns a JSON array
  # from Invoke-RestMethod as a single unrolled collection, so @() around it
  # produces an array containing an array: Count becomes 1 and every property
  # access returns a space-joined string. Assigning straight through keeps Count
  # correct for both the single-document and many-document cases.
  $docs = Invoke-RestMethod -Uri "$Base/api/documents?corpusId=${corpusId}&size=200" -Headers $headers -TimeoutSec 30
  if ($null -eq $docs) { $docs = @() }
  $docList = @($docs)
  if ($docList.Count -eq 1 -and $docList[0] -is [array]) { $docList = $docList[0] }

  $pending = 0
  $triples = 0
  $claims = 0
  $quarantined = 0
  $chunks = 0
  foreach ($doc in $docList) {
    if ($doc.status -in @('UPLOADED', 'CHUNKING', 'CHUNKED', 'EXTRACTING')) { $pending++ }
    try {
      $p = Invoke-RestMethod -Uri "$Base/api/documents/$($doc.id)/progress" -Headers $headers -TimeoutSec 20
      $triples += [int]$p.triplesFound
      $claims += [int]$p.claimsFound
      $quarantined += [int]$p.quarantinedCount
      $chunks += [int]$p.chunkCount
    } catch {
      # A document that cannot be read is counted as still in flight rather than
      # silently treated as settled, so the loop cannot exit early.
      $pending++
    }
  }

  Write-Host ("  {0,3}s  docs={1,-3} in-flight={2,-3} chunks={3,-4} triples={4,-4} claims={5,-4} quarantined={6}" -f `
      ($attempt * 3), $docList.Count, $pending, $chunks, $triples, $claims, $quarantined) -ForegroundColor DarkGray

  if ($pending -eq 0 -and $docList.Count -gt 0) { $settled = $true; break }
}

Say ''
if ($settled) {
  Say "ingestion complete: $chunks chunks, $triples triples, $claims claims, $quarantined quarantined" 'Green'
} else {
  Say 'ingestion did not settle within the time budget; the corpus is still processing.' 'Red'
}

# Surface failures explicitly. A document that failed to ingest is the single most
# important thing to report: a corpus that looks populated but is missing a third
# of its memos is worse than one that says so.
$failed = @($docList | Where-Object { $_.status -eq 'FAILED' })
if ($failed.Count -gt 0) {
  Say ''
  Say "$($failed.Count) document(s) FAILED to ingest:" 'Red'
  foreach ($doc in $failed) { Say "  #$($doc.id) $($doc.title)" 'Red' }
}
if ($quarantined -gt 0) {
  Say ''
  Say "$quarantined model response(s) were quarantined." 'Yellow'
  Say 'They are retained verbatim and shown on each document page: that is the' 'Yellow'
  Say 'system reporting model misbehaviour rather than hiding it.' 'Yellow'
}

# ---- approval and verification -------------------------------------------

if (-not $ApproveAll) {
  Say ''
  Say "stopping before the human gate, which is where a demonstration should start." 'Cyan'
  Say "sign in as $Username and open the approval queue." 'Cyan'
  Say "re-run with -ApproveAll to drive approvals, verification and detection." 'DarkGray'
  Write-Host ''
  Say "CORPUS_ID=$corpusId" 'White'
  Say "USERNAME=$Username" 'White'
  return
}

if (-not $isVerifier) {
  Say ''
  Say "cannot approve: $Username is $($login.user.role). Promote to VERIFIER and re-run -ApproveAll." 'Red'
  return
}

Say ''
Say 'approving every pending triple and claim (this is the human gate; the rate'
Say 'limiter applies, so this backs off rather than hammering)'
$queue = Invoke-Prism -Uri "$Base/api/approval-queue?corpusId=${corpusId}&size=200"
$approvedTriples = 0
foreach ($t in $queue.triples) {
  try {
    Invoke-Prism -Method Post -Uri "$Base/api/triples/$($t.id)/approve" `
      -Body @{ note = 'demo: approved as self-consistent' } | Out-Null
    $approvedTriples++
  } catch {
    $status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 'ERR' }
    Say "  triple $($t.id) failed ($status): $($_.Exception.Message)" 'Red'
  }
}
Say "  triples: $approvedTriples approved"

$approvedClaims = 0
foreach ($c in $queue.claims) {
  try {
    Invoke-Prism -Method Post -Uri "$Base/api/claims/$($c.id)/approve" | Out-Null
    $approvedClaims++
  } catch {
    $status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 'ERR' }
    Say "  claim $($c.id) failed ($status): $($_.Exception.Message)" 'Red'
  }
}
Say "  claims: $approvedClaims approved"
Say "approved $approvedTriples triples and $approvedClaims claims ($script:rateLimitRetries rate-limit backoffs)" 'Green'

if ($Verifications -gt 0) {
  Say ''
  Say "verifying $Verifications claims (rule analysis, then retrieval, then the judge model)"
  $result = Invoke-Prism -Method Post -Uri "$Base/api/claims/verify-all?corpusId=${corpusId}&limit=${Verifications}"
  Say "attempted=$($result.attempted) succeeded=$($result.succeeded) failed=$($result.failed)" 'Green'
}

Say ''
Say 'scanning for contradictions'
$scan = Invoke-Prism -Method Post -Uri "$Base/api/contradictions/scan?corpusId=${corpusId}"
Say "findings=$($scan.findings) created=$($scan.created) unchanged=$($scan.unchanged)" 'Green'
Say '(created=0 on a rescan is the idempotency guarantee, not a failure)'

$findings = Invoke-Prism -Uri "$Base/api/contradictions?corpusId=${corpusId}&size=50"
foreach ($f in $findings.content) {
  Say ''
  Say "  #$($f.id) [$($f.contradictionType)] rule=$($f.ruleCode) $($f.ruleVersion)" 'Cyan'
  Say "     subject: $($f.subjectText)  predicate: $($f.predicate)" 'DarkGray'
  Say "     left:  $($f.leftDescription)" 'DarkGray'
  Say "     right: $($f.rightDescription)" 'DarkGray'
  if ($f.explanation) { Say "     why: $($f.explanation)" 'DarkGray' }
}

Write-Host ''
Say "CORPUS_ID=$corpusId" 'White'
Say "USERNAME=$Username" 'White'
Say "Open http://localhost:5173 and select '$CorpusName'." 'White'
