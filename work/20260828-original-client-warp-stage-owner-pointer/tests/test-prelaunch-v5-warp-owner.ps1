$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot;$verifier=Join-Path $root 'src/verify-prelaunch-v5-warp-owner.ps1';$contract=Join-Path $root 'evidence/prelaunch-v5-warp-owner.json'
if(-not(Test-Path $verifier)){throw 'RED: v5 verifier missing'};if(-not(Test-Path $contract)){throw 'RED: v5 contract missing'}
$temp=Join-Path ([IO.Path]::GetTempPath()) ('logh7-warp-owner-v5-'+[guid]::NewGuid().ToString('N'));New-Item -ItemType Directory $temp|Out-Null;$script:n=0
function Eq($name,$actual,$expected){$script:n++;if($actual-ne$expected){throw "$name expected=$expected actual=$actual"}}
function Run($p){&$verifier -ContractPath $p|ConvertFrom-Json}
function Variant($name,[scriptblock]$change){$j=Get-Content $contract -Raw -Encoding UTF8|ConvertFrom-Json;&$change $j;$p=Join-Path $temp "$name.json";$j|ConvertTo-Json -Depth 30|Set-Content $p -Encoding UTF8;$p}
try{
 $r=Run $contract;Eq 'result' $r.result 'PASS';Eq 'state' $r.state 'OFFLINE_PRELAUNCH_WARP_OWNER_INTEGRATED_READY_FALSE';Eq 'blockers' $r.blockerCount 12;Eq 'first policy' $r.firstMissingBoundary 'ACTIVATION_BUDGET_STAGE_CONTRACT_MISMATCH';Eq 'first technical' $r.firstTechnicalBoundary 'MOVEMENT_SPECIFIC_BREAKPOINT_RECEIPT_SCHEMA_MISSING';Eq 'static resolved' $r.warpOwnerStaticGapsResolved 1;Eq 'fresh boundary' $r.warpOwnerFreshRuntimeBoundary 'FRESH_WARP_STAGE_OWNER_SNAPSHOT_MISSING';Eq 'prior permit state' $r.priorPermitState 'CONSUMED_NO_RETRY';Eq 'live operations' $r.liveOperations 0;Eq 'inputs' $r.gameInputs 0;Eq 'permit' $r.permitIssued $false
 $p=Variant old {param($j)$j.blockers[1]='WARP_STAGE_OWNER_POINTER_UNBOUND'};Eq 'old blocker rejected' (Run $p).result 'FAIL'
 $p=Variant fresh {param($j)$j.blockers=@($j.blockers|Where-Object{$_-ne'FRESH_WARP_STAGE_OWNER_SNAPSHOT_MISSING'})};Eq 'fresh boundary removal rejected' (Run $p).result 'FAIL'
 $p=Variant status {param($j)$j.staticPreparation.warpStageOwner.runtimeObserved='PASS'};Eq 'runtime promotion rejected' (Run $p).result 'FAIL'
 $p=Variant manager {param($j)$j.staticPreparation.warpStageOwner.textDialogManager='0x00CA2930'};Eq 'manager mutation rejected' (Run $p).result 'FAIL'
 $p=Variant order {param($j)$t=$j.blockers[1];$j.blockers[1]=$j.blockers[2];$j.blockers[2]=$t};Eq 'order mutation rejected' (Run $p).result 'FAIL'
 $p=Variant hash {param($j)$j.boundArtifacts.warpOwnerStaticLedger.sha256='0'*64};Eq 'ledger hash mutation rejected' (Run $p).result 'FAIL'
 $p=Variant op {param($j)$j.operationCounters.processMemoryReads=1};Eq 'operation mutation rejected' (Run $p).result 'FAIL'
 $p=Variant eligible {param($j)$j.launchEligible=$true};Eq 'launch promotion rejected' (Run $p).result 'FAIL'
 $p=Variant source {param($j)$j.currentAuthority.source='SELF_ATTESTED'};Eq 'authority source rejected' (Run $p).result 'FAIL'
 $p=Variant scope {param($j)$j.currentAuthority.scope='UNBOUNDED'};Eq 'authority scope rejected' (Run $p).result 'FAIL'
 $p=Variant forbidden {param($j)$j.forbidden[1]='automatic retry allowed'};Eq 'forbidden mutation rejected' (Run $p).result 'FAIL'
 $p=Variant prior {param($j)$j.priorPermit.id='replacement';$j.priorPermit.state='ACTIVE';$j.priorPermit.reusable=$true};Eq 'prior permit mutation rejected' (Run $p).result 'FAIL'
 [ordered]@{result='PASS';cases=13;assertions=$script:n}|ConvertTo-Json
}finally{if(Test-Path $temp){$resolved=(Resolve-Path $temp).Path;$base=[IO.Path]::GetFullPath([IO.Path]::GetTempPath());if(-not$resolved.StartsWith($base,[StringComparison]::OrdinalIgnoreCase)){throw 'unsafe cleanup'};Remove-Item $resolved -Recurse -Force}}
