[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$OutputPath)
$ErrorActionPreference='Stop'
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Text;
public static class SessionRouteNative {
  [DllImport("kernel32.dll")] public static extern uint WTSGetActiveConsoleSessionId();
  [DllImport("user32.dll")] public static extern IntPtr GetProcessWindowStation();
  [DllImport("user32.dll")] public static extern IntPtr GetThreadDesktop(uint threadId);
  [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
  [DllImport("user32.dll", SetLastError=true)] public static extern bool GetUserObjectInformation(IntPtr handle, int index, StringBuilder info, uint length, out uint needed);
  public static string ObjectName(IntPtr handle) {
    uint needed=0; GetUserObjectInformation(handle,2,null,0,out needed);
    if(needed==0) return null;
    var value=new StringBuilder((int)(needed/2)+2);
    return GetUserObjectInformation(handle,2,value,(uint)(value.Capacity*2),out needed) ? value.ToString() : null;
  }
}
'@
function Get-ProcessRow([Diagnostics.Process]$process) {
  $path=$null;$start=$null;$user=$null;$mainHandle=$null
  try{$path=$process.Path}catch{}
  try{$start=$process.StartTime.ToUniversalTime().ToString('o')}catch{}
  try{$user=$process.UserName}catch{}
  try{$mainHandle='0x{0:X16}' -f [uint64]$process.MainWindowHandle.ToInt64()}catch{}
  [ordered]@{name=$process.ProcessName;pid=$process.Id;sessionId=$process.SessionId;userName=$user;startTimeUtc=$start;path=$path;mainWindowHandle=$mainHandle;responding=$process.Responding}
}
$current=[Diagnostics.Process]::GetCurrentProcess()
$targets=@()
foreach($name in @('explorer','G7MTClient','x32dbg','dwm','winlogon','LogonUI')){foreach($p in @(Get-Process -Name $name -ErrorAction SilentlyContinue)){$targets+=,(Get-ProcessRow $p)}}
$service=Get-Service -Name VMTools -ErrorAction SilentlyContinue
$toolsPath=$null;$toolsVersion=$null
try{$toolsPath=(Get-CimInstance Win32_Service -Filter "Name='VMTools'").PathName}catch{}
try{$toolsVersion=(Get-Item 'C:\Program Files\VMware\VMware Tools\vmtoolsd.exe').VersionInfo.FileVersion}catch{}
$receipt=[ordered]@{
  schemaVersion=1
  provenance='LIVE_READONLY_SESSION_DIAGNOSTIC'
  observedAtUtc=[DateTime]::UtcNow.ToString('o')
  currentProcess=[ordered]@{pid=$current.Id;sessionId=$current.SessionId;userName=[Environment]::UserName;userInteractive=[Environment]::UserInteractive;windowStation=[SessionRouteNative]::ObjectName([SessionRouteNative]::GetProcessWindowStation());desktop=[SessionRouteNative]::ObjectName([SessionRouteNative]::GetThreadDesktop([SessionRouteNative]::GetCurrentThreadId()))}
  activeConsoleSessionId=[uint32][SessionRouteNative]::WTSGetActiveConsoleSessionId()
  computerSystemUserName=(Get-CimInstance Win32_ComputerSystem).UserName
  quser=@(& "$env:SystemRoot\System32\quser.exe" 2>&1|ForEach-Object{[string]$_})
  qwinsta=@(& "$env:SystemRoot\System32\qwinsta.exe" 2>&1|ForEach-Object{[string]$_})
  processes=$targets
  vmwareTools=[ordered]@{serviceStatus=if($service){[string]$service.Status}else{$null};serviceStartType=if($service){[string]$service.StartType}else{$null};path=$toolsPath;fileVersion=$toolsVersion}
  operations=[ordered]@{guestObservationHelpers=1;processMemoryReads=0;processMemoryWrites=0;foregroundChanges=0;debuggerAttach=0;debuggerCommands=0;breakpointsInstalled=0;captures=0;gameInputs=0;automaticInputs=0;permitIssued=$false;vmLifecycleChanges=0;serverChanges=0;protocolChanges=0;databaseChanges=0}
}
$parent=Split-Path -Parent $OutputPath;if($parent-and-not(Test-Path -LiteralPath $parent)){New-Item -ItemType Directory -Path $parent|Out-Null}
[IO.File]::WriteAllText($OutputPath,(($receipt|ConvertTo-Json -Depth 12)+"`n"),[Text.UTF8Encoding]::new($false))
'SESSION_ROUTE_DIAGNOSTIC_WRITTEN'
