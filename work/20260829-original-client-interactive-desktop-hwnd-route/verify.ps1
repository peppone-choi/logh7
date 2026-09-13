param([string]$OutputPath)
$ErrorActionPreference='Stop'
$root=$PSScriptRoot

function Assert-True([bool]$Condition,[string]$Message){if(-not$Condition){throw $Message}}
function Read-TestJson([string]$Path){
 $text=@(& pwsh -NoProfile -File $Path 2>&1|ForEach-Object{$_.ToString()})
 Assert-True ($LASTEXITCODE-eq0) "test failed: $Path"
 return ($text[-1]|ConvertFrom-Json)
}

$evaluatorTest=Read-TestJson (Join-Path $root 'tests\test-evaluate-interactive-canary.ps1')
$contractTest=Read-TestJson (Join-Path $root 'tests\test-interactive-canary-contract.ps1')
Assert-True ($evaluatorTest.status-eq'PASS') 'evaluator test not PASS'
Assert-True ($contractTest.status-eq'PASS') 'contract test not PASS'

$parserResults=@()
foreach($file in @(Get-ChildItem -LiteralPath $root -Recurse -Filter '*.ps1' -File)){
 $tokens=$null;$errors=$null
 [void][Management.Automation.Language.Parser]::ParseFile($file.FullName,[ref]$tokens,[ref]$errors)
 Assert-True (@($errors).Count-eq0) "parser error: $($file.FullName)"
 $parserResults+=[ordered]@{path=$file.FullName;sha256=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash}
}

$runId='20260829T183100Z-v1'
$routePath=Join-Path $root "evidence\live-$runId-vmrun-route.json"
$route=Get-Content -LiteralPath $routePath -Raw -Encoding UTF8|ConvertFrom-Json
$preservedCollectorPath=Join-Path $root "evidence\live-$runId-collector.ps1"
$ledgerPath=Join-Path $root 'evidence\unit-operation-ledger.json'
$ledger=Get-Content -LiteralPath $ledgerPath -Raw -Encoding UTF8|ConvertFrom-Json
Assert-True ($route.provenance-eq'HOST_VMRUN_INTERACTIVE_ROUTE') 'route provenance'
Assert-True ($route.guestSourceCopies-eq1 -and $route.guestSourceCopyExitCode-eq0) 'source copy accounting'
Assert-True ($route.helperInvocationCount-eq1 -and $route.helperExitCode-eq-1) 'vmrun host attempt accounting'
Assert-True ($route.vmrunInteractive-eq$true -and $route.vmrunActiveWindow-eq$false) 'interactive route flags'
Assert-True (@($route.helperOutput).Count-eq1 -and $route.helperOutput[0]-eq'Error: A file was not found') 'exact helper failure'
Assert-True ($route.collectorSha256-eq(Get-FileHash -LiteralPath $preservedCollectorPath -Algorithm SHA256).Hash) 'preserved live collector hash drift'
Assert-True (@($route.copies).Count-eq3) 'copy-back count'
foreach($copy in @($route.copies)){
 Assert-True ($copy.exitCode-eq-1 -and $copy.exists-eq$false -and $null-eq$copy.sha256) "unexpected copied receipt: $($copy.role)"
 Assert-True (-not(Test-Path -LiteralPath $copy.hostPath)) "unexpected host receipt exists: $($copy.role)"
}
foreach($key in @('gameInputs','automaticInputs','foregroundChanges','processMemoryReads','processMemoryWrites','debuggerAttach','debuggerCommands','breakpointsInstalled','vmLifecycleChanges','serverChanges','protocolChanges','databaseChanges')){Assert-True ($route.operations.$key-eq0) "nonzero forbidden operation: $key"}
Assert-True ($route.operations.permitIssued-eq$false) 'permit issued'
Assert-True ($ledger.vmrunCommandCounts.copyFileFromHostToGuest-eq6 -and $ledger.vmrunCommandCounts.runProgramInGuest-eq7 -and $ledger.vmrunCommandCounts.copyFileFromGuestToHost-eq8) 'whole-unit vmrun counts'
Assert-True ($ledger.vmrunCommandCounts.captureScreen-eq2 -and $ledger.passiveVncCaptureCalls-eq1 -and $ledger.visualCaptureArtifacts-eq2) 'capture accounting'
Assert-True ($ledger.vmrunCommandCounts.fileExistsInGuest-eq1 -and $ledger.vmrunCommandCounts.killProcessInGuest-eq1 -and $ledger.ownedDiagnosticHelperTermination.count-eq1 -and $ledger.ownedDiagnosticHelperTermination.pid-eq5272) 'diagnostic operation accounting'
Assert-True ($ledger.interactiveCanaryAttempt.helperProcessCreated-eq'UNKNOWN' -and $ledger.interactiveCanaryAttempt.vmrunHostExitCode-eq-1) 'canary unknown process boundary'
Assert-True ($ledger.guestHelperReceiptWrites-eq'NOT_INSTRUMENTED_BEFORE_UNIT_LEDGER' -and $ledger.guestHelperProcessCreationCount-eq'NOT_INSTRUMENTED_BEFORE_UNIT_LEDGER') 'uninstrumented counts must remain explicit'
foreach($key in @('gameInputs','automaticInputs','foregroundChanges','processMemoryReads','processMemoryWrites','debuggerAttach','debuggerCommands','breakpointsInstalled','physicalActivations','vmLifecycleChanges','serverChanges','protocolChanges','databaseChanges')){Assert-True ($ledger.targetAndGameOperations.$key-eq0) "nonzero target/game operation: $key"}
Assert-True ($ledger.targetAndGameOperations.permitIssued-eq$false) 'ledger permit issued'

$result=[ordered]@{
 schemaVersion=1
 status='PASS'
 verdict='LIVE_HELPER_ROUTE_FAILED_BEFORE_STARTED_MARKER_NO_RETRY'
 evaluatorCases=[int]$evaluatorTest.cases
 evaluatorAssertions=[int]$evaluatorTest.assertions
 contractAssertions=[int]$contractTest.assertions
 parsedPowerShellFiles=$parserResults.Count
 routeSha256=(Get-FileHash -LiteralPath $routePath -Algorithm SHA256).Hash
 collectorSha256=$route.collectorSha256
 helperLaunchCalls=1
 helperProcessCreated='UNKNOWN'
  helperReceiptCount=0
  physicalActivations=0
 targetAndGameStateChangingOperationCount=0
 ownedDiagnosticHelperTerminations=1
 guestSourceFileWritesKnown=6
 guestHelperReceiptWrites='NOT_INSTRUMENTED_BEFORE_UNIT_LEDGER'
 fileExistsQueries=1
 visualCaptureCalls=3
 visualCaptureArtifacts=2
  parserArtifacts=$parserResults
}
$json=($result|ConvertTo-Json -Depth 10)-replace"`r`n","`n"
if($OutputPath){[IO.File]::WriteAllText($OutputPath,$json+"`n",[Text.UTF8Encoding]::new($false))}
$json
