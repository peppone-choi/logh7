$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$collector = Join-Path $root 'src/collect-textdialog-coordinate-snapshot.ps1'
$fixture = Join-Path $PSScriptRoot 'fixture-ready.json'
$identity = Join-Path $PSScriptRoot 'fixture-identity.json'
$temp = Join-Path ([IO.Path]::GetTempPath()) ('textdialog-collector-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp | Out-Null
$script:n = 0
function Eq($name,$actual,$expected) { $script:n++; if ($actual -ne $expected) { throw "$name expected=$expected actual=$actual" } }
function Variant($name,[scriptblock]$change) {
  $f = Get-Content $fixture -Raw -Encoding UTF8 | ConvertFrom-Json
  $f.second = $f.first | ConvertTo-Json -Depth 8 | ConvertFrom-Json
  & $change $f
  $p = Join-Path $temp "$name.json"; $f | ConvertTo-Json -Depth 10 | Set-Content $p -Encoding UTF8; $p
}
function Run($memory,$name) { $p=Join-Path $temp "$name-out.json"; & $collector -FixtureMemoryPath $memory -FixtureIdentityPath $identity -OutputPath $p; Get-Content $p -Raw -Encoding UTF8 | ConvertFrom-Json }
function RunIdentityVariant($name,[scriptblock]$change) { $i=Get-Content $identity -Raw|ConvertFrom-Json; & $change $i; $ip=Join-Path $temp "$name-identity.json"; $i|ConvertTo-Json|Set-Content $ip; $p=Join-Path $temp "$name-out.json"; & $collector -FixtureMemoryPath $fixture -FixtureIdentityPath $ip -OutputPath $p; Get-Content $p -Raw|ConvertFrom-Json }
try {
  $r=Run $fixture ready
  Eq 'eligible' $r.stateEligible $true
  Eq 'origin x' $r.coordinateFrame.logicalOrigin.x 100
  Eq 'origin y' $r.coordinateFrame.logicalOrigin.y 120
  Eq 'cache match' $r.manager.cachedOriginMatches $true
  Eq 'confirm left' $r.confirm.logicalRect.left 310
  Eq 'confirm top' $r.confirm.logicalRect.top 225
  Eq 'confirm right' $r.confirm.logicalRect.right 430
  Eq 'confirm bottom' $r.confirm.logicalRect.bottom 285
  Eq 'cancel left' $r.cancel.logicalRect.left 440
  Eq 'cancel top' $r.cancel.logicalRect.top 220
  Eq 'confirm active' $r.confirm.activeVisible 1
  Eq 'scale x' $r.coordinateFrame.scaleX 1.25
  Eq 'reads' $r.operations.memoryReadCount 84
  Eq 'writes' $r.operations.writes 0
  Eq 'inputs' $r.operations.gameInputs 0
  Eq 'stable' $r.snapshotStable $true

  $p=Variant parent { param($f)
    foreach($s in @($f.first,$f.second)) {
      $s.i32.'0x02000008'=2
      $s.u32 | Add-Member -NotePropertyName '0x0300000C' -NotePropertyValue 33566720
      $s.u32 | Add-Member -NotePropertyName '0x02003000' -NotePropertyValue 2
      $s.u8 | Add-Member -NotePropertyName '0x02003004' -NotePropertyValue 1
      $s.i32 | Add-Member -NotePropertyName '0x02003008' -NotePropertyValue -1
      $s.i32 | Add-Member -NotePropertyName '0x0200300C' -NotePropertyValue 50
      $s.i32 | Add-Member -NotePropertyName '0x02003010' -NotePropertyValue 60
      $s.u32 | Add-Member -NotePropertyName '0x02003030' -NotePropertyValue 50331648
    }
  }
  $r=Run $p parent
  Eq 'parent origin x' $r.coordinateFrame.logicalOrigin.x 150
  Eq 'parent origin y' $r.coordinateFrame.logicalOrigin.y 180
  Eq 'parent reads' $r.operations.memoryReadCount 98

  $p=Variant cache { param($f) $f.first.i32.'0x00CA36E8'=101; $f.second.i32.'0x00CA36E8'=101 }
  $r=Run $p cache; Eq 'cache blocker' (@($r.blockers)-contains 'MANAGER_CACHED_ORIGIN_MISMATCH') $true
  $p=Variant localgate { param($f) $f.first.u8.'0x02001014'=0; $f.second.u8.'0x02001014'=0 }
  $r=Run $p localgate; Eq 'local blocker' (@($r.blockers)-contains 'CONFIRM_LOCAL_TRANSFORM_GATE_INVALID') $true
  $p=Variant inactive { param($f) $f.first.u8.'0x02001018'=0; $f.second.u8.'0x02001018'=0 }
  $r=Run $p inactive; Eq 'active blocker' (@($r.blockers)-contains 'CONFIRM_WIDGET_NOT_INPUT_ELIGIBLE') $true
  $p=Variant torn { param($f) $f.second.i32.'0x0200102C'=121 }
  $r=Run $p torn; Eq 'torn blocked' (@($r.blockers)-contains 'TORN_SNAPSHOT') $true
  $p=Variant null { param($f) $f.first.u32.'0x00CA2950'=0; $f.second.u32.'0x00CA2950'=0 }
  $r=Run $p null; Eq 'null blocked' (@($r.blockers)-contains 'CONFIRM_WIDGET_POINTER_NULL') $true
  $r=RunIdentityVariant module { param($i) $i.moduleBase='0x00500000' }; Eq 'module blocked' (@($r.blockers)-contains 'MODULE_BASE_NOT_0X00400000') $true
  $r=RunIdentityVariant surface { param($i) $i.secondClientWidth=801 }; Eq 'surface torn blocked' (@($r.blockers)-contains 'OWNED_HWND_SURFACE_TORN') $true
  $p=Variant engine { param($f) $f.first.i32.'0x0402A604'=801; $f.second.i32.'0x0402A604'=801 }; $r=Run $p engine; Eq 'engine surface blocked' (@($r.blockers)-contains 'ENGINE_RECT_HWND_MISMATCH') $true
  $rejected=$false
  try { & $collector -TargetProcessId 2147483647 -ExpectedStartTimeUtc '2000-01-01Z' -ExpectedExecutableSha256 ('0'*64) -ExpectedWindowHandle 0x1 -OutputPath (Join-Path $temp bad.json) } catch { $rejected=$_.Exception.Message -like '*canonical*' }
  Eq 'hash rejected first' $rejected $true
  [ordered]@{result='PASS';cases=11;assertions=$script:n}|ConvertTo-Json
} finally { Remove-Item $temp -Recurse -Force }
