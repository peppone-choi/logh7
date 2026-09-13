[CmdletBinding()]
param(
 [int]$TargetProcessId,[string]$ExpectedStartTimeUtc,[string]$ExpectedExecutableSha256,[string]$ExpectedWindowHandle,
 [string]$FixtureMemoryPath,[string]$FixtureIdentityPath,[Parameter(Mandatory=$true)][string]$OutputPath
)
$ErrorActionPreference='Stop'
$canonical='BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16';$script:reads=0
function Hex([uint32]$v){'0x{0:X8}'-f$v}
function Add([uint32]$b,[uint32]$o){$v=[uint64]$b+$o;if($v-gt[uint32]::MaxValue){throw'address overflow'};[uint32]$v}
$fixtureMode=![string]::IsNullOrWhiteSpace($FixtureMemoryPath)
if($fixtureMode-ne(![string]::IsNullOrWhiteSpace($FixtureIdentityPath))){throw'Both fixture paths are required.'}
$handle=[IntPtr]::Zero
if($fixtureMode){$fixture=Get-Content $FixtureMemoryPath -Raw -Encoding UTF8|ConvertFrom-Json;$identity=Get-Content $FixtureIdentityPath -Raw -Encoding UTF8|ConvertFrom-Json -DateKind String}
else{
 if($ExpectedExecutableSha256.ToUpperInvariant()-ne$canonical){throw'Expected executable SHA-256 is not canonical.'}
 if($TargetProcessId-le0-or[string]::IsNullOrWhiteSpace($ExpectedStartTimeUtc)-or[string]::IsNullOrWhiteSpace($ExpectedWindowHandle)){throw'Live identity is incomplete.'}
 if(-not('WarpOwnerReadOnlyNative'-as[type])){Add-Type -TypeDefinition @'
using System; using System.Runtime.InteropServices;
public static class WarpOwnerReadOnlyNative {
 [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left,Top,Right,Bottom; }
 [DllImport("kernel32.dll",SetLastError=true)] public static extern IntPtr OpenProcess(uint a,bool i,int p);
 [DllImport("kernel32.dll",SetLastError=true)] public static extern bool ReadProcessMemory(IntPtr p,IntPtr a,byte[] b,UIntPtr n,out UIntPtr r);
 [DllImport("kernel32.dll")] public static extern bool CloseHandle(IntPtr h);
 [DllImport("user32.dll")] public static extern bool IsWindow(IntPtr h);
 [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h,out uint p);
 [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr h,out RECT r);
}
'@}
 $p=Get-Process -Id $TargetProcessId;$p.Refresh();if($p.ProcessName-ne'G7MTClient'){throw'Target is not G7MTClient.'}
 $start=$p.StartTime.ToUniversalTime().ToString('o');if($start-ne([DateTime]::Parse($ExpectedStartTimeUtc).ToUniversalTime().ToString('o'))){throw'Start time mismatch.'}
 $hash=(Get-FileHash $p.Path -Algorithm SHA256).Hash;if($hash-ne$canonical){throw'Executable hash mismatch.'}
 $module=[uint32]$p.MainModule.BaseAddress.ToInt64();$hwnd=[IntPtr][Convert]::ToInt64(($ExpectedWindowHandle-replace'^0x',''),16)
 if(-not[WarpOwnerReadOnlyNative]::IsWindow($hwnd)){throw'Invalid HWND.'};[uint32]$owner=0;[void][WarpOwnerReadOnlyNative]::GetWindowThreadProcessId($hwnd,[ref]$owner);if($owner-ne$TargetProcessId-or$p.MainWindowHandle-ne$hwnd){throw'HWND ownership mismatch.'}
 $rc=New-Object WarpOwnerReadOnlyNative+RECT;if(-not[WarpOwnerReadOnlyNative]::GetClientRect($hwnd,[ref]$rc)){throw'GetClientRect failed.'}
 $identity=[pscustomobject]@{pid=$p.Id;startTimeUtc=$start;sha256=$hash;moduleBase=(Hex $module);hwnd=(Hex ([uint32]$hwnd.ToInt64()));hwndOwnerPid=$owner;clientWidth=$rc.Right-$rc.Left;clientHeight=$rc.Bottom-$rc.Top;secondHwndOwnerPid=$owner;secondClientWidth=$rc.Right-$rc.Left;secondClientHeight=$rc.Bottom-$rc.Top}
 $handle=[WarpOwnerReadOnlyNative]::OpenProcess(0x0410,$false,$TargetProcessId);if($handle-eq[IntPtr]::Zero){throw'OpenProcess read-only failed.'}
}
function Bytes([uint32]$a,[int]$n){$b=[byte[]]::new($n);$got=[UIntPtr]::Zero;if(-not[WarpOwnerReadOnlyNative]::ReadProcessMemory($handle,[IntPtr][int64]$a,$b,[UIntPtr][uint64]$n,[ref]$got)-or$got.ToUInt64()-ne$n){throw"ReadProcessMemory failed at $(Hex $a)"};$script:reads++;,$b}
function FV($section,$kind,[uint32]$a){$k=Hex $a;$v=$section.$kind.PSObject.Properties[$k];if($null-eq$v){throw"Missing fixture $kind $k"};$script:reads++;$v.Value}
function U32($section,[uint32]$a){if($fixtureMode){[uint32](FV $section u32 $a)}else{[BitConverter]::ToUInt32((Bytes $a 4),0)}}
function I32($section,[uint32]$a){if($fixtureMode){[int32](FV $section i32 $a)}else{[BitConverter]::ToInt32((Bytes $a 4),0)}}
function U8($section,[uint32]$a){if($fixtureMode){[byte](FV $section u8 $a)}else{(Bytes $a 1)[0]}}
function Capture($section,[uint32]$module){
 $block=[Collections.Generic.List[string]]::new();$activeField=Add $module 0x89E2F8;$owner=U32 $section $activeField
 if($owner-eq0){$block.Add('ACTIVE_WARP_STAGE_OWNER_NULL');return [ordered]@{blockers=@($block);activeField=Hex $activeField;owner=0}}
 $ownerVtable=U32 $section $owner;$begin=U32 $section (Add $owner 0x10);$end=U32 $section (Add $owner 0x14);$cap=U32 $section (Add $owner 0x18);$cardId=U32 $section (Add $owner 0x20);$command=U32 $section (Add $owner 0x28);$currentCardId=U32 $section (Add $module 0x89EAC0)
 if($ownerVtable-ne(Add $module 0x2702B8)){$block.Add('FLOW_OWNER_VTABLE_MISMATCH')};if($command-ne0x2B){$block.Add('FLOW_OWNER_COMMAND_NOT_0X2B')};if($cardId-ne$currentCardId){$block.Add('FLOW_OWNER_AUTHORITY_CARD_MISMATCH')}
 $vectorValid=($begin-ne0-and$begin-le$end-and$end-le$cap-and(($end-$begin)%4)-eq0-and(($cap-$begin)%4)-eq0)
 if(-not$vectorValid){$block.Add('FLOW_CHILD_VECTOR_INVALID');return [ordered]@{blockers=@($block);activeField=Hex $activeField;owner=$owner;ownerVtable=$ownerVtable;begin=$begin;end=$end;cap=$cap;command=$command}}
 $count=[int](($end-$begin)/4);if($count-ne6){$block.Add('WARP_CHILD_COUNT_NOT_6')};if($count-gt32){$block.Add('FLOW_CHILD_COUNT_UNBOUNDED');return [ordered]@{blockers=@($block);activeField=Hex $activeField;owner=$owner;ownerVtable=$ownerVtable;begin=$begin;end=$end;cap=$cap;command=$command}}
 $children=@();for($i=0;$i-lt$count;$i++){$ptr=U32 $section (Add $begin ([uint32](4*$i)));if($ptr-eq0){$children+=,[ordered]@{index=$i;pointer=0;vtable=0};$block.Add("WARP_CHILD_${i}_NULL")}else{$children+=,[ordered]@{index=$i;pointer=$ptr;vtable=U32 $section $ptr}}}
 $expected=@((Add $module 0x270228),(Add $module 0x276B30),(Add $module 0x275780),(Add $module 0x276AEC),(Add $module 0x276AA8),(Add $module 0x2702C0))
 if($count-eq6){for($i=0;$i-lt6;$i++){if($children[$i].vtable-ne$expected[$i]){$block.Add("WARP_CHILD_${i}_VTABLE_MISMATCH")}}}
 $text=$null;$managerBase=Add $module 0x8A292C;$manager=$null
 if($count-gt2-and$children[2].pointer-ne0){$tp=[uint32]$children[2].pointer;$builder=U32 $section (Add $tp 0x38);$variant=U32 $section (Add $tp 0x3C);$managerIndex=U32 $section (Add $tp 0x40);$textCommand=U32 $section (Add $tp 0x48);$textArg=U32 $section (Add $tp 0x4C);$managerPointer=U32 $section (Add $tp 0x58);if($builder-ne4){$block.Add('TEXT_DIALOG_BUILDER_NOT_4')};if($variant-ne0){$block.Add('TEXT_DIALOG_VARIANT_NOT_0')};if($managerIndex-ne3){$block.Add('TEXT_DIALOG_MANAGER_INDEX_NOT_3')};if($textCommand-ne0x2B-or$textArg-ne0){$block.Add('TEXT_DIALOG_COMMAND_BINDING_MISMATCH')};if($managerPointer-ne$managerBase){$block.Add('TEXT_DIALOG_MANAGER_POINTER_MISMATCH')};$text=[ordered]@{pointer=$tp;builder=$builder;variant=$variant;managerIndex=$managerIndex;command=$textCommand;commandArg=$textArg;managerPointer=$managerPointer};$layout=I32 $section (Add $managerBase 0x37C);$terminal=I32 $section (Add $managerBase 0xDE0);$ui=U32 $section (Add $managerBase 8);if($ui-eq0){$block.Add('TEXT_DIALOG_UI_CONTEXT_NULL')};if($layout-ne4){$block.Add('TEXT_DIALOG_LAYOUT_NOT_4')};if($terminal-notin@(1,2)){$block.Add('TEXT_DIALOG_NOT_WAITING')};$manager=[ordered]@{base=$managerBase;uiContext=$ui;layout=$layout;terminalState=$terminal}}
 if($count-gt3-and$children[3].pointer-ne0){if((U8 $section (Add ([uint32]$children[3].pointer) 0x28))-ne1){$block.Add('SEND_WARP_FLAG_NOT_1')}}
 if($count-gt5-and$children[5].pointer-ne0){if((U32 $section (Add ([uint32]$children[5].pointer) 0x28))-ne0x0B07-or(U32 $section (Add ([uint32]$children[5].pointer) 0x2C))-ne0x0B01){$block.Add('RECEIVE_RESULT_OPCODE_PAIR_MISMATCH')}}
 [ordered]@{blockers=@($block);activeField=Hex $activeField;owner=$owner;ownerVtable=$ownerVtable;begin=$begin;end=$end;cap=$cap;cardId=$cardId;currentCardId=$currentCardId;command=$command;children=$children;textDialog=$text;manager=$manager}
}
try{
 [uint32]$module=[Convert]::ToUInt32(([string]$identity.moduleBase-replace'^0x',''),16);$aSection=if($fixtureMode){$fixture.first}else{$null};$bSection=if($fixtureMode-and$fixture.second-is[string]){$fixture.first}elseif($fixtureMode){$fixture.second}else{$null};$a=Capture $aSection $module;$b=Capture $bSection $module
 if(-not$fixtureMode){$p.Refresh();if(-not[WarpOwnerReadOnlyNative]::IsWindow($hwnd)-or$p.MainWindowHandle-ne$hwnd){throw'Post-capture HWND invalid.'};[uint32]$owner2=0;[void][WarpOwnerReadOnlyNative]::GetWindowThreadProcessId($hwnd,[ref]$owner2);$rc2=New-Object WarpOwnerReadOnlyNative+RECT;if(-not[WarpOwnerReadOnlyNative]::GetClientRect($hwnd,[ref]$rc2)){throw'Post-capture GetClientRect failed.'};$identity.secondHwndOwnerPid=$owner2;$identity.secondClientWidth=$rc2.Right-$rc2.Left;$identity.secondClientHeight=$rc2.Bottom-$rc2.Top}
 $stable=(($a|ConvertTo-Json -Depth 15 -Compress)-eq($b|ConvertTo-Json -Depth 15 -Compress));$surfaceStable=([int]$identity.hwndOwnerPid-eq[int]$identity.secondHwndOwnerPid-and[int]$identity.clientWidth-eq[int]$identity.secondClientWidth-and[int]$identity.clientHeight-eq[int]$identity.secondClientHeight);$block=@($a.blockers);if(-not$stable){$block+='TORN_WARP_STAGE_OWNER_SNAPSHOT'};if(-not$surfaceStable){$block+='OWNED_HWND_SURFACE_TORN'};if(([string]$identity.sha256).ToUpperInvariant()-ne$canonical){$block+='CANONICAL_EXECUTABLE_HASH_MISMATCH'};if($module-eq0){$block+='MODULE_BASE_NULL'}
 $children=@($a.children|ForEach-Object{[ordered]@{index=$_.index;pointer=Hex ([uint32]$_.pointer);vtable=Hex ([uint32]$_.vtable)}});$text=if($null-eq$a.textDialog){$null}else{[ordered]@{childIndex=2;pointer=Hex ([uint32]$a.textDialog.pointer);vtable=Hex (Add $module 0x275780);builder=$a.textDialog.builder;variant=$a.textDialog.variant;managerIndex=$a.textDialog.managerIndex;commandId=$a.textDialog.command;commandArg=$a.textDialog.commandArg;managerPointer=Hex ([uint32]$a.textDialog.managerPointer)}};$manager=if($null-eq$a.manager){$null}else{[ordered]@{base=Hex ([uint32]$a.manager.base);uiContextPointer=Hex ([uint32]$a.manager.uiContext);layout=$a.manager.layout;terminalState=$a.manager.terminalState}}
 $result=[ordered]@{schemaVersion=1;provenance=if($fixtureMode){'SYNTHETIC_FIXTURE'}else{'LIVE_READONLY_SELF_CLAIM'};process=$identity;stageOwner=[ordered]@{activePointerField=Hex (Add $module 0x89E2F8);pointer=Hex ([uint32]$a.owner);vtable=Hex ([uint32]$a.ownerVtable);commandId=$a.command;cardId=$a.cardId;currentAuthorityCardId=$a.currentCardId;childVector=[ordered]@{begin=Hex ([uint32]$a.begin);end=Hex ([uint32]$a.end);capacity=Hex ([uint32]$a.cap)};childCount=@($a.children).Count;children=$children};textDialog=$text;manager=$manager;snapshotStable=$stable;windowSurfaceStable=$surfaceStable;stateEligible=(@($block).Count-eq0);blockers=@($block);runtimeBindingStatus=if($fixtureMode){'OFFLINE_FIXTURE_ONLY'}else{'LIVE_SNAPSHOT_INDEPENDENT_BINDING_REQUIRED'};fixtureCoordinateReusable=$false;permitIssued=$false;operations=[ordered]@{memoryReads='READ_ONLY';memoryReadCount=$script:reads;writes=0;gameInputs=0;breakpointsInstalled=0}}
 $dir=Split-Path $OutputPath;if($dir-and-not(Test-Path $dir)){New-Item -ItemType Directory $dir|Out-Null};$result|ConvertTo-Json -Depth 20|Set-Content $OutputPath -Encoding UTF8
}finally{if($handle-ne[IntPtr]::Zero){[void][WarpOwnerReadOnlyNative]::CloseHandle($handle)}}
