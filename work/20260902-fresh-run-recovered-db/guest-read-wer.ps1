[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$RunId,[Parameter(Mandatory=$true)][string]$ReceiptPath,[string]$Pattern='AppCrash_G7MTClient*')
# READ-ONLY: extract crash signature fields from WER Report.wer files (no secrets; values only).
$ErrorActionPreference='SilentlyContinue'
$rows=@()
foreach ($root in @("$env:ProgramData\Microsoft\Windows\WER\ReportQueue","$env:ProgramData\Microsoft\Windows\WER\ReportArchive")) {
  foreach ($d in (Get-ChildItem -Path $root -Directory -Filter $Pattern | Sort-Object LastWriteTime -Descending | Select-Object -First 6)) {
    $files=@(Get-ChildItem -LiteralPath $d.FullName -File | ForEach-Object { "$($_.Name) $([int]($_.Length/1KB))KB $($_.LastWriteTime.ToUniversalTime().ToString('o'))" })
    $wer=Join-Path $d.FullName 'Report.wer'; $fields=[ordered]@{}
    if (Test-Path -LiteralPath $wer) {
      foreach ($l in (Get-Content -LiteralPath $wer -Encoding Unicode)) {
        if ($l -match '^(EventTime|EventType|Sig\[\d+\]\.(Name|Value)|DynamicSig\[1\]\.Value|AppPath|FriendlyEventName|ReportStatus|UI\[2\]|OriginalFilename)=(.*)$' -or $l -match '^(Sig\[\d+\]\.Name|Sig\[\d+\]\.Value)=(.*)$') { $kv=$l.Split('=',2); $fields[$kv[0]]=$kv[1] }
      }
    }
    $rows += [ordered]@{ dir=$d.FullName; dirWriteUtc=$d.LastWriteTime.ToUniversalTime().ToString('o'); files=$files; fields=$fields }
  }
}
$r=[ordered]@{ status='WER_READ'; runId=$RunId; reports=$rows; operations=[ordered]@{ writes=0 } }
[IO.File]::WriteAllText($ReceiptPath, (($r | ConvertTo-Json -Depth 6) -replace "`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
exit 0