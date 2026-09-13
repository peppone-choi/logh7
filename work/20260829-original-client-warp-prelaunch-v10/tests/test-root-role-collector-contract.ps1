$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$collector = Join-Path $root 'src/collect-root-role-adjudication.ps1'
if (-not (Test-Path -LiteralPath $collector)) { throw 'collector missing' }
$text = Get-Content -LiteralPath $collector -Raw -Encoding UTF8
$required = @('OpenProcess(0x410', 'ReadProcessMemory', 'IsWindow', 'GetWindowThreadProcessId', 'GetClientRect', 'captureStartedAtUtc', 'captureCompletedAtUtc', 'hashAfter', 'moduleAfter', "originalRuntimeObserved = `$false", "permitIssued = `$false", '0x1E15E2C', '0x89E638', '0x198', '0x1A0', '0x1AC')
foreach ($marker in $required) { if (-not $text.Contains($marker)) { throw "missing marker $marker" } }
$forbidden = @('WriteProcessMemory', 'SendInput', 'mouse_event', 'keybd_event', 'SetForegroundWindow', 'bphws', 'SetThreadContext')
foreach ($marker in $forbidden) { if ($text.Contains($marker)) { throw "forbidden marker $marker" } }
[ordered]@{status='PASS';assertions=$required.Count+$forbidden.Count} | ConvertTo-Json -Compress
