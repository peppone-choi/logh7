param(
    [Parameter(Mandatory=$true)][string]$OutputPath,
    [Parameter(Mandatory=$true)][string]$StartedPath,
    [Parameter(Mandatory=$true)][string]$DiagnosticPath
)

$ErrorActionPreference = 'Stop'
$captureStarted = [datetime]::UtcNow

function Write-CanonicalJson {
    param([object]$Value, [string]$Path)
    $json = ($Value | ConvertTo-Json -Depth 16) -replace "`r`n", "`n"
    [IO.File]::WriteAllText($Path, $json + "`n", [Text.UTF8Encoding]::new($false))
}

function Format-Hwnd([IntPtr]$Value) { return ('0x{0:X16}' -f $Value.ToInt64()) }
function Format-Address([IntPtr]$Value) { return ('0x{0:X8}' -f $Value.ToInt64()) }

try {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Text;

public static class InteractiveCanaryNative {
    public delegate bool EnumWindowsProc(IntPtr hwnd, IntPtr lParam);
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc callback, IntPtr lParam);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hwnd);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr hwnd, StringBuilder text, int maxCount);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetClassName(IntPtr hwnd, StringBuilder text, int maxCount);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hwnd, out RECT rect);
    [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr hwnd, out RECT rect);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern IntPtr GetProcessWindowStation();
    [DllImport("user32.dll")] public static extern IntPtr GetThreadDesktop(uint threadId);
    [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern bool GetUserObjectInformation(IntPtr handle, int index, StringBuilder value, uint length, out uint needed);
    [DllImport("kernel32.dll")] public static extern uint WTSGetActiveConsoleSessionId();
}
'@

    function Get-UserObjectName([IntPtr]$Handle) {
        $needed = [uint32]0
        $buffer = [Text.StringBuilder]::new(512)
        if (-not [InteractiveCanaryNative]::GetUserObjectInformation($Handle, 2, $buffer, [uint32]($buffer.Capacity * 2), [ref]$needed)) {
            throw "GetUserObjectInformation failed: $([Runtime.InteropServices.Marshal]::GetLastWin32Error())"
        }
        return $buffer.ToString()
    }

    function Get-ForegroundIdentity {
        $hwnd = [InteractiveCanaryNative]::GetForegroundWindow()
        $owner = [uint32]0
        if ($hwnd -ne [IntPtr]::Zero) { [void][InteractiveCanaryNative]::GetWindowThreadProcessId($hwnd, [ref]$owner) }
        return [ordered]@{ hwnd=(Format-Hwnd $hwnd); ownerPid=[int]$owner }
    }

    function Get-ProcessIdentity([Diagnostics.Process]$Process, [string]$Role) {
        $path = $Process.MainModule.FileName
        return [ordered]@{
            role=$Role
            name=$Process.ProcessName
            pid=$Process.Id
            sessionId=$Process.SessionId
            startTimeUtc=$Process.StartTime.ToUniversalTime().ToString('o')
            path=$path
            sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
            moduleBase=(Format-Address $Process.MainModule.BaseAddress)
            moduleSize=[int64]$Process.MainModule.ModuleMemorySize
            mainWindowHandle=(Format-Hwnd $Process.MainWindowHandle)
        }
    }

    function Get-ObservationSnapshot {
        $clients = @(Get-Process -Name 'G7MTClient' -ErrorAction SilentlyContinue)
        $debuggers = @(Get-Process -Name 'x32dbg' -ErrorAction SilentlyContinue)
        $processes = @()
        foreach ($process in $clients) { $processes += Get-ProcessIdentity $process 'CLIENT' }
        foreach ($process in $debuggers) { $processes += Get-ProcessIdentity $process 'DEBUGGER' }

        $roleByPid = @{}
        foreach ($process in $processes) { $roleByPid[[int]$process.pid] = [string]$process.role }
        $windows = [Collections.Generic.List[object]]::new()
        $callback = [InteractiveCanaryNative+EnumWindowsProc]{
            param([IntPtr]$hwnd, [IntPtr]$lParam)
            $owner = [uint32]0
            [void][InteractiveCanaryNative]::GetWindowThreadProcessId($hwnd, [ref]$owner)
            if ($roleByPid.ContainsKey([int]$owner)) {
                $wr = [InteractiveCanaryNative+RECT]::new()
                $cr = [InteractiveCanaryNative+RECT]::new()
                [void][InteractiveCanaryNative]::GetWindowRect($hwnd, [ref]$wr)
                [void][InteractiveCanaryNative]::GetClientRect($hwnd, [ref]$cr)
                $title = [Text.StringBuilder]::new(1024)
                $class = [Text.StringBuilder]::new(256)
                [void][InteractiveCanaryNative]::GetWindowText($hwnd, $title, $title.Capacity)
                [void][InteractiveCanaryNative]::GetClassName($hwnd, $class, $class.Capacity)
                $windows.Add([ordered]@{
                    role=$roleByPid[[int]$owner]
                    hwnd=(Format-Hwnd $hwnd)
                    ownerPid=[int]$owner
                    visible=[InteractiveCanaryNative]::IsWindowVisible($hwnd)
                    title=$title.ToString()
                    class=$class.ToString()
                    windowRect=[ordered]@{left=$wr.Left;top=$wr.Top;right=$wr.Right;bottom=$wr.Bottom}
                    clientRect=[ordered]@{left=$cr.Left;top=$cr.Top;right=$cr.Right;bottom=$cr.Bottom}
                })
            }
            return $true
        }
        [void][InteractiveCanaryNative]::EnumWindows($callback, [IntPtr]::Zero)
        return [ordered]@{processes=@($processes);windows=@($windows | Sort-Object role,hwnd)}
    }

    $self = Get-Process -Id $PID
    $activeConsole = [int][InteractiveCanaryNative]::WTSGetActiveConsoleSessionId()
    $windowStation = Get-UserObjectName ([InteractiveCanaryNative]::GetProcessWindowStation())
    $desktop = Get-UserObjectName ([InteractiveCanaryNative]::GetThreadDesktop([InteractiveCanaryNative]::GetCurrentThreadId()))
    $scriptHash = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash
    $helper = [ordered]@{
        scriptPath=$PSCommandPath
        scriptSha256=$scriptHash
        pid=$PID
        sessionId=$self.SessionId
        activeConsoleSessionId=$activeConsole
        userName=[Security.Principal.WindowsIdentity]::GetCurrent().Name
        windowStation=$windowStation
        desktop=$desktop
    }
    Write-CanonicalJson ([ordered]@{status='STARTED';capturedAtUtc=[datetime]::UtcNow.ToString('o');helper=$helper}) $StartedPath

    $foregroundBefore = Get-ForegroundIdentity
    $firstObservation = Get-ObservationSnapshot
    $first = [ordered]@{label='A';capturedAtUtc=[datetime]::UtcNow.ToString('o');processes=@($firstObservation.processes);windows=@($firstObservation.windows)}
    Start-Sleep -Milliseconds 150
    $secondObservation = Get-ObservationSnapshot
    $second = [ordered]@{label='B';capturedAtUtc=[datetime]::UtcNow.ToString('o');processes=@($secondObservation.processes);windows=@($secondObservation.windows)}
    $foregroundAfter = Get-ForegroundIdentity
    $firstJson = $first | ConvertTo-Json -Depth 12 -Compress
    $secondJson = $second | ConvertTo-Json -Depth 12 -Compress

    $receipt = [ordered]@{
        schemaVersion=1
        provenance='LIVE_READONLY_INTERACTIVE_CANARY'
        captureStartedAtUtc=$captureStarted.ToString('o')
        captureCompletedAtUtc=[datetime]::UtcNow.ToString('o')
        helper=$helper
        processes=@($second.processes)
        windows=@($second.windows)
        snapshots=@($first,$second)
        snapshotStable=($firstJson -ceq $secondJson)
        foreground=[ordered]@{
            beforeHwnd=$foregroundBefore.hwnd
            beforeOwnerPid=$foregroundBefore.ownerPid
            afterHwnd=$foregroundAfter.hwnd
            afterOwnerPid=$foregroundAfter.ownerPid
            unchanged=($foregroundBefore.hwnd -ceq $foregroundAfter.hwnd -and $foregroundBefore.ownerPid -eq $foregroundAfter.ownerPid)
        }
        operations=[ordered]@{
            helperProcessesCreated=1
            guestFileWrites=3
            processMemoryReads=0
            processMemoryWrites=0
            foregroundChanges=0
            debuggerAttach=0
            debuggerCommands=0
            breakpointsInstalled=0
            captures=0
            gameInputs=0
            automaticInputs=0
            permitIssued=$false
            vmLifecycleChanges=0
            serverChanges=0
            protocolChanges=0
            databaseChanges=0
        }
    }
    Write-CanonicalJson $receipt $OutputPath
    Write-CanonicalJson ([ordered]@{status='PASS';capturedAtUtc=[datetime]::UtcNow.ToString('o');outputPath=$OutputPath;startedPath=$StartedPath;scriptSha256=$scriptHash}) $DiagnosticPath
    $receipt | ConvertTo-Json -Depth 16
} catch {
    $failure = [ordered]@{
        status='FAIL'
        capturedAtUtc=[datetime]::UtcNow.ToString('o')
        errorType=$_.Exception.GetType().FullName
        errorMessage=$_.Exception.Message
        outputPath=$OutputPath
        startedPath=$StartedPath
    }
    Write-CanonicalJson $failure $DiagnosticPath
    throw
}
