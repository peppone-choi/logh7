"""Synthetic policy/lifecycle regression. Does NOT execute Windows/JVM/client."""
import json
import importlib.util
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, patch

import qa_upstream as q


class UpstreamTests(unittest.TestCase):
    def test_prepare_manifest_accepts_exact_closure_and_rejects_tamper(self):
        spec = importlib.util.spec_from_file_location('prepare_upstream', Path(q.__file__).with_name('prepare-upstream-runtime.py'))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            lib = root / 'distribution/server/app/build/install/app/lib'
            lib.mkdir(parents=True)
            (lib / 'app.jar').write_bytes(b'not a product fixture')
            jdk = root / 'jdk'
            (jdk / 'legal/java.base').mkdir(parents=True)
            (jdk / 'legal/java.base/LICENSE').write_text('test license')
            (jdk / 'release').write_text('JAVA_VERSION="25.0.1"\n')
            for name in ('jdk25.zip', 'distribution.zip'):
                (root / name).write_bytes(b'package test only')
            sha = q.digest(root / 'jdk25.zip')
            provenance = dict(head='a' * 40, run_id='1', repository='peppone-choi/logh7',
                build=':app:installDist', files=q.inventory(lib.parent))
            (root / 'distribution/distribution-provenance.json').write_text(json.dumps(provenance))
            args = (root, 'a' * 40, '1', '2', sha,
                'https://github.com/adoptium/temurin25-binaries/releases/download/test', sha)
            self.assertFalse(module.prepare(*args)['environment_ready'])
            (lib / 'app.jar').write_bytes(b'tampered')
            with self.assertRaisesRegex(ValueError, 'provenance'):
                module.prepare(*args)
            (jdk / 'release').write_text('JAVA_VERSION="250.0.1"\n')
            with self.assertRaisesRegex(ValueError, 'JDK25'):
                module.prepare(*args)

    def test_environment_drops_user_options_and_state(self):
        with patch.dict(os.environ, {'JAVA_TOOL_OPTIONS': '-javaagent:evil',
                'JDK_JAVA_OPTIONS': 'evil', 'CLASSPATH': 'evil',
                'LOGH7_ACCOUNTS_FILE': 'user-secret', 'LOGH7_SESSION_RULES': 'user-rules'}, clear=True):
            env = q.environment(Path('private'), Path('run'))
        self.assertNotIn('JAVA_TOOL_OPTIONS', env)
        self.assertNotIn('JDK_JAVA_OPTIONS', env)
        self.assertNotIn('LOGH7_ACCOUNTS_FILE', env)
        self.assertNotIn('LOGH7_SESSION_RULES', env)
        self.assertEqual(env['LOGH7_CAPTURE_DIR'], str(Path('run/capture')))
        self.assertEqual(env['LOGH7_BIND_ADDRESS'], '127.0.0.1')

    def test_command_is_direct_main_with_private_state(self):
        argv = q.command(Path('runtime'), Path('run'))
        self.assertEqual(argv[0], str(Path('runtime/jdk/bin/java.exe')))
        self.assertEqual(argv[-1], q.MAIN)
        self.assertIn('-Djava.awt.headless=true', argv)
        self.assertIn('-Duser.home=run/state', argv)
        self.assertNotIn('cmd.exe', argv)

    def test_inventory_detects_extra_modified_and_symlink(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / 'a').write_bytes(b'a')
            before = q.inventory(root)
            (root / 'a').write_bytes(b'b')
            self.assertNotEqual(before, q.inventory(root))
            (root / 'extra').write_bytes(b'')
            self.assertEqual(set(q.inventory(root)), {'a', 'extra'})
            (root / 'link').symlink_to(root / 'a')
            with self.assertRaisesRegex(ValueError, 'Reparse'):
                q.inventory(root)

    def test_external_pin_refused_before_windows_or_git(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / 'manifest'
            p.write_text('{}')
            module = SimpleNamespace(_plain_path=lambda _: p)
            with patch.dict('sys.modules', {'qa_prestart_audio': module}):
                with self.assertRaisesRegex(ValueError, 'external pin'):
                    q.validate(SimpleNamespace(upstream_manifest=p, upstream_manifest_sha256='0' * 64))

    def lifetime(self, fault=None):
        with tempfile.TemporaryDirectory() as d:
            args = SimpleNamespace(upstream_manifest_sha256='f' * 64, cancel_file=Path(d) / 'cancel')
            process, thread, job = MagicMock(), MagicMock(), MagicMock()
            wp, wa, we, wf, wj, wc = (MagicMock() for _ in range(6))
            wp.CreateProcess.return_value = (process, thread, 123, 456)
            wp.GetProcessTimes.return_value = {'CreationTime': 'created'}
            wa.GlobalMemoryStatus.return_value = {'AvailPhys': 4 * 1024 ** 3}
            we.WaitForSingleObject.side_effect = lambda handle, timeout: 0 if timeout == 5000 else 258
            wj.CreateJobObject.return_value = job
            wj.IsProcessInJob.return_value = True
            wj.QueryInformationJobObject.return_value = {'ActiveProcesses': 0}
            modules = dict(win32api=wa, win32con=wc, win32event=we, win32file=wf,
                           win32job=wj, win32process=wp, pywintypes=MagicMock())
            mutex = MagicMock()
            mutex.acquire.return_value = mutex
            client = MagicMock(return_value=dict(status='PRODUCT_WINDOW_COMPLETE', cleanup_verified=True))
            if fault == 'client':
                client.side_effect = RuntimeError('client error')
            if fault == 'assign':
                wj.AssignProcessToJobObject.side_effect = RuntimeError('assign failed')
            if fault == 'resume':
                wp.ResumeThread.side_effect = RuntimeError('resume failed')
            if fault == 'cleanup':
                wj.QueryInformationJobObject.return_value = {'ActiveProcesses': 1}
                we.WaitForSingleObject.return_value = 258
                we.WaitForSingleObject.side_effect = None
            if fault == 'wait':
                wa.GlobalMemoryStatus.return_value = {'AvailPhys': 1}
            if fault == 'cancel':
                args.cancel_file.touch()
            with patch.dict('sys.modules', modules), \
                    patch.object(q, 'validate', return_value=({}, Path(d))), \
                    patch.object(q, 'reserve_check'), \
                    patch.object(q, 'listener_owners', return_value={('127.0.0.1', p): 123 for p in q.PORTS}), \
                    patch('qa_isolated_runner.snapshot', return_value={}), \
                    patch('qa_isolated_runner.isolated', return_value=True), \
                    patch('qa_prestart_runner.session_ids', return_value=[0, 1]), \
                    patch('qa_prestart_runner.protected_processes', return_value=[]), \
                    patch('qa_prestart_runner.job_policy'), \
                    patch('qa_session_mutex.SessionMutex', return_value=mutex), \
                    patch.object(q.threading, 'Timer') as timer, \
                    patch.object(q.time, 'monotonic', side_effect=range(1000)):
                result = q.run(args, client)
            if fault != 'wait':
                process.Close.assert_called_once()
                job.Close.assert_called_once()
                wj.TerminateJobObject.assert_called_once_with(job, 2)
                if fault == 'assign':
                    wp.ResumeThread.assert_not_called()
                    wa.TerminateProcess.assert_called_once_with(process, 2)
                else:
                    wa.TerminateProcess.assert_not_called()
                timer.return_value.cancel.assert_called_once() if fault != 'assign' else None
            else:
                wp.CreateProcess.assert_not_called()
            mutex.close.assert_called_once()
            return result, client

    def test_success_keeps_client_verdict_and_independent_cleanup(self):
        report, client = self.lifetime()
        client.assert_called_once_with()
        self.assertEqual(report['status'], 'PRODUCT_WINDOW_COMPLETE')
        self.assertTrue(report['upstream']['cleanup_verified'])
        self.assertEqual(report['upstream']['creation_time'], 'created')
        self.assertEqual(report['upstream']['active_processes_after'], 0)

    def test_assignment_failure_stops_only_held_suspended_root(self):
        result, client = self.lifetime('assign')
        client.assert_not_called()
        self.assertEqual(result['status'], 'FAIL')
        self.assertTrue(result['upstream']['cleanup_verified'])

    def test_resume_exception_cleans_job(self):
        result, client = self.lifetime('resume')
        client.assert_not_called()
        self.assertEqual(result['status'], 'FAIL')
        self.assertTrue(result['upstream']['cleanup_verified'])

    def test_client_exception_cleans_job(self):
        result, _ = self.lifetime('client')
        self.assertEqual(result['status'], 'FAIL')
        self.assertTrue(result['upstream']['cleanup_verified'])

    def test_cleanup_failure_cannot_pass(self):
        result, _ = self.lifetime('cleanup')
        self.assertEqual(result['status'], 'CLEANUP_FAILED')
        self.assertFalse(result['cleanup_verified'])

    def test_low_memory_never_launches(self):
        result, client = self.lifetime('wait')
        self.assertEqual(result['status'], 'WAIT_LOW_MEMORY')
        client.assert_not_called()

    def test_cancel_during_startup_cleans_job(self):
        result, client = self.lifetime('cancel')
        self.assertEqual(result['status'], 'FAIL')
        client.assert_not_called()
        self.assertTrue(result['upstream']['cleanup_verified'])


if __name__ == '__main__':
    unittest.main()
