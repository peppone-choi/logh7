$ErrorActionPreference = 'Stop'
$unit = $PSScriptRoot
$repo = (Resolve-Path (Join-Path $unit '..\..')).Path
$python = (Get-Command python).Source

& $python -B -m unittest -v (Join-Path $unit 'tests/test_warp_stage_gate_v2.py')
if ($LASTEXITCODE -ne 0) { throw "warp stage gate v2 tests failed: $LASTEXITCODE" }

$expectedPath = Join-Path $unit 'tests/expected-source-hashes.json'
$gatePath = Join-Path $unit 'tests/current-offline-gate-input.json'
$evaluatorPath = Join-Path $unit 'src/evaluate_warp_stage_gate_v2.py'
$expected = Get-Content -LiteralPath $expectedPath -Raw -Encoding UTF8 | ConvertFrom-Json
$gate = Get-Content -LiteralPath $gatePath -Raw -Encoding UTF8 | ConvertFrom-Json
foreach ($role in $expected.roles.PSObject.Properties) {
    $source = $gate.sources.PSObject.Properties[$role.Name].Value
    if ([string]$source.sha256 -ne [string]$role.Value) { throw "source/expected mismatch $($role.Name)" }
    $path = Join-Path $repo ([string]$source.path)
    if (-not (Test-Path -LiteralPath $path)) { throw "missing source $($role.Name)" }
    if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne [string]$role.Value) { throw "actual source mismatch $($role.Name)" }
}

$temp = Join-Path ([IO.Path]::GetTempPath()) ('warp-stage-gate-v2-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp | Out-Null
try {
    $output = Join-Path $temp 'evaluation.json'
    $expectedHash = (Get-FileHash -LiteralPath $expectedPath -Algorithm SHA256).Hash
    & $python -B $evaluatorPath --gate-input $gatePath --expected-hashes $expectedPath --expected-hashes-sha256 $expectedHash --repo-root $repo --output $output | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'current offline gate evaluation failed' }
    $evaluation = Get-Content -LiteralPath $output -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($evaluation.status -ne 'OFFLINE_WARP_GATE_V2_AUDIT_PASS_READY_FALSE' -or $evaluation.auditPass -ne $true) { throw 'offline audit status mismatch' }
    if ($evaluation.stageLocalMaxPhysicalActivations -ne 1 -or $evaluation.physicalActivationsBefore -ne 0 -or $evaluation.physicalActivationsRemaining -ne 1) { throw 'stage-local authority mismatch' }
    if ($evaluation.readinessBlockers[0] -ne 'FRESH_OWNED_HWND_NOT_OBSERVABLE_FROM_AVAILABLE_GUEST_OPERATION_SESSION') { throw 'first readiness blocker drift' }
    foreach ($stage in @('warp','destination','confirm')) { if ($evaluation.stages.$stage.lifecycle -ne 'NOT_CREATED' -or $evaluation.stages.$stage.consumed -ne 0) { throw "future stage drift $stage" } }
    foreach ($field in @('activationEligible','warpPrelaunchEligible','launchEligible','permitEligible','permitIssued','originalRuntimeObserved','independentLiveBinding','livePromotionAllowed')) { if ($evaluation.$field -ne $false) { throw "claim promotion $field" } }
    if ($null -ne $evaluation.activationPoint -or $null -ne $evaluation.automaticActivationPoint -or $null -ne $evaluation.permit -or $null -ne $evaluation.warpPreparation.bindingSourceSha256) { throw 'forbidden point, permit, or binding output' }
}
finally {
    if (Test-Path -LiteralPath $temp) {
        $resolved = (Resolve-Path $temp).Path
        $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
        if (-not $resolved.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'unsafe cleanup target' }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}

$source = Get-Content -LiteralPath $evaluatorPath -Raw -Encoding UTF8
$forbiddenImports = @('import subprocess','import ctypes','import win32api','import pyautogui','from subprocess','from ctypes')
$hits = @($forbiddenImports | Where-Object { $source.Contains($_) })
if ($hits.Count -ne 0) { throw "forbidden evaluator capability: $($hits -join ', ')" }

[ordered]@{
    result = 'PASS'
    status = 'OFFLINE_WARP_GATE_V2_AUDIT_PASS_READY_FALSE'
    unitTests = 8
    mutationSubtests = 83
    externalSourceRolesVerified = @($expected.roles.PSObject.Properties).Count
    stageLocalMaxPhysicalActivations = 1
    physicalActivationsConsumed = 0
    physicalActivationsRemaining = 1
    stagesCreated = 0
    activationEligible = $false
    activationPoint = $null
    permitIssued = $false
    liveOperations = 0
    processMemoryReads = 0
    processMemoryWrites = 0
    physicalInputs = 0
    automaticInputs = 0
    debuggerOperations = 0
    vmOperations = 0
    serverProtocolDatabaseChanges = 0
} | ConvertTo-Json -Depth 8
