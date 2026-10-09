<#
PRISM load test harness.

Creates N users through the real HTTP API and drives N documents through the
real ingestion pipeline, then measures throughput, upload-ack latency and
time-to-settled.

Sequential by design: this machine's remaining CPU is already committed to a
CPU-bound local LLM, and a self-inflicted concurrency war would measure the
load generator rather than the server. A separate concurrent read phase probes
server-side concurrency, which is the interesting axis anyway.

Uses LLM_PROVIDER=fake so the numbers are comparable with docs/performance.md.
Nothing here is a scalability claim.
#>

param(
    [string]$Base = 'http://localhost:8080',
    [string]$AdminUsername = '',
    [string]$AdminPassword = '',
    [int]$UserCount = 100,
    [int]$DocCount = 100,
    [string]$CorpusName,
    [int]$PollIntervalSec = 2,
    [int]$SettleTimeoutSec = 600,
    [switch]$SkipUserCreation,
    [int]$ConcurrentReads = 0
)

$ErrorActionPreference = 'Continue'
$script:startTime = (Get-Date)

function Say($msg, $colour = 'Gray') {
    Write-Host ('[{0}] {1}' -f ((Get-Date) - $script:startTime).ToString('mm\:ss'), $msg) -ForegroundColor $colour
}

if (-not $CorpusName) {
    $CorpusName = 'LoadTest {0}' -f (Get-Date -Format 'yyyyMMdd-HHmmss')
}

# ---------- rate-limit aware request ----------
$script:rateLimitRetries = 0
function Invoke-Prism {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [string]$Method = 'GET',
        $Body = $null,
        [hashtable]$Headers,
        [int]$MaxAttempts = 8
    )
    for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
        try {
            $p = @{ Uri = $Uri; Method = $Method; Headers = $Headers; TimeoutSec = 900 }
            if ($null -ne $Body) {
                $p.ContentType = 'application/json'
                $p.Body = ($Body | ConvertTo-Json -Depth 6)
            }
            return Invoke-RestMethod @p
        } catch {
            $status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
            if ($status -eq 429 -and $attempt -lt $MaxAttempts) {
                $script:rateLimitRetries++
                $wait = [Math]::Min(30000, 1000 * [Math]::Pow(2, $attempt - 1))
                $wait = [Math]::Min($wait, 60000)
                Say ("  429 on {0}; backing off {1} ms (attempt {2})" -f $Uri, $wait, $attempt) 'Yellow'
                Start-Sleep -Milliseconds ($wait + (Get-Random -Minimum 0 -Maximum 500))
                continue
            }
            throw
        }
    }
    throw "rate limit not cleared after $MaxAttempts attempts"
}

# ---------- login ----------
# Credentials come from the operator, never the repository. A hardcoded
# default here would be a credential in version control.
if ([string]::IsNullOrWhiteSpace($AdminUsername) -or [string]::IsNullOrWhiteSpace($AdminPassword)) {
    Say 'Supply -AdminUsername and -AdminPassword. No default is provided: a credential' 'Red'
    Say 'in the repository is a credential in version control.' 'Red'
    exit 2
}
Say 'Logging in as admin'
$loginBody = @{ username = $AdminUsername; password = $AdminPassword }
$loginResp = Invoke-Prism -Uri "$Base/api/auth/login" -Method POST -Body $loginBody
$adminHeaders = @{ Authorization = "Bearer $($loginResp.accessToken)" }
Say ('admin token acquired, expires {0}' -f $loginResp.expiresAt) 'Green'

# ---------- users ----------
$userElapsed = 0.0
$createdUsers = @()
if (-not $SkipUserCreation) {
    Say ("Creating {0} users via POST /api/admin/users" -f $UserCount)
    $t0 = (Get-Date)
    for ($i = 1; $i -le $UserCount; $i++) {
        $suffix = '{0}_{1:D4}' -f (Get-Date -Format 'yyyyMMddHHmmss'), $i
        $username = 'load_{0}' -f $suffix
        $email = '{0}@prism.local' -f $username
        $password = 'LoadTest!2026x_{0}' -f $i
        $role = if ($i -le 3) { 'VERIFIER' } else { 'ANALYST' }
        $body = @{ username = $username; email = $email; password = $password; role = $role }
        try {
            $resp = Invoke-Prism -Uri "$Base/api/admin/users" -Method POST -Headers $adminHeaders -Body $body
            $createdUsers += [pscustomobject]@{
                Username = $username; Password = $password; Role = $role; Id = $resp.id
            }
        } catch {
            $err = $_
            $st = if ($err.Exception.Response) { [int]$err.Exception.Response.StatusCode } else { 0 }
            $msg = $err.Exception.Message
            Say ('  create failed {0}: HTTP {1} {2}' -f $username, $st, $msg) 'Red'
        }
        if ($i % 25 -eq 0 -or $i -eq $UserCount) { Say ('  users {0}/{1}' -f $i, $UserCount) }
    }
    $userElapsed = ((Get-Date) - $t0).TotalSeconds
    Say ('users created: {0} in {1}s ({2}/s)' -f $createdUsers.Count, [math]::Round($userElapsed, 1), [math]::Round($createdUsers.Count / [Math]::Max($userElapsed, 0.001), 2)) 'Green'
} else {
    Say 'Skipping user creation; reusing load_* users'
    $page = 0
    do {
        $list = Invoke-Prism -Uri "$Base/api/admin/users?page=$page&size=200" -Headers $adminHeaders
        foreach ($u in $list.content) {
            if ($u.username -like 'load_*') {
                $createdUsers += [pscustomobject]@{ Username = $u.username; Role = $u.role; Id = $u.id; Password = $null }
            }
        }
        $page++
    } while ($list.hasNext)
    Say ('reusing {0} existing load_* users' -f $createdUsers.Count)
}

# ---------- corpus ----------
Say ("Creating corpus '{0}'" -f $CorpusName)
$corpusResp = Invoke-Prism -Uri "$Base/api/corpora" -Method POST -Headers $adminHeaders -Body @{ name = $CorpusName; description = 'load test corpus' }
$corpusId = $corpusResp.id
Say ("corpus id={0}" -f $corpusId) 'Green'

# Tokens are acquired ONCE per user, up front, not per document. The auth
# bucket is 10 requests/min per source IP, so a per-document login turns a
# 1000-doc run into a 100-minute auth stall. JWT TTL is 60 min, so one token
# per user covers the whole upload phase at any realistic run size.
$userTokens = @{}
Say ("Acquiring tokens for {0} users (paced to the 10/min auth bucket)" -f $createdUsers.Count)
$t0tok = (Get-Date)
$tokCount = 0
foreach ($u in $createdUsers) {
    if (-not $u.Password) { continue }
    try {
        $lr = Invoke-Prism -Uri "$Base/api/auth/login" -Method POST -Body @{ username = $u.Username; password = $u.Password }
        $userTokens[$u.Username] = $lr.accessToken
        $tokCount++
        if ($tokCount % 8 -eq 0) { Start-Sleep -Seconds 60 }
    } catch {
        $err = $_
        Say ('  token failed {0}: {1}' -f $u.Username, $err.Exception.Message) 'Red'
    }
}
Say ('tokens acquired: {0} in {1}s' -f $tokCount, [math]::Round(((Get-Date) - $t0tok).TotalSeconds, 1)) 'Green'

# ---------- documents ----------
Say ("Uploading {0} documents to corpus {1}" -f $DocCount, $corpusId)
$uploadResults = New-Object System.Collections.ArrayList
$t0 = (Get-Date)
for ($i = 1; $i -le $DocCount; $i++) {
    $user = $createdUsers[($i - 1) % [Math]::Max($createdUsers.Count, 1)]
    $uname = if ($user) { $user.Username } else { '' }
    $token = if ($uname -and $userTokens.ContainsKey($uname)) { $userTokens[$uname] } else { $loginResp.accessToken }
    $h = @{ Authorization = "Bearer $token" }
    $content = @"
Load test document {0} issued {1}.
Corpus: {2}
This document is unique synthetic text exercising the PRISM ingestion pipeline.
Acme Corporation supplies Beta Industries.
Beta Industries is headquartered in New York.
Gamma Labs reports to Acme Corporation.
The chief executive of Acme Corporation is Jane Doe.
The parent organization of Beta Industries is Acme Corporation.
Document identifier: {3}
"@ -f $i, (Get-Date -Format 'o'), $CorpusName, ([guid]::NewGuid().ToString())
    $body = @{ corpusId = $corpusId; title = ("LoadTest Doc {0}" -f $i); contentText = $content }
    $ackStart = (Get-Date)
    try {
        $resp = Invoke-Prism -Uri "$Base/api/documents" -Method POST -Headers $h -Body $body
        $ackMs = ((Get-Date) - $ackStart).TotalMilliseconds
        [void]$uploadResults.Add([pscustomobject]@{
            DocIndex = $i; DocumentId = $resp.id; UploadAckMs = [math]::Round($ackMs); Username = $user.Username
        })
    } catch {
        $err = $_
        $st = if ($err.Exception.Response) { [int]$err.Exception.Response.StatusCode } else { 0 }
        Say ('  upload failed doc {0}: HTTP {1} {2}' -f $i, $st, $err.Exception.Message) 'Red'
    }
    if ($i % 25 -eq 0 -or $i -eq $DocCount) {
        $el = ((Get-Date) - $t0).TotalSeconds
        Say ('  uploaded {0}/{1}  ({2} docs/s)' -f $i, $DocCount, [math]::Round($i / [Math]::Max($el, 0.001), 2))
    }
}
$uploadElapsed = ((Get-Date) - $t0).TotalSeconds
Say ('upload done: {0} docs in {1}s = {2} docs/s' -f $uploadResults.Count, [math]::Round($uploadElapsed, 1), [math]::Round($uploadResults.Count / [Math]::Max($uploadElapsed, 0.001), 3)) 'Green'

# ---------- settle ----------
Say ("Polling to AWAITING_APPROVAL (every {0}s, timeout {1}s)" -f $PollIntervalSec, $SettleTimeoutSec)
$t0 = (Get-Date)
$pending = @{}
foreach ($r in $uploadResults) { $pending[$r.DocumentId] = $r }
$settled = New-Object System.Collections.ArrayList
$failed = 0
$quarantined = 0
$totalChunks = 0

while ($pending.Count -gt 0) {
    if ((((Get-Date) - $t0).TotalSeconds) -gt $SettleTimeoutSec) { break }
    foreach ($id in @($pending.Keys)) {
        try {
            $prog = Invoke-Prism -Uri "$Base/api/documents/$id/progress" -Headers $adminHeaders
        } catch { continue }
        $st = $prog.status
        if ($st -in @('AWAITING_APPROVAL', 'READY', 'FAILED')) {
            $rec = $pending[$id]
            [void]$settled.Add([pscustomobject]@{
                DocumentId = $id; Status = $st
                TotalChunks = [int]$prog.totalChunks
                Quarantined = [int]$prog.quarantinedCount
                SettleMs = [math]::Round(((Get-Date) - $t0).TotalMilliseconds)
            })
            if ($st -eq 'FAILED') { $failed++ } else { $totalChunks += [int]$prog.totalChunks; $quarantined += [int]$prog.quarantinedCount }
            [void]$pending.Remove($id)
        }
    }
    if ($pending.Count -gt 0) { Start-Sleep -Seconds $PollIntervalSec }
}
$settleElapsed = ((Get-Date) - $t0).TotalSeconds
Say ('settled={0} failed={1} still-pending={2} in {3}s' -f ($settled.Count - $failed), $failed, $pending.Count, [math]::Round($settleElapsed, 1)) 'Green'

# ---------- read phase (optional concurrent) ----------
$readResults = $null
if ($ConcurrentReads -gt 0) {
    Say ("Concurrent read phase: {0} parallel requests per endpoint" -f $ConcurrentReads)
    $vToken = $loginResp.accessToken
    $eps = @(
        @{ n = 'documents(50)'; u = "$Base/api/documents?corpusId=$corpusId&size=50" }
        @{ n = 'corpora'; u = "$Base/api/corpora" }
        @{ n = 'graph ALL_APPROVED'; u = "$Base/api/graph/$corpusId?scope=ALL_APPROVED" }
        @{ n = 'graph VERIFIED_ONLY'; u = "$Base/api/graph/$corpusId?scope=VERIFIED_ONLY" }
        @{ n = 'claims(50)'; u = "$Base/api/claims?corpusId=$corpusId&size=50" }
        @{ n = 'contradictions(50)'; u = "$Base/api/contradictions?corpusId=$corpusId&size=50" }
        @{ n = 'traces(20)'; u = "$Base/api/traces?corpusId=$corpusId&size=20" }
    )
    $readResults = @{}
    foreach ($e in $eps) {
        $sw = [Diagnostics.Stopwatch]::StartNew()
        $ok = 0
        $jobs = 1..$ConcurrentReads | ForEach-Object {
            Start-Job -ScriptBlock {
                param($uri, $tok)
                try { Invoke-RestMethod -Uri $uri -Headers @{ Authorization = "Bearer $tok" } -TimeoutSec 120 | Out-Null; 1 } catch { 0 }
            } -ArgumentList $e.u, $vToken
        }
        $jobs | Wait-Job | Out-Null
        foreach ($j in $jobs) { $ok += Receive-Job $j; Remove-Job $j | Out-Null }
        $sw.Stop()
        $ms = [math]::Round($sw.ElapsedMilliseconds / [Math]::Max($ConcurrentReads, 1), 1)
        $readResults[$e.n] = @{ ok = $ok; of = $ConcurrentReads; avgMs = $ms }
        Say ('  {0}: {1}/{2} ok, {3} ms avg' -f $e.n, $ok, $ConcurrentReads, $ms)
    }
}

# ---------- report ----------
$totalElapsed = ((Get-Date) - $script:startTime).TotalSeconds
function Pct($arr, $p) {
    if (-not $arr -or $arr.Count -eq 0) { return 0 }
    $s = $arr | Sort-Object
    return $s[[math]::Min($s.Count - 1, [math]::Floor($s.Count * $p))]
}
$acks = @($uploadResults | ForEach-Object { $_.UploadAckMs })
$settles = @($settled | Where-Object { $_.Status -ne 'FAILED' } | ForEach-Object { $_.SettleMs })

Say ''
Say ('=' * 72)
Say 'LOAD TEST REPORT'
Say ('=' * 72)
Say ('provider        : fake (LLM_PROVIDER=fake; no model latency included)')
Say ('users created   : {0}' -f $createdUsers.Count)
Say ('users elapsed   : {0}s ({1}/s)' -f [math]::Round($userElapsed, 1), [math]::Round($createdUsers.Count / [Math]::Max($userElapsed, 0.001), 2))
Say ('corpus          : {0} (id {1})' -f $CorpusName, $corpusId)
Say ('docs uploaded   : {0}' -f $uploadResults.Count)
Say ('upload wall     : {0}s' -f [math]::Round($uploadElapsed, 2))
Say ('upload tput     : {0} docs/s' -f [math]::Round($uploadResults.Count / [Math]::Max($uploadElapsed, 0.001), 3))
Say ('upload ack      : p50={0} p95={1} max={2} ms' -f (Pct $acks 0.5), (Pct $acks 0.95), ($(if ($acks.Count) { ($acks | Measure-Object -Maximum).Maximum } else { 0 })))
Say ('settled         : {0}' -f ($settled.Count - $failed))
Say ('settle failed   : {0}' -f $failed)
Say ('settle pending  : {0}' -f $pending.Count)
Say ('settle wall     : {0}s' -f [math]::Round($settleElapsed, 1))
if ($settles.Count) {
    Say ('settle latency  : p50={0} p95={1} max={2} ms' -f (Pct $settles 0.5), (Pct $settles 0.95), (($settles | Measure-Object -Maximum).Maximum))
}
Say ('chunks processed: {0}' -f $totalChunks)
Say ('quarantined     : {0}' -f $quarantined)
Say ('rate-limit hits : {0}' -f $script:rateLimitRetries)
Say ('total wall      : {0}s' -f [math]::Round($totalElapsed, 1))
Say ''
Say 'Not a scalability claim. Single machine, one backend, CPU-bound fake fixture.' 'Yellow'

# run record
$runId = [guid]::NewGuid().ToString()
$dir = 'D:\Projects\Prism\eval\runs'
if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
$record = [pscustomobject]@{
    runId = $runId
    executedAt = (Get-Date).ToString('o')
    provider = 'fake'
    userCount = $UserCount
    usersCreated = $createdUsers.Count
    userCreationSec = [math]::Round($userElapsed, 3)
    corpusId = $corpusId
    corpusName = $CorpusName
    docCount = $DocCount
    docsUploaded = $uploadResults.Count
    uploadWallSec = [math]::Round($uploadElapsed, 3)
    uploadThroughputPerSec = [math]::Round($uploadResults.Count / [Math]::Max($uploadElapsed, 0.001), 3)
    uploadAckMs = @{ p50 = (Pct $acks 0.5); p95 = (Pct $acks 0.95); max = $(if ($acks.Count) { ($acks | Measure-Object -Maximum).Maximum } else { 0 }); samples = $acks.Count }
    settled = ($settled.Count - $failed)
    settleFailed = $failed
    settlePending = $pending.Count
    settleWallSec = [math]::Round($settleElapsed, 3)
    settleLatencyMs = @{ p50 = (Pct $settles 0.5); p95 = (Pct $settles 0.95); max = $(if ($settles.Count) { ($settles | Measure-Object -Maximum).Maximum } else { 0 }); samples = $settles.Count }
    chunksProcessed = $totalChunks
    quarantined = $quarantined
    rateLimitRetries = $script:rateLimitRetries
    readPhase = $readResults
    totalWallSec = [math]::Round($totalElapsed, 3)
    disclaimer = 'LLM_PROVIDER=fake: chunking + deterministic parsing only. No model latency. Not a scalability claim.'
}
$path = Join-Path $dir ("loadtest-{0}.json" -f $runId)
$record | ConvertTo-Json -Depth 8 | Set-Content -Path $path -Encoding utf8
Say ''
Say ("run record: {0}" -f $path) 'Cyan'
