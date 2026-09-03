[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][int]$ExpectedPid,
    [Parameter(Mandatory=$true)][string]$Label,
    [Parameter(Mandatory=$true)][string]$ReceiptPath
)
# READ-ONLY probe of the +0x05 input-enable bytes on the four panels that FUN_0053c090 arms for the
# create/faction-screen context global 0x00CB0038. Reads pointers at FIXED absolute addresses and each
# target's +0x05 (the byte the input gate FUN_005024A0 returns). OpenProcess VM_READ + ReadProcessMemory
# only; no writes, no input, no debugger (the hardware-BP attach crashes this client). All addresses are
# byte-verified from FUN_0053c090 disassembly of the unmodified item1 client.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath $ReceiptPath) { throw 'RECEIPT_EXISTS' }
if (-not ('Rpm5' -as [type])) { Add-Type -TypeDefinition @'
using System;using System.Runtime.InteropServices;
public static class Rpm5{
 [DllImport("kernel32.dll",SetLastError=true)] public static extern IntPtr OpenProcess(int a,bool i,int pid);
 [DllImport("kernel32.dll",SetLastError=true)] public static extern bool ReadProcessMemory(IntPtr h,IntPtr addr,byte[] buf,int size,out int read);
 [DllImport("kernel32.dll")] public static extern bool CloseHandle(IntPtr h);
 public const int VM_READ=0x0010, QUERY=0x0400;
}
'@ }
$h = [Rpm5]::OpenProcess([Rpm5]::VM_READ -bor [Rpm5]::QUERY, $false, $ExpectedPid)
if ($h -eq [IntPtr]::Zero) { throw "OPEN_PROCESS_FAILED:$([Runtime.InteropServices.Marshal]::GetLastWin32Error())" }
function RB([uint32]$va,[int]$n){ $b=New-Object byte[] $n; $r=0; $ok=[Rpm5]::ReadProcessMemory($h,[IntPtr]([int64]$va),$b,$n,[ref]$r); if($ok -and $r -eq $n){return $b} return $null }
function RU32([uint32]$va){ $b=RB $va 4; if($null -eq $b){return $null} return [BitConverter]::ToUInt32($b,0) }
function RU8([uint32]$va){ $b=RB $va 1; if($null -eq $b){return $null} return [int]$b[0] }

$ctxThis = [uint32]0x00CB0038
$ptrAddrs = @([uint32]0x00CB005C, [uint32]0x01FB8264, [uint32]0x01FB8B64, [uint32]0x01FB9464)  # this+0x24, +0x14E822C, +0x14E8B2C, +0x14E942C
$result = [ordered]@{ status='PENDING'; label=$Label; pid=$ExpectedPid; capturedAtUtc=[datetime]::UtcNow.ToString('o'); ctxThis=('0x{0:X8}' -f $ctxThis); panels=@() }
try {
    # sanity: read a few dwords at the context global header (informational)
    $hdr = RB $ctxThis 0x28; $result.ctxHeaderHex = if ($hdr){ ([BitConverter]::ToString($hdr) -replace '-','') } else { $null }
    $panels = [Collections.Generic.List[object]]::new()
    $idx = 0
    foreach ($pa in $ptrAddrs) {
        $p = RU32 $pa
        $enable = $null; $valid = $false
        if ($null -ne $p -and $p -ge 0x400000 -and $p -le 0x7FFFFFFF) { $valid = $true; $enable = RU8 ([uint32]($p + 0x05)) }
        $panels.Add([ordered]@{ i=$idx; ptrAddr=('0x{0:X8}' -f $pa); ptr=('0x{0:X8}' -f ([uint32]([long]$p))); ptrValid=$valid; enable05=$enable })
        $idx++
    }
    $result.panels = @($panels)
    $result.status = 'ARM05_PROBE_CAPTURED'
} catch { $result.status='ARM05_PROBE_FAILED'; $result.error=$_.Exception.Message }
finally { [void][Rpm5]::CloseHandle($h) }
$parent = Split-Path -Parent $ReceiptPath; if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
[IO.File]::WriteAllText($ReceiptPath, (($result | ConvertTo-Json -Depth 6) -replace "`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
if ($result.status -ne 'ARM05_PROBE_CAPTURED') { exit 1 }
exit 0
