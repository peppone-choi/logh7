$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$evaluator=Join-Path $root 'src/evaluate-interactive-canary.ps1'
$collector=Join-Path $root 'src/collect-interactive-session-canary.ps1'
$fixture=Join-Path $PSScriptRoot 'fixture-interactive-canary.json'
$temp=Join-Path ([IO.Path]::GetTempPath()) ('interactive-canary-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp|Out-Null
$script:a=0;$script:c=0
function Eq($n,$x,$y){$script:a++;if($x-ne$y){throw "$n expected=$y actual=$x"}}
function Sha($p){(Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash}
function New-Binding($receiptPath){
 $receipt=Get-Content $receiptPath -Raw -Encoding UTF8|ConvertFrom-Json
 $stem=[IO.Path]::GetFileNameWithoutExtension($receiptPath)
 $prelaunchPath=Join-Path $temp "$stem-prelaunch.json";$brokerPath=Join-Path $temp "$stem-broker.json";$startedPath=Join-Path $temp "$stem-started.json";$diagnosticPath=Join-Path $temp "$stem-diagnostic.json"
 $guestStarted='C:\LOGH7_ORACLE\interactive-session-canary-started.json';$guestRaw='C:\LOGH7_ORACLE\interactive-session-canary-receipt.json';$guestDiagnostic='C:\LOGH7_ORACLE\interactive-session-canary-diagnostic.json'
 [ordered]@{schemaVersion=1;provenance='LIVE_READONLY_SESSION_DIAGNOSTIC';observedAtUtc='2026-08-28T23:59:50.0000000Z';activeConsoleSessionId=1;processes=@([ordered]@{name='G7MTClient';sessionId=1},[ordered]@{name='x32dbg';sessionId=1})}|ConvertTo-Json -Depth 8|Set-Content $prelaunchPath -Encoding UTF8
 [ordered]@{schemaVersion=1;provenance='LIVE_READONLY_BROKER_INVENTORY';observedAtUtc='2026-08-28T23:59:55.0000000Z';caller=[ordered]@{name='LOGH7-ORACLE-HD\logh7-oracle'};processes=@([ordered]@{name='vmtoolsd';sessionId=0},[ordered]@{name='vmtoolsd';sessionId=1})}|ConvertTo-Json -Depth 8|Set-Content $brokerPath -Encoding UTF8
 [ordered]@{status='STARTED';runId=$receipt.runId;capturedAtUtc='2026-08-29T00:00:00.0010000Z';helper=$receipt.helper}|ConvertTo-Json -Depth 8|Set-Content $startedPath -Encoding UTF8
 [ordered]@{status='PASS';runId=$receipt.runId;capturedAtUtc='2026-08-29T00:00:00.2100000Z';outputPath=$guestRaw;startedPath=$guestStarted;scriptSha256=(Sha $collector)}|ConvertTo-Json -Depth 8|Set-Content $diagnosticPath -Encoding UTF8
 [ordered]@{
  schemaVersion=1;runId=$receipt.runId;provenance='HOST_BOUND_LIVE_INTERACTIVE_CANARY';rawReceiptPath=$receiptPath;rawReceiptSha256=(Sha $receiptPath)
  collectorPath=$collector;collectorSha256=(Sha $collector);guestCollectorPath=$receipt.helper.scriptPath
  prelaunchSessionReceiptPath=$prelaunchPath;prelaunchSessionReceiptSha256=(Sha $prelaunchPath)
  brokerReceiptPath=$brokerPath;brokerReceiptSha256=(Sha $brokerPath)
  startedReceiptPath=$startedPath;startedReceiptSha256=(Sha $startedPath);guestStartedPath=$guestStarted
  diagnosticReceiptPath=$diagnosticPath;diagnosticReceiptSha256=(Sha $diagnosticPath);guestDiagnosticPath=$guestDiagnostic;guestRawReceiptPath=$guestRaw
  programExecutable='C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe'
  argumentVector=@('-NoProfile','-WindowStyle','Hidden','-ExecutionPolicy','Bypass','-File',$receipt.helper.scriptPath,'-RunId',$receipt.runId,'-OutputPath',$guestRaw,'-StartedPath',$guestStarted,'-DiagnosticPath',$guestDiagnostic)
  vmrunInteractive=$true;vmrunActiveWindow=$false;vmrunHostExitCode=0;guestSourceCopies=1;helperLaunchCalls=1;captureCopyCalls=3
 }
}
function Run($mutator,[bool]$bind=$false){
 $script:c++
 $x=Get-Content $fixture -Raw -Encoding UTF8|ConvertFrom-Json
 if($mutator){&$mutator $x}
 $i=Join-Path $temp "i$($script:c).json";$o=Join-Path $temp "o$($script:c).json";$b=Join-Path $temp "b$($script:c).json"
 if($bind-and$x.provenance-eq'LIVE_READONLY_INTERACTIVE_CANARY'){$x.helper.scriptSha256=Sha $collector}
 $x|ConvertTo-Json -Depth 20|Set-Content $i -Encoding UTF8
 $invokeArgs=@{ReceiptPath=$i;OutputPath=$o}
 if($bind){New-Binding $i|ConvertTo-Json -Depth 8|Set-Content $b -Encoding UTF8;$invokeArgs.BindingPath=$b}
 &$evaluator @invokeArgs|Out-Null
 Get-Content $o -Raw -Encoding UTF8|ConvertFrom-Json
}
try{
 $good=Run $null
 Eq 'status' $good.status 'STRUCTURALLY_READY_SYNTHETIC_NOT_LIVE';Eq 'candidate' $good.interactiveHwndCandidateEligible $true
 Eq 'promotion' $good.livePromotionAllowed $false;Eq 'launch' $good.prelaunchEligible $false
 Eq 'client hwnd' $good.client.hwnd '0x00000000001A0490';Eq 'foreground unchanged' $good.foregroundUnchanged $true
 $claimed=Run {param($x)$x.provenance='LIVE_READONLY_INTERACTIVE_CANARY'}
 Eq 'claimed live unbound' $claimed.status 'CLAIMED_LIVE_UNBOUND';Eq 'claimed candidate false' $claimed.interactiveHwndCandidateEligible $false
 $bound=Run {param($x)$x.provenance='LIVE_READONLY_INTERACTIVE_CANARY'} $true
 Eq 'bound live status' $bound.status 'INTERACTIVE_HWND_LIVE_CANDIDATE_UNREVIEWED';Eq 'bound candidate' $bound.interactiveHwndCandidateEligible $true
 Eq 'bound no promotion' $bound.livePromotionAllowed $false;Eq 'bound no prelaunch' $bound.prelaunchEligible $false
 $mutations=@(
  @{n='root extra';m={param($x)$x|Add-Member extra 1};b='SCHEMA_ROOT_KEYS_MISMATCH'},
  @{n='session0';m={param($x)$x.helper.sessionId=0};b='HELPER_SESSION_NOT_ACTIVE_CONSOLE'},
  @{n='console mismatch';m={param($x)$x.helper.activeConsoleSessionId=2};b='HELPER_SESSION_NOT_ACTIVE_CONSOLE'},
  @{n='winsta';m={param($x)$x.helper.windowStation='Service-0x0-3e7$'};b='HELPER_WINDOW_STATION_NOT_WINSTA0'},
  @{n='desktop';m={param($x)$x.helper.desktop='Disconnect'};b='HELPER_DESKTOP_NOT_DEFAULT'},
  @{n='snapshot missing';m={param($x)$x.snapshots=@($x.snapshots[0])};b='SNAPSHOT_COUNT_NOT_2'},
  @{n='snapshot label';m={param($x)$x.snapshots[1].label='A'};b='SNAPSHOT_LABELS_INVALID'},
  @{n='snapshot torn owner';m={param($x)$x.snapshots[1].windows[0].ownerPid=99};b='TORN_SNAPSHOT'},
  @{n='self stable false';m={param($x)$x.snapshotStable=$false};b='TORN_SNAPSHOT'},
  @{n='client duplicate';m={param($x)$x.snapshots[1].processes+=@($x.snapshots[1].processes[0])};b='CLIENT_PROCESS_COUNT_NOT_1'},
  @{n='client name';m={param($x)$x.snapshots[1].processes[0].name='Wrong'};b='CLIENT_NAME_MISMATCH'},
  @{n='client hash';m={param($x)$x.snapshots[1].processes[0].sha256=('0'*64)};b='CLIENT_HASH_MISMATCH'},
  @{n='client session';m={param($x)$x.snapshots[1].processes[0].sessionId=0};b='CLIENT_SESSION_MISMATCH'},
  @{n='client hwnd zero';m={param($x)$x.snapshots[1].processes[0].mainWindowHandle='0x0000000000000000'};b='CLIENT_MAIN_HWND_INVALID'},
  @{n='client module size';m={param($x)$x.snapshots[1].processes[0].moduleSize=0};b='CLIENT_MODULE_IDENTITY_INVALID'},
  @{n='client start';m={param($x)$x.snapshots[1].processes[0].startTimeUtc='bad'};b='CLIENT_START_TIME_INVALID'},
  @{n='window owner';m={param($x)$x.snapshots[1].windows[0].ownerPid=99};b='CLIENT_WINDOW_OWNER_MISMATCH'},
  @{n='window hidden';m={param($x)$x.snapshots[1].windows[0].visible=$false};b='CLIENT_VISIBLE_WINDOW_COUNT_NOT_1'},
  @{n='surface';m={param($x)$x.snapshots[1].windows[0].clientRect.right=0};b='CLIENT_SURFACE_INVALID'},
  @{n='debugger hash';m={param($x)$x.snapshots[1].processes[1].sha256=('0'*64)};b='DEBUGGER_HASH_MISMATCH'},
  @{n='debugger session';m={param($x)$x.snapshots[1].processes[1].sessionId=0};b='DEBUGGER_SESSION_MISMATCH'},
  @{n='debugger window owner';m={param($x)$x.snapshots[1].windows[1].ownerPid=99};b='DEBUGGER_WINDOW_OWNER_MISMATCH'},
  @{n='foreground';m={param($x)$x.foreground.afterHwnd='0x0000000000030666';$x.foreground.unchanged=$false};b='FOREGROUND_CHANGED'},
  @{n='helper count';m={param($x)$x.operations.helperProcessesCreated=2};b='HELPER_OPERATION_ACCOUNTING_INVALID'},
  @{n='file count';m={param($x)$x.operations.guestFileWrites=2};b='HELPER_OPERATION_ACCOUNTING_INVALID'},
  @{n='read';m={param($x)$x.operations.processMemoryReads=1};b='FORBIDDEN_OPERATION_RECORDED'},
  @{n='write';m={param($x)$x.operations.processMemoryWrites=1};b='FORBIDDEN_OPERATION_RECORDED'},
  @{n='foreground change';m={param($x)$x.operations.foregroundChanges=1};b='FORBIDDEN_OPERATION_RECORDED'},
  @{n='attach';m={param($x)$x.operations.debuggerAttach=1};b='FORBIDDEN_OPERATION_RECORDED'},
  @{n='debug command';m={param($x)$x.operations.debuggerCommands=1};b='FORBIDDEN_OPERATION_RECORDED'},
  @{n='bp';m={param($x)$x.operations.breakpointsInstalled=1};b='FORBIDDEN_OPERATION_RECORDED'},
  @{n='capture';m={param($x)$x.operations.captures=1};b='FORBIDDEN_OPERATION_RECORDED'},
  @{n='input';m={param($x)$x.operations.gameInputs=1};b='FORBIDDEN_OPERATION_RECORDED'},
  @{n='auto input';m={param($x)$x.operations.automaticInputs=1};b='FORBIDDEN_OPERATION_RECORDED'},
  @{n='permit';m={param($x)$x.operations.permitIssued=$true};b='FORBIDDEN_OPERATION_RECORDED'},
  @{n='vm';m={param($x)$x.operations.vmLifecycleChanges=1};b='FORBIDDEN_OPERATION_RECORDED'},
  @{n='server';m={param($x)$x.operations.serverChanges=1};b='FORBIDDEN_OPERATION_RECORDED'},
  @{n='protocol';m={param($x)$x.operations.protocolChanges=1};b='FORBIDDEN_OPERATION_RECORDED'},
  @{n='db';m={param($x)$x.operations.databaseChanges=1};b='FORBIDDEN_OPERATION_RECORDED'}
 )
 foreach($t in $mutations){$r=Run $t.m;Eq "$($t.n) blocker" ($r.blockers-contains$t.b) $true;Eq "$($t.n) candidate false" $r.interactiveHwndCandidateEligible $false;Eq "$($t.n) promotion false" $r.livePromotionAllowed $false}
 $bindingMutations=@(
  @{n='binding receipt hash';m={param($b)$b.rawReceiptSha256='0'*64};b='BINDING_RAW_RECEIPT_HASH_MISMATCH'},
  @{n='binding collector hash';m={param($b)$b.collectorSha256='0'*64};b='BINDING_COLLECTOR_HASH_MISMATCH'},
  @{n='binding interactive';m={param($b)$b.vmrunInteractive=$false};b='BINDING_ROUTE_INVALID'},
  @{n='binding active window';m={param($b)$b.vmrunActiveWindow=$true};b='BINDING_ROUTE_INVALID'},
  @{n='binding program';m={param($b)$b.programExecutable='powershell.exe'};b='BINDING_ROUTE_INVALID'},
  @{n='binding arguments';m={param($b)$b.argumentVector[0]='-Wrong'};b='BINDING_ROUTE_INVALID'},
  @{n='binding run';m={param($b)$b.runId='other-run'};b='BINDING_RUN_MISMATCH'},
  @{n='binding attempts';m={param($b)$b.helperLaunchCalls=2};b='BINDING_ROUTE_INVALID'}
 )
 foreach($t in $bindingMutations){
  $script:c++;$x=Get-Content $fixture -Raw -Encoding UTF8|ConvertFrom-Json;$x.provenance='LIVE_READONLY_INTERACTIVE_CANARY'
  $i=Join-Path $temp "bi$($script:c).json";$o=Join-Path $temp "bo$($script:c).json";$b=Join-Path $temp "bb$($script:c).json";$x|ConvertTo-Json -Depth 20|Set-Content $i -Encoding UTF8
  $binding=New-Binding $i;&$t.m $binding;$binding|ConvertTo-Json -Depth 8|Set-Content $b -Encoding UTF8;&$evaluator -ReceiptPath $i -OutputPath $o -BindingPath $b|Out-Null;$r=Get-Content $o -Raw|ConvertFrom-Json
  Eq "$($t.n) blocker" ($r.blockers-contains$t.b) $true;Eq "$($t.n) candidate false" $r.interactiveHwndCandidateEligible $false
 }
 $supportMutations=@(
  @{n='prelaunch future';path='prelaunchSessionReceiptPath';hash='prelaunchSessionReceiptSha256';m={param($s)$s.observedAtUtc='2026-08-29T00:00:10.0000000Z'};b='BINDING_PRELAUNCH_INVALID'},
  @{n='broker missing interactive agent';path='brokerReceiptPath';hash='brokerReceiptSha256';m={param($s)$s.processes=@($s.processes|Where-Object{[int]$_.sessionId-ne1})};b='BINDING_BROKER_INVALID'},
  @{n='started helper mismatch';path='startedReceiptPath';hash='startedReceiptSha256';m={param($s)$s.helper.pid=9001};b='BINDING_STARTED_HELPER_MISMATCH'},
  @{n='diagnostic not pass';path='diagnosticReceiptPath';hash='diagnosticReceiptSha256';m={param($s)$s.status='FAIL'};b='BINDING_DIAGNOSTIC_INVALID'}
 )
 foreach($t in $supportMutations){
  $script:c++;$x=Get-Content $fixture -Raw -Encoding UTF8|ConvertFrom-Json;$x.provenance='LIVE_READONLY_INTERACTIVE_CANARY';$x.helper.scriptSha256=Sha $collector
  $i=Join-Path $temp "si$($script:c).json";$o=Join-Path $temp "so$($script:c).json";$b=Join-Path $temp "sb$($script:c).json";$x|ConvertTo-Json -Depth 20|Set-Content $i -Encoding UTF8
  $binding=New-Binding $i;$supportPath=[string]$binding.($t.path);$support=Get-Content $supportPath -Raw|ConvertFrom-Json;&$t.m $support;$support|ConvertTo-Json -Depth 10|Set-Content $supportPath -Encoding UTF8;$binding.($t.hash)=Sha $supportPath
  $binding|ConvertTo-Json -Depth 10|Set-Content $b -Encoding UTF8;&$evaluator -ReceiptPath $i -OutputPath $o -BindingPath $b|Out-Null;$r=Get-Content $o -Raw|ConvertFrom-Json
  Eq "$($t.n) blocker" ($r.blockers-contains$t.b) $true;Eq "$($t.n) candidate false" $r.interactiveHwndCandidateEligible $false
 }
 [ordered]@{status='PASS';cases=$script:c;assertions=$script:a;receiptMutations=$mutations.Count;bindingMutations=$bindingMutations.Count;supportMutations=$supportMutations.Count}|ConvertTo-Json -Compress
}finally{if(Test-Path $temp){$resolved=(Resolve-Path $temp).Path;$base=[IO.Path]::GetFullPath([IO.Path]::GetTempPath());if(-not$resolved.StartsWith($base,[StringComparison]::OrdinalIgnoreCase)){throw 'unsafe cleanup'};Remove-Item -LiteralPath $resolved -Recurse -Force}}
