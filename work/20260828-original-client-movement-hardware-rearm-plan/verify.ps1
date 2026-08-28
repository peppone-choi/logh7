$ErrorActionPreference='Stop'
$unit=$PSScriptRoot
$hardwareTests=& (Join-Path $unit 'tests/test-hardware-rearm-plan.ps1')|ConvertFrom-Json
if($hardwareTests.result-ne'PASS'-or$hardwareTests.cases-ne39-or$hardwareTests.assertions-ne59-or$hardwareTests.mutations-ne38){throw 'hardware rearm tests failed or drifted'}
$v7Tests=& (Join-Path $unit 'tests/test-prelaunch-v7-hardware-rearm.ps1')|ConvertFrom-Json
if($v7Tests.result-ne'PASS'-or$v7Tests.cases-ne21-or$v7Tests.assertions-ne33-or$v7Tests.mutations-ne20){throw 'prelaunch v7 tests failed or drifted'}
$planResult=& (Join-Path $unit 'src/verify-hardware-rearm-plan.ps1') -PlanPath (Join-Path $unit 'evidence/hardware-rearm-plan.json')|ConvertFrom-Json
$v7Result=& (Join-Path $unit 'src/verify-prelaunch-v7-hardware-rearm.ps1') -ContractPath (Join-Path $unit 'evidence/prelaunch-v7-hardware-rearm.json')|ConvertFrom-Json
if($planResult.result-ne'PASS'-or$planResult.state-ne'OFFLINE_HARDWARE_REARM_PLAN_PASS_RECEIPT_SCHEMA_GAP'-or$planResult.peakActiveSlots-ne4-or$planResult.runtimeNoMissProof-ne'MISSING'-or$planResult.receiptV2MissingFieldCount-ne8-or$planResult.liveInstallEligible){throw 'hardware rearm semantic verification failed'}
if($v7Result.result-ne'PASS'-or$v7Result.firstTechnicalBoundary-ne'MOVEMENT_RECEIPT_TEMPORAL_THREAD_CORRELATION_SCHEMA_MISSING'-or$v7Result.receiptV2Status-ne'MISSING'-or$v7Result.receiptV2MissingFieldCount-ne8-or-not$v7Result.freshTraceCompared-or-not$v7Result.priorV6SealBound){throw 'prelaunch v7 semantic verification failed'}
$ledgerPath=Join-Path $unit 'evidence/artifact-ledger.json';$ledger=Get-Content -LiteralPath $ledgerPath -Raw -Encoding UTF8|ConvertFrom-Json;$hashMap=[ordered]@{}
foreach($artifact in $ledger.artifacts){$path=Join-Path $unit ([string]$artifact.path);if(-not(Test-Path -LiteralPath $path)){throw "missing artifact $($artifact.path)"};$actual=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash;if($actual-ne([string]$artifact.sha256).ToUpperInvariant()){throw "artifact hash mismatch $($artifact.path)"};$hashMap[$artifact.path]=$actual}
$scripts=@(Get-Content -LiteralPath (Join-Path $unit 'src/verify-hardware-rearm-plan.ps1') -Raw -Encoding UTF8;Get-Content -LiteralPath (Join-Path $unit 'src/verify-prelaunch-v7-hardware-rearm.ps1') -Raw -Encoding UTF8)-join"`n"
$forbidden=@('WriteProcessMemory','SendInput','SetCursorPos','PostMessage','mouse_event','keybd_event','VirtualAllocEx','CreateRemoteThread','Invoke-VMScript','Start-VM','Stop-VM','vmrun');$hits=@($forbidden|Where-Object{$scripts.Contains($_)})
if($hits.Count){throw "forbidden executable capability: $($hits-join', ')"}
[ordered]@{
 result='PASS';hardwareRearmTests=$hardwareTests;prelaunchV7Tests=$v7Tests;plan=$planResult;contract=$v7Result
 artifactHashesVerified=@($ledger.artifacts).Count;artifactLedgerSha256=(Get-FileHash -LiteralPath $ledgerPath -Algorithm SHA256).Hash;artifactHashMap=$hashMap
 installedX32dbgCommit=$planResult.installedX32dbgCommit;peakActiveSlots=$planResult.peakActiveSlots;receiptV2Status=$v7Result.receiptV2Status
 forbiddenCapabilityHits=0;liveOperations=0;processMemoryReads=0;gameInputs=0;permitIssued=$false
 status='OFFLINE_HARDWARE_REARM_PLAN_PASS_RECEIPT_SCHEMA_GAP_RUNTIME_UNSEEN'
}|ConvertTo-Json -Depth 20
