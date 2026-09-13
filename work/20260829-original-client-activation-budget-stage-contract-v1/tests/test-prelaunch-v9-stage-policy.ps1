$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$verifier=Join-Path $root 'src/verify-prelaunch-v9-stage-policy.ps1'
$contract=Join-Path $root 'evidence/prelaunch-v9-stage-policy.json'
if(-not(Test-Path -LiteralPath $verifier)){throw 'RED: prelaunch-v9 stage-policy verifier missing'}
if(-not(Test-Path -LiteralPath $contract)){throw 'RED: prelaunch-v9 stage-policy contract missing'}
$temp=Join-Path ([IO.Path]::GetTempPath()) ('logh7-prelaunch-v9-'+[guid]::NewGuid().ToString('N'));New-Item -ItemType Directory -Path $temp|Out-Null
$script:cases=0;$script:assertions=0
function Eq($name,$actual,$expected){$script:assertions++;if($actual-ne$expected){throw "$name expected=$expected actual=$actual"}}
function Run($path){$script:cases++;&$verifier -ContractPath $path|ConvertFrom-Json}
function Variant($name,[scriptblock]$change){$j=Get-Content -LiteralPath $contract -Raw -Encoding UTF8|ConvertFrom-Json;&$change $j;$p=Join-Path $temp ($name+'.json');$j|ConvertTo-Json -Depth 80|Set-Content -LiteralPath $p -Encoding UTF8;$p}
try{
 $r=Run $contract
 Eq 'result' $r.result 'PASS';Eq 'state' $r.state 'OFFLINE_PRELAUNCH_V9_WARP_STAGE_ONLY_POLICY_RESOLVED_FRESH_RUNTIME_MISSING_READY_FALSE';Eq 'stage' $r.currentStage 'WARP';Eq 'scoped blockers' $r.scopedBlockerCount 8;Eq 'post evidence' $r.postActivationEvidenceCount 7;Eq 'deferred' $r.deferredBoundaryCount 4;Eq 'first missing' $r.firstMissingBoundary 'FRESH_RUN_IDENTITY_MISSING';Eq 'first authority' $r.firstAuthorityBoundary 'FULL_MOVEMENT_TRANSACTION_AUTHORITY_INSUFFICIENT_TWO_ADDITIONAL_PHYSICAL_ACTIVATIONS_REQUIRED';Eq 'MVB01 post-WARP' $r.expectedPostWarpMvb01AcceptedHits 0;Eq 'launch' $r.launchEligible $false;Eq 'permit eligible' $r.permitEligible $false;Eq 'live' $r.liveOperations 0;Eq 'inputs' $r.gameInputs 0;Eq 'permit' $r.permitIssued $false
 Eq 'timing status' $r.stageMvbTimingStatus 'STATIC_EXPECTED_NOT_RUNTIME_OBSERVED'
 $m=[ordered]@{
  schemaVersion={param($j)$j.schemaVersion=8}
  contractName={param($j)$j.contract='WRONG'}
  state={param($j)$j.state='READY'}
  extraRoot={param($j)$j|Add-Member -NotePropertyName liveReady -NotePropertyValue $true}
  oldMismatchRetained={param($j)$j.resolvedPolicyBlockers=@()}
  replacementAuthorityMissing={param($j)$j.deferredFullTransactionBoundaries=@($j.deferredFullTransactionBoundaries|Select-Object -Skip 1)}
  currentStage={param($j)$j.currentStage='DESTINATION'}
  allowedStagesExpanded={param($j)$j.currentAuthority.allowedStages=@('WARP','DESTINATION')}
  prefixExpanded={param($j)$j.currentAuthority.authorizedPrefixLength=3}
  terminalPromoted={param($j)$j.currentAuthority.scopedTerminalState='FULL_MOVEMENT_COMPLETE'}
  fullAuthorized={param($j)$j.currentAuthority.fullTransactionAuthorized=$true}
  autoBudget={param($j)$j.currentAuthority.automaticInputBudget=1}
  retryBudget={param($j)$j.currentAuthority.retryBudget=1}
  permitChaining={param($j)$j.currentAuthority.permitChaining=$true}
  priorPermitReusable={param($j)$j.priorPermit.reusable=$true}
  scopedMissing={param($j)$j.scopedPrelaunchBlockers=@($j.scopedPrelaunchBlockers|Select-Object -Skip 1)}
  scopedOrder={param($j)$x=$j.scopedPrelaunchBlockers[1];$j.scopedPrelaunchBlockers[1]=$j.scopedPrelaunchBlockers[2];$j.scopedPrelaunchBlockers[2]=$x}
  initialInstrumentationMissing={param($j)$j.scopedPrelaunchBlockers[1]='MOVEMENT_RECEIPT_MVB01_MVB09_COMPLETION_MISSING'}
  warpOwnerBeforeActivation={param($j)$j.scopedPrelaunchBlockers+=@('FRESH_WARP_STAGE_OWNER_SNAPSHOT_MISSING')}
  destinationPostEvidenceMissing={param($j)$j.postActivationRequiredEvidence=@($j.postActivationRequiredEvidence|Where-Object{$_-ne'FRESH_DESTINATION_PROJECTION_SNAPSHOT_AND_HIT_REGION_MISSING'})}
  textDialogBeforeDestination={param($j)$j.scopedPrelaunchBlockers+=@('FRESH_TEXTDIALOG_SNAPSHOT_AND_CONFIRM_HIT_REGION_MISSING')}
  Mvb01ClassifiedAsWarpHit={param($j)$j.postActivationAssertions.expectedMvb01AcceptedHits=1}
  Mvb01PhaseAdvanced={param($j)$j.postActivationAssertions.expectedReceiptPhaseOrdinal=1}
  drSetChanged={param($j)$j.postActivationAssertions.expectedActiveAnchorShortIds=@('MVB02','MVB06','MVB08','MVB09')}
  destinationInputContinues={param($j)$j.postActivationAssertions.continueToDestinationInput=$true}
  stopMissing={param($j)$j.postActivationAssertions.stopAndHandoff=$false}
  firstMissing={param($j)$j.firstMissingBoundary='FULL_MOVEMENT_TRANSACTION_AUTHORITY_INSUFFICIENT_TWO_ADDITIONAL_PHYSICAL_ACTIVATIONS_REQUIRED'}
  firstTechnical={param($j)$j.firstTechnicalBoundary='FOREGROUND_PROBE_NOT_RUN'}
  firstAuthority={param($j)$j.firstAuthorityBoundary='NONE'}
  policyHash={param($j)$j.boundArtifacts.activationPolicy.sha256='0'*64}
  timingHash={param($j)$j.boundArtifacts.stageMvbTimingAdjudication.sha256='0'*64}
  v8Hash={param($j)$j.boundArtifacts.prelaunchV8Contract.sha256='0'*64}
  operation={param($j)$j.operationCounters.liveOperations=1}
  input={param($j)$j.operationCounters.physicalInputs=1}
  launch={param($j)$j.launchEligible=$true}
  eligible={param($j)$j.permitEligible=$true}
  permit={param($j)$j.permitIssued=$true}
 }
 foreach($e in $m.GetEnumerator()){Eq ($e.Key+' rejected') (Run (Variant $e.Key $e.Value)).result 'FAIL'}
 [ordered]@{result='PASS';cases=$script:cases;assertions=$script:assertions;mutations=$m.Count}|ConvertTo-Json
}finally{if(Test-Path -LiteralPath $temp){$resolved=(Resolve-Path -LiteralPath $temp).Path;$tempRoot=[IO.Path]::GetFullPath([IO.Path]::GetTempPath());if(-not$resolved.StartsWith($tempRoot,[StringComparison]::OrdinalIgnoreCase)){throw 'unsafe cleanup'};Remove-Item -LiteralPath $resolved -Recurse -Force}}
