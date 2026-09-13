$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$verifier=Join-Path $root 'src/verify-hardware-rearm-plan.ps1'
$plan=Join-Path $root 'evidence/hardware-rearm-plan.json'
$dryRun=Join-Path $root 'evidence/hardware-rearm-dry-run.json'
if(-not(Test-Path -LiteralPath $verifier)){throw 'RED: hardware rearm verifier missing'}
if(-not(Test-Path -LiteralPath $plan)){throw 'RED: hardware rearm plan missing'}
if(-not(Test-Path -LiteralPath $dryRun)){throw 'RED: hardware rearm dry-run missing'}
$temp=Join-Path ([IO.Path]::GetTempPath()) ('logh7-hw-rearm-'+[guid]::NewGuid().ToString('N'));New-Item -ItemType Directory -Path $temp|Out-Null
$script:cases=0;$script:assertions=0
function Eq($name,$actual,$expected){$script:assertions++;if($actual-ne$expected){throw "$name expected=$expected actual=$actual"}}
function Run($path){$script:cases++;&$verifier -PlanPath $path|ConvertFrom-Json}
function Variant($name,[scriptblock]$change){$j=Get-Content -LiteralPath $plan -Raw -Encoding UTF8|ConvertFrom-Json;&$change $j;$p=Join-Path $temp ($name+'.json');$j|ConvertTo-Json -Depth 40|Set-Content -LiteralPath $p -Encoding UTF8;$p}
try{
 $r=Run $plan
 Eq 'result' $r.result 'PASS';Eq 'state' $r.state 'OFFLINE_HARDWARE_REARM_PLAN_PASS_RECEIPT_SCHEMA_GAP';Eq 'anchors' $r.anchorCount 9;Eq 'slots' $r.maximumConcurrentSlots 4;Eq 'phases' $r.phaseCount 10;Eq 'transitions' $r.transitionCount 9;Eq 'peak' $r.peakActiveSlots 4;Eq 'thread coverage' $r.threadCoverage 'STATIC_SOURCE_INDICATES_ALL_THREAD_PROGRAMMING_RUNTIME_UNSEEN';Eq 'installed commit' $r.installedX32dbgCommit '9c8ca1cae0b6d56cc44f31fddcb10e3b02ffbb87';Eq 'no-miss runtime' $r.runtimeNoMissProof 'MISSING';Eq 'receipt fields' $r.receiptV2MissingFieldCount 8;Eq 'next boundary' $r.nextBoundary 'MOVEMENT_RECEIPT_TEMPORAL_THREAD_CORRELATION_SCHEMA_MISSING';Eq 'live eligible' $r.liveInstallEligible $false;Eq 'operations' $r.liveOperations 0;Eq 'permit' $r.permitIssued $false
 $mutations=[ordered]@{
  targetHash={param($j)$j.target.sha256='A'*64}
  slotLimit={param($j)$j.hardware.maximumConcurrentSlots=5}
  mechanism={param($j)$j.hardware.mechanism='SOFTWARE_INT3'}
  softwareAllowed={param($j)$j.hardware.softwareInt3Allowed=$true}
  memoryWrite={param($j)$j.hardware.processMemoryWritesAllowed=$true}
  singleshoot={param($j)$j.hardware.singleshoot=$true}
  automaticCommands={param($j)$j.hardware.onHitCommandAutomation=$true}
  emptyDelete={param($j)$j.hardware.emptyDeleteAddressAllowed=$true}
  slotDependency={param($j)$j.hardware.slotNumberDependencyAllowed=$true}
  initialMissing={param($j)$j.initial.active=@('MVB01','MVB06','MVB08')}
  initialWrong={param($j)$j.initial.active=@('MVB01','MVB02','MVB03','MVB04')}
  beforeDrift={param($j)$j.transitions[1].activeBefore=@('MVB02','MVB07','MVB08','MVB09')}
  wrongRemove={param($j)$j.transitions[2].remove='MVB02'}
  wrongAdd={param($j)$j.transitions[3].add='MVB07'}
  afterDrift={param($j)$j.transitions[4].activeAfter=@('MVB06','MVB08','MVB09')}
  resumedDuringMutation={param($j)$j.transitions[2].targetStoppedForEntireMutationRequired=$false}
  nextNotVerified={param($j)$j.transitions[3].activeSetVerificationRequiredBeforeResume=$false}
  allThreads={param($j)$j.threadCoverage.sourceImplementationIteratesAllExistingThreads=$false}
  newThread={param($j)$j.threadCoverage.newThreadHandlerAppliesDefinitionsBeforeContinuation=$false}
  selectedThreadOnly={param($j)$j.threadCoverage.selectedThreadOnlyAllowed=$true}
  installedCommit={param($j)$j.installedX32dbg.commit='0'*40}
  installedExe={param($j)$j.installedX32dbg.x32dbgExeSha256='0'*64}
  installedDll={param($j)$j.installedX32dbg.x32dbgDllSha256='0'*64}
  installedGui={param($j)$j.installedX32dbg.x32guiDllSha256='0'*64}
  commitFileHash={param($j)$j.installedX32dbg.commitHashFileSha256='0'*64}
  noMissPromoted={param($j)$j.debuggerSemantics.runtimeNoMissProof='PASS'}
  suspensionPromoted={param($j)$j.debuggerSemantics.debugEventAllThreadsStoppedEvidence='PROVEN'}
  preInstructionPromoted={param($j)$j.debuggerSemantics.executeHardwareBreakpointPreInstructionEvidence='PROVEN'}
  todoHidden={param($j)$j.threadCoverage.installedSourceMultiThreadTodoPresent=$false}
  runtimeClaim={param($j)$j.threadCoverage.multiThreadRuntimeValidation='PASS'}
  sourceCommit={param($j)$j.sources.x64dbg.commit='0'*40}
  titanCommit={param($j)$j.sources.titanEngine.commit='0'*40}
  docHash={param($j)$j.sources.setHardwareDoc.contentSha256='0'*64}
  cleanupRemaining={param($j)$j.transitions[8].activeAfter=@('MVB09')}
  finalResume={param($j)$j.transitions[8].resumeAllowed=$true}
  schemaGapHidden={param($j)$j.receiptGapAudit.status='PASS'}
  syntheticPromoted={param($j)$j.dryRun.runtimeStatus='ORIGINAL_RUNTIME_OBSERVED'}
  operation={param($j)$j.operations.debuggerCommands=1}
 }
 foreach($entry in $mutations.GetEnumerator()){Eq ($entry.Key+' rejected') (Run (Variant $entry.Key $entry.Value)).result 'FAIL'}
 $stored=Get-Content -LiteralPath $dryRun -Raw -Encoding UTF8|ConvertFrom-Json
 Eq 'dry-run result' $stored.result 'PASS';Eq 'dry-run phases' $stored.phaseCount 10;Eq 'dry-run peak' $stored.peakActiveSlots 4;Eq 'dry-run trace' $stored.traceSha256 'FE3E9046EA15DE5E64F1E7CF1159E3D6F3B72CC94C14AB1200E87B2F7243D8CA';Eq 'dry-run runtime' $stored.runtimeStatus 'SYNTHETIC_DRY_RUN_ONLY';Eq 'dry-run live' $stored.liveOperations 0
 [ordered]@{result='PASS';cases=$script:cases;assertions=$script:assertions;mutations=$mutations.Count}|ConvertTo-Json
}finally{if(Test-Path -LiteralPath $temp){$resolved=(Resolve-Path -LiteralPath $temp).Path;$base=[IO.Path]::GetFullPath([IO.Path]::GetTempPath());if(-not$resolved.StartsWith($base,[StringComparison]::OrdinalIgnoreCase)){throw 'unsafe cleanup'};Remove-Item -LiteralPath $resolved -Recurse -Force}}
