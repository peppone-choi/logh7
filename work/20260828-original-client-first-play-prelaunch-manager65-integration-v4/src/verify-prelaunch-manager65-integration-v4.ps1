[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$ContractPath)
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$contract=Get-Content -LiteralPath $ContractPath -Raw -Encoding UTF8|ConvertFrom-Json
$errors=[Collections.Generic.List[string]]::new()
function Eq([string]$name,$actual,$expected){if($actual-ne$expected){$errors.Add("$name expected=$expected actual=$actual")}}
function Seq([string]$name,$actual,$expected){if((@($actual)|ConvertTo-Json -Compress)-ne(@($expected)|ConvertTo-Json -Compress)){$errors.Add("$name sequence mismatch")}}
function Load([string]$path){Get-Content -LiteralPath $path -Raw -Encoding UTF8|ConvertFrom-Json}

$expectedHashes=[ordered]@{
 gapAuditV4='D4FEF852D6CCED8F84343B2E7125C94E43FAE6F79555D8F510A606707AD93053'
 priorV3Contract='12D3CA4D39BF746EAF08EC5ACE936C4D4CD368F1E7AA62B907CB82297F2DA0DE'
 priorV3ArtifactLedger='673AFA46BEBA5732D06F959B54159B83A2DC8B4E93E456CEE0ACC81BE0F1A31C'
 priorV3Verification='D61A6D0B8EFD4AEB62F8F8B41FA77ED52AEA19FBB89E67CA2C20252649BB3AC8'
 manager65ArtifactLedger='F26DFC2E9E9AA7252323171595E3C2E4073286862372D05A7097A70C9656E897'
 manager65StaticOwnerLedger='B0EAA1C0F3A165F1D1D12883EE83C5BC5CACEB5C7A4CE0C789F11ADAB32DFADA'
 manager65Verifier='757E2D988F916F58017973371073C5FFF65711BB8C09FC72E85241AEB3B56695'
}
$loaded=@{}
foreach($name in $expectedHashes.Keys){
 $entry=$contract.boundArtifacts.$name
 if($null-eq$entry){$errors.Add("missing artifact $name");continue}
 Eq "$name declared hash" ([string]$entry.sha256).ToUpperInvariant() $expectedHashes[$name]
 $path=Join-Path $repo ([string]$entry.path)
 if(-not(Test-Path -LiteralPath $path -PathType Leaf)){$errors.Add("missing file $name");continue}
 Eq "$name actual hash" (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash $expectedHashes[$name]
 if($path.EndsWith('.json')){$loaded[$name]=Load $path}
}

Eq 'schema' $contract.schemaVersion 4
Eq 'contract' $contract.contract 'ORIGINAL_CLIENT_FIRST_SERVER_BACKED_PLAY_PRELAUNCH_V4_MANAGER65_INTEGRATED'
Eq 'state' $contract.state 'OFFLINE_PRELAUNCH_MANAGER65_INTEGRATED_READY_FALSE'
Eq 'run id' $contract.oracleRunId $null
Eq 'launch eligible' $contract.launchEligible $false;Eq 'permit eligible' $contract.permitEligible $false;Eq 'permit issued' $contract.permitIssued $false
Eq 'authority source' $contract.currentAuthority.source 'USER_APPROVED_CURRENT_THREAD';Eq 'authority scope' $contract.currentAuthority.scope 'ONE_LIVE_ORACLE_ONE_PHYSICAL_ACTIVATION_READ_ONLY_CAPTURE';Eq 'activation budget' $contract.currentAuthority.activationBudget 1;Eq 'gate bypass' $contract.currentAuthority.doesNotBypassPrelaunchGates $true
Eq 'prior permit state' $contract.priorPermit.state 'CONSUMED_NO_RETRY';Eq 'prior permit reuse' $contract.priorPermit.reusable $false
$resolved=@('MANAGER65_LIVE_COLLECTOR_NOT_HARDENED')
$preserved=@('FRESH_MANAGER65_SNAPSHOT_MISSING')
$introduced=@('MANAGER65_ACTION_0X2B_HIT_REGION_INDEPENDENT_BINDING_MISSING')
Seq 'resolved blockers' $contract.integrationDelta.resolvedStaticBlockers $resolved;Seq 'preserved blockers' $contract.integrationDelta.preservedRuntimeBlockers $preserved;Seq 'introduced blockers' $contract.integrationDelta.introducedRuntimeBlockers $introduced
Eq 'prior blocker count' $contract.integrationDelta.priorBlockerCount 12;Eq 'current blocker count' $contract.integrationDelta.currentBlockerCount 12
$expectedBlockers=@('ACTIVATION_BUDGET_STAGE_CONTRACT_MISMATCH','WARP_STAGE_OWNER_POINTER_UNBOUND','MOVEMENT_SPECIFIC_BREAKPOINT_RECEIPT_SCHEMA_MISSING','FRESH_RUN_IDENTITY_MISSING','FRESH_MANAGER67_AUTHORITY_CARD_SNAPSHOT_MISSING','MANAGER67_AUTHORITY_CARD_HIT_REGION_INDEPENDENT_BINDING_MISSING','FRESH_MANAGER65_SNAPSHOT_MISSING','MANAGER65_ACTION_0X2B_HIT_REGION_INDEPENDENT_BINDING_MISSING','FRESH_DESTINATION_PROJECTION_SNAPSHOT_MISSING','FRESH_TEXTDIALOG_SNAPSHOT_MISSING','FOREGROUND_PROBE_NOT_RUN','INDEPENDENT_LIVE_PRELAUNCH_REVIEW_MISSING')
Seq 'blockers' $contract.blockers $expectedBlockers;Eq 'first missing' $contract.firstMissingBoundary $expectedBlockers[0];Eq 'first technical' $contract.firstTechnicalBoundary $expectedBlockers[1]
$m65=$contract.staticPreparation.manager65Collector
Eq 'manager65 status' $m65.status 'HARDENED_COLLECTOR_RESOLVER_PASS_LIVE_SNAPSHOT_MISSING';Eq 'manager65 runtime' $m65.runtimeObserved 'UNSEEN';Eq 'manager65 semantic' $m65.semantic 'CURRENT_AUTHORITY_CARD_ACTION_WIDGET_FOR_COMMAND_0X2B_WARP_NAVIGATION';Eq 'manager65 fixture reuse' $m65.fixtureCoordinateReusable $false;Eq 'manager65 independent hit' $m65.independentLiveHitRegion 'UNBOUND';Eq 'manager65 static owner' $m65.staticOwner 'PASS';Eq 'manager65 offline resolver' $m65.offlineCollectorResolver 'PASS';Eq 'manager65 review' $m65.independentReview 'APPROVE'
foreach($name in @('canonicalHashInternallyEnforced','moduleBaseBound','fullDoubleCapture','widgetActiveVisibleGates','postCaptureHwndSurfaceRecheck')){Eq "hardening $name" $m65.hardening.$name $true};Eq 'self claim promotable' $m65.hardening.liveSelfClaimPromotable $false
Eq 'manager67 runtime' $contract.staticPreparation.manager67AuthorityCardCollector.runtimeObserved 'UNSEEN';Eq 'destination runtime' $contract.staticPreparation.destinationProjection.runtimeObserved 'UNSEEN';Eq 'dialog runtime' $contract.staticPreparation.textDialog.runtimeObserved 'UNSEEN';Eq 'movement runtime' $contract.staticPreparation.movementInstrumentation.runtimeObserved 'UNSEEN'
$expectedComposition=@('fresh G7MTClient PID/startTime/hash/moduleBase/HWND/owner/client surface','fresh listener and heartbeat','fresh x32dbg PID/startTime/HWND/owner and exact foreground','fresh manager65-bound manager67 authority-card/action-0x2B/page-selected widget/gates/rect snapshot','independently bound manager67 authority-card hit region','fresh hardened manager65 action-0x2B snapshot','independently bound manager65 action-0x2B hit region','WARP stage-owner pointer bound to the scoped TextDialog manager','fresh destination projection snapshot and independently resolved hit region','fresh TextDialog snapshot and independently resolved confirm region','movement-specific breakpoint/receipt schema installed without writes','one physical activation sequence followed by outbound/inbound/pixel receipts')
Seq 'same-run composition' $contract.requiredSameRunComposition $expectedComposition
$expectedForbidden=@('reuse prior permit/run/PID/HWND/pointer/coordinate','automatic click or retry','input before all same-run gates and independent review pass','process-memory write or binary/resource patch','VM lifecycle or server/protocol/database change','fixture or self-claimed live evidence promotion')
Seq 'forbidden' $contract.forbidden $expectedForbidden
$counterNames=@('guestOperations','debuggerAttach','breakpointsInstalled','processMemoryReads','physicalInputs','captures','memoryWrites');Seq 'counter names' @($contract.operationCounters.PSObject.Properties.Name) $counterNames;foreach($name in $counterNames){Eq "counter $name" $contract.operationCounters.$name 0}

if($loaded.ContainsKey('priorV3Contract')){$v3=$loaded.priorV3Contract;Eq 'v3 schema' $v3.schemaVersion 3;Eq 'v3 state' $v3.state 'OFFLINE_PRELAUNCH_MANAGER67_INTEGRATED_READY_FALSE';Eq 'v3 blockers' @($v3.blockers).Count 12;$derived=@($v3.blockers|Where-Object{$_-ne'MANAGER65_LIVE_COLLECTOR_NOT_HARDENED'});$at=[Array]::IndexOf($derived,'FRESH_DESTINATION_PROJECTION_SNAPSHOT_MISSING');if($at-lt0){$errors.Add('v3 insertion point missing')}else{$derived=@($derived[0..($at-1)])+@('MANAGER65_ACTION_0X2B_HIT_REGION_INDEPENDENT_BINDING_MISSING')+@($derived[$at..($derived.Count-1)])};Seq 'blocker delta from v3' $contract.blockers $derived}
if($loaded.ContainsKey('priorV3Verification')){$r=$loaded.priorV3Verification;Eq 'v3 receipt' $r.result 'PASS';Eq 'v3 cases' $r.tests.cases 16;Eq 'v3 assertions' $r.tests.assertions 28;Eq 'v3 ledger receipt' $r.artifactLedgerSha256 $expectedHashes.priorV3ArtifactLedger;Eq 'v3 inputs' $r.gameInputs 0;Eq 'v3 permit' $r.permitIssued $false}
if($loaded.ContainsKey('priorV3ArtifactLedger')){$ledger=$loaded.priorV3ArtifactLedger;Eq 'v3 ledger count' @($ledger.artifacts).Count 5;foreach($a in $ledger.artifacts){$p=Join-Path $repo ('work/20260828-original-client-first-play-prelaunch-manager67-integration/'+[string]$a.path);if(-not(Test-Path $p)){$errors.Add("v3 artifact missing $($a.path)");continue};Eq "v3 ledger $($a.path)" (Get-FileHash $p -Algorithm SHA256).Hash $a.sha256}}
if($loaded.ContainsKey('manager65StaticOwnerLedger')){$s=$loaded.manager65StaticOwnerLedger;Eq 'manager65 target' $s.target.sha256 'BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16';Eq 'strategy root' $s.correction.strategyRoot 'moduleBase+0x89E638';Eq 'registry host' $s.correction.registryHost 'U32(moduleBase+0x1E15E2C)';Eq 'static semantic' $s.facts.semantic 'CURRENT_AUTHORITY_CARD_ACTION_WIDGET_FOR_COMMAND_0X2B_WARP_NAVIGATION';Eq 'static live snapshot' $s.status.liveSnapshot 'UNSEEN';Eq 'static hit binding' $s.status.independentLiveHitRegion 'UNBOUND';Eq 'static player visible' $s.status.playerVisible 'UNSEEN';Eq 'static inputs' $s.status.gameInputs 0;Eq 'static permit' $s.status.permitIssued $false}
if($loaded.ContainsKey('manager65ArtifactLedger')){$ledger=$loaded.manager65ArtifactLedger;Eq 'manager65 ledger count' @($ledger.artifacts).Count 9;foreach($a in $ledger.artifacts){$p=Join-Path $repo ('work/20260828-original-client-manager65-live-collector-hardening/'+[string]$a.path);if(-not(Test-Path $p)){$errors.Add("manager65 artifact missing $($a.path)");continue};Eq "manager65 ledger $($a.path)" (Get-FileHash $p -Algorithm SHA256).Hash $a.sha256}}
if($errors.Count-eq0){try{$freshV3=& (Join-Path $repo 'work/20260828-original-client-first-play-prelaunch-manager67-integration/verify.ps1')|ConvertFrom-Json;Eq 'fresh v3 result' $freshV3.result 'PASS';Eq 'fresh v3 cases' $freshV3.tests.cases 16;Eq 'fresh v3 assertions' $freshV3.tests.assertions 28;Eq 'fresh v3 ledger sha' $freshV3.artifactLedgerSha256 $expectedHashes.priorV3ArtifactLedger;Eq 'fresh v3 live ops' $freshV3.liveOperations 0;Eq 'fresh v3 inputs' $freshV3.gameInputs 0;Eq 'fresh v3 permit' $freshV3.permitIssued $false}catch{$errors.Add("fresh v3 verifier failed: $($_.Exception.Message)")}}
if($errors.Count-eq0){try{$fresh=& (Join-Path $repo 'work/20260828-original-client-manager65-live-collector-hardening/verify.ps1')|ConvertFrom-Json;Eq 'fresh manager65 result' $fresh.result 'PASS';Eq 'fresh manager65 cases' $fresh.tests.cases 44;Eq 'fresh manager65 assertions' $fresh.tests.assertions 91;Eq 'fresh manager65 artifacts' $fresh.artifactHashesVerified 9;Eq 'fresh manager65 ledger sha' $fresh.artifactLedgerSha256 $expectedHashes.manager65ArtifactLedger;Eq 'fresh manager65 live ops' $fresh.liveOperations 0;Eq 'fresh manager65 reads' $fresh.processMemoryReads 0;Eq 'fresh manager65 inputs' $fresh.gameInputs 0;Eq 'fresh manager65 permit' $fresh.permitIssued $false;Eq 'fresh manager65 status' $fresh.status 'OFFLINE_MANAGER65_HARDENED_COLLECTOR_RESOLVER_PASS_LIVE_UNSEEN'}catch{$errors.Add("fresh manager65 verifier failed: $($_.Exception.Message)")}}
if($loaded.ContainsKey('gapAuditV4')){$g=$loaded.gapAuditV4;Eq 'gap schema' $g.schemaVersion 3;Seq 'gap resolved' $g.resolvedStaticGaps $resolved;Seq 'gap preserved' $g.preservedRuntimeBoundaries $preserved;Seq 'gap introduced' $g.introducedRuntimeBoundaries $introduced;Eq 'gap runtime' $g.runtimeObserved 'UNSEEN';Eq 'gap fixture' $g.fixtureCoordinateReusable $false;Eq 'gap live ops' $g.liveOperations 0;Eq 'gap inputs' $g.gameInputs 0;Eq 'gap permit' $g.permitIssued $false;foreach($prop in $g.sources.PSObject.Properties){$p=Join-Path $repo ([string]$prop.Value.path);if(-not(Test-Path $p)){$errors.Add("gap source missing $($prop.Name)");continue};Eq "gap source $($prop.Name)" (Get-FileHash $p -Algorithm SHA256).Hash ([string]$prop.Value.sha256).ToUpperInvariant()}}

if($errors.Count){[ordered]@{result='FAIL';errors=@($errors)}|ConvertTo-Json -Depth 10;return}
[ordered]@{result='PASS';state=$contract.state;permitEligible=$false;launchEligible=$false;blockerCount=$contract.blockers.Count;firstMissingBoundary=$contract.firstMissingBoundary;firstTechnicalBoundary=$contract.firstTechnicalBoundary;artifactCount=$expectedHashes.Count;manager65StaticGapsResolved=1;manager65FreshRuntimeBoundaries=@($preserved)+@($introduced);liveOperations=0;gameInputs=0;permitIssued=$false}|ConvertTo-Json -Depth 10
