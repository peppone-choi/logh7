[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][int]$TargetProcessId,
    [Parameter(Mandatory=$true)][string]$ExpectedStartTimeUtc,
    [Parameter(Mandatory=$true)][string]$ExpectedExecutableSha256,
    [Parameter(Mandatory=$true)][string]$ExpectedWindowHandle,
    [Parameter(Mandatory=$true)][string]$OutputPath
)

$ErrorActionPreference = 'Stop'
$canonical = 'BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16'
$script:reads = 0
if ($ExpectedExecutableSha256.ToUpperInvariant() -ne $canonical) { throw 'Expected hash is not canonical.' }

if (-not ('RootRoleNative' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class RootRoleNative {
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
  [DllImport("kernel32.dll", SetLastError=true)] public static extern IntPtr OpenProcess(uint access, bool inherit, int pid);
  [DllImport("kernel32.dll", SetLastError=true)] public static extern bool ReadProcessMemory(IntPtr process, IntPtr address, byte[] buffer, UIntPtr size, out UIntPtr read);
  [DllImport("kernel32.dll")] public static extern bool CloseHandle(IntPtr handle);
  [DllImport("user32.dll")] public static extern bool IsWindow(IntPtr hwnd);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);
  [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr hwnd, out RECT rect);
}
'@
}

function Hex([uint32]$value) { '0x{0:X8}' -f $value }
function Add-Address([uint32]$base, [uint32]$offset) {
    $value = [uint64]$base + $offset
    if ($value -gt [uint32]::MaxValue) { throw 'Address overflow.' }
    [uint32]$value
}
function Read-Bytes([IntPtr]$handle, [uint32]$address, [int]$length) {
    $buffer = [byte[]]::new($length)
    $read = [UIntPtr]::Zero
    if (-not [RootRoleNative]::ReadProcessMemory($handle, [IntPtr][int64]$address, $buffer, [UIntPtr][uint64]$length, [ref]$read) -or $read.ToUInt64() -ne $length) { throw "ReadProcessMemory failed at $(Hex $address)" }
    $script:reads++
    ,$buffer
}
function U32([IntPtr]$handle, [uint32]$address) { [BitConverter]::ToUInt32((Read-Bytes $handle $address 4),0) }
function I32([IntPtr]$handle, [uint32]$address) { [BitConverter]::ToInt32((Read-Bytes $handle $address 4),0) }
function U8([IntPtr]$handle, [uint32]$address) { (Read-Bytes $handle $address 1)[0] }

$process = Get-Process -Id $TargetProcessId
$process.Refresh()
if ($process.ProcessName -ne 'G7MTClient') { throw 'Target is not G7MTClient.' }
$start = $process.StartTime.ToUniversalTime().ToString('o')
if ($start -ne ([DateTime]::Parse($ExpectedStartTimeUtc).ToUniversalTime().ToString('o'))) { throw 'Start time mismatch.' }
$hash = (Get-FileHash -LiteralPath $process.Path -Algorithm SHA256).Hash
if ($hash -ne $canonical) { throw 'Executable hash mismatch.' }
$moduleBase = [uint32]$process.MainModule.BaseAddress.ToInt64()
if ((Hex $moduleBase) -ne '0x00400000') { throw 'Module base mismatch.' }
$hwnd = [IntPtr][Convert]::ToInt64(($ExpectedWindowHandle -replace '^0x',''),16)
if (-not [RootRoleNative]::IsWindow($hwnd)) { throw 'HWND is not a live window.' }
[uint32]$owner = 0
[void][RootRoleNative]::GetWindowThreadProcessId($hwnd,[ref]$owner)
if ($owner -ne $TargetProcessId) { throw 'HWND owner mismatch.' }
$clientRect = New-Object RootRoleNative+RECT
if (-not [RootRoleNative]::GetClientRect($hwnd,[ref]$clientRect)) { throw 'GetClientRect failed.' }
$clientWidth = $clientRect.Right - $clientRect.Left
$clientHeight = $clientRect.Bottom - $clientRect.Top
if ($clientWidth -le 0 -or $clientHeight -le 0) { throw 'HWND client surface is empty.' }

$handle = [RootRoleNative]::OpenProcess(0x410,$false,$TargetProcessId)
if ($handle -eq [IntPtr]::Zero) { throw 'OpenProcess read-only failed.' }
try {
    $captureStartedAtUtc = [DateTime]::UtcNow.ToString('o')
    function Capture-Once {
        $uiRoot = U32 $handle (Add-Address $moduleBase 0x1E15E2C)
        $registry = U32 $handle (Add-Address $uiRoot 0x0C)
        $strategy = Add-Address $moduleBase 0x89E638
        $manager106 = U32 $handle $strategy
        $controller65 = Add-Address $strategy 0x130
        $manager65 = U32 $handle $controller65
        $controller67 = Add-Address $strategy 0x48C
        $manager67 = U32 $handle $controller67
        [ordered]@{
            uiRoot = [ordered]@{
                pointer = Hex $uiRoot
                builderMode = I32 $handle $uiRoot
                handlerState = I32 $handle (Add-Address $uiRoot 4)
                registryPointer = Hex $registry
            }
            strategyOwner = [ordered]@{
                pointer = Hex $strategy
                firstManagerPointer = Hex $manager106
                firstManagerId = I32 $handle $manager106
                firstManagerRegistryPointer = Hex (U32 $handle (Add-Address $manager106 0x30))
                registrySlot106Pointer = Hex (U32 $handle (Add-Address $registry 0x1AC))
                manager65ControllerPointer = Hex $controller65
                manager65Pointer = Hex $manager65
                manager65Id = I32 $handle $manager65
                manager65Active = [int](U8 $handle (Add-Address $manager65 4))
                manager65InputGate = [int](U8 $handle (Add-Address $manager65 5))
                manager65Page = I32 $handle (Add-Address $controller65 0x34C)
                manager65BoundCardId = I32 $handle (Add-Address $controller65 0x358)
                registrySlot101Pointer = Hex (U32 $handle (Add-Address $registry 0x198))
                manager67ControllerPointer = Hex $controller67
                manager67Pointer = Hex $manager67
                manager67Id = I32 $handle $manager67
                manager67Active = [int](U8 $handle (Add-Address $manager67 4))
                manager67InputGate = [int](U8 $handle (Add-Address $manager67 5))
                manager67Page = I32 $handle (Add-Address $controller67 0x61C)
                registrySlot103Pointer = Hex (U32 $handle (Add-Address $registry 0x1A0))
            }
        }
    }

    $first = Capture-Once
    $second = Capture-Once
    $captureCompletedAtUtc = [DateTime]::UtcNow.ToString('o')
    $stable = (($first | ConvertTo-Json -Depth 10 -Compress) -eq ($second | ConvertTo-Json -Depth 10 -Compress))
    $process.Refresh()
    [uint32]$ownerAfter = 0
    [void][RootRoleNative]::GetWindowThreadProcessId($hwnd,[ref]$ownerAfter)
    $clientRectAfter = New-Object RootRoleNative+RECT
    if (-not [RootRoleNative]::IsWindow($hwnd) -or -not [RootRoleNative]::GetClientRect($hwnd,[ref]$clientRectAfter)) { throw 'Post-capture HWND changed.' }
    $clientWidthAfter = $clientRectAfter.Right - $clientRectAfter.Left
    $clientHeightAfter = $clientRectAfter.Bottom - $clientRectAfter.Top
    $hashAfter = (Get-FileHash -LiteralPath $process.Path -Algorithm SHA256).Hash
    $moduleAfter = [uint32]$process.MainModule.BaseAddress.ToInt64()
    if ($process.StartTime.ToUniversalTime().ToString('o') -ne $start -or $hashAfter -ne $canonical -or $moduleAfter -ne $moduleBase -or $ownerAfter -ne $TargetProcessId -or $clientWidthAfter -ne $clientWidth -or $clientHeightAfter -ne $clientHeight) { throw 'Post-capture identity changed.' }
    $output = [ordered]@{
        schemaVersion = 1
        provenance = 'LIVE_READONLY'
        captureStartedAtUtc = $captureStartedAtUtc
        observedAtUtc = $captureCompletedAtUtc
        captureCompletedAtUtc = $captureCompletedAtUtc
        process = [ordered]@{ pid=$TargetProcessId; startTimeUtc=$start; sha256=$hash; moduleBase=Hex $moduleBase; hwnd=('0x{0:X8}' -f $hwnd.ToInt64()); hwndOwnerPid=$ownerAfter; clientWidth=$clientWidthAfter; clientHeight=$clientHeightAfter }
        uiRoot = $first.uiRoot
        strategyOwner = $first.strategyOwner
        snapshotStable = $stable
        originalRuntimeObserved = $false
        permitIssued = $false
        operations = [ordered]@{ memoryReads='READ_ONLY'; memoryReadCount=$script:reads; writes=0; gameInputs=0; breakpointsInstalled=0 }
    }
    $directory = Split-Path -Parent $OutputPath
    if ($directory -and -not (Test-Path -LiteralPath $directory)) { New-Item -ItemType Directory -Path $directory | Out-Null }
    $output | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $OutputPath -Encoding UTF8
    $output | ConvertTo-Json -Depth 12 -Compress
} finally {
    if ($handle -ne [IntPtr]::Zero) { [void][RootRoleNative]::CloseHandle($handle) }
}
