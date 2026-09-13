[CmdletBinding()]
param(
    [int]$TargetProcessId,
    [string]$ExpectedStartTimeUtc,
    [string]$ExpectedExecutableSha256,
    [string]$ExpectedWindowHandle,
    [string]$FixtureMemoryPath,
    [string]$FixtureIdentityPath,
    [Parameter(Mandatory=$true)][string]$OutputPath
)

$ErrorActionPreference = 'Stop'
$script:memoryReadCount = 0
$canonicalExecutableSha256 = 'BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16'

function Format-Hex32([uint32]$Value) { return ('0x{0:X8}' -f $Value) }

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

    if (-not ('DestinationStageReadOnlyNative' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class DestinationStageReadOnlyNative {
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

    $hwndValue = [Convert]::ToInt64(($ExpectedWindowHandle -replace '^0x',''), 16)
    $hwnd = [IntPtr]$hwndValue
    if (-not [DestinationStageReadOnlyNative]::IsWindow($hwnd)) { throw "Expected HWND $ExpectedWindowHandle is not a window." }
    [uint32]$ownerPid = 0
    [void][DestinationStageReadOnlyNative]::GetWindowThreadProcessId($hwnd, [ref]$ownerPid)
    if ($ownerPid -ne [uint32]$TargetProcessId) { throw "HWND owner PID mismatch: actual=$ownerPid expected=$TargetProcessId" }
    if ($target.MainWindowHandle -ne $hwnd) { throw "Process MainWindowHandle differs from expected HWND $ExpectedWindowHandle." }
    $client = New-Object DestinationStageReadOnlyNative+RECT
    if (-not [DestinationStageReadOnlyNative]::GetClientRect($hwnd, [ref]$client)) { throw 'GetClientRect failed.' }

    $identity = [pscustomobject]@{
        pid = $target.Id
        startTimeUtc = $actualStart
        sha256 = $actualHash
        hwnd = ('0x{0:X8}' -f $hwnd.ToInt64())
        hwndOwnerPid = [int]$ownerPid
        clientWidth = $client.Right - $client.Left
        clientHeight = $client.Bottom - $client.Top
    }

    $processHandle = [DestinationStageReadOnlyNative]::OpenProcess(0x0410, $false, $TargetProcessId)
    if ($processHandle -eq [IntPtr]::Zero) {
        throw "OpenProcess(PROCESS_QUERY_INFORMATION|PROCESS_VM_READ) failed: $([Runtime.InteropServices.Marshal]::GetLastWin32Error())"
    }
}

function Read-FixtureValue([string]$Kind, [uint32]$Address) {
    $key = Format-Hex32 $Address
    $section = $fixture.$Kind
    if ($null -eq $section) { throw "Fixture has no $Kind section." }
    $property = $section.PSObject.Properties[$key]
    if ($null -eq $property) { throw "Missing required fixture read $Kind at $key." }
    $script:memoryReadCount++
    return $property.Value
}

function Read-Bytes([uint32]$Address, [int]$Length) {
    $buffer = [byte[]]::new($Length)
    $read = [UIntPtr]::Zero
    $ok = [DestinationStageReadOnlyNative]::ReadProcessMemory(
        $processHandle, [IntPtr][int64]$Address, $buffer, [UIntPtr][uint64]$Length, [ref]$read)
    if (-not $ok -or $read.ToUInt64() -ne [uint64]$Length) {
        throw "ReadProcessMemory failed at $(Format-Hex32 $Address) length=$Length error=$([Runtime.InteropServices.Marshal]::GetLastWin32Error())"
    }
    $script:memoryReadCount++
    return ,$buffer
}

function Read-I32([uint32]$Address) {
    if ($fixtureMode) { return [int32](Read-FixtureValue 'i32' $Address) }
    return [BitConverter]::ToInt32((Read-Bytes $Address 4), 0)
}
function Read-U8([uint32]$Address) {
    if ($fixtureMode) { return [byte](Read-FixtureValue 'u8' $Address) }
    return (Read-Bytes $Address 1)[0]
}

try {
    $active = Read-U8 0x009D2A30
    $mode = Read-I32 0x009D2A34
    $previousMode = Read-I32 0x009D2A38
    $resultState = Read-I32 0x009D2A3C
    $selectedGridId = Read-I32 0x009D2A40
    $requestedChoice = Read-I32 0x009D2A44
    $gridX = Read-I32 0x009D2A48
    $gridY = Read-I32 0x009D2A4C
    $selectionStatus = Read-I32 0x009D2A50

    $blockers = @()
    if ($active -eq 0) { $blockers += 'SCENE_SELECTION_CONTROLLER_INACTIVE' }
    if ($mode -ne 0x101) { $blockers += 'MODE_NOT_SELECT_GRID_0x101' }
    if ($resultState -ne 0) { $blockers += 'RESULT_STATE_NOT_WAITING' }
    if ($selectedGridId -ne -1) { $blockers += 'SELECTED_GRID_NOT_UNSET' }
    if ($requestedChoice -ne 0) { $blockers += 'REQUESTED_CHOICE_NOT_WAITING' }

    $result = [ordered]@{
        schemaVersion = 1
        observedAtUtc = [DateTime]::UtcNow.ToString('o')
        stage = 'DESTINATION'
        process = [ordered]@{
            pid = [int]$identity.pid
            startTimeUtc = [string]$identity.startTimeUtc
            sha256 = ([string]$identity.sha256).ToUpperInvariant()
            hwnd = [string]$identity.hwnd
            hwndOwnerPid = [int]$identity.hwndOwnerPid
            clientWidth = [int]$identity.clientWidth
            clientHeight = [int]$identity.clientHeight
        }
        controller = [ordered]@{
            base = '0x009D2A30'
            active = [int]$active
            mode = $mode
            modeHex = Format-Hex32 ([uint32]$mode)
            previousMode = $previousMode
            resultState = $resultState
            selectedGridId = $selectedGridId
            requestedChoice = $requestedChoice
            gridX = $gridX
            gridY = $gridY
            selectionStatus = $selectionStatus
        }
        stateEligible = @($blockers).Count -eq 0
        blockers = @($blockers)
        activationRectangle = [ordered]@{
            status = 'UNBOUND'
            reason = 'GRID_WORLD_TO_CLIENT_HIT_REGION_OWNER_NOT_YET_BOUND'
        }
        bindingEligible = $false
        permitIssued = $false
        operations = [ordered]@{
            memoryReads = 'READ_ONLY'
            memoryReadCount = $script:memoryReadCount
            writes = 0
            gameInputs = 0
            breakpointsInstalled = 0
        }
    }

    $parent = Split-Path -Parent $OutputPath
    if ($parent -and -not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent | Out-Null }
    $result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $OutputPath -Encoding UTF8
}
finally {
    if ($processHandle -ne [IntPtr]::Zero) { [void][DestinationStageReadOnlyNative]::CloseHandle($processHandle) }
}
