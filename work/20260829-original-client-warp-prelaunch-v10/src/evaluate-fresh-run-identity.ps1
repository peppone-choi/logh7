[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$ReceiptPath)
$ErrorActionPreference='Stop'
$receipt=Get-Content -LiteralPath $ReceiptPath -Raw -Encoding UTF8|ConvertFrom-Json
$errors=[Collections.Generic.List[string]]::new()
function Add-Error($message){$errors.Add([string]$message)}
function Eq($name,$actual,$expected){if($actual-ne$expected){Add-Error "$name expected=$expected actual=$actual"}}
function Seq($name,$actual,$expected){$a=if($null-eq$actual){@()}else{@($actual)};$e=if($null-eq$expected){@()}else{@($expected)};if(($a|ConvertTo-Json -Compress -Depth 15)-ne($e|ConvertTo-Json -Compress -Depth 15)){Add-Error "$name sequence mismatch"}}
function Exact-Keys($name,$object,$expected){if($null-eq$object){Add-Error "$name missing";return};Seq "$name keys" @($object.PSObject.Properties.Name|Sort-Object) @($expected|Sort-Object)}
try{
 Exact-Keys 'root' $receipt @('schemaVersion','provenance','observedAtUtc','desktop','processes','windows','network','operations')
 Exact-Keys 'desktop' $receipt.desktop @('guestUser','computerName','sessionId','interactive','foregroundHwnd')
 Exact-Keys 'network' $receipt.network @('targetPid','serverPort','connections')
 Exact-Keys 'operations' $receipt.operations @('guestOperations','processMemoryReads','processMemoryWrites','debuggerCommands','breakpointsInstalled','gameInputs','automaticInputs','captures','permitIssued')
 Eq 'schema' $receipt.schemaVersion 1
 if($receipt.provenance-notin@('SYNTHETIC_FIXTURE','LIVE_READONLY')){Add-Error 'provenance is not an accepted collector mode'}
 if([string]::IsNullOrWhiteSpace([string]$receipt.desktop.guestUser)){Add-Error 'guest user missing'}
 if([string]::IsNullOrWhiteSpace([string]$receipt.desktop.computerName)){Add-Error 'computer name missing'}
 if([int]$receipt.desktop.sessionId-lt0){Add-Error 'interactive session id invalid'}
 Eq 'interactive desktop' $receipt.desktop.interactive $true
 $clients=@($receipt.processes|Where-Object{$_.role-eq'CLIENT'});$debuggers=@($receipt.processes|Where-Object{$_.role-eq'DEBUGGER'})
 Eq 'client count' $clients.Count 1;Eq 'debugger count' $debuggers.Count 1
 $processKeys=@('role','name','pid','startTimeUtc','executablePath','executableSha256','responding','moduleBase','moduleSize','mainWindowHandle')
 foreach($p in @($receipt.processes)){Exact-Keys "process $($p.role)" $p $processKeys;if([int]$p.pid-le0){Add-Error "$($p.role) pid invalid"};if([string]::IsNullOrWhiteSpace([string]$p.startTimeUtc)){Add-Error "$($p.role) start time missing"}else{[void][DateTimeOffset]::Parse([string]$p.startTimeUtc)};if([string]::IsNullOrWhiteSpace([string]$p.executablePath)){Add-Error "$($p.role) path missing"};if(([string]$p.executableSha256)-notmatch'^[0-9A-F]{64}$'){Add-Error "$($p.role) hash invalid"};Eq "$($p.role) responding" $p.responding $true;if(([string]$p.moduleBase)-notmatch'^0x[0-9A-Fa-f]{8,16}$'-or[Convert]::ToUInt64(([string]$p.moduleBase).Substring(2),16)-eq0){Add-Error "$($p.role) module base invalid"};if([int64]$p.moduleSize-le0){Add-Error "$($p.role) module size invalid"};if(([string]$p.mainWindowHandle)-notmatch'^0x[0-9A-Fa-f]{16}$'-or[Convert]::ToUInt64(([string]$p.mainWindowHandle).Substring(2),16)-eq0){Add-Error "$($p.role) main HWND invalid"}}
 $client=$clients|Select-Object -First 1;$debugger=$debuggers|Select-Object -First 1
 if($null-ne$client){Eq 'client name' $client.name 'G7MTClient';Eq 'client canonical hash' $client.executableSha256 'BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16'}
 if($null-ne$debugger){Eq 'debugger name' $debugger.name 'x32dbg';Eq 'debugger canonical hash' $debugger.executableSha256 '42CF419B3549332AF44A8500E99085A0C590547CAE6950623FE592EA885711C6'}
 $windowKeys=@('hwnd','ownerPid','foreground','visible','title','class','windowRect','clientRect')
 foreach($w in @($receipt.windows)){Exact-Keys "window $($w.hwnd)" $w $windowKeys;Exact-Keys "windowRect $($w.hwnd)" $w.windowRect @('left','top','right','bottom');Exact-Keys "clientRect $($w.hwnd)" $w.clientRect @('left','top','right','bottom')}
 if($null-ne$client){$clientWindows=@($receipt.windows|Where-Object{$_.hwnd-eq$client.mainWindowHandle-and$_.ownerPid-eq$client.pid-and$_.visible});Eq 'owned visible client window count' $clientWindows.Count 1}
 if($null-ne$debugger){$debuggerWindows=@($receipt.windows|Where-Object{$_.hwnd-eq$debugger.mainWindowHandle-and$_.ownerPid-eq$debugger.pid-and$_.visible});Eq 'owned visible debugger window count' $debuggerWindows.Count 1}
 Eq 'server port' $receipt.network.serverPort 47900
 if($null-ne$client){Eq 'network target pid' $receipt.network.targetPid $client.pid}
 $connectionKeys=@('protocol','localEndpoint','remoteEndpoint','state','pid')
 foreach($c in @($receipt.network.connections)){Exact-Keys "connection $($c.localEndpoint)" $c $connectionKeys}
 $established=@($receipt.network.connections|Where-Object{$_.protocol-eq'TCP'-and$_.pid-eq$receipt.network.targetPid-and$_.state-eq'ESTABLISHED'-and$_.remoteEndpoint-match':47900$'})
 if($established.Count-lt1){Add-Error 'no established client connection to server port 47900'}
 Eq 'guest operation count' $receipt.operations.guestOperations 1
 foreach($name in @('processMemoryReads','processMemoryWrites','debuggerCommands','breakpointsInstalled','gameInputs','automaticInputs','captures')){Eq "operation $name" $receipt.operations.$name 0}
 Eq 'permit issued' $receipt.operations.permitIssued $false
}catch{Add-Error "evaluation exception: $($_.Exception.Message)"}
if($errors.Count){[ordered]@{result='FAIL';errors=@($errors);livePromotionAllowed=$false;gameInputs=0;permitIssued=$false}|ConvertTo-Json -Depth 12;return}
$clientForeground=($clientWindows.Count-eq1-and[bool]$clientWindows[0].foreground-and$receipt.desktop.foregroundHwnd-eq$client.mainWindowHandle)
[ordered]@{result='PASS';state=if($receipt.provenance-eq'LIVE_READONLY'){'LIVE_READONLY_CANDIDATE_UNREVIEWED'}else{'STRUCTURALLY_READY_SYNTHETIC_NOT_LIVE'};clientPid=[int]$client.pid;debuggerPid=[int]$debugger.pid;clientHwnd=[string]$client.mainWindowHandle;establishedServerConnection=$true;clientForeground=$clientForeground;foregroundBoundaryStatus=if($clientForeground){'PASS'}else{'MISSING'};livePromotionAllowed=$false;gameInputs=0;permitIssued=$false}|ConvertTo-Json -Depth 10
