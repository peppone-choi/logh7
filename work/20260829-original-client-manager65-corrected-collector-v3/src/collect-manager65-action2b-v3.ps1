[CmdletBinding()]
param(
    [int]$TargetProcessId,
    [string]$ExpectedStartTimeUtc,
    [string]$ExpectedExecutableSha256,
    [string]$ExpectedWindowHandle,
    [string]$OracleRunId,
    [string]$ExternalIdentityReceiptSha256,
    [string]$FixtureMemoryPath,
    [string]$FixtureIdentityPath,
    [Parameter(Mandatory=$true)][string]$OutputPath
)

$ErrorActionPreference = 'Stop'
$canonical = 'BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16'
$script:reads = 0

function Hex([uint32]$Value) { '0x{0:X8}' -f $Value }
function Add-Address([uint32]$Base, [uint64]$Offset) {
    $value = [uint64]$Base + $Offset
    if ($value -gt [uint32]::MaxValue) { throw 'ADDRESS_OVERFLOW' }
    [uint32]$value
}
function Require-Sha256([string]$Value, [string]$Name) {
    if ($Value -notmatch '^[A-Fa-f0-9]{64}$') { throw "$Name must be SHA-256." }
}

$fixtureMode = -not [string]::IsNullOrWhiteSpace($FixtureMemoryPath)
if ($fixtureMode -ne (-not [string]::IsNullOrWhiteSpace($FixtureIdentityPath))) {
    throw 'Both fixture paths are required.'
}
if ([string]::IsNullOrWhiteSpace($OracleRunId)) { throw 'OracleRunId is required.' }
Require-Sha256 $ExternalIdentityReceiptSha256 'ExternalIdentityReceiptSha256'

$handle = [IntPtr]::Zero
$captureStartedAtUtc = [DateTimeOffset]::UtcNow
if ($fixtureMode) {
    $fixture = Get-Content -LiteralPath $FixtureMemoryPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $identity = Get-Content -LiteralPath $FixtureIdentityPath -Raw -Encoding UTF8 | ConvertFrom-Json
}
else {
    Require-Sha256 $ExpectedExecutableSha256 'ExpectedExecutableSha256'
    if ($ExpectedExecutableSha256.ToUpperInvariant() -ne $canonical) { throw 'Expected executable SHA-256 is not canonical.' }
    if ($TargetProcessId -le 0 -or [string]::IsNullOrWhiteSpace($ExpectedStartTimeUtc) -or [string]::IsNullOrWhiteSpace($ExpectedWindowHandle)) {
        throw 'Live identity is incomplete.'
    }
    if (-not ('Manager65CorrectedNative' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class Manager65CorrectedNative {
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
  [DllImport("kernel32.dll", SetLastError=true)] public static extern IntPtr OpenProcess(uint access, bool inherit, int pid);
  [DllImport("kernel32.dll", SetLastError=true)] public static extern bool ReadProcessMemory(IntPtr process, IntPtr address, byte[] buffer, UIntPtr size, out UIntPtr read);
  [DllImport("kernel32.dll")] public static extern bool CloseHandle(IntPtr handle);
  [DllImport("user32.dll")] public static extern bool IsWindow(IntPtr hwnd);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hwnd);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);
  [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr hwnd, out RECT rect);
}
'@
    }
    $process = Get-Process -Id $TargetProcessId
    $process.Refresh()
    if ($process.ProcessName -ne 'G7MTClient') { throw 'Target is not G7MTClient.' }
    $start = $process.StartTime.ToUniversalTime().ToString('o')
    if ($start -ne ([DateTimeOffset]::Parse($ExpectedStartTimeUtc).ToUniversalTime().ToString('o'))) { throw 'Start time mismatch.' }
    $path = $process.Path
    $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
    if ($hash -ne $canonical) { throw 'Executable hash mismatch.' }
    $moduleBaseValue = [uint32]$process.MainModule.BaseAddress.ToInt64()
    if ($moduleBaseValue -ne [uint32]0x00400000) { throw 'Module base mismatch.' }
    $moduleSize = [int]$process.MainModule.ModuleMemorySize
    $hwnd = [IntPtr][Convert]::ToInt64(($ExpectedWindowHandle -replace '^0x', ''), 16)
    if (-not [Manager65CorrectedNative]::IsWindow($hwnd) -or -not [Manager65CorrectedNative]::IsWindowVisible($hwnd)) { throw 'Invalid or invisible HWND.' }
    [uint32]$ownerPidA = 0
    [void][Manager65CorrectedNative]::GetWindowThreadProcessId($hwnd, [ref]$ownerPidA)
    if ($ownerPidA -ne $TargetProcessId) { throw 'HWND ownership mismatch.' }
    $rectA = New-Object Manager65CorrectedNative+RECT
    if (-not [Manager65CorrectedNative]::GetClientRect($hwnd, [ref]$rectA)) { throw 'GetClientRect failed.' }
    $identity = [ordered]@{
        pid = $process.Id; startTimeUtc = $start; path = $path; sha256 = $hash
        moduleBase = Hex $moduleBaseValue; moduleSize = $moduleSize; sessionId = $process.SessionId
        hwnd = Hex ([uint32]$hwnd.ToInt64()); hwndOwnerPidA = $ownerPidA; hwndVisibleA = $true
        clientWidthA = $rectA.Right - $rectA.Left; clientHeightA = $rectA.Bottom - $rectA.Top
        hwndOwnerPidB = 0; hwndVisibleB = $false; clientWidthB = 0; clientHeightB = 0
    }
    $handle = [Manager65CorrectedNative]::OpenProcess(0x0410, $false, $TargetProcessId)
    if ($handle -eq [IntPtr]::Zero) { throw 'OpenProcess read-only failed.' }
}

$moduleBase = if ($fixtureMode) { [uint32][Convert]::ToUInt64(([string]$identity.moduleBase -replace '^0x', ''), 16) } else { [uint32]0x00400000 }
function VA([uint32]$Rva) { Add-Address $moduleBase $Rva }
function Read-Bytes([uint32]$Address, [int]$Length) {
    $buffer = [byte[]]::new($Length); $got = [UIntPtr]::Zero
    if (-not [Manager65CorrectedNative]::ReadProcessMemory($handle, [IntPtr][int64]$Address, $buffer, [UIntPtr][uint64]$Length, [ref]$got) -or $got.ToUInt64() -ne $Length) {
        throw "ReadProcessMemory failed at $(Hex $Address)"
    }
    $script:reads++; ,$buffer
}
function Fixture-Value($Snapshot, [string]$Kind, [uint32]$Address) {
    $key = Hex $Address; $property = $Snapshot.$Kind.PSObject.Properties[$key]
    if ($null -eq $property) { throw "Missing fixture $Kind $key" }
    $script:reads++; $property.Value
}
function U32($s, [uint32]$a) { if ($fixtureMode) { [uint32](Fixture-Value $s u32 $a) } else { [BitConverter]::ToUInt32((Read-Bytes $a 4), 0) } }
function I32($s, [uint32]$a) { if ($fixtureMode) { [int32](Fixture-Value $s i32 $a) } else { [BitConverter]::ToInt32((Read-Bytes $a 4), 0) } }
function U16($s, [uint32]$a) { if ($fixtureMode) { [uint16](Fixture-Value $s u16 $a) } else { [BitConverter]::ToUInt16((Read-Bytes $a 2), 0) } }
function U8($s, [uint32]$a) { if ($fixtureMode) { [byte](Fixture-Value $s u8 $a) } else { (Read-Bytes $a 1)[0] } }
function F32($s, [uint32]$a) { if ($fixtureMode) { [single](Fixture-Value $s f32 $a) } else { [BitConverter]::ToSingle((Read-Bytes $a 4), 0) } }

function Read-Context($s, [uint32]$Root) {
    $nodes = @(); $seen = @{}; $pointer = $Root; $x = 0; $y = 0
    for ($depth = 0; $depth -lt 16; $depth++) {
        if ($pointer -eq 0) { throw 'NULL_CONTEXT' }
        $key = Hex $pointer
        if ($seen.ContainsKey($key)) { throw 'CONTEXT_CYCLE' }
        $seen[$key] = $true
        $id = U32 $s $pointer; $active = U8 $s (Add-Address $pointer 4); $parent = I32 $s (Add-Address $pointer 8)
        $localX = I32 $s (Add-Address $pointer 0xC); $localY = I32 $s (Add-Address $pointer 0x10); $registry = U32 $s (Add-Address $pointer 0x30)
        $nodes += ,[ordered]@{ pointer=$key; id=[int]$id; active=[int]$active; parentId=$parent; localX=$localX; localY=$localY; registry=Hex $registry }
        $x += $localX; $y += $localY
        if ($parent -eq -1) { return [ordered]@{ nodes=$nodes; resolvedX=$x; resolvedY=$y } }
        if ($parent -lt 0 -or $parent -gt 0x72 -or $parent -eq $id -or $registry -eq 0) { throw 'INVALID_PARENT_CHAIN' }
        $pointer = U32 $s (Add-Address $registry ([uint32](4 + 4 * $parent)))
    }
    throw 'CONTEXT_DEPTH_EXCEEDED'
}

function Read-Widget($s, [uint32]$Pointer, [int]$OriginX, [int]$OriginY) {
    if ($Pointer -eq 0) { return [ordered]@{ widgetPointer='0x00000000'; status='NULL'; eligible=$false } }
    $initialized=U8 $s (Add-Address $Pointer 8); $selector=U8 $s (Add-Address $Pointer 0xA)
    $localX=I32 $s (Add-Address $Pointer 0xC); $localY=I32 $s (Add-Address $Pointer 0x10); $localGate=U8 $s (Add-Address $Pointer 0x14)
    $hit=U8 $s (Add-Address $Pointer 0x15); $active=U8 $s (Add-Address $Pointer 0x18); $render=U8 $s (Add-Address $Pointer 0x1B)
    $x=I32 $s (Add-Address $Pointer 0x20); $y=I32 $s (Add-Address $Pointer 0x24); $width=I32 $s (Add-Address $Pointer 0x2C); $height=I32 $s (Add-Address $Pointer 0x30)
    if ($selector -ne 0) { $x += $localX; $y += $localY }
    $x += $OriginX; $y += $OriginY
    $eligible = $initialized -ne 0 -and $hit -ne 0 -and $active -ne 0 -and $render -ne 0 -and $width -gt 0 -and $height -gt 0 -and ($selector -eq 0 -or $localGate -ne 0)
    [ordered]@{
        widgetPointer=Hex $Pointer; status='READ'; initialized=[int]$initialized; localSelector=[int]$selector; localX=$localX; localY=$localY
        localGate=[int]$localGate; hitTestEnabled=[int]$hit; activeVisible=[int]$active; renderVisible=[int]$render
        width=$width; height=$height; eligible=$eligible; logicalRect=[ordered]@{ left=$x; top=$y; right=$x+$width; bottom=$y+$height }
    }
}

function Capture-State($s) {
    $uiRoot = U32 $s (VA 0x1E15E2C)
    $uiBuilderMode = if ($uiRoot -ne 0) { I32 $s $uiRoot } else { -1 }
    $uiHandlerState = if ($uiRoot -ne 0) { I32 $s (Add-Address $uiRoot 4) } else { -1 }
    $registry = if ($uiRoot -ne 0) { U32 $s (Add-Address $uiRoot 0xC) } else { [uint32]0 }
    $strategyOwner = VA 0x89E638
    $controller65 = Add-Address $strategyOwner 0x130; $manager65 = U32 $s $controller65
    $controller67 = Add-Address $strategyOwner 0x48C; $manager67 = U32 $s $controller67
    $registry65 = if ($registry -ne 0) { U32 $s (Add-Address $registry 0x198) } else { [uint32]0 }
    $registry67 = if ($registry -ne 0) { U32 $s (Add-Address $registry 0x1A0) } else { [uint32]0 }
    $context = Read-Context $s $manager65
    $manager65InputGate = U8 $s (Add-Address $manager65 5)
    $manager67Id = if ($manager67 -ne 0) { U32 $s $manager67 } else { 0 }
    $manager67Active = if ($manager67 -ne 0) { U8 $s (Add-Address $manager67 4) } else { 0 }
    $manager67InputGate = if ($manager67 -ne 0) { U8 $s (Add-Address $manager67 5) } else { 0 }
    $page=I32 $s (Add-Address $controller65 0x34C); $count=I32 $s (Add-Address $controller65 0x350)
    $selected=I32 $s (Add-Address $controller65 0x354); $cardId=I32 $s (Add-Address $controller65 0x358)
    $recordOwner = U32 $s (VA 0x3CCFFC); $recordCount = -1; $actions = @()
    if ($recordOwner -ne 0 -and $cardId -ge 0 -and $cardId -le 0xFFFF) {
        $record = Add-Address (Add-Address $recordOwner 0x3416D8) ([uint64]$cardId * 0x46)
        $recordCount = U8 $s (Add-Address $record 0x1E)
        if ($count -ge 1 -and $count -le 24) {
            for ($index=0; $index -lt $count; $index++) {
                $widgetPointer=U32 $s (Add-Address $controller65 ([uint32](0x30 + 4*$index)))
                $command=U16 $s (Add-Address $record ([uint32](0x20 + 2*$index)))
                $widget=Read-Widget $s $widgetPointer $context.resolvedX $context.resolvedY
                $actions += ,[ordered]@{ index=$index; commandId=[int]$command; widget=$widget }
            }
        }
    }
    $scaleX=F32 $s (VA 0x372E2C); $scaleY=F32 $s (VA 0x372E30); $engine=U32 $s (VA 0x3C1B4C)
    $logicalWidth=I32 $s (Add-Address $engine 0x2A3D8); $logicalHeight=I32 $s (Add-Address $engine 0x2A3DC)
    $engineRect=[ordered]@{ left=I32 $s (Add-Address $engine 0x2A5FC); top=I32 $s (Add-Address $engine 0x2A600); right=I32 $s (Add-Address $engine 0x2A604); bottom=I32 $s (Add-Address $engine 0x2A608) }
    [ordered]@{
        uiRoot=[ordered]@{ pointer=Hex $uiRoot; builderMode=$uiBuilderMode; handlerState=$uiHandlerState; registryPointer=Hex $registry }
        strategyOwner=[ordered]@{ pointer=Hex $strategyOwner; role='INLINE_STRATEGY_MANAGER_OWNER' }
        manager65=[ordered]@{ controllerPointer=Hex $controller65; managerPointer=Hex $manager65; registrySlotPointer=Hex $registry65; inputGate=[int]$manager65InputGate; context=$context; page=$page; actionCount=$count; selectedIndex=$selected; cardId=$cardId; recordOwnerPointer=Hex $recordOwner; recordActionCount=[int]$recordCount; actions=$actions }
        manager67=[ordered]@{ controllerPointer=Hex $controller67; managerPointer=Hex $manager67; registrySlotPointer=Hex $registry67; managerId=[int]$manager67Id; active=[int]$manager67Active; inputGate=[int]$manager67InputGate; disposition='DORMANT_PRIOR_AUTHORITY_CARD_STAGE' }
        coordinateFrame=[ordered]@{ scaleX=$scaleX; scaleY=$scaleY; logicalWidth=$logicalWidth; logicalHeight=$logicalHeight; engineClientRect=$engineRect }
    }
}

try {
    $firstSnapshot = if ($fixtureMode) { $fixture.first } else { $null }
    $secondSnapshot = if ($fixtureMode -and $fixture.second -is [string] -and $fixture.second -eq 'SAME_AS_FIRST') { $fixture.first } elseif ($fixtureMode) { $fixture.second } else { $null }
    $first = Capture-State $firstSnapshot
    $observedAtUtc=[DateTimeOffset]::UtcNow
    $second = Capture-State $secondSnapshot
    if (-not $fixtureMode) {
        $process.Refresh()
        if ($process.StartTime.ToUniversalTime().ToString('o') -ne $identity.startTimeUtc -or (Get-FileHash -LiteralPath $process.Path -Algorithm SHA256).Hash -ne $canonical -or [uint32]$process.MainModule.BaseAddress.ToInt64() -ne [uint32]0x00400000) { throw 'Post-capture process identity changed.' }
        if (-not [Manager65CorrectedNative]::IsWindow($hwnd)) { throw 'Post-capture HWND missing.' }
        [uint32]$ownerPidB=0; [void][Manager65CorrectedNative]::GetWindowThreadProcessId($hwnd,[ref]$ownerPidB)
        $rectB=New-Object Manager65CorrectedNative+RECT
        if (-not [Manager65CorrectedNative]::GetClientRect($hwnd,[ref]$rectB)) { throw 'Post-capture GetClientRect failed.' }
        $identity.hwndOwnerPidB=$ownerPidB; $identity.hwndVisibleB=[Manager65CorrectedNative]::IsWindowVisible($hwnd)
        $identity.clientWidthB=$rectB.Right-$rectB.Left; $identity.clientHeightB=$rectB.Bottom-$rectB.Top
    }
    $captureCompletedAtUtc=[DateTimeOffset]::UtcNow
    $snapshotStable=(($first|ConvertTo-Json -Depth 24 -Compress)-eq($second|ConvertTo-Json -Depth 24 -Compress))
    $surfaceStable=([int]$identity.hwndOwnerPidA -eq [int]$identity.hwndOwnerPidB -and [int]$identity.clientWidthA -eq [int]$identity.clientWidthB -and [int]$identity.clientHeightA -eq [int]$identity.clientHeightB)
    $blockers=[System.Collections.Generic.List[string]]::new()
    function Block([string]$Value) { if (-not $blockers.Contains($Value)) { $blockers.Add($Value) } }
    if (([string]$identity.sha256).ToUpperInvariant() -ne $canonical) { Block 'EXECUTABLE_HASH_MISMATCH' }
    if ([string]$identity.moduleBase -ne '0x00400000') { Block 'MODULE_BASE_NOT_0X00400000' }
    if ([int]$identity.pid -le 0 -or [int]$identity.hwndOwnerPidA -ne [int]$identity.pid -or [int]$identity.hwndOwnerPidB -ne [int]$identity.pid) { Block 'OWNED_HWND_PID_MISMATCH' }
    if (-not $surfaceStable) { Block 'OWNED_HWND_SURFACE_TORN' }
    if (-not $snapshotStable) { Block 'TORN_SNAPSHOT' }
    if ($first.uiRoot.pointer -eq '0x00000000') { Block 'UI_ROOT_NULL' }
    if ([int]$first.uiRoot.builderMode -ne 2) { Block 'UI_ROOT_BUILDER_MODE_NOT_2' }
    if ([int]$first.uiRoot.handlerState -ne 1) { Block 'UI_ROOT_HANDLER_STATE_NOT_1' }
    if ($first.uiRoot.registryPointer -eq '0x00000000') { Block 'MANAGER_REGISTRY_NULL' }
    if ($first.manager65.managerPointer -eq '0x00000000') { Block 'MANAGER65_NULL' }
    if ($first.manager65.managerPointer -ne $first.manager65.registrySlotPointer) { Block 'MANAGER65_REGISTRY_SLOT_MISMATCH' }
    if ([int]$first.manager65.context.nodes[0].id -ne 0x65) { Block 'MANAGER65_ID_MISMATCH' }
    if ([int]$first.manager65.context.nodes[0].active -eq 0 -or [int]$first.manager65.inputGate -eq 0) { Block 'MANAGER65_CONTEXT_INACTIVE' }
    if ($first.manager67.managerPointer -eq '0x00000000' -or $first.manager67.managerPointer -ne $first.manager67.registrySlotPointer -or [int]$first.manager67.managerId -ne 0x67) { Block 'MANAGER67_STRUCTURAL_MISMATCH' }
    if ([int]$first.manager65.context.nodes[0].active -ne 0 -and [int]$first.manager65.inputGate -ne 0 -and [int]$first.manager67.active -ne 0 -and [int]$first.manager67.inputGate -ne 0) { Block 'MANAGER65_MANAGER67_SIMULTANEOUSLY_ACTIVE' }
    if ([int]$first.manager65.page -lt 1 -or [int]$first.manager65.page -gt 5) { Block 'MANAGER65_PAGE_OUT_OF_RANGE' }
    if ([int]$first.manager65.actionCount -lt 1 -or [int]$first.manager65.actionCount -gt 24) { Block 'MANAGER65_ACTION_COUNT_OUT_OF_RANGE' }
    if ([int]$first.manager65.cardId -lt 0 -or [int]$first.manager65.cardId -gt 0xFFFF) { Block 'MANAGER65_BOUND_CARD_ID_INVALID' }
    if ($first.manager65.recordOwnerPointer -eq '0x00000000') { Block 'CURRENT_CHARACTER_OWNER_NULL' }
    if ([int]$first.manager65.recordActionCount -ne [int]$first.manager65.actionCount) { Block 'MANAGER65_RECORD_ACTION_COUNT_MISMATCH' }
    if ([int]$first.manager65.selectedIndex -ne -1) { Block 'MANAGER65_SELECTED_INDEX_NOT_RESET' }
    $matches=@($first.manager65.actions|Where-Object { [int]$_.commandId -eq 0x2B })
    if ($matches.Count -eq 0) { Block 'ACTION_0X2B_NOT_FOUND' } elseif ($matches.Count -gt 1) { Block 'ACTION_0X2B_NOT_UNIQUE' }
    $warp=if($matches.Count -eq 1){$matches[0]}else{$null}
    if($null-ne$warp){if(-not$warp.widget.eligible){Block 'ACTION_0X2B_WIDGET_NOT_ELIGIBLE'}}
    $sx=[single]$first.coordinateFrame.scaleX;$sy=[single]$first.coordinateFrame.scaleY
    if([float]::IsNaN($sx)-or[float]::IsInfinity($sx)-or[float]::IsNaN($sy)-or[float]::IsInfinity($sy)-or$sx-le0-or$sy-le0){Block 'INVALID_SCALE'}
    if([int]$first.coordinateFrame.logicalWidth-le0-or[int]$first.coordinateFrame.logicalHeight-le0){Block 'INVALID_LOGICAL_SURFACE'}
    $engineRect=$first.coordinateFrame.engineClientRect
    if($engineRect.left-lt0-or$engineRect.top-lt0-or$engineRect.right-le$engineRect.left-or$engineRect.bottom-le$engineRect.top-or$engineRect.right-gt[int]$identity.clientWidthA-or$engineRect.bottom-gt[int]$identity.clientHeightA){Block 'ENGINE_VIEWPORT_OUTSIDE_OWNED_HWND'}
    if($null-ne$warp-and$sx-gt0-and$sy-gt0){
        function Resolve-Axis([int]$lo,[int]$hi,[single]$scale,[int]$limit){$hits=@();for($pixel=0;$pixel-lt$limit;$pixel++){if([int][Math]::Truncate($pixel*$scale)-ge$lo-and[int][Math]::Truncate($pixel*$scale)-lt$hi){$hits+=$pixel}};if($hits.Count-eq0){return $null};[ordered]@{first=$hits[0];lastExclusive=$hits[-1]+1}}
        $xAxis=Resolve-Axis $warp.widget.logicalRect.left $warp.widget.logicalRect.right $sx ([int]$identity.clientWidthA)
        $yAxis=Resolve-Axis $warp.widget.logicalRect.top $warp.widget.logicalRect.bottom $sy ([int]$identity.clientHeightA)
        if($null-eq$xAxis-or$null-eq$yAxis){Block 'ACTION_0X2B_NO_OWNED_HWND_CLIENT_PIXELS'}
    }
    $output=[ordered]@{
        schemaVersion=3;receiptType='ORIGINAL_CLIENT_MANAGER65_ACTION_0X2B_RAW_CAPTURE';provenance=if($fixtureMode){'SYNTHETIC_FIXTURE'}else{'LIVE_READONLY'}
        oracleRunId=$OracleRunId;externalIdentityReceiptSha256=$ExternalIdentityReceiptSha256
        captureStartedAtUtc=$captureStartedAtUtc.ToString('o');observedAtUtc=$observedAtUtc.ToString('o');captureCompletedAtUtc=$captureCompletedAtUtc.ToString('o')
        process=$identity;rootRoles=[ordered]@{uiRootRole='UI_MODE_AND_REGISTRY_HOST';strategyOwnerRole='INLINE_STRATEGY_MANAGER_OWNER';legacyOwnerModeFieldsRejected=$true}
        snapshotA=$first;snapshotB=$second;snapshotStable=$snapshotStable;windowSurfaceStable=$surfaceStable
        semanticCandidateEligible=$blockers.Count-eq0;blockers=@($blockers);originalRuntimeObserved=$false;independentLiveBinding=$false;livePromotionAllowed=$false
        warpPrelaunchEligible=$false;launchEligible=$false;permitEligible=$false;permitIssued=$false;automaticActivationPoint=$null
        operations=[ordered]@{memoryAccess='READ_ONLY';memoryReadCount=$script:reads;memoryWrites=0;gameInputs=0;automaticInputs=0;retries=0;debuggerAttach=0;debuggerCommands=0;breakpointsInstalled=0;vmLifecycleChanges=0;serverChanges=0;protocolChanges=0;databaseChanges=0;permitIssuance=0}
    }
    $fullOutputPath=[IO.Path]::GetFullPath($OutputPath)
    $directory=Split-Path -Parent $fullOutputPath;if($directory-and-not(Test-Path -LiteralPath $directory)){New-Item -ItemType Directory -Path $directory|Out-Null}
    $tempOutput="$fullOutputPath.tmp-$([guid]::NewGuid().ToString('N'))"
    $output|ConvertTo-Json -Depth 30|Set-Content -LiteralPath $tempOutput -Encoding UTF8
    if (Test-Path -LiteralPath $fullOutputPath) {
        $backupOutput="$fullOutputPath.bak-$([guid]::NewGuid().ToString('N'))"
        [IO.File]::Replace($tempOutput, $fullOutputPath, $backupOutput)
        [IO.File]::Delete($backupOutput)
    }
    else {
        [IO.File]::Move($tempOutput, $fullOutputPath)
    }
}
finally { if($handle-ne[IntPtr]::Zero){[void][Manager65CorrectedNative]::CloseHandle($handle)} }
