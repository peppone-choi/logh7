param(
    [string]$ReceiptPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$env:PYTHONDONTWRITEBYTECODE = "1"

$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\..\.."))
Set-Location -LiteralPath $projectRoot
$defaultReceiptPath = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "foundation-verification.json"))
if ([string]::IsNullOrWhiteSpace($ReceiptPath)) {
    $ReceiptPath = $defaultReceiptPath
}
$ReceiptPath = [IO.Path]::GetFullPath($ReceiptPath)
$python = (Get-Command python -ErrorAction Stop).Source
$utf8NoBom = [Text.UTF8Encoding]::new($false)
$commandRecords = [Collections.Generic.List[object]]::new()

function Get-Sha256File([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToUpperInvariant()
}

function Get-Sha256Text([string]$Text) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($Text)
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes))
}

function Assert-Equal($Expected, $Actual, [string]$Label) {
    if ($Expected -ne $Actual) {
        throw "$Label mismatch: expected=$Expected actual=$Actual"
    }
}

function Assert-NoReparse([string]$Path, [string]$Label) {
    $item = Get-Item -LiteralPath $Path -Force
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "$Label is a symlink, junction, or reparse point: $Path"
    }
}

function Assert-CanonicalText([string]$Path) {
    $bytes = [IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -eq 0 -or $bytes[$bytes.Length - 1] -ne 10) {
        throw "canonical text must end in LF: $Path"
    }
    if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
        throw "canonical text must not contain a UTF-8 BOM: $Path"
    }
    if ([Array]::IndexOf($bytes, [byte]13) -ge 0) {
        throw "canonical text must use LF, not CRLF: $Path"
    }
}

function Invoke-RecordedProcess {
    param(
        [string]$FilePath,
        [string[]]$Arguments,
        [int[]]$ExpectedExitCodes = @(0),
        [string]$Label
    )
    $start = [DateTimeOffset]::UtcNow
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $FilePath
    $info.WorkingDirectory = $projectRoot
    $info.UseShellExecute = $false
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.Environment["PYTHONDONTWRITEBYTECODE"] = "1"
    foreach ($argument in $Arguments) {
        [void]$info.ArgumentList.Add($argument)
    }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $info
    if (-not $process.Start()) {
        throw "failed to start command: $Label"
    }
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    $stdout = $stdoutTask.GetAwaiter().GetResult()
    $stderr = $stderrTask.GetAwaiter().GetResult()
    $duration = [DateTimeOffset]::UtcNow - $start
    $record = [ordered]@{
        label = $Label
        argv = @($FilePath) + @($Arguments)
        exitCode = $process.ExitCode
        expectedExitCodes = @($ExpectedExitCodes)
        stdoutSha256 = Get-Sha256Text $stdout
        stderrSha256 = Get-Sha256Text $stderr
        durationMs = [Math]::Round($duration.TotalMilliseconds)
    }
    $commandRecords.Add([pscustomobject]$record)
    if (-not [string]::IsNullOrEmpty($stdout)) { Write-Host $stdout.TrimEnd() }
    if (-not [string]::IsNullOrEmpty($stderr)) { Write-Host $stderr.TrimEnd() }
    if ($ExpectedExitCodes -notcontains $process.ExitCode) {
        throw "$Label returned $($process.ExitCode); expected one of $($ExpectedExitCodes -join ',')"
    }
    return [pscustomobject]@{ exitCode = $process.ExitCode; stdout = $stdout; stderr = $stderr }
}

function Invoke-Python {
    param([string[]]$Arguments, [int[]]$ExpectedExitCodes = @(0), [string]$Label)
    return Invoke-RecordedProcess -FilePath $python -Arguments $Arguments -ExpectedExitCodes $ExpectedExitCodes -Label $Label
}

$receiptPolicy = Invoke-Python -Label "receipt-target-policy-preflight" -Arguments @(
    "-B", "-m", "tools.exhaustive_trace.foundation_verification",
    "--project-root", $projectRoot,
    "--default-receipt", $defaultReceiptPath,
    "--receipt", $ReceiptPath,
    "--temp-root", ([IO.Path]::GetFullPath([IO.Path]::GetTempPath()))
)
$ReceiptPath = $receiptPolicy.stdout.Trim()

function Get-TreeSurface([string]$Root) {
    Assert-NoReparse $Root "tree root"
    $lines = [Collections.Generic.List[string]]::new()
    foreach ($item in Get-ChildItem -LiteralPath $Root -Force -Recurse | Sort-Object FullName) {
        Assert-NoReparse $item.FullName "tree member"
        $relative = [IO.Path]::GetRelativePath($Root, $item.FullName).Replace('\', '/')
        if ($item.PSIsContainer) {
            $lines.Add("D:$relative")
        } else {
            $lines.Add("F:${relative}:$((Get-Sha256File $item.FullName))")
        }
    }
    return Get-Sha256Text (($lines -join "`n") + "`n")
}

function Get-FileSnapshot([string[]]$Paths) {
    $snapshot = [ordered]@{}
    foreach ($path in $Paths | Sort-Object -Unique) {
        Assert-NoReparse $path "protected file"
        $relative = [IO.Path]::GetRelativePath($projectRoot, $path).Replace('\', '/')
        $snapshot[$relative] = Get-Sha256File $path
    }
    return $snapshot
}

function Assert-SnapshotEqual($Before, $After, [string]$Label) {
    Assert-Equal $Before.Count $After.Count "$Label file count"
    foreach ($key in $Before.Keys) {
        if (-not $After.Contains($key)) { throw "$Label removed protected path: $key" }
        Assert-Equal $Before[$key] $After[$key] "$Label $key"
    }
}

$raw = Join-Path $projectRoot "evidence\exhaustive-trace\raw"
$checkedInventories = Join-Path $projectRoot "evidence\exhaustive-trace\inventories"
$checkedDomains = Join-Path $projectRoot "evidence\exhaustive-trace\domains"
$sourceManifest = Join-Path $projectRoot "docs\reverse-engineering\exhaustive-trace\source-manifest.json"
$domainConfig = Join-Path $projectRoot "docs\reverse-engineering\exhaustive-trace\domains.json"
$characterBoundary = Join-Path $projectRoot "docs\new-design\2026-08-27-original-character-roster-recovery-boundary.md"
$resourceAdjudications = Join-Path $projectRoot "evidence\exhaustive-trace\adjudications\resources.json"
$resourceAdjudicationDirectory = Join-Path $projectRoot "evidence\exhaustive-trace\adjudications"
$bootFirstExporter = Join-Path $projectRoot "work\20260828-bootfirst-resource-loader\ExportBootFirstFlow.java"
$bootFirstFlow = Join-Path $projectRoot "work\20260828-bootfirst-resource-loader\evidence\bootfirst-flow.txt"
$cdManualInspector = Join-Path $projectRoot "work\20260828-cd-manual-resource-loader\InspectCdManual.py"
$cdManualPdf = Join-Path $projectRoot 'evidence\installshield-extract\____________s___\____\doc\___p_`_v_}_j___a__.pdf'
$termsInspector = Join-Path $projectRoot "work\20260829-original-terms-resource-loader\InspectTermsDocument.py"
$termsDocument = Join-Path $projectRoot 'evidence\installshield-extract\____________s___\____\doc\___p_`vii___p_k__.txt'
$termsSupportLicense = Join-Path $projectRoot "evidence\installshield-extract\_support_language_independent_os_independent_files\license.txt"
$originalCdIso = "E:\logh7-vm-media\LOGH7-original-cd.iso"
$originalClientExe = Join-Path $projectRoot "evidence\installshield-extract\____________s___\____\exe\g7mtclient.exe"
$bootFirstExe = Join-Path $projectRoot "evidence\installshield-extract\____________s___\____\bootfirst.exe"
$updateClientExe = Join-Path $projectRoot "evidence\installshield-extract\____________s___\____\gin7updateclient.exe"
$expectedInventoryNames = @("authority.jsonl", "entities.jsonl", "functions.jsonl", "protocol.jsonl", "resources.jsonl", "ui.jsonl")
$expectedReconciliationNames = @("authority-reconciliation.json", "entities-reconciliation.json", "functions-reconciliation.json", "protocol-reconciliation.json", "resources-reconciliation.json", "ui-reconciliation.json")
$expectedDomainNames = 1..16 | ForEach-Object { "D{0:D2}.json" -f $_ }

function Assert-ExactNames([string]$Directory, [string[]]$Expected, [string]$Filter, [string]$Label) {
    $actual = @(Get-ChildItem -LiteralPath $Directory -File -Filter $Filter | ForEach-Object Name | Sort-Object)
    $wanted = @($Expected | Sort-Object)
    Assert-Equal ($wanted -join "|") ($actual -join "|") "$Label names"
}

function New-RunDirectory([string]$Root, [string]$Name) {
    $run = Join-Path $Root $Name
    [void](New-Item -ItemType Directory -Path $run)
    [void](New-Item -ItemType Directory -Path (Join-Path $run "inventories"))
    [void](New-Item -ItemType Directory -Path (Join-Path $run "raw"))
    Assert-NoReparse $run "run directory"
    return $run
}

function Build-FoundationChain([string]$Run) {
    $inventories = Join-Path $Run "inventories"
    $reconciliations = $inventories
    $imports = @(
        @{ name="protocol"; module="import_protocol"; input="protocol-ghidra.json"; evidence="protocol-evidence-manifest.json"; output="protocol.jsonl"; reconciliation="protocol-reconciliation.json" },
        @{ name="ui"; module="import_ui"; input="ui-ghidra.json"; evidence="ui-evidence-manifest.json"; output="ui.jsonl"; reconciliation="ui-reconciliation.json" },
        @{ name="entities"; module="import_entities"; input="records-ghidra.json"; evidence="records-evidence-manifest.json"; output="entities.jsonl"; reconciliation="entities-reconciliation.json" },
        @{ name="resources"; module="import_resources"; input="resources-ghidra.json"; evidence="resources-evidence-manifest.json"; output="resources.jsonl"; reconciliation="resources-reconciliation.json" },
        @{ name="functions"; module="import_functions"; input="functions-ghidra.json"; evidence="functions-evidence-manifest.json"; output="functions.jsonl"; reconciliation="functions-reconciliation.json" }
    )
    foreach ($entry in $imports) {
        $importArguments = @(
            "-B", "-m", "tools.exhaustive_trace.$($entry.module)",
            "--input", (Join-Path $raw $entry.input),
            "--output", (Join-Path $inventories $entry.output),
            "--reconciliation", (Join-Path $reconciliations $entry.reconciliation),
            "--evidence-manifest", (Join-Path $raw $entry.evidence),
            "--source-manifest", $sourceManifest
        )
        if ($entry.name -eq "resources") {
            $importArguments += @("--adjudications", $resourceAdjudications)
        }
        Invoke-Python -Label "$([IO.Path]::GetFileName($Run))-$($entry.name)" -Arguments $importArguments | Out-Null
    }
    Invoke-Python -Label "$([IO.Path]::GetFileName($Run))-authority" -Arguments @(
        "-B", "-m", "tools.exhaustive_trace.import_authority",
        "--server", (Join-Path $projectRoot "apps\server"),
        "--contracts", (Join-Path $projectRoot "contracts"),
        "--db", (Join-Path $projectRoot "db"),
        "--protocol-inventory", (Join-Path $inventories "protocol.jsonl"),
        "--entity-inventory", (Join-Path $inventories "entities.jsonl"),
        "--ui-inventory", (Join-Path $inventories "ui.jsonl"),
        "--raw-output", (Join-Path $Run "raw\authority-source.json"),
        "--output", (Join-Path $inventories "authority.jsonl"),
        "--reconciliation", (Join-Path $reconciliations "authority-reconciliation.json")
    ) | Out-Null
    Assert-ExactNames $inventories $expectedInventoryNames "*.jsonl" "inventory"
    Assert-ExactNames $reconciliations $expectedReconciliationNames "*-reconciliation.json" "reconciliation"
    Assert-Equal 12 @(Get-ChildItem -LiteralPath $inventories -File).Count "inventory directory total file count"

    Invoke-Python -Label "$([IO.Path]::GetFileName($Run))-graph" -Arguments @(
        "-B", "-m", "tools.exhaustive_trace.cli", "build-graph",
        "--inventories", $inventories, "--source-manifest", $sourceManifest,
        "--output", (Join-Path $Run "graph.jsonl")
    ) | Out-Null
    Invoke-Python -Label "$([IO.Path]::GetFileName($Run))-coverage-expected-fatal" -ExpectedExitCodes @(1) -Arguments @(
        "-B", "-m", "tools.exhaustive_trace.cli", "audit",
        "--inventories", $inventories, "--source-manifest", $sourceManifest,
        "--graph", (Join-Path $Run "graph.jsonl"), "--output", (Join-Path $Run "coverage.json")
    ) | Out-Null
    $coverage = Get-Content -Raw -Encoding UTF8 (Join-Path $Run "coverage.json") | ConvertFrom-Json
    Assert-Equal 1 $coverage.conservation.fatalStructuralCount "coverage fatal count"
    Assert-Equal "FEATURE_REACHABILITY_LEDGER_ABSENT" $coverage.globalFatals[0].ruleId "coverage fatal identity"

    Invoke-Python -Label "$([IO.Path]::GetFileName($Run))-domains" -Arguments @(
        "-B", "-m", "tools.exhaustive_trace.cli", "package-domains",
        "--inventories", $inventories, "--source-manifest", $sourceManifest,
        "--graph", (Join-Path $Run "graph.jsonl"), "--coverage", (Join-Path $Run "coverage.json"),
        "--domains", $domainConfig, "--output", (Join-Path $Run "domains")
    ) | Out-Null
    Assert-ExactNames (Join-Path $Run "domains") $expectedDomainNames "*.json" "domain"
    Invoke-Python -Label "$([IO.Path]::GetFileName($Run))-work-packages" -Arguments @(
        "-B", "-m", "tools.exhaustive_trace.cli", "build-work-packages",
        "--inventories", $inventories, "--source-manifest", $sourceManifest,
        "--graph", (Join-Path $Run "graph.jsonl"), "--coverage", (Join-Path $Run "coverage.json"),
        "--domains", $domainConfig, "--domain-packages", (Join-Path $Run "domains"),
        "--output", (Join-Path $Run "domain-plan-inputs.json")
    ) | Out-Null
    Invoke-Python -Label "$([IO.Path]::GetFileName($Run))-recovery" -Arguments @(
        "-B", "-m", "tools.exhaustive_trace.recovery",
        "--inventories", $inventories, "--graph", (Join-Path $Run "graph.jsonl"),
        "--coverage", (Join-Path $Run "coverage.json"), "--sources", $sourceManifest,
        "--domain-config", $domainConfig, "--domain-packages", (Join-Path $Run "domains"),
        "--character-boundary", $characterBoundary, "--output", (Join-Path $Run "recovery.json")
    ) | Out-Null
    return $Run
}

function Get-ArtifactMap([string]$Run) {
    $map = [ordered]@{}
    foreach ($name in $expectedInventoryNames) { $map["inventories/$name"] = Join-Path $Run "inventories\$name" }
    foreach ($name in $expectedReconciliationNames) { $map["reconciliations/$name"] = Join-Path $Run "inventories\$name" }
    $map["graph.jsonl"] = Join-Path $Run "graph.jsonl"
    $map["coverage.json"] = Join-Path $Run "coverage.json"
    foreach ($name in $expectedDomainNames) { $map["domains/$name"] = Join-Path $Run "domains\$name" }
    $map["domain-plan-inputs.json"] = Join-Path $Run "domain-plan-inputs.json"
    $map["recovery.json"] = Join-Path $Run "recovery.json"
    return $map
}

function Get-CheckedArtifactPath([string]$Key) {
    if ($Key.StartsWith("inventories/")) { return Join-Path $checkedInventories $Key.Substring(12) }
    if ($Key.StartsWith("reconciliations/")) { return Join-Path $checkedInventories $Key.Substring(16) }
    if ($Key.StartsWith("domains/")) { return Join-Path $checkedDomains $Key.Substring(8) }
    return Join-Path (Join-Path $projectRoot "evidence\exhaustive-trace") $Key
}

function Assert-AssignmentConservation([string]$Run) {
    $sourceKeys = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($name in $expectedInventoryNames) {
        foreach ($line in [IO.File]::ReadLines((Join-Path $Run "inventories\$name"), [Text.Encoding]::UTF8)) {
            $row = $line | ConvertFrom-Json
            if (-not $sourceKeys.Add([string]$row.key)) { throw "duplicate source key: $($row.key)" }
        }
    }
    $primaryKeys = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($name in $expectedDomainNames) {
        $domain = Get-Content -Raw -Encoding UTF8 (Join-Path $Run "domains\$name") | ConvertFrom-Json
        foreach ($row in $domain.primaryRows) {
            if (-not $primaryKeys.Add([string]$row.rowKey)) { throw "duplicate primary assignment: $($row.rowKey)" }
        }
    }
    Assert-Equal $sourceKeys.Count $primaryKeys.Count "primary assignment count"
    foreach ($key in $sourceKeys) {
        if (-not $primaryKeys.Contains($key)) { throw "unassigned source row: $key" }
    }
    return [ordered]@{ sourceKeyCount=$sourceKeys.Count; primaryKeyCount=$primaryKeys.Count; unassigned=0; duplicatePrimary=0 }
}

$protectedFiles = [Collections.Generic.List[string]]::new()
foreach ($path in @($sourceManifest, $domainConfig, $characterBoundary, $resourceAdjudications, $MyInvocation.MyCommand.Path)) { $protectedFiles.Add($path) }
foreach ($path in Get-ChildItem -LiteralPath $resourceAdjudicationDirectory -File) { $protectedFiles.Add($path.FullName) }
foreach ($path in @($bootFirstExporter, $bootFirstFlow)) { $protectedFiles.Add($path) }
foreach ($path in @(
    $cdManualInspector, $cdManualPdf, $termsInspector, $termsDocument, $termsSupportLicense,
    $originalCdIso,
    $originalClientExe, $bootFirstExe, $updateClientExe
)) { $protectedFiles.Add($path) }
foreach ($path in Get-ChildItem -LiteralPath $raw -File) { $protectedFiles.Add($path.FullName) }
foreach ($path in Get-ChildItem -LiteralPath $checkedInventories -File) { $protectedFiles.Add($path.FullName) }
foreach ($path in Get-ChildItem -LiteralPath $checkedDomains -File) { $protectedFiles.Add($path.FullName) }
foreach ($path in @(
    (Join-Path $projectRoot "evidence\exhaustive-trace\graph.jsonl"),
    (Join-Path $projectRoot "evidence\exhaustive-trace\coverage.json"),
    (Join-Path $projectRoot "evidence\exhaustive-trace\domain-plan-inputs.json"),
    (Join-Path $projectRoot "evidence\exhaustive-trace\recovery.json")
)) { $protectedFiles.Add($path) }
foreach ($path in Get-ChildItem -LiteralPath (Join-Path $projectRoot "tools\exhaustive_trace") -File -Filter "*.py") { $protectedFiles.Add($path.FullName) }
foreach ($path in Get-ChildItem -LiteralPath (Join-Path $projectRoot "tests\tools\exhaustive_trace") -File -Filter "*.py") { $protectedFiles.Add($path.FullName) }

$protectedBefore = Get-FileSnapshot $protectedFiles.ToArray()
$treeBefore = [ordered]@{
    server = Get-TreeSurface (Join-Path $projectRoot "apps\server")
    contracts = Get-TreeSurface (Join-Path $projectRoot "contracts")
    database = Get-TreeSurface (Join-Path $projectRoot "db")
}
$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) ("logh7-foundation-" + [Guid]::NewGuid().ToString("N"))
[void](New-Item -ItemType Directory -Path $temporaryRoot)
$resolvedTemporaryRoot = (Resolve-Path -LiteralPath $temporaryRoot).Path
Assert-NoReparse $resolvedTemporaryRoot "temporary root"

try {
    $tests = Invoke-Python -Label "full-exhaustive-trace-tests" -Arguments @("-B", "-m", "unittest", "discover", "-s", "tests/tools/exhaustive_trace", "-p", "test_*.py")
    $testInputPath = Join-Path $resolvedTemporaryRoot "unittest-result.json"
    $testInput = [ordered]@{ stdout=$tests.stdout; stderr=$tests.stderr; exitCode=$tests.exitCode } | ConvertTo-Json
    [IO.File]::WriteAllText($testInputPath, $testInput, $utf8NoBom)
    $testParser = Invoke-Python -Label "strict-unittest-result" -Arguments @(
        "-B", "-m", "tools.exhaustive_trace.foundation_verification",
        "--parse-unittest-json", $testInputPath
    )
    $testSummary = $testParser.stdout | ConvertFrom-Json
    $testCount = [int]$testSummary.discovered
    $manifestGate = Invoke-Python -Label "source-manifest-gate" -Arguments @("-B", "-m", "tools.exhaustive_trace.source_manifest", $sourceManifest)
    $runA = Build-FoundationChain (New-RunDirectory $resolvedTemporaryRoot "run-a")
    $runB = Build-FoundationChain (New-RunDirectory $resolvedTemporaryRoot "run-b")
    $mapA = Get-ArtifactMap $runA
    $mapB = Get-ArtifactMap $runB
    $artifactHashes = [ordered]@{}
    foreach ($key in $mapA.Keys) {
        Assert-CanonicalText $mapA[$key]
        Assert-CanonicalText $mapB[$key]
        $checked = Get-CheckedArtifactPath $key
        $hashA = Get-Sha256File $mapA[$key]
        $hashB = Get-Sha256File $mapB[$key]
        $hashChecked = Get-Sha256File $checked
        Assert-Equal $hashA $hashB "run-a/run-b $key"
        Assert-Equal $hashChecked $hashA "checked/run-a $key"
        $artifactHashes[$key] = [ordered]@{ checked=$hashChecked; runA=$hashA; runB=$hashB; bytes=(Get-Item -LiteralPath $checked).Length }
    }
    $assignments = Assert-AssignmentConservation $runA
    [void](Assert-AssignmentConservation $runB)

    $graph = Get-Content -Encoding UTF8 (Join-Path $runA "graph.jsonl") -TotalCount 1 | ConvertFrom-Json
    $coverage = Get-Content -Raw -Encoding UTF8 (Join-Path $runA "coverage.json") | ConvertFrom-Json
    $workPackages = Get-Content -Raw -Encoding UTF8 (Join-Path $runA "domain-plan-inputs.json") | ConvertFrom-Json
    $recovery = Get-Content -Raw -Encoding UTF8 (Join-Path $runA "recovery.json") | ConvertFrom-Json
    $inventoryCounts = [ordered]@{}
    foreach ($name in @("protocol", "ui", "entities", "resources", "functions", "authority")) {
        $inventoryCounts[$name] = [int]$graph.inventorySources.$name.rowCount
    }
    $perDomain = [ordered]@{}
    foreach ($domainId in 1..16 | ForEach-Object { "D{0:D2}" -f $_ }) {
        $recoveryReverse = @($workPackages.recoveryUnits | Where-Object domain -eq $domainId).Count
        $candidateUnits = @($workPackages.candidateFeaturePackages | Where-Object domain -eq $domainId | ForEach-Object units)
        $candidateReverse = @($candidateUnits | Where-Object kind -eq "reverse_contract").Count
        $candidateImplementation = @($candidateUnits | Where-Object { @($_.targets).Count -gt 0 }).Count
        $liveAction = @((@($workPackages.recoveryUnits) + @($candidateUnits)) | Where-Object { [int]$_.liveInputCount -gt 0 }).Count
        $perDomain[$domainId] = [ordered]@{
            recoveryReverseUnits=$recoveryReverse
            candidateFeatureReverseUnits=$candidateReverse
            totalReverseEngineeringUnits=$recoveryReverse + $candidateReverse
            confirmedImplementationUnits=0
            candidateTargetBearingImplementationUnits=$candidateImplementation
            liveActionUnits=$liveAction
        }
    }
    $firstUnit = $workPackages.recoveryUnits[0]
    Assert-Equal "RECOVERY:D01:RESOURCE_LOADER:E346F47C94A6E543" $firstUnit.unitId "first unit"
    Assert-Equal 0 $workPackages.conservation.uncoveredOpenRowCount "uncovered recovery rows"
    Assert-Equal 0 $workPackages.conservation.confirmedGameplayFeatureCount "confirmed gameplay features"
    Assert-Equal 0 $workPackages.conservation.maxLiveInputCount "live input count"
    Assert-Equal 0 $recovery.conservation.unaccountedRecoverySubjectCount "unaccounted recovery subjects"

    $protectedAfter = Get-FileSnapshot $protectedFiles.ToArray()
    Assert-SnapshotEqual $protectedBefore $protectedAfter "protected inputs and artifacts"
    $treeAfter = [ordered]@{
        server = Get-TreeSurface (Join-Path $projectRoot "apps\server")
        contracts = Get-TreeSurface (Join-Path $projectRoot "contracts")
        database = Get-TreeSurface (Join-Path $projectRoot "db")
    }
    foreach ($key in $treeBefore.Keys) { Assert-Equal $treeBefore[$key] $treeAfter[$key] "source tree $key" }

    $receiptMode = if ([StringComparer]::OrdinalIgnoreCase.Equals($ReceiptPath, $defaultReceiptPath)) {
        "REPOSITORY_DEFAULT_ATOMIC_REPLACE"
    } else {
        "FRESH_SYSTEM_TEMP_CHILD"
    }
    $finalReceiptPolicy = Invoke-Python -Label "receipt-target-policy-final" -Arguments @(
        "-B", "-m", "tools.exhaustive_trace.foundation_verification",
        "--project-root", $projectRoot,
        "--default-receipt", $defaultReceiptPath,
        "--receipt", $ReceiptPath,
        "--temp-root", ([IO.Path]::GetFullPath([IO.Path]::GetTempPath()))
    )
    Assert-Equal $ReceiptPath $finalReceiptPolicy.stdout.Trim() "final receipt target policy"

    $receipt = [ordered]@{
        schemaVersion = 1
        recordType = "EXHAUSTIVE_TRACE_FOUNDATION_VERIFICATION"
        status = [ordered]@{
            boundedFoundation = "PASS_REPRODUCIBLE_BASELINE_WITH_ACKNOWLEDGED_FATAL"
            overallGoal = "INCOMPLETE"
            coverageGate = "STRUCTURAL_FATAL"
            featureLedger = "ABSENT"
            independentReview = "UNSEEN"
        }
        tests = [ordered]@{
            discovered=$testCount; passed=[int]$testSummary.passed
            failures=[int]$testSummary.failures; errors=[int]$testSummary.errors
            skipped=[int]$testSummary.skipped
            expectedFailures=[int]$testSummary.expectedFailures
            unexpectedSuccesses=[int]$testSummary.unexpectedSuccesses
            stdout=$tests.stdout; stderr=$tests.stderr
        }
        sourceManifest = [ordered]@{ sha256=(Get-Sha256File $sourceManifest); gateExit=$manifestGate.exitCode }
        deterministicArtifacts = $artifactHashes
        conservation = [ordered]@{
            inventoryRows = $inventoryCounts
            sourceRowCount = [int]$graph.conservation.sourceRowCount
            graphNodeCount = [int]$graph.conservation.nodeCount
            graphEdgeCount = [int]$graph.conservation.edgeCount
            graphStructuralOrphanCount = [int]$graph.conservation.unrepresentedSourceRows + [int]$graph.conservation.danglingEdgeCount + [int]$graph.conservation.unaccountedJoinCandidates
            graphUnresolvedReferenceNodeCount = [int]$graph.conservation.unresolvedReferenceNodeCount
            coverageFatalCount = [int]$coverage.conservation.fatalStructuralCount
            coverageFatalIds = @($coverage.globalFatals.ruleId)
            evidenceGapCount = [int]$coverage.conservation.evidenceGapCount
            missingBoundaryOccurrenceCount = [int]$coverage.conservation.missingBoundaryCount
            closedVerticalTraceCount = [int]$coverage.conservation.closedVerticalTraceCount
            unknownVerdictRowCount = [int]$coverage.conservation.rowCountByVerdict.UNKNOWN
            routingUnresolvedRowCount = [int]$workPackages.conservation.routingUnresolvedRowCount
            domainAssignments = $assignments
            deterministicArtifactCount = $artifactHashes.Count
            recoverySubjects = [int]$recovery.conservation.totalSubjectCount
            recoveryActionableSubjects = [int]$recovery.conservation.actionableSubjectCount
            recoveryDispositionCounts = $recovery.conservation.countsByDisposition
            authoringPackageCount = [int]$recovery.conservation.authoringPackageCount
            confirmedGameplayFeatureCount = [int]$workPackages.conservation.confirmedGameplayFeatureCount
            candidateGameplayFeatureCount = [int]$workPackages.conservation.candidateGameplayFeatureCount
            candidateFeatureUnitCount = [int]$workPackages.conservation.candidateFeatureUnitCount
            liveActionUnitCount = 0
            recoverableLiveSubjectCount = [int]$recovery.conservation.countsByDisposition.RECOVERABLE_LIVE
            automaticRetryUnitCount = [int]$workPackages.conservation.automaticRetryUnitCount
            runtimeMutationUnitCount = [int]$workPackages.conservation.runtimeMutationUnitCount
        }
        perDomainUnits = $perDomain
        firstUnit = [ordered]@{
            unitId=$firstUnit.unitId
            domain=$firstUnit.domain
            pathKey=$firstUnit.pathKey
            firstMissingBoundary=$firstUnit.firstMissingBoundary
            recoveryDisposition=$firstUnit.recoveryDisposition
            dependsOnUnitIds=@($firstUnit.dependsOnUnitIds)
            executionStatus="NOT_STARTED"
            authorization="NOT_AUTHORIZED_BY_BASELINE"
        }
        knownLimitations = @(
            [ordered]@{
                id="AUTHORITY_RAW_PATH_SENSITIVE"
                checkedPath="evidence/exhaustive-trace/raw/authority-source.json"
                disposition="EXCLUDED_FROM_BYTE_IDENTITY"
                reason="the raw receipt intentionally retains validated absolute transport paths, while its semantic surface excludes those paths so normalized authority inventory, reconciliation, and every downstream artifact remain byte-identical across staging roots"
            }
        )
        safety = [ordered]@{
            vmActions=0; originalExecutableActions=0; debuggerAttach=0; processMemoryRead=0; processMemoryWrite=0
            physicalInput=0; automaticRetries=0; serverProtocolDatabaseMutations=0; vmLifecycleMutations=0
            checkedArtifactMutations=0
        }
        commands = @($commandRecords)
        protectedInputsBefore = $protectedBefore
        protectedInputsAfter = $protectedAfter
        sourceTreesBefore = $treeBefore
        sourceTreesAfter = $treeAfter
        receiptTargetPolicy = [ordered]@{
            path=$ReceiptPath
            mode=$receiptMode
            publication="PYTHON_ATOMIC_NO_REPLACE_FOR_FRESH_OR_OS_REPLACE_FOR_DEFAULT"
        }
    }
    $receiptDirectory = Split-Path -Parent $ReceiptPath
    [void](New-Item -ItemType Directory -Force -Path $receiptDirectory)
    $json = ($receipt | ConvertTo-Json -Depth 20) -replace "`r`n", "`n"
    $temporaryReceipt = Join-Path $receiptDirectory (".$([IO.Path]::GetFileName($ReceiptPath)).$([Guid]::NewGuid().ToString('N')).tmp")
    try {
        [IO.File]::WriteAllText($temporaryReceipt, $json + "`n", $utf8NoBom)
        $publication = Invoke-Python -Label "receipt-publication" -Arguments @(
            "-B", "-m", "tools.exhaustive_trace.foundation_verification",
            "--publish-source", $temporaryReceipt,
            "--publish-target", $ReceiptPath,
            "--publish-mode", $receiptMode
        )
        Assert-Equal $ReceiptPath $publication.stdout.Trim() "receipt publication target"
    } finally {
        if (Test-Path -LiteralPath $temporaryReceipt) { [IO.File]::Delete($temporaryReceipt) }
    }
    Write-Host "FOUNDATION_BASELINE_PASS receipt=$ReceiptPath sha256=$(Get-Sha256File $ReceiptPath)"
} finally {
    if (Test-Path -LiteralPath $resolvedTemporaryRoot) {
        $resolved = (Resolve-Path -LiteralPath $resolvedTemporaryRoot).Path
        $tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
        if (-not $resolved.StartsWith($tempBase, [StringComparison]::OrdinalIgnoreCase)) {
            throw "refusing to remove temporary directory outside temp base: $resolved"
        }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
