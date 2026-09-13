[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$OutputPath)
$ErrorActionPreference='Stop'
$roots=@('C:\Windows\Temp','C:\ProgramData\VMware\VMware Tools','C:\Users\logh7-oracle\AppData\Local\Temp')
$rows=@()
foreach($root in $roots){
  if(-not(Test-Path -LiteralPath $root)){continue}
  foreach($file in @(Get-ChildItem -LiteralPath $root -File -ErrorAction SilentlyContinue|Where-Object{$_.Name-match'(?i)^vmware-(vmusr|vmsvc|toolboxcmd).*\.log$|^vmtoolsd.*\.log$'})){
    $tail=@()
    try{
      $tail=@(Get-Content -LiteralPath $file.FullName -Tail 300 -Encoding UTF8 -ErrorAction Stop|Where-Object{$_-match'(?i)vix|guest|interactive|run|error|fail|warn|session|desktop' -and$_-notmatch'(?i)password|credential|token|secret|saml'}|Select-Object -Last 100)
    }catch{}
    $sha256=$null;$hashStatus='PASS'
    try{$sha256=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256 -ErrorAction Stop).Hash}catch{$hashStatus='HASH_UNAVAILABLE_FILE_LOCKED_OR_DENIED'}
    $rows+=,[ordered]@{path=$file.FullName;length=$file.Length;lastWriteTimeUtc=$file.LastWriteTimeUtc.ToString('o');sha256=$sha256;hashStatus=$hashStatus;filteredTail=$tail}
  }
}
$receipt=[ordered]@{schemaVersion=1;provenance='LIVE_READONLY_VMWARE_TOOLS_LOG_INVENTORY';observedAtUtc=[DateTime]::UtcNow.ToString('o');logs=$rows;operations=[ordered]@{guestObservationHelpers=1;loggingConfigurationChanges=0;serviceChanges=0;processesCreated=0;processMemoryReads=0;processMemoryWrites=0;foregroundChanges=0;gameInputs=0;automaticInputs=0;permitIssued=$false;vmLifecycleChanges=0}}
$parent=Split-Path -Parent $OutputPath;if($parent-and-not(Test-Path -LiteralPath $parent)){New-Item -ItemType Directory -Path $parent|Out-Null}
[IO.File]::WriteAllText($OutputPath,(($receipt|ConvertTo-Json -Depth 10)+"`n"),[Text.UTF8Encoding]::new($false))
'VMWARE_TOOLS_LOG_INVENTORY_WRITTEN'
