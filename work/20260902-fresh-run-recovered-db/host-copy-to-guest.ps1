[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$HostPath,
    [Parameter(Mandatory=$true)][string]$GuestPath,
    [string]$VixSourcePath = (Join-Path $PSScriptRoot 'fresh-run-vix.cs'),
    [string]$Vmx = 'E:\logh7-vms\oracle-win11-hd-re\oracle-win11-hd-re.vmx',
    [string]$VixDirectory = 'C:\Program Files (x86)\VMware\VMware VIX',
    [string]$SecretPath = 'E:\logh7-vms\oracle-win11-hd\.secrets\guest.dpapi',
    [string]$GuestUser = 'logh7-oracle'
)
# Copies ONE host file into the guest (VIX CopyFileFromHostToGuest). Used to stage data payloads (zips) next to
# step scripts; the guest step verifies the payload hash before using it.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $HostPath)) { throw 'HOST_PATH_MISSING' }
$running = @(& 'C:\Program Files (x86)\VMware\VMware Workstation\vmrun.exe' -T ws list | Select-Object -Skip 1 | Where-Object { ([string]$_).Trim() -ceq $Vmx })
if ($running.Count -ne 1) { throw 'EXACT_VMX_NOT_RUNNING' }
Add-Type -AssemblyName System.Security
$hex = (Get-Content -LiteralPath $SecretPath -Raw -Encoding UTF8).Trim()
$protected = New-Object byte[] ($hex.Length / 2); for ($i = 0; $i -lt $protected.Length; $i++) { $protected[$i] = [Convert]::ToByte($hex.Substring($i * 2, 2), 16) }
$plain = [Security.Cryptography.ProtectedData]::Unprotect($protected, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
$password = [Text.Encoding]::Unicode.GetString($plain).TrimEnd([char]0)
Add-Type -Path $VixSourcePath
[FreshRunVix]::ConfigureLibraryDirectory($VixDirectory)
$session = [FreshRunVix]::new($Vmx, $GuestUser, $password, $true)
try {
    $session.CopyToGuest($HostPath, $GuestPath)
    [ordered]@{ status = 'COPIED'; hostPath = $HostPath; guestPath = $GuestPath; sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $HostPath).Hash; bytes = (Get-Item -LiteralPath $HostPath).Length } | ConvertTo-Json -Compress
} finally { $session.Dispose(); $password = $null; $plain = $null }
