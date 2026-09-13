from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from tools.exhaustive_trace.foundation_verification import (
    parse_unittest_result,
    publish_receipt,
    validate_receipt_target,
)


class FoundationVerificationPolicyTests(unittest.TestCase):
    def test_fresh_receipt_publish_never_replaces_a_racing_target(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / ".receipt.tmp"
            target = root / "receipt.json"
            source.write_bytes(b"verified\n")
            target.write_bytes(b"attacker\n")
            with self.assertRaises(FileExistsError):
                publish_receipt(source, target, mode="FRESH_SYSTEM_TEMP_CHILD")
            self.assertEqual(b"attacker\n", target.read_bytes())
            self.assertEqual(b"verified\n", source.read_bytes())

            target.unlink()
            publish_receipt(source, target, mode="FRESH_SYSTEM_TEMP_CHILD")
            self.assertEqual(b"verified\n", target.read_bytes())
            self.assertFalse(source.exists())

    def test_unittest_summary_requires_zero_skips_and_exact_ok(self) -> None:
        result = parse_unittest_result(
            stdout="...\n",
            stderr="Ran 3 tests in 0.010s\n\nOK\n",
            exit_code=0,
        )
        self.assertEqual(
            {
                "discovered": 3,
                "passed": 3,
                "failures": 0,
                "errors": 0,
                "skipped": 0,
                "expectedFailures": 0,
                "unexpectedSuccesses": 0,
            },
            result,
        )
        with self.assertRaisesRegex(ValueError, "skip|non-pass|exact"):
            parse_unittest_result(
                stdout="...s\n",
                stderr="Ran 3 tests in 0.010s\n\nOK (skipped=1)\n",
                exit_code=0,
            )

    def test_default_receipt_may_be_replaced_but_other_repository_file_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "project"
            evidence = root / "work" / "foundation" / "evidence"
            evidence.mkdir(parents=True)
            default = evidence / "foundation-verification.json"
            default.write_text("{}\n", encoding="utf-8")
            protected = root / "evidence" / "coverage.json"
            protected.parent.mkdir()
            protected.write_text("{}\n", encoding="utf-8")

            self.assertEqual(
                default.resolve(),
                validate_receipt_target(
                    project_root=root,
                    default_receipt=default,
                    requested_receipt=default,
                    temp_root=Path(temporary) / "system-temp",
                ),
            )
            with self.assertRaisesRegex(ValueError, "default|temporary|receipt"):
                validate_receipt_target(
                    project_root=root,
                    default_receipt=default,
                    requested_receipt=protected,
                    temp_root=Path(temporary) / "system-temp",
                )

    def test_explicit_receipt_must_be_a_fresh_direct_child_of_temp_root(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            root = base / "project"
            root.mkdir()
            temp_root = base / "system-temp"
            temp_root.mkdir()
            default = root / "foundation-verification.json"
            fresh = temp_root / "independent.json"
            self.assertEqual(
                fresh.resolve(),
                validate_receipt_target(
                    project_root=root,
                    default_receipt=default,
                    requested_receipt=fresh,
                    temp_root=temp_root,
                ),
            )

            existing = temp_root / "existing.json"
            existing.write_text("do not overwrite\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "fresh|exist"):
                validate_receipt_target(
                    project_root=root,
                    default_receipt=default,
                    requested_receipt=existing,
                    temp_root=temp_root,
                )

            nested = temp_root / "nested" / "receipt.json"
            nested.parent.mkdir()
            with self.assertRaisesRegex(ValueError, "direct child"):
                validate_receipt_target(
                    project_root=root,
                    default_receipt=default,
                    requested_receipt=nested,
                    temp_root=temp_root,
                )


if __name__ == "__main__":
    unittest.main()
