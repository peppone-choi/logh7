"""Regression tests: readiness is never product PASS and never launches a game."""
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    "preflight", Path(__file__).with_name("windows-qa-preflight.py"))
assert spec is not None and spec.loader is not None
preflight = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preflight)


class PreflightTest(unittest.TestCase):
    def test_windows_cp949_stdout_preserves_original_unicode_path(self):
        report = {"sourcePath": "E:/original/ｱﾌﾟﾘｹｰｼｮﾝ/exe/G7MTClient.exe"}
        data = io.BytesIO()
        with io.TextIOWrapper(data, encoding="cp949") as output:
            with patch.object(preflight.sys, "stdout", output):
                preflight.print_report(report)
            output.flush()
            self.assertEqual(json.loads(data.getvalue().decode("cp949")), report)

    def test_evidence_cannot_escape_work_or_use_work_root(self):
        with tempfile.TemporaryDirectory() as root:
            repo = Path(root)
            for output in (repo, repo / "work", repo / "work" / ".." / "original"):
                with self.assertRaises(ValueError):
                    preflight.evidence_directory(repo, output)
            self.assertEqual(preflight.evidence_directory(repo, repo / "work" / "task"),
                             (repo / "work" / "task").resolve())

    def test_wrapper_failure_is_preserved_not_pass(self):
        report = preflight.product_report("pc", {"returncode": 1,
            "stderr": "[WinError 1]", "report": None}, [])
        self.assertEqual(report["platforms"]["windows"]["status"], "NOT_RUN")
        self.assertFalse(report["isolationVerified"])
        self.assertFalse(report["silentVerified"])
        self.assertFalse(report["cleanupVerified"])
        self.assertFalse(report["gamesExecuted"])
        self.assertIn("[WinError 1]", json.dumps(report))

    def test_readiness_pass_cannot_become_product_pass(self):
        report = preflight.product_report("pc", {"returncode": 0,
            "report": {"status": "PASS", "cleanup_verified": True}}, [])
        self.assertEqual(report["platforms"]["windows"]["status"], "NOT_RUN")
        self.assertFalse(report["cleanupVerified"])

    def test_unrecognized_wrapper_is_not_executed(self):
        with tempfile.TemporaryDirectory() as root:
            wrapper = Path(root) / "wrapper.py"
            wrapper.write_text("raise RuntimeError('must not execute')")
            with patch.object(preflight.subprocess, "run") as run:
                result = preflight.wrapper_readiness(wrapper, Path(root) / "readiness.json")
            run.assert_not_called()
            self.assertEqual(result["status"], "UNREVIEWED_WRAPPER")

    def test_pinned_wrapper_has_no_launcher_command(self):
        with tempfile.TemporaryDirectory() as root:
            output = Path(root) / "readiness.json"
            def fake_run(command, **kwargs):
                self.assertEqual(command[2:], [str(Path(root) / "wrapper.py"),
                    "--kind", "logh", "--project", "logh", "--report", str(output)])
                self.assertNotIn("run-background.py", command)
                output.write_text('{"status":"WAITING_FOR_BACKGROUND_QA"}')
                return subprocess.CompletedProcess(command, 3, "", "")
            with patch.object(preflight, "sha256", return_value=preflight.WRAPPER_SHA256), \
                    patch.object(preflight.subprocess, "run", side_effect=fake_run):
                result = preflight.wrapper_readiness(Path(root) / "wrapper.py", output)
            self.assertEqual(result["returncode"], 3)
            self.assertEqual(result["report"]["status"], "WAITING_FOR_BACKGROUND_QA")

    def test_timeout_is_not_success(self):
        with patch.object(preflight, "sha256", return_value=preflight.WRAPPER_SHA256), \
                patch.object(preflight.subprocess, "run", side_effect=subprocess.TimeoutExpired("wrapper", 20)):
            result = preflight.wrapper_readiness(Path("wrapper.py"), Path("missing.json"))
        self.assertEqual(result["status"], "WRAPPER_TIMEOUT")


if __name__ == "__main__":
    unittest.main()
