[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$ReceiptPath)

$ErrorActionPreference = 'Stop'
$unitRoot = Split-Path -Parent $PSScriptRoot
$repoRoot = (Resolve-Path (Join-Path $unitRoot '..\..')).Path
$schemaPath = Join-Path $unitRoot 'evidence/movement-breakpoint-receipt.schema.json'
$ledgerPath = Join-Path $unitRoot 'evidence/movement-breakpoint-static-ledger.json'
$receipt = Get-Content -LiteralPath $ReceiptPath -Raw -Encoding UTF8 | ConvertFrom-Json
$schema = Get-Content -LiteralPath $schemaPath -Raw -Encoding UTF8 | ConvertFrom-Json
$ledger = Get-Content -LiteralPath $ledgerPath -Raw -Encoding UTF8 | ConvertFrom-Json
$errors = [Collections.Generic.List[string]]::new()

function Add-Error([string]$Message) { $errors.Add($Message) }
function Eq([string]$Name, $Actual, $Expected) { if ($Actual -ne $Expected) { Add-Error "$Name expected=$Expected actual=$Actual" } }
function Seq([string]$Name, $Actual, $Expected) {
    if ((@($Actual) | ConvertTo-Json -Compress -Depth 10) -ne (@($Expected) | ConvertTo-Json -Compress -Depth 10)) { Add-Error "$Name sequence mismatch" }
}
function Is-Hex([object]$Value) { return $null -ne $Value -and [string]$Value -match '^0x[0-9A-F]+$' }
function Is-Sha([object]$Value) { return $null -ne $Value -and [string]$Value -match '^[0-9A-F]{64}$' }
function Hex-U64([string]$Value) { return [Convert]::ToUInt64($Value.Substring(2), 16) }
function Runtime-Address([string]$Base, [string]$Rva) { return ('0x{0:X8}' -f ((Hex-U64 $Base) + (Hex-U64 $Rva))) }
function Capture([object]$Hit, [string]$Label) { return @($Hit.memoryCaptures | Where-Object { $_.label -eq $Label }) | Select-Object -First 1 }

try {
    Eq 'schema dialect' $schema.'$schema' 'https://json-schema.org/draft/2020-12/schema'
    Eq 'schema root closed' $schema.additionalProperties $false
    Seq 'schema roots' $schema.required @('schemaVersion','receiptType','state','sourceMode','runtimeBindingStatus','run','client','debugger','priorPermit','instrumentationPlan','breakpoints','hits','correlation','ownedHwnd','operations','review','evaluation','permitIssued')
    Eq 'ledger kind' $ledger.ledger 'ORIGINAL_CLIENT_MOVEMENT_BREAKPOINT_STATIC_LEDGER'
    Eq 'ledger target hash' $ledger.target.sha256 'BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16'
    Eq 'ledger anchor count' @($ledger.anchors).Count 9
    foreach ($source in $ledger.sources.PSObject.Properties) {
        $path = Join-Path $repoRoot ([string]$source.Value.path)
        if (-not (Test-Path -LiteralPath $path)) { Add-Error "missing static source $($source.Name)"; continue }
        Eq "static source $($source.Name)" (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash ([string]$source.Value.sha256).ToUpperInvariant()
    }

    Eq 'schema version' $receipt.schemaVersion 1
    Eq 'receipt type' $receipt.receiptType 'ORIGINAL_CLIENT_MOVEMENT_BREAKPOINT_RECEIPT'
    Eq 'prior permit id' $receipt.priorPermit.id 'permit-live-v3-20260827-d89449bc2c3c4b70a9854833b2214012-once'
    Eq 'prior permit state' $receipt.priorPermit.state 'CONSUMED_NO_RETRY'
    Eq 'prior permit reusable' $receipt.priorPermit.reusable $false
    Eq 'permit issued' $receipt.permitIssued $false
    Eq 'breakpoint count' @($receipt.breakpoints).Count 9
    Eq 'instrumentation definitions' $receipt.instrumentationPlan.totalDefinitions 9
    Eq 'hardware limit' $receipt.instrumentationPlan.maxConcurrentHardwareBreakpoints 4
    Eq 'rearm required' $receipt.instrumentationPlan.rearmRequired $true
    Eq 'software INT3 forbidden' $receipt.instrumentationPlan.softwareInt3Allowed $false
    Eq 'instrumentation memory writes forbidden' $receipt.instrumentationPlan.processMemoryWritesAllowed $false

    for ($i=0; $i -lt [Math]::Min(9, @($receipt.breakpoints).Count); $i++) {
        $expected = $ledger.anchors[$i]
        $actual = $receipt.breakpoints[$i]
        Eq "bp[$i] id" $actual.id $expected.id
        Eq "bp[$i] order" $actual.order $expected.order
        Eq "bp[$i] staticVa" $actual.staticVa $expected.staticVa
        Eq "bp[$i] rva" $actual.rva $expected.rva
        Eq "bp[$i] role" $actual.role $expected.role
        Eq "bp[$i] instruction" $actual.instruction $expected.instruction
        Eq "bp[$i] software writes" $actual.softwarePatchBytesWritten 0
    }

    foreach ($name in @('processMemoryWrites','automaticInputs','binaryPatches','vmLifecycleChanges','serverProtocolDbChanges')) { Eq "operation $name" $receipt.operations.$name 0 }

    if ($receipt.state -eq 'EMPTY_TEMPLATE_NOT_LIVE') {
        Eq 'template source' $receipt.sourceMode 'NOT_RUN'
        Eq 'template runtime' $receipt.runtimeBindingStatus 'UNSEEN'
        Eq 'template mechanism' $receipt.instrumentationPlan.mechanism 'NOT_RUN'
        Eq 'template rearm status' $receipt.instrumentationPlan.rearmPlanStatus 'UNPROVEN'
        foreach ($value in @($receipt.run.oracleRunId,$receipt.run.authorizedScopeId,$receipt.run.singleWriter,$receipt.run.preparedAtUtc,$receipt.run.capturedAtUtc,$receipt.run.movementCommandOrdinal,$receipt.client.pid,$receipt.client.executableSha256,$receipt.client.moduleBase,$receipt.client.hwnd,$receipt.debugger.pid,$receipt.debugger.hwnd)) {
            if ($null -ne $value) { Add-Error 'template contains run-specific identity' }
        }
        foreach ($bp in $receipt.breakpoints) { Eq "template $($bp.id) installed" $bp.installed $false; Eq "template $($bp.id) runtime" $bp.runtimeAddress $null }
        Eq 'template hit count' @($receipt.hits).Count 0
        foreach ($property in $receipt.operations.PSObject.Properties) { Eq "template operation $($property.Name)" $property.Value 0 }
        Eq 'template capture method' $receipt.ownedHwnd.captureMethod 'NOT_RUN'
        Eq 'template review' $receipt.review.status 'NOT_REVIEWED'
        Eq 'template live eligible' $receipt.evaluation.liveReceiptEligible $false
    }
    elseif ($receipt.state -eq 'SYNTHETIC_SEMANTIC_SPECIMEN') {
        Eq 'specimen source' $receipt.sourceMode 'SYNTHETIC_TEST_ONLY'
        Eq 'specimen runtime' $receipt.runtimeBindingStatus 'SYNTHETIC_SPECIMEN_ONLY'
        Eq 'specimen mechanism' $receipt.instrumentationPlan.mechanism 'SYNTHETIC_TEST_ONLY'
        Eq 'specimen rearm status' $receipt.instrumentationPlan.rearmPlanStatus 'SYNTHETIC_SEMANTIC_SPECIMEN'
        foreach ($value in @($receipt.run.oracleRunId,$receipt.run.authorizedScopeId,$receipt.run.singleWriter,$receipt.run.preparedAtUtc,$receipt.run.capturedAtUtc,$receipt.client.processStartTimeUtc,$receipt.client.executablePath,$receipt.debugger.processStartTimeUtc,$receipt.debugger.executablePath)) {
            if ([string]::IsNullOrWhiteSpace([string]$value)) { Add-Error 'specimen identity field missing' }
        }
        try {
            $prepared = [DateTimeOffset]::Parse($receipt.run.preparedAtUtc)
            $captured = [DateTimeOffset]::Parse($receipt.run.capturedAtUtc)
            if ([Math]::Abs(($captured-$prepared).TotalSeconds) -gt $receipt.run.maxIdentityAgeSeconds) { Add-Error 'identity stale' }
        } catch { Add-Error 'identity timestamp invalid' }
        Eq 'client hash' $receipt.client.executableSha256 $ledger.target.sha256
        if ($receipt.client.pid -le 0 -or $receipt.client.hwndOwnerPid -ne $receipt.client.pid) { Add-Error 'client PID/HWND ownership invalid' }
        if (-not (Is-Hex $receipt.client.moduleBase) -or -not (Is-Hex $receipt.client.hwnd) -or $receipt.client.moduleSize -le 0 -or $receipt.client.clientWidth -le 0 -or $receipt.client.clientHeight -le 0) { Add-Error 'client module/window identity invalid' }
        if ($receipt.debugger.pid -le 0 -or $receipt.debugger.hwndOwnerPid -ne $receipt.debugger.pid -or $receipt.debugger.attachedClientPid -ne $receipt.client.pid -or $receipt.debugger.foregroundHwnd -ne $receipt.debugger.hwnd -or -not (Is-Sha $receipt.debugger.executableSha256)) { Add-Error 'debugger identity invalid' }
        for ($i=0; $i -lt [Math]::Min(9, @($receipt.breakpoints).Count); $i++) {
            $bp = $receipt.breakpoints[$i]
            Eq "bp[$i] installed" $bp.installed $true
            if (Is-Hex $receipt.client.moduleBase) { Eq "bp[$i] runtime" $bp.runtimeAddress (Runtime-Address $receipt.client.moduleBase $ledger.anchors[$i].rva) }
        }
        Eq 'hit count' @($receipt.hits).Count 9
        for ($i=0; $i -lt [Math]::Min(9, @($receipt.hits).Count); $i++) {
            $hit = $receipt.hits[$i]
            Eq "hit[$i] id" $hit.id $ledger.anchors[$i].id
            Eq "hit[$i] sequence" $hit.sequence ($i+1)
            if (Is-Hex $receipt.client.moduleBase) { Eq "hit[$i] runtime" $hit.runtimeAddress (Runtime-Address $receipt.client.moduleBase $ledger.anchors[$i].rva) }
            Eq "hit[$i] ordinal" $hit.movementCommandOrdinal $receipt.run.movementCommandOrdinal
            if (-not (Is-Sha $hit.payloadSha256) -or -not (Is-Sha $hit.registersSha256) -or -not (Is-Sha $hit.memoryCaptureSha256)) { Add-Error "hit[$i] digest invalid" }
        }
        Eq 'MVB02 stack flag' $receipt.hits[1].stack.'ESP+0x00' '0x00000001'
        Eq 'MVB02 stack kind' $receipt.hits[1].stack.'ESP+0x04' '0x0000003B'
        Eq 'MVB01 vtable' $receipt.hits[0].registers.VTABLE (Runtime-Address $receipt.client.moduleBase '0x00276AEC')
        Eq 'MVB01 selector' $receipt.hits[0].registers.SELECTOR '0x00000001'
        Eq 'MVB03 pre-execution expected claim' $receipt.hits[2].observedExpectedOpcode $null
        Eq 'MVB04 EBX proof' $receipt.hits[3].registers.EBX '0x00000B07'
        Eq 'MVB04 pre-execution outbound claim' $receipt.hits[3].observedOutboundOpcode $null
        Eq 'MVB05 ESI proof' $receipt.hits[4].registers.ESI '0x00000B01'
        Eq 'MVB05 transport stack' $receipt.hits[4].stack.'ESP+0x00' $receipt.hits[4].registers.EAX
        Eq 'MVB05 opcode stack' $receipt.hits[4].stack.'ESP+0x04' '0x00000B01'
        $sendPayload = Capture $receipt.hits[4] 'transport-payload'
        if ($null -eq $sendPayload) { Add-Error 'MVB05 payload capture missing' } else { Eq 'MVB05 payload expression' $sendPayload.addressExpression '[ESP+0x08]';Eq 'MVB05 payload pointer' $sendPayload.resolvedAddress $receipt.hits[4].stack.'ESP+0x08';Eq 'MVB05 payload hash' $sendPayload.sha256 $receipt.correlation.payloadSha256 }
        Eq 'MVB05 observed outbound' $receipt.hits[4].observedOutboundOpcode '0x0B01'
        Eq 'MVB05 observed expected' $receipt.hits[4].observedExpectedOpcode '0x0B07'
        Eq 'local kind' $receipt.correlation.localQueueKind '0x3B'
        Eq 'outbound opcode' $receipt.correlation.outboundOpcode '0x0B01'
        Eq 'expected opcode' $receipt.correlation.expectedOpcode '0x0B07'
        Eq 'notify opcode' $receipt.correlation.inboundNotifyOpcode '0x0B07'
        Eq 'correlation ordinal' $receipt.correlation.movementCommandOrdinal $receipt.run.movementCommandOrdinal
        Eq 'correlation queue slot' $receipt.hits[1].queueSlot $receipt.correlation.queueSlot
        foreach ($index in 2..8) { Eq "hit[$index] queue slot" $receipt.hits[$index].queueSlot $receipt.correlation.queueSlot }
        Eq 'payload MVB02' $receipt.hits[1].payloadSha256 $receipt.correlation.payloadSha256
        Eq 'payload MVB05' $receipt.hits[4].payloadSha256 $receipt.correlation.payloadSha256
        Eq 'wire id status' $receipt.correlation.wireCorrelationId 'ABSENT_OR_UNPROVEN'
        Eq 'correlation basis' $receipt.correlation.correlationBasis 'SINGLE_OUTSTANDING_QUEUE_ENTRY'
        Eq 'outbound before inbound' $receipt.correlation.outboundBeforeInbound $true
        Eq 'completion compare inbound' $receipt.hits[7].registers.EBX '0x00000B07'
        Eq 'completion compare expected' $receipt.hits[7].observedExpectedOpcode '0x0B07'
        $queueIndex = [int](Hex-U64 $receipt.hits[7].registers.EAX)
        $queueIndexTimesThree = [int](Hex-U64 $receipt.hits[7].registers.EDI)
        Eq 'completion queue slot' $receipt.hits[7].queueSlot $queueIndex
        Eq 'completion EDI relation' $queueIndexTimesThree ($queueIndex * 3)
        $expectedCapture = Capture $receipt.hits[7] 'queued-expected-opcode'
        $preCountCapture = Capture $receipt.hits[7] 'queue-count-before'
        $postCountCapture = Capture $receipt.hits[8] 'queue-count-after'
        if ($null -eq $expectedCapture) { Add-Error 'queued expected capture missing' } else { Eq 'queued expected value' $expectedCapture.decodedU32 2823;Eq 'queued expected address' $expectedCapture.resolvedAddress ('0x{0:X8}' -f ((Hex-U64 $receipt.hits[7].registers.ESI)+($queueIndexTimesThree*4)+0x357EC8)) }
        if ($null -eq $preCountCapture -or $null -eq $postCountCapture) { Add-Error 'queue count capture missing' } else { $pre=[int]$preCountCapture.decodedU32;$post=[int]$postCountCapture.decodedU32;if($pre-le0-or$post-lt0-or$post-ne($pre-1)){Add-Error 'queue count decrement relation invalid'};Eq 'queue post register' ([int](Hex-U64 $receipt.hits[8].registers.ECX)) $post;Eq 'queue count address stable' $postCountCapture.resolvedAddress $preCountCapture.resolvedAddress }
        if (-not (Is-Sha $receipt.correlation.stateBeforeSha256) -or -not (Is-Sha $receipt.correlation.stateAfterSha256) -or $receipt.correlation.stateBeforeSha256 -eq $receipt.correlation.stateAfterSha256) { Add-Error 'movement state transition digest invalid' }
        Eq 'owned before HWND' $receipt.ownedHwnd.beforeHwnd $receipt.client.hwnd
        Eq 'owned after HWND' $receipt.ownedHwnd.afterHwnd $receipt.client.hwnd
        Eq 'owned PID' $receipt.ownedHwnd.ownerPid $receipt.client.pid
        Eq 'owned method' $receipt.ownedHwnd.captureMethod 'OWNED_HWND_PRINTWINDOW'
        Eq 'owned stable' $receipt.ownedHwnd.ownershipStable $true
        if (-not (Is-Sha $receipt.ownedHwnd.beforePixelSha256) -or -not (Is-Sha $receipt.ownedHwnd.afterPixelSha256) -or $receipt.ownedHwnd.beforePixelSha256 -eq $receipt.ownedHwnd.afterPixelSha256) { Add-Error 'owned HWND pixel transition invalid' }
        Eq 'attach count' $receipt.operations.debuggerAttach 1
        Eq 'installed count' $receipt.operations.breakpointsInstalled 9
        Eq 'synthetic review' $receipt.review.status 'NOT_REVIEWED'
        Eq 'synthetic live eligible' $receipt.evaluation.liveReceiptEligible $false
    }
    else { Add-Error "unsupported state $($receipt.state) for offline verifier" }
}
catch { Add-Error "verification exception: $($_.Exception.Message)" }

if ($errors.Count) { [ordered]@{result='FAIL';errors=@($errors);permitIssued=$false} | ConvertTo-Json -Depth 10; return }
$isTemplate = $receipt.state -eq 'EMPTY_TEMPLATE_NOT_LIVE'
[ordered]@{
    result = 'PASS'
    state = $receipt.state
    runtimeBindingStatus = $receipt.runtimeBindingStatus
    anchorCount = @($receipt.breakpoints).Count
    hitCount = @($receipt.hits).Count
    outboundOpcode = $receipt.correlation.outboundOpcode
    expectedOpcode = $receipt.correlation.expectedOpcode
    ownedHwndPixelTransition = if ($isTemplate) { $false } else { $receipt.ownedHwnd.beforePixelSha256 -ne $receipt.ownedHwnd.afterPixelSha256 }
    liveReceiptEligible = $false
    permitIssued = $false
} | ConvertTo-Json -Depth 10
