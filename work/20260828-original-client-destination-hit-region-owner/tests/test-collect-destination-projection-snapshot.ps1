$ErrorActionPreference = 'Stop'

$caseRoot = Split-Path -Parent $PSScriptRoot
$collector = Join-Path $caseRoot 'src/collect-destination-projection-snapshot.ps1'
$identityPath = Join-Path $PSScriptRoot 'fixture-identity.json'
$memoryPath = Join-Path $PSScriptRoot 'fixture-projection-memory.json'
$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ('logh7-projection-snapshot-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tempRoot | Out-Null
$script:assertions = 0

function Assert-Equal($Name, $Actual, $Expected) {
    $script:assertions++
    if ($Actual -ne $Expected) { throw "$Name expected=$Expected actual=$Actual" }
}

function Write-Variant([string]$Name, [string]$Source, [scriptblock]$Mutate) {
    $value = Get-Content -LiteralPath $Source -Raw -Encoding UTF8 | ConvertFrom-Json
    & $Mutate $value
    $path = Join-Path $tempRoot "$Name.json"
    $value | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $path -Encoding UTF8
    return $path
}

function Invoke-Collector([string]$Memory, [string]$Identity, [string]$Name) {
    $output = Join-Path $tempRoot "$Name-output.json"
    $null = & $collector -FixtureMemoryPath $Memory -FixtureIdentityPath $Identity -TargetGridX 50 -TargetGridY 25 -OutputPath $output
    return Get-Content -LiteralPath $output -Raw -Encoding UTF8 | ConvertFrom-Json
}

try {
    # Catches swapped matrix roles/addresses, omitted hover fields, and a collector
    # that fails to bind viewport dimensions to the owned HWND client area.
    $ready = Invoke-Collector $memoryPath $identityPath 'ready'
    Assert-Equal 'ready source mode' $ready.sourceMode 'OFFLINE_FIXTURE'
    Assert-Equal 'ready module base' $ready.identity.moduleBase '0x00400000'
    Assert-Equal 'ready stage eligible' $ready.stageEligible $true
    Assert-Equal 'ready blocker count' @($ready.blockers).Count 0
    Assert-Equal 'ready target x' $ready.target.gridX 50
    Assert-Equal 'ready target y' $ready.target.gridY 25
    Assert-Equal 'ready viewport width' $ready.viewport.width 100
    Assert-Equal 'ready viewport height' $ready.viewport.height 100
    Assert-Equal 'ready engine viewport right' $ready.engineViewportRect.right 100
    Assert-Equal 'ready engine viewport bottom' $ready.engineViewportRect.bottom 100
    Assert-Equal 'ready world matrix count' @($ready.world).Count 16
    Assert-Equal 'ready view matrix count' @($ready.view).Count 16
    Assert-Equal 'ready world matrix m11' $ready.world[0] 2
    Assert-Equal 'ready view translation x' $ready.view[12] 3
    Assert-Equal 'ready projection matrix m11' $ready.projection[0] 0.05
    Assert-Equal 'ready projection translation x' $ready.projection[12] -0.15
    Assert-Equal 'ready hover x' $ready.observedHover.gridX 50
    Assert-Equal 'ready hover y' $ready.observedHover.gridY 25
    Assert-Equal 'ready target validity' $ready.targetValidity.valid $true
    Assert-Equal 'ready target cell type' $ready.targetValidity.cellType 3
    Assert-Equal 'ready target filter' $ready.targetValidity.filter 5
    Assert-Equal 'ready current grid x' $ready.targetValidity.currentGridX 51
    Assert-Equal 'ready current grid y truncates' $ready.targetValidity.currentGridY 25
    Assert-Equal 'ready target distance' $ready.targetValidity.distance 1
    Assert-Equal 'ready memory reads' $ready.operations.memoryReadCount 144
    Assert-Equal 'ready writes' $ready.operations.writes 0
    Assert-Equal 'ready game inputs' $ready.operations.gameInputs 0
    Assert-Equal 'ready permit' $ready.permitIssued $false

    $mismatchIdentity = Write-Variant 'mismatch-identity' $identityPath { param($f) $f.clientWidth = 101 }
    $mismatch = Invoke-Collector $memoryPath $mismatchIdentity 'mismatch'
    Assert-Equal 'mismatch eligible' $mismatch.stageEligible $false
    Assert-Equal 'mismatch blocker' (@($mismatch.blockers) -contains 'VIEWPORT_CLIENT_SIZE_MISMATCH') $true

    $engineMismatchMemory = Write-Variant 'engine-viewport-mismatch' $memoryPath { param($f) $f.i32.'0x0302A604' = 99 }
    $engineMismatch = Invoke-Collector $engineMismatchMemory $identityPath 'engine-mismatch'
    Assert-Equal 'engine mismatch eligible' $engineMismatch.stageEligible $false
    Assert-Equal 'engine mismatch blocker' (@($engineMismatch.blockers) -contains 'ENGINE_VIEWPORT_CLIENT_SIZE_MISMATCH') $true

    $wrongModeMemory = Write-Variant 'wrong-mode-memory' $memoryPath { param($f) $f.i32.'0x009D2A34' = 4 }
    $wrongMode = Invoke-Collector $wrongModeMemory $identityPath 'wrong-mode'
    Assert-Equal 'wrong mode eligible' $wrongMode.stageEligible $false
    Assert-Equal 'wrong mode blocker' (@($wrongMode.blockers) -contains 'MODE_NOT_SELECT_GRID_0x101') $true

    $invalidTypeMemory = Write-Variant 'invalid-cell-type' $memoryPath { param($f) $f.u8.'0x042C176B' = 2 }
    $invalidType = Invoke-Collector $invalidTypeMemory $identityPath 'invalid-type'
    Assert-Equal 'invalid type eligible' $invalidType.stageEligible $false
    Assert-Equal 'invalid type validity' $invalidType.targetValidity.valid $false
    Assert-Equal 'invalid type reason' (@($invalidType.targetValidity.reasons) -contains 'CELL_TYPE_NOT_1_OR_3') $true
    Assert-Equal 'invalid type blocker' (@($invalidType.blockers) -contains 'TARGET_GRID_FUN_004D6310_INVALID') $true

    $inactiveChoiceMemory = Write-Variant 'inactive-choice' $memoryPath { param($f)
        $f.i32.'0x009D2A44' = 1
        $f.u8.'0x009AAE54' = 0
    }
    $inactiveChoice = Invoke-Collector $inactiveChoiceMemory $identityPath 'inactive-choice'
    Assert-Equal 'inactive choice eligible' $inactiveChoice.stageEligible $false
    Assert-Equal 'inactive choice reason' (@($inactiveChoice.targetValidity.reasons) -contains 'TARGET_RENDER_RECORD_NOT_ACTIVE') $true

    $tornMemory = Write-Variant 'torn-projection' $memoryPath { param($f)
        $secondProjection = @($f.matrices.'0x009D13A8')
        $secondProjection[0] = 0.2
        $f | Add-Member -NotePropertyName secondRead -NotePropertyValue ([pscustomobject]@{
            matrices = [pscustomobject]@{ '0x009D13A8' = $secondProjection }
        })
    }
    $torn = Invoke-Collector $tornMemory $identityPath 'torn'
    Assert-Equal 'torn snapshot eligible' $torn.stageEligible $false
    Assert-Equal 'torn snapshot blocker' (@($torn.blockers) -contains 'PROJECTION_SURFACE_CHANGED_DURING_CAPTURE') $true

    $missingMatrixMemory = Write-Variant 'missing-view-matrix' $memoryPath { param($f) $f.matrices.PSObject.Properties.Remove('0x009D1368') }
    $missingOutput = Join-Path $tempRoot 'missing-output.json'
    $missingRejected = $false
    try {
        & $collector -FixtureMemoryPath $missingMatrixMemory -FixtureIdentityPath $identityPath -TargetGridX 50 -TargetGridY 25 -OutputPath $missingOutput
    }
    catch { $missingRejected = $_.Exception.Message -like '*Missing required fixture matrix*0x009D1368*' }
    Assert-Equal 'missing matrix rejected' $missingRejected $true
    Assert-Equal 'missing output absent' (Test-Path -LiteralPath $missingOutput) $false

    $canonicalRejected = $false
    try {
        & $collector -TargetProcessId 2147483647 -ExpectedStartTimeUtc '2000-01-01T00:00:00Z' -ExpectedExecutableSha256 ('0' * 64) -ExpectedWindowHandle '0x1' -TargetGridX 50 -TargetGridY 25 -OutputPath (Join-Path $tempRoot 'wrong-hash.json')
    }
    catch { $canonicalRejected = $_.Exception.Message -like '*Expected executable SHA-256 is not the canonical G7MTClient target*' }
    Assert-Equal 'canonical hash rejected before process lookup' $canonicalRejected $true

    $targetRejected = $false
    try {
        & $collector -FixtureMemoryPath $memoryPath -FixtureIdentityPath $identityPath -TargetGridX 100 -TargetGridY 25 -OutputPath (Join-Path $tempRoot 'wrong-target.json')
    }
    catch { $targetRejected = $_.Exception.Message -like '*Target grid is outside original bounds*' }
    Assert-Equal 'out of range target rejected' $targetRejected $true

    [ordered]@{
        result='PASS'; cases=10; assertions=$script:assertions
        productionChangesCaught=@(
            'wrong projection state addresses or matrix roles',
            'viewport not bound to owned HWND client dimensions',
            'mouse unprojection RECT not bound to owned HWND client dimensions',
            'mode gate removed',
            'FUN_004D6310 cell type or active-record gate removed',
            'torn cross-frame matrix snapshot accepted',
            'missing matrix silently zero-filled',
            'canonical executable gate removed',
            'target grid bounds removed'
        )
    } | ConvertTo-Json -Depth 6
}
finally {
    if (Test-Path -LiteralPath $tempRoot) { Remove-Item -LiteralPath $tempRoot -Recurse -Force }
}
