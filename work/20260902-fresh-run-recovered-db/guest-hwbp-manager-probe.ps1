[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][int]$ExpectedPid,
    [Parameter(Mandatory=$true)][string]$Label,
    [Parameter(Mandatory=$true)][string]$ReceiptPath,
    [uint32]$BpVa = 0x005015F0,   # FUN_005015F0 entry (shared per-manager hit-test): this=ecx=manager, arg2=[esp+8]=ctx
    [int]$MaxHits = 600,
    [int]$MaxSeconds = 8,
    [int[]]$FactionRows = @(45, 46, 47, 80)
)
# READ-ONLY debug-attach probe. Sets a HARDWARE execution breakpoint (debug register DR0, no target memory
# writes) on the shared hit-test FUN_005015F0 of the 32-bit (WOW64) client, and at each hit records the
# manager `this` (Ecx) and the ctx widget-holder (stack arg2). For each distinct manager it reads, read-only,
# manager+0x05 (the input-enable byte the gate FUN_005024A0 returns) and, from ctx, the widget count
# (ctx+0x3F4) and each record's +0x15 constmsg row (records at ctx+0x4E8, 0x34 stride). The faction manager is
# the one whose ctx records carry rows in {45,46,47,80}. Detaches without killing the client
# (DebugSetProcessKillOnExit false). No WriteProcessMemory, no input, no allocation in the target.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath $ReceiptPath) { throw 'RECEIPT_EXISTS' }
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

# WOW64_CONTEXT offsets (x86 CONTEXT): ContextFlags@0; Dr0@4 Dr1@8 Dr2@0xC Dr3@0x10 Dr6@0x14 Dr7@0x18;
# Ecx@0xAC; Eip@0xB8; EFlags@0xC0; Esp@0xC4. Size 0x2CC. CONTEXT flags: i386|CONTROL|INTEGER|DEBUG = 0x10013.
$CTXSZ = 0x2CC; $CF = 0x00010013
function NewCtx { $b = New-Object byte[] $CTXSZ; [BitConverter]::GetBytes([uint32]$CF).CopyTo($b,0); return $b }
function GetU32([byte[]]$b,[int]$o){ return [BitConverter]::ToUInt32($b,$o) }
function SetU32([byte[]]$b,[int]$o,[uint32]$v){ [BitConverter]::GetBytes([uint32]$v).CopyTo($b,$o) }

$armed = @{}
function ArmThread([int]$tid) {
    if ($armed.ContainsKey($tid)) { return }
    $ht = [Dbg]::OpenThread([Dbg]::THREAD_GET -bor [Dbg]::THREAD_SET -bor [Dbg]::THREAD_QUERY -bor [Dbg]::THREAD_SUSPEND, $false, $tid)
    if ($ht -eq [IntPtr]::Zero) { return }
    try {
        # Context writes are only reliable on a suspended thread; suspend, write debug registers, resume.
        $sc = [Dbg]::SuspendThread($ht)
        try {
            $c = NewCtx
            if ([Dbg]::Wow64GetThreadContext($ht, $c)) {
                SetU32 $c 0x04 $BpVa           # Dr0 = breakpoint address
                SetU32 $c 0x14 0               # Dr6 = 0
                SetU32 $c 0x18 0x00000001      # Dr7 = L0 (execute, len 1 byte)
                if ([Dbg]::Wow64SetThreadContext($ht, $c)) { $armed[$tid] = $true }
            }
        } finally { if ($sc -ne [uint32]::MaxValue) { [void][Dbg]::ResumeThread($ht) } }
    } finally { [void][Dbg]::CloseHandle($ht) }
}

$hProc = [Dbg]::OpenProcess([Dbg]::PVM_READ -bor [Dbg]::PQUERY, $false, $ExpectedPid)
if ($hProc -eq [IntPtr]::Zero) { throw "OPEN_PROCESS_FAILED:$([Runtime.InteropServices.Marshal]::GetLastWin32Error())" }
function RB([uint32]$va,[int]$n){ $b=New-Object byte[] $n; $r=0; $ok=[Dbg]::ReadProcessMemory($hProc,[IntPtr]([int64]$va),$b,$n,[ref]$r); if($ok -and $r -eq $n){return $b} return $null }

$result = [ordered]@{ status='PENDING'; label=$Label; pid=$ExpectedPid; bpVa=('0x{0:X8}' -f $BpVa); capturedAtUtc=[datetime]::UtcNow.ToString('o'); totalHits=0; distinctManagers=0; armedThreads=0; events=[ordered]@{ total=0; exception=0; singleStep=0; breakpoint=0; createThread=0; other=0 }; singleStepEipSamples=@(); managers=@() }
$seen = @{}   # manager -> record object
$attached = $false
try {
    [void][Dbg]::DebugSetProcessKillOnExit($false)
    if (-not [Dbg]::DebugActiveProcess($ExpectedPid)) { throw "DEBUG_ATTACH_FAILED:$([Runtime.InteropServices.Marshal]::GetLastWin32Error())" }
    $attached = $true
    # Arm all existing threads.
    $snap = [Dbg]::CreateToolhelp32Snapshot([Dbg]::TH32CS_SNAPTHREAD, 0)
    if ($snap -ne [IntPtr](-1)) {
        $te = New-Object Dbg+THREADENTRY32; $te.dwSize = [Runtime.InteropServices.Marshal]::SizeOf($te)
        if ([Dbg]::Thread32First($snap, [ref]$te)) {
            do { if ($te.th32OwnerProcessID -eq $ExpectedPid) { ArmThread ([int]$te.th32ThreadID) } } while ([Dbg]::Thread32Next($snap, [ref]$te))
        }
        [void][Dbg]::CloseHandle($snap)
    }
    $result.armedThreads = $armed.Count
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
            if ($exCode -eq $EX_SINGLE_STEP -or $exCode -eq $EX_BP) {
                $ht = [Dbg]::OpenThread([Dbg]::THREAD_GET -bor [Dbg]::THREAD_SET -bor [Dbg]::THREAD_QUERY, $false, $tid)
                if ($ht -ne [IntPtr]::Zero) {
                    try {
                        $c = NewCtx
                        if ([Dbg]::Wow64GetThreadContext($ht, $c)) {
                            $eip = GetU32 $c 0xB8
                            if ($exCode -eq $EX_SINGLE_STEP -and $result.singleStepEipSamples.Count -lt 8) { $result.singleStepEipSamples += ('0x{0:X8}' -f $eip) }
                            if ($eip -eq $BpVa) {
                                $result.totalHits++
                                $mgr = GetU32 $c 0xAC          # Ecx = manager this
                                $esp = GetU32 $c 0xC4
                                if (-not $seen.ContainsKey($mgr)) {
                                    $ctxb = RB ([uint32]($esp + 8)) 4    # arg2 = ctx (widget holder)
                                    $ctx = if ($ctxb) { [BitConverter]::ToUInt32($ctxb,0) } else { 0 }
                                    $enable = $null; $eb = RB ([uint32]($mgr + 0x05)) 1; if ($eb) { $enable = $eb[0] }
                                    $rows = @(); $count = $null; $factionHit = $false
                                    if ($ctx -ge 0x10000) {
                                        $cb = RB ([uint32]($ctx + 0x3F4)) 4
                                        if ($cb) { $count = [BitConverter]::ToUInt32($cb,0) }
                                        if ($count -ne $null -and $count -ge 1 -and $count -le 64) {
                                            $n = [Math]::Min([int]$count, 24)
                                            for ($i=0; $i -lt $n; $i++) {
                                                $rec = RB ([uint32]($ctx + 0x4E8 + $i*0x34)) 0x34
                                                if ($rec) { $r15=$rec[0x15]; $r08=$rec[0x08]; $rows += [ordered]@{ i=$i; row15=$r15; f08=$r08 }; if ($FactionRows -contains [int]$r15) { $factionHit = $true } }
                                            }
                                        }
                                    }
                                    $seen[$mgr] = [ordered]@{ manager=('0x{0:X8}' -f $mgr); enable05=$enable; ctx=('0x{0:X8}' -f $ctx); ctxWidgetCount=$count; factionRowsPresent=$factionHit; hitCount=0; widgets=$rows }
                                }
                                $seen[$mgr].hitCount++
                                SetU32 $c 0x14 0                      # clear Dr6
                                $ef = GetU32 $c 0xC0; SetU32 $c 0xC0 ($ef -bor 0x10000)   # set RF so BP does not re-trigger on this insn
                                [void][Dbg]::Wow64SetThreadContext($ht, $c)
                            }
                        }
                    } finally { [void][Dbg]::CloseHandle($ht) }
                }
                if ($exCode -eq $EX_BP) { $cont = $DBG_CONTINUE } # swallow initial attach breakpoint
            } else { $cont = $DBG_NOT_HANDLED }
        }
        [void][Dbg]::ContinueDebugEvent($ExpectedPid, $tid, $cont)
    }
    # Disarm EVERY thread of the process (fresh snapshot, not just the armed set) while SUSPENDED, so no
    # thread keeps DR7 after detach (a stray DR7 kills the client on the next hit). Retry until all clear.
    function DisarmAllThreads {
        $done = $true; $cleared = 0; $failed = @()
        $snap = [Dbg]::CreateToolhelp32Snapshot([Dbg]::TH32CS_SNAPTHREAD, 0)
        if ($snap -eq [IntPtr](-1)) { return @{ done = $false; cleared = 0; failed = @('snapshot') } }
        try {
            $te = New-Object Dbg+THREADENTRY32; $te.dwSize = [Runtime.InteropServices.Marshal]::SizeOf($te)
            if ([Dbg]::Thread32First($snap, [ref]$te)) {
                do {
                    if ($te.th32OwnerProcessID -ne $ExpectedPid) { continue }
                    $tid = [int]$te.th32ThreadID
                    $ht = [Dbg]::OpenThread([Dbg]::THREAD_GET -bor [Dbg]::THREAD_SET -bor [Dbg]::THREAD_SUSPEND, $false, $tid)
                    if ($ht -eq [IntPtr]::Zero) { $done = $false; $failed += $tid; continue }
                    try {
                        $sc = [Dbg]::SuspendThread($ht)
                        try {
                            $c = NewCtx; $ok = $false
                            if ([Dbg]::Wow64GetThreadContext($ht, $c)) { SetU32 $c 0x18 0; SetU32 $c 0x04 0; SetU32 $c 0x14 0; $ok = [Dbg]::Wow64SetThreadContext($ht, $c) }
                            $v = NewCtx
                            if ($ok -and [Dbg]::Wow64GetThreadContext($ht, $v) -and (GetU32 $v 0x18) -eq 0) { $cleared++ } else { $done = $false; $failed += $tid }
                        } finally { if ($sc -ne [uint32]::MaxValue) { [void][Dbg]::ResumeThread($ht) } }
                    } finally { [void][Dbg]::CloseHandle($ht) }
                } while ([Dbg]::Thread32Next($snap, [ref]$te))
            }
        } finally { [void][Dbg]::CloseHandle($snap) }
        return @{ done = $done; cleared = $cleared; failed = $failed }
    }
    $result.disarmed = 0; $result.disarmFailed = @()
    for ($attempt = 0; $attempt -lt 4; $attempt++) {
        $d = DisarmAllThreads
        $result.disarmed = $d.cleared; $result.disarmFailed = @($d.failed)
        if ($d.done) { break }
        Start-Sleep -Milliseconds 150
    }
    $result.managers = @($seen.Values)
    $result.distinctManagers = $seen.Count
    $result.status = 'HWBP_MANAGER_PROBE_CAPTURED'
} catch { $result.status='HWBP_MANAGER_PROBE_FAILED'; $result.error=$_.Exception.Message }
finally {
    if ($attached) { [void][Dbg]::DebugActiveProcessStop($ExpectedPid) }
    [void][Dbg]::CloseHandle($hProc)
}
$parent = Split-Path -Parent $ReceiptPath; if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
[IO.File]::WriteAllText($ReceiptPath, (($result | ConvertTo-Json -Depth 7) -replace "`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
if ($result.status -ne 'HWBP_MANAGER_PROBE_CAPTURED') { exit 1 }
exit 0
