param(
    [switch]$Publish
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$env:PYTHONDONTWRITEBYTECODE = "1"

$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
Set-Location -LiteralPath $projectRoot
$python = (Get-Command python -ErrorAction Stop).Source
$raw = Join-Path $projectRoot "evidence\exhaustive-trace\raw"
$sourceManifest = Join-Path $projectRoot "docs\reverse-engineering\exhaustive-trace\source-manifest.json"
$domainConfig = Join-Path $projectRoot "docs\reverse-engineering\exhaustive-trace\domains.json"
$characterBoundary = Join-Path $projectRoot "docs\new-design\2026-08-27-original-character-roster-recovery-boundary.md"
$resourceAdjudications = Join-Path $projectRoot "evidence\exhaustive-trace\adjudications\resources.json"
$stagingRoot = Join-Path ([IO.Path]::GetTempPath()) ("logh7-foundation-refresh-" + [Guid]::NewGuid().ToString("N"))
$inventories = Join-Path $stagingRoot "inventories"
$domains = Join-Path $stagingRoot "domains"
$stagingRaw = Join-Path $stagingRoot "raw"
[void](New-Item -ItemType Directory -Path $inventories)
[void](New-Item -ItemType Directory -Path $domains)
[void](New-Item -ItemType Directory -Path $stagingRaw)

function Invoke-Stage {
    param(
        [string[]]$Arguments,
        [int[]]$ExpectedExitCodes = @(0)
    )
    & $python @Arguments
    if ($ExpectedExitCodes -notcontains $LASTEXITCODE) {
        throw "stage failed with exit ${LASTEXITCODE}: python $($Arguments -join ' ')"
    }
}

$imports = @(
    @{ module="import_protocol"; input="protocol-ghidra.json"; evidence="protocol-evidence-manifest.json"; output="protocol.jsonl"; reconciliation="protocol-reconciliation.json" },
    @{ module="import_ui"; input="ui-ghidra.json"; evidence="ui-evidence-manifest.json"; output="ui.jsonl"; reconciliation="ui-reconciliation.json" },
    @{ module="import_entities"; input="records-ghidra.json"; evidence="records-evidence-manifest.json"; output="entities.jsonl"; reconciliation="entities-reconciliation.json" },
    @{ module="import_resources"; input="resources-ghidra.json"; evidence="resources-evidence-manifest.json"; output="resources.jsonl"; reconciliation="resources-reconciliation.json" },
    @{ module="import_functions"; input="functions-ghidra.json"; evidence="functions-evidence-manifest.json"; output="functions.jsonl"; reconciliation="functions-reconciliation.json" }
)

foreach ($entry in $imports) {
    $arguments = @(
        "-B", "-m", "tools.exhaustive_trace.$($entry.module)",
        "--input", (Join-Path $raw $entry.input),
        "--output", (Join-Path $inventories $entry.output),
        "--reconciliation", (Join-Path $inventories $entry.reconciliation),
        "--evidence-manifest", (Join-Path $raw $entry.evidence),
        "--source-manifest", $sourceManifest
    )
    if ($entry.module -eq "import_resources") {
        $arguments += @("--adjudications", $resourceAdjudications)
    }
    Invoke-Stage -Arguments $arguments
}

Invoke-Stage -Arguments @(
    "-B", "-m", "tools.exhaustive_trace.import_authority",
    "--server", (Join-Path $projectRoot "apps\server"),
    "--contracts", (Join-Path $projectRoot "contracts"),
    "--db", (Join-Path $projectRoot "db"),
    "--protocol-inventory", (Join-Path $inventories "protocol.jsonl"),
    "--entity-inventory", (Join-Path $inventories "entities.jsonl"),
    "--ui-inventory", (Join-Path $inventories "ui.jsonl"),
    "--raw-output", (Join-Path $stagingRaw "authority-source.json"),
    "--output", (Join-Path $inventories "authority.jsonl"),
    "--reconciliation", (Join-Path $inventories "authority-reconciliation.json")
)
Invoke-Stage -Arguments @(
    "-B", "-m", "tools.exhaustive_trace.cli", "build-graph",
    "--inventories", $inventories, "--source-manifest", $sourceManifest,
    "--output", (Join-Path $stagingRoot "graph.jsonl")
)
Invoke-Stage -ExpectedExitCodes @(1) -Arguments @(
    "-B", "-m", "tools.exhaustive_trace.cli", "audit",
    "--inventories", $inventories, "--source-manifest", $sourceManifest,
    "--graph", (Join-Path $stagingRoot "graph.jsonl"),
    "--output", (Join-Path $stagingRoot "coverage.json")
)
$coverage = Get-Content -Raw -Encoding UTF8 (Join-Path $stagingRoot "coverage.json") | ConvertFrom-Json
if ($coverage.conservation.fatalStructuralCount -ne 1 -or $coverage.globalFatals[0].ruleId -ne "FEATURE_REACHABILITY_LEDGER_ABSENT") {
    throw "unexpected coverage fatal surface"
}
Invoke-Stage -Arguments @(
    "-B", "-m", "tools.exhaustive_trace.cli", "package-domains",
    "--inventories", $inventories, "--source-manifest", $sourceManifest,
    "--graph", (Join-Path $stagingRoot "graph.jsonl"),
    "--coverage", (Join-Path $stagingRoot "coverage.json"),
    "--domains", $domainConfig, "--output", $domains
)
Invoke-Stage -Arguments @(
    "-B", "-m", "tools.exhaustive_trace.cli", "build-work-packages",
    "--inventories", $inventories, "--source-manifest", $sourceManifest,
    "--graph", (Join-Path $stagingRoot "graph.jsonl"),
    "--coverage", (Join-Path $stagingRoot "coverage.json"),
    "--domains", $domainConfig, "--domain-packages", $domains,
    "--output", (Join-Path $stagingRoot "domain-plan-inputs.json")
)
Invoke-Stage -Arguments @(
    "-B", "-m", "tools.exhaustive_trace.recovery",
    "--inventories", $inventories,
    "--graph", (Join-Path $stagingRoot "graph.jsonl"),
    "--coverage", (Join-Path $stagingRoot "coverage.json"),
    "--sources", $sourceManifest,
    "--domain-config", $domainConfig,
    "--domain-packages", $domains,
    "--character-boundary", $characterBoundary,
    "--output", (Join-Path $stagingRoot "recovery.json")
)

if (@(Get-ChildItem -LiteralPath $inventories -File).Count -ne 12) { throw "expected 12 inventory/reconciliation files" }
if (@(Get-ChildItem -LiteralPath $domains -File -Filter "D*.json").Count -ne 16) { throw "expected 16 domain files" }

if ($Publish) {
    $checkedInventories = Join-Path $projectRoot "evidence\exhaustive-trace\inventories"
    $checkedDomains = Join-Path $projectRoot "evidence\exhaustive-trace\domains"
    foreach ($file in Get-ChildItem -LiteralPath $inventories -File) {
        Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $checkedInventories $file.Name) -Force
    }
    foreach ($file in Get-ChildItem -LiteralPath $domains -File -Filter "D*.json") {
        Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $checkedDomains $file.Name) -Force
    }
    foreach ($name in @("graph.jsonl", "coverage.json", "domain-plan-inputs.json", "recovery.json")) {
        Copy-Item -LiteralPath (Join-Path $stagingRoot $name) -Destination (Join-Path $projectRoot "evidence\exhaustive-trace\$name") -Force
    }
}

[pscustomobject]@{
    status = "PASS"
    published = [bool]$Publish
    stagingRoot = $stagingRoot
    sourceManifestSha256 = (Get-FileHash -LiteralPath $sourceManifest -Algorithm SHA256).Hash
    sourceRowCount = [int]$coverage.conservation.sourceRowCount
    evidenceGapCount = [int]$coverage.conservation.evidenceGapCount
    missingBoundaryCount = [int]$coverage.conservation.missingBoundaryCount
    fatalStructuralCount = [int]$coverage.conservation.fatalStructuralCount
} | ConvertTo-Json
