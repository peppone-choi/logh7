"""Synthetic adapter tests only: no Windows, fixtures or product execution."""
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location('qa_product_report', HERE / 'qa_product_report.py')
assert SPEC is not None and SPEC.loader is not None
qa = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(qa)


class ProductReportTests(unittest.TestCase):
    def test_every_runtime_status_is_nonpassing(self):
        for status in ('PASS', 'PRODUCT_OBSERVED', 'WAIT_USER_GAME_OR_STEAM',
                       'TIMEOUT', 'FAIL', 'CLEANUP_FAILED', 'CANCELLED'):
            with self.subTest(status=status):
                report = qa.product_report('device', 'a' * 40, {'status': status}, 'raw.json')
                self.assertNotEqual(report['platforms']['windows']['status'], 'PASS')
                self.assertFalse(report['product_pass'])
                self.assertIsNone(report['windows_qa_receipt'])
                self.assertEqual(len(report['platforms']['windows']['checks']), 4)
                self.assertTrue(all(c['status'] == 'NOT_RUN' for c in report['platforms']['windows']['checks']))

    def test_forged_fixture_or_product_pass_does_not_promote(self):
        raw = {'status': 'PASS', 'product_pass': True, 'product_qa': 'PASS',
               'games_executed': True, 'isolationVerified': True, 'silentVerified': True,
               'cleanup_verified': True, 'platforms': {'windows': {'status': 'PASS'}}}
        report = qa.product_report('device', 'a' * 40, raw, 'raw.json')
        self.assertEqual(report['productQA'], 'NOT_RUN')
        for flag in ('isolationVerified', 'silentVerified', 'cleanupVerified', 'product_pass'):
            self.assertIs(report[flag], False)

    def test_cleanup_failure_is_failure_not_wait(self):
        report = qa.product_report('device', 'a' * 40, {'status': 'CLEANUP_FAILED'}, 'raw.json')
        self.assertEqual(report['platforms']['windows']['status'], 'FAIL')

    def test_report_requires_object_and_known_status(self):
        for value in ([], {}, {'status': []}, {'status': 'NEW_UNKNOWN_STATUS'}):
            with self.subTest(value=value), self.assertRaises(ValueError):
                qa.product_report('device', 'a' * 40, value, 'raw.json')

    def test_pinned_bytes_and_duplicate_keys(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'raw.json'
            path.write_bytes(b'{"status":"PASS"}')
            pin = hashlib.sha256(path.read_bytes()).hexdigest()
            self.assertEqual(qa.read_runtime(path, pin)['status'], 'PASS')
            with self.assertRaises(ValueError):
                qa.read_runtime(path, '0' * 64)
            path.write_bytes(b'{"status":"FAIL","status":"PASS"}')
            with self.assertRaises(ValueError):
                qa.read_runtime(path, hashlib.sha256(path.read_bytes()).hexdigest())

    def test_checkout_wrong_sha_or_dirty_rejected(self):
        for actual, dirty in (('b' * 40, ''), ('a' * 40, ' M file')):
            with self.subTest(actual=actual, dirty=dirty), self.assertRaises(ValueError):
                qa.validate_checkout('a' * 40, actual, dirty)

    def test_checkout_clean_exact(self):
        qa.validate_checkout('a' * 40, 'a' * 40, '')

    def test_cli_is_single_json_and_nonzero_without_evidence(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = subprocess.run([sys.executable, str(HERE / 'qa_product_report.py'),
                                     '--repo', tmp, '--head', 'a' * 40,
                                     '--out', str(Path(tmp) / 'report.json')],
                                    text=True, capture_output=True)
            self.assertEqual(result.returncode, 3, result.stderr)
            report = json.loads(result.stdout)
            self.assertEqual(report['platforms']['windows']['status'], 'NOT_RUN')
            self.assertFalse(report['runtimeExecutedByAdapter'])
            self.assertEqual(json.loads((Path(tmp) / 'report.json').read_text()), report)

    def test_existing_evidence_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / 'report.json'
            output.write_text('preserve')
            result = subprocess.run([sys.executable, str(HERE / 'qa_product_report.py'),
                                     '--repo', tmp, '--head', 'a' * 40, '--out', str(output)],
                                    text=True, capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(output.read_text(), 'preserve')
            self.assertNotEqual(json.loads(result.stdout)['platforms']['windows']['status'], 'PASS')

    def test_cli_pinned_report_through_real_clean_checkout(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            repo = root / 'repo'
            repo.mkdir()
            def git(*args):
                return subprocess.run(['git', '-C', str(repo), *args], check=True,
                                      capture_output=True, text=True).stdout.strip()
            git('init')
            git('-c', 'user.name=Adapter Test', '-c', 'user.email=adapter@example.invalid',
                'commit', '--allow-empty', '-m', 'Synthetic adapter test')
            head = git('rev-parse', 'HEAD')
            raw = root / 'raw.json'
            raw.write_text(json.dumps({'status': 'PASS', 'product_pass': True}))
            pin = hashlib.sha256(raw.read_bytes()).hexdigest()
            output = root / 'report.json'
            result = subprocess.run([sys.executable, str(HERE / 'qa_product_report.py'),
                                     '--repo', str(repo), '--head', head,
                                     '--runtime-report', str(raw), '--runtime-report-sha256', pin,
                                     '--out', str(output)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 3, result.stderr)
            report = json.loads(result.stdout)
            self.assertEqual(report['runtimeStatus'], 'PASS')
            self.assertEqual(report['platforms']['windows']['status'], 'NOT_RUN')
            self.assertFalse(report['runtimeHeadProvenanceVerified'])
            self.assertEqual(report['runtimeEvidenceSha256'], pin)
            self.assertEqual(json.loads(output.read_text()), report)
            self.assertEqual(git('status', '--porcelain'), '')

    def test_cli_invalid_sha_and_unpaired_evidence_fail_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            for index, extra in enumerate((['--head', 'invalid'],
                                           ['--head', 'a' * 40, '--runtime-report', 'missing'])):
                result = subprocess.run([sys.executable, str(HERE / 'qa_product_report.py'),
                                         '--repo', tmp, '--out', str(Path(tmp) / f'{index}.json'),
                                         *extra], capture_output=True, text=True)
                self.assertEqual(result.returncode, 2, result.stderr)
                self.assertEqual(json.loads(result.stdout)['platforms']['windows']['status'], 'FAIL')


if __name__ == '__main__':
    unittest.main()
