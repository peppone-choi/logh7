param([string]$OutputPath)
$ErrorActionPreference = 'Stop'
$unit = $PSScriptRoot
$ledger = Get-Content -LiteralPath (Join-Path $unit 'evidence/artifact-ledger.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$assertions = 0
function Assert-True([string]$name, [bool]$value) { $script:assertions++; if (-not $value) { throw "assertion failed: $name" } }
function Assert-Equal([string]$name, $actual, $expected) { $script:assertions++; if ($actual -ne $expected) { throw "$name expected=$expected actual=$actual" } }

foreach ($artifact in $ledger.artifacts) {
    $path = Join-Path $unit ([string]$artifact.path)
    Assert-True "artifact exists $($artifact.path)" (Test-Path -LiteralPath $path)
    Assert-Equal "artifact hash $($artifact.path)" (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash ([string]$artifact.sha256)
}

$tests = @(
    'tests/test-evaluate-fresh-run-identity.ps1',
    'tests/test-netstat-port47900-parser.ps1',
    'tests/test-evaluate-heartbeat-binding.ps1',
    'tests/test-ps51-collector-compatibility.ps1',
    'tests/test-evaluate-root-role-adjudication.ps1',
    'tests/test-root-role-collector-contract.ps1'
)
$testReceipts = @()
foreach ($relative in $tests) {
    $raw = & pwsh -NoProfile -File (Join-Path $unit $relative)
    if ($LASTEXITCODE -ne 0) { throw "test failed: $relative" }
    $receipt = ($raw -join [Environment]::NewLine) | ConvertFrom-Json
    $testStatus = if ($null -ne $receipt.PSObject.Properties['status']) { $receipt.status } else { $receipt.result }
    Assert-Equal "test status $relative" $testStatus 'PASS'
    $testReceipts += [ordered]@{ path=$relative; receipt=$receipt }
}

$manager65 = Get-Content -LiteralPath (Join-Path $unit 'evidence/fresh-manager65.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$manager67 = Get-Content -LiteralPath (Join-Path $unit 'evidence/fresh-manager67.json') -Raw -Encoding UTF8 | ConvertFrom-Json
foreach ($pair in @(@('manager65',$manager65),@('manager67',$manager67))) {
    Assert-Equal "$($pair[0]) live provenance" $pair[1].provenance 'LIVE_READONLY'
    Assert-Equal "$($pair[0]) writes" $pair[1].operations.writes 0
    Assert-Equal "$($pair[0]) inputs" $pair[1].operations.gameInputs 0
    Assert-Equal "$($pair[0]) breakpoints" $pair[1].operations.breakpointsInstalled 0
    Assert-Equal "$($pair[0]) state remains ineligible" $pair[1].stateEligible $false
}

$identityD = Get-Content -LiteralPath (Join-Path $unit 'evidence/fresh-run-identity-d.json') -Raw -Encoding UTF8 | ConvertFrom-Json
Assert-Equal 'session-zero diagnostic' $identityD.desktop.sessionId 0
Assert-Equal 'session-zero windows are not promoted' @($identityD.windows).Count 0
Assert-True 'connection remains established' (@($identityD.network.connections | Where-Object { $_.state -eq 'ESTABLISHED' -and $_.remoteEndpoint -match ':47900$' }).Count -ge 1)

$adjudication = Get-Content -LiteralPath (Join-Path $unit 'evidence/root-role-static-adjudication.json') -Raw -Encoding UTF8 | ConvertFrom-Json
foreach ($source in $adjudication.sources) {
    $sourcePath = Join-Path (Split-Path -Parent (Split-Path -Parent $unit)) ([string]$source.path)
    Assert-True "nested source exists $($source.path)" (Test-Path -LiteralPath $sourcePath)
    Assert-Equal "nested source hash $($source.path)" (Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash ([string]$source.sha256)
}
Assert-Equal 'role split verdict' $adjudication.adjudication.status 'SOURCE_CONFLICT_RESOLVED_STATIC_WITH_PARTIAL_RUNTIME_CORROBORATION'
Assert-Equal 'current boundary' $adjudication.currentLiveBoundary.reason 'FRESH_OWNED_HWND_NOT_OBSERVABLE_FROM_AVAILABLE_GUEST_OPERATION_SESSION'
Assert-Equal 'activation unconsumed' $adjudication.currentLiveBoundary.physicalActivationConsumed 0
Assert-Equal 'attach absent' $adjudication.currentLiveBoundary.debuggerAttachCount 0
Assert-Equal 'bp absent' $adjudication.currentLiveBoundary.breakpointsInstalled 0
Assert-Equal 'writes absent' $adjudication.currentLiveBoundary.processMemoryWrites 0
Assert-Equal 'inputs absent' $adjudication.currentLiveBoundary.gameInputs 0
Assert-True 'failed live root capture is not published' (-not (Test-Path -LiteralPath (Join-Path $unit 'evidence/fresh-root-role-adjudication.json')))

$operationLedger = Get-Content -LiteralPath (Join-Path $unit 'evidence/operation-attempt-ledger.json') -Raw -Encoding UTF8 | ConvertFrom-Json
Assert-Equal 'attempt count' @($operationLedger.attempts).Count 13
Assert-Equal 'read-only memory reads' $operationLedger.aggregate.readOnlyProcessMemoryReads 292
Assert-Equal 'manager read sum' ([int]$manager65.operations.memoryReadCount + [int]$manager67.operations.memoryReadCount) 292
foreach ($name in @('processMemoryWrites','debuggerAttachCount','debuggerCommands','breakpointsInstalled','ownedHwndCaptures','gameInputs','automaticInputs','physicalActivations','permitIssuance','vmLifecycleChanges','serverChanges','protocolChanges','databaseChanges')) { Assert-Equal "operation zero $name" $operationLedger.aggregate.$name 0 }
Assert-Equal 'transport instrumentation boundary' $operationLedger.aggregate.guestTransportCalls 'NOT_INSTRUMENTED_BEFORE_THIS_LEDGER'

$receipt = [ordered]@{
    schemaVersion = 1
    status = 'PASS'
    verdict = 'PRELAUNCH_V10_BLOCKED_BEFORE_ATTACH_OR_INPUT'
    artifactLedgerSha256 = (Get-FileHash -LiteralPath (Join-Path $unit 'evidence/artifact-ledger.json') -Algorithm SHA256).Hash
    artifactCount = @($ledger.artifacts).Count
    testReceipts = $testReceipts
    assertions = $assertions
    operationCounters = $operationLedger.aggregate
}
$json = $receipt | ConvertTo-Json -Depth 12
if (-not [string]::IsNullOrWhiteSpace($OutputPath)) { [IO.File]::WriteAllText($OutputPath,($json + "`n"),[Text.UTF8Encoding]::new($false)) }
$json
