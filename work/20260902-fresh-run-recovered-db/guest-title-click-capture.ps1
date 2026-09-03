[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][int]$ExpectedPid,
    [Parameter(Mandatory=$true)][string]$ExpectedHwnd,
    [Parameter(Mandatory=$true)][string]$OutputPath,
    [Parameter(Mandatory=$true)][string]$ReceiptPath,
    [int]$TitleX = 300,
    [int]$TitleY = 15,
    [int]$SettleMilliseconds = 2000
)
# One left click on the client window's TITLE BAR (non-client area, not a game input), the same wake action
# that produced the login surface in run 20260829T081704Z, then window + screen capture.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ((Test-Path -LiteralPath $OutputPath) -or (Test-Path -LiteralPath $ReceiptPath)) { throw 'OUTPUT_EXISTS' }
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms
if (-not ('TitleClickNative' -as [type])) { Add-Type -TypeDefinition @'
using System;using System.Runtime.InteropServices;
public static class TitleClickNative{
 [StructLayout(LayoutKind.Sequential)]public struct RECT{public int Left,Top,Right,Bottom;}[StructLayout(LayoutKind.Sequential)]public struct POINT{public int X,Y;}
 [DllImport("user32.dll")]public static extern bool IsWindow(IntPtr h);[DllImport("user32.dll")]public static extern uint GetWindowThreadProcessId(IntPtr h,out uint p);
 [DllImport("user32.dll")]public static extern bool GetWindowRect(IntPtr h,out RECT r);[DllImport("user32.dll")]public static extern bool GetClientRect(IntPtr h,out RECT r);
 [DllImport("user32.dll")]public static extern bool ClientToScreen(IntPtr h,ref POINT p);[DllImport("user32.dll")]public static extern bool SetCursorPos(int x,int y);
 [DllImport("user32.dll")]public static extern bool GetCursorPos(out POINT p);[DllImport("user32.dll")]public static extern void mouse_event(uint flags,uint dx,uint dy,uint data,UIntPtr extra);
 [DllImport("user32.dll")]public static extern bool PrintWindow(IntPtr h,IntPtr hdc,uint flags);[DllImport("user32.dll")]public static extern IntPtr GetForegroundWindow();
 [DllImport("user32.dll")]public static extern short GetAsyncKeyState(int key);[DllImport("user32.dll")]public static extern IntPtr WindowFromPoint(POINT p);}
'@ }
$hwnd = [IntPtr][Convert]::ToInt64($ExpectedHwnd.Substring(2), 16)
if (-not [TitleClickNative]::IsWindow($hwnd)) { throw 'HWND_INVALID' }
$o = [uint32]0; [void][TitleClickNative]::GetWindowThreadProcessId($hwnd, [ref]$o); if ([int]$o -ne $ExpectedPid) { throw 'HWND_OWNER_MISMATCH' }
if ([TitleClickNative]::GetAsyncKeyState(1) -lt 0) { throw 'LEFT_BUTTON_ALREADY_DOWN' }
$wr = [TitleClickNative+RECT]::new(); [void][TitleClickNative]::GetWindowRect($hwnd, [ref]$wr)
$origin = [TitleClickNative+POINT]::new(); [void][TitleClickNative]::ClientToScreen($hwnd, [ref]$origin)
$x = $wr.Left + $TitleX; $y = $wr.Top + $TitleY
if ($y -ge $origin.Y) { throw "TITLE_POINT_INSIDE_CLIENT_AREA:$y>=$($origin.Y)" }
$pt = [TitleClickNative+POINT]::new(); $pt.X = $x; $pt.Y = $y
$under = [TitleClickNative]::WindowFromPoint($pt); $uo = [uint32]0; [void][TitleClickNative]::GetWindowThreadProcessId($under, [ref]$uo)
if ([int]$uo -ne $ExpectedPid) { throw "TITLE_POINT_NOT_OVER_CLIENT_WINDOW:pid=$uo" }
if (-not [TitleClickNative]::SetCursorPos($x, $y)) { throw 'SET_CURSOR_FAILED' }
Start-Sleep -Milliseconds 200
[TitleClickNative]::mouse_event(0x0002, 0, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 120; [TitleClickNative]::mouse_event(0x0004, 0, 0, 0, [UIntPtr]::Zero)
$clickedAt = [datetime]::UtcNow.ToString('o')
Start-Sleep -Milliseconds $SettleMilliseconds
$after = [TitleClickNative+POINT]::new(); [void][TitleClickNative]::GetCursorPos([ref]$after)
[void][TitleClickNative]::GetWindowRect($hwnd, [ref]$wr)
$w = $wr.Right - $wr.Left; $h = $wr.Bottom - $wr.Top
function Stats([Drawing.Bitmap]$b) { $n = 0; $nw = 0; $nb = 0; for ($yy = 0; $yy -lt $b.Height; $yy += 6) { for ($xx = 0; $xx -lt $b.Width; $xx += 6) { $c = $b.GetPixel($xx, $yy); $s = $c.R + $c.G + $c.B; $n++; if ($s -lt 720) { $nw++ }; if ($s -gt 30) { $nb++ } } }; [ordered]@{ sampled = $n; nonWhite = $nw; nonBlack = $nb } }
$bmp = New-Object Drawing.Bitmap $w, $h; $g = [Drawing.Graphics]::FromImage($bmp); $hdc = $g.GetHdc()
try { $pw = [TitleClickNative]::PrintWindow($hwnd, $hdc, 2) } finally { $g.ReleaseHdc($hdc); $g.Dispose() }
$pwStats = Stats $bmp; $bmp.Save($OutputPath, [Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose()
$bounds = [Windows.Forms.Screen]::PrimaryScreen.Bounds
$full = New-Object Drawing.Bitmap $bounds.Width, $bounds.Height; $g2 = [Drawing.Graphics]::FromImage($full); try { $g2.CopyFromScreen($bounds.Location, [Drawing.Point]::Empty, $bounds.Size) } finally { $g2.Dispose() }
$fullPath = [IO.Path]::ChangeExtension($OutputPath, '.screen.png'); $full.Save($fullPath, [Drawing.Imaging.ImageFormat]::Png); $full.Dispose()
$fg = [TitleClickNative]::GetForegroundWindow()
$receipt = [ordered]@{ status = 'ONE_TITLE_BAR_CLICK_SENT_AND_CAPTURED'; clickedAtUtc = $clickedAt; capturedAtUtc = [datetime]::UtcNow.ToString('o'); sessionId = [Diagnostics.Process]::GetCurrentProcess().SessionId; pid = $ExpectedPid; hwnd = $ExpectedHwnd; clickPoint = [ordered]@{ x = $x; y = $y; area = 'title-bar-non-client' }; cursorAfter = [ordered]@{ x = $after.X; y = $after.Y }; windowRectAfter = [ordered]@{ left = $wr.Left; top = $wr.Top; right = $wr.Right; bottom = $wr.Bottom }; foregroundIsClient = ($fg -eq $hwnd); printWindowResult = $pw; printWindow = $pwStats; printWindowSha256 = (Get-FileHash -LiteralPath $OutputPath -Algorithm SHA256).Hash; screenSha256 = (Get-FileHash -LiteralPath $fullPath -Algorithm SHA256).Hash; tcp = @(Get-NetTCPConnection -OwningProcess $ExpectedPid -ErrorAction SilentlyContinue | ForEach-Object { "$($_.LocalAddress):$($_.LocalPort)->$($_.RemoteAddress):$($_.RemotePort) $($_.State)" }); operations = [ordered]@{ gameInputs = 0; nonClientClicks = 1; keyEvents = 0; inputRetries = 0 } }
[IO.File]::WriteAllText($ReceiptPath, (($receipt | ConvertTo-Json -Depth 6) -replace "`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
exit 0
