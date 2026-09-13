$ErrorActionPreference='Stop'
$unit=$PSScriptRoot
$v2=& (Join-Path $unit 'tests/test-movement-breakpoint-receipt-v2.ps1')|ConvertFrom-Json
if($v2.result-ne'PASS'-or$v2.cases-ne78-or$v2.assertions-ne101-or$v2.mutations-ne74){throw 'receipt-v2 tests failed or drifted'}
$v8=& (Join-Path $unit 'tests/test-prelaunch-v8-movement-receipt-v2.ps1')|ConvertFrom-Json
if($v8.result-ne'PASS'-or$v8.cases-ne20-or$v8.assertions-ne30-or$v8.mutations-ne19){throw 'prelaunch-v8 tests failed or drifted'}
$schema=&python (Join-Path $unit '..\20260828-original-client-movement-breakpoint-receipt-schema\tests\validate-json-schema.py') (Join-Path $unit 'evidence/movement-breakpoint-receipt-v2.schema.json') (Join-Path $unit 'evidence/movement-breakpoint-receipt-v2-template.json') (Join-Path $unit 'tests/fixture-v2-semantic-specimen.json')|ConvertFrom-Json
if($schema.result-ne'PASS'-or$schema.dialect-ne'2020-12'-or$schema.documents-ne2){throw 'v2 JSON Schema validation failed'}
$template=& (Join-Path $unit 'src/verify-movement-breakpoint-receipt-v2.ps1') -ReceiptPath (Join-Path $unit 'evidence/movement-breakpoint-receipt-v2-template.json')|ConvertFrom-Json
$specimen=& (Join-Path $unit 'src/verify-movement-breakpoint-receipt-v2.ps1') -ReceiptPath (Join-Path $unit 'tests/fixture-v2-semantic-specimen.json')|ConvertFrom-Json
$contract=& (Join-Path $unit 'src/verify-prelaunch-v8-movement-receipt-v2.ps1') -ContractPath (Join-Path $unit 'evidence/prelaunch-v8-movement-receipt-v2.json')|ConvertFrom-Json
if($template.result-ne'PASS'-or$template.state-ne'EMPTY_TEMPLATE_NOT_LIVE'-or$template.liveReceiptEligible){throw 'v2 template semantic verification failed'}
if($specimen.result-ne'PASS'-or$specimen.state-ne'SYNTHETIC_SEMANTIC_SPECIMEN'-or$specimen.fieldGroupCount-ne8-or$specimen.phaseCount-ne10-or$specimen.liveReceiptEligible){throw 'v2 specimen semantic verification failed'}
if($contract.result-ne'PASS'-or$contract.firstTechnicalBoundary-ne'FRESH_RUN_IDENTITY_MISSING'-or$contract.runtimeReceiptStatus-ne'MISSING'){throw 'v8 semantic verification failed'}
$ledgerPath=Join-Path $unit 'evidence/artifact-ledger.json';$ledger=Get-Content -LiteralPath $ledgerPath -Raw -Encoding UTF8|ConvertFrom-Json;$map=[ordered]@{}
foreach($a in $ledger.artifacts){$p=Join-Path $unit ([string]$a.path);if(-not(Test-Path -LiteralPath $p)){throw "missing artifact $($a.path)"};$h=(Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash;if($h-ne([string]$a.sha256).ToUpperInvariant()){throw "artifact hash mismatch $($a.path)"};$map[$a.path]=$h}
$scripts=@(Get-Content -LiteralPath (Join-Path $unit 'src/verify-movement-breakpoint-receipt-v2.ps1') -Raw -Encoding UTF8;Get-Content -LiteralPath (Join-Path $unit 'src/verify-prelaunch-v8-movement-receipt-v2.ps1') -Raw -Encoding UTF8)-join"`n"
$forbidden=@('WriteProcessMemory','SendInput','SetCursorPos','PostMessage','mouse_event','keybd_event','VirtualAllocEx','CreateRemoteThread','Invoke-VMScript','Start-VM','Stop-VM','vmrun');$hits=@($forbidden|Where-Object{$scripts.Contains($_)});if($hits.Count){throw "forbidden executable capability: $($hits-join', ')"}
[ordered]@{result='PASS';receiptV2Tests=$v2;prelaunchV8Tests=$v8;jsonSchemaValidation=$schema;template=$template;specimen=$specimen;contract=$contract;artifactHashesVerified=@($ledger.artifacts).Count;artifactLedgerSha256=(Get-FileHash -LiteralPath $ledgerPath -Algorithm SHA256).Hash;artifactHashMap=$map;fieldGroupCount=8;phaseCount=10;commandCount=18;runtimeNoMissProof='MISSING';runtimeReceiptStatus='MISSING';forbiddenCapabilityHits=0;liveOperations=0;debuggerCommands=0;processMemoryReads=0;gameInputs=0;permitIssued=$false;status='OFFLINE_MOVEMENT_RECEIPT_V2_SCHEMA_PASS_RUNTIME_UNSEEN'}|ConvertTo-Json -Depth 20
