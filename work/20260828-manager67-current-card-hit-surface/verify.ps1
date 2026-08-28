$ErrorActionPreference='Stop'
$unit=$PSScriptRoot
$repo=Resolve-Path (Join-Path $unit '..\..')
$collectorTest=& (Join-Path $unit 'tests/test-collect-manager67-current-card.ps1')|ConvertFrom-Json
$resolverTest=& (Join-Path $unit 'tests/test-resolve-manager67-current-card.ps1')|ConvertFrom-Json
if($collectorTest.result-ne'PASS'-or$collectorTest.cases-ne36-or$collectorTest.assertions-ne59){throw'collector test contract mismatch'}
if($resolverTest.result-ne'PASS'-or$resolverTest.cases-ne6-or$resolverTest.assertions-ne19){throw'resolver test contract mismatch'}

$temp=Join-Path ([IO.Path]::GetTempPath()) ('manager67-verify-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory $temp|Out-Null
try{
    $regeneratedCapture=Join-Path $temp 'fixture-capture.json'
    $regeneratedResolution=Join-Path $temp 'fixture-resolution.json'
    & (Join-Path $unit 'src/collect-manager67-current-card.ps1') -FixtureMemoryPath (Join-Path $unit 'tests/fixture-ready.json') -FixtureIdentityPath (Join-Path $unit 'tests/fixture-identity.json') -OutputPath $regeneratedCapture
    & (Join-Path $unit 'src/resolve-manager67-current-card.ps1') -CapturePath $regeneratedCapture -OutputPath $regeneratedResolution
    foreach($pair in @(
        @($regeneratedCapture,(Join-Path $unit 'evidence/fixture-capture.json')),
        @($regeneratedResolution,(Join-Path $unit 'evidence/fixture-resolution.json'))
    )){
        if((Get-FileHash -LiteralPath $pair[0] -Algorithm SHA256).Hash-ne(Get-FileHash -LiteralPath $pair[1] -Algorithm SHA256).Hash){throw"published fixture evidence drift: $($pair[1])"}
    }
}finally{
    if(Test-Path $temp){$resolved=(Resolve-Path $temp).Path;$tempBase=[IO.Path]::GetFullPath([IO.Path]::GetTempPath());if(-not$resolved.StartsWith($tempBase,[StringComparison]::OrdinalIgnoreCase)){throw'unsafe verifier cleanup'};Remove-Item $resolved -Recurse -Force}
}

$ledger=Get-Content (Join-Path $unit 'evidence/static-owner-ledger.json') -Raw -Encoding UTF8|ConvertFrom-Json
foreach($source in $ledger.sources){
    $path=Join-Path $repo ([string]$source.path)
    if(-not(Test-Path -LiteralPath $path)){throw"missing source: $($source.path)"}
    if((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash-ne$source.sha256){throw"source hash mismatch: $($source.path)"}
}

$exe=(Resolve-Path (Join-Path $repo 'evidence/installshield-extract/*/*/exe/g7mtclient.exe')).Path
if((Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash-ne$ledger.target.sha256){throw'canonical executable hash mismatch'}
$bytes=[IO.File]::ReadAllBytes($exe)
function Test-Page([int]$Offset,$expected){
    $slice=[byte[]]::new(0x208);[Array]::Copy($bytes,$Offset,$slice,0,$slice.Length)
    $hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($slice))
    if($hash-ne$expected.sha256){throw'page table hash mismatch'}
    if([Convert]::ToHexString($slice[0..31])-ne$expected.prefix32){throw'page table prefix mismatch'}
    if(@($slice[32..($slice.Length-1)]|Where-Object{$_-ne0}).Count-ne0){throw'page table tail is not zero'}
}
Test-Page 0x26F540 $ledger.pageTable.page2
Test-Page 0x26F748 $ledger.pageTable.page3

$collectorPath=Join-Path $unit 'src/collect-manager67-current-card.ps1'
$resolverPath=Join-Path $unit 'src/resolve-manager67-current-card.ps1'
$collectorText=Get-Content $collectorPath -Raw -Encoding UTF8
$resolverText=Get-Content $resolverPath -Raw -Encoding UTF8
$required=@('0x89E638','0x488','0x48C','0x61C','0x620','0x624','0x628','0x26C','0x88','0xC8','BOUND_AUTHORITY_CARD_NOT_UNIQUE_IN_CURRENT_MANAGER67_LIST','AUTHORITY_CARD_WITH_WARP_ACTION_NOT_PROVEN_CAPTAIN_PORTRAIT')
$missing=@($required|Where-Object{-not$collectorText.Contains($_)})
if($missing.Count){throw"collector markers missing: $($missing-join', ')"}
if(-not$resolverText.Contains("automaticActivationPoint=`$null")-or-not$resolverText.Contains("gameInputs=0")){throw'resolver non-activation contract missing'}

$dllImports=[regex]::Matches($collectorText,'DllImport\("(?<dll>[^\"]+)"[^\]]*\)\]\s*public static extern (?<sig>[^;]+);')
$nativeNames=@($dllImports|ForEach-Object{if($_.Groups['sig'].Value-match'\s(?<name>[A-Za-z0-9_]+)\('){$Matches.name}}|Sort-Object -Unique)
$expectedNative=@('CloseHandle','GetClientRect','GetWindowThreadProcessId','IsWindow','OpenProcess','ReadProcessMemory')
if(($nativeNames|ConvertTo-Json -Compress)-ne($expectedNative|ConvertTo-Json -Compress)){throw"native surface mismatch: $($nativeNames-join', ')"}
$forbidden=@('WriteProcessMemory','SendInput','SetCursorPos','PostMessage','mouse_event','keybd_event','VirtualAllocEx','CreateRemoteThread','Invoke-VMScript','vmrun','x32dbg')
$forbiddenHits=@($forbidden|Where-Object{$collectorText.Contains($_)-or$resolverText.Contains($_)})
if($forbiddenHits.Count){throw"forbidden capability markers: $($forbiddenHits-join', ')"}
if(-not$collectorText.Contains('OpenProcess(0x410')){throw'read-only process access mask missing'}

$artifactLedger=Get-Content (Join-Path $unit 'evidence/artifact-ledger.json') -Raw -Encoding UTF8|ConvertFrom-Json
$artifactLedgerSha256=(Get-FileHash -LiteralPath (Join-Path $unit 'evidence/artifact-ledger.json') -Algorithm SHA256).Hash
$artifactHashMap=[ordered]@{}
foreach($artifact in $artifactLedger.artifacts){
    $path=Join-Path $unit ([string]$artifact.path)
    $actual=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
    if($actual-ne$artifact.sha256){throw"artifact hash mismatch: $($artifact.path)"}
    $artifactHashMap[$artifact.path]=$actual
}

[ordered]@{
    result='PASS'
    collectorTests=$collectorTest
    resolverTests=$resolverTest
    sourceHashesVerified=$ledger.sources.Count
    canonicalExecutableVerified=$true
    pageTablesVerified=2
    staticMarkersVerified=$required.Count
    publishedFixtureArtifactsReproduced=2
    artifactHashesVerified=$artifactLedger.artifacts.Count
    artifactLedgerSha256=$artifactLedgerSha256
    artifactHashMap=$artifactHashMap
    nativeReadOnlyApis=$nativeNames
    forbiddenCapabilityHits=0
    liveOperations=0
    gameInputs=0
    permitIssued=$false
}|ConvertTo-Json -Depth 10
