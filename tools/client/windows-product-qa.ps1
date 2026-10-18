# Non-launching LOGH report entry. Invoke from a task-owned parameter-binding
# script passed to hermes-windows qa. Never use this as a product PASS source.
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$Python,
    [Parameter(Mandatory=$true)][string]$Repo,
    [Parameter(Mandatory=$true)][ValidatePattern('^[0-9a-f]{40}$')][string]$Head,
    [Parameter(Mandatory=$true)][string]$Out,
    [string]$RuntimeReport,
    [string]$RuntimeReportSha256
)
$ErrorActionPreference = 'Stop'
try {
    if ($env:OS -ne 'Windows_NT') { throw 'Native Windows report entry required' }
    # Empty private cache prevents timestamp-matching pyc execution. -I ignores
    # user Python environment; -B avoids writes into the exact-head checkout.
    $cache = Join-Path ([IO.Path]::GetDirectoryName($Out)) ('report-cache-' + [Guid]::NewGuid().ToString('N'))
    $null = New-Item -ItemType Directory -Path $cache -ErrorAction Stop
    $arguments = @('-I', '-S', '-B', '-X', ('pycache_prefix=' + $cache),
        (Join-Path $Repo 'tools/client/qa_product_report.py'),
        '--repo', $Repo, '--head', $Head, '--out', $Out)
    if ($RuntimeReport -or $RuntimeReportSha256) {
        if (-not $RuntimeReport -or -not $RuntimeReportSha256) { throw 'Report and external SHA256 required together' }
        $arguments += @('--runtime-report', $RuntimeReport, '--runtime-report-sha256', $RuntimeReportSha256)
    }
    $raw = & $Python @arguments
    $code = $LASTEXITCODE
    if ($code -notin @(2, 3)) {
        throw ('Unexpected adapter exit code: ' + $code)
    }
    $parsed = ($raw -join "`n") | ConvertFrom-Json -ErrorAction Stop
    if ($parsed.platforms.windows.status -notin @('NOT_RUN', 'FAIL') -or
        $parsed.product_pass -ne $false) { throw 'Refusing unexpected product verdict' }
    $parsed | ConvertTo-Json -Depth 12 -Compress
    exit $code
} catch {
    # One JSON document on failure as well. Never manufacture safety flags.
    $report = @{
        project='logh'; head_sha=$Head; productQA='NOT_RUN'; product_pass=$false
        windows_qa_receipt=$null; runtimeExecutedByAdapter=$false
        isolationVerified=$false; silentVerified=$false; cleanupVerified=$false
        platforms=@{windows=@{status='FAIL'; deviceId=$env:COMPUTERNAME; checks=@()}}
        blockers=@($_.Exception.Message)
    }
    $report | ConvertTo-Json -Depth 8 -Compress
    exit 2
}
