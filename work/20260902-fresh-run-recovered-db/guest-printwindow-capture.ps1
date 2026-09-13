[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][int]$ExpectedPid,
    [Parameter(Mandatory=$true)][string]$ExpectedHwnd,
    [Parameter(Mandatory=$true)][string]$OutputPath,
    [Parameter(Mandatory=$true)][string]$ReceiptPath
)
# Input-free capture of one window through PrintWindow(PW_RENDERFULLCONTENT) plus a GDI screen copy of the
# same rectangle, so a white/black GDI copy can be distinguished from a window that truly has no content.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ((Test-Path -LiteralPath $OutputPath) -or (Test-Path -LiteralPath $ReceiptPath)) { throw 'OUTPUT_EXISTS' }
Add-Type -AssemblyName System.Drawing
if (-not ('PrintWindowNative' -as [type])) { Add-Type -TypeDefinition @'
using System;using System.Runtime.InteropServices;
public static class PrintWindowNative{
 [StructLayout(LayoutKind.Sequential)]public struct RECT{public int Left,Top,Right,Bottom;}
 [DllImport("user32.dll")]public static extern bool IsWindow(IntPtr h);[DllImport("user32.dll")]public static extern uint GetWindowThreadProcessId(IntPtr h,out uint p);
 [DllImport("user32.dll")]public static extern bool GetWindowRect(IntPtr h,out RECT r);[DllImport("user32.dll")]public static extern bool PrintWindow(IntPtr h,IntPtr hdc,uint flags);
 [DllImport("user32.dll")]public static extern IntPtr GetForegroundWindow();}
'@ }
$hwnd = [IntPtr][Convert]::ToInt64($ExpectedHwnd.Substring(2), 16)
if (-not [PrintWindowNative]::IsWindow($hwnd)) { throw 'HWND_INVALID' }
$o = [uint32]0; [void][PrintWindowNative]::GetWindowThreadProcessId($hwnd, [ref]$o); if ([int]$o -ne $ExpectedPid) { throw 'HWND_OWNER_MISMATCH' }
$r = [PrintWindowNative+RECT]::new(); [void][PrintWindowNative]::GetWindowRect($hwnd, [ref]$r)
$w = $r.Right - $r.Left; $h = $r.Bottom - $r.Top
$bmp = New-Object Drawing.Bitmap $w, $h
$g = [Drawing.Graphics]::FromImage($bmp); $hdc = $g.GetHdc()
try { $pw = [PrintWindowNative]::PrintWindow($hwnd, $hdc, 2) } finally { $g.ReleaseHdc($hdc); $g.Dispose() }
function Stats([Drawing.Bitmap]$b) { $n = 0; $nw = 0; $nb = 0; for ($y = 0; $y -lt $b.Height; $y += 6) { for ($x = 0; $x -lt $b.Width; $x += 6) { $c = $b.GetPixel($x, $y); $s = $c.R + $c.G + $c.B; $n++; if ($s -lt 720) { $nw++ }; if ($s -gt 30) { $nb++ } } }; [ordered]@{ sampled = $n; nonWhite = $nw; nonBlack = $nb } }
$pwStats = Stats $bmp
$bmp.Save($OutputPath, [Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose()
$bmp2 = New-Object Drawing.Bitmap $w, $h
$g2 = [Drawing.Graphics]::FromImage($bmp2); try { $g2.CopyFromScreen($r.Left, $r.Top, 0, 0, [Drawing.Size]::new($w, $h)) } finally { $g2.Dispose() }
$gdiStats = Stats $bmp2
$gdiPath = [IO.Path]::ChangeExtension($OutputPath, '.gdi.png'); $bmp2.Save($gdiPath, [Drawing.Imaging.ImageFormat]::Png); $bmp2.Dispose()
$fg = [PrintWindowNative]::GetForegroundWindow()
$receipt = [ordered]@{ status = 'PRINTWINDOW_CAPTURED'; capturedAtUtc = [datetime]::UtcNow.ToString('o'); sessionId = [Diagnostics.Process]::GetCurrentProcess().SessionId; pid = $ExpectedPid; hwnd = $ExpectedHwnd; printWindowResult = $pw; rect = [ordered]@{ left = $r.Left; top = $r.Top; right = $r.Right; bottom = $r.Bottom }; foregroundIsClient = ($fg -eq $hwnd); printWindow = $pwStats; gdi = $gdiStats; printWindowSha256 = (Get-FileHash -LiteralPath $OutputPath -Algorithm SHA256).Hash; gdiSha256 = (Get-FileHash -LiteralPath $gdiPath -Algorithm SHA256).Hash; tcp = @(Get-NetTCPConnection -OwningProcess $ExpectedPid -ErrorAction SilentlyContinue | ForEach-Object { "$($_.LocalAddress):$($_.LocalPort)->$($_.RemoteAddress):$($_.RemotePort) $($_.State)" }); operations = [ordered]@{ gameInputs = 0; inputRetries = 0 } }
[IO.File]::WriteAllText($ReceiptPath, (($receipt | ConvertTo-Json -Depth 6) -replace "`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
exit 0
