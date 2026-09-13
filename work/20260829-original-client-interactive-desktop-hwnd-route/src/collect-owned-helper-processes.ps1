[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$OutputPath)
$ErrorActionPreference='Stop'
$rows=@()
foreach($process in @(Get-CimInstance Win32_Process -Filter "Name='powershell.exe'")){
  $rows+=,[ordered]@{pid=[int]$process.ProcessId;parentPid=[int]$process.ParentProcessId;sessionId=[int]$process.SessionId;creationDate=if($process.CreationDate){$process.CreationDate.ToUniversalTime().ToString('o')}else{$null};executablePath=$process.ExecutablePath;commandLine=$process.CommandLine}
}
$receipt=[ordered]@{schemaVersion=1;provenance='LIVE_READONLY_HELPER_PROCESS_INVENTORY';observedAtUtc=[DateTime]::UtcNow.ToString('o');powershellProcesses=$rows;operations=[ordered]@{guestObservationHelpers=1;processesKilled=0;processMemoryReads=0;processMemoryWrites=0;foregroundChanges=0;gameInputs=0;automaticInputs=0;permitIssued=$false;vmLifecycleChanges=0}}
$parent=Split-Path -Parent $OutputPath;if($parent-and-not(Test-Path -LiteralPath $parent)){New-Item -ItemType Directory -Path $parent|Out-Null}
[IO.File]::WriteAllText($OutputPath,(($receipt|ConvertTo-Json -Depth 8)+"`n"),[Text.UTF8Encoding]::new($false))
'OWNED_HELPER_PROCESS_INVENTORY_WRITTEN'
