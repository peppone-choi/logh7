$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$verifier = Join-Path $root 'src/verify-prelaunch-v6-movement-receipt.ps1'
$contract = Join-Path $root 'evidence/prelaunch-v6-movement-receipt.json'
if (-not (Test-Path -LiteralPath $verifier)) { throw 'RED: prelaunch v6 verifier missing' }
if (-not (Test-Path -LiteralPath $contract)) { throw 'RED: prelaunch v6 contract missing' }

$temp = Join-Path ([IO.Path]::GetTempPath()) ('logh7-movement-v6-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp | Out-Null
$script:assertions = 0
$script:cases = 0
function Eq([string]$Name, $Actual, $Expected) { $script:assertions++; if ($Actual -ne $Expected) { throw "$Name expected=$Expected actual=$Actual" } }
function Run([string]$Path) { $script:cases++; & $verifier -ContractPath $Path | ConvertFrom-Json }
function Variant([string]$Name, [scriptblock]$Change) { $j=Get-Content -LiteralPath $contract -Raw -Encoding UTF8|ConvertFrom-Json;&$Change $j;$p=Join-Path $temp ($Name+'.json');$j|ConvertTo-Json -Depth 40|Set-Content -LiteralPath $p -Encoding UTF8;$p }
try {
    $result = Run $contract
    Eq 'result' $result.result 'PASS'
    Eq 'state' $result.state 'OFFLINE_PRELAUNCH_MOVEMENT_RECEIPT_SCHEMA_INTEGRATED_READY_FALSE'
    Eq 'blockers' $result.blockerCount 12
    Eq 'first policy' $result.firstMissingBoundary 'ACTIVATION_BUDGET_STAGE_CONTRACT_MISMATCH'
    Eq 'first technical' $result.firstTechnicalBoundary 'MOVEMENT_HARDWARE_BREAKPOINT_REARM_PLAN_MISSING'
    Eq 'schema resolved' $result.movementSchemaStaticGapsResolved 1
    Eq 'primary anchors' $result.primaryAnchorCount 7
    Eq 'completion anchors' $result.completionAnchorCount 2
    Eq 'runtime' $result.runtimeStatus 'UNSEEN'
    Eq 'prior permit' $result.priorPermitState 'CONSUMED_NO_RETRY'
    Eq 'live operations' $result.liveOperations 0
    Eq 'inputs' $result.gameInputs 0
    Eq 'permit' $result.permitIssued $false

    $mutations = [ordered]@{
        oldSchemaBlocker = { param($j) $j.blockers[1]='MOVEMENT_SPECIFIC_BREAKPOINT_RECEIPT_SCHEMA_MISSING' }
        rearmRemoved = { param($j) $j.blockers=@($j.blockers|Where-Object{$_-ne'MOVEMENT_HARDWARE_BREAKPOINT_REARM_PLAN_MISSING'}) }
        blockerOrder = { param($j) $x=$j.blockers[1];$j.blockers[1]=$j.blockers[2];$j.blockers[2]=$x }
        primaryCount = { param($j) $j.staticPreparation.movementReceipt.primaryAnchorCount=9 }
        completionCount = { param($j) $j.staticPreparation.movementReceipt.completionAnchorCount=0 }
        completionPromoted = { param($j) $j.staticPreparation.movementReceipt.queueCompletion='PASS' }
        codecPromoted = { param($j) $j.staticPreparation.movementReceipt.fullPayloadCodec='CODEC_PROVEN' }
        runtimePromoted = { param($j) $j.staticPreparation.movementReceipt.runtimeObserved='PASS' }
        softwareInt3 = { param($j) $j.staticPreparation.movementReceipt.breakpointMechanism='SOFTWARE_INT3' }
        artifactHash = { param($j) $j.boundArtifacts.movementSchema.sha256='0'*64 }
        authoritySource = { param($j) $j.currentAuthority.source='SELF_ATTESTED' }
        activationBudget = { param($j) $j.currentAuthority.activationBudget=3 }
        priorPermit = { param($j) $j.priorPermit.state='ACTIVE';$j.priorPermit.reusable=$true }
        processWrite = { param($j) $j.operationCounters.memoryWrites=1 }
        launchEligible = { param($j) $j.launchEligible=$true }
        forbidden = { param($j) $j.forbidden[1]='software INT3 allowed' }
    }
    foreach ($entry in $mutations.GetEnumerator()) { Eq ($entry.Key+' rejected') (Run (Variant $entry.Key $entry.Value)).result 'FAIL' }
    [ordered]@{result='PASS';cases=$script:cases;assertions=$script:assertions;mutations=$mutations.Count}|ConvertTo-Json
}
finally {
    if (Test-Path -LiteralPath $temp) { $resolved=(Resolve-Path -LiteralPath $temp).Path;$base=[IO.Path]::GetFullPath([IO.Path]::GetTempPath());if(-not$resolved.StartsWith($base,[StringComparison]::OrdinalIgnoreCase)){throw 'unsafe temp cleanup target'};Remove-Item -LiteralPath $resolved -Recurse -Force }
}
