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
 gapAuditV3='6D138E4E5B595FD85E9F8F589C6CF7D625D4C86C69F44573D75D495DBCEDB4A0'
 priorV2Contract='5DE8873D2C9372CF9F7068AA9A8FD97D48032DFBA84A2ACC36DE57877057FEAE'
 priorV2Verification='BE0F57F465D55967CA9EC8D40669D37ECF27E7D80A9F3C55EF9E7B55EF087BFB'
 manager67Verification='628AF51614931DEDFFCCB1B5BA71A94864F437609E6948A1B28B8D8AA77FA9D2'
 manager67ArtifactLedger='1205449A4E247323D59CB73C975F0EE6865AC5865C8F9FBA54BA54F1FEE62AF3'
}
$loaded=@{}
foreach($name in $expectedHashes.Keys){
 $entry=$contract.boundArtifacts.$name
 if($null-eq$entry){$errors.Add("missing artifact $name");continue}
 Eq "$name declared hash" ([string]$entry.sha256).ToUpperInvariant() $expectedHashes[$name]
 $path=Join-Path $repo ([string]$entry.path)
 if(-not(Test-Path -LiteralPath $path -PathType Leaf)){$errors.Add("missing file $name");continue}
 Eq "$name actual hash" (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash $expectedHashes[$name]
 $loaded[$name]=Load $path
}

Eq 'schema' $contract.schemaVersion 3
Eq 'contract' $contract.contract 'ORIGINAL_CLIENT_FIRST_SERVER_BACKED_PLAY_PRELAUNCH_V3_MANAGER67_INTEGRATED'
Eq 'state' $contract.state 'OFFLINE_PRELAUNCH_MANAGER67_INTEGRATED_READY_FALSE'
Eq 'run id' $contract.oracleRunId $null
Eq 'launch eligible' $contract.launchEligible $false
Eq 'permit eligible' $contract.permitEligible $false
Eq 'permit issued' $contract.permitIssued $false
Eq 'authority source' $contract.currentAuthority.source 'USER_APPROVED_CURRENT_THREAD'
Eq 'authority scope' $contract.currentAuthority.scope 'ONE_LIVE_ORACLE_ONE_PHYSICAL_ACTIVATION_READ_ONLY_CAPTURE'
Eq 'activation budget' $contract.currentAuthority.activationBudget 1
Eq 'authority gate bypass' $contract.currentAuthority.doesNotBypassPrelaunchGates $true
Eq 'prior permit state' $contract.priorPermit.state 'CONSUMED_NO_RETRY'
Eq 'prior permit reusable' $contract.priorPermit.reusable $false

$resolved=@('MANAGER67_CURRENT_CARD_COLLECTOR_MISSING','SELECTED_CAPTAIN_CARD_WIDGET_COLLECTOR_MISSING')
Seq 'resolved static blockers' $contract.integrationDelta.resolvedStaticBlockers $resolved
$introduced=@('FRESH_MANAGER67_AUTHORITY_CARD_SNAPSHOT_MISSING','MANAGER67_AUTHORITY_CARD_HIT_REGION_INDEPENDENT_BINDING_MISSING')
Seq 'introduced runtime blockers' $contract.integrationDelta.introducedRuntimeBlockers $introduced
Eq 'prior blocker count' $contract.integrationDelta.priorBlockerCount 12
Eq 'current blocker count' $contract.integrationDelta.currentBlockerCount 12
$expectedBlockers=@(
 'ACTIVATION_BUDGET_STAGE_CONTRACT_MISMATCH','MANAGER65_LIVE_COLLECTOR_NOT_HARDENED','WARP_STAGE_OWNER_POINTER_UNBOUND','MOVEMENT_SPECIFIC_BREAKPOINT_RECEIPT_SCHEMA_MISSING','FRESH_RUN_IDENTITY_MISSING','FRESH_MANAGER67_AUTHORITY_CARD_SNAPSHOT_MISSING','MANAGER67_AUTHORITY_CARD_HIT_REGION_INDEPENDENT_BINDING_MISSING','FRESH_MANAGER65_SNAPSHOT_MISSING','FRESH_DESTINATION_PROJECTION_SNAPSHOT_MISSING','FRESH_TEXTDIALOG_SNAPSHOT_MISSING','FOREGROUND_PROBE_NOT_RUN','INDEPENDENT_LIVE_PRELAUNCH_REVIEW_MISSING'
)
Seq 'blockers' $contract.blockers $expectedBlockers
Eq 'first missing' $contract.firstMissingBoundary $expectedBlockers[0]
Eq 'first technical' $contract.firstTechnicalBoundary 'MANAGER65_LIVE_COLLECTOR_NOT_HARDENED'

$manager67=$contract.staticPreparation.manager67AuthorityCardCollector
Eq 'manager67 status' $manager67.status 'STATIC_OFFLINE_COLLECTOR_PASS_LIVE_BINDING_MISSING'
Eq 'manager67 runtime' $manager67.runtimeObserved 'UNSEEN'
Eq 'manager67 semantic' $manager67.semantic 'AUTHORITY_CARD_WITH_WARP_ACTION_NOT_PROVEN_CAPTAIN_PORTRAIT'
Eq 'manager67 fixture reuse' $manager67.fixtureCoordinateReusable $false
Eq 'manager67 published fixtures' $manager67.publishedFixtureArtifactsReproduced 2
Eq 'manager67 artifact hashes' $manager67.artifactHashesVerified 10
Eq 'manager67 review' $manager67.independentReview 'APPROVE'
Eq 'manager65 status' $contract.staticPreparation.manager65Collector.status 'OFFLINE_PASS_NOT_LIVE_HARDENED'
Eq 'manager65 runtime' $contract.staticPreparation.manager65Collector.runtimeObserved 'UNSEEN'
$deficiencies=@('CANONICAL_HASH_NOT_INTERNALLY_ENFORCED','MODULE_BASE_NOT_BOUND','DOUBLE_CAPTURE_MISSING','WIDGET_ACTIVE_VISIBLE_GATE_MISSING','POST_CAPTURE_HWND_SURFACE_RECHECK_MISSING')
Seq 'manager65 deficiencies' $contract.staticPreparation.manager65Collector.deficiencies $deficiencies
Eq 'destination runtime' $contract.staticPreparation.destinationProjection.runtimeObserved 'UNSEEN'
Eq 'destination fixture reuse' $contract.staticPreparation.destinationProjection.fixtureCoordinateReusable $false
Eq 'dialog runtime' $contract.staticPreparation.textDialog.runtimeObserved 'UNSEEN'
Eq 'dialog fixture reuse' $contract.staticPreparation.textDialog.fixtureCoordinateReusable $false
Eq 'movement runtime' $contract.staticPreparation.movementInstrumentation.runtimeObserved 'UNSEEN'

$expectedComposition=@(
 'fresh G7MTClient PID/startTime/hash/moduleBase/HWND/owner/client surface','fresh listener and heartbeat','fresh x32dbg PID/startTime/HWND/owner and exact foreground','fresh manager65-bound manager67 authority-card/action-0x2B/page-selected widget/gates/rect snapshot','independently bound manager67 authority-card hit region','hardened manager65 action-0x2B snapshot','WARP stage-owner pointer bound to the scoped TextDialog manager','fresh destination projection snapshot and independently resolved hit region','fresh TextDialog snapshot and independently resolved confirm region','movement-specific breakpoint/receipt schema installed without writes','one physical activation sequence followed by outbound/inbound/pixel receipts'
)
Seq 'same-run composition' $contract.requiredSameRunComposition $expectedComposition
$expectedForbidden=@('reuse prior permit/run/PID/HWND/pointer/coordinate','automatic click or retry','input before all same-run gates and independent review pass','process-memory write or binary/resource patch','VM lifecycle or server/protocol/database change','fixture or self-claimed live evidence promotion')
Seq 'forbidden' $contract.forbidden $expectedForbidden
$counterNames=@('guestOperations','debuggerAttach','breakpointsInstalled','processMemoryReads','physicalInputs','captures','memoryWrites')
Seq 'counter names' @($contract.operationCounters.PSObject.Properties.Name) $counterNames
foreach($name in $counterNames){Eq "counter $name" $contract.operationCounters.$name 0}

if($loaded.ContainsKey('priorV2Contract')){
 $prior=$loaded.priorV2Contract
 Eq 'prior v2 schema' $prior.schemaVersion 2;Eq 'prior v2 state' $prior.state 'OFFLINE_PRELAUNCH_AUDIT_PASS_READY_FALSE';Eq 'prior blocker count' @($prior.blockers).Count 12;Eq 'prior first technical' $prior.firstTechnicalBoundary 'MANAGER67_CURRENT_CARD_COLLECTOR_MISSING'
 $derived=@($prior.blockers|Where-Object{$resolved-notcontains$_})
 $insertAt=[Array]::IndexOf($derived,'FRESH_MANAGER65_SNAPSHOT_MISSING')
 if($insertAt-lt0){$errors.Add('prior fresh manager65 blocker missing')}else{$derived=@($derived[0..($insertAt-1)])+$introduced+@($derived[$insertAt..($derived.Count-1)])}
 Seq 'blocker delta derived from v2' $contract.blockers $derived
}
if($loaded.ContainsKey('priorV2Verification')){Eq 'prior v2 receipt' $loaded.priorV2Verification.result 'PASS';Eq 'prior v2 tests' $loaded.priorV2Verification.tests.cases 16;Eq 'prior v2 inputs' $loaded.priorV2Verification.gameInputs 0}
if($loaded.ContainsKey('manager67Verification')){
 $receipt=$loaded.manager67Verification
 Eq 'manager67 receipt' $receipt.result 'PASS';Eq 'manager67 collector cases' $receipt.collectorTests.cases 36;Eq 'manager67 collector assertions' $receipt.collectorTests.assertions 59;Eq 'manager67 resolver cases' $receipt.resolverTests.cases 6;Eq 'manager67 resolver assertions' $receipt.resolverTests.assertions 19;Eq 'manager67 published reproduction' $receipt.publishedFixtureArtifactsReproduced 2;Eq 'manager67 hash count' $receipt.artifactHashesVerified 10;Eq 'manager67 ledger receipt hash' $receipt.artifactLedgerSha256 $expectedHashes.manager67ArtifactLedger;Eq 'manager67 live ops' $receipt.liveOperations 0;Eq 'manager67 inputs' $receipt.gameInputs 0;Eq 'manager67 permit' $receipt.permitIssued $false
}
if($loaded.ContainsKey('manager67ArtifactLedger')){
 $ledger=$loaded.manager67ArtifactLedger
 Eq 'manager67 ledger artifact count' @($ledger.artifacts).Count 10
 foreach($artifact in $ledger.artifacts){$path=Join-Path $repo ('work/20260828-manager67-current-card-hit-surface/'+[string]$artifact.path);if(-not(Test-Path -LiteralPath $path)){$errors.Add("manager67 ledger file missing $($artifact.path)");continue};$actual=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash;Eq "manager67 ledger file $($artifact.path)" $actual $artifact.sha256;if($loaded.ContainsKey('manager67Verification')){Eq "manager67 receipt map $($artifact.path)" $loaded.manager67Verification.artifactHashMap.([string]$artifact.path) $artifact.sha256}}
}
if($loaded.ContainsKey('gapAuditV3')){
 $gap=$loaded.gapAuditV3
 Eq 'gap schema' $gap.schemaVersion 2;Eq 'gap audit' $gap.audit 'FIRST_PLAY_UI_AND_MOVEMENT_GAP_AUDIT_MANAGER67_INTEGRATED';Seq 'gap resolved' $gap.resolvedStaticGaps $resolved;Seq 'gap runtime boundaries' $gap.introducedRuntimeBoundaries $introduced;Seq 'gap statuses' @($gap.uiBindings.status) @('OFFLINE_COLLECTOR_PASS_LIVE_SNAPSHOT_MISSING','STATIC_OWNER_OFFLINE_RESOLVER_PASS_LIVE_BINDING_MISSING','OFFLINE_COLLECTOR_EXISTS_NOT_LIVE_HARDENED','LIVE_BINDING_MISSING','LIVE_BINDING_MISSING');Seq 'gap first boundaries' @($gap.uiBindings[0..1].firstMissingBoundary) $introduced;Eq 'gap authority semantic' $gap.uiBindings[1].semantic 'AUTHORITY_CARD_WITH_WARP_ACTION_NOT_PROVEN_CAPTAIN_PORTRAIT';Eq 'gap activation' $gap.activationGate.status 'ACTIVATION_BUDGET_STAGE_CONTRACT_MISMATCH';Eq 'gap runtime' $gap.runtimeObserved 'UNSEEN';Eq 'gap fixture reuse' $gap.fixtureCoordinateReusable $false;Eq 'gap live ops' $gap.liveOperations 0;Eq 'gap inputs' $gap.gameInputs 0;Eq 'gap permit' $gap.permitIssued $false
 foreach($property in $gap.sources.PSObject.Properties){$entry=$property.Value;$path=Join-Path $repo ([string]$entry.path);if(-not(Test-Path -LiteralPath $path)){$errors.Add("gap source missing $($property.Name)");continue};Eq "gap source hash $($property.Name)" (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash ([string]$entry.sha256).ToUpperInvariant()}
}

if($errors.Count){[ordered]@{result='FAIL';errors=@($errors)}|ConvertTo-Json -Depth 8;return}
[ordered]@{result='PASS';state=$contract.state;permitEligible=$false;launchEligible=$false;blockerCount=$contract.blockers.Count;firstMissingBoundary=$contract.firstMissingBoundary;firstTechnicalBoundary=$contract.firstTechnicalBoundary;artifactCount=$expectedHashes.Count;manager67StaticGapsResolved=2;manager67FreshRuntimeBoundaries=$introduced;liveOperations=0;gameInputs=0;permitIssued=$false}|ConvertTo-Json -Depth 8
