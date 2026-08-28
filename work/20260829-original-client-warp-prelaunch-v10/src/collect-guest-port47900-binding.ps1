[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$OutputPath)
$ErrorActionPreference='Stop'
$modulePath=Join-Path $PSScriptRoot 'NetstatPort47900.psm1';if(-not(Test-Path -LiteralPath $modulePath)){throw 'NetstatPort47900 parser module missing'};Import-Module $modulePath -Force
$rows=@(ConvertFrom-NetstatPort47900 -Lines @(& "$env:SystemRoot\System32\netstat.exe" -ano -p tcp))
$processes=[Collections.Generic.List[object]]::new()
foreach($pidValue in @($rows|ForEach-Object{$_.pid}|Sort-Object -Unique)){
  $p=Get-Process -Id $pidValue -ErrorAction SilentlyContinue
  if($null-ne$p){$path=$null;$hash=$null;$start=$null;$commandLine=$null;try{$path=$p.Path}catch{};try{$start=$p.StartTime.ToUniversalTime().ToString('o')}catch{};try{$commandLine=(Get-CimInstance Win32_Process -Filter "ProcessId=$pidValue").CommandLine}catch{};if($path-and(Test-Path -LiteralPath $path)){$hash=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash};$processes.Add([ordered]@{pid=$p.Id;name=$p.ProcessName;path=$path;sha256=$hash;startTimeUtc=$start;commandLine=$commandLine})}
}
$out=[ordered]@{schemaVersion=1;provenance='LIVE_READONLY';observedAtUtc=[DateTime]::UtcNow.ToString('o');computerName=[Environment]::MachineName;port=47900;rows=$rows;processes=$processes;operations=[ordered]@{guestOperations=1;processMemoryReads=0;processMemoryWrites=0;debuggerCommands=0;gameInputs=0;permitIssued=$false}}
$parent=Split-Path -Parent $OutputPath;if($parent-and-not(Test-Path -LiteralPath $parent)){New-Item -ItemType Directory -Path $parent|Out-Null}
[IO.File]::WriteAllText($OutputPath,(($out|ConvertTo-Json -Depth 10)+[Environment]::NewLine),[Text.UTF8Encoding]::new($false))
Write-Output 'GUEST_PORT47900_BINDING_WRITTEN'
