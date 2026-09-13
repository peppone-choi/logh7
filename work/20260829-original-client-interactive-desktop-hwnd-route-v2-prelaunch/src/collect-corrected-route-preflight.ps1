param([Parameter(Mandatory=$true)][string]$RunId,[Parameter(Mandatory=$true)][string]$OutputPath)
$ErrorActionPreference='Stop'

function Write-CanonicalJson([object]$Value,[string]$Path){$json=($Value|ConvertTo-Json -Depth 14)-replace"`r`n","`n";[IO.File]::WriteAllText($Path,$json+"`n",[Text.UTF8Encoding]::new($false))}

Add-Type -TypeDefinition @'
using System.Runtime.InteropServices;
public static class CorrectedRouteNative {
  [DllImport("kernel32.dll")] public static extern uint WTSGetActiveConsoleSessionId();
}
'@

function Get-OwnerIdentity($CimProcess){
 $owner=Invoke-CimMethod -InputObject $CimProcess -MethodName GetOwner
 $sid=Invoke-CimMethod -InputObject $CimProcess -MethodName GetOwnerSid
 if($owner.ReturnValue-ne0-or$sid.ReturnValue-ne0){return [ordered]@{status='FAILED';name=$null;sid=$null}}
 $name=if([string]::IsNullOrWhiteSpace([string]$owner.Domain)){[string]$owner.User}else{"$($owner.Domain)\$($owner.User)"}
 return [ordered]@{status='SUCCESS';name=$name;sid=[string]$sid.Sid}
}

function Get-ProcessReceipt($CimProcess,[string]$Role){
 $path=[string]$CimProcess.ExecutablePath
 $owner=Get-OwnerIdentity $CimProcess
 $managed=Get-Process -Id ([int]$CimProcess.ProcessId)
 $created=if($CimProcess.CreationDate-is[datetime]){[datetime]$CimProcess.CreationDate}else{[Management.ManagementDateTimeConverter]::ToDateTime([string]$CimProcess.CreationDate)}
 [ordered]@{
  role=$Role;name=[IO.Path]::GetFileNameWithoutExtension([string]$CimProcess.Name);pid=[int]$CimProcess.ProcessId;sessionId=[int]$CimProcess.SessionId
  startTimeUtc=$created.ToUniversalTime().ToString('o')
  path=$path;sha256=if(Test-Path -LiteralPath $path){(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash}else{$null}
  owner=$owner.name;ownerSid=$owner.sid;ownerLookupStatus=$owner.status
  moduleBase=('0x{0:X16}'-f$managed.MainModule.BaseAddress.ToInt64());moduleSize=[int64]$managed.MainModule.ModuleMemorySize
 }
}

$active=[int][CorrectedRouteNative]::WTSGetActiveConsoleSessionId()
$self=Get-Process -Id $PID
$all=@(Get-CimInstance Win32_Process)
$processes=@()
foreach($p in @($all|Where-Object{$_.Name-eq'G7MTClient.exe'})){$processes+=Get-ProcessReceipt $p 'CLIENT'}
foreach($p in @($all|Where-Object{$_.Name-eq'x32dbg.exe'})){$processes+=Get-ProcessReceipt $p 'DEBUGGER'}
foreach($p in @($all|Where-Object{$_.Name-eq'vmtoolsd.exe'-and[int]$_.SessionId-eq$active})){$processes+=Get-ProcessReceipt $p 'INTERACTIVE_AGENT'}

$program='C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe'
$receipt=[ordered]@{
 schemaVersion=1;runId=$RunId;provenance='LIVE_READONLY_CORRECTED_ROUTE_PREFLIGHT';observedAtUtc=[datetime]::UtcNow.ToString('o')
 helper=[ordered]@{pid=$PID;sessionId=$self.SessionId;userName=[Security.Principal.WindowsIdentity]::GetCurrent().Name;userSid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value}
 activeConsoleSessionId=$active
 absoluteProgram=[ordered]@{path=$program;exists=(Test-Path -LiteralPath $program);sha256=if(Test-Path -LiteralPath $program){(Get-FileHash -LiteralPath $program -Algorithm SHA256).Hash}else{$null};length=if(Test-Path -LiteralPath $program){(Get-Item -LiteralPath $program).Length}else{0};observedAtUtc=[datetime]::UtcNow.ToString('o')}
 processes=@($processes)
 operations=[ordered]@{helperProcessesCreated=1;guestSourceCopies=1;guestFileWrites=1;processMemoryReads=0;processMemoryWrites=0;foregroundChanges=0;debuggerAttach=0;debuggerCommands=0;breakpointsInstalled=0;captures=0;gameInputs=0;automaticInputs=0;permitIssued=$false;vmLifecycleChanges=0;serverChanges=0;protocolChanges=0;databaseChanges=0}
}
Write-CanonicalJson $receipt $OutputPath
$receipt|ConvertTo-Json -Depth 14
