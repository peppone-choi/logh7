$ErrorActionPreference='Stop'
$caseRoot=Split-Path -Parent $PSScriptRoot
$ledgerPath=Join-Path $PSScriptRoot 'destination-hit-region-ledger.json'
$staticExport=Join-Path $PSScriptRoot 'destination-hit-region-owner.txt'
$exporter=Join-Path $caseRoot 'ExportDestinationHitRegionOwner.java'
$collector=Join-Path $caseRoot 'src/collect-destination-projection-snapshot.ps1'
$resolver=Join-Path $caseRoot 'src/resolve-destination-hit-region.ps1'
$collectorTest=Join-Path $caseRoot 'tests/test-collect-destination-projection-snapshot.ps1'
$resolverTest=Join-Path $caseRoot 'tests/test-resolve-destination-hit-region.ps1'
$identityFixture=Join-Path $caseRoot 'tests/fixture-identity.json'
$memoryFixture=Join-Path $caseRoot 'tests/fixture-projection-memory.json'
$resolverFixture=Join-Path $caseRoot 'tests/fixture-projection.json'
$fixtureSnapshot=Join-Path $PSScriptRoot 'fixture-projection-snapshot.json'
$fixtureRegion=Join-Path $PSScriptRoot 'fixture-hit-region.json'
$outputPath=Join-Path $PSScriptRoot 'final-verification.json'

function Eq($Name,$Actual,$Expected){if($Actual -ne $Expected){throw "$Name expected=$Expected actual=$Actual"}}
function Hash($Path){(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToUpperInvariant()}

$ledger=Get-Content -LiteralPath $ledgerPath -Raw -Encoding UTF8|ConvertFrom-Json
Eq 'result' $ledger.result 'STATIC_HIT_REGION_OWNER_AND_OFFLINE_RESOLVER_PASS'
Eq 'bounded status' $ledger.boundedStatus 'PARTIAL_LIVE_SNAPSHOT_UNSEEN'
Eq 'target hash' $ledger.target.sha256 'BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16'
Eq 'first missing' $ledger.firstMissingBoundary 'FRESH_DESTINATION_PROJECTION_SNAPSHOT'
Eq 'permit' $ledger.status.permitIssued $false
Eq 'live' $ledger.status.liveOperations 0

$bindings=[ordered]@{
  exporter=$exporter;staticExport=$staticExport;collector=$collector;resolver=$resolver
  collectorTest=$collectorTest;resolverTest=$resolverTest;identityFixture=$identityFixture
  projectionMemoryFixture=$memoryFixture;resolverFixture=$resolverFixture
}
foreach($name in $bindings.Keys){Eq "$name hash" (Hash $bindings[$name]) $ledger.hashes.$name}

$collectorResult=& $collectorTest|ConvertFrom-Json
$resolverResult=& $resolverTest|ConvertFrom-Json
Eq 'collector test' $collectorResult.result 'PASS';Eq 'collector cases' $collectorResult.cases 10;Eq 'collector assertions' $collectorResult.assertions 46
Eq 'resolver test' $resolverResult.result 'PASS';Eq 'resolver cases' $resolverResult.cases 7;Eq 'resolver assertions' $resolverResult.assertions 34

$null=& $collector -FixtureMemoryPath $memoryFixture -FixtureIdentityPath $identityFixture -TargetGridX 50 -TargetGridY 25 -OutputPath $fixtureSnapshot
$null=& $resolver -SnapshotPath $fixtureSnapshot -OutputPath $fixtureRegion
Eq 'fixture snapshot hash' (Hash $fixtureSnapshot) $ledger.hashes.fixtureSnapshot
Eq 'fixture region hash' (Hash $fixtureRegion) $ledger.hashes.fixtureHitRegion
$region=Get-Content -LiteralPath $fixtureRegion -Raw -Encoding UTF8|ConvertFrom-Json
Eq 'fixture binding' $region.bindingEligible $true;Eq 'fixture pixels' $region.region.pixelCount 25
Eq 'fixture safe x' $region.safePoint.x 52;Eq 'fixture safe y' $region.safePoint.y 47
Eq 'fixture runtime' $region.provenance.originalRuntimeObserved $false

$markers=@(
  'GetKeyboardState((PBYTE)(param_1 + 0x194));',
  'ScreenToClient(*(HWND *)(DAT_007c1b4c + 0x2a5ec),&local_18);',
  'FUN_004b25a0(&fStack_1fc,DAT_022143dc,DAT_022143e0,&fStack_1e8);',
  'FUN_004d3580(&puStack_1d8,&iStack_1dc,&fStack_21c);',
  'TEST byte ptr [0x022142db],0x40',
  'MEMORY_FLOAT grid x rounding bias address=0066e244 bits=42480000 value=50.0',
  'MEMORY_FLOAT grid y origin address=0066e61c bits=41c80000 value=25.0',
  'MEMORY_FLOAT target distance filter epsilon address=0066e664 bits=3d4ccccd value=0.05',
  '(**(code **)(*DAT_02229400 + 0x98))(DAT_02229400,3,&DAT_009d13a8);',
  '(**(code **)(*DAT_02229400 + 0x98))(DAT_02229400,2,&DAT_009d1368);',
  '(**(code **)(*DAT_02229400 + 0x98))(DAT_02229400,0x100,&DAT_009d13e8);',
  'return (uint)*(byte *)(DAT_007ccffc + param_2 * 100 + 0x2c03cc + param_1) * 3 + 0x2c1755 +'
)
$staticText=Get-Content -LiteralPath $staticExport -Raw -Encoding UTF8
foreach($marker in $markers){if(-not $staticText.Contains($marker)){throw "static marker missing: $marker"}}

$sourceText=(Get-Content -LiteralPath $collector -Raw -Encoding UTF8)+"`n"+(Get-Content -LiteralPath $resolver -Raw -Encoding UTF8)
$forbidden=@('WriteProcessMemory','VirtualProtectEx','CreateRemoteThread','SendInput','mouse_event','keybd_event','SetCursorPos','DebugActiveProcess','Start-Process','vmrun','TcpClient','System.Net.Sockets')
$hits=@($forbidden|Where-Object{$sourceText.IndexOf($_,[StringComparison]::OrdinalIgnoreCase)-ge 0})
Eq 'forbidden capability count' $hits.Count 0
$allowedNative=@('OpenProcess','ReadProcessMemory','CloseHandle','IsWindow','GetWindowThreadProcessId','GetClientRect')
$native=@([regex]::Matches($sourceText,'extern\s+(?:bool|IntPtr|uint)\s+([A-Za-z0-9_]+)\s*\(')|ForEach-Object{$_.Groups[1].Value}|Sort-Object -Unique)
foreach($name in $native){if($allowedNative -notcontains $name){throw "unapproved native import: $name"}}

$result=[ordered]@{
  result='PASS';boundedStatus=$ledger.boundedStatus;targetSha256=$ledger.target.sha256
  collector=[ordered]@{cases=10;assertions=46;doubleCaptureReads=144;status='PASS_OFFLINE_FIXTURE'}
  resolver=[ordered]@{cases=7;assertions=34;fixturePixels=25;safePoint='52,47';status='PASS_OFFLINE_FIXTURE';liveSelfClaim='REJECTED'}
  staticMarkersChecked=$markers.Count;nativeImports=$native;forbiddenCapabilityHits=0
  runtimeObserved='UNSEEN';playerVisible='UNSEEN';liveOperations=0;gameInputs=0;permitIssued=$false
  firstMissingBoundary=$ledger.firstMissingBoundary;nextOfflineUnit=$ledger.nextOfflineUnit
  hashes=[ordered]@{ledger=Hash $ledgerPath;fixtureSnapshot=Hash $fixtureSnapshot;fixtureHitRegion=Hash $fixtureRegion}
}
$json=$result|ConvertTo-Json -Depth 8
[IO.File]::WriteAllText($outputPath,(($json-replace"`r?`n","`n")+"`n"),[Text.UTF8Encoding]::new($false))
$json
