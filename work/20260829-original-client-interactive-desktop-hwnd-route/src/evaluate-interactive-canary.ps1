param(
    [Parameter(Mandatory=$true)][string]$ReceiptPath,
    [Parameter(Mandatory=$true)][string]$OutputPath,
    [string]$BindingPath
)

$ErrorActionPreference = 'Stop'

function Write-CanonicalJson {
    param([object]$Value, [string]$Path)
    $json = ($Value | ConvertTo-Json -Depth 16) -replace "`r`n", "`n"
    [IO.File]::WriteAllText($Path, $json + "`n", [Text.UTF8Encoding]::new($false))
}

function Test-ExactKeys {
    param([object]$Value, [string[]]$Expected)
    if ($null -eq $Value) { return $false }
    $actual = @($Value.PSObject.Properties.Name | Sort-Object)
    $wanted = @($Expected | Sort-Object)
    return (($actual.Count -eq $wanted.Count) -and
        ((Compare-Object -ReferenceObject $wanted -DifferenceObject $actual).Count -eq 0))
}

function Test-Hex64 { param([object]$Value) return ([string]$Value -match '^[0-9A-Fa-f]{64}$') }
function Get-Sha256 { param([string]$Path) return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash }
function Test-Hwnd { param([object]$Value) return ([string]$Value -match '^0x[0-9A-Fa-f]{16}$' -and [string]$Value -ne '0x0000000000000000') }
function Test-PositiveRect {
    param([object]$Rect)
    if (-not (Test-ExactKeys $Rect @('left','top','right','bottom'))) { return $false }
    return (([int64]$Rect.right -gt [int64]$Rect.left) -and ([int64]$Rect.bottom -gt [int64]$Rect.top))
}

$blockers = [Collections.Generic.List[string]]::new()
function Add-Blocker([string]$Code) { if (-not $blockers.Contains($Code)) { $blockers.Add($Code) } }

$receipt = $null
try {
    $receipt = Get-Content -LiteralPath $ReceiptPath -Raw -Encoding UTF8 | ConvertFrom-Json
} catch {
    Add-Blocker 'RECEIPT_JSON_INVALID'
}

$rootKeys = @('schemaVersion','runId','provenance','captureStartedAtUtc','captureCompletedAtUtc','helper','processes','windows','snapshots','snapshotStable','foreground','operations')
$helperKeys = @('scriptPath','scriptSha256','pid','sessionId','activeConsoleSessionId','userName','windowStation','desktop')
$processKeys = @('role','name','pid','sessionId','startTimeUtc','path','sha256','moduleBase','moduleSize','mainWindowHandle')
$windowKeys = @('role','hwnd','ownerPid','visible','title','class','windowRect','clientRect')
$snapshotKeys = @('label','capturedAtUtc','processes','windows')
$foregroundKeys = @('beforeHwnd','beforeOwnerPid','afterHwnd','afterOwnerPid','unchanged')
$operationKeys = @('helperProcessesCreated','guestFileWrites','processMemoryReads','processMemoryWrites','foregroundChanges','debuggerAttach','debuggerCommands','breakpointsInstalled','captures','gameInputs','automaticInputs','permitIssued','vmLifecycleChanges','serverChanges','protocolChanges','databaseChanges')

if ($null -ne $receipt) {
    if (-not (Test-ExactKeys $receipt $rootKeys)) { Add-Blocker 'SCHEMA_ROOT_KEYS_MISMATCH' }
    if ([int]$receipt.schemaVersion -ne 1) { Add-Blocker 'SCHEMA_VERSION_UNSUPPORTED' }
    if ([string]::IsNullOrWhiteSpace([string]$receipt.runId)) { Add-Blocker 'RUN_ID_INVALID' }
    if ($receipt.provenance -notin @('SYNTHETIC_FIXTURE','LIVE_READONLY_INTERACTIVE_CANARY')) { Add-Blocker 'PROVENANCE_INVALID' }

    try {
        $started = [datetime]$receipt.captureStartedAtUtc
        $completed = [datetime]$receipt.captureCompletedAtUtc
        if ($completed.ToUniversalTime() -lt $started.ToUniversalTime()) { throw 'capture order' }
    } catch { Add-Blocker 'CAPTURE_TIME_INVALID' }

    if (-not (Test-ExactKeys $receipt.helper $helperKeys)) { Add-Blocker 'SCHEMA_HELPER_KEYS_MISMATCH' }
    if ([int]$receipt.helper.sessionId -le 0 -or [int]$receipt.helper.sessionId -ne [int]$receipt.helper.activeConsoleSessionId) { Add-Blocker 'HELPER_SESSION_NOT_ACTIVE_CONSOLE' }
    if ([string]$receipt.helper.windowStation -cne 'WinSta0') { Add-Blocker 'HELPER_WINDOW_STATION_NOT_WINSTA0' }
    if ([string]$receipt.helper.desktop -cne 'Default') { Add-Blocker 'HELPER_DESKTOP_NOT_DEFAULT' }
    if (-not (Test-Hex64 $receipt.helper.scriptSha256)) { Add-Blocker 'HELPER_SCRIPT_HASH_INVALID' }
    if ([int]$receipt.helper.pid -le 0 -or [string]::IsNullOrWhiteSpace([string]$receipt.helper.scriptPath)) { Add-Blocker 'HELPER_IDENTITY_INVALID' }

    $snapshots = @($receipt.snapshots)
    if ($snapshots.Count -ne 2) { Add-Blocker 'SNAPSHOT_COUNT_NOT_2' }
    foreach ($snapshot in $snapshots) {
        if (-not (Test-ExactKeys $snapshot $snapshotKeys)) { Add-Blocker 'SCHEMA_SNAPSHOT_KEYS_MISMATCH' }
    }
    if ($snapshots.Count -eq 2) {
        if ([string]$snapshots[0].label -cne 'A' -or [string]$snapshots[1].label -cne 'B') { Add-Blocker 'SNAPSHOT_LABELS_INVALID' }
        try {
            $snapshotATime = [datetime]$snapshots[0].capturedAtUtc
            $snapshotBTime = [datetime]$snapshots[1].capturedAtUtc
            if ($snapshotBTime.ToUniversalTime() -le $snapshotATime.ToUniversalTime()) { throw 'snapshot order' }
        } catch { Add-Blocker 'SNAPSHOT_TIME_INVALID' }
        $semanticA = [ordered]@{processes=@($snapshots[0].processes);windows=@($snapshots[0].windows)} | ConvertTo-Json -Depth 12 -Compress
        $semanticB = [ordered]@{processes=@($snapshots[1].processes);windows=@($snapshots[1].windows)} | ConvertTo-Json -Depth 12 -Compress
        if ($semanticA -cne $semanticB -or $receipt.snapshotStable -ne $true) { Add-Blocker 'TORN_SNAPSHOT' }
        $summary = [ordered]@{processes=@($receipt.processes);windows=@($receipt.windows)} | ConvertTo-Json -Depth 12 -Compress
        if ($summary -cne $semanticB) { Add-Blocker 'SUMMARY_SNAPSHOT_MISMATCH' }
        $processes = @($snapshots[1].processes)
        $windows = @($snapshots[1].windows)
    } else {
        $processes = @($receipt.processes)
        $windows = @($receipt.windows)
    }
    foreach ($process in $processes) { if (-not (Test-ExactKeys $process $processKeys)) { Add-Blocker 'SCHEMA_PROCESS_KEYS_MISMATCH' } }
    $clients = @($processes | Where-Object role -eq 'CLIENT')
    $debuggers = @($processes | Where-Object role -eq 'DEBUGGER')
    if ($clients.Count -ne 1) { Add-Blocker 'CLIENT_PROCESS_COUNT_NOT_1' }
    if ($debuggers.Count -ne 1) { Add-Blocker 'DEBUGGER_PROCESS_COUNT_NOT_1' }
    $client = if ($clients.Count -eq 1) { $clients[0] } else { $null }
    $debugger = if ($debuggers.Count -eq 1) { $debuggers[0] } else { $null }

    if ($null -ne $client) {
        if ([string]$client.name -cne 'G7MTClient') { Add-Blocker 'CLIENT_NAME_MISMATCH' }
        if ([string]$client.sha256 -cne 'BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16') { Add-Blocker 'CLIENT_HASH_MISMATCH' }
        if ([int]$client.sessionId -ne [int]$receipt.helper.sessionId) { Add-Blocker 'CLIENT_SESSION_MISMATCH' }
        if (-not (Test-Hwnd $client.mainWindowHandle)) { Add-Blocker 'CLIENT_MAIN_HWND_INVALID' }
        if ([int]$client.pid -le 0 -or [int64]$client.moduleSize -le 0 -or [string]$client.moduleBase -notmatch '^0x[0-9A-Fa-f]{8,16}$') { Add-Blocker 'CLIENT_MODULE_IDENTITY_INVALID' }
        try { $clientStarted = [datetime]$client.startTimeUtc } catch { Add-Blocker 'CLIENT_START_TIME_INVALID' }
    }
    if ($null -ne $debugger) {
        if ([string]$debugger.name -cne 'x32dbg') { Add-Blocker 'DEBUGGER_NAME_MISMATCH' }
        if ([string]$debugger.sha256 -cne '42CF419B3549332AF44A8500E99085A0C590547CAE6950623FE592EA885711C6') { Add-Blocker 'DEBUGGER_HASH_MISMATCH' }
        if ([int]$debugger.sessionId -ne [int]$receipt.helper.sessionId) { Add-Blocker 'DEBUGGER_SESSION_MISMATCH' }
        if (-not (Test-Hwnd $debugger.mainWindowHandle)) { Add-Blocker 'DEBUGGER_MAIN_HWND_INVALID' }
        if ([int]$debugger.pid -le 0 -or [int64]$debugger.moduleSize -le 0 -or [string]$debugger.moduleBase -notmatch '^0x[0-9A-Fa-f]{8,16}$') { Add-Blocker 'DEBUGGER_MODULE_IDENTITY_INVALID' }
        try { $debuggerStarted = [datetime]$debugger.startTimeUtc } catch { Add-Blocker 'DEBUGGER_START_TIME_INVALID' }
    }

    foreach ($window in $windows) {
        if (-not (Test-ExactKeys $window $windowKeys)) { Add-Blocker 'SCHEMA_WINDOW_KEYS_MISMATCH' }
        if (-not (Test-PositiveRect $window.windowRect) -or -not (Test-PositiveRect $window.clientRect)) { Add-Blocker "$($window.role)_SURFACE_INVALID" }
    }
    $clientWindows = @($windows | Where-Object { $_.role -eq 'CLIENT' -and $_.visible -eq $true })
    $debuggerWindows = @($windows | Where-Object { $_.role -eq 'DEBUGGER' -and $_.visible -eq $true })
    if ($clientWindows.Count -ne 1) { Add-Blocker 'CLIENT_VISIBLE_WINDOW_COUNT_NOT_1' }
    if ($debuggerWindows.Count -ne 1) { Add-Blocker 'DEBUGGER_VISIBLE_WINDOW_COUNT_NOT_1' }
    if ($null -ne $client -and $clientWindows.Count -eq 1) {
        if ([int]$clientWindows[0].ownerPid -ne [int]$client.pid -or [string]$clientWindows[0].hwnd -cne [string]$client.mainWindowHandle) { Add-Blocker 'CLIENT_WINDOW_OWNER_MISMATCH' }
        if (-not (Test-PositiveRect $clientWindows[0].clientRect)) { Add-Blocker 'CLIENT_SURFACE_INVALID' }
    }
    if ($null -ne $debugger -and $debuggerWindows.Count -eq 1) {
        if ([int]$debuggerWindows[0].ownerPid -ne [int]$debugger.pid -or [string]$debuggerWindows[0].hwnd -cne [string]$debugger.mainWindowHandle) { Add-Blocker 'DEBUGGER_WINDOW_OWNER_MISMATCH' }
        if (-not (Test-PositiveRect $debuggerWindows[0].clientRect)) { Add-Blocker 'DEBUGGER_SURFACE_INVALID' }
    }

    if ($receipt.snapshotStable -ne $true) { Add-Blocker 'TORN_SNAPSHOT' }
    if (-not (Test-ExactKeys $receipt.foreground $foregroundKeys)) { Add-Blocker 'SCHEMA_FOREGROUND_KEYS_MISMATCH' }
    if ($receipt.foreground.unchanged -ne $true -or
        [string]$receipt.foreground.beforeHwnd -cne [string]$receipt.foreground.afterHwnd -or
        [int]$receipt.foreground.beforeOwnerPid -ne [int]$receipt.foreground.afterOwnerPid) { Add-Blocker 'FOREGROUND_CHANGED' }

    if (-not (Test-ExactKeys $receipt.operations $operationKeys)) { Add-Blocker 'SCHEMA_OPERATION_KEYS_MISMATCH' }
    if ([int]$receipt.operations.helperProcessesCreated -ne 1 -or [int]$receipt.operations.guestFileWrites -ne 3) { Add-Blocker 'HELPER_OPERATION_ACCOUNTING_INVALID' }
    $forbiddenCounts = @('processMemoryReads','processMemoryWrites','foregroundChanges','debuggerAttach','debuggerCommands','breakpointsInstalled','captures','gameInputs','automaticInputs','vmLifecycleChanges','serverChanges','protocolChanges','databaseChanges')
    foreach ($key in $forbiddenCounts) { if ([int]$receipt.operations.$key -ne 0) { Add-Blocker 'FORBIDDEN_OPERATION_RECORDED' } }
    if ($receipt.operations.permitIssued -ne $false) { Add-Blocker 'FORBIDDEN_OPERATION_RECORDED' }
}

$bindingValid = $false
if ($null -ne $receipt -and $receipt.provenance -eq 'LIVE_READONLY_INTERACTIVE_CANARY') {
    if ([string]::IsNullOrWhiteSpace($BindingPath) -or -not (Test-Path -LiteralPath $BindingPath)) {
        Add-Blocker 'LIVE_BINDING_MISSING'
    } else {
        try {
            $binding = Get-Content -LiteralPath $BindingPath -Raw -Encoding UTF8 | ConvertFrom-Json
            $bindingKeys = @('schemaVersion','runId','provenance','rawReceiptPath','rawReceiptSha256','collectorPath','collectorSha256','guestCollectorPath','prelaunchSessionReceiptPath','prelaunchSessionReceiptSha256','brokerReceiptPath','brokerReceiptSha256','startedReceiptPath','startedReceiptSha256','guestStartedPath','diagnosticReceiptPath','diagnosticReceiptSha256','guestDiagnosticPath','guestRawReceiptPath','programExecutable','argumentVector','vmrunInteractive','vmrunActiveWindow','vmrunHostExitCode','guestSourceCopies','helperLaunchCalls','captureCopyCalls')
            if (-not (Test-ExactKeys $binding $bindingKeys) -or [int]$binding.schemaVersion -ne 1 -or [string]$binding.provenance -cne 'HOST_BOUND_LIVE_INTERACTIVE_CANARY') { Add-Blocker 'BINDING_SCHEMA_INVALID' }
            if ([string]$binding.runId -cne [string]$receipt.runId) { Add-Blocker 'BINDING_RUN_MISMATCH' }
            if ([IO.Path]::GetFullPath([string]$binding.rawReceiptPath) -cne [IO.Path]::GetFullPath($ReceiptPath) -or
                [string]$binding.rawReceiptSha256 -cne (Get-Sha256 $ReceiptPath)) { Add-Blocker 'BINDING_RAW_RECEIPT_HASH_MISMATCH' }
            if (-not (Test-Path -LiteralPath $binding.collectorPath) -or [string]$binding.collectorSha256 -cne (Get-Sha256 $binding.collectorPath)) { Add-Blocker 'BINDING_COLLECTOR_HASH_MISMATCH' }
            if ([string]$receipt.helper.scriptSha256 -cne [string]$binding.collectorSha256 -or [string]$receipt.helper.scriptPath -cne [string]$binding.guestCollectorPath) { Add-Blocker 'BINDING_COLLECTOR_IDENTITY_MISMATCH' }
            foreach ($pair in @(
                @('prelaunchSessionReceiptPath','prelaunchSessionReceiptSha256'),
                @('brokerReceiptPath','brokerReceiptSha256'),
                @('startedReceiptPath','startedReceiptSha256'),
                @('diagnosticReceiptPath','diagnosticReceiptSha256'))) {
                $path = [string]$binding.($pair[0]); $hash = [string]$binding.($pair[1])
                if (-not (Test-Path -LiteralPath $path) -or -not (Test-Hex64 $hash) -or $hash -cne (Get-Sha256 $path)) { Add-Blocker 'BINDING_SUPPORT_HASH_MISMATCH' }
            }

            $prelaunch = Get-Content -LiteralPath $binding.prelaunchSessionReceiptPath -Raw -Encoding UTF8 | ConvertFrom-Json
            $prelaunchClients = @($prelaunch.processes | Where-Object { $_.name -eq 'G7MTClient' -and [int]$_.sessionId -eq [int]$receipt.helper.sessionId })
            $prelaunchDebuggers = @($prelaunch.processes | Where-Object { $_.name -eq 'x32dbg' -and [int]$_.sessionId -eq [int]$receipt.helper.sessionId })
            try { $prelaunchTime=[datetime]$prelaunch.observedAtUtc } catch { $prelaunchTime=[datetime]::MaxValue }
            if ([string]$prelaunch.provenance -cne 'LIVE_READONLY_SESSION_DIAGNOSTIC' -or [int]$prelaunch.activeConsoleSessionId -ne [int]$receipt.helper.sessionId -or $prelaunchClients.Count -ne 1 -or $prelaunchDebuggers.Count -ne 1 -or $prelaunchTime.ToUniversalTime() -gt $started.ToUniversalTime()) { Add-Blocker 'BINDING_PRELAUNCH_INVALID' }

            $broker = Get-Content -LiteralPath $binding.brokerReceiptPath -Raw -Encoding UTF8 | ConvertFrom-Json
            $helperUserLeaf = ([string]$receipt.helper.userName -split '\\')[-1]
            $brokerUserLeaf = ([string]$broker.caller.name -split '\\')[-1]
            $interactiveAgents = @($broker.processes | Where-Object { $_.name -eq 'vmtoolsd' -and [int]$_.sessionId -eq [int]$receipt.helper.sessionId })
            try { $brokerTime=[datetime]$broker.observedAtUtc } catch { $brokerTime=[datetime]::MaxValue }
            if ([string]$broker.provenance -cne 'LIVE_READONLY_BROKER_INVENTORY' -or $helperUserLeaf -cne $brokerUserLeaf -or $interactiveAgents.Count -lt 1 -or $brokerTime.ToUniversalTime() -gt $started.ToUniversalTime()) { Add-Blocker 'BINDING_BROKER_INVALID' }

            $startedReceipt = Get-Content -LiteralPath $binding.startedReceiptPath -Raw -Encoding UTF8 | ConvertFrom-Json
            $startedHelperJson = $startedReceipt.helper | ConvertTo-Json -Depth 8 -Compress
            $rawHelperJson = $receipt.helper | ConvertTo-Json -Depth 8 -Compress
            try { $startedMarkerTime=[datetime]$startedReceipt.capturedAtUtc } catch { $startedMarkerTime=[datetime]::MinValue }
            if ([string]$startedReceipt.status -cne 'STARTED' -or [string]$startedReceipt.runId -cne [string]$receipt.runId -or $startedHelperJson -cne $rawHelperJson -or [string]$startedReceipt.helper.scriptPath -cne [string]$binding.guestCollectorPath -or $startedMarkerTime.ToUniversalTime() -lt $started.ToUniversalTime() -or $startedMarkerTime.ToUniversalTime() -gt $completed.ToUniversalTime()) { Add-Blocker 'BINDING_STARTED_HELPER_MISMATCH' }

            $diagnosticReceipt = Get-Content -LiteralPath $binding.diagnosticReceiptPath -Raw -Encoding UTF8 | ConvertFrom-Json
            try { $diagnosticTime=[datetime]$diagnosticReceipt.capturedAtUtc } catch { $diagnosticTime=[datetime]::MinValue }
            if ([string]$diagnosticReceipt.status -cne 'PASS' -or [string]$diagnosticReceipt.runId -cne [string]$receipt.runId -or [string]$diagnosticReceipt.scriptSha256 -cne [string]$binding.collectorSha256 -or [string]$diagnosticReceipt.outputPath -cne [string]$binding.guestRawReceiptPath -or [string]$diagnosticReceipt.startedPath -cne [string]$binding.guestStartedPath -or $diagnosticTime.ToUniversalTime() -lt $completed.ToUniversalTime()) { Add-Blocker 'BINDING_DIAGNOSTIC_INVALID' }

            $expectedProgram = 'C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe'
            $expectedArguments = @('-NoProfile','-WindowStyle','Hidden','-ExecutionPolicy','Bypass','-File',[string]$binding.guestCollectorPath,'-RunId',[string]$receipt.runId,'-OutputPath',[string]$binding.guestRawReceiptPath,'-StartedPath',[string]$binding.guestStartedPath,'-DiagnosticPath',[string]$binding.guestDiagnosticPath)
            $actualArguments = @($binding.argumentVector)
            $argumentMismatch = ($actualArguments.Count -ne $expectedArguments.Count -or (Compare-Object -ReferenceObject $expectedArguments -DifferenceObject $actualArguments -SyncWindow 0).Count -ne 0)
            if ([string]$binding.programExecutable -cne $expectedProgram -or $argumentMismatch -or $binding.vmrunInteractive -ne $true -or $binding.vmrunActiveWindow -ne $false -or [int]$binding.vmrunHostExitCode -ne 0 -or [int]$binding.guestSourceCopies -ne 1 -or [int]$binding.helperLaunchCalls -ne 1 -or [int]$binding.captureCopyCalls -ne 3) { Add-Blocker 'BINDING_ROUTE_INVALID' }
            $bindingBlockers = @($blockers | Where-Object { $_ -match '^BINDING_|^LIVE_BINDING_' })
            $bindingValid = ($bindingBlockers.Count -eq 0)
        } catch {
            Add-Blocker 'BINDING_INVALID'
        }
    }
}

$structuralEligible = ($null -ne $receipt -and (@($blockers | Where-Object { $_ -notin @('LIVE_BINDING_MISSING') })).Count -eq 0)
$eligible = if ($null -eq $receipt) { $false } elseif ($receipt.provenance -eq 'LIVE_READONLY_INTERACTIVE_CANARY') { $structuralEligible -and $bindingValid -and $blockers.Count -eq 0 } else { $blockers.Count -eq 0 }
$status = if ($null -ne $receipt -and $receipt.provenance -eq 'LIVE_READONLY_INTERACTIVE_CANARY' -and $blockers.Contains('LIVE_BINDING_MISSING')) { 'CLAIMED_LIVE_UNBOUND' }
          elseif (-not $eligible) { 'INTERACTIVE_HWND_CANDIDATE_REJECTED' }
          elseif ($receipt.provenance -eq 'LIVE_READONLY_INTERACTIVE_CANARY') { 'INTERACTIVE_HWND_LIVE_CANDIDATE_UNREVIEWED' }
          else { 'STRUCTURALLY_READY_SYNTHETIC_NOT_LIVE' }

$result = [ordered]@{
    schemaVersion = 1
    status = $status
    provenance = if ($null -ne $receipt) { [string]$receipt.provenance } else { $null }
    interactiveHwndCandidateEligible = $eligible
    livePromotionAllowed = $false
    prelaunchEligible = $false
    independentReviewRequired = $true
    foregroundUnchanged = ($eligible -and $receipt.foreground.unchanged -eq $true)
    client = if ($null -ne $client) { [ordered]@{ pid=[int]$client.pid; hwnd=[string]$client.mainWindowHandle; sha256=[string]$client.sha256; sessionId=[int]$client.sessionId } } else { $null }
    debugger = if ($null -ne $debugger) { [ordered]@{ pid=[int]$debugger.pid; hwnd=[string]$debugger.mainWindowHandle; sha256=[string]$debugger.sha256; sessionId=[int]$debugger.sessionId } } else { $null }
    blockers = @($blockers)
}

Write-CanonicalJson $result $OutputPath
$result | ConvertTo-Json -Depth 8
