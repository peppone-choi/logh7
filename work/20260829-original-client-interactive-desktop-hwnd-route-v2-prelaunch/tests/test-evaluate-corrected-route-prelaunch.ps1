$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$evaluator=Join-Path $root 'src\evaluate-corrected-route-prelaunch.ps1'
$preflightCollector=Join-Path $root 'src\collect-corrected-route-preflight.ps1'
$interactiveCollector=Resolve-Path (Join-Path $root '..\20260829-original-client-interactive-desktop-hwnd-route\src\collect-interactive-session-canary.ps1')
$fixture=Join-Path $PSScriptRoot 'fixture-corrected-route-preflight.json'
$vmx='E:\logh7-vms\oracle-win11-hd-re\oracle-win11-hd-re.vmx'
$temp=Join-Path ([IO.Path]::GetTempPath()) ('corrected-route-v2-'+[guid]::NewGuid().ToString('N'));New-Item -ItemType Directory -Path $temp|Out-Null
$script:cases=0;$script:assertions=0
function Eq($n,$a,$b){$script:assertions++;if($a-ne$b){throw "$n expected=$b actual=$a"}}
function Sha($p){(Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash}
function New-Binding($receiptPath,$runId,$outputPath){
 $hostDir=Split-Path -Parent $outputPath;$guestCollector="C:\LOGH7_ORACLE\interactive-canary-$runId.ps1";$guestStarted="C:\LOGH7_ORACLE\interactive-canary-$runId-started.json";$guestRaw="C:\LOGH7_ORACLE\interactive-canary-$runId-receipt.json";$guestDiagnostic="C:\LOGH7_ORACLE\interactive-canary-$runId-diagnostic.json";$hostStarted=Join-Path $hostDir "interactive-canary-$runId-started.json";$hostRaw=Join-Path $hostDir "interactive-canary-$runId-receipt.json";$hostDiagnostic=Join-Path $hostDir "interactive-canary-$runId-diagnostic.json"
 $programCopy=Join-Path $temp "program-copy-$runId.json";$history=Join-Path $temp "history-$runId.json";$absence=Join-Path $temp "absence-$runId.json"
 [ordered]@{schemaVersion=1;provenance='HOST_GUEST_PROGRAM_COPY_RECEIPT';runId=$runId;guestProgramPath='C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe';hostCopyPath=$fixture;hostCopySha256=(Sha $fixture);hostCopyLength=(Get-Item $fixture).Length;copyExitCode=0;copiedAtUtc='2026-08-29T01:00:10.0000000Z'}|ConvertTo-Json -Depth 6|Set-Content $programCopy -Encoding UTF8
 [ordered]@{schemaVersion=1;provenance='IMMUTABLE_ATTEMPT_HISTORY';historicalRuns=@([ordered]@{runId='20260829T183100Z-v1';guestPaths=@('C:\LOGH7_ORACLE\interactive-canary-20260829T183100Z-v1.ps1','C:\LOGH7_ORACLE\interactive-canary-20260829T183100Z-v1-started.json','C:\LOGH7_ORACLE\interactive-canary-20260829T183100Z-v1-receipt.json','C:\LOGH7_ORACLE\interactive-canary-20260829T183100Z-v1-diagnostic.json')})}|ConvertTo-Json -Depth 8|Set-Content $history -Encoding UTF8
 [ordered]@{schemaVersion=1;provenance='HOST_PATH_ABSENCE_RECEIPT';runId=$runId;observedAtUtc='2026-08-29T01:00:15.0000000Z';guestPaths=@($guestCollector,$guestStarted,$guestRaw,$guestDiagnostic);hostPaths=@($hostStarted,$hostRaw,$hostDiagnostic);allAbsent=$true}|ConvertTo-Json -Depth 8|Set-Content $absence -Encoding UTF8
 [ordered]@{schemaVersion=1;runId=$runId;provenance='HOST_BOUND_CORRECTED_ROUTE_PREFLIGHT';createdAtUtc='2026-08-29T01:00:20.0000000Z';rawReceiptPath=$receiptPath;rawReceiptSha256=(Sha $receiptPath);preflightCollectorPath=$preflightCollector;preflightCollectorSha256=(Sha $preflightCollector);vmxPath=$vmx;vmxSha256=(Sha $vmx);programCopyReceiptPath=$programCopy;programCopyReceiptSha256=(Sha $programCopy);interactiveCollectorSourcePath=$interactiveCollector.Path;interactiveCollectorSourceSha256=(Sha $interactiveCollector.Path);interactiveCollectorSealedPath=$interactiveCollector.Path;interactiveCollectorSealedSha256=(Sha $interactiveCollector.Path);interactiveCollectorRoundTripPath=$interactiveCollector.Path;interactiveCollectorRoundTripSha256=(Sha $interactiveCollector.Path);preflightGuestScriptPath='C:\LOGH7_ORACLE\collect-corrected-route-preflight.ps1';programExecutable='C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe';argumentVector=@('-NoProfile','-ExecutionPolicy','Bypass','-File','C:\LOGH7_ORACLE\collect-corrected-route-preflight.ps1','-RunId',$runId,'-OutputPath','C:\LOGH7_ORACLE\corrected-route-preflight.json');interactivePaths=[ordered]@{guestCollector=$guestCollector;guestStarted=$guestStarted;guestRaw=$guestRaw;guestDiagnostic=$guestDiagnostic;hostStarted=$hostStarted;hostRaw=$hostRaw;hostDiagnostic=$hostDiagnostic};interactiveArgumentVector=@('-NoProfile','-WindowStyle','Hidden','-ExecutionPolicy','Bypass','-File',$guestCollector,'-RunId',$runId,'-OutputPath',$guestRaw,'-StartedPath',$guestStarted,'-DiagnosticPath',$guestDiagnostic);historyLedgerPath=$history;historyLedgerSha256=(Sha $history);pathAbsenceReceiptPath=$absence;pathAbsenceReceiptSha256=(Sha $absence);guestSourceCopies=1;helperLaunchCalls=1;copyBackCalls=1;vmrunHostExitCode=0}
}
function Run($mutator,[bool]$bound=$false){
 $script:cases++;$runId="corrected-route-$($script:cases)";$x=Get-Content $fixture -Raw|ConvertFrom-Json;$x.runId=$runId;if($mutator){&$mutator $x};if($bound){$x.absoluteProgram.sha256=Sha $fixture;$x.absoluteProgram.length=(Get-Item $fixture).Length}
 $i=Join-Path $temp "i$($script:cases).json";$o=Join-Path $temp "o$($script:cases).json";$b=Join-Path $temp "b$($script:cases).json";$x|ConvertTo-Json -Depth 15|Set-Content $i -Encoding UTF8
 $invoke=@{ReceiptPath=$i;OutputPath=$o;RunId=$runId;InteractiveCollectorPath=$interactiveCollector.Path;EvaluationTimeUtc='2026-08-29T01:00:30.0000000Z'}
 if($bound){
  $binding=New-Binding $i $runId $o
  $binding|ConvertTo-Json -Depth 8|Set-Content $b -Encoding UTF8;$invoke.BindingPath=$b
 }
 &$evaluator @invoke|Out-Null;Get-Content $o -Raw|ConvertFrom-Json
}
try{
 $good=Run $null;Eq status $good.status 'STRUCTURALLY_READY_SYNTHETIC_PREFLIGHT_NOT_LIVE';Eq candidate $good.routeLaunchCandidateEligible $false;Eq authorized $good.executionAuthorized $false
 $claimed=Run {param($x)$x.provenance='LIVE_READONLY_CORRECTED_ROUTE_PREFLIGHT'};Eq claimed $claimed.status 'CLAIMED_LIVE_PREFLIGHT_UNBOUND';Eq claimedCandidate $claimed.routeLaunchCandidateEligible $false
 $live=Run {param($x)$x.provenance='LIVE_READONLY_CORRECTED_ROUTE_PREFLIGHT'} $true;Eq live $live.status 'CORRECTED_ROUTE_LIVE_PREFLIGHT_STRUCTURALLY_READY_AUTHORITY_MISSING';Eq prepared $live.routePreparedCandidateEligible $true;Eq liveCandidate $live.routeLaunchCandidateEligible $false;Eq authorityBlock ($live.blockers-contains'EXECUTION_AUTHORITY_NOT_BOUND') $true;Eq liveUnauthorized $live.executionAuthorized $false
 Eq program $live.route.programExecutable 'C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe';Eq noActiveWindow $live.route.vmrunActiveWindow $false;Eq interactive $live.route.vmrunInteractive $true
 Eq retry $live.route.retryAllowed $false;Eq pathUnique (@($live.route.guestCollectorPath,$live.route.guestStartedPath,$live.route.guestRawReceiptPath,$live.route.guestDiagnosticPath)|Sort-Object -Unique).Count 4
 $m=@(
  @{n='program path';f={param($x)$x.absoluteProgram.path='powershell.exe'};b='ABSOLUTE_PROGRAM_PATH_MISMATCH'},
  @{n='program missing';f={param($x)$x.absoluteProgram.exists=$false};b='ABSOLUTE_PROGRAM_MISSING'},
  @{n='program length';f={param($x)$x.absoluteProgram.length=0};b='ABSOLUTE_PROGRAM_IDENTITY_INVALID'},
  @{n='program stale';f={param($x)$x.absoluteProgram.observedAtUtc='2026-08-28T00:00:00Z'};b='PREFLIGHT_STALE'},
  @{n='session0';f={param($x)$x.activeConsoleSessionId=0};b='ACTIVE_CONSOLE_INVALID'},
  @{n='session2 all';f={param($x)$x.activeConsoleSessionId=2;foreach($p in $x.processes){$p.sessionId=2}};b='ACTIVE_CONSOLE_INVALID'},
  @{n='client hash';f={param($x)$x.processes[0].sha256='0'*64};b='CLIENT_HASH_MISMATCH'},
  @{n='client sid';f={param($x)$x.processes[0].ownerSid='S-1-5-18'};b='CLIENT_OWNER_SID_MISMATCH'},
  @{n='client module';f={param($x)$x.processes[0].moduleSize=0};b='CLIENT_MODULE_IDENTITY_INVALID'},
  @{n='client pid';f={param($x)$x.processes[0].pid=0};b='CLIENT_IDENTITY_INVALID'},
  @{n='client path';f={param($x)$x.processes[0].path='C:\wrong.exe'};b='CLIENT_PATH_MISMATCH'},
  @{n='client owner lookup';f={param($x)$x.processes[0].ownerLookupStatus='FAILED'};b='CLIENT_OWNER_LOOKUP_FAILED'},
  @{n='blank owner';f={param($x)$x.helper.userName='';foreach($p in $x.processes){$p.owner=''}};b='OWNER_IDENTITY_INVALID'},
  @{n='blank sid';f={param($x)$x.helper.userSid='';foreach($p in $x.processes){$p.ownerSid=''}};b='OWNER_IDENTITY_INVALID'},
  @{n='zero module';f={param($x)$x.processes[0].moduleBase='0x00000000'};b='CLIENT_MODULE_IDENTITY_INVALID'},
  @{n='pid collision';f={param($x)$x.processes[1].pid=$x.processes[0].pid};b='PROCESS_PID_COLLISION'},
  @{n='debugger hash';f={param($x)$x.processes[1].sha256='0'*64};b='DEBUGGER_HASH_MISMATCH'},
  @{n='agent owner';f={param($x)$x.processes[2].owner='SYSTEM'};b='INTERACTIVE_AGENT_OWNER_MISMATCH'},
  @{n='agent sid';f={param($x)$x.processes[2].ownerSid='S-1-5-18'};b='INTERACTIVE_AGENT_OWNER_SID_MISMATCH'},
  @{n='agent lookup';f={param($x)$x.processes[2].ownerLookupStatus='FAILED'};b='INTERACTIVE_AGENT_OWNER_LOOKUP_FAILED'},
  @{n='agent session';f={param($x)$x.processes[2].sessionId=0};b='INTERACTIVE_AGENT_SESSION_MISMATCH'},
  @{n='agent pid';f={param($x)$x.processes[2].pid=0};b='INTERACTIVE_AGENT_IDENTITY_INVALID'},
  @{n='agent path';f={param($x)$x.processes[2].path='C:\wrong.exe'};b='INTERACTIVE_AGENT_PATH_MISMATCH'},
  @{n='duplicate client';f={param($x)$x.processes+=@($x.processes[0])};b='CLIENT_COUNT_NOT_1'},
  @{n='input';f={param($x)$x.operations.gameInputs=1};b='FORBIDDEN_OPERATION_RECORDED'},
  @{n='write';f={param($x)$x.operations.processMemoryWrites=1};b='FORBIDDEN_OPERATION_RECORDED'},
  @{n='permit';f={param($x)$x.operations.permitIssued=$true};b='FORBIDDEN_OPERATION_RECORDED'}
 )
 foreach($t in $m){$r=Run $t.f;Eq "$($t.n) blocker" ($r.blockers-contains$t.b) $true;Eq "$($t.n) candidate" $r.routeLaunchCandidateEligible $false}
 $bm=@(
  @{n='binding raw';f={param($b)$b.rawReceiptSha256='0'*64};b='BINDING_RAW_HASH_MISMATCH'},
  @{n='binding schema version';f={param($b)$b.schemaVersion=2};b='BINDING_SCHEMA_INVALID'},
  @{n='binding run';f={param($b)$b.runId='other-run'};b='BINDING_RUN_ID_MISMATCH'},
  @{n='binding collector';f={param($b)$b.preflightCollectorSha256='0'*64};b='BINDING_COLLECTOR_HASH_MISMATCH'},
  @{n='binding vmx';f={param($b)$b.vmxSha256='0'*64};b='BINDING_VMX_HASH_MISMATCH'},
  @{n='binding time';f={param($b)$b.createdAtUtc='2026-08-28T00:00:00Z'};b='BINDING_TIME_INVALID'},
  @{n='binding program copy';f={param($b)$b.programCopyReceiptSha256='0'*64};b='BINDING_PROGRAM_COPY_RECEIPT_INVALID'},
  @{n='binding source drift';f={param($b)$b.interactiveCollectorSourceSha256='0'*64};b='BINDING_INTERACTIVE_COLLECTOR_DRIFT'},
  @{n='binding sealed drift';f={param($b)$b.interactiveCollectorSealedSha256='0'*64};b='BINDING_INTERACTIVE_COLLECTOR_DRIFT'},
  @{n='binding roundtrip drift';f={param($b)$b.interactiveCollectorRoundTripSha256='0'*64};b='BINDING_INTERACTIVE_COLLECTOR_DRIFT'},
  @{n='binding program';f={param($b)$b.programExecutable='powershell.exe'};b='BINDING_ROUTE_INVALID'},
  @{n='binding args';f={param($b)$b.argumentVector[0]='-Wrong'};b='BINDING_ROUTE_INVALID'},
  @{n='binding interactive args';f={param($b)$b.interactiveArgumentVector[0]='-Wrong'};b='BINDING_INTERACTIVE_ROUTE_INVALID'},
  @{n='binding interactive path';f={param($b)$b.interactivePaths.guestRaw='C:\wrong.json'};b='BINDING_INTERACTIVE_PATHS_INVALID'},
  @{n='binding history';f={param($b)$b.historyLedgerSha256='0'*64};b='BINDING_HISTORY_INVALID'},
  @{n='binding absence';f={param($b)$b.pathAbsenceReceiptSha256='0'*64};b='BINDING_PATH_ABSENCE_INVALID'},
  @{n='binding launches';f={param($b)$b.helperLaunchCalls=2};b='BINDING_ROUTE_INVALID'}
 )
 foreach($t in $bm){
  $script:cases++;$runId="corrected-route-$($script:cases)";$x=Get-Content $fixture -Raw|ConvertFrom-Json;$x.runId=$runId;$x.provenance='LIVE_READONLY_CORRECTED_ROUTE_PREFLIGHT';$i=Join-Path $temp "bi$($script:cases).json";$o=Join-Path $temp "bo$($script:cases).json";$bp=Join-Path $temp "bb$($script:cases).json";$x.absoluteProgram.sha256=Sha $fixture;$x.absoluteProgram.length=(Get-Item $fixture).Length;$x|ConvertTo-Json -Depth 15|Set-Content $i -Encoding UTF8;$binding=New-Binding $i $runId $o;&$t.f $binding;$binding|ConvertTo-Json -Depth 12|Set-Content $bp -Encoding UTF8;&$evaluator -ReceiptPath $i -OutputPath $o -RunId $runId -InteractiveCollectorPath $interactiveCollector.Path -BindingPath $bp -EvaluationTimeUtc '2026-08-29T01:00:30.0000000Z'|Out-Null;$result=Get-Content $o -Raw|ConvertFrom-Json
  Eq "$($t.n) blocker" ($result.blockers-contains$t.b) $true;Eq "$($t.n) candidate" $result.routeLaunchCandidateEligible $false
 }
 $sm=@(
  @{n='program copy guest path';path='programCopyReceiptPath';hash='programCopyReceiptSha256';f={param($s)$s.guestProgramPath='powershell.exe'};b='BINDING_PROGRAM_COPY_RECEIPT_INVALID'},
  @{n='program copy schema';path='programCopyReceiptPath';hash='programCopyReceiptSha256';f={param($s)$s.schemaVersion=2};b='BINDING_PROGRAM_COPY_RECEIPT_INVALID'},
  @{n='program copy future';path='programCopyReceiptPath';hash='programCopyReceiptSha256';f={param($s)$s.copiedAtUtc='2026-08-29T01:00:25Z'};b='BINDING_PROGRAM_COPY_RECEIPT_INVALID'},
  @{n='history reused';path='historyLedgerPath';hash='historyLedgerSha256';f={param($s)$s.historicalRuns[0].runId=$script:currentRun};b='RUN_OR_PATH_REUSED'},
  @{n='history empty';path='historyLedgerPath';hash='historyLedgerSha256';f={param($s)$s.historicalRuns=@()};b='BINDING_HISTORY_INVALID'},
  @{n='history empty paths';path='historyLedgerPath';hash='historyLedgerSha256';f={param($s)$s.historicalRuns[0].guestPaths=@()};b='BINDING_HISTORY_INVALID'},
  @{n='history extra key';path='historyLedgerPath';hash='historyLedgerSha256';f={param($s)$s.historicalRuns[0]|Add-Member extra 1};b='BINDING_HISTORY_INVALID'},
  @{n='absence false';path='pathAbsenceReceiptPath';hash='pathAbsenceReceiptSha256';f={param($s)$s.allAbsent=$false};b='BINDING_PATH_ABSENCE_INVALID'},
  @{n='absence schema';path='pathAbsenceReceiptPath';hash='pathAbsenceReceiptSha256';f={param($s)$s.schemaVersion=2};b='BINDING_PATH_ABSENCE_INVALID'},
  @{n='absence wrong path';path='pathAbsenceReceiptPath';hash='pathAbsenceReceiptSha256';f={param($s)$s.guestPaths[0]='C:\wrong.ps1'};b='BINDING_PATH_ABSENCE_INVALID'}
 )
 foreach($t in $sm){
  $script:cases++;$runId="corrected-route-$($script:cases)";$script:currentRun=$runId;$x=Get-Content $fixture -Raw|ConvertFrom-Json;$x.runId=$runId;$x.provenance='LIVE_READONLY_CORRECTED_ROUTE_PREFLIGHT';$x.absoluteProgram.sha256=Sha $fixture;$x.absoluteProgram.length=(Get-Item $fixture).Length;$i=Join-Path $temp "si$($script:cases).json";$o=Join-Path $temp "so$($script:cases).json";$bp=Join-Path $temp "sb$($script:cases).json";$x|ConvertTo-Json -Depth 15|Set-Content $i -Encoding UTF8;$binding=New-Binding $i $runId $o;$supportPath=[string]$binding.($t.path);$support=Get-Content $supportPath -Raw|ConvertFrom-Json;&$t.f $support;$support|ConvertTo-Json -Depth 10|Set-Content $supportPath -Encoding UTF8;$binding.($t.hash)=Sha $supportPath;$binding|ConvertTo-Json -Depth 12|Set-Content $bp -Encoding UTF8;&$evaluator -ReceiptPath $i -OutputPath $o -RunId $runId -InteractiveCollectorPath $interactiveCollector.Path -BindingPath $bp -EvaluationTimeUtc '2026-08-29T01:00:30.0000000Z'|Out-Null;$result=Get-Content $o -Raw|ConvertFrom-Json
  Eq "$($t.n) blocker" ($result.blockers-contains$t.b) $true;Eq "$($t.n) candidate" $result.routeLaunchCandidateEligible $false
 }
 [ordered]@{status='PASS';cases=$script:cases;assertions=$script:assertions;receiptMutations=$m.Count;bindingMutations=$bm.Count;supportMutations=$sm.Count}|ConvertTo-Json -Compress
}finally{if(Test-Path $temp){$resolved=(Resolve-Path $temp).Path;$base=[IO.Path]::GetFullPath([IO.Path]::GetTempPath());if(-not$resolved.StartsWith($base,[StringComparison]::OrdinalIgnoreCase)){throw 'unsafe cleanup'};Remove-Item -LiteralPath $resolved -Recurse -Force}}
