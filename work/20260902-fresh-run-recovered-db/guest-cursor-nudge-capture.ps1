[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][int]$ExpectedPid,
    [Parameter(Mandatory=$true)][string]$ExpectedHwnd,
    [Parameter(Mandatory=$true)][string]$OutputPath,
    [Parameter(Mandatory=$true)][string]$ReceiptPath,
    [int]$SettleMilliseconds = 1500
)
# Moves the cursor over the client area (two SetCursorPos steps, no button, no key) so the client's message
# loop receives WM_MOUSEMOVE, then captures the window (PrintWindow) and the whole screen (GDI).
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ((Test-Path -LiteralPath $OutputPath) -or (Test-Path -LiteralPath $ReceiptPath)) { throw 'OUTPUT_EXISTS' }
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms
if (-not ('NudgeNative' -as [type])) { Add-Type -TypeDefinition @'
using System;using System.Runtime.InteropServices;
public static class NudgeNative{
 [StructLayout(LayoutKind.Sequential)]public struct RECT{public int Left,Top,Right,Bottom;}[StructLayout(LayoutKind.Sequential)]public struct POINT{public int X,Y;}
 [DllImport("user32.dll")]public static extern bool IsWindow(IntPtr h);[DllImport("user32.dll")]public static extern uint GetWindowThreadProcessId(IntPtr h,out uint p);
 [DllImport("user32.dll")]public static extern bool GetWindowRect(IntPtr h,out RECT r);[DllImport("user32.dll")]public static extern bool GetClientRect(IntPtr h,out RECT r);
 [DllImport("user32.dll")]public static extern bool ClientToScreen(IntPtr h,ref POINT p);[DllImport("user32.dll")]public static extern bool SetCursorPos(int x,int y);
 [DllImport("user32.dll")]public static extern bool GetCursorPos(out POINT p);[DllImport("user32.dll")]public static extern bool PrintWindow(IntPtr h,IntPtr hdc,uint flags);
 [DllImport("user32.dll")]public static extern IntPtr GetForegroundWindow();[DllImport("user32.dll")]public static extern short GetAsyncKeyState(int key);}
'@ }
$hwnd = [IntPtr][Convert]::ToInt64($ExpectedHwnd.Substring(2), 16)
if (-not [NudgeNative]::IsWindow($hwnd)) { throw 'HWND_INVALID' }
$o = [uint32]0; [void][NudgeNative]::GetWindowThreadProcessId($hwnd, [ref]$o); if ([int]$o -ne $ExpectedPid) { throw 'HWND_OWNER_MISMATCH' }
if ([NudgeNative]::GetAsyncKeyState(1) -lt 0) { throw 'LEFT_BUTTON_ALREADY_DOWN' }
$wr = [NudgeNative+RECT]::new(); [void][NudgeNative]::GetWindowRect($hwnd, [ref]$wr)
$cr = [NudgeNative+RECT]::new(); [void][NudgeNative]::GetClientRect($hwnd, [ref]$cr)
$origin = [NudgeNative+POINT]::new(); [void][NudgeNative]::ClientToScreen($hwnd, [ref]$origin)
$cx = $origin.X + [int](($cr.Right - $cr.Left) / 2); $cy = $origin.Y + [int](($cr.Bottom - $cr.Top) / 2)
$before = [NudgeNative+POINT]::new(); [void][NudgeNative]::GetCursorPos([ref]$before)
if (-not [NudgeNative]::SetCursorPos($cx, $cy)) { throw 'SET_CURSOR_FAILED' }
Start-Sleep -Milliseconds 300
[void][NudgeNative]::SetCursorPos($cx + 3, $cy + 2)
Start-Sleep -Milliseconds 300
[void][NudgeNative]::SetCursorPos($cx, $cy)
Start-Sleep -Milliseconds $SettleMilliseconds
$after = [NudgeNative+POINT]::new(); [void][NudgeNative]::GetCursorPos([ref]$after)
$w = $wr.Right - $wr.Left; $h = $wr.Bottom - $wr.Top
function Stats([Drawing.Bitmap]$b) { $n = 0; $nw = 0; $nb = 0; for ($y = 0; $y -lt $b.Height; $y += 6) { for ($x = 0; $x -lt $b.Width; $x += 6) { $c = $b.GetPixel($x, $y); $s = $c.R + $c.G + $c.B; $n++; if ($s -lt 720) { $nw++ }; if ($s -gt 30) { $nb++ } } }; [ordered]@{ sampled = $n; nonWhite = $nw; nonBlack = $nb } }
$bmp = New-Object Drawing.Bitmap $w, $h; $g = [Drawing.Graphics]::FromImage($bmp); $hdc = $g.GetHdc()
try { $pw = [NudgeNative]::PrintWindow($hwnd, $hdc, 2) } finally { $g.ReleaseHdc($hdc); $g.Dispose() }
$pwStats = Stats $bmp; $bmp.Save($OutputPath, [Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose()
$bounds = [Windows.Forms.Screen]::PrimaryScreen.Bounds
$full = New-Object Drawing.Bitmap $bounds.Width, $bounds.Height; $g2 = [Drawing.Graphics]::FromImage($full); try { $g2.CopyFromScreen($bounds.Location, [Drawing.Point]::Empty, $bounds.Size) } finally { $g2.Dispose() }
$fullPath = [IO.Path]::ChangeExtension($OutputPath, '.screen.png'); $full.Save($fullPath, [Drawing.Imaging.ImageFormat]::Png); $full.Dispose()
$fg = [NudgeNative]::GetForegroundWindow()
$receipt = [ordered]@{ status = 'CURSOR_NUDGE_CAPTURED'; capturedAtUtc = [datetime]::UtcNow.ToString('o'); sessionId = [Diagnostics.Process]::GetCurrentProcess().SessionId; pid = $ExpectedPid; hwnd = $ExpectedHwnd; cursorBefore = [ordered]@{ x = $before.X; y = $before.Y }; cursorTarget = [ordered]@{ x = $cx; y = $cy }; cursorAfter = [ordered]@{ x = $after.X; y = $after.Y }; foregroundIsClient = ($fg -eq $hwnd); printWindowResult = $pw; printWindow = $pwStats; printWindowSha256 = (Get-FileHash -LiteralPath $OutputPath -Algorithm SHA256).Hash; screenSha256 = (Get-FileHash -LiteralPath $fullPath -Algorithm SHA256).Hash; tcp = @(Get-NetTCPConnection -OwningProcess $ExpectedPid -ErrorAction SilentlyContinue | ForEach-Object { "$($_.LocalAddress):$($_.LocalPort)->$($_.RemoteAddress):$($_.RemotePort) $($_.State)" }); operations = [ordered]@{ gameInputs = 0; clicks = 0; keyEvents = 0; cursorMoves = 3; inputRetries = 0 } }
[IO.File]::WriteAllText($ReceiptPath, (($receipt | ConvertTo-Json -Depth 6) -replace "`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
exit 0
