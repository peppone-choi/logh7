"""작성: 최병호. Docker/네트워크를 기동하지 않는 smoke 제어 흐름 회귀 시험."""
import contextlib

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

from stack_smoke import Smoke


class SmokeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=os.environ.get('TMPDIR'))
        self.addCleanup(self.temp.cleanup)
        env = patch.dict(os.environ, {'COMPOSE_PROJECT_NAME': 'logh7-stack-ci-123-1',
                                     'RUNNER_TEMP': self.temp.name})
        env.start()
        self.addCleanup(env.stop)
        self.smoke = Smoke()

    def test_rejects_non_ci_project_before_docker(self):
        with patch.dict(os.environ, {'COMPOSE_PROJECT_NAME': 'logh7'}):
            with self.assertRaises(ValueError):
                Smoke()

    def test_prepare_uses_only_fresh_secret_files(self):
        with patch.object(self.smoke, 'command') as command:
            self.smoke.prepare()
        override = json.loads((self.smoke.directory / 'override.json').read_text())
        values = []
        for name, item in override['secrets'].items():
            path = Path(item['file'])
            self.assertEqual(path.parent, self.smoke.directory)
            values.append(path.read_text())
            self.assertEqual(len(values[-1].strip()), 64)
            if os.name == 'posix':
                self.assertEqual(path.stat().st_mode & 0o777, 0o640 if name == 'grafana_password' else 0o600)
        self.assertNotEqual(*values)
        if os.name == 'posix':
            self.assertEqual(self.smoke.directory.stat().st_mode & 0o777, 0o700)
        command.assert_called_once_with('sudo', 'chgrp', '0', str(self.smoke.directory / 'grafana_password'))

    def test_command_failure_does_not_expose_output(self):
        result = subprocess.CompletedProcess([], 1, 'synthetic-secret', 'synthetic-secret')
        with patch('stack_smoke.subprocess.run', return_value=result):
            with self.assertRaises(RuntimeError) as error:
                self.smoke.command('docker', 'compose')
        self.assertNotIn('synthetic-secret', str(error.exception))

    def test_wait_fails_on_readiness_timeout(self):
        check = Mock(side_effect=RuntimeError('not ready'))
        with patch('stack_smoke.time.sleep'):
            with self.assertRaisesRegex(RuntimeError, 'readiness timed out'):
                self.smoke.wait(check, 'test', attempts=2)
        self.assertEqual(check.call_count, 2)

    def test_wait_retries_and_succeeds(self):
        check = Mock(side_effect=[OSError(), None])
        with patch('stack_smoke.time.sleep'):
            self.smoke.wait(check, 'test', attempts=2)
        self.assertEqual(check.call_count, 2)

    def test_grafana_requires_database_ok(self):
        response = Mock(status=200)
        response.read.return_value = b'{"database":"failed"}'
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        with patch('stack_smoke.urllib.request.urlopen', return_value=response):
            with self.assertRaisesRegex(RuntimeError, 'database not ready'):
                self.smoke.http('http://127.0.0.1:3000/api/health', True)
            response.status = 503
            with self.assertRaisesRegex(RuntimeError, 'HTTP not ready'):
                self.smoke.http('http://127.0.0.1:3000/api/health')

    def run_mocked(self, sql_results, compose_failure=None):
        mocks = contextlib.ExitStack()
        self.addCleanup(mocks.close)
        mocks.enter_context(patch.object(self.smoke, 'prepare'))
        compose = mocks.enter_context(patch.object(self.smoke, 'compose', side_effect=compose_failure))
        mocks.enter_context(patch.object(self.smoke, 'ready'))
        mocks.enter_context(patch.object(self.smoke, 'wait', side_effect=lambda check, label: check()))
        mocks.enter_context(patch('stack_smoke.secrets.token_hex', return_value='marker'))
        mocks.enter_context(patch.object(self.smoke, 'sql', side_effect=sql_results))
        cleanup = mocks.enter_context(patch.object(self.smoke, 'cleanup'))
        return compose, cleanup

    def test_persistence_match_passes_after_restart(self):
        compose, cleanup = self.run_mocked(['', '', 'marker'])
        self.smoke.run()
        self.assertIn(unittest.mock.call('restart', 'postgres'), compose.call_args_list)
        cleanup.assert_called_once()

    def test_persistence_mismatch_fails_and_cleans_up(self):
        _, cleanup = self.run_mocked(['', '', 'lost'])
        with self.assertRaisesRegex(RuntimeError, 'persistence mismatch'):
            self.smoke.run()
        cleanup.assert_called_once()

    def test_up_failure_still_cleans_up(self):
        _, cleanup = self.run_mocked([], RuntimeError('up failed'))
        with self.assertRaisesRegex(RuntimeError, 'up failed'):
            self.smoke.run()
        cleanup.assert_called_once()

    def test_readiness_failure_still_cleans_up(self):
        _, cleanup = self.run_mocked([])
        with patch.object(self.smoke, 'ready', side_effect=RuntimeError('readiness failed')):
            with self.assertRaisesRegex(RuntimeError, 'readiness failed'):
                self.smoke.run()
        cleanup.assert_called_once()

    def test_cleanup_failure_overrides_success(self):
        self.run_mocked(['', '', 'marker'])
        with patch.object(self.smoke, 'cleanup', side_effect=RuntimeError('CLEANUP_FAILED')), patch('builtins.print') as output:
            with self.assertRaisesRegex(RuntimeError, 'CLEANUP_FAILED'):
                self.smoke.run()
        output.assert_not_called()

    def test_cleanup_rejects_remaining_resources(self):
        for outputs in (['', 'container-id'], ['', '', 'volume-id']):
            with self.subTest(outputs=outputs), patch.object(self.smoke, 'command', side_effect=outputs):
                with self.assertRaisesRegex(RuntimeError, 'CLEANUP_FAILED'):
                    self.smoke.cleanup()

    def test_cleanup_checks_both_labels_and_removes_secrets(self):
        self.smoke.directory.mkdir()
        (self.smoke.directory / 'override.json').write_text('{}')
        (self.smoke.directory / 'postgres_password').write_text('synthetic')
        with patch.object(self.smoke, 'compose') as compose, patch.object(self.smoke, 'command', return_value='') as command:
            self.smoke.cleanup()
        compose.assert_called_once_with('down', '-v', '--remove-orphans', '--timeout', '10')
        self.assertEqual(command.call_count, 2)
        for call in command.call_args_list:
            self.assertEqual(call.args[-1], 'label=com.docker.compose.project=logh7-stack-ci-123-1')
        self.assertFalse(self.smoke.directory.exists())

    def test_down_failure_retains_override_for_always_retry(self):
        self.smoke.directory.mkdir()
        override = self.smoke.directory / 'override.json'
        override.write_text('{}')
        with patch.object(self.smoke, 'compose', side_effect=RuntimeError('down failed')):
            with self.assertRaises(RuntimeError):
                self.smoke.cleanup()
        self.assertTrue(override.exists())


if __name__ == '__main__':
    unittest.main()
