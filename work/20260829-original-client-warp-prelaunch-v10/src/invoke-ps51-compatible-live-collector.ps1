[CmdletBinding()]
param(
 [Parameter(Mandatory=$true)][string]$CollectorPath,
 [Parameter(Mandatory=$true)][string]$ExpectedCollectorSha256,
 [Parameter(Mandatory=$true)][int]$TargetProcessId,
 [Parameter(Mandatory=$true)][string]$ExpectedStartTimeUtc,
 [Parameter(Mandatory=$true)][string]$ExpectedExecutableSha256,
 [Parameter(Mandatory=$true)][string]$ExpectedWindowHandle,
 [Parameter(Mandatory=$true)][string]$OutputPath
)
$ErrorActionPreference='Stop'
$allowed=@(
 '7D953A45691C464FB60800AB470D0D9BCFECD5648BA201D88247CE140C8DAFBC',
 '9B475E5D171FC34A9C69322E29A80D59F7FB9199B0B8653D435CFEAC72A4494C'
)
$expected=$ExpectedCollectorSha256.ToUpperInvariant();if($allowed-notcontains$expected){throw 'collector hash is not allowlisted'}
$actual=(Get-FileHash -LiteralPath $CollectorPath -Algorithm SHA256).Hash;if($actual-ne$expected){throw "collector hash mismatch expected=$expected actual=$actual"}
$modulePath=Join-Path $PSScriptRoot 'Ps51CollectorCompatibility.psm1';if(-not(Test-Path -LiteralPath $modulePath)){throw 'PS5.1 compatibility module missing'};Import-Module $modulePath -Force
$source=Get-Content -LiteralPath $CollectorPath -Raw -Encoding UTF8;$patched=ConvertTo-Ps51CollectorSource -Source $source;$scriptBlock=[scriptblock]::Create($patched)
$arguments=@{TargetProcessId=$TargetProcessId;ExpectedStartTimeUtc=$ExpectedStartTimeUtc;ExpectedExecutableSha256=$ExpectedExecutableSha256;ExpectedWindowHandle=$ExpectedWindowHandle;OutputPath=$OutputPath}
& $scriptBlock @arguments
