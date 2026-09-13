[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][int]$ExpectedPid,
    [Parameter(Mandatory=$true)][string]$Label,
    [Parameter(Mandatory=$true)][string]$ReceiptPath
)
# READ-ONLY probe of the faction-screen context state fields that FUN_00539ce0 gates on. factionCtx is the
# FIXED global 0x00CB0038 (= topCtx 0x00C9E638 + 0x11A00, byte-verified from FUN_004fd100/FUN_00539ce0).
# Reads: gate *(ctx+0xA08) (must be 3), *(ctx+0x14E0), *(ctx+0x24) then that object's +4, and a header dump.
# OpenProcess VM_READ + ReadProcessMemory only; no writes, no input, no debugger (crash-free).
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath $ReceiptPath) { throw 'RECEIPT_EXISTS' }
if (-not ('RpmF' -as [type])) { Add-Type -TypeDefinition @'
using System;using System.Runtime.InteropServices;
public static class RpmF{
 [DllImport("kernel32.dll",SetLastError=true)] public static extern IntPtr OpenProcess(int a,bool i,int pid);
 [DllImport("kernel32.dll",SetLastError=true)] public static extern bool ReadProcessMemory(IntPtr h,IntPtr addr,byte[] buf,int size,out int read);
 [DllImport("kernel32.dll")] public static extern bool CloseHandle(IntPtr h);
 public const int VM_READ=0x0010, QUERY=0x0400;
}
'@ }
$h = [RpmF]::OpenProcess([RpmF]::VM_READ -bor [RpmF]::QUERY, $false, $ExpectedPid)
if ($h -eq [IntPtr]::Zero) { throw "OPEN_PROCESS_FAILED:$([Runtime.InteropServices.Marshal]::GetLastWin32Error())" }
function RB([uint32]$va,[int]$n){ $b=New-Object byte[] $n; $r=0; $ok=[RpmF]::ReadProcessMemory($h,[IntPtr]([int64]$va),$b,$n,[ref]$r); if($ok -and $r -eq $n){return $b} return $null }
function RU32([uint32]$va){ $b=RB $va 4; if($null -eq $b){return $null} return [BitConverter]::ToUInt32($b,0) }
function RU8([uint32]$va){ $b=RB $va 1; if($null -eq $b){return $null} return [int]$b[0] }

$topCtx=[uint32]0x00C9E638; $ctx=[uint32]0x00CB0038
$result=[ordered]@{ status='PENDING'; label=$Label; pid=$ExpectedPid; capturedAtUtc=[datetime]::UtcNow.ToString('o'); topCtx=('0x{0:X8}' -f $topCtx); factionCtx=('0x{0:X8}' -f $ctx) }
try {
    $result.gate_a08 = RU32 ([uint32]($ctx + 0xA08))       # must be 3 for input processing
    $result.state_14e0 = RU32 ([uint32]($ctx + 0x14E0))
    $p24 = RU32 ([uint32]($ctx + 0x24)); $result.ptr24 = ('0x{0:X8}' -f ([uint32]([long]$p24)))
    $result.ptr24_plus4 = if ($null -ne $p24 -and $p24 -ge 0x400000 -and $p24 -le 0x7FFFFFFF) { RU8 ([uint32]($p24 + 4)) } else { $null }
    $hdr = RB $ctx 0x40; $result.ctxHeaderHex = if ($hdr){ ([BitConverter]::ToString($hdr) -replace '-','') } else { $null }
    $a00 = RB ([uint32]($ctx + 0xA00)) 0x20; $result.ctxA00Hex = if ($a00){ ([BitConverter]::ToString($a00) -replace '-','') } else { $null }
    $result.status='FACTIONCTX_PROBE_CAPTURED'
} catch { $result.status='FACTIONCTX_PROBE_FAILED'; $result.error=$_.Exception.Message }
finally { [void][RpmF]::CloseHandle($h) }
$parent = Split-Path -Parent $ReceiptPath; if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
[IO.File]::WriteAllText($ReceiptPath, (($result | ConvertTo-Json -Depth 6) -replace "`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
if ($result.status -ne 'FACTIONCTX_PROBE_CAPTURED') { exit 1 }
exit 0
