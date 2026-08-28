[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$OutputPath)
$ErrorActionPreference='Stop'

Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text;
public static class FreshRunWindows {
  public delegate bool EnumWindowsProc(IntPtr hwnd, IntPtr parameter);
  [StructLayout(LayoutKind.Sequential)] public struct Rect { public int Left; public int Top; public int Right; public int Bottom; }
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc callback, IntPtr parameter);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint processId);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hwnd);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hwnd, out Rect rect);
  [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr hwnd, out Rect rect);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr hwnd, StringBuilder text, int count);
  [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetClassName(IntPtr hwnd, StringBuilder text, int count);
}
'@

function Format-HexHandle([IntPtr]$value){'0x{0:X16}' -f [uint64]$value.ToInt64()}
$roles=[ordered]@{G7MTClient='CLIENT';x32dbg='DEBUGGER'}
$processRows=[Collections.Generic.List[object]]::new()
$targetPids=[Collections.Generic.HashSet[uint32]]::new()
foreach($name in $roles.Keys){
  foreach($process in @(Get-Process -Name $name -ErrorAction SilentlyContinue)){
    [void]$targetPids.Add([uint32]$process.Id)
    $path=$null;$sha256=$null;$startTimeUtc=$null;$moduleBase=$null;$moduleSize=0
    try{$path=$process.Path}catch{}
    try{$startTimeUtc=$process.StartTime.ToUniversalTime().ToString('o')}catch{}
    if($path-and(Test-Path -LiteralPath $path)){$sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash}
    try{$moduleBase='0x{0:X8}' -f [uint32]$process.MainModule.BaseAddress.ToInt64();$moduleSize=[int64]$process.MainModule.ModuleMemorySize}catch{}
    $processRows.Add([ordered]@{
      role=$roles[$name];name=$process.ProcessName;pid=$process.Id;startTimeUtc=$startTimeUtc;executablePath=$path;executableSha256=$sha256;responding=$process.Responding;moduleBase=$moduleBase;moduleSize=$moduleSize;mainWindowHandle=(Format-HexHandle $process.MainWindowHandle)
    })
  }
}

$foreground=[FreshRunWindows]::GetForegroundWindow()
$windowRows=[Collections.Generic.List[object]]::new()
$callback=[FreshRunWindows+EnumWindowsProc]{
  param([IntPtr]$hwnd,[IntPtr]$parameter)
  [uint32]$owner=0;[FreshRunWindows]::GetWindowThreadProcessId($hwnd,[ref]$owner)|Out-Null
  if($targetPids.Contains($owner)){
    $windowRect=[FreshRunWindows+Rect]::new();$clientRect=[FreshRunWindows+Rect]::new();[FreshRunWindows]::GetWindowRect($hwnd,[ref]$windowRect)|Out-Null;[FreshRunWindows]::GetClientRect($hwnd,[ref]$clientRect)|Out-Null
    $title=[Text.StringBuilder]::new(512);$class=[Text.StringBuilder]::new(512);[FreshRunWindows]::GetWindowText($hwnd,$title,$title.Capacity)|Out-Null;[FreshRunWindows]::GetClassName($hwnd,$class,$class.Capacity)|Out-Null
    $windowRows.Add([ordered]@{hwnd=(Format-HexHandle $hwnd);ownerPid=$owner;foreground=($hwnd-eq$foreground);visible=[FreshRunWindows]::IsWindowVisible($hwnd);title=$title.ToString();class=$class.ToString();windowRect=[ordered]@{left=$windowRect.Left;top=$windowRect.Top;right=$windowRect.Right;bottom=$windowRect.Bottom};clientRect=[ordered]@{left=$clientRect.Left;top=$clientRect.Top;right=$clientRect.Right;bottom=$clientRect.Bottom}})
  }
  return $true
}
[FreshRunWindows]::EnumWindows($callback,[IntPtr]::Zero)|Out-Null

$client=@($processRows|Where-Object{$_.role-eq'CLIENT'})|Select-Object -First 1
$connections=[Collections.Generic.List[object]]::new()
if($null-ne$client){
  foreach($line in @(& "$env:SystemRoot\System32\netstat.exe" -ano -p tcp)){
    if($line-match'^\s*(TCP)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\d+)\s*$'){
      $pidValue=[int]$matches[5]
      if($pidValue-eq[int]$client.pid){$connections.Add([ordered]@{protocol=$matches[1];localEndpoint=$matches[2];remoteEndpoint=$matches[3];state=$matches[4];pid=$pidValue})}
    }
  }
}

$receipt=[ordered]@{
  schemaVersion=1
  provenance='LIVE_READONLY'
  observedAtUtc=[DateTime]::UtcNow.ToString('o')
  desktop=[ordered]@{guestUser=[Environment]::UserName;computerName=[Environment]::MachineName;sessionId=[Diagnostics.Process]::GetCurrentProcess().SessionId;interactive=[Environment]::UserInteractive;foregroundHwnd=(Format-HexHandle $foreground)}
  processes=$processRows
  windows=$windowRows
  network=[ordered]@{targetPid=if($null-ne$client){[int]$client.pid}else{0};serverPort=47900;connections=$connections}
  operations=[ordered]@{guestOperations=1;processMemoryReads=0;processMemoryWrites=0;debuggerCommands=0;breakpointsInstalled=0;gameInputs=0;automaticInputs=0;captures=0;permitIssued=$false}
}
$parent=Split-Path -Parent $OutputPath;if($parent-and-not(Test-Path -LiteralPath $parent)){New-Item -ItemType Directory -Path $parent|Out-Null}
[IO.File]::WriteAllText($OutputPath,(($receipt|ConvertTo-Json -Depth 12)+[Environment]::NewLine),[Text.UTF8Encoding]::new($false))
Write-Output 'FRESH_RUN_IDENTITY_WRITTEN'
