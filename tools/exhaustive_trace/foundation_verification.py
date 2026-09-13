"""Safety policy shared by the exhaustive-trace foundation verifier."""

from __future__ import annotations

import argparse
import json
import os
import re
import stat
from pathlib import Path
from typing import Sequence


def _absolute(path: str | Path) -> Path:
    return Path(os.path.abspath(os.fspath(path)))


def _is_reparse(path: Path) -> bool:
    try:
        result = path.lstat()
    except OSError:
        return False
    return path.is_symlink() or bool(
        getattr(result, "st_file_attributes", 0) & stat.FILE_ATTRIBUTE_REPARSE_POINT
    )


def _reject_reparse_chain(path: Path, label: str) -> None:
    for component in (*reversed(path.parents), path):
        if component == Path(component.anchor) or not component.exists():
            continue
        if _is_reparse(component):
            raise ValueError(f"{label} contains a symlink, junction, or reparse point: {component}")


def _same_path(left: Path, right: Path) -> bool:
    return os.path.normcase(os.fspath(left)) == os.path.normcase(os.fspath(right))


def validate_receipt_target(
    *,
    project_root: str | Path,
    default_receipt: str | Path,
    requested_receipt: str | Path,
    temp_root: str | Path | None = None,
) -> Path:
    """Return an approved absolute receipt target or fail before verifier work starts.

    The repository-owned default receipt may be atomically replaced. Any explicit
    independent-review receipt must be a fresh direct child of the real system temp
    root, which prevents the verifier from overwriting an arbitrary repository file.
    """

    project = _absolute(project_root)
    default = _absolute(default_receipt)
    requested = _absolute(requested_receipt)
    temporary = _absolute(temp_root if temp_root is not None else Path(os.getenv("TEMP", "")))
    if not project.is_dir():
        raise ValueError(f"project root must exist: {project}")
    _reject_reparse_chain(project, "project root")
    _reject_reparse_chain(default.parent, "default receipt parent")
    try:
        default.relative_to(project)
    except ValueError as error:
        raise ValueError("default receipt must be inside the project root") from error

    if _same_path(requested, default):
        if os.path.lexists(requested) and (not requested.is_file() or _is_reparse(requested)):
            raise ValueError("default receipt must be a regular non-reparse file")
        return requested

    if not temporary.is_dir():
        raise ValueError(f"temporary receipt root must exist: {temporary}")
    _reject_reparse_chain(temporary, "temporary receipt root")
    if not _same_path(requested.parent, temporary):
        raise ValueError("explicit receipt must be a direct child of the temporary receipt root")
    if requested.suffix.casefold() != ".json":
        raise ValueError("explicit receipt must use a .json filename")
    if os.path.lexists(requested):
        raise ValueError("explicit temporary receipt must be fresh and must not exist")
    return requested


def parse_unittest_result(*, stdout: str, stderr: str, exit_code: int) -> dict[str, int]:
    combined = f"{stdout}\n{stderr}"
    if exit_code != 0:
        raise ValueError(f"unittest returned nonzero exit code: {exit_code}")
    match = re.search(r"Ran\s+(\d+)\s+tests?", combined)
    if match is None:
        raise ValueError("unittest discovered count is missing")
    if re.search(r"(?m)^OK\r?$", combined) is None:
        raise ValueError("unittest did not end with an exact zero-skip OK status")

    counters = {
        "skipped": r"skipped=(\d+)",
        "expectedFailures": r"expected failures?=(\d+)",
        "unexpectedSuccesses": r"unexpected successes?=(\d+)",
    }
    parsed = {
        name: int(found.group(1)) if (found := re.search(pattern, combined)) else 0
        for name, pattern in counters.items()
    }
    if any(parsed.values()):
        raise ValueError(
            "unittest non-pass outcomes: "
            + " ".join(f"{name}={value}" for name, value in parsed.items())
        )
    discovered = int(match.group(1))
    return {
        "discovered": discovered,
        "passed": discovered,
        "failures": 0,
        "errors": 0,
        **parsed,
    }


def publish_receipt(source: str | Path, target: str | Path, *, mode: str) -> None:
    source_path = _absolute(source)
    target_path = _absolute(target)
    if not _same_path(source_path.parent, target_path.parent):
        raise ValueError("receipt publication source and target must share a directory")
    if not source_path.is_file() or _is_reparse(source_path):
        raise ValueError("receipt publication source must be a regular non-reparse file")
    if mode == "FRESH_SYSTEM_TEMP_CHILD":
        os.link(source_path, target_path, follow_symlinks=False)
        source_path.unlink()
        return
    if mode == "REPOSITORY_DEFAULT_ATOMIC_REPLACE":
        if os.path.lexists(target_path) and _is_reparse(target_path):
            raise ValueError("default receipt target became a reparse point")
        os.replace(source_path, target_path)
        return
    raise ValueError(f"unknown receipt publication mode: {mode}")


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", type=Path)
    parser.add_argument("--default-receipt", type=Path)
    parser.add_argument("--receipt", type=Path)
    parser.add_argument("--temp-root", type=Path)
    parser.add_argument("--parse-unittest-json", type=Path)
    parser.add_argument("--publish-source", type=Path)
    parser.add_argument("--publish-target", type=Path)
    parser.add_argument(
        "--publish-mode",
        choices=("FRESH_SYSTEM_TEMP_CHILD", "REPOSITORY_DEFAULT_ATOMIC_REPLACE"),
    )
    args = parser.parse_args(argv)
    if args.parse_unittest_json is not None:
        if any(
            value is not None
            for value in (
                args.project_root, args.default_receipt, args.receipt, args.temp_root,
                args.publish_source, args.publish_target, args.publish_mode,
            )
        ):
            parser.error("--parse-unittest-json cannot be combined with receipt arguments")
        payload = json.loads(args.parse_unittest_json.read_text(encoding="utf-8"))
        result = parse_unittest_result(
            stdout=payload["stdout"],
            stderr=payload["stderr"],
            exit_code=payload["exitCode"],
        )
        print(json.dumps(result, sort_keys=True, separators=(",", ":")), end="")
        return 0
    if args.publish_source is not None or args.publish_target is not None or args.publish_mode is not None:
        if any(value is not None for value in (args.project_root, args.default_receipt, args.receipt, args.temp_root, args.parse_unittest_json)):
            parser.error("receipt publication cannot be combined with validation or parsing arguments")
        if args.publish_source is None or args.publish_target is None or args.publish_mode is None:
            parser.error("receipt publication requires source, target, and mode")
        publish_receipt(args.publish_source, args.publish_target, mode=args.publish_mode)
        print(_absolute(args.publish_target), end="")
        return 0
    if any(
        value is None
        for value in (args.project_root, args.default_receipt, args.receipt, args.temp_root)
    ):
        parser.error("receipt validation requires all four receipt arguments")
    target = validate_receipt_target(
        project_root=args.project_root,
        default_receipt=args.default_receipt,
        requested_receipt=args.receipt,
        temp_root=args.temp_root,
    )
    print(target, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())


__all__ = ["parse_unittest_result", "publish_receipt", "validate_receipt_target"]
