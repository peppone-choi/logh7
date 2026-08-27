"""Command-line entry points for exhaustive-trace graph artifacts."""

from __future__ import annotations

import argparse
import hashlib
import os
import tempfile
from pathlib import Path
from typing import Callable, Sequence, TypeVar

from .coverage import audit_graph, coverage_json, load_coverage_json
from .graph import build_graph, graph_jsonl, load_graph_jsonl
from .inventories import load_inventory_bundle
from .io import canonical_json


PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SOURCE_MANIFEST = (
    PROJECT_ROOT / "docs" / "reverse-engineering" / "exhaustive-trace" / "source-manifest.json"
)
DEFAULT_INVENTORIES = PROJECT_ROOT / "evidence" / "exhaustive-trace" / "inventories"


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
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    return int(args.handler(args))


if __name__ == "__main__":
    raise SystemExit(main())
