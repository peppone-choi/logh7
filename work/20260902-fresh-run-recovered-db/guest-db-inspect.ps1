[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$RunId,
    [Parameter(Mandatory=$true)][string]$ReceiptPath,
    [int]$Port = 55432
)
# Read-only inspection of the RUN'S OWN PostgreSQL copy (never the sealed source): temporarily switches the
# single localhost HBA rule to trust, reloads, runs SELECTs, restores the original HBA and reloads again.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = "C:\Users\logh7-oracle\AppData\Local\Temp\logh7-l1\$RunId"
$data = Join-Path $root 'postgres-data'; $hba = Join-Path $data 'pg_hba.conf'; $backup = Join-Path $root 'pg_hba.original.conf'
$prep = Get-Content -LiteralPath (Join-Path $root 'fresh-run-prep.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if ($prep.runId -cne $RunId -or [string]$prep.database.dataDirectory -cne $data) { throw 'PREP_MISMATCH' }
$bin = Split-Path -Parent ((Get-ChildItem -LiteralPath (Join-Path $root 'postgresql') -Recurse -Filter 'psql.exe' | Select-Object -First 1).FullName)
$psql = Join-Path $bin 'psql.exe'; $pgCtl = Join-Path $bin 'pg_ctl.exe'
$backupHash = (Get-FileHash -LiteralPath $backup -Algorithm SHA256).Hash
$queries = [ordered]@{
    identity = "select current_database()||'|'||current_user||'|'||pg_is_in_recovery()||'|'||current_setting('port');"
    gridUnit = "select row_to_json(t) from (select * from original_grid_unit) t;"
    moveCommands = "select json_agg(t) from (select * from original_grid_move_command order by 1) t;"
    moveCommandCount = "select count(*) from original_grid_move_command;"
    domainEventCount = "select count(*) from domain_event;"
    domainEventLatest = "select json_agg(t) from (select event_id, aggregate_type, aggregate_id, event_type, payload, authority_version, created_at from domain_event order by event_id desc limit 6) t;"
    accountVersion = "select authority_version from account;"
    characterVersion = "select character_id||'|'||authority_version||'|'||rank from character;"
    mail = "select json_agg(t) from (select mail_id, sender_character_id, recipient_character_id, is_read, read_at, authority_version from original_mail_message order by mail_id) t;"
    orderReply = "select json_agg(t) from (select character_id, card_id, reply_value, authority_version from original_order_suggest_reply order by card_id) t;"
    lotteryEntry = "select json_agg(t) from (select entry_id, status, authority_version from original_character_lottery_entry order by entry_id) t;"
    characters = "select json_agg(t) from (select character_id, slot, rank, authority_version from character order by slot) t;"
    cardAppointment = "select json_agg(t) from (select character_id, card_id, target_character_id, authority_version, created_at from original_card_appointment order by authority_version) t;"
    characterCard = "select json_agg(t) from (select character_id, card_id, appointed_by_character_id, authority_version, updated_at from original_character_card order by character_id) t;"
}
$results = [ordered]@{}; $status = 'PENDING'; $err = $null; $modified = $false
try {
    $text = [IO.File]::ReadAllText($hba, [Text.Encoding]::UTF8); $pattern = '(?m)^(\s*host\s+all\s+all\s+127\.0\.0\.1/32\s+)scram-sha-256\s*$'
    if ([Text.RegularExpressions.Regex]::Matches($text, $pattern).Count -ne 1) { throw 'HBA_PATTERN_INVALID' }
    [IO.File]::WriteAllText($hba, [Text.RegularExpressions.Regex]::Replace($text, $pattern, '$1trust'), [Text.UTF8Encoding]::new($false)); $modified = $true
    & $pgCtl -D $data -s reload; if ($LASTEXITCODE -ne 0) { throw 'HBA_RELOAD_FAILED' }
    Start-Sleep -Milliseconds 500
    foreach ($k in $queries.Keys) {
        $so = Join-Path $root "dbq-$k.out"; $se = Join-Path $root "dbq-$k.err"
        & $psql -X -qAt -v ON_ERROR_STOP=1 -h 127.0.0.1 -p $Port -U logh7 -d logh7 -c $queries[$k] 1> $so 2> $se
        $results[$k] = [ordered]@{ exitCode = $LASTEXITCODE; stdout = ([IO.File]::ReadAllText($so, [Text.Encoding]::UTF8)).Trim(); stderr = ([IO.File]::ReadAllText($se, [Text.Encoding]::UTF8)).Trim() }
    }
    $status = 'DB_INSPECTED'
} catch { $err = $_.Exception.Message; $status = 'DB_INSPECT_FAILED' }
finally {
    if ($modified) { Copy-Item -LiteralPath $backup -Destination $hba -Force; & $pgCtl -D $data -s reload | Out-Null }
    $restored = ((Get-FileHash -LiteralPath $hba -Algorithm SHA256).Hash -ceq $backupHash)
    $receipt = [ordered]@{ status = $status; error = $err; runId = $RunId; inspectedAtUtc = [datetime]::UtcNow.ToString('o'); hbaRestored = $restored; hbaSha256 = $backupHash; results = $results; operations = [ordered]@{ sqlWrites = 0; gameInputs = 0 } }
    [IO.File]::WriteAllText($ReceiptPath, (($receipt | ConvertTo-Json -Depth 8) -replace "`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
}
if ($status -ne 'DB_INSPECTED') { exit 1 }
exit 0
