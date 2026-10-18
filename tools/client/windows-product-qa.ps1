# LOGH Run/Report entry. Observation alone is never product PASS.
[CmdletBinding()]
param(
    [ValidateSet('Report', 'Run')][string]$Mode = 'Report',
    [Parameter(Mandatory=$true)][string]$Python,
    [Parameter(Mandatory=$true)][string]$Repo,
    [Parameter(Mandatory=$true)][ValidatePattern('^[0-9a-f]{40}$')][string]$Head,
    [Parameter(Mandatory=$true)][string]$Out,
    [string]$RuntimeReport,
    [string]$RuntimeReportSha256,
    [string]$Manifest,
    [ValidatePattern('^[0-9a-f]{64}$')][string]$ManifestSha256,
    [string]$AppRoot,
    [string]$TempParent,
    [ValidateRange(1024,65535)][int]$Port = 20000,
    [ValidateRange(1024,65535)][int]$UpstreamPort,
    [ValidateRange(1,25)][int]$ObserveSeconds = 5,
    [ValidateRange(2,30)][int]$Timeout = 30
)
$ErrorActionPreference = 'Stop'
try {
    if ($env:OS -ne 'Windows_NT') { throw 'Native Windows report entry required' }
    if (Test-Path -LiteralPath $Out) { throw 'Existing output refused' }
    # Empty private cache prevents timestamp-matching pyc execution. -I ignores
    # user Python environment; -B avoids writes into the exact-head checkout.
    $cache = Join-Path ([IO.Path]::GetDirectoryName($Out)) ('report-cache-' + [Guid]::NewGuid().ToString('N'))
    $null = New-Item -ItemType Directory -Path $cache -ErrorAction Stop
    $arguments = @('-I', '-S', '-B', '-X', ('pycache_prefix=' + $cache),
        (Join-Path $Repo 'tools/client/qa_product_report.py'),
        '--repo', $Repo, '--head', $Head, '--out', $Out)
    if ($Mode -eq 'Run') {
        if ($RuntimeReport -or $RuntimeReportSha256) { throw 'Run cannot import historical evidence' }
        if (-not $Manifest -or -not $ManifestSha256 -or -not $AppRoot -or -not $TempParent -or
            $ObserveSeconds -ge $Timeout) { throw 'Run requires manifest/app/temp and observe < timeout' }
        # The adapter reuses the non-launching checkout inspector before/after.
        $checkArguments = $arguments + @('--check-only')
        $beforeRaw = & $Python @checkArguments
        if ($LASTEXITCODE -ne 3) { throw 'Pre-run checkout inspection failed' }
        $before = ($beforeRaw -join "`n") | ConvertFrom-Json -ErrorAction Stop
        if ($before.head -ne $Head -or $before.dirty -ne '') { throw 'Exact clean checkout required' }
        $run = Join-Path ([IO.Path]::GetDirectoryName($Out)) ('run-' + [Guid]::NewGuid().ToString('N'))
        if (Test-Path -LiteralPath $run) { throw 'Existing run directory refused' }
        $null = New-Item -ItemType Directory -Path $run -ErrorAction Stop
        $runnerCache = Join-Path $run 'runner-cache'
        $null = New-Item -ItemType Directory -Path $runnerCache -ErrorAction Stop
        $RuntimeReport = Join-Path $run 'runtime.json'
        $cancel = Join-Path $run 'cancel'
        $runnerArguments = @('-I', '-S', '-B', '-X', ('pycache_prefix=' + $runnerCache),
            (Join-Path $Repo 'tools/client/qa_isolated_runner.py'), '--product-logh',
            '--repo', $Repo, '--temp-parent', $TempParent, '--manifest', $Manifest,
            '--manifest-sha256', $ManifestSha256, '--report', $RuntimeReport,
            '--executable', (Join-Path $AppRoot 'exe/G7MTClient.exe'), '--app-root', $AppRoot,
            '--port', [string]$Port, '--observe-seconds', [string]$ObserveSeconds,
            '--timeout', [string]$Timeout, '--cancel-file', $cancel)
        if ($UpstreamPort) { $runnerArguments += @('--upstream-port', [string]$UpstreamPort) }
        # Dedicated runspace directly invokes Python; no Start-Process or shell
        # command interpolation. Both native streams go only to owned files.
        $pipeline = [PowerShell]::Create()
        $null = $pipeline.AddScript({ param($python, $argv, $stdout, $stderr)
            & $python @argv 1> $stdout 2> $stderr
            $LASTEXITCODE
        }).AddArgument($Python).AddArgument($runnerArguments).AddArgument(
            (Join-Path $run 'runner.stdout')).AddArgument((Join-Path $run 'runner.stderr'))
        $pending = $pipeline.BeginInvoke()
        if (-not $pending.AsyncWaitHandle.WaitOne(($Timeout + 40) * 1000)) {
            # Ask the existing supervisor to clean its Jobs. Never claim that
            # cancellation/runner stop proves target cleanup.
            [IO.File]::WriteAllText($cancel, '')
            $null = $pipeline.BeginStop($null, $null)
            throw 'Runner wait exceeded; cleanup unverified (evidence retained)'
        }
        $runnerResult = $pipeline.EndInvoke($pending)
        $pipeline.Dispose()
        if ($runnerResult.Count -ne 1 -or [int]$runnerResult[0] -notin @(2,3)) {
            throw 'Unexpected runner result; product mode cannot return PASS'
        }
        $RuntimeReportSha256 = (Get-FileHash -LiteralPath $RuntimeReport -Algorithm SHA256).Hash.ToLowerInvariant()
        $afterRaw = & $Python @checkArguments
        if ($LASTEXITCODE -ne 3) { throw 'Post-run checkout inspection failed' }
        $after = ($afterRaw -join "`n") | ConvertFrom-Json -ErrorAction Stop
        if ($after.head -ne $Head -or $after.dirty -ne '') { throw 'Post-run checkout changed' }
        $binding = @{ before=$before; after=$after; repo=$Repo; run_directory=$run;
            manifest=$Manifest; runner_exit=[int]$runnerResult[0]; wait_exceeded=$false }
        $binding | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $run 'invocation.json') -Encoding UTF8
        $arguments[$arguments.Length - 1] = Join-Path $run 'product-report.json'
        $arguments += @('--same-run', $run, '--manifest-sha256', $ManifestSha256)
    }
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
    if ($Mode -eq 'Run') {
        # Publish the same adapter document to the caller's new output too.
        $stream = [IO.File]::Open($Out, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
        try {
            $bytes = [Text.Encoding]::UTF8.GetBytes(($raw -join "`n"))
            $stream.Write($bytes, 0, $bytes.Length)
        } finally { $stream.Dispose() }
    }
    $parsed | ConvertTo-Json -Depth 12 -Compress
    exit $code
} catch {
    # One JSON document on failure as well. Never manufacture safety flags.
    $report = @{
        project='logh'; head_sha=$Head; productQA='NOT_RUN'; product_pass=$false
        windows_qa_receipt=$null; runtimeExecutedByAdapter=$false
        runtimeHeadProvenanceVerified=$false
        isolationVerified=$false; silentVerified=$false; cleanupVerified=$false
        platforms=@{windows=@{status='FAIL'; deviceId=$env:COMPUTERNAME; checks=@(
            @{name='original-client API behavior';status='NOT_RUN'},
            @{name='original-client audio coverage';status='NOT_RUN'},
            @{name='original-client rendering';status='NOT_RUN'},
            @{name='original-client protocol interoperability';status='NOT_RUN'})}}
        blockers=@($_.Exception.Message)
    }
    $report | ConvertTo-Json -Depth 8 -Compress
    exit 2
}
