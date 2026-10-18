"""Collect LOGH Windows readiness evidence without launching the client.

Run with hermes-windows exec, NOT qa: this cannot issue a product PASS.
The only executable wrapper accepted is the reviewed, unconditional LOGH gate.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys

WRAPPER_SHA256 = "818595eda6b666a6ab0df68f8fcc4793bdf7f095e79d6dfb6260f22c92507869"
AUDIO_GUARD_SHA256 = "3353e18184541be0566460993693888872a35c14206128c7ce4a120071890ee3"
# The pinned wrapper imports from this fixed path, independent of --repo.
AUDIO_GUARD_PATH = Path("E:/logh7/tools/client/audio_guard.py")
TARGET_SHA256 = "bd19263c10decc3d58373165a82d42a9267868400d407da87d5f4f4109ab6e16"


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def wrapper_readiness(wrapper, output):
    # This exact wrapper returns before creating any process for kind=logh.
    # Changed bytes require renewed review, never auto-accept a newer launcher.
    digest = sha256(wrapper)
    if digest != WRAPPER_SHA256:
        return {"status": "UNREVIEWED_WRAPPER", "sha256": digest}
    guard = {"path": str(AUDIO_GUARD_PATH), "sha256": None}
    try:
        guard["sha256"] = sha256(AUDIO_GUARD_PATH)
    except OSError as error:
        guard["error"] = f"{type(error).__name__}: {error}"
    if guard["sha256"] != AUDIO_GUARD_SHA256:
        return {"status": "UNREVIEWED_AUDIO_GUARD", "sha256": digest, "audioGuard": guard}
    if output.exists():
        raise ValueError("Refusing to overwrite readiness evidence")
    try:
        result = subprocess.run(
            [sys.executable, "-B", str(wrapper), "--kind", "logh", "--project", "logh",
             "--report", str(output)], capture_output=True, text=True, timeout=20)
    except subprocess.TimeoutExpired:
        return {"status": "WRAPPER_TIMEOUT", "sha256": digest, "audioGuard": guard}
    return {"sha256": digest, "audioGuard": guard, "returncode": result.returncode,
            "stdout": result.stdout, "stderr": result.stderr,
            "report": json.loads(output.read_text(encoding="utf-8")) if output.exists() else None}


def checkout_readiness(repo_head, requested_head, status, inspect_only, wrapper, output):
    if inspect_only:
        return {"status": "INSPECTION_ONLY", "wrapperExecuted": False}
    if repo_head != requested_head or status:
        return {"status": "CHECKOUT_REJECTED", "wrapperExecuted": False}
    return wrapper_readiness(wrapper, output)


def product_report(device, readiness, blockers):
    return {"platforms": {"windows": {"status": "NOT_RUN", "deviceId": device,
                "checks": [{"name": "original-client MPS interoperability",
                            "status": "NOT_RUN", "evidence": "No client launched by readiness adapter"}]}},
            "isolationVerified": False, "silentVerified": False, "cleanupVerified": False,
            "gamesExecuted": False, "productQA": "NOT_RUN", "blockers": blockers,
            "readiness": readiness}


def git(repo, *args):
    result = subprocess.run(["git", "-c", "core.fsmonitor=false", "-C", str(repo), *args],
                            capture_output=True, text=True, timeout=20)
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or "git inspection failed")
    return result.stdout.strip()


def evidence_directory(repo, output):
    work = (repo / "work").resolve()
    output = output.resolve()
    if output == work or not output.is_relative_to(work):
        raise ValueError("Evidence must be a new subdirectory under repository work/")
    return output


def print_report(report):
    # Windows SSH Python may still use CP949; JSON escapes preserve every path.
    print(json.dumps(report, ensure_ascii=True))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", "--windows-repo", dest="repo", type=Path, required=True)
    parser.add_argument("--head", required=True)
    parser.add_argument("--source-exe", type=Path, required=True)
    parser.add_argument("--owned-wrapper", type=Path)
    parser.add_argument("--inspect-only", action="store_true",
                        help="Inspect exact-head copy only; do not execute any wrapper/import")
    parser.add_argument("--out", type=Path, required=True, help="New task-owned evidence directory")
    args = parser.parse_args()
    if not args.inspect_only and args.owned_wrapper is None:
        parser.error("--owned-wrapper is required unless --inspect-only")
    if not re.fullmatch(r"[0-9a-f]{40}", args.head):
        parser.error("--head must be an exact lowercase 40-character Git SHA")
    if os.name != "nt":
        parser.error("Readiness collection requires native Windows; unit tests work elsewhere")
    # Fail if evidence already exists; never modify the repository or original.
    args.out = evidence_directory(args.repo, args.out)
    args.out.mkdir(parents=True, exist_ok=False)
    blockers = ["Prestart silence/whole-target audio coverage has no approval; GUI launch prohibited"]
    report = product_report(socket.gethostname(), None, blockers)
    report.update(requestedHead=args.head, adapterSha256=sha256(Path(__file__)))
    try:
        before = git(args.repo, "rev-parse", "HEAD")
        status_before = git(args.repo, "status", "--porcelain=v1", "--untracked-files=normal")
        report.update(windowsHeadBefore=before, windowsStatusBefore=status_before,
                      sourcePath=str(args.source_exe), sourceSha256Before=sha256(args.source_exe))
        if before != args.head:
            blockers.append("Windows checkout SHA does not match requested head")
        if status_before:
            blockers.append("Windows checkout has local changes; do not reset or overwrite")
        if report["sourceSha256Before"] != TARGET_SHA256:
            blockers.append("Original executable hash differs from reviewed CD client")
        report["readiness"] = checkout_readiness(before, args.head, status_before,
            args.inspect_only, args.owned_wrapper, args.out / "owned-readiness.json")
        report.update(windowsHeadAfter=git(args.repo, "rev-parse", "HEAD"),
                      windowsStatusAfter=git(args.repo, "status", "--porcelain=v1", "--untracked-files=normal"),
                      sourceSha256After=sha256(args.source_exe))
        if (report["windowsHeadAfter"] != before
                or report["windowsStatusAfter"] != status_before
                or report["sourceSha256After"] != report["sourceSha256Before"]):
            blockers.append("Repository or original changed during readiness collection")
        blockers.append("Existing wrapper readiness is diagnostic only, never product PASS")
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
        blockers.append(f"Readiness collection failed: {type(error).__name__}: {error}")
    report["evidencePath"] = str(args.out / "preflight.json")
    (args.out / "preflight.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n",
                                           encoding="utf-8")
    print_report(report)
    return 3  # NOT_RUN is deliberately nonzero, including when all inspection succeeds.


if __name__ == "__main__":
    raise SystemExit(main())
