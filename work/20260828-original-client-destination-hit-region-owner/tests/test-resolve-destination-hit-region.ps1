$ErrorActionPreference = 'Stop'

$caseRoot = Split-Path -Parent $PSScriptRoot
$resolver = Join-Path $caseRoot 'src/resolve-destination-hit-region.ps1'
$fixturePath = Join-Path $PSScriptRoot 'fixture-projection.json'
$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ('logh7-hit-region-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tempRoot | Out-Null
$script:assertions = 0

function Assert-Equal($Name, $Actual, $Expected) {
    $script:assertions++
    if ($Actual -ne $Expected) { throw "$Name expected=$Expected actual=$Actual" }
}

function Write-Variant([string]$Name, [scriptblock]$Mutate) {
    $fixture = Get-Content -LiteralPath $fixturePath -Raw -Encoding UTF8 | ConvertFrom-Json
    & $Mutate $fixture
    $path = Join-Path $tempRoot "$Name.json"
    $fixture | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $path -Encoding UTF8
    return $path
}

function Invoke-Resolver([string]$InputPath, [string]$Name) {
    $outputPath = Join-Path $tempRoot "$Name-output.json"
    & $resolver -SnapshotPath $InputPath -OutputPath $outputPath
    return Get-Content -LiteralPath $outputPath -Raw -Encoding UTF8 | ConvertFrom-Json
}

try {
    # Catches wrong matrix order, wrong Direct3D Y inversion, wrong grid rounding,
    # bounding-box off-by-one, and a safe point that lacks a 3x3 same-grid margin.
    $bound = Invoke-Resolver $fixturePath 'bound'
    Assert-Equal 'bound status' $bound.status 'BOUND_OFFLINE_FIXTURE'
    Assert-Equal 'bound eligible' $bound.bindingEligible $true
    Assert-Equal 'pixel count' $bound.region.pixelCount 25
    Assert-Equal 'span count' @($bound.region.scanlineSpans).Count 5
    Assert-Equal 'left' $bound.region.boundingRectangle.left 50
    Assert-Equal 'top' $bound.region.boundingRectangle.top 46
    Assert-Equal 'right exclusive' $bound.region.boundingRectangle.rightExclusive 55
    Assert-Equal 'bottom exclusive' $bound.region.boundingRectangle.bottomExclusive 51
    Assert-Equal 'safe x' $bound.safePoint.x 52
    Assert-Equal 'safe y' $bound.safePoint.y 47
    Assert-Equal 'safe margin' $bound.safePoint.sameGridChebyshevMargin 1
    Assert-Equal 'safe candidate x' $bound.safePoint.replayedGridX 50
    Assert-Equal 'safe candidate y' $bound.safePoint.replayedGridY 25
    Assert-Equal 'projected center x' $bound.projectedCellCenter.x 52.5
    Assert-Equal 'projected center y' $bound.projectedCellCenter.y 47.5
    Assert-Equal 'writes' $bound.operations.writes 0
    Assert-Equal 'inputs' $bound.operations.gameInputs 0
    Assert-Equal 'permit' $bound.permitIssued $false

    # Catches geometry-only promotion without the original FUN_004D6310 gate.
    $invalidTargetPath = Write-Variant 'invalid-target' { param($f) $f.targetValidity.valid = $false }
    $invalidTarget = Invoke-Resolver $invalidTargetPath 'invalid-target'
    Assert-Equal 'invalid target status' $invalidTarget.status 'UNBOUND'
    Assert-Equal 'invalid target blocker' (@($invalidTarget.blockers) -contains 'TARGET_GRID_VALIDITY_NOT_PROVEN') $true

    # Catches promoting a JSON self-claim into reviewed original-runtime evidence.
    $livePath = Write-Variant 'live-readonly' { param($f)
        $f | Add-Member -NotePropertyName sourceMode -NotePropertyValue 'LIVE_READONLY'
        $f | Add-Member -NotePropertyName provenance -NotePropertyValue ([pscustomobject]@{ originalRuntimeObserved=$true; playerVisible=$false })
    }
    $live = Invoke-Resolver $livePath 'live-readonly'
    Assert-Equal 'live snapshot status' $live.status 'UNBOUND'
    Assert-Equal 'live snapshot binding withheld' $live.bindingEligible $false
    Assert-Equal 'live self claim not promoted' $live.provenance.originalRuntimeObserved $false
    Assert-Equal 'live claim retained separately' $live.provenance.claimedOriginalRuntimeObserved $true
    Assert-Equal 'live review blocker' (@($live.blockers) -contains 'LIVE_SNAPSHOT_INDEPENDENT_BINDING_REQUIRED') $true

    # Catches a resolver that returns coordinates without the prior stage gate.
    $ineligiblePath = Write-Variant 'ineligible' { param($f) $f.stageEligible = $false }
    $ineligible = Invoke-Resolver $ineligiblePath 'ineligible'
    Assert-Equal 'ineligible status' $ineligible.status 'UNBOUND'
    Assert-Equal 'ineligible binding' $ineligible.bindingEligible $false
    Assert-Equal 'ineligible blocker' (@($ineligible.blockers) -contains 'DESTINATION_STAGE_NOT_ELIGIBLE') $true

    # Catches accepting a matrix that cannot be inverted for ray construction.
    $singularPath = Write-Variant 'singular' { param($f) $f.projection = @(0,0,0,0, 0,0,0,0, 0,0,0,0, 0,0,0,0) }
    $singular = Invoke-Resolver $singularPath 'singular'
    Assert-Equal 'singular status' $singular.status 'UNBOUND'
    Assert-Equal 'singular blocker' (@($singular.blockers) -contains 'COMBINED_MATRIX_NOT_INVERTIBLE') $true

    # Catches treating an unproved nonzero D3D viewport origin as client coordinates.
    $offsetPath = Write-Variant 'offset' { param($f) $f.viewport.x = 4 }
    $offset = Invoke-Resolver $offsetPath 'offset'
    Assert-Equal 'offset status' $offset.status 'UNBOUND'
    Assert-Equal 'offset blocker' (@($offset.blockers) -contains 'VIEWPORT_ORIGIN_NOT_CLIENT_ZERO') $true

    # Catches fabricating an activation point for a target outside the viewport.
    $invisiblePath = Write-Variant 'invisible' { param($f) $f.target.gridX = 99; $f.target.gridY = 49 }
    $invisible = Invoke-Resolver $invisiblePath 'invisible'
    Assert-Equal 'invisible status' $invisible.status 'UNBOUND'
    Assert-Equal 'invisible blocker' (@($invisible.blockers) -contains 'TARGET_GRID_HAS_NO_CLIENT_PIXELS') $true

    [ordered]@{
        result = 'PASS'
        cases = 7
        assertions = $script:assertions
        productionChangesCaught = @(
            'wrong matrix multiplication or Direct3D viewport convention',
            'wrong ftol grid quantization',
            'unsafe edge coordinate without 3x3 same-grid margin',
            'missing destination-stage eligibility gate',
            'geometry-only promotion without FUN_004D6310 validity',
            'self-claimed fixture promoted to live runtime evidence',
            'singular matrix acceptance',
            'unproved viewport-origin translation',
            'fabricated off-screen target point'
        )
    } | ConvertTo-Json -Depth 6
}
finally {
    if (Test-Path -LiteralPath $tempRoot) { Remove-Item -LiteralPath $tempRoot -Recurse -Force }
}
