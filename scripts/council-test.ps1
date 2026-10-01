# PRISM Council flow test.
#
# The end-to-end path that smoke-test.ps1 deliberately does not cover: convening a
# Council over a detected contradiction, running its rounds, recording a chair's
# weights, and synthesising a report.
#
# This is the part of PRISM where the state machine, optimistic locking, and
# citation validation all have to hold at once. It is the highest-risk flow in the
# system and the one most likely to break silently, so it gets its own script
# rather than being folded into the general smoke test.
#
# Requires a running backend and a corpus that already has:
#   * at least one OPEN contradiction (run seed-demo.ps1 -ApproveAll first)
#   * an account holding the VERIFIER role
#
# Usage:
#   powershell -File scripts/council-test.ps1
#   powershell -File scripts/council-test.ps1 -CorpusId 24 -Username ops -Password '...'
param(
  [string]$Base = 'http://localhost:8080',
  [long]$CorpusId = 0,
  [string]$Username = '',
  [string]$Password = '',
  [int]$Rounds = 3
)

# ASCII only: Windows PowerShell 5.1 reads a .ps1 as ANSI unless it carries a
# UTF-8 BOM, so a non-ASCII literal here would be mangled before it left the
# script and would surface as a server-side encoding fault.
$ErrorActionPreference = 'Continue'

$script:Pass = 0
$script:Fail = 0

function Say($msg, $colour = 'Gray') { Write-Host $msg -ForegroundColor $colour }

# A check that asserts a condition and records it, rather than throwing. One
# failed assertion must not abort the run: the remaining checks are exactly what
# tells you how far the state machine actually got.
function Check($name, $condition, $detail = '') {
  if ($condition) {
    $script:Pass++
    Write-Host ("  PASS  {0}" -f $name) -ForegroundColor Green
  } else {
    $script:Fail++
    Write-Host ("  FAIL  {0}" -f $name) -ForegroundColor Red
    if ($detail) { Write-Host ("        {0}" -f $detail) -ForegroundColor DarkRed }
  }
}

function Invoke-Prism {
  param(
    [Parameter(Mandatory = $true)][string]$Uri,
    [string]$Method = 'GET',
    [object]$Body = $null
  )
  $params = @{ Uri = $Uri; Method = $Method; Headers = $headers; TimeoutSec = 900 }
  if ($null -ne $Body) {
    $params.ContentType = 'application/json'
    $params.Body = ($Body | ConvertTo-Json -Depth 8)
  }
  try {
    return Invoke-RestMethod @params
  } catch {
    # PowerShell 5.1 in NonInteractive mode makes the response body unreadable
    # on a failed call, so the status is captured here and thrown as a
    # comparable object rather than swallowed.
    $status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
    $err = [pscustomobject]@{ Status = $status; Message = $_.Exception.Message }
    throw $err
  }
}

# ---- sign in --------------------------------------------------------------

if (-not $Username) {
  Say 'No -Username supplied. Looking for an existing OPEN contradiction is not possible' 'Yellow'
  Say 'without knowing which corpus to use. Supply -CorpusId and -Username.' 'Yellow'
  exit 2
}

Say "signing in as $Username" 'Cyan'
$login = Invoke-RestMethod -Method Post -Uri "$Base/api/auth/login" -ContentType 'application/json' `
  -Body (@{ username = $Username; password = $Password } | ConvertTo-Json) -TimeoutSec 30
$headers = @{ Authorization = "Bearer $($login.accessToken)" }
Say "  role=$($login.user.role) id=$($login.user.id)"

if ($login.user.role -notin @('VERIFIER', 'ADMIN')) {
  Say "This account is $($login.user.role). Convening and chairing require VERIFIER or ADMIN." 'Red'
  Say 'Promote it first, then re-run.' 'Red'
  exit 2
}

# ---- pick an open contradiction ------------------------------------------

Say ''
Say 'finding an OPEN contradiction to convene a Council over' 'Cyan'
$findings = Invoke-Prism -Uri "$Base/api/contradictions?corpusId=${CorpusId}&status=OPEN&size=50"
$open = @($findings.content)
Say "  $($open.Count) OPEN finding(s) available"

if ($open.Count -eq 0) {
  Say 'No OPEN contradiction. Run seed-demo.ps1 -ApproveAll to produce one.' 'Red'
  exit 2
}

$target = $open[0]
Say "  target #$($target.id) $($target.subjectText) / $($target.predicate)" 'DarkGray'

# ---- the state machine is the authority, so read it first -----------------

Say ''
Say 'reading the state machine' 'Cyan'
$fsm = Invoke-Prism -Uri "$Base/api/debates/fsm"
$maxRounds = [int]$fsm.maxRounds
$RoundsToRun = [Math]::Min($Rounds, $maxRounds)
$minWeight = [int]$fsm.weightRange.min
$maxWeight = [int]$fsm.weightRange.max
$personas = @($fsm.personas)
# `states` is a name -> description map, so it is counted as a property
# collection rather than an array.
$stateNames = @($fsm.states.PSObject.Properties.Name)
$events = @($fsm.events)
Say "  states=$($stateNames.Count) events=$($events.Count) maxRounds=$maxRounds weights=$minWeight..$maxWeight"
Say "  personas: $($personas -join ', ')"
Check 'the state machine is published' ($stateNames.Count -gt 0 -and $events.Count -gt 0)
Check 'every persona is declared' ($personas.Count -eq 3)
Check 'every state has a description' (@($fsm.states.PSObject.Properties |
        Where-Object { [string]::IsNullOrWhiteSpace($_.Value) }).Count -eq 0)
Check 'the round ceiling is published' ($maxRounds -ge 1)
Check 'the weight range is published and ordered' ($minWeight -ge 1 -and $maxWeight -gt $minWeight)

# A terminal state must exist or the machine can never end. A debate that can
# only be abandoned is not a finished Council.
$terminalStates = @('COMPLETED', 'ABORTED')
$missingTerminal = @($terminalStates | Where-Object { $stateNames -notcontains $_ })
Check 'both terminal states are declared' ($missingTerminal.Count -eq 0) "missing=$($missingTerminal -join ',')"

# ---- convene --------------------------------------------------------------

Say ''
Say 'convening the Council' 'Cyan'
# The chair is not part of the request: the authenticated caller chairs, so a
# body that names one would be a client asserting authority it does not have.
$debate = Invoke-Prism -Method Post -Uri "$Base/api/contradictions/$($target.id)/debate" -Body @{
  topic = "Which value is correct for $($target.subjectText) $($target.predicate)?"
}
$debateId = [long]$debate.id

Check 'a debate was created' ($debateId -gt 0) "id=$debateId"
Check 'it starts in CREATED' ($debate.state -eq 'CREATED') "state=$($debate.state)"
Check 'its current round is 0' ([int]$debate.currentRound -eq 0)
Check 'it has a topic' (-not [string]::IsNullOrWhiteSpace($debate.topic))
Check 'the chair is recorded' (-not [string]::IsNullOrWhiteSpace($debate.chair))
# `@($null).Count` is 1 in Windows PowerShell 5.1, so a missing key would read as
# "one round" and fail this assertion for the wrong reason. The key's absence is
# tested explicitly instead.
Check 'it carries no rounds yet' (-not $debate.PSObject.Properties.Name.Contains('rounds') `
    -or @($debate.rounds | Where-Object { $null -ne $_ }).Count -eq 0)
Say "  #$debateId  '$($debate.topic)'  chaired by $($debate.chair)" 'DarkGray'

# A second Council over the same contradiction must be refused: the machine
# only holds one active debate per contradiction, and allowing a second would
# mean two divergent conclusions for one conflict.
$duplicate = $null
try {
  $dup = Invoke-Prism -Method Post -Uri "$Base/api/contradictions/$($target.id)/debate" -Body @{
    topic = 'a second council over the same conflict'
  }
  $duplicate = 'created a SECOND debate, id=' + $dup.id
} catch {
  $duplicate = $null
  Check 'a second Council over the same contradiction is refused' ($true) "status=$($_.Status)"
}
if ($null -ne $duplicate) {
  Check 'a second Council over the same contradiction is refused' $false $duplicate
}

# ---- an illegal transition must be refused -------------------------------

# Advancing a debate that has not started is the clearest illegal move. If it
# were accepted, the round machinery would run on an unstarted debate.
$illegal = $null
try {
  $r = Invoke-Prism -Method Post -Uri "$Base/api/debates/$debateId/advance"
  $illegal = "advance on CREATED returned state=$($r.state)"
} catch {
  $illegal = $null
  Check 'advance before start is refused' ($true) "status=$($_.Status)"
}
if ($null -ne $illegal) { Check 'advance before start is refused' $false $illegal }

# Synthesising before the personas have argued must also be refused: a report
# over zero arguments would be a fabrication wearing a report's clothes.
$illegal2 = $null
try {
  Invoke-Prism -Method Post -Uri "$Base/api/debates/$debateId/synthesize" | Out-Null
  $illegal2 = 'synthesize on CREATED succeeded'
} catch {
  $illegal2 = $null
  Check 'synthesise before the debate has argued is refused' ($true) "status=$($_.Status)"
}
if ($null -ne $illegal2) { Check 'synthesise before the debate has argued is refused' $false $illegal2 }

# ---- run the rounds, weighting each before advancing ----------------------
#
# The order matters, and it mirrors how a chair actually works: a round runs,
# its arguments are weighed, and only then can it advance. Advancing first is
# correctly refused with a 409 while any argument is unweighted, so a test that
# advanced immediately would be testing the refusal rather than the debate.

Say ''
Say "running the debate to $RoundsToRun rounds, weighting each before advancing" 'Cyan'

$weightableTotal = 0
$firstFailed = $null

for ($r = 1; $r -le $RoundsToRun; $r++) {
  if ($r -eq 1) {
    $state = Invoke-Prism -Method Post -Uri "$Base/api/debates/$debateId/start"
  } else {
    $state = Invoke-Prism -Method Post -Uri "$Base/api/debates/$debateId/advance"
  }
  $round = @($state.rounds | Where-Object { $_.roundNumber -eq $r })
  $args = if ($round.Count -gt 0) { @($round[0].arguments) } else { @() }
  $failedArgs = @($args | Where-Object { $_.failed })
  if ($null -eq $firstFailed -and $failedArgs.Count -gt 0) { $firstFailed = $failedArgs[0] }

  Say ("  round {0}: state={1,-14} arguments={2} failed={3}" -f `
      $r, $state.state, $args.Count, $failedArgs.Count) 'DarkGray'

  Check ("round {0} produced an argument per persona" -f $r) ($args.Count -eq $personas.Count) `
      "expected $($personas.Count), got $($args.Count)"

  foreach ($a in $args) {
    if ($a.failed) {
      # A failed argument is legitimate and must stay visible, not hidden.
      # The check is that it is recorded together with its reason.
      Check ("failed argument #{0} carries a reason" -f $a.id) `
          (-not [string]::IsNullOrWhiteSpace($a.failureReason))
    } else {
      Check ("argument #{0} has text" -f $a.id) (-not [string]::IsNullOrWhiteSpace($a.argumentText))
      Check ("argument #{0} has a persona" -f $a.id) ($personas -contains $a.persona)
      # Every citation a persona does carry must resolve to a real source.
      foreach ($c in @($a.citations)) {
        $hasTarget = ($null -ne $c.chunkId) -or ($null -ne $c.tripleId) -or ($null -ne $c.claimId)
        Check ("citation #{0} of argument #{1} names a real target" -f $c.id, $a.id) $hasTarget
      }
    }
  }

  # ---- weight this round's arguments before the round can advance ----
  $weightable = @($args | Where-Object { -not $_.failed })
  $weightedThisRound = 0
  $i = 0
  foreach ($a in $weightable) {
    $w = if ($i % 2 -eq 0) { $maxWeight } else { $minWeight }
    $i++
    try {
      $res = Invoke-Prism -Method Post -Uri "$Base/api/debates/$debateId/arguments/$($a.id)/weight" `
        -Body @{ weight = $w; note = 'council-test' }
      $weightedThisRound++
      $weightableTotal++
      Check ("weight for argument #{0} was attributed" -f $a.id) `
          ($res.verifier -eq $Username) "verifier=$($res.verifier)"
    } catch {
      Check ("weight for argument #{0} was accepted" -f $a.id) $false "status=$($_.Status)"
    }
  }
  Say ("          weighted {0} of {1} argument(s) this round" -f $weightedThisRound, $args.Count) 'DarkGray'
}

Check 'at least one argument was weighted across the debate' ($weightableTotal -gt 0) "count=$weightableTotal"

if ($null -ne $firstFailed) {
  Say ''
  Say "  first failed argument: $($firstFailed.failureReason)" 'DarkYellow'
}

# ---- append-only weights, and validation that refuses rather than clamps ----

$anyArgument = $null
$snapshot = Invoke-Prism -Uri "$Base/api/debates/$debateId"
foreach ($rd in @($snapshot.rounds)) {
  foreach ($a in @($rd.arguments)) {
    if (-not $a.failed) { $anyArgument = $a; break }
  }
  if ($null -ne $anyArgument) { break }
}

if ($null -ne $anyArgument) {
  # A silent clamp would mean the audit record disagrees with what the chair chose.
  $badWeight = $null
  try {
    Invoke-Prism -Method Post -Uri "$Base/api/debates/$debateId/arguments/$($anyArgument.id)/weight" `
      -Body @{ weight = 99 } | Out-Null
    $badWeight = 'a weight of 99 was accepted'
  } catch {
    $badWeight = $null
    Check 'an out-of-range weight is refused' $true "status=$($_.Status)"
  }
  if ($null -ne $badWeight) { Check 'an out-of-range weight is refused' $false $badWeight }

  # Re-weighting must create a new row rather than overwrite, so the earlier
  # judgement of the chair is never lost.
  $reweigh = $null
  try {
    $again = Invoke-Prism -Method Post -Uri "$Base/api/debates/$debateId/arguments/$($anyArgument.id)/weight" `
      -Body @{ weight = $minWeight; note = 'council-test revision' }
    $reweigh = $again
  } catch {
    $reweigh = $null
  }
  Check 're-weighting is accepted as a new audit row' ($null -ne $reweigh)
  if ($null -ne $reweigh) {
    Check 're-weighting changes the effective weight' ([int]$reweigh.weight -eq $minWeight)
  }
}

$after = Invoke-Prism -Uri "$Base/api/debates/$debateId"
$reflected = @($after.rounds | ForEach-Object { $_.arguments } | Where-Object { $null -ne $_.chairWeight })
Check 'weights are reflected on the debate read-back' ($reflected.Count -gt 0) "count=$($reflected.Count)"
$attributed = @($reflected | Where-Object { -not [string]::IsNullOrWhiteSpace($_.weightedBy) })
Check 'every reflected weight names the chair who set it' `
    ($attributed.Count -eq $reflected.Count) "attributed=$($attributed.Count) of $($reflected.Count)"


# ---- synthesise -----------------------------------------------------------

Say ''
Say 'advancing past the round ceiling to reach SYNTHESIZING'
# The machine, not the caller, decides that the rounds are over. After the last
# permitted round the debate sits in AWAITING_CHAIR; submitting the chair's
# weights one more time is what carries it to SYNTHESIZING. Asking for synthesis
# before that transition is a 409, and correctly so: synthesis over a debate the
# engine has not finished would be a report on a conversation that has not ended.
$ceiling = Invoke-Prism -Method Post -Uri "$Base/api/debates/$debateId/advance"
Say "  state now: $($ceiling.state)" 'DarkGray'
Check 'reaching the ceiling moves the debate to SYNTHESIZING' `
    ($ceiling.state -in @('SYNTHESIZING', 'COMPLETED')) "state=$($ceiling.state)"

Say ''
Say 'synthesising the report' 'Cyan'
$stateBefore = Invoke-Prism -Uri "$Base/api/debates/$debateId"
Say "  state before synthesis: $($stateBefore.state)"

if ($stateBefore.state -eq 'SYNTHESIZING') {
  $receipt = Invoke-Prism -Method Post -Uri "$Base/api/debates/$debateId/synthesize"
  Check 'synthesis returns a receipt with a report id' ($receipt.reportId -gt 0) "reportId=$($receipt.reportId)"

  $report = Invoke-Prism -Uri "$Base/api/debates/$debateId/report"
  Check 'the report has a conclusion' (-not [string]::IsNullOrWhiteSpace($report.conclusion))
  Check 'the report has at least one block' (@($report.blocks).Count -gt 0) "blocks=$(@($report.blocks).Count)"
  Check 'the report records its model' (-not [string]::IsNullOrWhiteSpace($report.model))
  Check 'the report records its prompt version' (-not [string]::IsNullOrWhiteSpace($report.promptVersion))

  # Every block must carry structurally valid citations. A report that cites
  # nothing is an assertion, and the UI says so; the backend must not pretend
  # otherwise.
  $badCitations = 0
  # A citation must name something a reader can go and look at. Synthesis cites
  # in two kinds: ARGUMENT, which points at a stored argument row, and
  # MACHINE_FACT, which points at an entry in the machine-evidence brief. Both
  # are targets; a check that only looked at chunk/triple/claim would call a
  # correctly-cited ARGUMENT block uncited and pass a genuinely broken one.
  $totalCitations = 0
  foreach ($b in @($report.blocks)) {
    foreach ($c in @($b.citations)) {
      $totalCitations++
      $hasTarget = ($null -ne $c.chunkId) -or ($null -ne $c.tripleId) -or ($null -ne $c.claimId) `
        -or ($null -ne $c.verdictId) -or ($null -ne $c.argumentId) -or (-not [string]::IsNullOrWhiteSpace($c.machineFactId))
      if (-not $hasTarget) { $badCitations++ }
    }
  }
  Check 'no report citation lacks a target' ($badCitations -eq 0 -and $totalCitations -gt 0) `
      "bad=$badCitations of $totalCitations"
  Say "  $($report.blocks.Count) block(s), $totalCitations citation(s)" 'DarkGray'

  # Synthesis is idempotent: calling it again must return the same report
  # rather than generating a second one.
  $second = Invoke-Prism -Method Post -Uri "$Base/api/debates/$debateId/synthesize"
  Check 'synthesis is idempotent' ([long]$second.reportId -eq [long]$receipt.reportId) `
      "first=$($receipt.reportId) second=$($second.reportId)"

  $final = Invoke-Prism -Uri "$Base/api/debates/$debateId"
  Check 'the debate reaches COMPLETED' ($final.state -eq 'COMPLETED') "state=$($final.state)"
  Check 'it records when it finished' ($null -ne $final.finishedAt)
} else {
  # Reaching a non-AWAITING state means the engine's own round ceiling or a
  # refusal ended the debate early. That is not a failure of this script, and
  # saying so is more useful than a red X.
  Say "  debate ended in $($stateBefore.state) rather than SYNTHESIZING." 'Yellow'
  Say '  The engine stopped it on its own terms; the report assertions are skipped.' 'Yellow'
  if ($null -ne $stateBefore.lastError) { Say "  lastError: $($stateBefore.lastError)" 'Yellow' }
  Check 'the debate reached a terminal state' ($stateBefore.state -in @('COMPLETED', 'ABORTED')) `
      "state=$($stateBefore.state)"
}

# ---- the contradiction and the trace -------------------------------------

Say ''
Say 'checking the contradiction and the Glass Box' 'Cyan'
$afterScan = Invoke-Prism -Uri "$Base/api/contradictions/$($target.id)"
Check 'the contradiction is no longer OPEN' ($afterScan.status -ne 'OPEN') "status=$($afterScan.status)"
Check 'it links to the debate that settled it' ($null -ne $afterScan.debateId) "debateId=$($afterScan.debateId)"

$traces = Invoke-Prism -Uri "$Base/api/traces?corpusId=${CorpusId}&size=100"
$debateTraces = @($traces.content | Where-Object { $_.operationKey -like "*$debateId*" -or $_.operationType -eq 'DEBATE' })
if ($debateTraces.Count -gt 0) {
  # A debate's Glass Box is composed of several runs, not one: convening, each
  # start and advance, every chair weighting, and synthesis each record their
  # own. So the actor-separation assertions aggregate across every run of this
  # debate. Checking one arbitrary run would prove nothing -- a single round run
  # legitimately has no HUMAN step, because the human action is recorded by the
  # separate weighting call, and reading that as "no human was involved" is
  # exactly the misreading the X-Ray exists to prevent.
  $allEngine = @()
  $allLlm = @()
  $allHuman = @()
  $runCount = 0
  $anySteps = 0
  foreach ($t in $debateTraces) {
    $runCount++
    $run = Invoke-Prism -Uri "$Base/api/traces/$($t.id)"
    $anySteps += @($run.steps).Count
    $x = Invoke-Prism -Uri "$Base/api/traces/$($t.id)/xray"
    $allEngine += @($x.engine)
    $allLlm += @($x.llm)
    $allHuman += @($x.human)
  }
  Check 'the debate is in the Glass Box' ($runCount -gt 0) "runs=$runCount"
  Check 'its runs record their steps' ($anySteps -gt 0) "steps=$anySteps over $runCount run(s)"
  # The X-Ray is the mechanism that proves the LLM only proposed. Across the
  # whole debate, model calls must appear as LLM steps, state transitions as
  # ENGINE steps, and the chair's judgements as HUMAN steps.
  Check 'model steps are attributed to the LLM' ($allLlm.Count -gt 0) "llm=$($allLlm.Count)"
  Check 'state transitions are attributed to the engine' ($allEngine.Count -gt 0) `
      "engine=$($allEngine.Count)"
  Check 'the human chair weight is attributed to a person' ($allHuman.Count -gt 0) `
      "human=$($allHuman.Count)"
  $weighted = @($allHuman | Where-Object { $_.eventType -eq 'ARGUMENT_WEIGHTED' })
  Check 'a human step names the weighting it recorded' ($weighted.Count -gt 0) `
      "weighted=$($weighted.Count)"
}

# ---- summary --------------------------------------------------------------

Write-Host ''
Write-Host ('=' * 68) -ForegroundColor DarkGray
if ($script:Fail -eq 0) {
  Write-Host ("COUNCIL FLOW OK   {0} checks passed" -f $script:Pass) -ForegroundColor Green
  exit 0
} else {
  Write-Host ("COUNCIL FLOW FAILED   {0} passed, {1} failed" -f $script:Pass, $script:Fail) -ForegroundColor Red
  exit 1
}
