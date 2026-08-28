[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$ContractPath)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$contract = Get-Content -LiteralPath $ContractPath -Raw -Encoding UTF8 | ConvertFrom-Json
$errors = [Collections.Generic.List[string]]::new()

function Assert-Equal([string]$Name, $Actual, $Expected) {
    if ($Actual -ne $Expected) { $errors.Add("$Name expected=$Expected actual=$Actual") }
}
function Assert-Sequence([string]$Name, $Actual, $Expected) {
    $a = @($Actual) | ConvertTo-Json -Compress
    $e = @($Expected) | ConvertTo-Json -Compress
    if ($a -ne $e) { $errors.Add("$Name sequence mismatch") }
}
function Load-Json([string]$Path) {
    Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json
}

$expectedHashes = [ordered]@{
    gapAudit = '26ADFA4E61B385776A0D703D370968662B44E6229314026551B5406344E6B313'
    v1Contract = '66ABE74EF005D73354D4FFA28B7993732E5C887EF92AB168DB817E5676257463'
    manager65Verification = 'DBB8CBD223D02C8BF569CCE26352875A49BD3C15CCD0F8A82DE0906C5B5A1DBD'
    manager65Collector = '0244BF801F9CD687E6FE7AE39C702408FDD9A114E7B7185795F9D721EA0C6B0B'
    stageVerification = '17AF17ED2E6A8821F83AE76E213AF1A77867CD26C4159B4BAB52B849256ADFBA'
    destinationHitVerification = '9C5C2918EB6010393126650B738A7830FF50EC2E0EE812E0C03EF61C63DE910A'
    textDialogVerification = '6FBD209DFC4E8E636AA4383B919973B60C77AF7D62307F94F9450235C8308650'
    foregroundBundle = 'A144A1CC53AA28CB078CADDF1FE2228CA2FD5620EE211FC75C95C0E91AD525F9'
    priorPermitConsumption = '6DEB344D029C4315865AE44495F0B8A73AF6E5A4BA55D9649154FDC1B2C5B570'
    moveGridLedger = 'ADC03E13E3A4C7F58D8973F67146B0ABDB749B57F3AAF03F94B4A15BE05E0971'
}

$loaded = @{}
foreach ($name in $expectedHashes.Keys) {
    $entry = $contract.boundArtifacts.$name
    if ($null -eq $entry) { $errors.Add("missing artifact $name"); continue }
    Assert-Equal "$name declared hash" ([string]$entry.sha256).ToUpperInvariant() $expectedHashes[$name]
    $path = Join-Path $repo ([string]$entry.path)
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { $errors.Add("missing file $name"); continue }
    $actual = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
    Assert-Equal "$name actual hash" $actual $expectedHashes[$name]
    $loaded[$name] = if ([IO.Path]::GetExtension($path) -eq '.json') { Load-Json $path } else { Get-Content -LiteralPath $path -Raw -Encoding UTF8 }
}

Assert-Equal 'schema' $contract.schemaVersion 2
Assert-Equal 'contract' $contract.contract 'ORIGINAL_CLIENT_FIRST_SERVER_BACKED_PLAY_PRELAUNCH_V2'
Assert-Equal 'state' $contract.state 'OFFLINE_PRELAUNCH_AUDIT_PASS_READY_FALSE'
Assert-Equal 'run id' $contract.oracleRunId $null
Assert-Equal 'launch eligible' $contract.launchEligible $false
Assert-Equal 'permit eligible' $contract.permitEligible $false
Assert-Equal 'permit issued' $contract.permitIssued $false

Assert-Equal 'authority source' $contract.currentAuthority.source 'USER_APPROVED_CURRENT_THREAD'
Assert-Equal 'authority scope' $contract.currentAuthority.scope 'ONE_LIVE_ORACLE_ONE_PHYSICAL_ACTIVATION_READ_ONLY_CAPTURE'
Assert-Equal 'activation budget' $contract.currentAuthority.activationBudget 1
Assert-Equal 'authority gate bypass' $contract.currentAuthority.doesNotBypassPrelaunchGates $true
Assert-Equal 'prior permit id' $contract.priorPermit.id 'permit-live-v3-20260827-d89449bc2c3c4b70a9854833b2214012-once'
Assert-Equal 'prior permit state' $contract.priorPermit.state 'CONSUMED_NO_RETRY'
Assert-Equal 'prior reusable' $contract.priorPermit.reusable $false

$expectedBlockers = @(
    'ACTIVATION_BUDGET_STAGE_CONTRACT_MISMATCH',
    'MANAGER67_CURRENT_CARD_COLLECTOR_MISSING',
    'SELECTED_CAPTAIN_CARD_WIDGET_COLLECTOR_MISSING',
    'MANAGER65_LIVE_COLLECTOR_NOT_HARDENED',
    'WARP_STAGE_OWNER_POINTER_UNBOUND',
    'MOVEMENT_SPECIFIC_BREAKPOINT_RECEIPT_SCHEMA_MISSING',
    'FRESH_RUN_IDENTITY_MISSING',
    'FRESH_MANAGER65_SNAPSHOT_MISSING',
    'FRESH_DESTINATION_PROJECTION_SNAPSHOT_MISSING',
    'FRESH_TEXTDIALOG_SNAPSHOT_MISSING',
    'FOREGROUND_PROBE_NOT_RUN',
    'INDEPENDENT_LIVE_PRELAUNCH_REVIEW_MISSING'
)
Assert-Sequence 'blockers' $contract.blockers $expectedBlockers
Assert-Equal 'first missing' $contract.firstMissingBoundary $expectedBlockers[0]
Assert-Equal 'first technical' $contract.firstTechnicalBoundary 'MANAGER67_CURRENT_CARD_COLLECTOR_MISSING'

$expectedComposition = @(
    'fresh G7MTClient PID/startTime/hash/moduleBase/HWND/owner/client surface',
    'fresh listener and heartbeat',
    'fresh x32dbg PID/startTime/HWND/owner and exact foreground',
    'fresh manager67 current pointer/count/card widget/gate/rect snapshot',
    'fresh selected captain-card exact widget/gate/rect snapshot',
    'hardened manager65 action-0x2B snapshot',
    'WARP stage-owner pointer bound to the scoped TextDialog manager',
    'fresh destination projection snapshot and independently resolved hit region',
    'fresh TextDialog snapshot and independently resolved confirm region',
    'movement-specific breakpoint/receipt schema installed without writes',
    'one physical activation sequence followed by outbound/inbound/pixel receipts'
)
Assert-Sequence 'same-run composition' $contract.requiredSameRunComposition $expectedComposition

$expectedForbidden = @(
    'reuse prior permit/run/PID/HWND/pointer/coordinate',
    'automatic click or retry',
    'input before all same-run gates and independent review pass',
    'process-memory write or binary/resource patch',
    'VM lifecycle or server/protocol/database change',
    'fixture or self-claimed live evidence promotion'
)
Assert-Sequence 'forbidden' $contract.forbidden $expectedForbidden
$expectedCounters = @('guestOperations','debuggerAttach','breakpointsInstalled','processMemoryReads','physicalInputs','captures','memoryWrites')
Assert-Sequence 'counter names' @($contract.operationCounters.PSObject.Properties.Name) $expectedCounters
foreach ($name in $expectedCounters) { Assert-Equal "counter $name" $contract.operationCounters.$name 0 }

Assert-Equal 'manager route status' $contract.staticPreparation.manager67Route.status 'STATIC_OWNER_PASS_COLLECTOR_MISSING'
Assert-Equal 'manager runtime' $contract.staticPreparation.manager67Route.runtimeObserved 'UNSEEN'
Assert-Equal 'captain status' $contract.staticPreparation.selectedCaptainCard.status 'STATIC_ROUTE_PARTIAL_EXACT_WIDGET_COLLECTOR_MISSING'
Assert-Equal 'captain runtime' $contract.staticPreparation.selectedCaptainCard.runtimeObserved 'UNSEEN'
Assert-Equal 'manager collector status' $contract.staticPreparation.manager65Collector.status 'OFFLINE_PASS_NOT_LIVE_HARDENED'
Assert-Equal 'manager collector runtime' $contract.staticPreparation.manager65Collector.runtimeObserved 'UNSEEN'
$deficiencies = @('CANONICAL_HASH_NOT_INTERNALLY_ENFORCED','MODULE_BASE_NOT_BOUND','DOUBLE_CAPTURE_MISSING','WIDGET_ACTIVE_VISIBLE_GATE_MISSING','POST_CAPTURE_HWND_SURFACE_RECHECK_MISSING')
Assert-Sequence 'manager deficiencies' $contract.staticPreparation.manager65Collector.deficiencies $deficiencies
Assert-Equal 'destination status' $contract.staticPreparation.destinationProjection.status 'STATIC_OFFLINE_RESOLVER_PASS'
Assert-Equal 'destination runtime' $contract.staticPreparation.destinationProjection.runtimeObserved 'UNSEEN'
Assert-Equal 'destination fixture reuse' $contract.staticPreparation.destinationProjection.fixtureCoordinateReusable $false
Assert-Equal 'dialog status' $contract.staticPreparation.textDialog.status 'STATIC_OFFLINE_RESOLVER_PASS'
Assert-Equal 'dialog runtime' $contract.staticPreparation.textDialog.runtimeObserved 'UNSEEN'
Assert-Equal 'dialog fixture reuse' $contract.staticPreparation.textDialog.fixtureCoordinateReusable $false
Assert-Equal 'wire status' $contract.staticPreparation.moveGridWire.status 'STATIC_WIRE_BINDING_PASS'
Assert-Equal 'wire runtime' $contract.staticPreparation.moveGridWire.runtimeObserved 'UNSEEN'
Assert-Equal 'instrument status' $contract.staticPreparation.movementInstrumentation.status 'STATIC_ADDRESSES_PARTIAL_RECEIPT_SCHEMA_MISSING'
Assert-Equal 'instrument runtime' $contract.staticPreparation.movementInstrumentation.runtimeObserved 'UNSEEN'
$instrumentMissing = @(
    'MOVEMENT_HANDLER_CONSUMPTION_BP_RECEIPT_BINDING_MISSING',
    'MOVEGRID_PAYLOAD_BUILDER_BP_RECEIPT_BINDING_MISSING',
    'MOVEGRID_EXPECTED_0B07_ASSIGNMENT_BP_RECEIPT_BINDING_MISSING',
    'MOVEGRID_OUTBOUND_0B01_ASSIGNMENT_BP_RECEIPT_BINDING_MISSING',
    'MOVEGRID_TRANSPORT_SEND_BP_RECEIPT_BINDING_MISSING',
    'MOVEGRID_INBOUND_0B07_DISPATCH_BP_RECEIPT_BINDING_MISSING',
    'MOVEGRID_OWNED_HWND_DELTA_RECEIPT_BINDING_MISSING'
)
Assert-Sequence 'instrument missing bindings' $contract.staticPreparation.movementInstrumentation.missingBindings $instrumentMissing

if ($loaded.ContainsKey('v1Contract')) {
    Assert-Equal 'v1 state' $loaded.v1Contract.state 'OFFLINE_CONTRACT_NOT_LIVE'
    Assert-Equal 'v1 permit' $loaded.v1Contract.permitState 'NOT_ISSUED'
}
if ($loaded.ContainsKey('manager65Verification')) {
    Assert-Equal 'manager verifier' $loaded.manager65Verification.result 'PASS'
    Assert-Equal 'manager claim' $loaded.manager65Verification.claim 'OFFLINE_MANAGER65_READONLY_COLLECTOR_PASS'
    Assert-Equal 'manager live ops' $loaded.manager65Verification.safety.liveOperations 0
}
if ($loaded.ContainsKey('stageVerification')) {
    Assert-Equal 'stage verifier' $loaded.stageVerification.result 'PASS'
    Assert-Equal 'stage permit' $loaded.stageVerification.permitIssued $false
}
if ($loaded.ContainsKey('destinationHitVerification')) {
    Assert-Equal 'hit verifier' $loaded.destinationHitVerification.result 'PASS'
    Assert-Equal 'hit runtime' $loaded.destinationHitVerification.runtimeObserved 'UNSEEN'
    Assert-Equal 'hit permit' $loaded.destinationHitVerification.permitIssued $false
}
if ($loaded.ContainsKey('textDialogVerification')) {
    Assert-Equal 'dialog verifier' $loaded.textDialogVerification.result 'PASS'
    Assert-Equal 'dialog live ops' $loaded.textDialogVerification.liveOperations 0
    Assert-Equal 'dialog permit' $loaded.textDialogVerification.permitIssued $false
}
if ($loaded.ContainsKey('priorPermitConsumption')) {
    Assert-Equal 'consumed artifact id' $loaded.priorPermitConsumption.permitId $contract.priorPermit.id
    Assert-Equal 'consumed artifact state' $loaded.priorPermitConsumption.permitState 'CONSUMED_NO_RETRY'
    Assert-Equal 'consumed retry' $loaded.priorPermitConsumption.retryAllowed $false
    Assert-Equal 'consumed activations' $loaded.priorPermitConsumption.postFailureState.gameActivations 0
}

if ($loaded.ContainsKey('manager65Collector')) {
    $managerSource = [string]$loaded.manager65Collector
    $missingMarkers = [ordered]@{
        CANONICAL_HASH_NOT_INTERNALLY_ENFORCED = 'canonicalExecutableSha256'
        MODULE_BASE_NOT_BOUND = 'MainModule.BaseAddress'
        DOUBLE_CAPTURE_MISSING = 'snapshotStable'
        WIDGET_ACTIVE_VISIBLE_GATE_MISSING = 'activeVisible'
        POST_CAPTURE_HWND_SURFACE_RECHECK_MISSING = 'secondClientWidth'
    }
    foreach ($name in $missingMarkers.Keys) {
        if ($managerSource -match [regex]::Escape($missingMarkers[$name])) { $errors.Add("manager65 deficiency no longer true: $name") }
    }
}

if ($loaded.ContainsKey('gapAudit')) {
    $gap = $loaded.gapAudit
    Assert-Equal 'gap schema' $gap.schemaVersion 1
    Assert-Equal 'gap audit' $gap.audit 'FIRST_PLAY_UI_AND_MOVEMENT_GAP_AUDIT'
    Assert-Sequence 'gap ui statuses' @($gap.uiBindings.status) @('STILL_STATIC_MISSING','STILL_STATIC_MISSING','OFFLINE_COLLECTOR_EXISTS_NOT_LIVE_HARDENED','LIVE_BINDING_MISSING','LIVE_BINDING_MISSING')
    Assert-Sequence 'gap ui missing' @($gap.uiBindings.firstMissingBoundary) @('MANAGER67_CURRENT_CARD_COLLECTOR_MISSING','SELECTED_CAPTAIN_CARD_WIDGET_COLLECTOR_MISSING','MANAGER65_LIVE_COLLECTOR_NOT_HARDENED','FRESH_DESTINATION_PROJECTION_SNAPSHOT','FRESH_TEXTDIALOG_SNAPSHOT_MISSING')
    $movementIds = @('MVB01_HANDLER_CALLBACK','MVB02_PAYLOAD_READY','MVB03_EXPECTED_SET','MVB04_COMMAND_SET','MVB05_TRANSPORT_SEND','MVB06_NOTIFY_DISPATCH','MVB07_NOTIFY_APPLIED')
    Assert-Sequence 'movement ids' @($gap.movementAnchors.id) $movementIds
    foreach ($anchor in $gap.movementAnchors) { Assert-Equal "movement binding $($anchor.id)" $anchor.binding 'MISSING' }
    Assert-Equal 'existing bp count' $gap.movementReceiptAudit.existingInformationBreakpointCount 14
    Assert-Equal 'movement intersection' $gap.movementReceiptAudit.movementAddressIntersectionCount 0
    Assert-Equal 'information reusable' $gap.movementReceiptAudit.informationBreakpointsReusable $false
    Assert-Sequence 'activation stages' $gap.activationGate.stageNames @('WARP','DESTINATION','CONFIRM')
    Assert-Equal 'stage max activation' $gap.activationGate.maximumPhysicalActivations 3
    Assert-Equal 'stage current budget' $gap.activationGate.currentAuthorityActivationBudget 1
    Assert-Equal 'activation gap status' $gap.activationGate.status 'ACTIVATION_BUDGET_STAGE_CONTRACT_MISMATCH'

    $gapSources = @{}
    foreach ($property in $gap.sources.PSObject.Properties) {
        $entry = $property.Value
        $path = Join-Path $repo ([string]$entry.path)
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { $errors.Add("gap source missing $($property.Name)"); continue }
        $actual = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
        Assert-Equal "gap source hash $($property.Name)" $actual ([string]$entry.sha256).ToUpperInvariant()
        $gapSources[$property.Name] = Load-Json $path
    }
    if ($gapSources.ContainsKey('firstServerActionLedger')) { Assert-Equal 'manager67 fresh required' $gapSources.firstServerActionLedger.nextLiveRunContract.requiresFreshManager67PointerAndGate $true }
    if ($gapSources.ContainsKey('warpWidgetLedger')) { Assert-Equal 'captain widget unbound' $gapSources.warpWidgetLedger.entryFromVisibleUi.captainCardExactWidget 'UNBOUND' }
    if ($gapSources.ContainsKey('visibleWarpLedger')) { Assert-Equal 'manager65 fresh widget unseen' $gapSources.visibleWarpLedger.freshBinding.widgetPointer 'UNSEEN' }
    if ($gapSources.ContainsKey('stageGateContract')) {
        Assert-Equal 'stage gate max' $gapSources.stageGateContract.maximumPhysicalActivations 3
        Assert-Sequence 'stage gate names' @($gapSources.stageGateContract.stages.name) @('WARP','DESTINATION','CONFIRM')
    }
    if ($gapSources.ContainsKey('stageGateVerification')) { Assert-Equal 'stage gate verification' $gapSources.stageGateVerification.result 'PASS' }
    if ($gapSources.ContainsKey('informationLiveContract')) {
        $movementAddresses = @($gap.movementAnchors | ForEach-Object { $_.address })
        $informationAddresses = @($gapSources.informationLiveContract.breakpoints | ForEach-Object { $_.address })
        Assert-Equal 'information BP exact count' @($informationAddresses).Count 14
        Assert-Equal 'actual movement intersection' @($movementAddresses | Where-Object { $informationAddresses -contains $_ }).Count 0
    }
    if ($gapSources.ContainsKey('destinationHitVerification')) { Assert-Equal 'gap destination runtime' $gapSources.destinationHitVerification.runtimeObserved 'UNSEEN' }
    if ($gapSources.ContainsKey('textDialogVerification')) { Assert-Equal 'gap dialog live ops' $gapSources.textDialogVerification.liveOperations 0 }
}

if ($errors.Count) {
    [ordered]@{ result='FAIL'; errors=@($errors) } | ConvertTo-Json -Depth 8
    return
}
[ordered]@{
    result='PASS'
    state=$contract.state
    permitEligible=$false
    launchEligible=$false
    blockerCount=$contract.blockers.Count
    firstMissingBoundary=$contract.firstMissingBoundary
    firstTechnicalBoundary=$contract.firstTechnicalBoundary
    priorPermitState=$contract.priorPermit.state
    liveOperations=0
    gameInputs=0
    artifactCount=$expectedHashes.Count
} | ConvertTo-Json -Depth 8
