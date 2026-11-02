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
# bh_wave.mdx references bh_a.tga, which no original install or update ships. Its additive
# material then draws as a grey cell-sized square around black holes. Supply a generated,
# fully transparent 128x128 32-bit TGA (no original asset); existing files are kept.
$tga = New-Object byte[] (18 + 128 * 128 * 4)
$tga[2] = 2; $tga[12] = 128; $tga[14] = 128; $tga[16] = 32; $tga[17] = 0x28
foreach ($quality in 'Hi', 'Mid', 'Lo') {
    $folder = Join-Path $root "data\model\images\$quality"
    $texture = Join-Path $folder 'bh_a.tga'
    if ((Test-Path -LiteralPath $folder) -and !(Test-Path -LiteralPath $texture)) {
        [IO.File]::WriteAllBytes($texture, $tga)
        Write-Output "Supplied transparent $texture"
    }
}
