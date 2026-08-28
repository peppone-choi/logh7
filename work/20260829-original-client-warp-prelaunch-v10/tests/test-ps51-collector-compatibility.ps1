$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot;$module=Join-Path $root 'src/Ps51CollectorCompatibility.psm1';if(-not(Test-Path -LiteralPath $module)){throw 'RED: PS5.1 collector compatibility module missing'};Import-Module $module -Force
$source=@'
$first=[pscustomobject]@{scaleX=[single]1.0;scaleY=[single]2.0}
if(-not[float]::IsFinite([single]$first.scaleX)-or-not[float]::IsFinite([single]$first.scaleY)){'INVALID'}else{'VALID'}
'@
$patched=ConvertTo-Ps51CollectorSource -Source $source
$n=0;function Eq($name,$actual,$expected){$script:n++;if($actual-ne$expected){throw "$name expected=$expected actual=$actual"}}
Eq 'IsFinite removed' $patched.Contains('::IsFinite') $false
Eq 'IsNaN x present' $patched.Contains('::IsNaN([single]$first.scaleX)') $true
Eq 'IsInfinity y present' $patched.Contains('::IsInfinity([single]$first.scaleY)') $true
$encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($patched));$output=& 'C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe' -NoProfile -EncodedCommand $encoded;if($LASTEXITCODE-ne0){throw 'transformed source failed under Windows PowerShell 5.1'}
Eq 'PS5.1 behavior' (@($output)[-1]) 'VALID'
$bad=$source.Replace('$first.scaleY','$other.scaleY');$rejected=$false;try{$null=ConvertTo-Ps51CollectorSource -Source $bad}catch{$rejected=$true};Eq 'unexpected source rejected' $rejected $true
[ordered]@{result='PASS';cases=2;assertions=$n}|ConvertTo-Json
