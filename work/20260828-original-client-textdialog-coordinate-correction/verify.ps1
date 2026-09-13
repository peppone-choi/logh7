$ErrorActionPreference='Stop'
$root=$PSScriptRoot
$collectorTest=& (Join-Path $root 'tests/test-collect-textdialog-coordinate-snapshot.ps1')|ConvertFrom-Json
$resolverTest=& (Join-Path $root 'tests/test-resolve-textdialog-client-rects.ps1')|ConvertFrom-Json
$collector=Get-Content (Join-Path $root 'src/collect-textdialog-coordinate-snapshot.ps1') -Raw
$resolver=Get-Content (Join-Path $root 'src/resolve-textdialog-client-rects.ps1') -Raw
$forbidden=@('WriteProcessMemory','VirtualProtectEx','SendInput','mouse_event','SetCursorPos','PostMessage','SendMessage')|Where-Object{$collector-match$_-or$resolver-match$_}
$required=@('0x00CA292C','0xDBC','0xDC0','0x00772E2C','0x00772E30','0x18','TORN_SNAPSHOT','LIVE_SNAPSHOT_INDEPENDENT_BINDING_REQUIRED','0x00400000','OWNED_HWND_SURFACE_TORN','ENGINE_RECT_HWND_MISMATCH')|Where-Object{$collector-match[regex]::Escape($_)-or$resolver-match[regex]::Escape($_)}
if($collectorTest.result-ne'PASS'-or$resolverTest.result-ne'PASS'-or$forbidden.Count-ne0-or$required.Count-ne11){throw 'verification failed'}
[ordered]@{result='PASS';collector=$collectorTest;resolver=$resolverTest;requiredMarkers=$required.Count;forbiddenCapabilityHits=$forbidden.Count;liveOperations=0;gameInputs=0;permitIssued=$false}|ConvertTo-Json -Depth 8
