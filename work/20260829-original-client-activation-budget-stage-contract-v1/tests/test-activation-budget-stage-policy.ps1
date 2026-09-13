$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$verifier=Join-Path $root 'src/verify-activation-budget-stage-policy.ps1'
$contract=Join-Path $root 'evidence/activation-budget-stage-policy.json'
if(-not(Test-Path -LiteralPath $verifier)){throw 'RED: activation-budget stage-policy verifier missing'}
if(-not(Test-Path -LiteralPath $contract)){throw 'RED: activation-budget stage-policy contract missing'}
$temp=Join-Path ([IO.Path]::GetTempPath()) ('logh7-activation-policy-'+[guid]::NewGuid().ToString('N'));New-Item -ItemType Directory -Path $temp|Out-Null
$script:cases=0;$script:assertions=0
function Eq($name,$actual,$expected){$script:assertions++;if($actual-ne$expected){throw "$name expected=$expected actual=$actual"}}
function Run($path){$script:cases++;&$verifier -PolicyPath $path|ConvertFrom-Json}
function Variant($name,[scriptblock]$change){$j=Get-Content -LiteralPath $contract -Raw -Encoding UTF8|ConvertFrom-Json;&$change $j;$p=Join-Path $temp ($name+'.json');$j|ConvertTo-Json -Depth 60|Set-Content -LiteralPath $p -Encoding UTF8;$p}
try{
 $r=Run $contract
 Eq 'result' $r.result 'PASS'
 Eq 'state' $r.state 'OFFLINE_POLICY_RESOLVED_STAGE_LOCAL_ONE_ACTIVATION_ONLY'
 Eq 'retired mismatch' $r.retiredMismatch 1
 Eq 'current authority budget' $r.currentAuthorityBudget 1
 Eq 'current live runs remaining' $r.currentLiveRunsRemaining 1
 Eq 'transaction activations' $r.transactionPhysicalActivations 3
 Eq 'current stage' $r.currentStage 'WARP'
 Eq 'current stage compatible' $r.currentStageBudgetCompatible $true
 Eq 'full transaction sufficient' $r.fullTransactionAuthoritySufficient $false
 Eq 'additional activations' $r.additionalPhysicalActivationsRequired 2
 Eq 'automatic chaining' $r.automaticPermitChaining $false
 Eq 'stage permit reusable' $r.stagePermitReusable $false
 Eq 'stop after stage' $r.stopAfterAuthorizedStage $true
 Eq 'live operations' $r.liveOperations 0
 Eq 'permit' $r.permitIssued $false

 $m=[ordered]@{
 schemaVersion={param($j)$j.schemaVersion=2}
  extraRootProperty={param($j)$j|Add-Member -NotePropertyName liveReady -NotePropertyValue $true}
  extraNestedProperty={param($j)$j.stagePolicy|Add-Member -NotePropertyName liveReady -NotePropertyValue $true}
  contractName={param($j)$j.contract='WRONG'}
  state={param($j)$j.state='READY'}
  authoritySource={param($j)$j.currentAuthority.source='SYNTHETIC'}
  authorityScope={param($j)$j.currentAuthority.scope='THREE_ACTIVATIONS'}
  authorityBudgetExpanded={param($j)$j.currentAuthority.activationBudget=3}
  authorityRunsExpanded={param($j)$j.currentAuthority.maxLiveRuns=2}
  authorityConsumed={param($j)$j.currentAuthority.liveRunsConsumed=1;$j.currentAuthority.liveRunsRemaining=0}
  remainingRunFabricated={param($j)$j.currentAuthority.liveRunsRemaining=2}
  reauthorizationOrder={param($j)$j.currentAuthority.reauthorizedAfterPriorConsumption=$false}
  authorityTreatedAsPermit={param($j)$j.currentAuthority.isPermit=$true}
  movementBreakpointAuthorityRemoved={param($j)$j.currentAuthority.allowedBreakpointSets=@('BP01-BP14')}
  broadInstrumentationExtensionRemoved={param($j)$j.currentAuthority.broadApprovalInstrumentationExtension=$false}
  broadApprovalExpandsCaps={param($j)$j.currentAuthority.broadApprovalOverridesActivationOrForbiddenCaps=$true}
  authorityBypassesGates={param($j)$j.currentAuthority.doesNotBypassPrelaunchGates=$false}
  currentAuthorityRecordHash={param($j)$j.boundArtifacts.currentAuthorityRecord.sha256='0'*64}
  chronologyHash={param($j)$j.boundArtifacts.authorityMessageChronology.sha256='0'*64}
  currentAuthorityIdCollision={param($j)$j.currentAuthority.authorityId=$j.historicalAuthority.authorityId}
  priorPermitId={param($j)$j.priorPermit.id='OTHER'}
  priorPermitState={param($j)$j.priorPermit.state='AVAILABLE'}
  priorPermitReusable={param($j)$j.priorPermit.reusable=$true}
  stageMissing={param($j)$j.transaction.stages=@($j.transaction.stages|Select-Object -Skip 1)}
  stageOrder={param($j)$x=$j.transaction.stages[0];$j.transaction.stages[0]=$j.transaction.stages[1];$j.transaction.stages[1]=$x}
  activationCount={param($j)$j.transaction.physicalActivationCount=1}
  technicalModelAsAuthority={param($j)$j.transaction.provenance='USER_AUTHORITY'}
  automaticInput={param($j)$j.transaction.automaticInputBudget=1}
  retryBudget={param($j)$j.transaction.retryBudget=1}
  sameRun={param($j)$j.transaction.sameRunRequired=$false}
  currentStage={param($j)$j.stagePolicy.currentStage='DESTINATION'}
  warpAllocationZero={param($j)$j.stagePolicy.allocation.WARP=0}
  destinationAllocated={param($j)$j.stagePolicy.allocation.DESTINATION=1}
  allocationSum={param($j)$j.stagePolicy.allocationSum=3}
  budgetCompatibility={param($j)$j.stagePolicy.currentStageBudgetCompatible=$false}
  fullTransactionPromoted={param($j)$j.stagePolicy.fullTransactionAuthoritySufficient=$true}
  fullTransactionLaunchPromoted={param($j)$j.stagePolicy.fullTransactionLaunchEligible=$true}
  additionalActivations={param($j)$j.stagePolicy.additionalPhysicalActivationsRequired=0}
  automaticPermitChaining={param($j)$j.stagePolicy.automaticPermitChaining=$true}
  reusableStagePermit={param($j)$j.stagePolicy.stagePermitReusable=$true}
  noStopAfterStage={param($j)$j.stagePolicy.stopAfterAuthorizedStage=$false}
  successClaimsOutbound={param($j)$j.stagePolicy.claimsAllowed+=@('OUTBOUND_0X0B01')}
  successSequenceContinues={param($j)$j.stagePolicy.successSequence+=@('DESTINATION_INPUT')}
  futureAuthorityPromoted={param($j)$j.stagePolicy.futureStageAuthorityStatus='PASS'}
  prepositionedState={param($j)$j.stagePolicy.prepositionedDestinationOrConfirmStateBound=$true}
  retiredBlocker={param($j)$j.ruling.retiredBlocker='OTHER'}
  replacementBlocker={param($j)$j.ruling.replacementFullTransactionBlocker='NONE'}
  authorityExpansion={param($j)$j.ruling.doesNotExpandAuthority=$false}
  stageGateHash={param($j)$j.boundArtifacts.stageGateContract.sha256='0'*64}
  v8Hash={param($j)$j.boundArtifacts.prelaunchV8Contract.sha256='0'*64}
  v8VerifierHash={param($j)$j.boundArtifacts.prelaunchV8Verifier.sha256='0'*64}
  liveOperation={param($j)$j.operations.liveOperations=1}
  physicalInput={param($j)$j.operations.physicalInputs=1}
  permitIssued={param($j)$j.permitIssued=$true}
 }
 foreach($e in $m.GetEnumerator()){Eq ($e.Key+' rejected') (Run (Variant $e.Key $e.Value)).result 'FAIL'}
 [ordered]@{result='PASS';cases=$script:cases;assertions=$script:assertions;mutations=$m.Count}|ConvertTo-Json
}finally{if(Test-Path -LiteralPath $temp){$resolved=(Resolve-Path -LiteralPath $temp).Path;$tempRoot=[IO.Path]::GetFullPath([IO.Path]::GetTempPath());if(-not$resolved.StartsWith($tempRoot,[StringComparison]::OrdinalIgnoreCase)){throw 'unsafe cleanup'};Remove-Item -LiteralPath $resolved -Recurse -Force}}
