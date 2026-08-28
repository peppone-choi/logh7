$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$resolver=Join-Path $root 'src/resolve-textdialog-client-rects.ps1'
$collector=Join-Path $root 'src/collect-textdialog-coordinate-snapshot.ps1'
$fixture=Join-Path $PSScriptRoot 'fixture-ready.json'; $identity=Join-Path $PSScriptRoot 'fixture-identity.json'
$temp=Join-Path ([IO.Path]::GetTempPath()) ('textdialog-resolver-'+[guid]::NewGuid().ToString('N')); New-Item -ItemType Directory $temp|Out-Null
$script:n=0; function Eq($n,$a,$e){$script:n++;if($a-ne$e){throw "$n expected=$e actual=$a"}}
function Resolve($capture,$name){$p=Join-Path $temp "$name.json";&$resolver -CapturePath $capture -OutputPath $p;Get-Content $p -Raw|ConvertFrom-Json}
try {
 $capture=Join-Path $temp capture.json;&$collector -FixtureMemoryPath $fixture -FixtureIdentityPath $identity -OutputPath $capture
 $r=Resolve $capture ready
 Eq 'status' $r.status 'OFFLINE_RESOLVED'
 Eq 'confirm client left' $r.confirm.clientRect.left 248
 Eq 'confirm right' $r.confirm.clientRect.right 344
 Eq 'confirm top' $r.confirm.clientRect.top 150
 Eq 'confirm bottom' $r.confirm.clientRect.bottom 190
 Eq 'confirm point x' $r.confirm.safePoint.x 295
 Eq 'confirm point y' $r.confirm.safePoint.y 169
 Eq 'forward x' $r.confirm.safePoint.forwardLogical.x 368
 Eq 'forward y' $r.confirm.safePoint.forwardLogical.y 253
 Eq 'cancel left' $r.cancel.clientRect.left 352
 Eq 'cancel right' $r.cancel.clientRect.right 448
 Eq 'unobserved' $r.originalRuntimeObserved $false
 $j=Get-Content $capture -Raw|ConvertFrom-Json;$j.stateEligible=$false;$j.blockers=@('TEST');$p=Join-Path $temp ineligible.json;$j|ConvertTo-Json -Depth 12|Set-Content $p
 $r=Resolve $p ineligible;Eq 'ineligible' $r.status 'UNBOUND'
 $j=Get-Content $capture -Raw|ConvertFrom-Json;$j.coordinateFrame.scaleX=0;$p=Join-Path $temp zero.json;$j|ConvertTo-Json -Depth 12|Set-Content $p
 $r=Resolve $p zero;Eq 'zero' (@($r.blockers)-contains 'INVALID_SCALE') $true
 $j=Get-Content $capture -Raw|ConvertFrom-Json;$j.confirm.logicalRect.right=311;$p=Join-Path $temp tiny.json;$j|ConvertTo-Json -Depth 12|Set-Content $p
 $r=Resolve $p tiny;Eq 'tiny' (@($r.blockers)-contains 'CONFIRM_NO_3X3_SAFE_MARGIN') $true
 $j=Get-Content $capture -Raw|ConvertFrom-Json;$j.provenance='LIVE_READONLY';$p=Join-Path $temp live.json;$j|ConvertTo-Json -Depth 12|Set-Content $p
 $r=Resolve $p live;Eq 'self claim refused' $r.status 'UNBOUND';Eq 'self claim reason' $r.reason 'LIVE_SNAPSHOT_INDEPENDENT_BINDING_REQUIRED'
 [ordered]@{result='PASS';cases=5;assertions=$script:n}|ConvertTo-Json
}finally{Remove-Item $temp -Recurse -Force}
