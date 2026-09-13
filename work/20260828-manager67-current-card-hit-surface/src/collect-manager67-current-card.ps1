[CmdletBinding()]
param(
    [int]$TargetProcessId,
    [string]$ExpectedStartTimeUtc,
    [string]$ExpectedExecutableSha256,
    [string]$ExpectedWindowHandle,
    [string]$FixtureMemoryPath,
    [string]$FixtureIdentityPath,
    [Parameter(Mandatory=$true)][string]$OutputPath
)

$ErrorActionPreference='Stop'
$canonical='BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16'
$script:reads=0
function Hex([uint32]$Value){'0x{0:X8}'-f$Value}
function Add([uint32]$Base,[uint32]$Offset){$value=[uint64]$Base+$Offset;if($value-gt[uint32]::MaxValue){throw'address overflow'};[uint32]$value}

$fixtureMode=![string]::IsNullOrWhiteSpace($FixtureMemoryPath)
if($fixtureMode-ne(![string]::IsNullOrWhiteSpace($FixtureIdentityPath))){throw'Both fixture paths are required.'}
$handle=[IntPtr]::Zero

if($fixtureMode){
    $fixture=Get-Content $FixtureMemoryPath -Raw -Encoding UTF8|ConvertFrom-Json
    $identity=Get-Content $FixtureIdentityPath -Raw -Encoding UTF8|ConvertFrom-Json -DateKind String
}else{
    if($ExpectedExecutableSha256.ToUpperInvariant()-ne$canonical){throw'Expected executable SHA-256 is not the canonical G7MTClient target.'}
    if($TargetProcessId-le0-or[string]::IsNullOrWhiteSpace($ExpectedStartTimeUtc)-or[string]::IsNullOrWhiteSpace($ExpectedWindowHandle)){throw'Live identity is incomplete.'}
    if(-not('Manager67ReadOnlyNative'-as[type])){Add-Type -TypeDefinition @'
using System; using System.Runtime.InteropServices;
public static class Manager67ReadOnlyNative {
 [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left,Top,Right,Bottom; }
 [DllImport("kernel32.dll",SetLastError=true)] public static extern IntPtr OpenProcess(uint a,bool i,int p);
 [DllImport("kernel32.dll",SetLastError=true)] public static extern bool ReadProcessMemory(IntPtr p,IntPtr a,byte[] b,UIntPtr n,out UIntPtr r);
 [DllImport("kernel32.dll")] public static extern bool CloseHandle(IntPtr h);
 [DllImport("user32.dll")] public static extern bool IsWindow(IntPtr h);
 [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h,out uint p);
 [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr h,out RECT r);
}
'@}
    $process=Get-Process -Id $TargetProcessId;$process.Refresh()
    if($process.ProcessName-ne'G7MTClient'){throw'Target is not G7MTClient.'}
    $start=$process.StartTime.ToUniversalTime().ToString('o')
    if($start-ne([DateTime]::Parse($ExpectedStartTimeUtc).ToUniversalTime().ToString('o'))){throw'Start time mismatch.'}
    $hash=(Get-FileHash $process.Path -Algorithm SHA256).Hash
    if($hash-ne$canonical){throw'Executable hash mismatch.'}
    $module=('0x{0:X8}'-f$process.MainModule.BaseAddress.ToInt64())
    if($module-ne'0x00400000'){throw'Module base mismatch.'}
    $hwnd=[IntPtr][Convert]::ToInt64(($ExpectedWindowHandle-replace'^0x',''),16)
    if(-not[Manager67ReadOnlyNative]::IsWindow($hwnd)){throw'Invalid HWND.'}
    [uint32]$owner=0;[void][Manager67ReadOnlyNative]::GetWindowThreadProcessId($hwnd,[ref]$owner)
    if($owner-ne$TargetProcessId-or$process.MainWindowHandle-ne$hwnd){throw'HWND ownership mismatch.'}
    $rect=New-Object Manager67ReadOnlyNative+RECT
    if(-not[Manager67ReadOnlyNative]::GetClientRect($hwnd,[ref]$rect)){throw'GetClientRect failed.'}
    $identity=[pscustomobject]@{pid=$process.Id;startTimeUtc=$start;sha256=$hash;moduleBase=$module;hwnd=('0x{0:X8}'-f$hwnd.ToInt64());hwndOwnerPid=$owner;clientWidth=$rect.Right-$rect.Left;clientHeight=$rect.Bottom-$rect.Top;secondHwndOwnerPid=$owner;secondClientWidth=$rect.Right-$rect.Left;secondClientHeight=$rect.Bottom-$rect.Top}
    $handle=[Manager67ReadOnlyNative]::OpenProcess(0x410,$false,$TargetProcessId)
    if($handle-eq[IntPtr]::Zero){throw'OpenProcess read-only failed.'}
}

$moduleBase=if($fixtureMode){[uint32]0x00400000}else{[uint32][Convert]::ToUInt64(([string]$identity.moduleBase-replace'^0x',''),16)}
function VA([uint32]$Rva){Add $moduleBase $Rva}
function Read-Bytes([uint32]$Address,[int]$Length){$buffer=[byte[]]::new($Length);$got=[UIntPtr]::Zero;if(-not[Manager67ReadOnlyNative]::ReadProcessMemory($handle,[IntPtr][int64]$Address,$buffer,[UIntPtr][uint64]$Length,[ref]$got)-or$got.ToUInt64()-ne$Length){throw"ReadProcessMemory failed at $(Hex $Address)"};$script:reads++;,$buffer}
function Fixture-Value($Section,[string]$Kind,[uint32]$Address){$key=Hex $Address;$property=$Section.$Kind.PSObject.Properties[$key];if($null-eq$property){throw "Missing fixture $Kind $key"};$script:reads++;$property.Value}
function U32($s,[uint32]$a){if($fixtureMode){[uint32](Fixture-Value $s u32 $a)}else{[BitConverter]::ToUInt32((Read-Bytes $a 4),0)}}
function I32($s,[uint32]$a){if($fixtureMode){[int32](Fixture-Value $s i32 $a)}else{[BitConverter]::ToInt32((Read-Bytes $a 4),0)}}
function U16($s,[uint32]$a){if($fixtureMode){[uint16](Fixture-Value $s u16 $a)}else{[BitConverter]::ToUInt16((Read-Bytes $a 2),0)}}
function U8($s,[uint32]$a){if($fixtureMode){[byte](Fixture-Value $s u8 $a)}else{(Read-Bytes $a 1)[0]}}
function F32($s,[uint32]$a){if($fixtureMode){[single](Fixture-Value $s f32 $a)}else{[BitConverter]::ToSingle((Read-Bytes $a 4),0)}}

function Read-Context($s,[uint32]$Root){
    $nodes=@();$seen=@{};$pointer=$Root;$x=0;$y=0
    for($depth=0;$depth-lt16;$depth++){
        if($pointer-eq0){throw'NULL_CONTEXT'};$key=Hex $pointer
        if($seen.ContainsKey($key)){throw'CONTEXT_CYCLE'};$seen[$key]=$true
        $id=U32 $s $pointer;$active=U8 $s (Add $pointer 4);$parent=I32 $s (Add $pointer 8);$localX=I32 $s (Add $pointer 0xC);$localY=I32 $s (Add $pointer 0x10);$registry=U32 $s (Add $pointer 0x30)
        $nodes+=,[ordered]@{pointer=$key;id=[int]$id;active=[int]$active;parentId=$parent;localX=$localX;localY=$localY;registry=Hex $registry};$x+=$localX;$y+=$localY
        if($parent-eq-1){return [ordered]@{nodes=$nodes;resolvedX=$x;resolvedY=$y}}
        if($parent-lt0-or$parent-gt0x72-or$parent-eq$id-or$registry-eq0){throw'INVALID_PARENT_CHAIN'}
        $pointer=U32 $s (Add $registry ([uint32](4+4*$parent)))
    }
    throw'CONTEXT_DEPTH_EXCEEDED'
}

function Read-Widget($s,[uint32]$Pointer,[int]$OriginX,[int]$OriginY,[string]$Surface){
    if($Pointer-eq0){return [ordered]@{surface=$Surface;widgetPointer='0x00000000';status='NULL';eligible=$false}}
    $initialized=U8 $s (Add $Pointer 8);$selector=U8 $s (Add $Pointer 0xA);$localX=I32 $s (Add $Pointer 0xC);$localY=I32 $s (Add $Pointer 0x10);$localGate=U8 $s (Add $Pointer 0x14);$hit=U8 $s (Add $Pointer 0x15);$active=U8 $s (Add $Pointer 0x18);$renderVisible=U8 $s (Add $Pointer 0x1B);$x=I32 $s (Add $Pointer 0x20);$y=I32 $s (Add $Pointer 0x24);$width=I32 $s (Add $Pointer 0x2C);$height=I32 $s (Add $Pointer 0x30)
    if($selector-ne0){$x+=$localX;$y+=$localY};$x+=$OriginX;$y+=$OriginY
    $eligible=$initialized-ne0-and$hit-ne0-and$active-ne0-and$renderVisible-ne0-and$width-gt0-and$height-gt0-and($selector-eq0-or$localGate-ne0)
    [ordered]@{surface=$Surface;widgetPointer=Hex $Pointer;status='READ';initialized=[int]$initialized;localSelector=[int]$selector;localGate=[int]$localGate;hitTestEnabled=[int]$hit;activeVisible=[int]$active;renderVisible=[int]$renderVisible;width=$width;height=$height;eligible=$eligible;logicalRect=[ordered]@{left=$x;top=$y;right=$x+$width;bottom=$y+$height}}
}

function Capture($s){
    $strategyRoot=VA 0x89E638
    $builderMode=I32 $s $strategyRoot;$handlerState=I32 $s (Add $strategyRoot 4);$strategyMode=I32 $s (Add $strategyRoot 0xF4);$boundAuthorityCardId=I32 $s (Add $strategyRoot 0x488)
    $registryHost=U32 $s (VA 0x1E15E2C);$registry=U32 $s (Add $registryHost 0xC);$registry67=U32 $s (Add $registry 0x1A0)
    $controller=Add $strategyRoot 0x48C;$manager=U32 $s $controller;$page=I32 $s (Add $controller 0x61C);$count=I32 $s (Add $controller 0x620);$selected=I32 $s (Add $controller 0x624);$dataOwner=U32 $s (Add $controller 0x628);$dataCount=U8 $s (Add $dataOwner 0x270)
    $currentCharacterOwner=U32 $s (VA 0x3CCFFC);$expectedCurrentRecord=U32 $s (Add $currentCharacterOwner 8)
    $scaleX=F32 $s (VA 0x372E2C);$scaleY=F32 $s (VA 0x372E30);$engine=U32 $s (VA 0x3C1B4C);$logicalWidth=I32 $s (Add $engine 0x2A3D8);$logicalHeight=I32 $s (Add $engine 0x2A3DC);$engineRect=[ordered]@{left=I32 $s (Add $engine 0x2A5FC);top=I32 $s (Add $engine 0x2A600);right=I32 $s (Add $engine 0x2A604);bottom=I32 $s (Add $engine 0x2A608)}
    $context=Read-Context $s $manager;$managerInputGate=U8 $s (Add $manager 5);$cards=@();$recordRoot=Add $currentCharacterOwner 0x3416D8
    if($count-ge1-and$count-le16){for($index=0;$index-lt$count;$index++){$cardId=U16 $s (Add $dataOwner ([uint32](0x26C+($count-$index)*8)));$surfaceC=U32 $s (Add $controller ([uint32](0x88+4*$index)));$surfaceD=U32 $s (Add $controller ([uint32](0xC8+4*$index)));$record=Add $recordRoot ([uint32]($cardId*0x46));$actionCount=U8 $s (Add $record 0x1E);$actions=@();if($actionCount-le24){for($actionIndex=0;$actionIndex-lt$actionCount;$actionIndex++){$actions+=,[int](U16 $s (Add $record ([uint32](0x20+2*$actionIndex))))}};$cards+=,[ordered]@{index=$index;cardId=[int]$cardId;actionCount=[int]$actionCount;actionIds=$actions;hasWarpAction=($actions-contains0x2B);surfaceC=Read-Widget $s $surfaceC $context.resolvedX $context.resolvedY 'C';surfaceD=Read-Widget $s $surfaceD $context.resolvedX $context.resolvedY 'D'}}}
    [ordered]@{strategyRoot=Hex $strategyRoot;builderMode=$builderMode;handlerState=$handlerState;strategyMode=$strategyMode;boundAuthorityCardId=$boundAuthorityCardId;registryHost=Hex $registryHost;registry=Hex $registry;registry67=Hex $registry67;controller=Hex $controller;manager=Hex $manager;managerInputGate=[int]$managerInputGate;page=$page;count=$count;selected=$selected;dataOwner=Hex $dataOwner;dataCount=[int]$dataCount;currentCharacterOwner=Hex $currentCharacterOwner;expectedCurrentRecord=Hex $expectedCurrentRecord;scaleX=$scaleX;scaleY=$scaleY;logicalWidth=$logicalWidth;logicalHeight=$logicalHeight;engineRect=$engineRect;context=$context;cards=$cards}
}

try{
    $firstSection=if($fixtureMode){$fixture.first}else{$null};$secondSection=if($fixtureMode-and$fixture.second-is[string]){$fixture.first}elseif($fixtureMode){$fixture.second}else{$null}
    $first=Capture $firstSection;$second=Capture $secondSection
    if(-not$fixtureMode){$process.Refresh();if($process.StartTime.ToUniversalTime().ToString('o')-ne$identity.startTimeUtc-or(Get-FileHash $process.Path -Algorithm SHA256).Hash-ne$canonical-or('0x{0:X8}'-f$process.MainModule.BaseAddress.ToInt64())-ne'0x00400000'-or-not[Manager67ReadOnlyNative]::IsWindow($hwnd)-or$process.MainWindowHandle-ne$hwnd){throw'Post-capture process/window identity changed.'};[uint32]$owner2=0;[void][Manager67ReadOnlyNative]::GetWindowThreadProcessId($hwnd,[ref]$owner2);$rect2=New-Object Manager67ReadOnlyNative+RECT;if(-not[Manager67ReadOnlyNative]::GetClientRect($hwnd,[ref]$rect2)){throw'Post-capture GetClientRect failed.'};$identity.secondHwndOwnerPid=$owner2;$identity.secondClientWidth=$rect2.Right-$rect2.Left;$identity.secondClientHeight=$rect2.Bottom-$rect2.Top}
    $stable=(($first|ConvertTo-Json -Depth 20 -Compress)-eq($second|ConvertTo-Json -Depth 20 -Compress));$surfaceStable=([int]$identity.hwndOwnerPid-eq[int]$identity.secondHwndOwnerPid-and[int]$identity.clientWidth-eq[int]$identity.secondClientWidth-and[int]$identity.clientHeight-eq[int]$identity.secondClientHeight);$blockers=@()
    if(([string]$identity.sha256).ToUpperInvariant()-ne$canonical){$blockers+='EXECUTABLE_HASH_MISMATCH'};if($identity.moduleBase-ne'0x00400000'){$blockers+='MODULE_BASE_NOT_0X00400000'};if([int]$identity.pid-le0-or[int]$identity.hwndOwnerPid-ne[int]$identity.pid-or[int]$identity.secondHwndOwnerPid-ne[int]$identity.pid){$blockers+='OWNED_HWND_PID_MISMATCH'};if([int]$identity.clientWidth-le0-or[int]$identity.clientHeight-le0-or[int]$identity.secondClientWidth-le0-or[int]$identity.secondClientHeight-le0){$blockers+='NONPOSITIVE_CLIENT_SURFACE'};if(-not$stable){$blockers+='TORN_SNAPSHOT'};if(-not$surfaceStable){$blockers+='OWNED_HWND_SURFACE_TORN'};if($first.engineRect.left-ne0-or$first.engineRect.top-ne0-or$first.engineRect.right-ne[int]$identity.clientWidth-or$first.engineRect.bottom-ne[int]$identity.clientHeight){$blockers+='ENGINE_RECT_HWND_MISMATCH'};if($first.strategyMode-ne2){$blockers+='STRATEGY_MODE_NOT_2'};if($first.registry67-ne$first.manager){$blockers+='MANAGER67_REGISTRY_SLOT_MISMATCH'};if($first.context.nodes[0].id-ne0x67){$blockers+='MANAGER67_ID_MISMATCH'};if($first.context.nodes[0].active-eq0-or$first.managerInputGate-eq0){$blockers+='MANAGER67_CONTEXT_INACTIVE'};if($first.page-notin@(2,3)){$blockers+='MANAGER67_PAGE_NOT_2_OR_3'};if($first.count-lt1-or$first.count-gt16){$blockers+='MANAGER67_CARD_COUNT_OUT_OF_RANGE'};if($first.dataCount-ne$first.count){$blockers+='MANAGER67_DATA_OWNER_COUNT_MISMATCH'};if($first.dataOwner-ne$first.expectedCurrentRecord){$blockers+='MANAGER67_CURRENT_RECORD_SOURCE_MISMATCH'};if($first.selected-ne-1){$blockers+='MANAGER67_PENDING_HIT_NOT_RESET'};if($first.boundAuthorityCardId-lt0-or$first.boundAuthorityCardId-gt0xFFFF){$blockers+='MANAGER65_BOUND_AUTHORITY_CARD_ID_INVALID'};if(@($first.cards|Where-Object{$_.actionCount-gt24}).Count){$blockers+='AUTHORITY_CARD_ACTION_COUNT_OUT_OF_RANGE'}
    if(-not[float]::IsFinite([single]$first.scaleX)-or-not[float]::IsFinite([single]$first.scaleY)-or$first.scaleX-le0-or$first.scaleY-le0){$blockers+='INVALID_SCALE'}elseif([int]$identity.clientWidth-gt0-and[int]$identity.clientHeight-gt0-and([Math]::Abs($first.scaleX-($first.logicalWidth/[double]$identity.clientWidth))-gt0.0001-or[Math]::Abs($first.scaleY-($first.logicalHeight/[double]$identity.clientHeight))-gt0.0001)){$blockers+='SCALE_LOGICAL_SURFACE_MISMATCH'}
    $matches=@($first.cards|Where-Object{$_.cardId-eq$first.boundAuthorityCardId});if($matches.Count-eq0){$blockers+='BOUND_AUTHORITY_CARD_NOT_IN_CURRENT_MANAGER67_LIST'}elseif($matches.Count-gt1){$blockers+='BOUND_AUTHORITY_CARD_NOT_UNIQUE_IN_CURRENT_MANAGER67_LIST'};$selectedAuthorityCard=if($matches.Count-eq1){$match=$matches[0];if(-not$match.hasWarpAction){$blockers+='BOUND_AUTHORITY_CARD_MISSING_WARP_ACTION_0X2B'};$activeSurface=if($first.page-eq2){$match.surfaceC}elseif($first.page-eq3){$match.surfaceD}else{$null};$inactiveSurface=if($first.page-eq2){$match.surfaceD}elseif($first.page-eq3){$match.surfaceC}else{$null};if($null-eq$activeSurface-or-not$activeSurface.eligible){$blockers+='BOUND_AUTHORITY_CARD_NO_ELIGIBLE_SURFACE'};if($null-ne$inactiveSurface-and$inactiveSurface.eligible){$blockers+='MANAGER67_PAGE_SURFACE_GATE_MISMATCH'};[ordered]@{source='MANAGER65_BOUND_CARD_ID_UNIQUELY_RECONCILED_TO_MANAGER67_REVERSED_LIST';semantic='AUTHORITY_CARD_WITH_WARP_ACTION_NOT_PROVEN_CAPTAIN_PORTRAIT';index=$match.index;cardId=$match.cardId;actionIds=$match.actionIds;activeSurface=$activeSurface;inactiveSurface=$inactiveSurface;surfaces=@($match.surfaceC,$match.surfaceD)}}else{$null}
    $output=[ordered]@{schemaVersion=1;provenance=if($fixtureMode){'SYNTHETIC_FIXTURE'}else{'LIVE_READONLY'};process=$identity;strategyRoot=[ordered]@{pointer=$first.strategyRoot;builderMode=$first.builderMode;handlerState=$first.handlerState;strategyMode=$first.strategyMode};manager65=[ordered]@{boundAuthorityCardId=$first.boundAuthorityCardId};manager67=[ordered]@{controllerPointer=$first.controller;managerPointer=$first.manager;managerId=$first.context.nodes[0].id;managerInputGate=$first.managerInputGate;page=$first.page;cardCount=$first.count;pendingHitIndex=$first.selected;dataOwnerPointer=$first.dataOwner;dataOwnerCount=$first.dataCount;dataOwnerCountMatches=($first.dataCount-eq$first.count);currentCharacterOwnerPointer=$first.currentCharacterOwner;expectedCurrentRecordPointer=$first.expectedCurrentRecord;currentRecordSourceMatches=($first.dataOwner-eq$first.expectedCurrentRecord);registryHostPointer=$first.registryHost;registryPointer=$first.registry;registrySlotPointer=$first.registry67;registrySlotMatches=($first.registry67-eq$first.manager);cards=$first.cards};coordinateFrame=[ordered]@{contextChain=$first.context.nodes;logicalOrigin=[ordered]@{x=$first.context.resolvedX;y=$first.context.resolvedY};scaleX=$first.scaleX;scaleY=$first.scaleY;logicalWidth=$first.logicalWidth;logicalHeight=$first.logicalHeight;engineClientRect=$first.engineRect};selectedAuthorityCard=$selectedAuthorityCard;snapshotStable=$stable;windowSurfaceStable=$surfaceStable;stateEligible=(@($blockers).Count-eq0);blockers=@($blockers);originalRuntimeObserved=$false;permitIssued=$false;operations=[ordered]@{memoryReads='READ_ONLY';memoryReadCount=$script:reads;writes=0;gameInputs=0;breakpointsInstalled=0}}
    $directory=Split-Path $OutputPath;if($directory-and-not(Test-Path $directory)){New-Item -ItemType Directory $directory|Out-Null};$output|ConvertTo-Json -Depth 20|Set-Content $OutputPath -Encoding UTF8
}finally{if($handle-ne[IntPtr]::Zero){[void][Manager67ReadOnlyNative]::CloseHandle($handle)}}
