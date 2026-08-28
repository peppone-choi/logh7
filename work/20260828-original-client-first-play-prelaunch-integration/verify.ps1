$ErrorActionPreference='Stop'
$root=$PSScriptRoot
$tests=& (Join-Path $root 'tests/test-first-play-prelaunch-integration.ps1')|ConvertFrom-Json
$contractResult=& (Join-Path $root 'src/verify-first-play-prelaunch-integration.ps1') -ContractPath (Join-Path $root 'evidence/first-play-prelaunch-integration.json')|ConvertFrom-Json

function Get-CommandNames([string]$Path) {
    $tokens=$null;$parseErrors=$null
    $ast=[Management.Automation.Language.Parser]::ParseFile($Path,[ref]$tokens,[ref]$parseErrors)
    if(@($parseErrors).Count){throw "parse errors in $Path"}
    @($ast.FindAll({param($node)$node-is[Management.Automation.Language.CommandAst]},$true)|ForEach-Object{$_.GetCommandName()}|Where-Object{$_}|Sort-Object -Unique)
}

$productionScripts=@((Join-Path $root 'verify.ps1'),(Join-Path $root 'src/verify-first-play-prelaunch-integration.ps1'))
$testScripts=@((Join-Path $root 'tests/test-first-play-prelaunch-integration.ps1'))
$productionCommands=@($productionScripts|ForEach-Object{Get-CommandNames $_}|Sort-Object -Unique)
$testCommands=@($testScripts|ForEach-Object{Get-CommandNames $_}|Sort-Object -Unique)
$liveCommands=@('Start-Process','Invoke-VMScript','Invoke-VMRun','vmrun','x32dbg','SendInput','WriteProcessMemory','SetCursorPos','PostMessage','SendMessage')
$writeCommands=@('Set-Content','Out-File','Add-Content','New-Item','Remove-Item','Copy-Item','Move-Item','Rename-Item')
$liveHits=@($productionCommands|Where-Object{$liveCommands-contains$_})
$productionWriteHits=@($productionCommands|Where-Object{$writeCommands-contains$_})
$testTempWriteCommands=@($testCommands|Where-Object{$writeCommands-contains$_})
$expectedTestTempWriteCommands=@('New-Item','Remove-Item','Set-Content')
if((@($testTempWriteCommands)|ConvertTo-Json -Compress)-ne(@($expectedTestTempWriteCommands)|ConvertTo-Json -Compress)){throw 'unexpected test write capability'}
if($tests.result-ne'PASS'-or$contractResult.result-ne'PASS'-or$liveHits.Count-ne0-or$productionWriteHits.Count-ne0){throw 'aggregate verification failed'}
[ordered]@{
 result='PASS';tests=$tests;contract=$contractResult
 ownedScriptCount=$productionScripts.Count+$testScripts.Count
 productionCommandCount=$productionCommands.Count
 liveCapabilityHits=@($liveHits)
 productionWriteCapabilityHits=@($productionWriteHits)
 testTemporaryWriteCommands=@($testTempWriteCommands)
 forbiddenCapabilityHits=0;validatorWrites=0;liveOperations=0;gameInputs=0;permitIssued=$false
}|ConvertTo-Json -Depth 10
