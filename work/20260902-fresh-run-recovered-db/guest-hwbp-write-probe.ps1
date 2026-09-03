[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][int]$ExpectedPid,
    [Parameter(Mandatory=$true)][string]$Label,
    [Parameter(Mandatory=$true)][string]$ReceiptPath,
    [Parameter(Mandatory=$true)][string]$WatchVaHex,   # data address to watch, e.g. "0x00C9EABC" (4-byte aligned)
    [string]$RunId = '',                               # accepted and ignored; host-step passes it uniformly
    [int]$MaxHits = 200,
    [int]$MaxSeconds = 30
)
# READ-ONLY debug-attach probe. Sets a HARDWARE DATA-WRITE watchpoint (DR0 + DR7 R/W0=01 len=4) on $WatchVa in the
# 32-bit (WOW64) client and records, for each distinct faulting EIP, the register file and the value now at $WatchVa.
# A data breakpoint traps AFTER the storing instruction, so the recorded Eip is the instruction FOLLOWING the store;
# disassemble backwards on the host to identify the writer. No WriteProcessMemory, no input, no allocation in the
# target. Detaches without killing the client (DebugSetProcessKillOnExit false) and clears DR7 on every thread.
# ASCII only: this file is read as CP949 by the guest PowerShell and non-ASCII would be mangled.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath $ReceiptPath) { throw 'RECEIPT_EXISTS' }
if ($WatchVaHex -notmatch '^0[xX][0-9A-Fa-f]{1,8}$') { throw 'WATCH_VA_INVALID' }
$WatchVa = [uint32]('0x' + $WatchVaHex.Substring(2))
if (($WatchVa % 4) -ne 0) { throw 'WATCH_VA_NOT_DWORD_ALIGNED' }
if (-not ('Dbg' -as [type])) { Add-Type -TypeDefinition @'
using System;using System.Collections.Generic;using System.Runtime.InteropServices;
public static class Dbg{
 [DllImport("kernel32.dll",SetLastError=true)] public static extern bool DebugActiveProcess(int pid);
 [DllImport("kernel32.dll",SetLastError=true)] public static extern bool DebugActiveProcessStop(int pid);
 [DllImport("kernel32.dll",SetLastError=true)] public static extern bool DebugSetProcessKillOnExit(bool k);
 [DllImport("kernel32.dll",SetLastError=true)] public static extern bool WaitForDebugEvent(byte[] e,int ms);
 [DllImport("kernel32.dll",SetLastError=true)] public static extern bool ContinueDebugEvent(int pid,int tid,int status);
 [DllImport("kernel32.dll",SetLastError=true)] public static extern IntPtr OpenThread(int access,bool inherit,int tid);
 [DllImport("kernel32.dll",SetLastError=true)] public static extern bool CloseHandle(IntPtr h);
 [DllImport("kernel32.dll",SetLastError=true)] public static extern bool Wow64GetThreadContext(IntPtr h,byte[] ctx);
 [DllImport("kernel32.dll",SetLastError=true)] public static extern bool GetThreadContext(IntPtr h,IntPtr ctx);
 [DllImport("kernel32.dll",SetLastError=true)] public static extern bool SetThreadContext(IntPtr h,IntPtr ctx);
 [DllImport("kernel32.dll",SetLastError=true)] public static extern bool Wow64SetThreadContext(IntPtr h,byte[] ctx);
 [DllImport("kernel32.dll",SetLastError=true)] public static extern uint SuspendThread(IntPtr h);
 [DllImport("kernel32.dll",SetLastError=true)] public static extern uint ResumeThread(IntPtr h);
 [DllImport("kernel32.dll",SetLastError=true)] public static extern IntPtr OpenProcess(int a,bool i,int pid);
 [DllImport("kernel32.dll",SetLastError=true)] public static extern bool ReadProcessMemory(IntPtr h,IntPtr addr,byte[] buf,int size,out int read);
 [DllImport("kernel32.dll",SetLastError=true)] public static extern IntPtr CreateToolhelp32Snapshot(uint flags,int pid);
 [DllImport("kernel32.dll",SetLastError=true)] public static extern bool Thread32First(IntPtr h,ref THREADENTRY32 e);
 [DllImport("kernel32.dll",SetLastError=true)] public static extern bool Thread32Next(IntPtr h,ref THREADENTRY32 e);
 [StructLayout(LayoutKind.Sequential)] public struct THREADENTRY32{ public uint dwSize,cntUsage,th32ThreadID,th32OwnerProcessID; public int tpBasePri,tpDeltaPri; public uint dwFlags; }
 public const int THREAD_GET=0x0008, THREAD_SET=0x0010, THREAD_QUERY=0x0040, THREAD_SUSPEND=0x0002;
 public const int PVM_READ=0x0010, PQUERY=0x0400;
 public const uint TH32CS_SNAPTHREAD=0x00000004;
}
'@ }

# WOW64_CONTEXT (x86): Dr0@4 Dr6@0x14 Dr7@0x18; Edi@0x9C Esi@0xA0 Ebx@0xA4 Edx@0xA8 Ecx@0xAC Eax@0xB0
# Ebp@0xB4 Eip@0xB8 EFlags@0xC0 Esp@0xC4. Size 0x2CC. flags i386|CONTROL|INTEGER|DEBUG = 0x10013.
$CTXSZ = 0x2CC; $CF = 0x00010013
function NewCtx { $b = New-Object byte[] $CTXSZ; [BitConverter]::GetBytes([uint32]$CF).CopyTo($b,0); return $b }
function GetU32([byte[]]$b,[int]$o){ return [BitConverter]::ToUInt32($b,$o) }
function SetU32([byte[]]$b,[int]$o,[uint32]$v){ [BitConverter]::GetBytes([uint32]$v).CopyTo($b,$o) }

# x64 CONTEXT (1232 bytes, MUST be 16-byte aligned): ContextFlags@0x30, Dr0@0x48, Dr6@0x68, Dr7@0x70, Rip@0xF8.
# CONTEXT_AMD64 0x00100000 | CONTROL | INTEGER | DEBUG_REGISTERS = 0x00100013. The debug registers of a WOW64
# thread live in the 64-bit context; Wow64SetThreadContext accepts them and silently drops them (measured).
$CTX64SZ = 1232; $CF64 = 0x00100013
$C64_FLAGS = 0x30; $C64_DR0 = 0x48; $C64_DR6 = 0x68; $C64_DR7 = 0x70; $C64_RIP = 0xF8
$script:ctx64raw = [Runtime.InteropServices.Marshal]::AllocHGlobal($CTX64SZ + 16)
$script:ctx64 = [IntPtr](([int64]$script:ctx64raw + 15) -band -16)
function Ctx64Init { for ($i=0; $i -lt $CTX64SZ; $i+=4) { [Runtime.InteropServices.Marshal]::WriteInt32($script:ctx64, $i, 0) }; [Runtime.InteropServices.Marshal]::WriteInt32($script:ctx64, $C64_FLAGS, $CF64) }
function Ctx64GetU64([int]$o){ return [uint64][Runtime.InteropServices.Marshal]::ReadInt64($script:ctx64, $o) }
function Ctx64SetU64([int]$o,[uint64]$v){ [Runtime.InteropServices.Marshal]::WriteInt64($script:ctx64, $o, [int64]$v) }
function Ctx64Flags { [Runtime.InteropServices.Marshal]::WriteInt32($script:ctx64, $C64_FLAGS, $CF64) }

# DR7: L0(bit0)=1 enable, R/W0(bits16-17)=01 data-write, LEN0(bits18-19)=11 four bytes -> 0x000D0001
$DR7_WRITE4 = 0x000D0001
$armed = @{}
function ArmThread([int]$tid) {
    if ($armed.ContainsKey($tid)) { return }
    $ht = [Dbg]::OpenThread([Dbg]::THREAD_GET -bor [Dbg]::THREAD_SET -bor [Dbg]::THREAD_QUERY -bor [Dbg]::THREAD_SUSPEND, $false, $tid)
    if ($ht -eq [IntPtr]::Zero) { return }
    try {
        $sc = [Dbg]::SuspendThread($ht)
        try {
            Ctx64Init
            if ([Dbg]::GetThreadContext($ht, $script:ctx64)) {
                Ctx64SetU64 $C64_DR0 ([uint64]$WatchVa)
                Ctx64SetU64 $C64_DR6 0
                Ctx64SetU64 $C64_DR7 ([uint64]$DR7_WRITE4)
                Ctx64Flags
                if ([Dbg]::SetThreadContext($ht, $script:ctx64)) { $armed[$tid] = $true }
            }
        } finally { if ($sc -ne [uint32]::MaxValue) { [void][Dbg]::ResumeThread($ht) } }
    } finally { [void][Dbg]::CloseHandle($ht) }
}

$hProc = [Dbg]::OpenProcess([Dbg]::PVM_READ -bor [Dbg]::PQUERY, $false, $ExpectedPid)
if ($hProc -eq [IntPtr]::Zero) { throw "OPEN_PROCESS_FAILED:$([Runtime.InteropServices.Marshal]::GetLastWin32Error())" }
function RB([uint32]$va,[int]$n){ $b=New-Object byte[] $n; $r=0; $ok=[Dbg]::ReadProcessMemory($hProc,[IntPtr]([int64]$va),$b,$n,[ref]$r); if($ok -and $r -eq $n){return $b} return $null }

$result = [ordered]@{ status='PENDING'; label=$Label; pid=$ExpectedPid; watchVa=('0x{0:X8}' -f $WatchVa); dr7=('0x{0:X8}' -f $DR7_WRITE4); capturedAtUtc=[datetime]::UtcNow.ToString('o'); totalHits=0; distinctWriters=0; armedThreads=0; events=[ordered]@{ total=0; exception=0; singleStep=0; breakpoint=0; createThread=0; other=0 }; exCodeSamples=@(); dr7Verify=@(); writers=@() }
$seen = @{}
$attached = $false
try {
    [void][Dbg]::DebugSetProcessKillOnExit($false)
    if (-not [Dbg]::DebugActiveProcess($ExpectedPid)) { throw "DEBUG_ATTACH_FAILED:$([Runtime.InteropServices.Marshal]::GetLastWin32Error())" }
    $attached = $true
    $snap = [Dbg]::CreateToolhelp32Snapshot([Dbg]::TH32CS_SNAPTHREAD, 0)
    if ($snap -ne [IntPtr](-1)) {
        $te = New-Object Dbg+THREADENTRY32; $te.dwSize = [Runtime.InteropServices.Marshal]::SizeOf($te)
        $more = [Dbg]::Thread32First($snap, [ref]$te)
        while ($more) {
            if ($te.th32OwnerProcessID -eq $ExpectedPid) { ArmThread ([int]$te.th32ThreadID) }
            $more = [Dbg]::Thread32Next($snap, [ref]$te)
        }
        [void][Dbg]::CloseHandle($snap)
    }
    $result.armedThreads = $armed.Count
    # read back DR7/DR0 from up to 3 armed threads to prove the watchpoint really took
    $vn = 0
    foreach ($t in @($armed.Keys)) {
        if ($vn -ge 3) { break }
        $hv = [Dbg]::OpenThread([Dbg]::THREAD_GET -bor [Dbg]::THREAD_QUERY, $false, [int]$t)
        if ($hv -ne [IntPtr]::Zero) {
            try { Ctx64Init; if ([Dbg]::GetThreadContext($hv, $script:ctx64)) { $result.dr7Verify += [ordered]@{ tid=[int]$t; dr0=('0x{0:X16}' -f (Ctx64GetU64 $C64_DR0)); dr7=('0x{0:X16}' -f (Ctx64GetU64 $C64_DR7)) }; $vn++ } }
            finally { [void][Dbg]::CloseHandle($hv) }
        }
    }
    $EXCEPTION=1; $CREATE_THREAD=2; $EXIT_THREAD=4
    $EX_SINGLE_STEP=0x80000004; $EX_BP=0x80000003
    $DBG_CONTINUE=0x00010002; $DBG_NOT_HANDLED=0x80010001
    $ev = New-Object byte[] 4096
    $deadline = [datetime]::UtcNow.AddSeconds($MaxSeconds)
    while ([datetime]::UtcNow -lt $deadline -and $result.totalHits -lt $MaxHits) {
        if (-not [Dbg]::WaitForDebugEvent($ev, 200)) { continue }
        $code = [BitConverter]::ToInt32($ev,0); $tid = [BitConverter]::ToInt32($ev,8)
        $cont = $DBG_CONTINUE
        $result.events.total++
        if ($code -eq $EXCEPTION) { $result.events.exception++ } elseif ($code -eq $CREATE_THREAD) { $result.events.createThread++ } else { $result.events.other++ }
        if ($code -eq $CREATE_THREAD) { ArmThread $tid }
        elseif ($code -eq $EXIT_THREAD) { if ($armed.ContainsKey($tid)) { $armed.Remove($tid) } }
        elseif ($code -eq $EXCEPTION) {
            $exCode = [BitConverter]::ToUInt32($ev,16)
            if ($exCode -eq $EX_SINGLE_STEP) { $result.events.singleStep++ } elseif ($exCode -eq $EX_BP) { $result.events.breakpoint++ }
            if ($result.exCodeSamples.Count -lt 12) { $result.exCodeSamples += ('0x{0:X8}' -f $exCode) }
            if ($exCode -eq $EX_SINGLE_STEP -or $exCode -eq $EX_BP) {
                $ht = [Dbg]::OpenThread([Dbg]::THREAD_GET -bor [Dbg]::THREAD_SET -bor [Dbg]::THREAD_QUERY, $false, $tid)
                if ($ht -ne [IntPtr]::Zero) {
                    try {
                        Ctx64Init
                        $c = NewCtx
                        if ([Dbg]::GetThreadContext($ht, $script:ctx64)) {
                            $dr6 = [uint32]((Ctx64GetU64 $C64_DR6) -band 0xFFFFFFFF)
                            # B0 (bit0) set => our DR0 data watchpoint fired
                            if (($dr6 -band 0x1) -ne 0) {
                                $result.totalHits++
                                $eip = 0
                                if ([Dbg]::Wow64GetThreadContext($ht, $c)) { $eip = GetU32 $c 0xB8 }
                                if ($eip -eq 0) { $eip = [uint32]((Ctx64GetU64 $C64_RIP) -band 0xFFFFFFFF) }
                                $key = ('0x{0:X8}' -f $eip)
                                if (-not $seen.ContainsKey($key)) {
                                    $vb = RB $WatchVa 4
                                    $val = if ($vb) { ('0x{0:X8}' -f [BitConverter]::ToUInt32($vb,0)) } else { $null }
                                    $seen[$key] = [ordered]@{
                                        eipAfterStore = $key
                                        valueAtWatch  = $val
                                        eax=('0x{0:X8}' -f (GetU32 $c 0xB0)); ecx=('0x{0:X8}' -f (GetU32 $c 0xAC))
                                        edx=('0x{0:X8}' -f (GetU32 $c 0xA8)); ebx=('0x{0:X8}' -f (GetU32 $c 0xA4))
                                        esi=('0x{0:X8}' -f (GetU32 $c 0xA0)); edi=('0x{0:X8}' -f (GetU32 $c 0x9C))
                                        ebp=('0x{0:X8}' -f (GetU32 $c 0xB4)); esp=('0x{0:X8}' -f (GetU32 $c 0xC4))
                                        hitCount = 0
                                    }
                                }
                                $seen[$key].hitCount++
                                Ctx64SetU64 $C64_DR6 0     # clear DR6 status
                                Ctx64Flags
                                [void][Dbg]::SetThreadContext($ht, $script:ctx64)
                            }
                        }
                    } finally { [void][Dbg]::CloseHandle($ht) }
                }
                if ($exCode -eq $EX_BP) { $cont = $DBG_CONTINUE }
            } else { $cont = $DBG_NOT_HANDLED }
        }
        [void][Dbg]::ContinueDebugEvent($ExpectedPid, $tid, $cont)
    }
    # Collect thread ids into an array FIRST (a do{}while() walk with any early-exit inside is what silently
    # skipped threads before), then clear DR7/DR0/DR6 on each while suspended. A stray DR7 kills the client on
    # its next watchpoint hit, so this must never partially run.
    function ListProcThreads {
        $ids = New-Object System.Collections.ArrayList
        $snap = [Dbg]::CreateToolhelp32Snapshot([Dbg]::TH32CS_SNAPTHREAD, 0)
        if ($snap -eq [IntPtr](-1)) { return $ids }
        try {
            $te = New-Object Dbg+THREADENTRY32
            $te.dwSize = [Runtime.InteropServices.Marshal]::SizeOf($te)
            $more = [Dbg]::Thread32First($snap, [ref]$te)
            while ($more) {
                if ($te.th32OwnerProcessID -eq $ExpectedPid) { [void]$ids.Add([int]$te.th32ThreadID) }
                $more = [Dbg]::Thread32Next($snap, [ref]$te)
            }
        } finally { [void][Dbg]::CloseHandle($snap) }
        return $ids
    }
    function DisarmAllThreads {
        $done = $true; $cleared = 0; $failed = @()
        $ids = @(ListProcThreads)
        if ($ids.Count -eq 0) { return @{ done = $false; cleared = 0; failed = @('no-threads') } }
        foreach ($tid in $ids) {
            $ht = [Dbg]::OpenThread([Dbg]::THREAD_GET -bor [Dbg]::THREAD_SET -bor [Dbg]::THREAD_SUSPEND, $false, [int]$tid)
            if ($ht -eq [IntPtr]::Zero) { $done = $false; $failed += $tid }
            else {
                try {
                    $sc = [Dbg]::SuspendThread($ht)
                    try {
                        $ok = $false
                        Ctx64Init
                        if ([Dbg]::GetThreadContext($ht, $script:ctx64)) {
                            Ctx64SetU64 $C64_DR7 0; Ctx64SetU64 $C64_DR0 0; Ctx64SetU64 $C64_DR6 0; Ctx64Flags
                            $ok = [Dbg]::SetThreadContext($ht, $script:ctx64)
                        }
                        Ctx64Init
                        if ($ok -and [Dbg]::GetThreadContext($ht, $script:ctx64) -and (Ctx64GetU64 $C64_DR7) -eq 0) { $cleared++ }
                        else { $done = $false; $failed += $tid }
                    } finally { if ($sc -ne [uint32]::MaxValue) { [void][Dbg]::ResumeThread($ht) } }
                } finally { [void][Dbg]::CloseHandle($ht) }
            }
        }
        return @{ done = $done; cleared = $cleared; failed = $failed }
    }
    $result.disarmed = 0; $result.disarmFailed = @()
    for ($attempt = 0; $attempt -lt 4; $attempt++) {
        $d = DisarmAllThreads
        $result.disarmed = $d.cleared; $result.disarmFailed = @($d.failed)
        if ($d.done) { break }
        Start-Sleep -Milliseconds 150
    }
    $result.writers = @($seen.Values)
    $result.distinctWriters = $seen.Count
    $result.status = 'HWBP_WRITE_PROBE_CAPTURED'
} catch { $result.status='HWBP_WRITE_PROBE_FAILED'; $result.error=$_.Exception.Message }
finally {
    if ($attached) { [void][Dbg]::DebugActiveProcessStop($ExpectedPid) }
    [void][Dbg]::CloseHandle($hProc)
    if ($script:ctx64raw -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::FreeHGlobal($script:ctx64raw) }
}
$parent = Split-Path -Parent $ReceiptPath; if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
[IO.File]::WriteAllText($ReceiptPath, (($result | ConvertTo-Json -Depth 7) -replace "`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
if ($result.status -ne 'HWBP_WRITE_PROBE_CAPTURED') { exit 1 }
exit 0
