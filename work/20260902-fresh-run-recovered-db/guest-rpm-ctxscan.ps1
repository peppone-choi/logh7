[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][int]$ExpectedPid,
    [Parameter(Mandatory=$true)][string]$Label,
    [Parameter(Mandatory=$true)][string]$ReceiptPath
)
# READ-ONLY scan of the frame dispatcher FUN_004fd100's sub-handler contexts (topCtx 0x00C9E638 + offset,
# byte-verified). Reads each context's first 0x40 bytes to see which subsystem is ACTIVE (non-zero) on this
# screen; comparing lobby vs faction isolates the faction subsystem. No writes/input/debugger (crash-free).
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath $ReceiptPath) { throw 'RECEIPT_EXISTS' }
if (-not ('RpmC' -as [type])) { Add-Type -TypeDefinition @'
using System;using System.Runtime.InteropServices;
public static class RpmC{
 [DllImport("kernel32.dll",SetLastError=true)] public static extern IntPtr OpenProcess(int a,bool i,int pid);
 [DllImport("kernel32.dll",SetLastError=true)] public static extern bool ReadProcessMemory(IntPtr h,IntPtr addr,byte[] buf,int size,out int read);
 [DllImport("kernel32.dll")] public static extern bool CloseHandle(IntPtr h);
 public const int VM_READ=0x0010, QUERY=0x0400;
}
'@ }
$h = [RpmC]::OpenProcess([RpmC]::VM_READ -bor [RpmC]::QUERY, $false, $ExpectedPid)
if ($h -eq [IntPtr]::Zero) { throw "OPEN_PROCESS_FAILED:$([Runtime.InteropServices.Marshal]::GetLastWin32Error())" }
function RB([uint32]$va,[int]$n){ $b=New-Object byte[] $n; $r=0; $ok=[RpmC]::ReadProcessMemory($h,[IntPtr]([int64]$va),$b,$n,[ref]$r); if($ok -and $r -eq $n){return $b} return $null }

# (absCtx, handler) sub-handlers of FUN_004fd100 (this=0x00C9E638)
$ctxs = @(
 @(0x00CA558C,'FUN_0052dc20'), @(0x00C9E768,'FUN_004f58c0'), @(0x00C9F0F0,'FUN_005909b0'),
 @(0x00CA3710,'FUN_005794d0'), @(0x00CB0038,'FUN_00539ce0'),
 @(0x021A541C,'FUN_00535390'), @(0x021A5430,'FUN_00543570'), @(0x021A9B70,'FUN_00546380')
)
$result=[ordered]@{ status='PENDING'; label=$Label; pid=$ExpectedPid; capturedAtUtc=[datetime]::UtcNow.ToString('o'); contexts=@() }
$list=[Collections.Generic.List[object]]::new()
try {
    foreach ($c in $ctxs) {
        $addr=[uint32]$c[0]; $hd=RB $addr 0x40
        $nz=0; $hex=$null
        if ($hd) { foreach($b in $hd){ if($b -ne 0){$nz++} }; $hex=([BitConverter]::ToString($hd) -replace '-','') }
        $list.Add([ordered]@{ ctx=('0x{0:X8}' -f $addr); handler=$c[1]; readable=($null -ne $hd); nonZeroBytes=$nz; headerHex=$hex })
    }
    $result.contexts=@($list); $result.status='CTXSCAN_CAPTURED'
} catch { $result.status='CTXSCAN_FAILED'; $result.error=$_.Exception.Message }
finally { [void][RpmC]::CloseHandle($h) }
$parent = Split-Path -Parent $ReceiptPath; if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
[IO.File]::WriteAllText($ReceiptPath, (($result | ConvertTo-Json -Depth 6) -replace "`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
if ($result.status -ne 'CTXSCAN_CAPTURED') { exit 1 }
exit 0
