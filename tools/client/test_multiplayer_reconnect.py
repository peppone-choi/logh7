"""Reconnect cannot reuse a live/unclean client or overwrite evidence. Author: Peppone Choi."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('multiplayer', Path(__file__).with_name('run-multiplayer.py'))
multiplayer = importlib.util.module_from_spec(spec); spec.loader.exec_module(multiplayer)


def cleaned(**changes):
    return dict(success=True, process_stopped=True, job_active_after_cleanup=0,
                source_hash_unchanged=True, reconnect_requested=True) | changes


class FakeBackground:
    def __init__(self, reports, code=0): self.reports=iter(reports); self.paths=[]; self.code=code
    def run(self, source, destination, seconds, **options):
        self.paths.append(destination)
        destination.mkdir()
        (destination/'run.json').write_text(json.dumps(next(self.reports)), encoding='utf-8')
        return self.code


class MultiplayerReconnectTest(unittest.TestCase):
    def setUp(self):
        work=Path(__file__).resolve().parents[2]/'work'
        self.temporary=tempfile.TemporaryDirectory(prefix='reconnect-test-',dir=work)
        self.addCleanup(self.temporary.cleanup)
        self.out=Path(self.temporary.name)
    def run_client(self, background, allowance=1):
        return multiplayer.run_client(background, Path('original'), Path('copy'), 'multi0',
            self.out, 0, 60, 47900, object(), allowance)
    def test_reconnect_uses_fresh_evidence_and_stops_without_another_request(self):
        background=FakeBackground([cleaned(),cleaned(reconnect_requested=False)])
        self.assertEqual(0,self.run_client(background))
        self.assertEqual(['client-0','client-0-reconnect-1'],[p.name for p in background.paths])
        self.assertTrue((background.paths[0]/'run.json').is_file())
    def test_plain_stop_does_not_restart(self):
        background=FakeBackground([cleaned(reconnect_requested=False)])
        self.assertEqual(0,self.run_client(background));self.assertEqual(1,len(background.paths))
    def test_incomplete_cleanup_or_hash_never_relaunches(self):
        cases=[dict(success=False),dict(process_stopped=False),dict(job_active_after_cleanup=1),
               dict(job_active_after_cleanup=False),dict(source_hash_unchanged=False),dict(job_active_after_cleanup=None)]
        root=self.out
        for index,changes in enumerate(cases):
            with self.subTest(changes=changes):
                self.out=root/str(index);self.out.mkdir()
                background=FakeBackground([cleaned(**changes)])
                with self.assertRaisesRegex(RuntimeError,'cleanup'): self.run_client(background)
                self.assertEqual(1,len(background.paths))
    def test_failed_attempt_is_not_retried(self):
        background=FakeBackground([cleaned()],code=2)
        self.assertEqual(2,self.run_client(background));self.assertEqual(1,len(background.paths))
    def test_no_allowance_and_exhausted_allowance_fail(self):
        background=FakeBackground([cleaned()])
        with self.assertRaisesRegex(RuntimeError,'allowance'): self.run_client(background,0)
        self.out=self.out/'exhausted';self.out.mkdir()
        background=FakeBackground([cleaned(),cleaned()])
        with self.assertRaisesRegex(RuntimeError,'allowance'): self.run_client(background)
        self.assertEqual(2,len(background.paths))


if __name__=='__main__':unittest.main()
