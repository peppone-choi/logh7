$ErrorActionPreference = 'Stop'

$caseRoot = Split-Path -Parent $PSScriptRoot
$collector = Join-Path $caseRoot 'src/collect-confirm-stage-state.ps1'
$identity = Join-Path $PSScriptRoot 'fixture-identity.json'
$readyMemory = Join-Path $PSScriptRoot 'fixture-confirm-ready.json'
$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ('logh7-confirm-state-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tempRoot | Out-Null
$script:assertionCount = 0

function Assert-Equal($Name, $Actual, $Expected) {
    $script:assertionCount++
    if ($Actual -ne $Expected) { throw "$Name expected=$Expected actual=$Actual" }
}

function Invoke-Collector([string]$MemoryPath, [string]$Name) {
    $out = Join-Path $tempRoot "$Name.json"
    & $collector -FixtureMemoryPath $MemoryPath -FixtureIdentityPath $identity -OutputPath $out
    return Get-Content -LiteralPath $out -Raw -Encoding UTF8 | ConvertFrom-Json
}

function Write-Variant([string]$Name, [scriptblock]$Mutate) {
    $fixture = Get-Content -LiteralPath $readyMemory -Raw -Encoding UTF8 | ConvertFrom-Json
    & $Mutate $fixture
    $path = Join-Path $tempRoot "$Name-memory.json"
    $fixture | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath $path -Encoding UTF8
    return $path
}

try {
    $ready = Invoke-Collector $readyMemory 'ready'
    Assert-Equal 'ready stage' $ready.stage 'CONFIRM'
    Assert-Equal 'ready manager base' $ready.manager.base '0x00CA292C'
    Assert-Equal 'ready layout' $ready.manager.layout 4
    Assert-Equal 'ready state' $ready.manager.terminalState 1
    Assert-Equal 'ready confirm pointer' $ready.confirm.widgetPointer '0x02001000'
    Assert-Equal 'ready cancel pointer' $ready.cancel.widgetPointer '0x02002000'
    Assert-Equal 'ready confirm left' $ready.confirm.rawRect.left 400
    Assert-Equal 'ready confirm right' $ready.confirm.rawRect.right 520
    Assert-Equal 'ready cancel left' $ready.cancel.rawRect.left 540
    Assert-Equal 'ready cancel right' $ready.cancel.rawRect.right 660
    Assert-Equal 'ready state eligible' $ready.stateEligible $true
    Assert-Equal 'ready blocker count' @($ready.blockers).Count 0
    Assert-Equal 'ready binding eligible' $ready.bindingEligible $false
    Assert-Equal 'ready coordinate frame' $ready.coordinateFrame.status 'UNBOUND'
    Assert-Equal 'ready permit' $ready.permitIssued $false
    Assert-Equal 'ready reads' $ready.operations.memoryReadCount 27
    Assert-Equal 'ready writes' $ready.operations.writes 0
    Assert-Equal 'ready inputs' $ready.operations.gameInputs 0

    $wrongLayoutPath = Write-Variant 'wrong-layout' { param($f) $f.i32.'0x00CA2CA8' = 3 }
    $wrongLayout = Invoke-Collector $wrongLayoutPath 'wrong-layout'
    Assert-Equal 'wrong layout eligible' $wrongLayout.stateEligible $false
    Assert-Equal 'wrong layout blocker' $wrongLayout.blockers[0] 'TEXT_DIALOG_LAYOUT_NOT_4'

    $terminalPath = Write-Variant 'already-terminal' { param($f) $f.i32.'0x00CA370C' = 3 }
    $terminal = Invoke-Collector $terminalPath 'already-terminal'
    Assert-Equal 'terminal eligible' $terminal.stateEligible $false
    Assert-Equal 'terminal blocker' (@($terminal.blockers) -contains 'TERMINAL_STATE_NOT_WAITING_1_OR_2') $true

    $missingPointerPath = Write-Variant 'missing-confirm' { param($f) $f.u32.'0x00CA2950' = 0 }
    $missingPointer = Invoke-Collector $missingPointerPath 'missing-confirm'
    Assert-Equal 'missing confirm eligible' $missingPointer.stateEligible $false
    Assert-Equal 'missing confirm blocker' (@($missingPointer.blockers) -contains 'CONFIRM_WIDGET_POINTER_NULL') $true

    $canonicalHashRejected = $false
    try {
        & $collector -TargetProcessId 2147483647 -ExpectedStartTimeUtc '2000-01-01T00:00:00Z' -ExpectedExecutableSha256 ('0' * 64) -ExpectedWindowHandle '0x1' -OutputPath (Join-Path $tempRoot 'wrong-canonical.json')
    }
    catch {
        $canonicalHashRejected = $_.Exception.Message -like '*Expected executable SHA-256 is not the canonical G7MTClient target*'
    }
    Assert-Equal 'noncanonical expected hash rejected before process lookup' $canonicalHashRejected $true

    [ordered]@{
        result = 'PASS'
        cases = 5
        assertions = $script:assertionCount
        productionChangeCaught = 'wrong fixed manager derivation, wrong terminal-state gate, swapped confirm/cancel widget pointers, or fabricated client-space coordinates'
    } | ConvertTo-Json -Depth 4
}
finally {
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force
    }
}
