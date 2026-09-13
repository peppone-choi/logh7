[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$OutputPath)
$ErrorActionPreference='Stop'
$identity=[Security.Principal.WindowsIdentity]::GetCurrent()
$processes=@()
foreach($name in @('vmtoolsd','VGAuthService','explorer','G7MTClient','x32dbg','taskeng','taskhostw')){
  foreach($process in @(Get-Process -Name $name -ErrorAction SilentlyContinue)){
    $path=$null;$start=$null
    try{$path=$process.Path}catch{}
    try{$start=$process.StartTime.ToUniversalTime().ToString('o')}catch{}
    $processes+=,[ordered]@{name=$process.ProcessName;pid=$process.Id;sessionId=$process.SessionId;path=$path;startTimeUtc=$start}
  }
}
$tasks=@()
if(Get-Command Get-ScheduledTask -ErrorAction SilentlyContinue){
  foreach($task in @(Get-ScheduledTask -ErrorAction SilentlyContinue)){
    $actions=@($task.Actions|ForEach-Object{[ordered]@{execute=$_.Execute;arguments=$_.Arguments;workingDirectory=$_.WorkingDirectory}})
    $surface=($task.TaskPath+' '+$task.TaskName+' '+(($actions|ConvertTo-Json -Compress)-as[string])+' '+$task.Principal.LogonType)
    if($surface-match'(?i)LOGH7|ORACLE|INTERACTIVE|DESKTOP|HWND|POWERSHELL|CODEX'){
      $tasks+=,[ordered]@{taskPath=$task.TaskPath;taskName=$task.TaskName;state=[string]$task.State;principal=[ordered]@{userId=$task.Principal.UserId;logonType=[string]$task.Principal.LogonType;runLevel=[string]$task.Principal.RunLevel};actions=$actions}
    }
  }
}
$services=@()
foreach($service in @(Get-CimInstance Win32_Service -ErrorAction SilentlyContinue)){
  if(($service.Name+' '+$service.DisplayName+' '+$service.PathName)-match'(?i)VMWARE|LOGH7|ORACLE|CODEX|INTERACTIVE|DESKTOP'){
    $services+=,[ordered]@{name=$service.Name;displayName=$service.DisplayName;state=$service.State;startMode=$service.StartMode;startName=$service.StartName;pathName=$service.PathName}
  }
}
$candidateFiles=@()
if(Test-Path -LiteralPath 'C:\LOGH7_ORACLE'){
  foreach($file in @(Get-ChildItem -LiteralPath 'C:\LOGH7_ORACLE' -Recurse -File -ErrorAction SilentlyContinue|Where-Object{$_.Name-match'(?i)interactive|desktop|hwnd|task|helper|launcher|session'})){
    $candidateFiles+=,[ordered]@{path=$file.FullName;length=$file.Length;lastWriteTimeUtc=$file.LastWriteTimeUtc.ToString('o');sha256=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash}
  }
}
$receipt=[ordered]@{
  schemaVersion=1
  provenance='LIVE_READONLY_BROKER_INVENTORY'
  observedAtUtc=[DateTime]::UtcNow.ToString('o')
  caller=[ordered]@{name=$identity.Name;sid=$identity.User.Value;ownerSid=$identity.Owner.Value;sessionId=[Diagnostics.Process]::GetCurrentProcess().SessionId;isSystem=($identity.User.Value-eq'S-1-5-18');authenticationType=$identity.AuthenticationType}
  privileges=@(& "$env:SystemRoot\System32\whoami.exe" /priv /fo csv /nh 2>&1|ForEach-Object{[string]$_})
  processes=$processes
  scheduledTaskCandidates=$tasks
  serviceCandidates=$services
  existingHelperCandidates=$candidateFiles
  operations=[ordered]@{guestObservationHelpers=1;processesCreated=0;scheduledTaskChanges=0;serviceChanges=0;processMemoryReads=0;processMemoryWrites=0;foregroundChanges=0;debuggerAttach=0;debuggerCommands=0;breakpointsInstalled=0;captures=0;gameInputs=0;automaticInputs=0;permitIssued=$false;vmLifecycleChanges=0;serverChanges=0;protocolChanges=0;databaseChanges=0}
}
$parent=Split-Path -Parent $OutputPath;if($parent-and-not(Test-Path -LiteralPath $parent)){New-Item -ItemType Directory -Path $parent|Out-Null}
[IO.File]::WriteAllText($OutputPath,(($receipt|ConvertTo-Json -Depth 12)+"`n"),[Text.UTF8Encoding]::new($false))
'INTERACTIVE_BROKER_INVENTORY_WRITTEN'
