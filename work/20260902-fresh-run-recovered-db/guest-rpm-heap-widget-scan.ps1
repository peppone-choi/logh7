[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][int]$ExpectedPid,
    [Parameter(Mandatory=$true)][string]$Label,
    [Parameter(Mandatory=$true)][string]$ReceiptPath,
    # constmsg table 0x4E rows that identify faction/create widgets: 帝国=45, 同盟=46, 次へ=47, 中止=80.
    [int[]]$FactionRows = @(45, 46, 47, 80),
    [int]$MaxRegionMB = 96
)
# READ-ONLY full-committed-memory scan for the client's widget-manager objects.
# The manager layout (validated in prior probes): widget count at +0x3F4, widget records of 0x34 bytes at
# +0x4E8, each record carrying an input-enable byte at +0x08 and its constmsg-0x4E row at +0x15.
# The earlier collector only dereferenced uiRoot/registry slots and found fixed template objects; this scans
# every committed readable region and treats each 4-aligned offset as a candidate manager base, so it can
# locate the LIVE create/faction manager wherever it was heap-allocated. No writes, no input, no allocation
# in the target: OpenProcess(VM_READ|QUERY) + VirtualQueryEx + ReadProcessMemory only.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath $ReceiptPath) { throw 'RECEIPT_EXISTS' }
if (-not ('RpmScan' -as [type])) { Add-Type -TypeDefinition @'
using System;using System.Runtime.InteropServices;
public static class RpmScan{
 [DllImport("kernel32.dll",SetLastError=true)] public static extern IntPtr OpenProcess(int access,bool inherit,int pid);
 [DllImport("kernel32.dll",SetLastError=true)] public static extern bool ReadProcessMemory(IntPtr h,IntPtr addr,byte[] buf,int size,out int read);
 [DllImport("kernel32.dll")] public static extern bool CloseHandle(IntPtr h);
 [DllImport("kernel32.dll")] public static extern int VirtualQueryEx(IntPtr h,IntPtr addr,out MEMORY_BASIC_INFORMATION mbi,int len);
 public const int PROCESS_VM_READ=0x0010, PROCESS_QUERY_INFORMATION=0x0400;
 [StructLayout(LayoutKind.Sequential)] public struct MEMORY_BASIC_INFORMATION{
  public IntPtr BaseAddress; public IntPtr AllocationBase; public uint AllocationProtect; public IntPtr RegionSize;
  public uint State; public uint Protect; public uint Type; }
}
'@ }
$h = [RpmScan]::OpenProcess([RpmScan]::PROCESS_VM_READ -bor [RpmScan]::PROCESS_QUERY_INFORMATION, $false, $ExpectedPid)
if ($h -eq [IntPtr]::Zero) { throw "OPEN_PROCESS_FAILED:$([Runtime.InteropServices.Marshal]::GetLastWin32Error())" }

$MEM_COMMIT = 0x1000
$PAGE_READABLE = 0x02, 0x04, 0x20, 0x40  # READONLY, READWRITE, EXECUTE_READ, EXECUTE_READWRITE
$PAGE_GUARD = 0x100
$rowSet = @{}; foreach ($rr in $FactionRows) { $rowSet[[int]$rr] = $true }

$result = [ordered]@{ status = 'PENDING'; label = $Label; pid = $ExpectedPid; capturedAtUtc = [datetime]::UtcNow.ToString('o'); factionRows = $FactionRows; regionsScanned = 0; bytesScanned = 0; candidates = @() }
$cands = [Collections.Generic.List[object]]::new()
try {
    $addr = [int64]0
    $max = [int64]0x7FFFFFFF
    while ($addr -lt $max) {
        $mbi = New-Object RpmScan+MEMORY_BASIC_INFORMATION
        $got = [RpmScan]::VirtualQueryEx($h, [IntPtr]$addr, [ref]$mbi, [Runtime.InteropServices.Marshal]::SizeOf($mbi))
        if ($got -eq 0) { break }
        $rsize = [int64]$mbi.RegionSize
        if ($rsize -le 0) { break }
        $base = [int64]$mbi.BaseAddress
        $prot = [int]$mbi.Protect
        $isCommit = ([int]$mbi.State -eq $MEM_COMMIT)
        $isReadable = ($PAGE_READABLE -contains ($prot -band 0xFF)) -and (($prot -band $PAGE_GUARD) -eq 0)
        if ($isCommit -and $isReadable -and $rsize -le ($MaxRegionMB * 1MB)) {
            $buf = New-Object byte[] $rsize
            $read = 0
            $ok = [RpmScan]::ReadProcessMemory($h, [IntPtr]$base, $buf, [int][Math]::Min($rsize, [int64]([int]::MaxValue)), [ref]$read)
            if ($ok -and $read -gt 0x600) {
                $result.regionsScanned++
                $result.bytesScanned += $read
                # Treat each 4-aligned offset as a candidate manager base.
                $limit = $read - 0x600
                for ($o = 0; $o -lt $limit; $o += 4) {
                    $count = [BitConverter]::ToUInt32($buf, $o + 0x3F4)
                    if ($count -lt 2 -or $count -gt 64) { continue }
                    $recs = @(); $rowsSeen = @{}; $distinct = 0
                    $n = [Math]::Min([int]$count, 24)
                    $recBase = $o + 0x4E8
                    if ($recBase + $n * 0x34 -gt $read) { continue }
                    for ($i = 0; $i -lt $n; $i++) {
                        $rb = $recBase + $i * 0x34
                        $f08 = $buf[$rb + 0x08]; $f15 = $buf[$rb + 0x15]
                        $recs += [ordered]@{ i = $i; flag08 = $f08; row15 = $f15 }
                        if ($rowSet.ContainsKey([int]$f15) -and -not $rowsSeen.ContainsKey([int]$f15)) { $rowsSeen[[int]$f15] = $true; $distinct++ }
                    }
                    if ($distinct -ge 2) {
                        $cands.Add([ordered]@{ managerVa = ('0x{0:X8}' -f ([uint32]($base + $o))); widgetCount = [int]$count; distinctFactionRows = $distinct; regionBase = ('0x{0:X8}' -f ([uint32]$base)); regionType = [int]$mbi.Type; widgets = $recs })
                    }
                }
            }
        }
        $addr = $base + $rsize
    }
    $result.candidates = @($cands)
    $result.status = 'HEAP_WIDGET_SCAN_CAPTURED'
} catch { $result.status = 'HEAP_WIDGET_SCAN_FAILED'; $result.error = $_.Exception.Message }
finally { [void][RpmScan]::CloseHandle($h) }
$parent = Split-Path -Parent $ReceiptPath; if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
[IO.File]::WriteAllText($ReceiptPath, (($result | ConvertTo-Json -Depth 7) -replace "`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
if ($result.status -ne 'HEAP_WIDGET_SCAN_CAPTURED') { exit 1 }
exit 0
