$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot;$evaluator=Join-Path $root 'src/evaluate-heartbeat-binding.ps1';if(-not(Test-Path -LiteralPath $evaluator)){throw 'RED: heartbeat binding evaluator missing'}
$identity=Join-Path $PSScriptRoot 'fixture-fresh-identity.json';$network=Join-Path $PSScriptRoot 'fixture-guest-port47900-binding.json';$trace=Join-Path $PSScriptRoot 'fixture-heartbeat-trace.jsonl';$temp=Join-Path ([IO.Path]::GetTempPath()) ('logh7-heartbeat-'+[guid]::NewGuid().ToString('N'));New-Item -ItemType Directory -Path $temp|Out-Null
$script:cases=0;$script:assertions=0
function Eq($name,$actual,$expected){$script:assertions++;if($actual-ne$expected){throw "$name expected=$expected actual=$actual"}}
function Run($i,$n,$t){$script:cases++;&$evaluator -IdentityPath $i -NetworkPath $n -TracePath $t|ConvertFrom-Json}
function JsonVariant($name,$source,[scriptblock]$change){$j=Get-Content -LiteralPath $source -Raw -Encoding UTF8|ConvertFrom-Json;&$change $j;$p=Join-Path $temp ($name+'.json');$j|ConvertTo-Json -Depth 20|Set-Content -LiteralPath $p -Encoding UTF8;$p}
function TraceVariant($name,[scriptblock]$change){$rows=@(Get-Content -LiteralPath $trace -Encoding UTF8|ForEach-Object{$_|ConvertFrom-Json});&$change $rows;$p=Join-Path $temp ($name+'.jsonl');$rows|ForEach-Object{$_|ConvertTo-Json -Compress -Depth 10}|Set-Content -LiteralPath $p -Encoding UTF8;$p}
try{
 $r=Run $identity $network $trace;Eq 'result' $r.result 'PASS';Eq 'state' $r.state 'APPLICATION_HEARTBEAT_BOUND_SYNTHETIC_NOT_LIVE';Eq 'server pid' $r.serverPid 8668;Eq 'client pid' $r.clientPid 3448;Eq 'connection' $r.connectionId 3;Eq 'request' $r.requestCode '0x0f08';Eq 'response' $r.responseCode '0x0f09';Eq 'heartbeat time' $r.heartbeatAtUtc '2026-08-29T00:00:02.0100000Z';Eq 'promotion' $r.livePromotionAllowed $false;Eq 'inputs' $r.gameInputs 0;Eq 'permit' $r.permitIssued $false
 $variants=[ordered]@{
  noListener=@($identity,(JsonVariant noListener $network {param($j)$j.rows=@($j.rows|Where-Object{$_.state-ne'LISTENING'})}),$trace)
  noClientConnection=@($identity,(JsonVariant noClientConnection $network {param($j)$j.rows=@($j.rows|Where-Object{$_.pid-ne3448})}),$trace)
  serverPidMismatch=@($identity,(JsonVariant serverPidMismatch $network {param($j)($j.processes|Where-Object{$_.name-eq'node'}).pid=9999}),$trace)
  heartbeatBeforeIdentity=@((JsonVariant heartbeatBeforeIdentity $identity {param($j)$j.observedAtUtc='2026-08-29T00:00:03.0000000Z'}),$network,$trace)
  noDecode=@($identity,$network,(TraceVariant noDecode {param($rows)$rows[1].event='other'}))
  wrongRequest=@($identity,$network,(TraceVariant wrongRequest {param($rows)$rows[1].innerCodeHex='0x0f07'}))
  noResponse=@($identity,$network,(TraceVariant noResponse {param($rows)$rows[2].event='other'}))
  wrongResponse=@($identity,$network,(TraceVariant wrongResponse {param($rows)$rows[2].codes=@('0x0f0a')}))
  processProofMissing=@($identity,$network,(TraceVariant processProofMissing {param($rows)$rows[3].processId=0}))
 }
 foreach($entry in $variants.GetEnumerator()){Eq ($entry.Key+' rejected') (Run $entry.Value[0] $entry.Value[1] $entry.Value[2]).result 'FAIL'}
 [ordered]@{result='PASS';cases=$script:cases;assertions=$script:assertions;mutations=$variants.Count}|ConvertTo-Json
}finally{if(Test-Path -LiteralPath $temp){$resolved=(Resolve-Path -LiteralPath $temp).Path;$tempRoot=[IO.Path]::GetFullPath([IO.Path]::GetTempPath());if(-not$resolved.StartsWith($tempRoot,[StringComparison]::OrdinalIgnoreCase)){throw 'unsafe cleanup'};Remove-Item -LiteralPath $resolved -Recurse -Force}}
