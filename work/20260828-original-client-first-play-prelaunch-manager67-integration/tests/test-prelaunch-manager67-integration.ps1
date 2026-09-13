$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$verifier=Join-Path $root 'src/verify-prelaunch-manager67-integration.ps1'
$contract=Join-Path $root 'evidence/prelaunch-manager67-integration.json'
if(-not(Test-Path -LiteralPath $verifier)){throw 'RED: production verifier missing'}
if(-not(Test-Path -LiteralPath $contract)){throw 'RED: v3 integration contract missing'}
$temp=Join-Path ([IO.Path]::GetTempPath()) ('logh7-prelaunch-manager67-'+[guid]::NewGuid().ToString('N'));New-Item -ItemType Directory $temp|Out-Null
$script:n=0
function Eq($name,$actual,$expected){$script:n++;if($actual-ne$expected){throw "$name expected=$expected actual=$actual"}}
function Run($path){&$verifier -ContractPath $path|ConvertFrom-Json}
function Variant($name,[scriptblock]$change){$j=Get-Content $contract -Raw -Encoding UTF8|ConvertFrom-Json;&$change $j;$p=Join-Path $temp "$name.json";$j|ConvertTo-Json -Depth 20|Set-Content $p -Encoding UTF8;$p}
try{
 $r=Run $contract
 Eq 'canonical result' $r.result 'PASS';Eq 'state' $r.state 'OFFLINE_PRELAUNCH_MANAGER67_INTEGRATED_READY_FALSE';Eq 'permit eligible' $r.permitEligible $false;Eq 'launch eligible' $r.launchEligible $false;Eq 'blocker count' $r.blockerCount 12;Eq 'first missing' $r.firstMissingBoundary 'ACTIVATION_BUDGET_STAGE_CONTRACT_MISMATCH';Eq 'first technical' $r.firstTechnicalBoundary 'MANAGER65_LIVE_COLLECTOR_NOT_HARDENED';Eq 'artifact count' $r.artifactCount 5;Eq 'fresh manager67 boundary count' @($r.manager67FreshRuntimeBoundaries).Count 2;Eq 'second manager67 boundary' $r.manager67FreshRuntimeBoundaries[1] 'MANAGER67_AUTHORITY_CARD_HIT_REGION_INDEPENDENT_BINDING_MISSING';Eq 'live ops' $r.liveOperations 0;Eq 'inputs' $r.gameInputs 0;Eq 'permit' $r.permitIssued $false
 $p=Variant permit {param($j)$j.permitEligible=$true};Eq 'permit promotion rejected' (Run $p).result 'FAIL'
 $p=Variant launch {param($j)$j.launchEligible=$true};Eq 'launch promotion rejected' (Run $p).result 'FAIL'
 $p=Variant policy {param($j)$j.blockers=@($j.blockers|Where-Object{$_-ne'ACTIVATION_BUDGET_STAGE_CONTRACT_MISMATCH'})};Eq 'policy removal rejected' (Run $p).result 'FAIL'
 $p=Variant fresh {param($j)$j.blockers=@($j.blockers|Where-Object{$_-ne'FRESH_MANAGER67_AUTHORITY_CARD_SNAPSHOT_MISSING'})};Eq 'fresh manager67 removal rejected' (Run $p).result 'FAIL'
 $p=Variant hitbinding {param($j)$j.blockers=@($j.blockers|Where-Object{$_-ne'MANAGER67_AUTHORITY_CARD_HIT_REGION_INDEPENDENT_BINDING_MISSING'})};Eq 'manager67 independent hit binding removal rejected' (Run $p).result 'FAIL'
 $p=Variant status {param($j)$j.staticPreparation.manager67AuthorityCardCollector.status='LIVE_PASS'};Eq 'manager67 live promotion rejected' (Run $p).result 'FAIL'
 $p=Variant runtime {param($j)$j.staticPreparation.manager67AuthorityCardCollector.runtimeObserved='PASS'};Eq 'manager67 runtime promotion rejected' (Run $p).result 'FAIL'
 $p=Variant semantic {param($j)$j.staticPreparation.manager67AuthorityCardCollector.semantic='CAPTAIN_PORTRAIT_PROVEN'};Eq 'captain semantic promotion rejected' (Run $p).result 'FAIL'
 $p=Variant fixture {param($j)$j.staticPreparation.manager67AuthorityCardCollector.fixtureCoordinateReusable=$true};Eq 'fixture coordinate promotion rejected' (Run $p).result 'FAIL'
 $p=Variant managerhash {param($j)$j.boundArtifacts.manager67Verification.sha256='0'*64};Eq 'manager67 hash mutation rejected' (Run $p).result 'FAIL'
 $p=Variant priorhash {param($j)$j.boundArtifacts.priorV2Contract.sha256='0'*64};Eq 'prior v2 hash mutation rejected' (Run $p).result 'FAIL'
 $p=Variant budget {param($j)$j.currentAuthority.activationBudget=3};Eq 'budget inflation rejected' (Run $p).result 'FAIL'
 $p=Variant counters {param($j)$j.operationCounters.processMemoryReads=1};Eq 'operation counter mutation rejected' (Run $p).result 'FAIL'
 $p=Variant sequence {param($j)$j.requiredSameRunComposition[3]='selected captain portrait'};Eq 'unsafe composition terminology rejected' (Run $p).result 'FAIL'
 $p=Variant boundary {param($j)$j.firstTechnicalBoundary='FRESH_MANAGER67_AUTHORITY_CARD_SNAPSHOT_MISSING'};Eq 'technical ordering mutation rejected' (Run $p).result 'FAIL'
 [ordered]@{result='PASS';cases=16;assertions=$script:n}|ConvertTo-Json
}finally{if(Test-Path $temp){$resolved=(Resolve-Path $temp).Path;$base=[IO.Path]::GetFullPath([IO.Path]::GetTempPath());if(-not$resolved.StartsWith($base,[StringComparison]::OrdinalIgnoreCase)){throw'unsafe temp cleanup'};Remove-Item -LiteralPath $resolved -Recurse -Force}}
