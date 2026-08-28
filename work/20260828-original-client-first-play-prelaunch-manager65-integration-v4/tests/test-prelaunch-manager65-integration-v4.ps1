$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$verifier=Join-Path $root 'src/verify-prelaunch-manager65-integration-v4.ps1'
$contract=Join-Path $root 'evidence/prelaunch-manager65-integration-v4.json'
if(-not(Test-Path -LiteralPath $verifier)){throw 'RED: production verifier missing'}
if(-not(Test-Path -LiteralPath $contract)){throw 'RED: v4 integration contract missing'}
$temp=Join-Path ([IO.Path]::GetTempPath()) ('logh7-prelaunch-manager65-v4-'+[guid]::NewGuid().ToString('N'));New-Item -ItemType Directory $temp|Out-Null
$script:n=0
function Eq($name,$actual,$expected){$script:n++;if($actual-ne$expected){throw "$name expected=$expected actual=$actual"}}
function Run($path){&$verifier -ContractPath $path|ConvertFrom-Json}
function Variant($name,[scriptblock]$change){$j=Get-Content $contract -Raw -Encoding UTF8|ConvertFrom-Json;&$change $j;$p=Join-Path $temp "$name.json";$j|ConvertTo-Json -Depth 30|Set-Content $p -Encoding UTF8;$p}
try{
 $r=Run $contract
 Eq 'canonical result' $r.result 'PASS';Eq 'state' $r.state 'OFFLINE_PRELAUNCH_MANAGER65_INTEGRATED_READY_FALSE';Eq 'permit eligible' $r.permitEligible $false;Eq 'launch eligible' $r.launchEligible $false;Eq 'blocker count' $r.blockerCount 12;Eq 'first missing' $r.firstMissingBoundary 'ACTIVATION_BUDGET_STAGE_CONTRACT_MISMATCH';Eq 'first technical' $r.firstTechnicalBoundary 'WARP_STAGE_OWNER_POINTER_UNBOUND';Eq 'artifact count' $r.artifactCount 7;Eq 'resolved static count' $r.manager65StaticGapsResolved 1;Eq 'runtime boundary count' @($r.manager65FreshRuntimeBoundaries).Count 2;Eq 'second runtime boundary' $r.manager65FreshRuntimeBoundaries[1] 'MANAGER65_ACTION_0X2B_HIT_REGION_INDEPENDENT_BINDING_MISSING';Eq 'live ops' $r.liveOperations 0;Eq 'inputs' $r.gameInputs 0;Eq 'permit' $r.permitIssued $false
 $p=Variant permit {param($j)$j.permitEligible=$true};Eq 'permit promotion rejected' (Run $p).result 'FAIL'
 $p=Variant launch {param($j)$j.launchEligible=$true};Eq 'launch promotion rejected' (Run $p).result 'FAIL'
 $p=Variant policy {param($j)$j.blockers=@($j.blockers|Where-Object{$_-ne'ACTIVATION_BUDGET_STAGE_CONTRACT_MISMATCH'})};Eq 'policy removal rejected' (Run $p).result 'FAIL'
 $p=Variant oldblocker {param($j)$j.blockers[1]='MANAGER65_LIVE_COLLECTOR_NOT_HARDENED'};Eq 'old hardening blocker rejected' (Run $p).result 'FAIL'
 $p=Variant fresh {param($j)$j.blockers=@($j.blockers|Where-Object{$_-ne'FRESH_MANAGER65_SNAPSHOT_MISSING'})};Eq 'fresh snapshot removal rejected' (Run $p).result 'FAIL'
 $p=Variant hitbinding {param($j)$j.blockers=@($j.blockers|Where-Object{$_-ne'MANAGER65_ACTION_0X2B_HIT_REGION_INDEPENDENT_BINDING_MISSING'})};Eq 'independent binding removal rejected' (Run $p).result 'FAIL'
 $p=Variant status {param($j)$j.staticPreparation.manager65Collector.status='LIVE_PASS'};Eq 'live status promotion rejected' (Run $p).result 'FAIL'
 $p=Variant runtime {param($j)$j.staticPreparation.manager65Collector.runtimeObserved='PASS'};Eq 'runtime promotion rejected' (Run $p).result 'FAIL'
 $p=Variant semantic {param($j)$j.staticPreparation.manager65Collector.semantic='WARP_EXECUTION_CONFIRMED'};Eq 'semantic promotion rejected' (Run $p).result 'FAIL'
 $p=Variant fixture {param($j)$j.staticPreparation.manager65Collector.fixtureCoordinateReusable=$true};Eq 'fixture reuse rejected' (Run $p).result 'FAIL'
 $p=Variant managerhash {param($j)$j.boundArtifacts.manager65ArtifactLedger.sha256='0'*64};Eq 'manager65 hash mutation rejected' (Run $p).result 'FAIL'
 $p=Variant priorhash {param($j)$j.boundArtifacts.priorV3Contract.sha256='0'*64};Eq 'prior v3 hash mutation rejected' (Run $p).result 'FAIL'
 $p=Variant budget {param($j)$j.currentAuthority.activationBudget=3};Eq 'budget inflation rejected' (Run $p).result 'FAIL'
 $p=Variant counters {param($j)$j.operationCounters.processMemoryReads=1};Eq 'operation counter rejected' (Run $p).result 'FAIL'
 $p=Variant composition {param($j)$j.requiredSameRunComposition[6]='manager65 fixture coordinate reused'};Eq 'unsafe composition rejected' (Run $p).result 'FAIL'
 $p=Variant order {param($j)$t=$j.blockers[1];$j.blockers[1]=$j.blockers[2];$j.blockers[2]=$t};Eq 'blocker order rejected' (Run $p).result 'FAIL'
 $p=Variant selfclaim {param($j)$j.staticPreparation.manager65Collector.independentLiveHitRegion='PASS'};Eq 'self claim rejected' (Run $p).result 'FAIL'
 $p=Variant taxonomy {param($j)$j.integrationDelta.preservedRuntimeBlockers=@();$j.integrationDelta.introducedRuntimeBlockers=@('FRESH_MANAGER65_SNAPSHOT_MISSING','MANAGER65_ACTION_0X2B_HIT_REGION_INDEPENDENT_BINDING_MISSING')};Eq 'runtime taxonomy mutation rejected' (Run $p).result 'FAIL'
 [ordered]@{result='PASS';cases=19;assertions=$script:n}|ConvertTo-Json
}finally{if(Test-Path $temp){$resolved=(Resolve-Path $temp).Path;$base=[IO.Path]::GetFullPath([IO.Path]::GetTempPath());if(-not$resolved.StartsWith($base,[StringComparison]::OrdinalIgnoreCase)){throw'unsafe temp cleanup'};Remove-Item -LiteralPath $resolved -Recurse -Force}}
