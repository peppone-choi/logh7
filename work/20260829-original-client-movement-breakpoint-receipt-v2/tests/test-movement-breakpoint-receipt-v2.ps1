$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$verifier=Join-Path $root 'src/verify-movement-breakpoint-receipt-v2.ps1'
$template=Join-Path $root 'evidence/movement-breakpoint-receipt-v2-template.json'
$specimen=Join-Path $root 'tests/fixture-v2-semantic-specimen.json'
if(-not(Test-Path -LiteralPath $verifier)){throw 'RED: receipt-v2 verifier missing'}
if(-not(Test-Path -LiteralPath $template)){throw 'RED: receipt-v2 template missing'}
if(-not(Test-Path -LiteralPath $specimen)){throw 'RED: receipt-v2 specimen missing'}
$temp=Join-Path ([IO.Path]::GetTempPath()) ('logh7-move-v2-'+[guid]::NewGuid().ToString('N'));New-Item -ItemType Directory -Path $temp|Out-Null
$script:cases=0;$script:assertions=0
function Eq($name,$actual,$expected){$script:assertions++;if($actual-ne$expected){throw "$name expected=$expected actual=$actual"}}
function Run($path){$script:cases++;&$verifier -ReceiptPath $path|ConvertFrom-Json}
function Variant($name,[scriptblock]$change){$j=Get-Content -LiteralPath $specimen -Raw -Encoding UTF8|ConvertFrom-Json;&$change $j;$p=Join-Path $temp ($name+'.json');$j|ConvertTo-Json -Depth 80|Set-Content -LiteralPath $p -Encoding UTF8;$p}
try{
 $t=Run $template
 Eq 'template result' $t.result 'PASS';Eq 'template state' $t.state 'EMPTY_TEMPLATE_NOT_LIVE';Eq 'template base' $t.baseReceiptState 'EMPTY_TEMPLATE_NOT_LIVE';Eq 'template groups' $t.fieldGroupCount 8;Eq 'template phases' $t.phaseCount 0;Eq 'template hits' $t.acceptedHitCount 0;Eq 'template thread phases' $t.threadPhaseCount 0;Eq 'template queue' $t.uniquePendingExpected0B07 $false;Eq 'template no miss' $t.runtimeNoMissProof 'MISSING';Eq 'template live' $t.liveReceiptEligible $false;Eq 'template operations' $t.liveOperations 0;Eq 'template permit' $t.permitIssued $false
 $s=Run $specimen
 Eq 'specimen result' $s.result 'PASS';Eq 'specimen state' $s.state 'SYNTHETIC_SEMANTIC_SPECIMEN';Eq 'specimen base' $s.baseReceiptState 'SYNTHETIC_SEMANTIC_SPECIMEN';Eq 'specimen groups' $s.fieldGroupCount 8;Eq 'specimen phases' $s.phaseCount 10;Eq 'specimen hits' $s.acceptedHitCount 9;Eq 'specimen thread phases' $s.threadPhaseCount 10;Eq 'specimen rejected' $s.rejectedHitCount 1;Eq 'specimen queue' $s.uniquePendingExpected0B07 $true;Eq 'specimen no miss' $s.runtimeNoMissProof 'MISSING';Eq 'specimen live' $s.liveReceiptEligible $false;Eq 'specimen operations' $s.liveOperations 0;Eq 'specimen permit' $s.permitIssued $false
 $exitPositive=Variant 'exitThreadPositive' {param($j)$j.perThreadDrState.snapshots[9].enumeratedThreadIds=@(4243);$j.perThreadDrState.snapshots[9].threads=@($j.perThreadDrState.snapshots[9].threads|Where-Object{$_.threadId-ne4242});$j.perThreadDrState.lifecycleEvents+=@([pscustomobject]@{debugEventOrdinal=95;eventType='EXIT_THREAD';threadId=4242;appliedAtPhaseOrdinal=9;snapshotId='S09';beforeContinuationVerified=$true})}
 Eq 'exit lifecycle supported' (Run $exitPositive).result 'PASS'
 $dr6ReservedPositive=Variant 'dr6ReservedPositive' {param($j)$j.acceptedHits[0].preCommandDr6='0xFFFF0FF1'}
 Eq 'DR6 reserved status bits tolerated' (Run $dr6ReservedPositive).result 'PASS'
 $m=[ordered]@{
  schemaVersion={param($j)$j.schemaVersion=1}
  extraRootProperty={param($j)$j|Add-Member -NotePropertyName runtimeObserved -NotePropertyValue 'PASS'}
  extraNestedProperty={param($j)$j.acceptedHits[0]|Add-Member -NotePropertyName runtimeObserved -NotePropertyValue 'PASS'}
  baseHash={param($j)$j.baseReceiptBinding.sha256='0'*64}
  baseState={param($j)$j.baseReceiptBinding.expectedState='LIVE_CAPTURE_REVIEWED_PASS'}
  baseVerifierHash={param($j)$j.baseReceiptBinding.verifierSha256='0'*64}
  planHash={param($j)$j.rearmPlanBinding.sha256='0'*64}
  planId={param($j)$j.rearmPlanBinding.planId='WRONG'}
  planTrace={param($j)$j.rearmPlanBinding.traceSha256='0'*64}
  planVersion={param($j)$j.rearmPlanBinding.schemaVersion=2}
  alias={param($j)$j.anchorAliases[2].shortId='MVB04'}
  debuggerExe={param($j)$j.debuggerBuildBinding.files[0].sha256='0'*64}
  debuggerDll={param($j)$j.debuggerBuildBinding.files[1].sha256='0'*64}
  debuggerGui={param($j)$j.debuggerBuildBinding.files[2].sha256='0'*64}
  commitFile={param($j)$j.debuggerBuildBinding.commitHashFile.sha256='0'*64}
  commit={param($j)$j.debuggerBuildBinding.commit='0'*40}
  phaseMissing={param($j)$j.phaseExecutionLedger.phases=@($j.phaseExecutionLedger.phases|Select-Object -Skip 1)}
  phaseOrdinal={param($j)$j.phaseExecutionLedger.phases[3].phaseOrdinal=9}
  phaseEventOrder={param($j)$j.phaseExecutionLedger.phases[4].triggerDebugEventOrdinal=$j.phaseExecutionLedger.phases[3].triggerDebugEventOrdinal}
  snapshotEventMismatch={param($j)$j.perThreadDrState.snapshots[7].debugEventOrdinal=79}
  phaseTrigger={param($j)$j.phaseExecutionLedger.phases[3].triggerAnchorShortId='MVB04'}
  replyLater={param($j)$j.phaseExecutionLedger.continuePolicy='DBG_REPLY_LATER_ALLOWED'}
  activeBefore={param($j)$j.phaseExecutionLedger.phases[3].activeBefore=@('MVB03','MVB06','MVB07','MVB09')}
  commandMissing={param($j)$j.phaseExecutionLedger.phases[3].commands=@()}
  commandText={param($j)$j.phaseExecutionLedger.phases[3].commands[0].command='bphwc'}
  commandFailure={param($j)$j.phaseExecutionLedger.phases[3].commands[0].result='FAIL'}
  commandAutomatic={param($j)$j.phaseExecutionLedger.phases[3].commands[0].automatic=$true}
  activeAfter={param($j)$j.phaseExecutionLedger.phases[3].activeAfter=@('MVB04','MVB06','MVB07','MVB09')}
  beforeResume={param($j)$j.phaseExecutionLedger.phases[3].beforeResumeVerification='FAIL'}
  slotOverflow={param($j)$j.phaseExecutionLedger.phases[3].activeAfter+=@('MVB01')}
  threadPhaseMissing={param($j)$j.perThreadDrState.snapshots=@($j.perThreadDrState.snapshots|Select-Object -Skip 1)}
  threadCoverage={param($j)$j.perThreadDrState.snapshots[3].allCurrentThreadsCovered=$false}
  lifecycleMissing={param($j)$j.perThreadDrState.lifecycleEvents=@()}
  lifecycleUnmatched={param($j)$j.perThreadDrState.lifecycleEvents+=@([pscustomobject]@{debugEventOrdinal=75;eventType='CREATE_THREAD';threadId=9999;appliedAtPhaseOrdinal=7;snapshotId='S07';beforeContinuationVerified=$true})}
  lifecycleOrder={param($j)$j.perThreadDrState.snapshots[9].enumeratedThreadIds=@(4243);$j.perThreadDrState.snapshots[9].threads=@($j.perThreadDrState.snapshots[9].threads|Where-Object{$_.threadId-ne4242});$exit=[pscustomobject]@{debugEventOrdinal=95;eventType='EXIT_THREAD';threadId=4242;appliedAtPhaseOrdinal=9;snapshotId='S09';beforeContinuationVerified=$true};$j.perThreadDrState.lifecycleEvents=@($exit,$j.perThreadDrState.lifecycleEvents[0])}
  contextReadFailure={param($j)$j.perThreadDrState.snapshots[3].threads[0].contextReadSuccess=$false}
  duplicateThread={param($j)$j.perThreadDrState.snapshots[3].threads+=@($j.perThreadDrState.snapshots[3].threads[0])}
  threadMembership={param($j)$j.perThreadDrState.snapshots[3].threads[0].activeAnchorShortIds=@('MVB04','MVB06','MVB07','MVB09')}
  threadDr={param($j)$j.perThreadDrState.snapshots[3].threads[0].dr0='0x00000000'}
  dr7Rw={param($j)$j.perThreadDrState.snapshots[3].threads[0].dr7='0x00010055'}
  dr7Gd={param($j)$j.perThreadDrState.snapshots[3].threads[0].dr7='0x00002055'}
  dr7Reserved={param($j)$j.perThreadDrState.snapshots[3].threads[0].dr7='0x00004055'}
  hitMissing={param($j)$j.acceptedHits=@($j.acceptedHits|Select-Object -Skip 1)}
  hitEventOrder={param($j)$j.acceptedHits[4].debugEventOrdinal=$j.acceptedHits[3].debugEventOrdinal}
  hitThread={param($j)$j.acceptedHits[4].threadId=9999}
  hitThreadAbsentFromCensus={param($j)foreach($i in 5..8){$j.acceptedHits[$i].threadId=9999}}
  hitAccepted={param($j)$j.acceptedHits[4].acceptedCandidate=$false}
  hitResumeFlag={param($j)$j.acceptedHits[3]|Add-Member -Force -NotePropertyName eflags -NotePropertyValue '0x00010202';$j.acceptedHits[3]|Add-Member -Force -NotePropertyName resumeFlagSet -NotePropertyValue $true}
  hitSuppression={param($j)$j.acceptedHits[3]|Add-Member -Force -NotePropertyName predecessorSuppressionClass -NotePropertyValue 'MOV_SS'}
  hitPreCommandDr={param($j)$j.acceptedHits[3]|Add-Member -Force -NotePropertyName preCommandDr7 -NotePropertyValue '0x00000000'}
  hitDr6={param($j)$j.acceptedHits[3]|Add-Member -Force -NotePropertyName preCommandDr6 -NotePropertyValue '0x00000000'}
  hitDr6Bd={param($j)$j.acceptedHits[3].preCommandDr6='0x00002001'}
  hitDr6Bs={param($j)$j.acceptedHits[3].preCommandDr6='0x00004001'}
  hitDr6Bt={param($j)$j.acceptedHits[3].preCommandDr6='0x00008001'}
  rejectedCount={param($j)$j.rejectedHitLog.count=2}
  observationRange={param($j)$j.rejectedHitLog.observationStartDebugEventOrdinal=101}
  rejectedOutOfRange={param($j)$j.rejectedHitLog.entries[0].debugEventOrdinal=101;$j.rejectedHitLog.hardwareBreakpointEventOrdinals[6]=101}
  rejectedThreadAbsentFromCensus={param($j)$j.rejectedHitLog.entries[0].threadId=9999}
  rejectedRotates={param($j)$j.rejectedHitLog.entries=@([pscustomobject]@{debugEventOrdinal=1002;threadId=4242;exceptionAddress='0x005737D0';candidateAnchorShortId='MVB01';reason='ORDINAL_MISMATCH';phaseAdvanced=$true;entrySha256='A'*64});$j.rejectedHitLog.count=1}
  acceptedAlsoRejected={param($j)$j.rejectedHitLog.entries[0].debugEventOrdinal=$j.acceptedHits[5].debugEventOrdinal}
  eventTranscriptMissing={param($j)$j.rejectedHitLog|Add-Member -Force -NotePropertyName hardwareBreakpointEventOrdinals -NotePropertyValue @(10,20,30,40,50,60,70,80,90)}
  queueTwoMatches={param($j)$j.queueCensus.entries+=@($j.queueCensus.entries[0]);$j.queueCensus.pendingCount=2;$j.queueCensus.matchingExpectedOpcodeCount=2}
  queueUnique={param($j)$j.queueCensus.uniquePendingExpected0B07=$false}
  queueSlot={param($j)$j.queueCensus.selectedQueueSlot=1}
  queueOrdinal={param($j)$j.queueCensus.entries[0].movementCommandOrdinal=8}
  queuePayload={param($j)$j.queueCensus.entries[0].payloadSha256='D'*64}
  queuePostEvent={param($j)$j.queueCensus|Add-Member -Force -NotePropertyName postDecrementDebugEventOrdinal -NotePropertyValue 90}
  queuePostPhase={param($j)$j.queueCensus.postDecrementPhaseOrdinal=8}
  noMissPromoted={param($j)$j.evaluation.runtimeNoMissProof='PASS'}
  int3={param($j)$j.operations.softwareInt3Breakpoints=1}
  memoryWrite={param($j)$j.operations.processMemoryWrites=1}
  automaticInput={param($j)$j.operations.automaticInputs=1}
  liveEligible={param($j)$j.evaluation.liveReceiptEligible=$true}
  permit={param($j)$j.permitIssued=$true}
 }
 foreach($e in $m.GetEnumerator()){Eq ($e.Key+' rejected') (Run (Variant $e.Key $e.Value)).result 'FAIL'}
 [ordered]@{result='PASS';cases=$script:cases;assertions=$script:assertions;mutations=$m.Count}|ConvertTo-Json
}finally{if(Test-Path -LiteralPath $temp){$resolved=(Resolve-Path -LiteralPath $temp).Path;$base=[IO.Path]::GetFullPath([IO.Path]::GetTempPath());if(-not$resolved.StartsWith($base,[StringComparison]::OrdinalIgnoreCase)){throw 'unsafe cleanup'};Remove-Item -LiteralPath $resolved -Recurse -Force}}
