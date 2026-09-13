[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$CapturePath,
    [Parameter(Mandatory=$true)][string]$OutputPath
)
$ErrorActionPreference = 'Stop'
$canonical = 'BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16'
$capture = Get-Content -LiteralPath $CapturePath -Raw -Encoding UTF8 | ConvertFrom-Json
$roleBlockers = @()
function Exact-Keys([string]$name, $value, [string[]]$expected) {
    if ($null -eq $value) { $script:roleBlockers += "CAPTURE_SCHEMA_${name}_MISSING"; return }
    $actual = @($value.PSObject.Properties.Name | Sort-Object)
    $wanted = @($expected | Sort-Object)
    if (($actual -join '|') -ne ($wanted -join '|')) { $script:roleBlockers += "CAPTURE_SCHEMA_${name}_KEYS_MISMATCH" }
}

Exact-Keys 'ROOT' $capture @('schemaVersion','provenance','captureStartedAtUtc','observedAtUtc','captureCompletedAtUtc','process','uiRoot','strategyOwner','snapshotStable','originalRuntimeObserved','permitIssued','operations')
Exact-Keys 'PROCESS' $capture.process @('pid','startTimeUtc','sha256','moduleBase','hwnd','hwndOwnerPid','clientWidth','clientHeight')
Exact-Keys 'UI_ROOT' $capture.uiRoot @('pointer','builderMode','handlerState','registryPointer')
Exact-Keys 'STRATEGY_OWNER' $capture.strategyOwner @('pointer','firstManagerPointer','firstManagerId','firstManagerRegistryPointer','registrySlot106Pointer','manager65ControllerPointer','manager65Pointer','manager65Id','manager65Active','manager65InputGate','manager65Page','manager65BoundCardId','registrySlot101Pointer','manager67ControllerPointer','manager67Pointer','manager67Id','manager67Active','manager67InputGate','manager67Page','registrySlot103Pointer')
Exact-Keys 'OPERATIONS' $capture.operations @('memoryReads','memoryReadCount','writes','gameInputs','breakpointsInstalled')

if ([int]$capture.schemaVersion -ne 1) { $roleBlockers += 'SCHEMA_VERSION_MISMATCH' }
if (([string]$capture.provenance) -notin @('SYNTHETIC_FIXTURE','LIVE_READONLY')) { $roleBlockers += 'PROVENANCE_NOT_ALLOWED' }
try {
    $started = [DateTimeOffset]::Parse([string]$capture.captureStartedAtUtc)
    $observed = [DateTimeOffset]::Parse([string]$capture.observedAtUtc)
    $completed = [DateTimeOffset]::Parse([string]$capture.captureCompletedAtUtc)
    $processStarted = [DateTimeOffset]::Parse([string]$capture.process.startTimeUtc)
    if ($started -gt $observed -or $observed -gt $completed) { $roleBlockers += 'CAPTURE_TIMESTAMP_ORDER_INVALID' }
    if ($processStarted -gt $started) { $roleBlockers += 'PROCESS_START_AFTER_CAPTURE' }
} catch { $roleBlockers += 'CAPTURE_TIMESTAMP_INVALID' }
if (([string]$capture.process.sha256).ToUpperInvariant() -ne $canonical) { $roleBlockers += 'EXECUTABLE_HASH_MISMATCH' }
if ([string]$capture.process.moduleBase -ne '0x00400000') { $roleBlockers += 'MODULE_BASE_MISMATCH' }
if ([int]$capture.process.pid -le 0 -or [int]$capture.process.hwndOwnerPid -ne [int]$capture.process.pid) { $roleBlockers += 'OWNED_HWND_PID_MISMATCH' }
if (([string]$capture.process.hwnd) -notmatch '^0x[0-9A-Fa-f]{8}$') { $roleBlockers += 'OWNED_HWND_INVALID' } else { try { if ([Convert]::ToUInt32(([string]$capture.process.hwnd).Substring(2),16) -eq 0) { $roleBlockers += 'OWNED_HWND_INVALID' } } catch { $roleBlockers += 'OWNED_HWND_INVALID' } }
if ([int]$capture.process.clientWidth -le 0 -or [int]$capture.process.clientHeight -le 0) { $roleBlockers += 'OWNED_HWND_SURFACE_INVALID' }
if ([string]$capture.uiRoot.pointer -eq '0x00000000') { $roleBlockers += 'UI_ROOT_NULL' }
if ([int]$capture.uiRoot.builderMode -ne 2) { $roleBlockers += 'UI_ROOT_BUILDER_MODE_NOT_2' }
if ([int]$capture.uiRoot.handlerState -ne 1) { $roleBlockers += 'UI_ROOT_HANDLER_STATE_NOT_1' }
if ([string]$capture.uiRoot.registryPointer -eq '0x00000000') { $roleBlockers += 'UI_REGISTRY_NULL' }
if ([string]$capture.strategyOwner.pointer -ne '0x00C9E638') { $roleBlockers += 'STRATEGY_OWNER_ADDRESS_MISMATCH' }
if ([int]$capture.strategyOwner.firstManagerId -ne 106) { $roleBlockers += 'STRATEGY_OWNER_FIRST_MANAGER_ID_NOT_106' }
if ([string]$capture.strategyOwner.firstManagerRegistryPointer -ne [string]$capture.uiRoot.registryPointer) { $roleBlockers += 'STRATEGY_OWNER_MANAGER106_BACKPOINTER_MISMATCH' }
if ([string]$capture.strategyOwner.firstManagerPointer -ne [string]$capture.strategyOwner.registrySlot106Pointer) { $roleBlockers += 'STRATEGY_OWNER_MANAGER106_REGISTRY_MISMATCH' }
if ([int]$capture.strategyOwner.manager65Id -ne 101) { $roleBlockers += 'MANAGER65_ID_MISMATCH' }
if ([string]$capture.strategyOwner.manager65Pointer -ne [string]$capture.strategyOwner.registrySlot101Pointer) { $roleBlockers += 'MANAGER65_REGISTRY_MISMATCH' }
if ([int]$capture.strategyOwner.manager67Id -ne 103) { $roleBlockers += 'MANAGER67_ID_MISMATCH' }
if ([string]$capture.strategyOwner.manager67Pointer -ne [string]$capture.strategyOwner.registrySlot103Pointer) { $roleBlockers += 'MANAGER67_REGISTRY_MISMATCH' }
if (-not [bool]$capture.snapshotStable) { $roleBlockers += 'TORN_SNAPSHOT' }
if ([bool]$capture.originalRuntimeObserved -or [bool]$capture.permitIssued) { $roleBlockers += 'SELF_PROMOTION_CLAIM_RECORDED' }
if ([string]$capture.operations.memoryReads -ne 'READ_ONLY' -or [int]$capture.operations.memoryReadCount -le 0 -or [int]$capture.operations.writes -ne 0 -or [int]$capture.operations.gameInputs -ne 0 -or [int]$capture.operations.breakpointsInstalled -ne 0) { $roleBlockers += 'FORBIDDEN_OPERATION_RECORDED' }

$rolesProven = $roleBlockers.Count -eq 0
$roleStatus = if (-not $rolesProven) {'ROLES_UNPROVEN'} elseif ([string]$capture.provenance -eq 'SYNTHETIC_FIXTURE') {'ROLES_PROVEN_OFFLINE_FIXTURE'} else {'ROLES_LIVE_READONLY_CANDIDATE_UNREVIEWED'}
$stageBlockers = @()
if (-not $rolesProven) { $stageBlockers += 'ROOT_ROLES_UNPROVEN' }
if ([int]$capture.strategyOwner.manager65Active -eq 0 -or [int]$capture.strategyOwner.manager65InputGate -eq 0) { $stageBlockers += 'MANAGER65_CONTEXT_INACTIVE' }
if ([int]$capture.strategyOwner.manager65Page -lt 1 -or [int]$capture.strategyOwner.manager65Page -gt 5) { $stageBlockers += 'MANAGER65_PAGE_OUT_OF_RANGE' }
if ([int]$capture.strategyOwner.manager65BoundCardId -lt 0 -or [int]$capture.strategyOwner.manager65BoundCardId -gt 0xFFFF) { $stageBlockers += 'MANAGER65_BOUND_CARD_ID_INVALID' }
if ([int]$capture.strategyOwner.manager67Active -ne 0 -or [int]$capture.strategyOwner.manager67InputGate -ne 0) { $stageBlockers += 'MUTUALLY_EXCLUSIVE_STAGE_STATE_INCOHERENT' }
$stageBlockers += 'MANAGER65_ACTION_0X2B_RECEIPT_REQUIRED'
$stageBlockers += 'MANAGER65_HIT_REGION_INDEPENDENT_BINDING_REQUIRED'
$stageBlockers += 'MANAGER67_PRIOR_STAGE_HIT_REGION_RECEIPT_REQUIRED'
$stageBlockers += 'INDEPENDENT_LIVE_REVIEW_REQUIRED'

$output = [ordered]@{
    schemaVersion = 2
    sourceCaptureSha256 = (Get-FileHash -LiteralPath $CapturePath -Algorithm SHA256).Hash
    sourceProvenance = [string]$capture.provenance
    roleAdjudication = [ordered]@{status=$roleStatus;rolesProven=$rolesProven;uiRootRole='UI_MODE_AND_REGISTRY_HOST';strategyOwnerRole='INLINE_STRATEGY_MANAGER_OWNER';rejectedInterpretation='INLINE_STRATEGY_OWNER_FIRST_DWORDS_ARE_MODE_STATE';blockers=@($roleBlockers)}
    currentStage = 'MANAGER65_WARP_ACTION'
    manager67Disposition = 'DORMANT_STRUCTURAL_DATA_ONLY_PRIOR_HIT_REGION_REQUIRED'
    rootRoleCandidateEligible = $rolesProven
    warpPrelaunchEligible = $false
    launchEligible = $false
    blockers = @($stageBlockers)
    originalRuntimeObserved = $false
    livePromotionAllowed = $false
    independentReviewStatus = 'NOT_REVIEWED'
    automaticActivationPoint = $null
    operations = [ordered]@{writes=0;gameInputs=0;automaticInputs=0;debuggerAttach=0;debuggerCommands=0;breakpointsInstalled=0;permitIssuance=0}
}
$directory = Split-Path -Parent $OutputPath
if ($directory -and -not (Test-Path -LiteralPath $directory)) { New-Item -ItemType Directory -Path $directory | Out-Null }
$output | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $OutputPath -Encoding UTF8
$output | ConvertTo-Json -Depth 12 -Compress
