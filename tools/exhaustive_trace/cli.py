"""Command-line entry points for exhaustive-trace graph artifacts."""

from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import tempfile
import uuid
from pathlib import Path
from typing import Callable, Sequence, TypeVar

from .coverage import audit_graph, coverage_json, load_coverage_json
from .domains import (
    build_domain_packages,
    domain_package_files,
    load_domain_config,
    load_domain_packages,
)
from .graph import build_graph, graph_jsonl, load_graph_jsonl
from .inventories import load_inventory_bundle
from .io import canonical_json
from .work_packages import (
    build_work_packages,
    inferred_move_grid_candidate,
    load_work_packages_json,
    work_packages_json,
)


PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SOURCE_MANIFEST = (
    PROJECT_ROOT / "docs" / "reverse-engineering" / "exhaustive-trace" / "source-manifest.json"
)
DEFAULT_INVENTORIES = PROJECT_ROOT / "evidence" / "exhaustive-trace" / "inventories"
DEFAULT_DOMAINS = PROJECT_ROOT / "docs" / "reverse-engineering" / "exhaustive-trace" / "domains.json"


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest().upper()


Verified = TypeVar("Verified")


def _write_atomic(
    path: Path,
    data: bytes,
    *,
    verify: Callable[[Path], Verified],
) -> Verified:
    output = Path(os.path.abspath(path))
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.is_symlink():
        raise ValueError(f"graph output must not be a link: {output}")
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{output.name}.", suffix=".tmp", dir=output.parent
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        verified = verify(temporary)
        os.replace(temporary, output)
        return verified
    finally:
        if temporary.exists():
            temporary.unlink()


def _is_link_or_reparse(path: Path) -> bool:
    try:
        stat = path.lstat()
    except OSError:
        return False
    return path.is_symlink() or bool(getattr(stat, "st_file_attributes", 0) & 0x400)


def _write_directory_transactional(
    path: Path,
    files: dict[str, bytes],
    *,
    verify: Callable[[Path], Verified],
) -> Verified:
    output = Path(os.path.abspath(path))
    output.parent.mkdir(parents=True, exist_ok=True)
    for component in (*reversed(output.parents), output):
        if component == Path(component.anchor):
            continue
        if component.exists() and _is_link_or_reparse(component):
            raise ValueError(f"domain output contains a link or reparse point: {component}")
    if output.exists() and not output.is_dir():
        raise ValueError(f"domain output is not a directory: {output}")
    staging = Path(tempfile.mkdtemp(prefix=f".{output.name}.", suffix=".staging", dir=output.parent))
    backup: Path | None = None
    try:
        for name, data in sorted(files.items()):
            target = staging / name
            with target.open("wb") as stream:
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
        verified = verify(staging)
        if output.exists():
            backup = output.parent / f".{output.name}.{uuid.uuid4().hex}.backup"
            os.replace(output, backup)
        try:
            os.replace(staging, output)
        except BaseException:
            if backup is not None and backup.exists() and not output.exists():
                os.replace(backup, output)
            raise
        if backup is not None and backup.exists():
            shutil.rmtree(backup)
        return verified
    finally:
        if staging.exists():
            shutil.rmtree(staging)


def _build_graph(args: argparse.Namespace) -> int:
    bundle = load_inventory_bundle(
        args.inventories,
        source_manifest=args.source_manifest,
    )
    graph = build_graph(bundle.rows)
    data = graph_jsonl(graph, bundle).encode("utf-8")
    output = Path(args.output)
    verified = _write_atomic(
        output,
        data,
        verify=lambda temporary: load_graph_jsonl(temporary, bundle=bundle),
    )
    print(
        canonical_json(
            {
                "command": "build-graph",
                "edgeCount": verified.conservation["edgeCount"],
                "fileSha256": _sha256(data),
                "graphSurfaceSha256": verified.graph_surface_sha256,
                "nodeCount": verified.conservation["nodeCount"],
                "output": str(Path(os.path.abspath(output))),
                "sourceRowNodes": verified.conservation["sourceRowNodes"],
                "status": "PASS",
            }
        ),
        end="",
    )
    return 0


def _audit(args: argparse.Namespace) -> int:
    bundle = load_inventory_bundle(args.inventories, source_manifest=args.source_manifest)
    graph = load_graph_jsonl(args.graph, bundle=bundle)
    report = audit_graph(graph, bundle=bundle)
    data = coverage_json(report, graph=graph, bundle=bundle).encode("utf-8")
    output = Path(args.output)
    verified = _write_atomic(
        output,
        data,
        verify=lambda temporary: load_coverage_json(temporary, graph=graph, bundle=bundle),
    )
    failed = bool(verified.fatals)
    print(
        canonical_json(
            {
                "command": "audit",
                "coverageSurfaceSha256": verified.coverage_surface_sha256,
                "evidenceGapCount": verified.conservation["evidenceGapCount"],
                "fatalStructuralCount": verified.conservation["fatalStructuralCount"],
                "fileSha256": _sha256(data),
                "output": str(Path(os.path.abspath(output))),
                "sourceRowCount": verified.conservation["sourceRowCount"],
                "status": "FAIL" if failed else "PASS",
            }
        ),
        end="",
    )
    return 1 if failed else 0


def _package_domains(args: argparse.Namespace) -> int:
    bundle = load_inventory_bundle(args.inventories, source_manifest=args.source_manifest)
    graph = load_graph_jsonl(args.graph, bundle=bundle)
    coverage = load_coverage_json(args.coverage, graph=graph, bundle=bundle)
    config = load_domain_config(args.domains, project_root=PROJECT_ROOT)
    package_set = build_domain_packages(graph, coverage, config)
    files = {
        name: value if isinstance(value, bytes) else value.encode("utf-8")
        for name, value in domain_package_files(package_set).items()
    }
    output = Path(args.output)
    verified = _write_directory_transactional(
        output,
        files,
        verify=lambda directory: load_domain_packages(
            directory, graph=graph, coverage=coverage, config=config
        ),
    )
    print(
        canonical_json(
            {
                "command": "package-domains",
                "coverageGateStatus": verified.coverage_gate_status,
                "domainCount": len(verified.packages),
                "domainConfigSha256": config.config_sha256,
                "output": str(Path(os.path.abspath(output))),
                "packageSetSha256": verified.package_set_sha256,
                "sourceRowCount": verified.conservation["sourceRowCount"],
                "topologicalOrder": list(config.topological_order),
                "unresolvedRoutingCount": verified.conservation["unresolvedRoutingCount"],
                "status": "PASS",
            }
        ),
        end="",
    )
    return 0


def _build_work_packages(args: argparse.Namespace) -> int:
    bundle = load_inventory_bundle(args.inventories, source_manifest=args.source_manifest)
    graph = load_graph_jsonl(args.graph, bundle=bundle)
    coverage = load_coverage_json(args.coverage, graph=graph, bundle=bundle)
    config = load_domain_config(args.domains, project_root=PROJECT_ROOT)
    packages = load_domain_packages(
        args.domain_packages, graph=graph, coverage=coverage, config=config
    )
    candidates = inferred_move_grid_candidate(packages)
    plan = build_work_packages(packages, candidate_features=candidates)
    data = work_packages_json(plan).encode("utf-8")
    output = Path(args.output)
    verified = _write_atomic(
        output,
        data,
        verify=lambda temporary: load_work_packages_json(
            temporary, packages=packages, candidate_features=candidates
        ),
    )
    print(
        canonical_json(
            {
                "command": "build-work-packages",
                "candidateGameplayFeatureCount": verified.conservation["candidateGameplayFeatureCount"],
                "confirmedGameplayFeatureCount": verified.conservation["confirmedGameplayFeatureCount"],
                "coverageGateStatus": verified.coverage_gate_status,
                "featureLedgerStatus": verified.feature_ledger_status,
                "fileSha256": _sha256(data),
                "output": str(Path(os.path.abspath(output))),
                "planSurfaceSha256": verified.plan_surface_sha256,
                "recoveryUnitCount": verified.conservation["recoveryUnitCount"],
                "status": "PASS",
                "uncoveredOpenRowCount": verified.conservation["uncoveredOpenRowCount"],
            }
        ),
        end="",
    )
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subcommands = parser.add_subparsers(dest="command", required=True)
    build = subcommands.add_parser("build-graph", help="build the canonical typed graph")
    build.add_argument("--inventories", required=True, type=Path)
    build.add_argument("--source-manifest", type=Path, default=DEFAULT_SOURCE_MANIFEST)
    build.add_argument("--output", required=True, type=Path)
    build.set_defaults(handler=_build_graph)
    audit = subcommands.add_parser("audit", help="audit orphan and vertical-trace coverage")
    audit.add_argument("--inventories", type=Path, default=DEFAULT_INVENTORIES)
    audit.add_argument("--source-manifest", type=Path, default=DEFAULT_SOURCE_MANIFEST)
    audit.add_argument("--graph", required=True, type=Path)
    audit.add_argument("--output", required=True, type=Path)
    audit.set_defaults(handler=_audit)
    package = subcommands.add_parser("package-domains", help="build all sixteen domain packages")
    package.add_argument("--inventories", type=Path, default=DEFAULT_INVENTORIES)
    package.add_argument("--source-manifest", type=Path, default=DEFAULT_SOURCE_MANIFEST)
    package.add_argument("--graph", required=True, type=Path)
    package.add_argument("--coverage", required=True, type=Path)
    package.add_argument("--domains", type=Path, default=DEFAULT_DOMAINS)
    package.add_argument("--output", required=True, type=Path)
    package.set_defaults(handler=_package_domains)
    work = subcommands.add_parser(
        "build-work-packages", help="build recovery and implementation planning units"
    )
    work.add_argument("--inventories", type=Path, default=DEFAULT_INVENTORIES)
    work.add_argument("--source-manifest", type=Path, default=DEFAULT_SOURCE_MANIFEST)
    work.add_argument("--graph", required=True, type=Path)
    work.add_argument("--coverage", required=True, type=Path)
    work.add_argument("--domains", type=Path, default=DEFAULT_DOMAINS)
    work.add_argument("--domain-packages", required=True, type=Path)
    work.add_argument("--output", required=True, type=Path)
    work.set_defaults(handler=_build_work_packages)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    return int(args.handler(args))


if __name__ == "__main__":
    raise SystemExit(main())
