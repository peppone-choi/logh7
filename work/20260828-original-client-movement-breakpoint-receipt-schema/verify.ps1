$ErrorActionPreference = 'Stop'
$unit = $PSScriptRoot
$movementTests = & (Join-Path $unit 'tests/test-movement-breakpoint-receipt.ps1') | ConvertFrom-Json
if ($movementTests.result -ne 'PASS' -or $movementTests.cases -ne 39 -or $movementTests.assertions -ne 53 -or $movementTests.mutations -ne 37) { throw 'movement receipt tests failed or drifted' }
$v6Tests = & (Join-Path $unit 'tests/test-prelaunch-v6-movement-receipt.ps1') | ConvertFrom-Json
if ($v6Tests.result -ne 'PASS' -or $v6Tests.cases -ne 17 -or $v6Tests.assertions -ne 29 -or $v6Tests.mutations -ne 16) { throw 'prelaunch v6 tests failed or drifted' }
$schemaValidation = & python (Join-Path $unit 'tests/validate-json-schema.py') (Join-Path $unit 'evidence/movement-breakpoint-receipt.schema.json') (Join-Path $unit 'evidence/movement-breakpoint-receipt-template.json') (Join-Path $unit 'tests/fixture-semantic-specimen.json') | ConvertFrom-Json
if ($schemaValidation.result -ne 'PASS' -or $schemaValidation.dialect -ne '2020-12' -or $schemaValidation.documents -ne 2) { throw 'JSON Schema validation failed or drifted' }
$templateResult = & (Join-Path $unit 'src/verify-movement-breakpoint-receipt.ps1') -ReceiptPath (Join-Path $unit 'evidence/movement-breakpoint-receipt-template.json') | ConvertFrom-Json
$specimenResult = & (Join-Path $unit 'src/verify-movement-breakpoint-receipt.ps1') -ReceiptPath (Join-Path $unit 'tests/fixture-semantic-specimen.json') | ConvertFrom-Json
$v6Result = & (Join-Path $unit 'src/verify-prelaunch-v6-movement-receipt.ps1') -ContractPath (Join-Path $unit 'evidence/prelaunch-v6-movement-receipt.json') | ConvertFrom-Json
if ($templateResult.result -ne 'PASS' -or $templateResult.state -ne 'EMPTY_TEMPLATE_NOT_LIVE' -or $templateResult.anchorCount -ne 9 -or $templateResult.liveReceiptEligible) { throw 'template semantic verification failed' }
if ($specimenResult.result -ne 'PASS' -or $specimenResult.runtimeBindingStatus -ne 'SYNTHETIC_SPECIMEN_ONLY' -or $specimenResult.liveReceiptEligible) { throw 'synthetic specimen semantic verification failed' }
if ($v6Result.result -ne 'PASS' -or $v6Result.firstTechnicalBoundary -ne 'MOVEMENT_HARDWARE_BREAKPOINT_REARM_PLAN_MISSING') { throw 'v6 semantic verification failed' }
$ledgerPath = Join-Path $unit 'evidence/artifact-ledger.json'
$ledger = Get-Content -LiteralPath $ledgerPath -Raw -Encoding UTF8 | ConvertFrom-Json
$hashMap = [ordered]@{}
foreach ($artifact in $ledger.artifacts) {
    $path = Join-Path $unit ([string]$artifact.path)
    if (-not (Test-Path -LiteralPath $path)) { throw "missing artifact $($artifact.path)" }
    $actual = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
    if ($actual -ne ([string]$artifact.sha256).ToUpperInvariant()) { throw "artifact hash mismatch $($artifact.path)" }
    $hashMap[$artifact.path] = $actual
}
$scripts = @(
    Get-Content -LiteralPath (Join-Path $unit 'src/verify-movement-breakpoint-receipt.ps1') -Raw -Encoding UTF8
    Get-Content -LiteralPath (Join-Path $unit 'src/verify-prelaunch-v6-movement-receipt.ps1') -Raw -Encoding UTF8
) -join "`n"
$forbiddenCapabilities = @('WriteProcessMemory','SendInput','SetCursorPos','PostMessage','mouse_event','keybd_event','VirtualAllocEx','CreateRemoteThread','Invoke-VMScript','Start-VM','Stop-VM','vmrun')
$hits = @($forbiddenCapabilities | Where-Object { $scripts.Contains($_) })
if ($hits.Count) { throw "forbidden executable capability: $($hits -join ', ')" }
[ordered]@{
    result = 'PASS'
    movementReceiptTests = $movementTests
    prelaunchV6Tests = $v6Tests
    jsonSchemaValidation = $schemaValidation
    templateState = $templateResult.state
    syntheticRuntimeBindingStatus = $specimenResult.runtimeBindingStatus
    contract = $v6Result
    artifactHashesVerified = @($ledger.artifacts).Count
    artifactLedgerSha256 = (Get-FileHash -LiteralPath $ledgerPath -Algorithm SHA256).Hash
    artifactHashMap = $hashMap
    primaryAnchorCount = 7
    completionAnchorCount = 2
    hardwareBreakpointRearmPlan = 'UNPROVEN'
    forbiddenCapabilityHits = 0
    liveOperations = 0
    processMemoryReads = 0
    gameInputs = 0
    permitIssued = $false
    status = 'OFFLINE_MOVEMENT_RECEIPT_SCHEMA_PASS_REARM_PLAN_MISSING_RUNTIME_UNSEEN'
} | ConvertTo-Json -Depth 14
