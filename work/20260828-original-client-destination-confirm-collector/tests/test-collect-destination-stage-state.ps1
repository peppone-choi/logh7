$ErrorActionPreference = 'Stop'

$caseRoot = Split-Path -Parent $PSScriptRoot
$collector = Join-Path $caseRoot 'src/collect-destination-stage-state.ps1'
$identity = Join-Path $PSScriptRoot 'fixture-identity.json'
$readyMemory = Join-Path $PSScriptRoot 'fixture-destination-ready.json'
$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ('logh7-destination-state-' + [guid]::NewGuid().ToString('N'))
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
    $fixture | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $path -Encoding UTF8
    return $path
}

try {
    $ready = Invoke-Collector $readyMemory 'ready'
    Assert-Equal 'ready schema' $ready.schemaVersion 1
    Assert-Equal 'ready stage' $ready.stage 'DESTINATION'
    Assert-Equal 'ready mode' $ready.controller.mode 257
    Assert-Equal 'ready mode hex' $ready.controller.modeHex '0x00000101'
    Assert-Equal 'ready result' $ready.controller.resultState 0
    Assert-Equal 'ready selected grid' $ready.controller.selectedGridId -1
    Assert-Equal 'ready requested choice' $ready.controller.requestedChoice 0
    Assert-Equal 'ready state eligible' $ready.stateEligible $true
    Assert-Equal 'ready blocker count' @($ready.blockers).Count 0
    Assert-Equal 'ready binding eligible' $ready.bindingEligible $false
    Assert-Equal 'ready rectangle status' $ready.activationRectangle.status 'UNBOUND'
    Assert-Equal 'ready permit' $ready.permitIssued $false
    Assert-Equal 'ready reads' $ready.operations.memoryReadCount 9
    Assert-Equal 'ready writes' $ready.operations.writes 0
    Assert-Equal 'ready inputs' $ready.operations.gameInputs 0

    $wrongModePath = Write-Variant 'wrong-mode' { param($f) $f.i32.'0x009D2A34' = 4 }
    $wrongMode = Invoke-Collector $wrongModePath 'wrong-mode'
    Assert-Equal 'wrong mode state eligible' $wrongMode.stateEligible $false
    Assert-Equal 'wrong mode blocker' $wrongMode.blockers[0] 'MODE_NOT_SELECT_GRID_0x101'

    $alreadySelectedPath = Write-Variant 'already-selected' { param($f) $f.i32.'0x009D2A40' = 42 }
    $alreadySelected = Invoke-Collector $alreadySelectedPath 'already-selected'
    Assert-Equal 'already selected eligible' $alreadySelected.stateEligible $false
    Assert-Equal 'already selected blocker' (@($alreadySelected.blockers) -contains 'SELECTED_GRID_NOT_UNSET') $true

    $completedResultPath = Write-Variant 'completed-result' { param($f) $f.i32.'0x009D2A3C' = 1 }
    $completedResult = Invoke-Collector $completedResultPath 'completed-result'
    Assert-Equal 'completed result eligible' $completedResult.stateEligible $false
    Assert-Equal 'completed result blocker' (@($completedResult.blockers) -contains 'RESULT_STATE_NOT_WAITING') $true

    $missingPath = Write-Variant 'missing-result' { param($f) $f.i32.PSObject.Properties.Remove('0x009D2A3C') }
    $missingOut = Join-Path $tempRoot 'missing-result.json'
    $threw = $false
    try {
        & $collector -FixtureMemoryPath $missingPath -FixtureIdentityPath $identity -OutputPath $missingOut
    }
    catch {
        $threw = $_.Exception.Message -like '*Missing required fixture read*0x009D2A3C*'
    }
    Assert-Equal 'missing read fails closed' $threw $true
    Assert-Equal 'missing read output absent' (Test-Path -LiteralPath $missingOut) $false

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
        cases = 6
        assertions = $script:assertionCount
        productionChangeCaught = 'wrong controller offsets, permissive mode/result acceptance, or fabricated activation rectangle'
    } | ConvertTo-Json -Depth 4
}
finally {
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force
    }
}
