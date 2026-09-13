[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateRange(1, [int]::MaxValue)]
    [int] $ProcessId,

    [ValidateRange(1, 60)]
    [int] $WaitSeconds = 10,

    [Parameter(Mandatory)]
    [string] $Output
)

$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.Drawing
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

public static class Logh7WindowCaptureNative
{
    [StructLayout(LayoutKind.Sequential)]
    public struct Rect
    {
        public int Left;
        public int Top;
        public int Right;
        public int Bottom;
    }

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool GetClientRect(IntPtr window, out Rect rect);

    [DllImport("user32.dll")]
    public static extern uint GetDpiForWindow(IntPtr window);

    [DllImport("user32.dll")]
    public static extern uint GetWindowThreadProcessId(IntPtr window, out uint processId);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool PrintWindow(IntPtr window, IntPtr deviceContext, uint flags);
}
'@

$process = Get-Process -Id $ProcessId -ErrorAction Stop
$processStartTime = $process.StartTime.ToUniversalTime()
$deadline = [DateTime]::UtcNow.AddSeconds($WaitSeconds)
$window = [IntPtr]::Zero

do {
    $process = Get-Process -Id $ProcessId -ErrorAction Stop
    if ($process.StartTime.ToUniversalTime() -ne $processStartTime) {
        throw "process $ProcessId no longer refers to the supplied process instance"
    }

    $process.Refresh()
    $window = $process.MainWindowHandle
    if ($window -ne [IntPtr]::Zero) {
        break
    }
    Start-Sleep -Milliseconds 100
} while ([DateTime]::UtcNow -lt $deadline)

if ($window -eq [IntPtr]::Zero) {
    throw "process $ProcessId did not create an owned top-level window within $WaitSeconds seconds"
}

$windowProcessId = [uint32] 0
[void] [Logh7WindowCaptureNative]::GetWindowThreadProcessId($window, [ref] $windowProcessId)
if ($windowProcessId -ne [uint32] $ProcessId) {
    throw "HWND $window belongs to process $windowProcessId, not supplied process $ProcessId"
}

$client = [Logh7WindowCaptureNative+Rect]::new()
if (-not [Logh7WindowCaptureNative]::GetClientRect($window, [ref] $client)) {
    throw "GetClientRect failed for HWND $window"
}

$width = $client.Right - $client.Left
$height = $client.Bottom - $client.Top
if ($width -le 0 -or $height -le 0) {
    throw "HWND $window has invalid client dimensions ${width}x${height}"
}

$outputPath = [System.IO.Path]::GetFullPath($Output)
$outputDirectory = [System.IO.Path]::GetDirectoryName($outputPath)
[System.IO.Directory]::CreateDirectory($outputDirectory) | Out-Null

$bitmap = [System.Drawing.Bitmap]::new($width, $height, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
try {
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $deviceContext = $graphics.GetHdc()
        try {
            $captured = [Logh7WindowCaptureNative]::PrintWindow($window, $deviceContext, 1)
        }
        finally {
            $graphics.ReleaseHdc($deviceContext)
        }
    }
    finally {
        $graphics.Dispose()
    }

    if (-not $captured) {
        throw "PrintWindow failed for HWND $window"
    }
    $bitmap.Save($outputPath, [System.Drawing.Imaging.ImageFormat]::Png)
}
finally {
    $bitmap.Dispose()
}

$captureTime = [DateTime]::UtcNow
$sha256 = (Get-FileHash -LiteralPath $outputPath -Algorithm SHA256).Hash.ToLowerInvariant()
$receiptPath = [System.IO.Path]::ChangeExtension($outputPath, '.json')
$receipt = [ordered]@{
    processId = $ProcessId
    executablePath = $process.Path
    processStartTime = $processStartTime.ToString('o')
    hwnd = $window.ToInt64()
    clientWidth = $width
    clientHeight = $height
    dpi = [Logh7WindowCaptureNative]::GetDpiForWindow($window)
    captureTimestamp = $captureTime.ToString('o')
    sha256 = $sha256
}

$receipt | ConvertTo-Json | Set-Content -LiteralPath $receiptPath -Encoding utf8 -NoNewline
Write-Output $receiptPath
