$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$collector=Join-Path $root 'src/collect-warp-stage-owner-snapshot.ps1'
if(-not(Test-Path -LiteralPath $collector)){throw 'RED: warp stage owner collector missing'}
$memory=Join-Path $PSScriptRoot 'fixture-memory.json';$identity=Join-Path $PSScriptRoot 'fixture-identity.json'
$temp=Join-Path ([IO.Path]::GetTempPath()) ('logh7-warp-owner-'+[guid]::NewGuid().ToString('N'));New-Item -ItemType Directory $temp|Out-Null
$script:n=0
function Eq($name,$actual,$expected){$script:n++;if($actual-ne$expected){throw "$name expected=$expected actual=$actual"}}
function Run($m=$memory,$i=$identity){$o=Join-Path $temp (([guid]::NewGuid().ToString('N'))+'.json');$null=&$collector -FixtureMemoryPath $m -FixtureIdentityPath $i -OutputPath $o;Get-Content $o -Raw -Encoding UTF8|ConvertFrom-Json}
function MemoryVariant($name,[scriptblock]$change){$j=Get-Content $memory -Raw -Encoding UTF8|ConvertFrom-Json;&$change $j;$p=Join-Path $temp "$name.json";$j|ConvertTo-Json -Depth 20|Set-Content $p -Encoding UTF8;$p}
function IdentityVariant($name,[scriptblock]$change){$j=Get-Content $identity -Raw -Encoding UTF8|ConvertFrom-Json;&$change $j;$p=Join-Path $temp "$name.json";$j|ConvertTo-Json -Depth 20|Set-Content $p -Encoding UTF8;$p}
try{
 $r=Run
 Eq 'canonical state eligible' $r.stateEligible $true;Eq 'runtime status' $r.runtimeBindingStatus 'OFFLINE_FIXTURE_ONLY';Eq 'active owner' $r.stageOwner.pointer '0x05000000';Eq 'factory command' $r.stageOwner.commandId 43;Eq 'authority card id' $r.stageOwner.cardId 7;Eq 'child count' $r.stageOwner.childCount 6;Eq 'text child index' $r.textDialog.childIndex 2;Eq 'text vtable' $r.textDialog.vtable '0x00675780';Eq 'builder' $r.textDialog.builder 4;Eq 'manager index' $r.textDialog.managerIndex 3;Eq 'manager pointer' $r.textDialog.managerPointer '0x00CA292C';Eq 'layout' $r.manager.layout 4;Eq 'terminal' $r.manager.terminalState 1;Eq 'fixture reusable' $r.fixtureCoordinateReusable $false;Eq 'inputs' $r.operations.gameInputs 0;Eq 'permit' $r.permitIssued $false
 $p=MemoryVariant nullowner {param($j)$j.first.u32.'0x00C9E2F8'=0};Eq 'null owner blocked' (Run $p).stateEligible $false
 $p=MemoryVariant wrongcommand {param($j)$j.first.u32.'0x05000028'=42};Eq 'wrong command blocked' (Run $p).stateEligible $false
 $p=MemoryVariant wrongcard {param($j)$j.first.u32.'0x05000020'=8};Eq 'authority card mismatch blocked' (Run $p).stateEligible $false
 $p=MemoryVariant badvector {param($j)$j.first.u32.'0x05000014'=100663316};Eq 'bad child count blocked' (Run $p).stateEligible $false
 $p=MemoryVariant malformedvector {param($j)$j.first.u32.'0x05000014'=100663292};Eq 'malformed vector invariant blocked' (Run $p).stateEligible $false
 $p=MemoryVariant ownervtable {param($j)$j.first.u32.'0x05000000'=6750760};Eq 'owner vtable blocked' (Run $p).stateEligible $false
 $p=MemoryVariant wrongsequence {param($j)$j.first.u32.'0x07000100'=6772608};Eq 'wrong sequence blocked' (Run $p).stateEligible $false
 $p=MemoryVariant builder {param($j)$j.first.u32.'0x07000238'=5};Eq 'wrong builder blocked' (Run $p).stateEligible $false
 $p=MemoryVariant variant {param($j)$j.first.u32.'0x0700023C'=1};Eq 'wrong variant blocked' (Run $p).stateEligible $false
 $p=MemoryVariant wrongparams {param($j)$j.first.u32.'0x07000240'=2};Eq 'wrong manager index blocked' (Run $p).stateEligible $false
 $p=MemoryVariant wrongtextcommand {param($j)$j.first.u32.'0x07000248'=60};Eq 'wrong TextDialog command blocked' (Run $p).stateEligible $false
 $p=MemoryVariant commandarg {param($j)$j.first.u32.'0x0700024C'=1};Eq 'wrong command arg blocked' (Run $p).stateEligible $false
 $p=MemoryVariant wrongmanager {param($j)$j.first.u32.'0x07000258'=13248816};Eq 'wrong manager pointer blocked' (Run $p).stateEligible $false
 $p=MemoryVariant uicontext {param($j)$j.first.u32.'0x00CA2934'=0};Eq 'null UI context blocked' (Run $p).stateEligible $false
 $p=MemoryVariant wronglayout {param($j)$j.first.i32.'0x00CA2CA8'=5};Eq 'wrong manager layout blocked' (Run $p).stateEligible $false
 $p=MemoryVariant terminal {param($j)$j.first.i32.'0x00CA370C'=3};Eq 'terminal state blocked' (Run $p).stateEligible $false
 $p=MemoryVariant sendflag {param($j)$j.first.u8.'0x07000328'=0};Eq 'SendWarp flag blocked' (Run $p).stateEligible $false
 $p=MemoryVariant receivepair {param($j)$j.first.u32.'0x0700052C'=2818};Eq 'receive opcode pair blocked' (Run $p).stateEligible $false
 $p=MemoryVariant torn {param($j)$j.second=($j.first|ConvertTo-Json -Depth 20|ConvertFrom-Json);$j.second.u32.'0x05000028'=42};Eq 'torn snapshot blocked' (Run $p).stateEligible $false
 $p=IdentityVariant hash {param($j)$j.sha256='0'*64};Eq 'wrong executable hash blocked' (Run $memory $p).stateEligible $false
 $p=IdentityVariant surface {param($j)$j.secondHwndOwnerPid=999};Eq 'HWND surface tear blocked' (Run $memory $p).stateEligible $false
 [ordered]@{result='PASS';cases=22;assertions=$script:n}|ConvertTo-Json
}finally{if(Test-Path $temp){$resolved=(Resolve-Path $temp).Path;$base=[IO.Path]::GetFullPath([IO.Path]::GetTempPath());if(-not$resolved.StartsWith($base,[StringComparison]::OrdinalIgnoreCase)){throw'unsafe cleanup'};Remove-Item -LiteralPath $resolved -Recurse -Force}}
