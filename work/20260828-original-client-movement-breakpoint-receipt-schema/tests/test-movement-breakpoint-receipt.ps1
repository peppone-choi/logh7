$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$verifier = Join-Path $root 'src/verify-movement-breakpoint-receipt.ps1'
$schema = Join-Path $root 'evidence/movement-breakpoint-receipt.schema.json'
$template = Join-Path $root 'evidence/movement-breakpoint-receipt-template.json'
$specimen = Join-Path $PSScriptRoot 'fixture-semantic-specimen.json'

if (-not (Test-Path -LiteralPath $verifier)) { throw 'RED: movement receipt verifier missing' }
if (-not (Test-Path -LiteralPath $schema)) { throw 'RED: movement receipt schema missing' }
if (-not (Test-Path -LiteralPath $template)) { throw 'RED: movement receipt template missing' }
if (-not (Test-Path -LiteralPath $specimen)) { throw 'RED: semantic specimen missing' }

$temp = Join-Path ([IO.Path]::GetTempPath()) ('logh7-movement-receipt-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp | Out-Null
$script:assertions = 0
$script:cases = 0

function Eq([string]$Name, $Actual, $Expected) {
    $script:assertions++
    if ($Actual -ne $Expected) { throw "$Name expected=$Expected actual=$Actual" }
}
function Run([string]$Path) {
    $script:cases++
    & $verifier -ReceiptPath $Path | ConvertFrom-Json
}
function Variant([string]$Name, [scriptblock]$Change) {
    $value = Get-Content -LiteralPath $specimen -Raw -Encoding UTF8 | ConvertFrom-Json
    & $Change $value
    $path = Join-Path $temp ($Name + '.json')
    $value | ConvertTo-Json -Depth 40 | Set-Content -LiteralPath $path -Encoding UTF8
    $path
}

try {
    $templateResult = Run $template
    Eq 'template result' $templateResult.result 'PASS'
    Eq 'template state' $templateResult.state 'EMPTY_TEMPLATE_NOT_LIVE'
    Eq 'template runtime' $templateResult.runtimeBindingStatus 'UNSEEN'
    Eq 'template anchors' $templateResult.anchorCount 9
    Eq 'template eligible' $templateResult.liveReceiptEligible $false
    Eq 'template permit' $templateResult.permitIssued $false

    $ready = Run $specimen
    Eq 'specimen result' $ready.result 'PASS'
    Eq 'specimen state' $ready.state 'SYNTHETIC_SEMANTIC_SPECIMEN'
    Eq 'specimen runtime' $ready.runtimeBindingStatus 'SYNTHETIC_SPECIMEN_ONLY'
    Eq 'specimen anchors' $ready.anchorCount 9
    Eq 'specimen hits' $ready.hitCount 9
    Eq 'specimen command' $ready.outboundOpcode '0x0B01'
    Eq 'specimen expected' $ready.expectedOpcode '0x0B07'
    Eq 'specimen pixel transition' $ready.ownedHwndPixelTransition $true
    Eq 'specimen live eligible' $ready.liveReceiptEligible $false
    Eq 'specimen permit' $ready.permitIssued $false

    $mutations = [ordered]@{
        wrongRva = { param($j) $j.breakpoints[0].rva = '0x001737D1' }
        wrongInstruction = { param($j) $j.breakpoints[2].instruction = 'MOV EBX,0x0B01' }
        wrongBreakpointOrder = { param($j) $x=$j.breakpoints[1];$j.breakpoints[1]=$j.breakpoints[2];$j.breakpoints[2]=$x }
        staleIdentity = { param($j) $j.run.capturedAtUtc = '2026-08-28T00:03:00Z' }
        clientHash = { param($j) $j.client.executableSha256 = 'A' * 64 }
        hwndOwner = { param($j) $j.client.hwndOwnerPid = $j.client.pid + 1 }
        moduleBase = { param($j) $j.client.moduleBase = '0x00500000' }
        breakpointMissing = { param($j) $j.breakpoints = @($j.breakpoints | Select-Object -First 6) }
        breakpointNotInstalled = { param($j) $j.breakpoints[4].installed = $false }
        softwareBreakpoint = { param($j) $j.instrumentationPlan.mechanism = 'SOFTWARE_INT3' }
        hitOrder = { param($j) $x=$j.hits[1];$j.hits[1]=$j.hits[2];$j.hits[2]=$x }
        hitAddress = { param($j) $j.hits[4].runtimeAddress = '0x004B85B7' }
        handlerVtable = { param($j) $j.hits[0].registers | Add-Member -NotePropertyName VTABLE -NotePropertyValue '0x00676AA8' -Force }
        movementOrdinal = { param($j) $j.hits[6].movementCommandOrdinal = 8 }
        payloadDigest = { param($j) $j.hits[4].payloadSha256 = 'B' * 64 }
        expectedRegister = { param($j) $j.hits[3].registers.EBX = '0x00000B01' }
        sendRegister = { param($j) $j.hits[4].registers.ESI = '0x00000B07' }
        sendStackPayload = { param($j) $j.hits[4].stack.'ESP+0x08' = '0x0012FE24' }
        queueExpected = { param($j) $j.hits[4].observedExpectedOpcode = '0x0B01' }
        preExpectedSelfClaim = { param($j) $j.hits[2].observedExpectedOpcode = '0x0B07' }
        preOutboundSelfClaim = { param($j) $j.hits[3].observedOutboundOpcode = '0x0B01' }
        outboundOpcode = { param($j) $j.correlation.outboundOpcode = '0x0B07' }
        expectedOpcode = { param($j) $j.correlation.expectedOpcode = '0x0B01' }
        notifyOpcode = { param($j) $j.correlation.inboundNotifyOpcode = '0x0B01' }
        correlationBasis = { param($j) $j.correlation.correlationBasis = 'INVENTED_WIRE_ID' }
        sameStateHash = { param($j) $j.correlation.stateAfterSha256 = $j.correlation.stateBeforeSha256 }
        completionCompare = { param($j) $j.hits[7].registers.EBX = '0x00000B01' }
        completionIndex = { param($j) $j.hits[7].registers.EDI = '0x00000003' }
        completionExpectedValue = { param($j) ($j.hits[7].memoryCaptures|Where-Object label -eq 'queued-expected-opcode').decodedU32 = 2817 }
        completionExpectedAddress = { param($j) ($j.hits[7].memoryCaptures|Where-Object label -eq 'queued-expected-opcode').resolvedAddress = '0x05367ECC' }
        queueNotDecremented = { param($j) $j.hits[8].registers.ECX = '0x00000002';($j.hits[8].memoryCaptures|Where-Object label -eq 'queue-count-after').decodedU32 = 2 }
        samePixelHash = { param($j) $j.ownedHwnd.afterPixelSha256 = $j.ownedHwnd.beforePixelSha256 }
        differentHwnd = { param($j) $j.ownedHwnd.afterHwnd = '0x00020002' }
        memoryWrite = { param($j) $j.operations.processMemoryWrites = 1 }
        automaticInput = { param($j) $j.operations.automaticInputs = 1 }
        selfPromoted = { param($j) $j.runtimeBindingStatus = 'ORIGINAL_RUNTIME_OBSERVED' }
        activePermit = { param($j) $j.priorPermit.state = 'ACTIVE'; $j.priorPermit.reusable = $true }
    }
    foreach ($entry in $mutations.GetEnumerator()) {
        $candidate = Variant $entry.Key $entry.Value
        Eq ($entry.Key + ' rejected') (Run $candidate).result 'FAIL'
    }

    [ordered]@{ result='PASS'; cases=$script:cases; assertions=$script:assertions; mutations=$mutations.Count } | ConvertTo-Json
}
finally {
    if (Test-Path -LiteralPath $temp) {
        $resolved = (Resolve-Path -LiteralPath $temp).Path
        $base = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
        if (-not $resolved.StartsWith($base, [StringComparison]::OrdinalIgnoreCase)) { throw 'unsafe temp cleanup target' }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
