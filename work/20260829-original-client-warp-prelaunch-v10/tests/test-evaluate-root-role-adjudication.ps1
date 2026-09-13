$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$evaluator = Join-Path $root 'src/evaluate-root-role-adjudication.ps1'
$fixture = Join-Path $PSScriptRoot 'fixture-root-role-capture.json'
$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ('root-role-v10-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tempRoot | Out-Null
$script:assertions = 0
$script:cases = 0
function Assert-Equal($name, $actual, $expected) { $script:assertions++; if ($actual -ne $expected) { throw "$name expected=$expected actual=$actual" } }
function Invoke-Evaluation($mutator) {
    $script:cases++
    $capture = Get-Content -LiteralPath $fixture -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($null -ne $mutator) { & $mutator $capture }
    $input = Join-Path $tempRoot ("input-$($script:cases).json")
    $output = Join-Path $tempRoot ("output-$($script:cases).json")
    $capture | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $input -Encoding UTF8
    & $evaluator -CapturePath $input -OutputPath $output | Out-Null
    Get-Content -LiteralPath $output -Raw -Encoding UTF8 | ConvertFrom-Json
}
try {
    $good = Invoke-Evaluation $null
    Assert-Equal 'role status' $good.roleAdjudication.status 'ROLES_PROVEN_OFFLINE_FIXTURE'
    Assert-Equal 'UI root role' $good.roleAdjudication.uiRootRole 'UI_MODE_AND_REGISTRY_HOST'
    Assert-Equal 'strategy role' $good.roleAdjudication.strategyOwnerRole 'INLINE_STRATEGY_MANAGER_OWNER'
    Assert-Equal 'root structural candidate' $good.rootRoleCandidateEligible $true
    Assert-Equal 'warp always false' $good.warpPrelaunchEligible $false
    Assert-Equal 'launch always false' $good.launchEligible $false
    Assert-Equal 'no promotion' $good.livePromotionAllowed $false
    Assert-Equal 'no original runtime self claim' $good.originalRuntimeObserved $false
    Assert-Equal 'no auto point' ($null -eq $good.automaticActivationPoint) $true
    Assert-Equal 'manager67 dormant disposition' $good.manager67Disposition 'DORMANT_STRUCTURAL_DATA_ONLY_PRIOR_HIT_REGION_REQUIRED'

    $syntheticReady = Invoke-Evaluation { param($x) $x.strategyOwner.manager65Active=1;$x.strategyOwner.manager65InputGate=1;$x.strategyOwner.manager65Page=2;$x.strategyOwner.manager65BoundCardId=7 }
    Assert-Equal 'synthetic ready remains nonpromotable' $syntheticReady.warpPrelaunchEligible $false
    Assert-Equal 'synthetic ready still needs action receipt' ($syntheticReady.blockers -contains 'MANAGER65_ACTION_0X2B_RECEIPT_REQUIRED') $true
    Assert-Equal 'synthetic ready still needs review' ($syntheticReady.blockers -contains 'INDEPENDENT_LIVE_REVIEW_REQUIRED') $true

    $liveUnreviewed = Invoke-Evaluation { param($x) $x.provenance='LIVE_READONLY';$x.strategyOwner.manager65Active=1;$x.strategyOwner.manager65InputGate=1;$x.strategyOwner.manager65Page=2;$x.strategyOwner.manager65BoundCardId=7 }
    Assert-Equal 'live candidate status' $liveUnreviewed.roleAdjudication.status 'ROLES_LIVE_READONLY_CANDIDATE_UNREVIEWED'
    Assert-Equal 'live unreviewed remains blocked' $liveUnreviewed.warpPrelaunchEligible $false
    Assert-Equal 'live promotion false' $liveUnreviewed.livePromotionAllowed $false

    $bothActive = Invoke-Evaluation { param($x) $x.strategyOwner.manager65Active=1;$x.strategyOwner.manager65InputGate=1;$x.strategyOwner.manager65BoundCardId=7;$x.strategyOwner.manager67Active=1;$x.strategyOwner.manager67InputGate=1 }
    Assert-Equal 'both active incoherent' ($bothActive.blockers -contains 'MUTUALLY_EXCLUSIVE_STAGE_STATE_INCOHERENT') $true
    Assert-Equal 'both active cannot promote' $bothActive.warpPrelaunchEligible $false

    $mutations = @(
        @{name='root extra';change={param($x)$x|Add-Member extra 1};blocker='CAPTURE_SCHEMA_ROOT_KEYS_MISMATCH'},
        @{name='process extra';change={param($x)$x.process|Add-Member extra 1};blocker='CAPTURE_SCHEMA_PROCESS_KEYS_MISMATCH'},
        @{name='hash';change={param($x)$x.process.sha256=('0'*64)};blocker='EXECUTABLE_HASH_MISMATCH'},
        @{name='module';change={param($x)$x.process.moduleBase='0x00500000'};blocker='MODULE_BASE_MISMATCH'},
        @{name='owner';change={param($x)$x.process.hwndOwnerPid=99};blocker='OWNED_HWND_PID_MISMATCH'},
        @{name='hwnd';change={param($x)$x.process.hwnd='0x00000000'};blocker='OWNED_HWND_INVALID'},
        @{name='surface';change={param($x)$x.process.clientWidth=0};blocker='OWNED_HWND_SURFACE_INVALID'},
        @{name='timestamp';change={param($x)$x.captureStartedAtUtc='2026-08-29T00:00:01Z'};blocker='CAPTURE_TIMESTAMP_ORDER_INVALID'},
        @{name='ui pointer';change={param($x)$x.uiRoot.pointer='0x00000000'};blocker='UI_ROOT_NULL'},
        @{name='builder';change={param($x)$x.uiRoot.builderMode=1};blocker='UI_ROOT_BUILDER_MODE_NOT_2'},
        @{name='handler';change={param($x)$x.uiRoot.handlerState=0};blocker='UI_ROOT_HANDLER_STATE_NOT_1'},
        @{name='registry';change={param($x)$x.uiRoot.registryPointer='0x00000000'};blocker='UI_REGISTRY_NULL'},
        @{name='owner base';change={param($x)$x.strategyOwner.pointer='0x00C9E000'};blocker='STRATEGY_OWNER_ADDRESS_MISMATCH'},
        @{name='manager106 id';change={param($x)$x.strategyOwner.firstManagerId=105};blocker='STRATEGY_OWNER_FIRST_MANAGER_ID_NOT_106'},
        @{name='manager106 slot';change={param($x)$x.strategyOwner.registrySlot106Pointer='0x00000000'};blocker='STRATEGY_OWNER_MANAGER106_REGISTRY_MISMATCH'},
        @{name='manager65 slot';change={param($x)$x.strategyOwner.registrySlot101Pointer='0x00000000'};blocker='MANAGER65_REGISTRY_MISMATCH'},
        @{name='manager67 slot';change={param($x)$x.strategyOwner.registrySlot103Pointer='0x00000000'};blocker='MANAGER67_REGISTRY_MISMATCH'},
        @{name='torn';change={param($x)$x.snapshotStable=$false};blocker='TORN_SNAPSHOT'},
        @{name='runtime self claim';change={param($x)$x.originalRuntimeObserved=$true};blocker='SELF_PROMOTION_CLAIM_RECORDED'},
        @{name='permit self claim';change={param($x)$x.permitIssued=$true};blocker='SELF_PROMOTION_CLAIM_RECORDED'},
        @{name='write';change={param($x)$x.operations.writes=1};blocker='FORBIDDEN_OPERATION_RECORDED'},
        @{name='input';change={param($x)$x.operations.gameInputs=1};blocker='FORBIDDEN_OPERATION_RECORDED'},
        @{name='breakpoint';change={param($x)$x.operations.breakpointsInstalled=1};blocker='FORBIDDEN_OPERATION_RECORDED'}
    )
    foreach($mutation in $mutations){$result=Invoke-Evaluation $mutation.change;Assert-Equal "$($mutation.name) rejected" ($result.roleAdjudication.blockers-contains$mutation.blocker) $true;Assert-Equal "$($mutation.name) role unproven" $result.roleAdjudication.rolesProven $false;Assert-Equal "$($mutation.name) warp false" $result.warpPrelaunchEligible $false}
    [ordered]@{status='PASS';cases=$script:cases;assertions=$script:assertions;mutations=$mutations.Count}|ConvertTo-Json -Compress
} finally {
    if (Test-Path -LiteralPath $tempRoot) {
        $resolved=(Resolve-Path -LiteralPath $tempRoot).Path;$base=[IO.Path]::GetFullPath([IO.Path]::GetTempPath())
        if (-not $resolved.StartsWith($base,[StringComparison]::OrdinalIgnoreCase)) { throw 'unsafe test cleanup path' }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
