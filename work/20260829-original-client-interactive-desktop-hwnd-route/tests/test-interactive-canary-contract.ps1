$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$path=Join-Path $root 'src/collect-interactive-session-canary.ps1'
if(-not(Test-Path $path)){throw 'collector missing'}
$text=Get-Content $path -Raw -Encoding UTF8
$required=@('[Parameter(Mandatory=$true)][string]$RunId','EnumWindows','GetWindowThreadProcessId','IsWindowVisible','GetWindowRect','GetClientRect','GetForegroundWindow','GetProcessWindowStation','GetThreadDesktop','GetUserObjectInformation','WTSGetActiveConsoleSessionId','LIVE_READONLY_INTERACTIVE_CANARY','label=''A''','label=''B''','$firstObservation | ConvertTo-Json','$secondObservation | ConvertTo-Json','foregroundChanges=0','gameInputs=0','automaticInputs=0','debuggerAttach=0','breakpointsInstalled=0','permitIssued=$false')
$forbidden=@('$first | ConvertTo-Json','$second | ConvertTo-Json','SendInput','SetForegroundWindow','ShowWindow','mouse_event','keybd_event','SetCursorPos','PostMessage','SendMessage','ReadProcessMemory','WriteProcessMemory','DebugActiveProcess','OpenProcess(','CreateProcess','Start-Process','Invoke-Command','schtasks','Start-Service','Stop-Service','Restart-Service','vmrun','vncdo')
foreach($marker in $required){if(-not$text.Contains($marker)){throw "missing $marker"}}
foreach($marker in $forbidden){if($text.Contains($marker)){throw "forbidden $marker"}}
$tokens=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile((Resolve-Path $path),[ref]$tokens,[ref]$errors)
if(@($errors).Count-ne0){throw 'collector parser errors'}
$forbiddenCommands=@('Start-Process','Invoke-Command','Enter-PSSession','New-PSSession','Remove-Item','Move-Item','Copy-Item','Set-ItemProperty','New-ItemProperty','Stop-Process')
$commands=@($ast.FindAll({param($node)$node -is [Management.Automation.Language.CommandAst]},$true)|ForEach-Object{$_.GetCommandName()}|Where-Object{$_})
foreach($command in $forbiddenCommands){if($commands-contains$command){throw "forbidden command $command"}}
[ordered]@{status='PASS';assertions=$required.Count+$forbidden.Count+$forbiddenCommands.Count;commands=@($commands|Sort-Object -Unique)}|ConvertTo-Json -Compress
