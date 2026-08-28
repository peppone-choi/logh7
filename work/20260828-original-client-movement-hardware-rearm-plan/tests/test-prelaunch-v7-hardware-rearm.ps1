$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$verifier=Join-Path $root 'src/verify-prelaunch-v7-hardware-rearm.ps1'
$contract=Join-Path $root 'evidence/prelaunch-v7-hardware-rearm.json'
if(-not(Test-Path -LiteralPath $verifier)){throw 'RED: prelaunch v7 verifier missing'}
if(-not(Test-Path -LiteralPath $contract)){throw 'RED: prelaunch v7 contract missing'}
$temp=Join-Path ([IO.Path]::GetTempPath()) ('logh7-hw-v7-'+[guid]::NewGuid().ToString('N'));New-Item -ItemType Directory -Path $temp|Out-Null
$script:cases=0;$script:assertions=0
function Eq($name,$actual,$expected){$script:assertions++;if($actual-ne$expected){throw "$name expected=$expected actual=$actual"}}
function Run($path){$script:cases++;&$verifier -ContractPath $path|ConvertFrom-Json}
function Variant($name,[scriptblock]$change){$j=Get-Content -LiteralPath $contract -Raw -Encoding UTF8|ConvertFrom-Json;&$change $j;$p=Join-Path $temp ($name+'.json');$j|ConvertTo-Json -Depth 50|Set-Content -LiteralPath $p -Encoding UTF8;$p}
try{
 $r=Run $contract
 Eq 'result' $r.result 'PASS';Eq 'state' $r.state 'OFFLINE_PRELAUNCH_HARDWARE_REARM_PLAN_INTEGRATED_RECEIPT_V2_MISSING_READY_FALSE';Eq 'blockers' $r.blockerCount 12;Eq 'first policy' $r.firstMissingBoundary 'ACTIVATION_BUDGET_STAGE_CONTRACT_MISMATCH';Eq 'first technical' $r.firstTechnicalBoundary 'MOVEMENT_RECEIPT_TEMPORAL_THREAD_CORRELATION_SCHEMA_MISSING';Eq 'rearm resolved' $r.hardwareRearmPlanResolved 1;Eq 'receipt v2' $r.receiptV2Status 'MISSING';Eq 'receipt fields' $r.receiptV2MissingFieldCount 8;Eq 'fresh trace' $r.freshTraceCompared $true;Eq 'v6 seal' $r.priorV6SealBound $true;Eq 'live operations' $r.liveOperations 0;Eq 'inputs' $r.gameInputs 0;Eq 'permit' $r.permitIssued $false
 $mutations=[ordered]@{
  oldRearmBlocker={param($j)$j.blockers[1]='MOVEMENT_HARDWARE_BREAKPOINT_REARM_PLAN_MISSING'}
  receiptGapRemoved={param($j)$j.blockers=@($j.blockers|Where-Object{$_-ne'MOVEMENT_RECEIPT_TEMPORAL_THREAD_CORRELATION_SCHEMA_MISSING'})}
  blockerOrder={param($j)$x=$j.blockers[1];$j.blockers[1]=$j.blockers[2];$j.blockers[2]=$x}
  resolvedDelta={param($j)$j.integrationDelta.resolvedStaticBlockers=@()}
  introducedDelta={param($j)$j.integrationDelta.introducedStaticBlockers=@()}
  planPromoted={param($j)$j.staticPreparation.hardwareRearm.runtimeObserved='PASS'}
  perThreadPromoted={param($j)$j.staticPreparation.hardwareRearm.livePerThreadDrReceipt='PASS'}
  receiptV2Promoted={param($j)$j.staticPreparation.receiptV2.status='PASS'}
  missingFieldRemoved={param($j)$j.staticPreparation.receiptV2.missingFields=@($j.staticPreparation.receiptV2.missingFields|Select-Object -Skip 1)}
  installedHash={param($j)$j.staticPreparation.hardwareRearm.installedX32dbgExeSha256='0'*64}
  installedCommitHash={param($j)$j.staticPreparation.hardwareRearm.installedCommitHashFileSha256='0'*64}
  noMissPromoted={param($j)$j.staticPreparation.hardwareRearm.runtimeNoMissProof='PASS'}
  artifactHash={param($j)$j.boundArtifacts.hardwareRearmPlan.sha256='0'*64}
  priorPermit={param($j)$j.priorPermit.state='ACTIVE';$j.priorPermit.reusable=$true}
  activationBudget={param($j)$j.currentAuthority.activationBudget=3}
  launchEligible={param($j)$j.launchEligible=$true}
  permitEligible={param($j)$j.permitEligible=$true}
  debuggerOperation={param($j)$j.operationCounters.debuggerCommands=1}
  inputOperation={param($j)$j.operationCounters.physicalInputs=1}
  forbidden={param($j)$j.forbidden[1]='software INT3 allowed'}
 }
 foreach($entry in $mutations.GetEnumerator()){Eq ($entry.Key+' rejected') (Run (Variant $entry.Key $entry.Value)).result 'FAIL'}
 [ordered]@{result='PASS';cases=$script:cases;assertions=$script:assertions;mutations=$mutations.Count}|ConvertTo-Json
}finally{if(Test-Path -LiteralPath $temp){$resolved=(Resolve-Path -LiteralPath $temp).Path;$base=[IO.Path]::GetFullPath([IO.Path]::GetTempPath());if(-not$resolved.StartsWith($base,[StringComparison]::OrdinalIgnoreCase)){throw 'unsafe cleanup'};Remove-Item -LiteralPath $resolved -Recurse -Force}}
