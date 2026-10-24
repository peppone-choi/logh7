"""Portable integrity and controller failure checks; never launch the game."""
from pathlib import Path
import tempfile
import unittest

from silent_audio import SilentAudio, runtime_manifest, verify_runtime_manifest


class SilentAudioIntegrityTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.exe = self.root / 'client' / 'G7MTClient.exe'
        self.exe.parent.mkdir()
        self.exe.write_bytes(b'fixture')
        self.script = self.root / 'adapter.js'
        self.script.write_bytes(b'fixture adapter')

    def test_copied_overlays_match_and_injected_changes_fail(self):
        wrapper = self.root / 'wrapper.dll'
        wrapper.write_bytes(b'wrapper')
        expected = runtime_manifest(self.exe, [self.script], {'app/d3d8.dll': wrapper})
        (self.exe.parent / 'd3d8.dll').write_bytes(wrapper.read_bytes())
        self.assertEqual(verify_runtime_manifest(self.exe, expected, [self.script]), expected)
        self.script.write_bytes(b'changed')
        with self.assertRaisesRegex(RuntimeError, 'mismatch'):
            verify_runtime_manifest(self.exe, expected, [self.script])

    def test_added_dependency_and_changed_executable_fail(self):
        expected = runtime_manifest(self.exe)
        extra = self.exe.parent / 'unexpected.dll'
        extra.write_bytes(b'extra')
        with self.assertRaisesRegex(RuntimeError, 'mismatch'):
            verify_runtime_manifest(self.exe, expected)
        extra.unlink()
        self.exe.write_bytes(b'changed')
        with self.assertRaisesRegex(RuntimeError, 'mismatch'):
            verify_runtime_manifest(self.exe, expected)

    def test_overlay_cannot_escape_runtime_directory(self):
        with self.assertRaisesRegex(RuntimeError, 'invalid runtime overlay'):
            runtime_manifest(self.exe, overlays={'app/../escape.dll': self.script})

    def test_unexpected_detach_stops_only_owned_target(self):
        guard = SilentAudio.__new__(SilentAudio)
        stopped = []
        guard.closing = False
        guard.on_failure = lambda: stopped.append('owned')
        guard._detached()
        self.assertEqual(stopped, ['owned'])
        with self.assertRaisesRegex(RuntimeError, 'detached'):
            guard.assert_ready()
        guard.closing = True
        guard._detached()
        self.assertEqual(stopped, ['owned'])


if __name__ == '__main__':
    unittest.main()
