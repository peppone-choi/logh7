[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][int]$ExpectedPid,
    [Parameter(Mandatory=$true)][string]$OutputPath,
    [Parameter(Mandatory=$true)][string]$ReceiptPath
)
# Input-free screen capture of the interactive desktop (must run via VIX RunInteractive, session 1).
# Captures the full primary screen and records the expected client process identity and owned windows.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ((Test-Path -LiteralPath $OutputPath) -or (Test-Path -LiteralPath $ReceiptPath)) { throw 'CAPTURE_OUTPUT_EXISTS' }
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms
if (-not ('CaptureNative' -as [type])) { Add-Type -TypeDefinition @'
using System;using System.Runtime.InteropServices;using System.Text;
public static class CaptureNative{
 public delegate bool EnumWindowsProc(IntPtr h,IntPtr p);[StructLayout(LayoutKind.Sequential)]public struct RECT{public int Left,Top,Right,Bottom;}
 [DllImport("user32.dll")]public static extern bool EnumWindows(EnumWindowsProc c,IntPtr p);[DllImport("user32.dll")]public static extern uint GetWindowThreadProcessId(IntPtr h,out uint p);
 [DllImport("user32.dll")]public static extern bool IsWindowVisible(IntPtr h);[DllImport("user32.dll")]public static extern bool GetWindowRect(IntPtr h,out RECT r);
 [DllImport("user32.dll",CharSet=CharSet.Unicode)]public static extern int GetWindowText(IntPtr h,StringBuilder s,int n);[DllImport("user32.dll")]public static extern IntPtr GetForegroundWindow();}
'@ }
$rows = [Collections.Generic.List[object]]::new()
$cb = [CaptureNative+EnumWindowsProc]{ param([IntPtr]$h, [IntPtr]$u); $o = [uint32]0; [void][CaptureNative]::GetWindowThreadProcessId($h, [ref]$o); if ([int]$o -eq $ExpectedPid -and [CaptureNative]::IsWindowVisible($h)) { $rc = [CaptureNative+RECT]::new(); [void][CaptureNative]::GetWindowRect($h, [ref]$rc); $sb = [Text.StringBuilder]::new(256); [void][CaptureNative]::GetWindowText($h, $sb, 256); $rows.Add([ordered]@{ hwnd = ('0x{0:X16}' -f $h.ToInt64()); title = $sb.ToString(); rect = [ordered]@{ left = $rc.Left; top = $rc.Top; right = $rc.Right; bottom = $rc.Bottom } }) }; $true }
[void][CaptureNative]::EnumWindows($cb, [IntPtr]::Zero)
$proc = Get-Process -Id $ExpectedPid -ErrorAction SilentlyContinue
$cim = Get-CimInstance Win32_Process -Filter "ProcessId=$ExpectedPid" -ErrorAction SilentlyContinue
$bounds = [Windows.Forms.Screen]::PrimaryScreen.Bounds
$bmp = New-Object Drawing.Bitmap $bounds.Width, $bounds.Height
$g = [Drawing.Graphics]::FromImage($bmp)
try { $g.CopyFromScreen($bounds.Location, [Drawing.Point]::Empty, $bounds.Size) } finally { $g.Dispose() }
$nonBlack = 0; $step = 8
for ($y = 0; $y -lt $bmp.Height; $y += $step) { for ($x = 0; $x -lt $bmp.Width; $x += $step) { $c = $bmp.GetPixel($x, $y); if (($c.R + $c.G + $c.B) -gt 30) { $nonBlack++ } } }
$sampled = [int][Math]::Ceiling($bmp.Height / $step) * [int][Math]::Ceiling($bmp.Width / $step)
$bmp.Save($OutputPath, [Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose()
$fg = [CaptureNative]::GetForegroundWindow(); $fgPid = [uint32]0; [void][CaptureNative]::GetWindowThreadProcessId($fg, [ref]$fgPid)
$receipt = [ordered]@{
    status = 'GUEST_DESKTOP_CAPTURED'; capturedAtUtc = [datetime]::UtcNow.ToString('o'); sessionId = [Diagnostics.Process]::GetCurrentProcess().SessionId
    client = [ordered]@{ pid = $ExpectedPid; alive = ($null -ne $proc); path = $(if ($cim) { $cim.ExecutablePath } else { $null }); sha256 = $(if ($cim -and $cim.ExecutablePath) { (Get-FileHash -LiteralPath $cim.ExecutablePath -Algorithm SHA256).Hash } else { $null }); windows = @($rows); tcp = @(Get-NetTCPConnection -OwningProcess $ExpectedPid -ErrorAction SilentlyContinue | ForEach-Object { "$($_.LocalAddress):$($_.LocalPort)->$($_.RemoteAddress):$($_.RemotePort) $($_.State)" }) }
    foreground = [ordered]@{ hwnd = ('0x{0:X16}' -f $fg.ToInt64()); pid = [int]$fgPid; isClient = ([int]$fgPid -eq $ExpectedPid) }
    screen = [ordered]@{ width = $bounds.Width; height = $bounds.Height; sampledPixels = $sampled; nonBlackSampled = $nonBlack }
    outputSha256 = (Get-FileHash -LiteralPath $OutputPath -Algorithm SHA256).Hash
    operations = [ordered]@{ gameInputs = 0; inputRetries = 0 }
}
[IO.File]::WriteAllText($ReceiptPath, (($receipt | ConvertTo-Json -Depth 8) -replace "`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
exit 0
