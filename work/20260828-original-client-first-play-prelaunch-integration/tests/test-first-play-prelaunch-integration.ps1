$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$verifier=Join-Path $root 'src/verify-first-play-prelaunch-integration.ps1'
$contract=Join-Path $root 'evidence/first-play-prelaunch-integration.json'
$temp=Join-Path ([IO.Path]::GetTempPath()) ('logh7-prelaunch-v2-'+[guid]::NewGuid().ToString('N'));New-Item -ItemType Directory $temp|Out-Null
$script:n=0
function Eq($name,$actual,$expected){$script:n++;if($actual-ne$expected){throw "$name expected=$expected actual=$actual"}}
function Run($path){$text=&$verifier -ContractPath $path;$text|ConvertFrom-Json}
function Variant($name,[scriptblock]$change){$j=Get-Content $contract -Raw -Encoding UTF8|ConvertFrom-Json;&$change $j;$p=Join-Path $temp "$name.json";$j|ConvertTo-Json -Depth 20|Set-Content $p -Encoding UTF8;$p}
try{
 $r=Run $contract
 Eq 'canonical result' $r.result 'PASS'
 Eq 'state' $r.state 'OFFLINE_PRELAUNCH_AUDIT_PASS_READY_FALSE'
 Eq 'eligible' $r.permitEligible $false
 Eq 'launch' $r.launchEligible $false
 Eq 'blockers' $r.blockerCount 12
 Eq 'first missing' $r.firstMissingBoundary 'ACTIVATION_BUDGET_STAGE_CONTRACT_MISMATCH'
 Eq 'first technical' $r.firstTechnicalBoundary 'MANAGER67_CURRENT_CARD_COLLECTOR_MISSING'
 Eq 'live ops' $r.liveOperations 0
 Eq 'inputs' $r.gameInputs 0
 Eq 'prior permit' $r.priorPermitState 'CONSUMED_NO_RETRY'

 $p=Variant eligible {param($j)$j.permitEligible=$true};Eq 'eligible mutation rejected' (Run $p).result 'FAIL'
 $p=Variant launch {param($j)$j.launchEligible=$true};Eq 'launch mutation rejected' (Run $p).result 'FAIL'
 $p=Variant prior {param($j)$j.priorPermit.state='REUSABLE'};Eq 'permit reuse rejected' (Run $p).result 'FAIL'
 $p=Variant blocker {param($j)$j.blockers=@($j.blockers|Select-Object -Skip 1)};Eq 'missing blocker rejected' (Run $p).result 'FAIL'
 $p=Variant extra {param($j)$j.blockers+=@('FABRICATED')};Eq 'extra blocker rejected' (Run $p).result 'FAIL'
 $p=Variant manager {param($j)$j.staticPreparation.manager65Collector.status='LIVE_READY'};Eq 'manager promotion rejected' (Run $p).result 'FAIL'
 $p=Variant runtime {param($j)$j.staticPreparation.textDialog.runtimeObserved='PASS'};Eq 'runtime self-promotion rejected' (Run $p).result 'FAIL'
 $p=Variant hash {param($j)$j.boundArtifacts.textDialogVerification.sha256='0'*64};Eq 'stale hash rejected' (Run $p).result 'FAIL'
 $p=Variant input {param($j)$j.operationCounters.physicalInputs=1};Eq 'input rejected' (Run $p).result 'FAIL'
 $p=Variant budget {param($j)$j.currentAuthority.activationBudget=2};Eq 'budget inflation rejected' (Run $p).result 'FAIL'
 $p=Variant scope {param($j)$j.currentAuthority.scope='UNBOUNDED'};Eq 'authority scope rejected' (Run $p).result 'FAIL'
 $p=Variant permitid {param($j)$j.priorPermit.id='different'};Eq 'prior permit id rejected' (Run $p).result 'FAIL'
 $p=Variant sequence {param($j)$j.requiredSameRunComposition[0]='fabricated'};Eq 'same-run sequence rejected' (Run $p).result 'FAIL'
 $p=Variant forbidden {param($j)$j.forbidden[0]='allow reuse'};Eq 'forbidden mutation rejected' (Run $p).result 'FAIL'
 $p=Variant counter {param($j)$value=$j.operationCounters.guestOperations;$j.operationCounters.PSObject.Properties.Remove('guestOperations');$j.operationCounters|Add-Member -NotePropertyName fabricatedCounter -NotePropertyValue $value};Eq 'counter name rejected' (Run $p).result 'FAIL'
 [ordered]@{result='PASS';cases=16;assertions=$script:n}|ConvertTo-Json
}finally{if(Test-Path $temp){$resolved=(Resolve-Path $temp).Path;$base=[IO.Path]::GetFullPath([IO.Path]::GetTempPath());if(-not$resolved.StartsWith($base,[StringComparison]::OrdinalIgnoreCase)){throw 'unsafe temp cleanup target'};Remove-Item -LiteralPath $resolved -Recurse -Force}}
