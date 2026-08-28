$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$verifier=Join-Path $root 'src/verify-prelaunch-v8-movement-receipt-v2.ps1'
$contract=Join-Path $root 'evidence/prelaunch-v8-movement-receipt-v2.json'
if(-not(Test-Path -LiteralPath $verifier)){throw 'RED: prelaunch v8 verifier missing'}
if(-not(Test-Path -LiteralPath $contract)){throw 'RED: prelaunch v8 contract missing'}
$temp=Join-Path ([IO.Path]::GetTempPath()) ('logh7-move-v8-'+[guid]::NewGuid().ToString('N'));New-Item -ItemType Directory -Path $temp|Out-Null
$script:cases=0;$script:assertions=0
function Eq($n,$a,$e){$script:assertions++;if($a-ne$e){throw "$n expected=$e actual=$a"}}
function Run($p){$script:cases++;&$verifier -ContractPath $p|ConvertFrom-Json}
function Variant($n,[scriptblock]$change){$j=Get-Content -LiteralPath $contract -Raw -Encoding UTF8|ConvertFrom-Json;&$change $j;$p=Join-Path $temp ($n+'.json');$j|ConvertTo-Json -Depth 60|Set-Content -LiteralPath $p -Encoding UTF8;$p}
try{
 $r=Run $contract
 Eq 'result' $r.result 'PASS';Eq 'state' $r.state 'OFFLINE_PRELAUNCH_MOVEMENT_RECEIPT_V2_INTEGRATED_RUNTIME_INSTALL_MISSING_READY_FALSE';Eq 'blockers' $r.blockerCount 12;Eq 'first policy' $r.firstMissingBoundary 'ACTIVATION_BUDGET_STAGE_CONTRACT_MISMATCH';Eq 'first technical' $r.firstTechnicalBoundary 'FRESH_RUN_IDENTITY_MISSING';Eq 'schema resolved' $r.receiptV2SchemaBlockerResolved 1;Eq 'groups' $r.receiptV2FieldGroupCount 8;Eq 'runtime blocker' $r.runtimeReceiptStatus 'MISSING';Eq 'live operations' $r.liveOperations 0;Eq 'inputs' $r.gameInputs 0;Eq 'permit' $r.permitIssued $false
 $m=[ordered]@{
  oldSchemaBlocker={param($j)$j.blockers[1]='MOVEMENT_RECEIPT_TEMPORAL_THREAD_CORRELATION_SCHEMA_MISSING'}
  runtimeBlockerRemoved={param($j)$j.blockers=@($j.blockers|Where-Object{$_-ne'FRESH_MOVEMENT_BREAKPOINT_INSTALL_AND_PER_THREAD_TEMPORAL_RECEIPT_MISSING'})}
  blockerOrder={param($j)$x=$j.blockers[1];$j.blockers[1]=$j.blockers[2];$j.blockers[2]=$x}
  resolvedDelta={param($j)$j.integrationDelta.resolvedStaticBlockers=@()}
  introducedDelta={param($j)$j.integrationDelta.introducedRuntimeBlockers=@()}
  fieldGroups={param($j)$j.staticPreparation.movementReceiptV2.fieldGroupCount=7}
  schemaStatus={param($j)$j.staticPreparation.movementReceiptV2.schemaStatus='MISSING'}
  templatePromoted={param($j)$j.staticPreparation.movementReceiptV2.templateState='LIVE_CAPTURE_REVIEWED_PASS'}
  runtimePromoted={param($j)$j.staticPreparation.movementReceiptV2.runtimeReceiptStatus='PASS'}
  noMissPromoted={param($j)$j.staticPreparation.movementReceiptV2.runtimeNoMissProof='PASS'}
  schemaHash={param($j)$j.boundArtifacts.receiptV2Schema.sha256='0'*64}
  verifierHash={param($j)$j.boundArtifacts.receiptV2Verifier.sha256='0'*64}
  priorV7Hash={param($j)$j.boundArtifacts.priorV7Contract.sha256='0'*64}
  priorPermit={param($j)$j.priorPermit.state='ACTIVE';$j.priorPermit.reusable=$true}
  activationBudget={param($j)$j.currentAuthority.activationBudget=3}
  launchEligible={param($j)$j.launchEligible=$true}
  permitEligible={param($j)$j.permitEligible=$true}
  operation={param($j)$j.operationCounters.debuggerCommands=1}
  forbidden={param($j)$j.forbidden[1]='live install allowed'}
 }
 foreach($e in $m.GetEnumerator()){Eq ($e.Key+' rejected') (Run (Variant $e.Key $e.Value)).result 'FAIL'}
 [ordered]@{result='PASS';cases=$script:cases;assertions=$script:assertions;mutations=$m.Count}|ConvertTo-Json
}finally{if(Test-Path -LiteralPath $temp){$resolved=(Resolve-Path -LiteralPath $temp).Path;$base=[IO.Path]::GetFullPath([IO.Path]::GetTempPath());if(-not$resolved.StartsWith($base,[StringComparison]::OrdinalIgnoreCase)){throw 'unsafe cleanup'};Remove-Item -LiteralPath $resolved -Recurse -Force}}
