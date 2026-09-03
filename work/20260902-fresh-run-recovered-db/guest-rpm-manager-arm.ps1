[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$RunId,
    [Parameter(Mandatory=$true)][int]$ExpectedPid,
    [Parameter(Mandatory=$true)][string]$ReceiptPath,
    [string]$Label = 'unlabeled',
    [string]$PrepFileName = 'fresh-run-prep.json',
    [int]$MaxRegions = 4096,
    [string]$DumpBlockStartHex = '',
    [int]$DumpBlockSize = 0,
    [string]$DumpPath = ''
)
# READ-ONLY process-memory probe (OpenProcess PROCESS_VM_READ|PROCESS_QUERY_INFORMATION only; no writes, no
# threads, no input). Enumerates committed private RW regions of the original client and finds candidate
# KWSWND managers: dword tag 0x63 at +0x00 (the FUN_005024B0 guard), reporting the input-arm byte at +0x05 and
# the visibility byte at +0x04 for each candidate. Purpose: compare arm states between a responsive lobby and a
# wedged lobby panel without a debugger. Values only; no secrets.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath $ReceiptPath) { throw 'RECEIPT_EXISTS' }
$root = "C:\Users\logh7-oracle\AppData\Local\Temp\logh7-l1\$RunId"
$prep = Get-Content -LiteralPath (Join-Path $root $PrepFileName) -Raw -Encoding UTF8 | ConvertFrom-Json
if ([int]$prep.client.pid -ne $ExpectedPid) { throw 'PREP_IDENTITY_MISMATCH' }
$cim = Get-CimInstance Win32_Process -Filter "ProcessId=$ExpectedPid"
if ($null -eq $cim -or $cim.ExecutablePath -cne [string]$prep.client.path) { throw 'CLIENT_IDENTITY_MISMATCH' }
if (-not ('RpmArmNative' -as [type])) { Add-Type -TypeDefinition @'
using System;using System.Runtime.InteropServices;
public static class RpmArmNative{
 [StructLayout(LayoutKind.Sequential)]public struct MBI{public IntPtr BaseAddress;public IntPtr AllocationBase;public uint AllocationProtect;public IntPtr RegionSize;public uint State;public uint Protect;public uint Type;}
 [DllImport("kernel32.dll",SetLastError=true)]public static extern IntPtr OpenProcess(uint access,bool inherit,int pid);
 [DllImport("kernel32.dll",SetLastError=true)]public static extern bool ReadProcessMemory(IntPtr h,IntPtr addr,byte[] buf,int size,out int read);
 [DllImport("kernel32.dll")]public static extern int VirtualQueryEx(IntPtr h,IntPtr addr,out MBI mbi,int len);
 [DllImport("kernel32.dll")]public static extern bool CloseHandle(IntPtr h);}
'@ }
$h = [RpmArmNative]::OpenProcess(0x0010 -bor 0x0400, $false, $ExpectedPid)   # PROCESS_VM_READ | PROCESS_QUERY_INFORMATION
if ($h -eq [IntPtr]::Zero) { throw "OPEN_PROCESS_FAILED:$([Runtime.InteropServices.Marshal]::GetLastWin32Error())" }
$cands = [Collections.Generic.List[object]]::new(); $regions = 0; $bytesScanned = [long]0
try {
    $addr = [IntPtr]::Zero; $mbi = New-Object RpmArmNative+MBI
    while ($regions -lt $MaxRegions -and [RpmArmNative]::VirtualQueryEx($h, $addr, [ref]$mbi, [Runtime.InteropServices.Marshal]::SizeOf($mbi)) -ne 0) {
        $size = $mbi.RegionSize.ToInt64(); $base = $mbi.BaseAddress.ToInt64()
        if ($base -ge 0x7FFF0000) { break }
        $next = $base + $size
        # MEM_COMMIT(0x1000); MEM_PRIVATE(0x20000) heap OR MEM_IMAGE(0x1000000) writable data/bss (the client's UI
        # globals such as uiRoot 0x02215E2C live in the image's data section); PAGE_READWRITE(0x04) or
        # PAGE_WRITECOPY(0x08) or PAGE_EXECUTE_READWRITE(0x40); skip huge regions
        $writable = (($mbi.Protect -band 0x04) -ne 0) -or (($mbi.Protect -band 0x08) -ne 0) -or (($mbi.Protect -band 0x40) -ne 0)
        if ($mbi.State -eq 0x1000 -and ($mbi.Type -eq 0x20000 -or $mbi.Type -eq 0x1000000) -and $writable -and $size -le 64MB) {
            $regions++
            $buf = New-Object byte[] $size; $read = 0
            if ([RpmArmNative]::ReadProcessMemory($h, $mbi.BaseAddress, $buf, $size, [ref]$read) -and $read -gt 16) {
                $bytesScanned += $read
                for ($i = 0; $i -le $read - 0x500; $i += 4) {
                    if ($buf[$i] -eq 0x63 -and $buf[$i+1] -eq 0 -and $buf[$i+2] -eq 0 -and $buf[$i+3] -eq 0) {
                        $vis = $buf[$i+4]; $arm = $buf[$i+5]
                        $wcount = [BitConverter]::ToUInt32($buf, $i+0x3F4); $wlist = [BitConverter]::ToUInt32($buf, $i+0x470)
                        # manager fingerprint (dispatcher FUN_005015F0 layout): small widget count at +0x3F4, heap pointer at +0x470
                        $plausible = ($wcount -ge 1 -and $wcount -le 256 -and $wlist -ge 0x00400000 -and $wlist -lt 0x7FFF0000)
                        if ($plausible -or (($vis -le 1) -and ($arm -le 1))) {
                            $cands.Add([ordered]@{ address = ('0x{0:X8}' -f ($base + $i)); visible = [int]$vis; arm = [int]$arm; b6 = [int]$buf[$i+6]; b7 = [int]$buf[$i+7]; dword8 = ('0x{0:X8}' -f [BitConverter]::ToUInt32($buf, $i+8)); dword12 = ('0x{0:X8}' -f [BitConverter]::ToUInt32($buf, $i+12)); widgetCount3F4 = [int64]$wcount; widgetList470 = ('0x{0:X8}' -f $wlist); plausibleManager = $plausible })
                        }
                    }
                }
            }
        }
        if ($next -le $base) { break }
        $addr = [IntPtr]$next
    }
    # optional raw read-only block dump (e.g. the lobby UI global block) for host-side state diffing
    $dumpInfo = $null
    if ($DumpBlockStartHex -and $DumpBlockSize -gt 0 -and $DumpPath) {
        $start = [Convert]::ToInt64($DumpBlockStartHex, 16); $dbuf = New-Object byte[] $DumpBlockSize; $dread = 0
        $ok = [RpmArmNative]::ReadProcessMemory($h, [IntPtr]$start, $dbuf, $DumpBlockSize, [ref]$dread)
        if ($ok -and $dread -gt 0) { [IO.File]::WriteAllBytes($DumpPath, $dbuf[0..($dread-1)]); $dumpInfo = [ordered]@{ start = $DumpBlockStartHex; bytes = $dread; path = $DumpPath; sha256 = (Get-FileHash -LiteralPath $DumpPath -Algorithm SHA256).Hash } }
        else { $dumpInfo = [ordered]@{ start = $DumpBlockStartHex; bytes = 0; error = [Runtime.InteropServices.Marshal]::GetLastWin32Error() } }
    }
} finally { [void][RpmArmNative]::CloseHandle($h) }
$armed = @($cands | Where-Object { $_.arm -eq 1 }).Count; $disarmed = @($cands | Where-Object { $_.arm -eq 0 }).Count
$receipt = [ordered]@{ status = 'RPM_ARM_PROBED'; label = $Label; runId = $RunId; probedAtUtc = [datetime]::UtcNow.ToString('o'); client = [ordered]@{ pid = $ExpectedPid }; access = 'PROCESS_VM_READ|PROCESS_QUERY_INFORMATION (read-only)'; regionsScanned = $regions; bytesScanned = $bytesScanned; candidateCount = $cands.Count; armedCount = $armed; disarmedCount = $disarmed; candidates = @($cands | Select-Object -First 400); blockDump = $dumpInfo; operations = [ordered]@{ writes = 0; gameInputs = 0 } }
[IO.File]::WriteAllText($ReceiptPath, (($receipt | ConvertTo-Json -Depth 6) -replace "`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
exit 0
