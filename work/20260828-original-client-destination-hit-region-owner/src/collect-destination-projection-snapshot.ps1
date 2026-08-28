[CmdletBinding()]
param(
    [int]$TargetProcessId,
    [string]$ExpectedStartTimeUtc,
    [string]$ExpectedExecutableSha256,
    [string]$ExpectedWindowHandle,
    [string]$FixtureMemoryPath,
    [string]$FixtureIdentityPath,
    [Parameter(Mandatory=$true)][int]$TargetGridX,
    [Parameter(Mandatory=$true)][int]$TargetGridY,
    [Parameter(Mandatory=$true)][string]$OutputPath
)

$ErrorActionPreference = 'Stop'
$canonicalExecutableSha256 = 'BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16'
$script:memoryReadCount = 0
$script:capturePass = 1

if ($TargetGridX -lt 0 -or $TargetGridX -ge 100 -or $TargetGridY -lt 0 -or $TargetGridY -ge 50) {
    throw 'Target grid is outside original bounds x=0..99 y=0..49.'
}

$fixtureMode = -not [string]::IsNullOrWhiteSpace($FixtureMemoryPath)
if ($fixtureMode -ne (-not [string]::IsNullOrWhiteSpace($FixtureIdentityPath))) {
    throw 'FixtureMemoryPath and FixtureIdentityPath must be supplied together.'
}

$processHandle = [IntPtr]::Zero
if ($fixtureMode) {
    $fixture = Get-Content -LiteralPath $FixtureMemoryPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $identity = Get-Content -LiteralPath $FixtureIdentityPath -Raw -Encoding UTF8 | ConvertFrom-Json -DateKind String
}
else {
    if ($TargetProcessId -le 0 -or [string]::IsNullOrWhiteSpace($ExpectedStartTimeUtc) -or
        [string]::IsNullOrWhiteSpace($ExpectedExecutableSha256) -or [string]::IsNullOrWhiteSpace($ExpectedWindowHandle)) {
        throw 'Live mode requires TargetProcessId, ExpectedStartTimeUtc, ExpectedExecutableSha256, and ExpectedWindowHandle.'
    }
    if ($ExpectedExecutableSha256.ToUpperInvariant() -ne $canonicalExecutableSha256) {
        throw "Expected executable SHA-256 is not the canonical G7MTClient target: expected=$canonicalExecutableSha256"
    }

    if (-not ('DestinationProjectionReadOnlyNative' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class DestinationProjectionReadOnlyNative {
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
    [DllImport("kernel32.dll", SetLastError=true)] public static extern IntPtr OpenProcess(uint access, bool inheritHandle, int processId);
    [DllImport("kernel32.dll", SetLastError=true)] [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool ReadProcessMemory(IntPtr process, IntPtr address, byte[] buffer, UIntPtr size, out UIntPtr bytesRead);
    [DllImport("kernel32.dll")] [return: MarshalAs(UnmanagedType.Bool)] public static extern bool CloseHandle(IntPtr handle);
    [DllImport("user32.dll")] [return: MarshalAs(UnmanagedType.Bool)] public static extern bool IsWindow(IntPtr hwnd);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint processId);
    [DllImport("user32.dll")] [return: MarshalAs(UnmanagedType.Bool)] public static extern bool GetClientRect(IntPtr hwnd, out RECT rect);
}
'@
    }

    $target = Get-Process -Id $TargetProcessId -ErrorAction Stop
    $target.Refresh()
    if ($target.ProcessName -ne 'G7MTClient') { throw "PID $TargetProcessId is $($target.ProcessName), not G7MTClient." }
    $actualStart = $target.StartTime.ToUniversalTime().ToString('o')
    $expectedStart = [DateTime]::Parse($ExpectedStartTimeUtc).ToUniversalTime().ToString('o')
    if ($actualStart -ne $expectedStart) { throw "Process start time mismatch: actual=$actualStart expected=$expectedStart" }
    $actualHash = (Get-FileHash -LiteralPath $target.Path -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($actualHash -ne $canonicalExecutableSha256) { throw "Executable SHA-256 mismatch: actual=$actualHash expected=$canonicalExecutableSha256" }
    $moduleBase = $target.MainModule.BaseAddress.ToInt64()
    if ($moduleBase -ne 0x00400000) { throw ('Module base mismatch: actual=0x{0:X8} expected=0x00400000' -f $moduleBase) }

    $hwndValue = [Convert]::ToInt64(($ExpectedWindowHandle -replace '^0x',''), 16)
    $hwnd = [IntPtr]$hwndValue
    if (-not [DestinationProjectionReadOnlyNative]::IsWindow($hwnd)) { throw "Expected HWND $ExpectedWindowHandle is not a window." }
    [uint32]$ownerPid = 0
    [void][DestinationProjectionReadOnlyNative]::GetWindowThreadProcessId($hwnd, [ref]$ownerPid)
    if ($ownerPid -ne [uint32]$TargetProcessId) { throw "HWND owner PID mismatch: actual=$ownerPid expected=$TargetProcessId" }
    if ($target.MainWindowHandle -ne $hwnd) { throw "Process MainWindowHandle differs from expected HWND $ExpectedWindowHandle." }
    $client = New-Object DestinationProjectionReadOnlyNative+RECT
    if (-not [DestinationProjectionReadOnlyNative]::GetClientRect($hwnd, [ref]$client)) { throw 'GetClientRect failed.' }
    $identity = [pscustomobject]@{
        pid=$target.Id; startTimeUtc=$actualStart; sha256=$actualHash
        hwnd=('0x{0:X8}' -f $hwnd.ToInt64()); hwndOwnerPid=[int]$ownerPid
        moduleBase=('0x{0:X8}' -f $moduleBase)
        clientWidth=$client.Right-$client.Left; clientHeight=$client.Bottom-$client.Top
    }
    $processHandle = [DestinationProjectionReadOnlyNative]::OpenProcess(0x0410, $false, $TargetProcessId)
    if ($processHandle -eq [IntPtr]::Zero) { throw 'OpenProcess(PROCESS_QUERY_INFORMATION|PROCESS_VM_READ) failed.' }
}

function Read-LiveBytes([uint32]$Address, [int]$Count) {
    $buffer = [byte[]]::new($Count); [UIntPtr]$read = [UIntPtr]::Zero
    if (-not [DestinationProjectionReadOnlyNative]::ReadProcessMemory($processHandle,[IntPtr][int64]$Address,$buffer,[UIntPtr]$Count,[ref]$read) -or $read.ToUInt64() -ne [uint64]$Count) {
        throw ('ReadProcessMemory failed at 0x{0:X8} count={1}' -f $Address,$Count)
    }
    return $buffer
}
function Read-I32([uint32]$Address) {
    $script:memoryReadCount++
    if ($fixtureMode) {
        $key=('0x{0:X8}' -f $Address)
        $property=$null
        if($script:capturePass -eq 2 -and $null -ne $fixture.secondRead -and $null -ne $fixture.secondRead.i32){$property=$fixture.secondRead.i32.PSObject.Properties[$key]}
        if($null -eq $property){$property=$fixture.i32.PSObject.Properties[$key]}
        if ($null -eq $property) { throw "Missing required fixture i32 read $key" }
        return [int]$property.Value
    }
    return [BitConverter]::ToInt32((Read-LiveBytes $Address 4),0)
}
function Read-U32([uint32]$Address) {
    $script:memoryReadCount++
    if ($fixtureMode) {
        $key=('0x{0:X8}' -f $Address)
        $property=$null
        if($script:capturePass -eq 2 -and $null -ne $fixture.secondRead -and $null -ne $fixture.secondRead.u32){$property=$fixture.secondRead.u32.PSObject.Properties[$key]}
        if($null -eq $property){$property=$fixture.u32.PSObject.Properties[$key]}
        if($null -eq $property){throw "Missing required fixture u32 read $key"}
        return [uint32]$property.Value
    }
    return [BitConverter]::ToUInt32((Read-LiveBytes $Address 4),0)
}
function Read-U8([uint32]$Address) {
    $script:memoryReadCount++
    if ($fixtureMode) {
        $key=('0x{0:X8}' -f $Address)
        $property=$null
        if($script:capturePass -eq 2 -and $null -ne $fixture.secondRead -and $null -ne $fixture.secondRead.u8){$property=$fixture.secondRead.u8.PSObject.Properties[$key]}
        if($null -eq $property){$property=$fixture.u8.PSObject.Properties[$key]}
        if($null -eq $property){throw "Missing required fixture u8 read $key"}
        return [byte]$property.Value
    }
    return (Read-LiveBytes $Address 1)[0]
}
function Read-Viewport([uint32]$Address) {
    $script:memoryReadCount += 6
    if ($fixtureMode) {
        $source=$fixture.viewport
        if($script:capturePass -eq 2 -and $null -ne $fixture.secondRead -and $null -ne $fixture.secondRead.viewport){$source=$fixture.secondRead.viewport}
        if ($source.address -ne ('0x{0:X8}' -f $Address)) { throw "Missing required fixture viewport 0x$('{0:X8}' -f $Address)" }
        return [ordered]@{ x=[int]$source.x; y=[int]$source.y; width=[int]$source.width; height=[int]$source.height; minDepth=[double]$source.minDepth; maxDepth=[double]$source.maxDepth }
    }
    $bytes=Read-LiveBytes $Address 24
    return [ordered]@{ x=[BitConverter]::ToUInt32($bytes,0); y=[BitConverter]::ToUInt32($bytes,4); width=[BitConverter]::ToUInt32($bytes,8); height=[BitConverter]::ToUInt32($bytes,12); minDepth=[BitConverter]::ToSingle($bytes,16); maxDepth=[BitConverter]::ToSingle($bytes,20) }
}
function Read-Matrix([uint32]$Address) {
    $script:memoryReadCount += 16
    $key=('0x{0:X8}' -f $Address)
    if ($fixtureMode) {
        $property=$null
        if($script:capturePass -eq 2 -and $null -ne $fixture.secondRead -and $null -ne $fixture.secondRead.matrices){$property=$fixture.secondRead.matrices.PSObject.Properties[$key]}
        if($null -eq $property){$property=$fixture.matrices.PSObject.Properties[$key]}
        if ($null -eq $property) { throw "Missing required fixture matrix $key" }
        if (@($property.Value).Count -ne 16) { throw "Fixture matrix $key does not contain 16 floats" }
        return @($property.Value | ForEach-Object { [double]$_ })
    }
    $bytes=Read-LiveBytes $Address 64
    return @(0..15 | ForEach-Object { [double][BitConverter]::ToSingle($bytes,$_ * 4) })
}

try {
    function Capture-ProjectionSurface([int]$Pass) {
        $script:capturePass=$Pass
        $engineRoot=Read-U32 0x007C1B4C
        if($engineRoot -eq 0){throw 'Engine root pointer 0x007C1B4C is null'}
        $engineRectAddress=[uint32]($engineRoot+0x2A5FC)
        $worldDataBase=Read-U32 0x007CCFFC
        if($worldDataBase -eq 0){throw 'World data base pointer 0x007CCFFC is null'}
        $characterContext=Read-U32 0x007CD04C
        if($characterContext -eq 0){throw 'Character context pointer 0x007CD04C is null'}
        $linearTarget=[uint32]($TargetGridX+$TargetGridY*100)
        $cellIndex=Read-U8 ([uint32]($worldDataBase+0x2C03CC+$linearTarget))
        return [ordered]@{
            mode=Read-I32 0x009D2A34; resultState=Read-I32 0x009D2A3C
            selectedGrid=Read-I32 0x009D2A40; requestedChoice=Read-I32 0x009D2A44
            filter=Read-I32 0x009D2A50
            hoverX=Read-I32 0x009D2A54; hoverY=Read-I32 0x009D2A58
            currentCellId=Read-U32 ([uint32]($characterContext+0x11178))
            targetCellIndex=[int]$cellIndex
            targetCellType=[int](Read-U8 ([uint32]($worldDataBase+0x2C1755+[uint32]$cellIndex*3+1)))
            targetRenderActive=[int](Read-U8 ([uint32](0x00983098+$linearTarget*0x40+0x3C)))
            engineViewportRect=[ordered]@{
                left=Read-I32 $engineRectAddress
                top=Read-I32 ([uint32]($engineRectAddress+4))
                right=Read-I32 ([uint32]($engineRectAddress+8))
                bottom=Read-I32 ([uint32]($engineRectAddress+12))
            }
            viewport=Read-Viewport 0x009D1428
            world=Read-Matrix 0x009D13E8
            view=Read-Matrix 0x009D1368
            projection=Read-Matrix 0x009D13A8
        }
    }
    $first=Capture-ProjectionSurface 1
    $second=Capture-ProjectionSurface 2
    $mode=$second.mode; $resultState=$second.resultState
    $selectedGrid=$second.selectedGrid; $requestedChoice=$second.requestedChoice
    $hoverX=$second.hoverX; $hoverY=$second.hoverY
    $engineViewportRect=$second.engineViewportRect
    $viewport=$second.viewport; $world=$second.world; $view=$second.view; $projection=$second.projection

    $blockers=[Collections.Generic.List[string]]::new()
    $firstSemantic=$first|ConvertTo-Json -Compress -Depth 8
    $secondSemantic=$second|ConvertTo-Json -Compress -Depth 8
    if($firstSemantic -cne $secondSemantic){$blockers.Add('PROJECTION_SURFACE_CHANGED_DURING_CAPTURE')}
    if ($identity.sha256.ToUpperInvariant() -ne $canonicalExecutableSha256) { $blockers.Add('EXECUTABLE_HASH_NOT_CANONICAL') }
    if ($identity.moduleBase -ne '0x00400000') { $blockers.Add('MODULE_BASE_NOT_0x00400000') }
    if ([int]$identity.pid -ne [int]$identity.hwndOwnerPid) { $blockers.Add('HWND_OWNER_PID_MISMATCH') }
    if ($mode -ne 0x101) { $blockers.Add('MODE_NOT_SELECT_GRID_0x101') }
    if ($resultState -ne 0) { $blockers.Add('RESULT_STATE_NOT_WAITING') }
    if ($selectedGrid -ne -1) { $blockers.Add('SELECTED_GRID_NOT_UNSET') }
    if ($requestedChoice -ne 0) { $blockers.Add('REQUESTED_CHOICE_NOT_ZERO') }
    if ([int]$viewport.width -ne [int]$identity.clientWidth -or [int]$viewport.height -ne [int]$identity.clientHeight) { $blockers.Add('VIEWPORT_CLIENT_SIZE_MISMATCH') }
    if ([int]$viewport.x -ne 0 -or [int]$viewport.y -ne 0) { $blockers.Add('VIEWPORT_ORIGIN_NOT_CLIENT_ZERO') }
    if([int]$engineViewportRect.left -ne 0 -or [int]$engineViewportRect.top -ne 0){$blockers.Add('ENGINE_VIEWPORT_ORIGIN_NOT_CLIENT_ZERO')}
    if(([int]$engineViewportRect.right-[int]$engineViewportRect.left) -ne [int]$identity.clientWidth -or
       ([int]$engineViewportRect.bottom-[int]$engineViewportRect.top) -ne [int]$identity.clientHeight){$blockers.Add('ENGINE_VIEWPORT_CLIENT_SIZE_MISMATCH')}

    $validityReasons=[Collections.Generic.List[string]]::new()
    if($second.targetCellType -notin @(1,3)){$validityReasons.Add('CELL_TYPE_NOT_1_OR_3')}
    if($requestedChoice -ne 0 -and $second.targetRenderActive -eq 0){$validityReasons.Add('TARGET_RENDER_RECORD_NOT_ACTIVE')}
    $currentX=[int]([uint32]$second.currentCellId % 100)
    $currentY=[int][Math]::Floor([double][uint32]$second.currentCellId / 100.0)
    $distance=[Math]::Sqrt([Math]::Pow($TargetGridX-$currentX,2)+[Math]::Pow($TargetGridY-$currentY,2))
    if($second.filter -ge 0){
        if($requestedChoice -eq 0 -and $TargetGridX -eq $currentX -and $TargetGridY -eq $currentY){$validityReasons.Add('CURRENT_GRID_DISALLOWED_FOR_CHOICE_ZERO')}
        if($distance -gt ([double]$second.filter+0.05)){$validityReasons.Add('TARGET_DISTANCE_EXCEEDS_FILTER_PLUS_0_05')}
    }
    $targetValid=$validityReasons.Count -eq 0
    if(-not $targetValid){$blockers.Add('TARGET_GRID_FUN_004D6310_INVALID')}

    $result=[ordered]@{
        schemaVersion=1; sourceMode=if($fixtureMode){'OFFLINE_FIXTURE'}else{'LIVE_READONLY'}
        identity=$identity
        target=[ordered]@{gridX=$TargetGridX;gridY=$TargetGridY}
        stageEligible=$blockers.Count -eq 0; blockers=@($blockers)
        controller=[ordered]@{mode=$mode;resultState=$resultState;selectedGridId=$selectedGrid;requestedChoice=$requestedChoice}
        observedHover=[ordered]@{gridX=$hoverX;gridY=$hoverY}
        targetValidity=[ordered]@{
            valid=$targetValid; reasons=@($validityReasons); cellIndex=$second.targetCellIndex
            cellType=$second.targetCellType; renderRecordActive=[bool]$second.targetRenderActive
            filter=$second.filter; currentCellId=[uint32]$second.currentCellId
            currentGridX=$currentX; currentGridY=$currentY; distance=[Math]::Round($distance,6)
        }
        engineViewportRect=$engineViewportRect
        viewport=$viewport; world=$world; view=$view; projection=$projection
        provenance=[ordered]@{ originalRuntimeObserved=-not $fixtureMode; playerVisible=$false }
        operations=[ordered]@{memoryReadCount=$script:memoryReadCount;writes=0;gameInputs=0;liveOperations=if($fixtureMode){0}else{1}}
        permitIssued=$false
    }
    $json=$result|ConvertTo-Json -Depth 10
    $canonical=($json -replace "`r?`n","`n")+"`n"
    $parent=Split-Path -Parent $OutputPath
    if(-not [string]::IsNullOrWhiteSpace($parent)){New-Item -ItemType Directory -Path $parent -Force|Out-Null}
    [IO.File]::WriteAllText($OutputPath,$canonical,[Text.UTF8Encoding]::new($false))
    $json
}
finally {
    if(-not $fixtureMode -and $processHandle -ne [IntPtr]::Zero){[void][DestinationProjectionReadOnlyNative]::CloseHandle($processHandle)}
}
