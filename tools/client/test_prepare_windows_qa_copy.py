"""Bounded real-Git regression tests for immutable originals and fail-closed copies."""
import importlib.util
from pathlib import Path

import tempfile
import unittest

spec = importlib.util.spec_from_file_location('copy_qa', Path(__file__).with_name('prepare-windows-qa-copy.py'))
assert spec is not None and spec.loader is not None
copy_qa = importlib.util.module_from_spec(spec)
spec.loader.exec_module(copy_qa)


class CopyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.original = self.root / 'original'
        self.original.mkdir()
        copy_qa.git(self.original, 'init', '--template=')
        (self.original / 'tracked.txt').write_text('reviewed\n')
        copy_qa.git(self.original, 'add', 'tracked.txt')
        copy_qa.git(self.original, '-c', 'user.name=QA', '-c', 'user.email=qa@example.invalid',
                    'commit', '-m', 'fixture')
        self.head = copy_qa.git(self.original, 'rev-parse', 'HEAD').decode().strip()
        self.bundle = self.root / 'test.bundle'
        copy_qa.git(self.original, 'bundle', 'create', str(self.bundle), 'HEAD')
        (self.original / 'tracked.txt').write_text('user edits\n')
        (self.original / 'untracked.py').write_text("raise RuntimeError('NEVER execute or copy')\n")
        self.qa = self.root / 'qa'
        self.dest = self.qa / self.head

    def prepare(self, **kwargs):
        args = dict(original=self.original, destination=self.dest, head=self.head,
                    root=self.qa, bundle=self.bundle, bundle_sha256=copy_qa.digest(self.bundle),
                    inventory=lambda: [{'pid': 1, 'name': 'steam', 'created': 'fixture'}])
        args.update(kwargs)
        return copy_qa.prepare(**args)

    def test_real_bundle_copy_preserves_dirty_original(self):
        before = copy_qa.snapshot(self.original)
        report = self.prepare()
        self.assertEqual(report['status'], 'COPY_READY')
        self.assertEqual(report['copyHead'], self.head)
        self.assertEqual(report['copyStatus'], '')
        self.assertEqual(copy_qa.snapshot(self.original), before)
        self.assertTrue(report['originalUnchanged'])
        self.assertTrue(report['userProcessesPreserved'])
        self.assertFalse((self.dest / 'untracked.py').exists())
        self.assertEqual((self.dest / 'tracked.txt').read_text(), 'reviewed\n')
        self.assertEqual(report['productQA'], 'NOT_RUN')

    def test_existing_even_empty_destination_rejected(self):
        self.dest.mkdir(parents=True)
        with self.assertRaises(FileExistsError):
            self.prepare()
        self.assertEqual(list(self.dest.iterdir()), [])

    def test_original_or_nested_or_wrong_destination_rejected(self):
        for dest in (self.original, self.original / self.head, self.qa / 'wrong', self.root):
            with self.subTest(dest=dest), self.assertRaises(ValueError):
                self.prepare(destination=dest)

    def test_malformed_sha_rejected_before_destination_creation(self):
        for head in ('HEAD', self.head.upper(), self.head[:-1], 'g' * 40):
            with self.subTest(head=head), self.assertRaises(ValueError):
                self.prepare(head=head)
        self.assertFalse(self.dest.exists())

    def test_bad_bundle_hash_rejected_without_writes(self):
        with self.assertRaises(ValueError):
            self.prepare(bundle_sha256='0' * 64)
        self.assertFalse(self.dest.exists())

    def test_explicit_shallow_boundary_is_required_for_truncated_bundle(self):
        copy_qa.git(self.original, '-c', 'user.name=QA', '-c', 'user.email=qa@example.invalid',
                    'commit', '--allow-empty', '-m', 'child')
        head = copy_qa.git(self.original, 'rev-parse', 'HEAD').decode().strip()
        (self.original / '.git' / 'shallow').write_text(head + '\n')
        bundle = self.root / 'shallow.bundle'
        copy_qa.git(self.original, 'bundle', 'create', str(bundle), 'HEAD')
        failed = self.prepare(head=head, destination=self.qa / 'missing-boundary' / head,
                              root=self.qa / 'missing-boundary',
                              bundle=bundle, bundle_sha256=copy_qa.digest(bundle))
        self.assertEqual(failed['status'], 'FAIL')
        report = self.prepare(head=head, destination=self.qa / head,
                              bundle=bundle, bundle_sha256=copy_qa.digest(bundle),
                              shallow_boundaries=[head])
        self.assertEqual(report['status'], 'COPY_READY')
        self.assertEqual(report['copyHead'], head)
        self.assertTrue(report['originalUnchanged'])
        self.assertEqual(report['shallowBoundaries'], [head])

    def test_malformed_boundary_rejected_before_writes(self):
        with self.assertRaises(ValueError):
            self.prepare(shallow_boundaries=['HEAD'])
        self.assertFalse(self.dest.exists())

    def test_nonexistent_commit_refused_and_failure_not_reused(self):
        head = '1' * 40
        report = self.prepare(head=head, destination=self.qa / head)
        self.assertEqual(report['status'], 'FAIL')
        self.assertIn('differs', report['error'])
        self.assertTrue(report['originalUnchanged'])
        with self.assertRaises(FileExistsError):
            self.prepare(head=head, destination=self.qa / head)

    def test_disappearing_user_process_is_not_preserved(self):
        values = iter([[{'pid': 1, 'name': 'steam'}], []])
        report = self.prepare(inventory=lambda: next(values))
        self.assertEqual(report['status'], 'FAIL')
        self.assertFalse(report['userProcessesPreserved'])

    def test_dirty_symlink_outside_original_refused(self):
        outside = self.root / 'outside'
        outside.write_text('do not follow')
        try:
            (self.original / 'link').symlink_to(outside)
        except OSError:
            self.skipTest('symlinks unavailable')
        with self.assertRaises(ValueError):
            self.prepare()
        self.assertFalse(self.dest.exists())


if __name__ == '__main__':
    unittest.main()
