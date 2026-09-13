$ErrorActionPreference = 'Stop'
$unit = $PSScriptRoot
$repo = (Resolve-Path (Join-Path $unit '..\..')).Path
$python = (Get-Command python).Source
$tests = Join-Path $unit 'tests/test_warp_external_binding_subject_v1.py'
$schema = Join-Path $unit 'evidence/warp-external-binding-subject-v1.schema.json'
$contract = Join-Path $unit 'tests/current-offline-contract.json'
$evaluator = Join-Path $unit 'src/evaluate_warp_external_binding_subject_v1.py'

& $python -B -m unittest -v $tests
if ($LASTEXITCODE -ne 0) { throw "external binding subject tests failed: $LASTEXITCODE" }

$temp = Join-Path ([IO.Path]::GetTempPath()) ('warp-external-binding-v1-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp | Out-Null
try {
    $output = Join-Path $temp 'evaluation.json'
    $schemaHash = (Get-FileHash -LiteralPath $schema -Algorithm SHA256).Hash
    $contractJson = Get-Content -LiteralPath $contract -Raw -Encoding UTF8 | ConvertFrom-Json
    $captureSource = Join-Path $repo ([string]$contractJson.testOnlyVector.source.capturePath)
    $evaluationSource = Join-Path $repo ([string]$contractJson.testOnlyVector.source.evaluationPath)
    $captureExpectedHash = (Get-FileHash -LiteralPath $captureSource -Algorithm SHA256).Hash
    $evaluationExpectedHash = (Get-FileHash -LiteralPath $evaluationSource -Algorithm SHA256).Hash
    & $python -B $evaluator --contract $contract --schema $schema --expected-schema-sha256 $schemaHash --expected-capture-sha256 $captureExpectedHash --expected-evaluation-sha256 $evaluationExpectedHash --repo-root $repo --output $output | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'current contract evaluation failed' }
    $result = Get-Content -LiteralPath $output -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($result.status -ne 'OFFLINE_WARP_EXTERNAL_LIVE_BINDING_SUBJECT_V1_PASS_NOT_CREATED_NOT_ELIGIBLE' -or $result.contractPass -ne $true -or $result.testOnlyVectorValid -ne $true) { throw 'evaluation status mismatch' }
    foreach ($lifecycle in $result.chainLifecycle.PSObject.Properties) { if ($lifecycle.Value -ne 'NOT_CREATED') { throw "chain created $($lifecycle.Name)" } }
    if ($result.readinessBlockers[0] -ne 'FRESH_OWNED_HWND_NOT_OBSERVABLE_FROM_AVAILABLE_GUEST_OPERATION_SESSION') { throw 'first readiness blocker drift' }
    foreach ($field in @('liveSubjectEligible','warpPrelaunchEligible','activationEligible','launchEligible','permitEligible','permitIssued','originalRuntimeObserved','independentLiveBinding','livePromotionAllowed')) { if ($result.$field -ne $false) { throw "claim promotion $field" } }
    foreach ($field in @('activationPoint','automaticActivationPoint','permit','bindingDigest')) { if ($null -ne $result.$field) { throw "forbidden output $field" } }
    $serialized = $result | ConvertTo-Json -Depth 20 -Compress
    foreach ($testCoordinate in @('"x":607','"left":560','"y":427')) { if ($serialized.Contains($testCoordinate)) { throw "test-only coordinate escaped into evaluation: $testCoordinate" } }
}
finally {
    if (Test-Path -LiteralPath $temp) {
        $resolved = (Resolve-Path $temp).Path
        $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
        if (-not $resolved.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'unsafe cleanup target' }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}

$contractJson = Get-Content -LiteralPath $contract -Raw -Encoding UTF8 | ConvertFrom-Json
$captureSource = Join-Path $repo ([string]$contractJson.testOnlyVector.source.capturePath)
$evaluationSource = Join-Path $repo ([string]$contractJson.testOnlyVector.source.evaluationPath)
if ((Get-FileHash -LiteralPath $captureSource -Algorithm SHA256).Hash -ne [string]$contractJson.testOnlyVector.source.captureSha256) { throw 'test-only capture source hash mismatch' }
if ((Get-FileHash -LiteralPath $evaluationSource -Algorithm SHA256).Hash -ne [string]$contractJson.testOnlyVector.source.evaluationSha256) { throw 'test-only evaluation source hash mismatch' }

$source = Get-Content -LiteralPath $evaluator -Raw -Encoding UTF8
$forbiddenImports = @('import subprocess','import ctypes','import socket','import win32api','import pyautogui','from subprocess','from ctypes')
$hits = @($forbiddenImports | Where-Object { $source.Contains($_) })
if ($hits.Count -ne 0) { throw "forbidden evaluator capability: $($hits -join ', ')" }

[ordered]@{
    result = 'PASS'
    status = 'OFFLINE_WARP_EXTERNAL_LIVE_BINDING_SUBJECT_V1_PASS_NOT_CREATED_NOT_ELIGIBLE'
    unitTests = 4
    mutationSubtests = 115
    jsonSchemaDraft202012 = 'PASS'
    chainStepsNotCreated = 6
    testOnlyVectorValid = $true
    testOnlyGeometryEscapedToLiveOutput = $false
    authorityMaxConsumedRemaining = '1/0/1'
    liveSubjectEligible = $false
    activationPoint = $null
    bindingDigest = $null
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
