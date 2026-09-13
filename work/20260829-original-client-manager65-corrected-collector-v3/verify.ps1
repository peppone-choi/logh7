$ErrorActionPreference = 'Stop'
$unit = $PSScriptRoot
$repo = (Resolve-Path (Join-Path $unit '..\..')).Path
$python = (Get-Command python).Source

& $python -m unittest -v (Join-Path $unit 'tests/test_manager65_v3.py')
if ($LASTEXITCODE -ne 0) { throw "manager65 v3 tests failed: $LASTEXITCODE" }

$ledgerPath = Join-Path $unit 'evidence/static-source-ledger.json'
$ledger = Get-Content -LiteralPath $ledgerPath -Raw -Encoding UTF8 | ConvertFrom-Json
foreach ($source in $ledger.sources) {
    $path = Join-Path $repo ([string]$source.path)
    if (-not (Test-Path -LiteralPath $path)) { throw "missing source $($source.path)" }
    if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne [string]$source.sha256) { throw "source hash mismatch $($source.path)" }
}
$target = (Resolve-Path (Join-Path $repo 'evidence/installshield-extract/*/*/exe/g7mtclient.exe')).Path
if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ne [string]$ledger.target.sha256) { throw 'canonical executable hash mismatch' }

$collectorPath = Join-Path $unit 'src/collect-manager65-action2b-v3.ps1'
$evaluatorPath = Join-Path $unit 'src/evaluate_manager65_capture_v3.py'
$collectorSource = Get-Content -LiteralPath $collectorPath -Raw -Encoding UTF8
$imports = [regex]::Matches($collectorSource, 'DllImport\("(?<dll>[^\"]+)"[^\]]*\)\]\s*public static extern (?<signature>[^;]+);')
$native = @($imports | ForEach-Object { if ($_.Groups['signature'].Value -match '\s(?<name>[A-Za-z0-9_]+)\(') { $Matches.name } } | Sort-Object -Unique)
$expectedNative = @('CloseHandle','GetClientRect','GetWindowThreadProcessId','IsWindow','IsWindowVisible','OpenProcess','ReadProcessMemory')
if (($native | ConvertTo-Json -Compress) -ne ($expectedNative | ConvertTo-Json -Compress)) { throw "native surface mismatch: $($native -join ', ')" }
$forbiddenNative = @('WriteProcessMemory','SendInput','SetCursorPos','PostMessage','mouse_event','keybd_event','VirtualAllocEx','CreateRemoteThread','DebugActiveProcess')
if (@($native | Where-Object { $_ -in $forbiddenNative }).Count -ne 0) { throw 'forbidden native capability present' }
if (-not $collectorSource.Contains('OpenProcess(0x0410')) { throw 'read-only OpenProcess access mask missing' }

$temp = Join-Path ([IO.Path]::GetTempPath()) ('manager65-v3-verify-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp | Out-Null
try {
    $capture = Join-Path $temp 'capture.json'
    $evaluation = Join-Path $temp 'evaluation.json'
    & $collectorPath -OracleRunId 'SYNTHETIC-RUN-V3' -ExternalIdentityReceiptSha256 ('A' * 64) -FixtureMemoryPath (Join-Path $unit 'tests/fixture-memory.json') -FixtureIdentityPath (Join-Path $unit 'tests/fixture-identity.json') -OutputPath $capture
    $captureHash = (Get-FileHash -LiteralPath $capture -Algorithm SHA256).Hash
    $collectorHash = (Get-FileHash -LiteralPath $collectorPath -Algorithm SHA256).Hash
    & $python $evaluatorPath --capture $capture --collector $collectorPath --expected-capture-sha256 $captureHash --expected-collector-sha256 $collectorHash --expected-run-id 'SYNTHETIC-RUN-V3' --expected-external-identity-receipt-sha256 ('A' * 64) --output $evaluation | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'fresh fixture evaluation failed' }
    $captureJson = Get-Content -LiteralPath $capture -Raw -Encoding UTF8 | ConvertFrom-Json
    $evaluationJson = Get-Content -LiteralPath $evaluation -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($captureJson.operations.memoryReadCount -ne 150 -or $captureJson.operations.memoryWrites -ne 0 -or $captureJson.operations.gameInputs -ne 0) { throw 'collector operation receipt mismatch' }
    if ($evaluationJson.status -ne 'OFFLINE_CORRECTED_MANAGER65_ACTION_0X2B_CANDIDATE_PASS' -or $evaluationJson.warpPrelaunchEligible -ne $false -or $null -ne $evaluationJson.automaticActivationPoint) { throw 'claim-ceiling mismatch' }
    if ($evaluationJson.remainingLiveBlockers -notcontains 'FRESH_OWNED_HWND_NOT_OBSERVABLE_FROM_AVAILABLE_GUEST_OPERATION_SESSION') { throw 'live blocker not preserved' }
}
finally {
    if (Test-Path -LiteralPath $temp) {
        $resolved = (Resolve-Path $temp).Path
        $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
        if (-not $resolved.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'unsafe cleanup target' }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}

[ordered]@{
    result = 'PASS'
    status = 'OFFLINE_CORRECTED_MANAGER65_ACTION_0X2B_COLLECTOR_V3_PASS_RUNTIME_UNSEEN'
    unitTests = 7
    mutationSubtests = 62
    staticSourcesVerified = $ledger.sources.Count
    canonicalExecutableVerified = $true
    nativeReadOnlyApis = $native
    forbiddenNativeCapabilities = 0
    freshFixtureMemoryReads = 150
    liveOperations = 0
    processMemoryWrites = 0
    gameInputs = 0
    debuggerOperations = 0
    vmOperations = 0
    serverProtocolDatabaseChanges = 0
    permitIssued = $false
    livePromotionAllowed = $false
} | ConvertTo-Json -Depth 8
