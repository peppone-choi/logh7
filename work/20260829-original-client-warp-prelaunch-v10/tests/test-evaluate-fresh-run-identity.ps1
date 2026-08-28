$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$evaluator=Join-Path $root 'src/evaluate-fresh-run-identity.ps1'
$fixture=Join-Path $PSScriptRoot 'fixture-fresh-identity.json'
if(-not(Test-Path -LiteralPath $evaluator)){throw 'RED: fresh-run identity evaluator missing'}
$temp=Join-Path ([IO.Path]::GetTempPath()) ('logh7-fresh-identity-'+[guid]::NewGuid().ToString('N'));New-Item -ItemType Directory -Path $temp|Out-Null
$script:cases=0;$script:assertions=0
function Eq($name,$actual,$expected){$script:assertions++;if($actual-ne$expected){throw "$name expected=$expected actual=$actual"}}
function Run($path){$script:cases++;&$evaluator -ReceiptPath $path|ConvertFrom-Json}
function Variant($name,[scriptblock]$change){$j=Get-Content -LiteralPath $fixture -Raw -Encoding UTF8|ConvertFrom-Json;&$change $j;$p=Join-Path $temp ($name+'.json');$j|ConvertTo-Json -Depth 30|Set-Content -LiteralPath $p -Encoding UTF8;$p}
try{
 $r=Run $fixture;Eq 'result' $r.result 'PASS';Eq 'state' $r.state 'STRUCTURALLY_READY_SYNTHETIC_NOT_LIVE';Eq 'client pid' $r.clientPid 3448;Eq 'debugger pid' $r.debuggerPid 6548;Eq 'client hwnd' $r.clientHwnd '0x00000000001A0490';Eq 'network' $r.establishedServerConnection $true;Eq 'client foreground' $r.clientForeground $false;Eq 'foreground boundary' $r.foregroundBoundaryStatus 'MISSING';Eq 'live promotion' $r.livePromotionAllowed $false;Eq 'inputs' $r.gameInputs 0;Eq 'permit' $r.permitIssued $false
 $mutations=[ordered]@{
  schema={param($j)$j.schemaVersion=2}
  provenance={param($j)$j.provenance='SELF_ASSERTED_LIVE'}
  duplicateClient={param($j)$j.processes+=@($j.processes[0])}
  clientHash={param($j)$j.processes[0].executableSha256='0'*64}
  clientPid={param($j)$j.processes[0].pid=0}
  moduleBase={param($j)$j.processes[0].moduleBase='0x00000000'}
  moduleSize={param($j)$j.processes[0].moduleSize=0}
  unresponsive={param($j)$j.processes[0].responding=$false}
  windowOwner={param($j)$j.windows[0].ownerPid=9999}
  invisible={param($j)$j.windows[0].visible=$false}
  noninteractive={param($j)$j.desktop.interactive=$false}
  disconnected={param($j)$j.network.connections=@()}
  wrongPort={param($j)$j.network.connections[0].remoteEndpoint='192.168.203.1:47901'}
  memoryRead={param($j)$j.operations.processMemoryReads=1}
  debuggerCommand={param($j)$j.operations.debuggerCommands=1}
  breakpoint={param($j)$j.operations.breakpointsInstalled=1}
  input={param($j)$j.operations.gameInputs=1}
  permit={param($j)$j.operations.permitIssued=$true}
  extraRoot={param($j)$j|Add-Member -NotePropertyName liveReady -NotePropertyValue $true}
 }
 foreach($entry in $mutations.GetEnumerator()){Eq ($entry.Key+' rejected') (Run (Variant $entry.Key $entry.Value)).result 'FAIL'}
 [ordered]@{result='PASS';cases=$script:cases;assertions=$script:assertions;mutations=$mutations.Count}|ConvertTo-Json
}finally{if(Test-Path -LiteralPath $temp){$resolved=(Resolve-Path -LiteralPath $temp).Path;$tempRoot=[IO.Path]::GetFullPath([IO.Path]::GetTempPath());if(-not$resolved.StartsWith($tempRoot,[StringComparison]::OrdinalIgnoreCase)){throw 'unsafe cleanup'};Remove-Item -LiteralPath $resolved -Recurse -Force}}
