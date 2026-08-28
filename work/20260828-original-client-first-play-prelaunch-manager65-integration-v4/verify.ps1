$ErrorActionPreference='Stop'
$root=$PSScriptRoot
$tests=& (Join-Path $root 'tests/test-prelaunch-manager65-integration-v4.ps1')|ConvertFrom-Json
$contract=& (Join-Path $root 'src/verify-prelaunch-manager65-integration-v4.ps1') -ContractPath (Join-Path $root 'evidence/prelaunch-manager65-integration-v4.json')|ConvertFrom-Json
function Commands([string]$path){$tokens=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile($path,[ref]$tokens,[ref]$errors);if(@($errors).Count){throw"parse errors in $path"};@($ast.FindAll({param($n)$n-is[Management.Automation.Language.CommandAst]},$true)|ForEach-Object{$_.GetCommandName()}|Where-Object{$_}|Sort-Object -Unique)}
$production=@((Join-Path $root 'verify.ps1'),(Join-Path $root 'src/verify-prelaunch-manager65-integration-v4.ps1'))
$test=@((Join-Path $root 'tests/test-prelaunch-manager65-integration-v4.ps1'))
$productionCommands=@($production|ForEach-Object{Commands $_}|Sort-Object -Unique)
$testCommands=@($test|ForEach-Object{Commands $_}|Sort-Object -Unique)
$live=@('Start-Process','Invoke-VMScript','Invoke-VMRun','vmrun','x32dbg','SendInput','WriteProcessMemory','SetCursorPos','PostMessage','SendMessage','ReadProcessMemory','OpenProcess')
$writes=@('Set-Content','Out-File','Add-Content','New-Item','Remove-Item','Copy-Item','Move-Item','Rename-Item')
$liveHits=@($productionCommands|Where-Object{$live-contains$_})
$writeHits=@($productionCommands|Where-Object{$writes-contains$_})
$testTempWrites=@($testCommands|Where-Object{$writes-contains$_})
if(($testTempWrites|ConvertTo-Json -Compress)-ne(@('New-Item','Remove-Item','Set-Content')|ConvertTo-Json -Compress)){throw'unexpected test write capability'}
$ledgerPath=Join-Path $root 'evidence/artifact-ledger.json'
$ledger=Get-Content -LiteralPath $ledgerPath -Raw -Encoding UTF8|ConvertFrom-Json
$hashMap=[ordered]@{}
foreach($artifact in $ledger.artifacts){$path=Join-Path $root ([string]$artifact.path);$actual=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash;if($actual-ne$artifact.sha256){throw"artifact hash mismatch: $($artifact.path)"};$hashMap[$artifact.path]=$actual}
if($tests.result-ne'PASS'-or$tests.cases-ne19-or$tests.assertions-ne32-or$contract.result-ne'PASS'-or$liveHits.Count-ne0-or$writeHits.Count-ne0){throw'aggregate verification failed'}
$transitiveTempWrites=@('v4 mutation-test JSON under system temp','v3 mutation-test JSON under system temp','manager65 test fixtures under system temp','manager65 aggregate capture/resolution replay under system temp')
[ordered]@{result='PASS';tests=$tests;contract=$contract;artifactHashesVerified=$ledger.artifacts.Count;artifactLedgerSha256=(Get-FileHash -LiteralPath $ledgerPath -Algorithm SHA256).Hash;artifactHashMap=$hashMap;productionCommandCount=$productionCommands.Count;localProductionWriteCapabilityHits=$writeHits;localTestTemporaryWriteCommands=$testTempWrites;transitivelyAuthorizedTemporaryWrites=$transitiveTempWrites;persistentWorkspaceWrites=0;forbiddenCapabilityHits=0;liveOperations=0;gameInputs=0;permitIssued=$false}|ConvertTo-Json -Depth 10
