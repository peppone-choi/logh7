param([string]$OutputPath)
$ErrorActionPreference='Stop';$root=$PSScriptRoot
function Assert([bool]$Condition,[string]$Message){if(-not$Condition){throw $Message}}
function Run-Test([string]$Path){$lines=@(& pwsh -NoProfile -File $Path 2>&1|ForEach-Object{$_.ToString()});Assert ($LASTEXITCODE-eq0) "test failed: $Path";return($lines[-1]|ConvertFrom-Json)}
$evalTest=Run-Test (Join-Path $root 'tests\test-evaluate-corrected-route-prelaunch.ps1')
$contractTest=Run-Test (Join-Path $root 'tests\test-corrected-route-preflight-collector-contract.ps1')
Assert ($evalTest.status-eq'PASS') 'evaluator test status';Assert ($contractTest.status-eq'PASS') 'collector contract status'

$parsed=@();foreach($file in @(Get-ChildItem -LiteralPath $root -Recurse -Filter '*.ps1' -File)){$t=$null;$e=$null;[void][Management.Automation.Language.Parser]::ParseFile($file.FullName,[ref]$t,[ref]$e);Assert (@($e).Count-eq0) "parser: $($file.FullName)";$parsed+=[ordered]@{path=$file.FullName;sha256=(Get-FileHash $file.FullName -Algorithm SHA256).Hash}}

$production=@((Join-Path $root 'src\collect-corrected-route-preflight.ps1'),(Join-Path $root 'src\evaluate-corrected-route-prelaunch.ps1'))
$text=($production|ForEach-Object{Get-Content $_ -Raw})-join"`n"
foreach($forbidden in @('vmrun.exe','vncdo','SendInput','SetForegroundWindow','ReadProcessMemory','WriteProcessMemory','DebugActiveProcess','Start-Process','Stop-Process')){Assert (-not$text.Contains($forbidden)) "forbidden capability: $forbidden"}

$syntheticPath=Join-Path $root 'evidence\synthetic-preflight-evaluation.json'
Assert (Test-Path $syntheticPath) 'synthetic evidence missing'
$synthetic=Get-Content $syntheticPath -Raw|ConvertFrom-Json
Assert ($synthetic.status-eq'STRUCTURALLY_READY_SYNTHETIC_PREFLIGHT_NOT_LIVE') 'synthetic status'
Assert ($synthetic.routePreparedCandidateEligible-eq$false-and$synthetic.routeLaunchCandidateEligible-eq$false-and$synthetic.executionAuthorized-eq$false) 'synthetic promotion'
Assert ($synthetic.route.programExecutable-eq'C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe') 'absolute program'
Assert ($synthetic.route.vmrunInteractive-eq$true-and$synthetic.route.vmrunActiveWindow-eq$false-and$synthetic.route.retryAllowed-eq$false) 'future route flags'
$guestPaths=@($synthetic.route.guestCollectorPath,$synthetic.route.guestStartedPath,$synthetic.route.guestRawReceiptPath,$synthetic.route.guestDiagnosticPath)
Assert (($guestPaths|Sort-Object -Unique).Count-eq4) 'guest paths unique'

$result=[ordered]@{schemaVersion=1;status='PASS';verdict='CORRECTED_ROUTE_V2_OFFLINE_PREPARED_NO_EXECUTION_AUTHORITY';evaluatorCases=[int]$evalTest.cases;evaluatorAssertions=[int]$evalTest.assertions;collectorContractAssertions=[int]$contractTest.assertions;parsedPowerShellFiles=$parsed.Count;syntheticEvaluationSha256=(Get-FileHash $syntheticPath -Algorithm SHA256).Hash;vmGuestOperations=0;helperLaunchCalls=0;gameInputs=0;physicalActivations=0;executionAuthorized=$false;parserArtifacts=$parsed}
$json=($result|ConvertTo-Json -Depth 10)-replace"`r`n","`n";if($OutputPath){[IO.File]::WriteAllText($OutputPath,$json+"`n",[Text.UTF8Encoding]::new($false))};$json
