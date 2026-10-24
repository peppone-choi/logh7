param(
    [Parameter(Mandatory=$true)][string]$ClientRoot,
    [Parameter(Mandatory=$true)][string]$Dll
)
$ErrorActionPreference = 'Stop'
$work = [IO.Path]::GetFullPath((Join-Path (Split-Path $PSScriptRoot) 'work')) + '\'
$root = [IO.Path]::GetFullPath($ClientRoot).TrimEnd('\')
if (!$root.StartsWith($work, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Only an owned client copy under repository work/ may be changed.'
}
$executable = Join-Path $root 'exe\G7MTClient.exe'
if ((Get-FileHash -LiteralPath $executable -Algorithm SHA256).Hash -ne 'BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16') {
    throw 'Unsupported client executable; no files changed.'
}
$source = (Resolve-Path -LiteralPath $Dll).Path
if (!$source.StartsWith($work, [StringComparison]::OrdinalIgnoreCase)) { throw 'Use the DLL built under work/.' }
$running = Get-CimInstance Win32_Process -Filter "Name='G7MTClient.exe'" |
    Where-Object { $_.ExecutablePath -eq $executable }
if ($running) { throw 'Stop the owned client before changing its compatibility module.' }
$target = Join-Path $root 'exe\dinput8.dll'
if (Test-Path -LiteralPath $target) { throw 'An input proxy already exists. Preserve it outside exe/ before installing.' }
Copy-Item -LiteralPath $source -Destination $target
Write-Output "Installed compatibility module in $target"
