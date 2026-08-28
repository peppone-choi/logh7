$ErrorActionPreference = 'Stop'

$caseRoot = Split-Path -Parent $PSScriptRoot
$ledgerPath = Join-Path $PSScriptRoot 'destination-confirm-stage-ledger.json'
$exportPath = Join-Path $PSScriptRoot 'destination-confirm-owners.txt'
$destinationCollector = Join-Path $caseRoot 'src/collect-destination-stage-state.ps1'
$confirmCollector = Join-Path $caseRoot 'src/collect-confirm-stage-state.ps1'
$exporter = Join-Path $caseRoot 'ExportDestinationConfirmOwners.java'
$destinationTest = Join-Path $caseRoot 'tests/test-collect-destination-stage-state.ps1'
$confirmTest = Join-Path $caseRoot 'tests/test-collect-confirm-stage-state.ps1'
$identityFixture = Join-Path $caseRoot 'tests/fixture-identity.json'
$destinationFixture = Join-Path $caseRoot 'tests/fixture-destination-ready.json'
$confirmFixture = Join-Path $caseRoot 'tests/fixture-confirm-ready.json'
$outputPath = Join-Path $PSScriptRoot 'final-verification.json'

function Assert-Equal($Name, $Actual, $Expected) {
    if ($Actual -ne $Expected) { throw "$Name expected=$Expected actual=$Actual" }
}
function Hash([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToUpperInvariant()
}

$ledger = Get-Content -LiteralPath $ledgerPath -Raw -Encoding UTF8 | ConvertFrom-Json
Assert-Equal 'result' $ledger.result 'STATIC_STAGE_STATE_COLLECTORS_PARTIAL'
Assert-Equal 'target hash' $ledger.target.sha256 'BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16'
Assert-Equal 'first missing boundary' $ledger.firstMissingBoundary 'DESTINATION_GRID_WORLD_TO_CLIENT_HIT_REGION_OWNER'
Assert-Equal 'permit issued' $ledger.status.permitIssued $false
Assert-Equal 'live operations' $ledger.status.liveOperations 0

Assert-Equal 'destination collector hash' (Hash $destinationCollector) $ledger.implementation.destinationCollectorSha256
Assert-Equal 'confirm collector hash' (Hash $confirmCollector) $ledger.implementation.confirmCollectorSha256
Assert-Equal 'exporter hash' (Hash $exporter) $ledger.implementation.exporterSha256
Assert-Equal 'static export hash' (Hash $exportPath) $ledger.implementation.staticExportSha256
Assert-Equal 'destination test hash' (Hash $destinationTest) $ledger.implementation.destinationTestSha256
Assert-Equal 'confirm test hash' (Hash $confirmTest) $ledger.implementation.confirmTestSha256
Assert-Equal 'identity fixture hash' (Hash $identityFixture) $ledger.implementation.identityFixtureSha256
Assert-Equal 'destination fixture hash' (Hash $destinationFixture) $ledger.implementation.destinationFixtureSha256
Assert-Equal 'confirm fixture hash' (Hash $confirmFixture) $ledger.implementation.confirmFixtureSha256

$destinationTestResult = (& $destinationTest | ConvertFrom-Json)
$confirmTestResult = (& $confirmTest | ConvertFrom-Json)
Assert-Equal 'destination tests' $destinationTestResult.result 'PASS'
Assert-Equal 'destination cases' $destinationTestResult.cases 6
Assert-Equal 'destination assertions' $destinationTestResult.assertions 24
Assert-Equal 'confirm tests' $confirmTestResult.result 'PASS'
Assert-Equal 'confirm cases' $confirmTestResult.cases 5
Assert-Equal 'confirm assertions' $confirmTestResult.assertions 25

$markers = @(
    'SLOT 00676b30 value=00581f80',
    'REF 00570a28 type=READ function=FUN_00570a10@00570a10 instruction=MOV EAX,[0x009d2a34]',
    'REF 00570b9b type=READ function=FUN_00570a10@00570a10 instruction=MOV EAX,[0x009d2a3c]',
    'REF 00570bfa type=READ function=FUN_00570a10@00570a10 instruction=MOV EDX,dword ptr [0x009d2a40]',
    '00572611 function=<none>  MOV ECX,0xc9e638',
    'MATCH 0056fa14 function=FUN_0056f960@0056f960 instruction=MOV dword ptr [ESI + 0xde0],0x3',
    'MATCH 0056fae8 function=FUN_0056f960@0056f960 instruction=MOV dword ptr [ESI + 0xde0],0x4',
    '===== FUNCTION FUN_004fdde0@004fdde0 ====='
)
$exportText = Get-Content -LiteralPath $exportPath -Raw -Encoding UTF8
foreach ($marker in $markers) {
    if (-not $exportText.Contains($marker)) { throw "static evidence marker missing: $marker" }
}

$forbidden = @(
    'WriteProcessMemory', 'VirtualProtectEx', 'CreateRemoteThread', 'SendInput',
    'mouse_event', 'keybd_event', 'SetCursorPos', 'DebugActiveProcess',
    'Start-Process', 'vmrun', 'TcpClient', 'System.Net.Sockets'
)
$collectorText = (Get-Content -LiteralPath $destinationCollector -Raw -Encoding UTF8) + "`n" +
                 (Get-Content -LiteralPath $confirmCollector -Raw -Encoding UTF8)
$forbiddenHits = @()
foreach ($token in $forbidden) {
    if ($collectorText.IndexOf($token, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
        $forbiddenHits += $token
    }
}
if ($forbiddenHits.Count -ne 0) { throw "forbidden capability token(s): $($forbiddenHits -join ', ')" }

$allowedNative = @('OpenProcess','ReadProcessMemory','CloseHandle','IsWindow','GetWindowThreadProcessId','GetClientRect')
$nativeMatches = [regex]::Matches($collectorText, 'extern\s+(?:bool|IntPtr|uint)\s+([A-Za-z0-9_]+)\s*\(')
$nativeNames = @($nativeMatches | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
foreach ($name in $nativeNames) {
    if ($allowedNative -notcontains $name) { throw "unapproved native import: $name" }
}

$result = [ordered]@{
    result = 'PASS'
    boundedStatus = 'STATIC_STAGE_STATE_COLLECTORS_PARTIAL'
    targetSha256 = $ledger.target.sha256
    destination = [ordered]@{ cases=6; assertions=24; stateCollector='PASS_OFFLINE'; activationRectangle='UNBOUND' }
    confirm = [ordered]@{ cases=5; assertions=25; stateAndWidgetCollector='PASS_OFFLINE'; coordinateFrame='UNBOUND' }
    staticMarkersChecked = $markers.Count
    nativeImports = $nativeNames
    forbiddenCapabilityHits = 0
    liveOperations = 0
    permitIssued = $false
    firstMissingBoundary = $ledger.firstMissingBoundary
    hashes = [ordered]@{
        ledger = Hash $ledgerPath
        exporter = Hash $exporter
        staticExport = Hash $exportPath
        destinationCollector = Hash $destinationCollector
        confirmCollector = Hash $confirmCollector
        destinationTest = Hash $destinationTest
        confirmTest = Hash $confirmTest
        identityFixture = Hash $identityFixture
        destinationFixture = Hash $destinationFixture
        confirmFixture = Hash $confirmFixture
    }
}
$resultJson = $result | ConvertTo-Json -Depth 7
$canonicalResultJson = ($resultJson -replace "`r?`n", "`n") + "`n"
[IO.File]::WriteAllText($outputPath, $canonicalResultJson, [Text.UTF8Encoding]::new($false))
$resultJson
