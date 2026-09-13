[CmdletBinding()]
param(
 [Parameter(Mandatory=$true)][string]$IdentityPath,
 [Parameter(Mandatory=$true)][string]$NetworkPath,
 [Parameter(Mandatory=$true)][string]$TracePath
)
$ErrorActionPreference='Stop'
$identity=Get-Content -LiteralPath $IdentityPath -Raw -Encoding UTF8|ConvertFrom-Json
$network=Get-Content -LiteralPath $NetworkPath -Raw -Encoding UTF8|ConvertFrom-Json
$traceRows=@();$lineNumber=0
foreach($line in @(Get-Content -LiteralPath $TracePath -Encoding UTF8)){$lineNumber++;if([string]::IsNullOrWhiteSpace($line)){continue};try{$row=$line|ConvertFrom-Json;$row|Add-Member -NotePropertyName _lineNumber -NotePropertyValue $lineNumber;$traceRows+=,$row}catch{}}
$errors=[Collections.Generic.List[string]]::new()
function Add-Error($message){$errors.Add([string]$message)}
function Eq($name,$actual,$expected){if($actual-ne$expected){Add-Error "$name expected=$expected actual=$actual"}}
try{
 Eq 'identity schema' $identity.schemaVersion 1;Eq 'network schema' $network.schemaVersion 1
 if($identity.provenance-notin@('SYNTHETIC_FIXTURE','LIVE_READONLY')){Add-Error 'identity provenance unsupported'}
 if($network.provenance-notin@('SYNTHETIC_FIXTURE','LIVE_READONLY')){Add-Error 'network provenance unsupported'}
 Eq 'network port' $network.port 47900
 $clients=@($identity.processes|Where-Object{$_.role-eq'CLIENT'});Eq 'identity client count' $clients.Count 1;$client=$clients|Select-Object -First 1
 if($null-ne$client){Eq 'client canonical hash' $client.executableSha256 'BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16'}
 $serverProcesses=@($network.processes|Where-Object{$_.name-eq'node'-and$_.commandLine-match'launch-unit5-fullserver\.mjs'-and$_.commandLine-match'trace2\.jsonl'});Eq 'server process count' $serverProcesses.Count 1;$server=$serverProcesses|Select-Object -First 1
 if($null-ne$server){if([int]$server.pid-le0){Add-Error 'server pid invalid'};if(([string]$server.sha256)-notmatch'^[0-9A-F]{64}$'){Add-Error 'server executable hash invalid'}}
 $listeners=@($network.rows|Where-Object{$_.protocol-eq'TCP'-and$_.state-eq'LISTENING'-and$_.localEndpoint-match':47900$'-and$_.pid-eq$server.pid});Eq 'server listener count' $listeners.Count 1
 $clientRows=@($network.rows|Where-Object{$_.protocol-eq'TCP'-and$_.state-eq'ESTABLISHED'-and$_.pid-eq$client.pid-and$_.remoteEndpoint-match':47900$'});Eq 'client established count' $clientRows.Count 1;$clientRow=$clientRows|Select-Object -First 1
 $serverRows=@();if($null-ne$clientRow){$serverRows=@($network.rows|Where-Object{$_.protocol-eq'TCP'-and$_.state-eq'ESTABLISHED'-and$_.pid-eq$server.pid-and$_.localEndpoint-eq$clientRow.remoteEndpoint-and$_.remoteEndpoint-eq$clientRow.localEndpoint})};Eq 'server accepted pair count' $serverRows.Count 1
 $identityTime=[DateTimeOffset]::Parse([string]$identity.observedAtUtc)
 $decodes=@($traceRows|Where-Object{$_.event-eq'0030-decoded'-and$_.innerCodeHex-eq'0x0f08'-and$_.checksumMismatch-eq$false-and[DateTimeOffset]::Parse([string]$_.ts)-gt$identityTime}|Sort-Object{[DateTimeOffset]::Parse([string]$_.ts)})
 if($decodes.Count-lt1){Add-Error 'no post-identity 0x0f08 heartbeat decode'};$decode=$decodes|Select-Object -Last 1
 $responses=@();if($null-ne$decode){$decodeTime=[DateTimeOffset]::Parse([string]$decode.ts);$responses=@($traceRows|Where-Object{$_.event-eq'world-response-sent'-and$_.connectionId-eq$decode.connectionId-and$_.reqCode-eq'0x0f08'-and@($_.codes)-contains'0x0f09'-and[DateTimeOffset]::Parse([string]$_.ts)-ge$decodeTime-and([DateTimeOffset]::Parse([string]$_.ts)-$decodeTime).TotalSeconds-le5})};Eq 'heartbeat response count' $responses.Count 1;$response=$responses|Select-Object -First 1
 $structured=@();if($null-ne$response){$responseTime=[DateTimeOffset]::Parse([string]$response.ts);$structured=@($traceRows|Where-Object{$_.schemaVersion-eq1-and$_.stage-eq'world-response-sent'-and$_.connectionId-eq$response.connectionId-and$_.processId-eq$server.pid-and$_.outcome-eq'ok'-and[Math]::Abs(([DateTimeOffset]::Parse([string]$_.wallTimeUtc)-$responseTime).TotalSeconds)-le5})};Eq 'structured server response proof count' $structured.Count 1
 foreach($name in @('processMemoryReads','processMemoryWrites','debuggerCommands','gameInputs')){Eq "network operation $name" $network.operations.$name 0};Eq 'network permit' $network.operations.permitIssued $false
}catch{Add-Error "heartbeat evaluation exception: $($_.Exception.Message)"}
if($errors.Count){[ordered]@{result='FAIL';errors=@($errors);livePromotionAllowed=$false;gameInputs=0;permitIssued=$false}|ConvertTo-Json -Depth 12;return}
$live=($identity.provenance-eq'LIVE_READONLY'-and$network.provenance-eq'LIVE_READONLY')
[ordered]@{result='PASS';state=if($live){'APPLICATION_HEARTBEAT_LIVE_CANDIDATE_UNREVIEWED'}else{'APPLICATION_HEARTBEAT_BOUND_SYNTHETIC_NOT_LIVE'};serverPid=[int]$server.pid;clientPid=[int]$client.pid;connectionId=[int]$decode.connectionId;requestCode='0x0f08';responseCode='0x0f09';heartbeatAtUtc=([DateTimeOffset]$decode.ts).ToUniversalTime().ToString('o');traceSha256=(Get-FileHash -LiteralPath $TracePath -Algorithm SHA256).Hash;livePromotionAllowed=$false;gameInputs=0;permitIssued=$false}|ConvertTo-Json -Depth 10
