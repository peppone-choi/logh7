[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][int]$ExpectedPid,
    [Parameter(Mandatory=$true)][string]$Label,
    [Parameter(Mandatory=$true)][string]$ReceiptPath,
    [uint32]$UiRootPtrVa = 0x02215E2C,
    [uint32]$InputOwnerVa = 0x022142A8
)
# READ-ONLY process-memory observation of the client UI managers.
# Walks uiRoot = U32(0x02215E2C) -> registry = U32(uiRoot+0x0C); scans registry slots for manager objects
# (count at +0x3F4 in [1,64]); dumps each manager's widget records (0x34 bytes at +0x4E8, flags +0x08/+0x15).
# No WriteProcessMemory, no input, no allocation in the target. VAs validated identical in item1 and item114.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath $ReceiptPath) { throw 'RECEIPT_EXISTS' }
if (-not ('Rpm' -as [type])) { Add-Type -TypeDefinition @'
using System;using System.Runtime.InteropServices;
public static class Rpm{
 [DllImport("kernel32.dll",SetLastError=true)] public static extern IntPtr OpenProcess(int access,bool inherit,int pid);
 [DllImport("kernel32.dll",SetLastError=true)] public static extern bool ReadProcessMemory(IntPtr h,IntPtr addr,byte[] buf,int size,out int read);
 [DllImport("kernel32.dll")] public static extern bool CloseHandle(IntPtr h);
 public const int PROCESS_VM_READ=0x0010, PROCESS_QUERY_INFORMATION=0x0400;
}
'@ }
$h = [Rpm]::OpenProcess([Rpm]::PROCESS_VM_READ -bor [Rpm]::PROCESS_QUERY_INFORMATION, $false, $ExpectedPid)
if ($h -eq [IntPtr]::Zero) { throw "OPEN_PROCESS_FAILED:$([Runtime.InteropServices.Marshal]::GetLastWin32Error())" }
function RB([uint32]$va, [int]$n) { $buf = New-Object byte[] $n; $read = 0; $ok = [Rpm]::ReadProcessMemory($h, [IntPtr]([int64]$va), $buf, $n, [ref]$read); if ($ok -and $read -eq $n) { return $buf } else { return $null } }
function RU32([uint32]$va) { $b = RB $va 4; if ($null -eq $b) { return $null } return [BitConverter]::ToUInt32($b, 0) }
$result = [ordered]@{ status = 'PENDING'; label = $Label; pid = $ExpectedPid; capturedAtUtc = [datetime]::UtcNow.ToString('o'); uiRootPtrVa = ('0x{0:X8}' -f $UiRootPtrVa); managers = @() }
try {
    $uiRoot = RU32 $UiRootPtrVa; $result.uiRoot = ('0x{0:X8}' -f ([uint32]$uiRoot))
    $registry = if ($uiRoot) { RU32 ([uint32]($uiRoot + 0x0C)) } else { $null }; $result.registry = ('0x{0:X8}' -f ([uint32]$registry))
    $io = RB $InputOwnerVa 0x140
    if ($io) { $result.cursor = [ordered]@{ x = [BitConverter]::ToInt32($io, 0x134); y = [BitConverter]::ToInt32($io, 0x138) } }
    $mgrs = [Collections.Generic.List[object]]::new()
    $seen = @{}
    if ($registry) {
        # Dereference each registry dword as a candidate manager pointer over a wide range,
        # then check the widget-list header (count at +0x3F4). Also probe the uiRoot object itself.
        $bases = @([uint32]$registry)
        if ($uiRoot) { $bases += [uint32]$uiRoot }
        foreach ($regBase in $bases) {
            for ($slot = 0; $slot -lt 0x600; $slot += 4) {
                $mp = RU32 ([uint32]($regBase + $slot))
                if (-not $mp -or $mp -lt 0x400000 -or $mp -gt 0x7FFFFFFF) { continue }
                if ($seen.ContainsKey([uint32]$mp)) { continue }
                $cnt = RU32 ([uint32]($mp + 0x3F4))
                if ($null -eq $cnt -or $cnt -lt 1 -or $cnt -gt 80) { continue }
                # sanity: first record must be readable and its label/handle dword nonzero
                $rec0 = RB ([uint32]($mp + 0x4E8)) 0x34
                if ($null -eq $rec0) { continue }
                $seen[[uint32]$mp] = $true
                $widgets = @()
                for ($i = 0; $i -lt [Math]::Min([int]$cnt, 24); $i++) {
                    $rec = RB ([uint32]($mp + 0x4E8 + $i * 0x34)) 0x34
                    if ($null -eq $rec) { break }
                    $widgets += [ordered]@{ i = $i; flag08 = $rec[0x08]; flag15 = $rec[0x15]; hex = ([BitConverter]::ToString($rec) -replace '-', '') }
                }
                $mgrs.Add([ordered]@{ fromBase = ('0x{0:X8}' -f $regBase); slot = ('0x{0:X}' -f $slot); ptr = ('0x{0:X8}' -f $mp); widgetCount = [int]$cnt; widgets = $widgets })
            }
        }
    }
    $result.managers = @($mgrs)
    $result.status = 'RPM_WIDGET_FLAGS_CAPTURED'
} catch { $result.status = 'RPM_FAILED'; $result.error = $_.Exception.Message }
finally { [void][Rpm]::CloseHandle($h) }
$parent = Split-Path -Parent $ReceiptPath; if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
[IO.File]::WriteAllText($ReceiptPath, (($result | ConvertTo-Json -Depth 6) -replace "`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
if ($result.status -ne 'RPM_WIDGET_FLAGS_CAPTURED') { exit 1 }
exit 0
