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
    def test_inventory_refuses_junction_attributes_without_following_stat(self):
        entry = MagicMock()
        entry.is_symlink.return_value = False
        entry.lstat.return_value = SimpleNamespace(st_file_attributes=0x400)
        root = MagicMock()
        root.resolve.return_value = root
        root.rglob.return_value = [entry]
        with patch.object(q, 'Path', return_value=root):
            with self.assertRaisesRegex(ValueError, 'Reparse'):
                q.inventory('synthetic-root')
        entry.stat.assert_not_called()

    def test_validate_rechecks_provenance_repository(self):
        runtime = Path('E:/HermesQA/logh-runtime/test')
        head = 'a' * 40
        data = dict(head=head, main=q.MAIN, runtime=str(runtime),
                    jdk=dict(files={}), distribution=dict(files={}, run_id='1'))
        provenance = dict(head=head, build=':app:installDist', run_id='1', files={},
                          repository='another/repository')
        args = SimpleNamespace(upstream_manifest=Path('manifest'), upstream_manifest_sha256='pin',
                               repo=Path('repo'), port=20000, upstream_port=47900)
        def read_text(path, *a, **kw):
            if path.name == 'release':
                return 'JAVA_VERSION="25.0.1"'
            return json.dumps(provenance if path.name == 'distribution-provenance.json' else data)
        with patch.dict('sys.modules', {'qa_prestart_audio': SimpleNamespace(_plain_path=lambda p: Path(p))}), \
                patch.object(q, 'digest', return_value='pin'), \
                patch.object(q, 'inventory', return_value={}), \
                patch.object(q.subprocess, 'check_output', side_effect=[head, '', head, '']), \
                patch.object(Path, 'read_text', read_text):
            with self.assertRaisesRegex(ValueError, 'provenance'):
                q.validate(args)
            provenance['repository'] = 'peppone-choi/logh7'
            self.assertEqual(q.validate(args), (data, runtime))

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
        self.assertIn('-Duser.home=' + str(Path('run') / 'state'), argv)
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
            files = [MagicMock() for _ in range(3)]
            wf.CreateFile.side_effect = files
            kernel = MagicMock()
            raw_job = 0x100000123  # Catch accidental 32-bit HANDLE truncation.
            kernel.CreateJobObjectW.return_value = raw_job
            kernel.CloseHandle.return_value = 1
            pywintypes = MagicMock()
            pywintypes.HANDLE.return_value = job
            if fault == 'create_job':
                kernel.CreateJobObjectW.return_value = None
            if fault in ('wrap_job', 'close_job'):
                pywintypes.HANDLE.side_effect = MemoryError('handle wrapper failed')
            if fault == 'close_job':
                kernel.CloseHandle.return_value = 0
            wp.CreateProcess.return_value = (process, thread, 123, 456)
            wp.GetProcessTimes.return_value = {'CreationTime': 'created'}
            wa.GlobalMemoryStatus.return_value = {'AvailPhys': 4 * 1024 ** 3}
            we.WaitForSingleObject.side_effect = lambda handle, timeout: 0 if timeout == 5000 else 258
            def strict_create(attributes, name):
                if name is None:
                    raise TypeError('None is not a valid string in this context')
                return job
            wj.CreateJobObject.side_effect = strict_create
            wj.IsProcessInJob.return_value = True
            wj.QueryInformationJobObject.return_value = {'ActiveProcesses': 0}
            modules = dict(win32api=wa, win32con=wc, win32event=we, win32file=wf,
                           win32job=wj, win32process=wp, pywintypes=pywintypes)
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
            if fault == 'exited':
                we.WaitForSingleObject.side_effect = lambda handle, timeout: 0
            if fault == 'start_timeout':
                owners = {}
            else:
                owners = {('127.0.0.1', p): 123 for p in q.PORTS}
            if fault == 'client_exit':
                def client_exit():
                    we.WaitForSingleObject.side_effect = lambda handle, timeout: 0
                    return dict(status='PRODUCT_WINDOW_COMPLETE', cleanup_verified=True)
                client.side_effect = client_exit
            with patch.dict('sys.modules', modules), \
                    patch.object(q.ctypes, 'WinDLL', return_value=kernel, create=True) as dll, \
                    patch.object(q.ctypes, 'get_last_error', return_value=5, create=True), \
                    patch.object(q.ctypes, 'WinError', side_effect=lambda code: OSError(code, 'native Job API failed'), create=True), \
                    patch.object(q, 'validate', return_value=({}, Path(d))), \
                    patch.object(q, 'reserve_check'), \
                    patch.object(q, 'listener_owners', return_value=owners), \
                    patch('qa_isolated_runner.snapshot', return_value={}), \
                    patch('qa_isolated_runner.isolated', return_value=True), \
                    patch('qa_prestart_runner.session_ids', return_value=[0, 1]), \
                    patch('qa_prestart_runner.protected_processes', return_value=[]), \
                    patch('qa_prestart_runner.job_policy') as policy, \
                    patch('qa_session_mutex.SessionMutex', return_value=mutex), \
                    patch.object(q.threading, 'Timer') as timer, \
                    patch.object(q.time, 'monotonic', side_effect=range(1000)):
                if fault == 'watchdog':
                    client.side_effect = lambda: (timer.call_args.args[1](),
                        dict(status='PRODUCT_WINDOW_COMPLETE', cleanup_verified=True))[1]
                result = q.run(args, client)
            self.assertNotEqual(result.get('error'), 'None is not a valid string in this context')
            if fault not in ('wait', 'create_job', 'wrap_job', 'close_job'):
                dll.assert_called_once_with('kernel32', use_last_error=True)
                kernel.CreateJobObjectW.assert_called_once_with(None, None)
                self.assertEqual(kernel.CreateJobObjectW.argtypes, [q.ctypes.c_void_p, q.ctypes.c_wchar_p])
                self.assertIs(kernel.CreateJobObjectW.restype, q.ctypes.c_void_p)
                pywintypes.HANDLE.assert_called_once_with(raw_job)
                wj.CreateJobObject.assert_not_called()
                policy.assert_called_once_with(job, target=True)
                wj.AssignProcessToJobObject.assert_called_once_with(job, process)
                wp.GetProcessTimes.assert_called_once_with(process)
                if fault != 'assign':
                    wj.IsProcessInJob.assert_called_once_with(process, job)
                    self.assertEqual(timer.call_args.args[0], 80)
                thread.Close.assert_called_once()
                process.Close.assert_called_once()
                job.Close.assert_called_once()
                kernel.CloseHandle.assert_not_called()  # PyHANDLE owns it now.
                for handle in files:
                    handle.Close.assert_called_once()
                self.assertEqual(wj.TerminateJobObject.call_count, 2 if fault == 'watchdog' else 1)
                wj.TerminateJobObject.assert_called_with(job, 2)
                if fault == 'assign':
                    wp.ResumeThread.assert_not_called()
                    wa.TerminateProcess.assert_called_once_with(process, 2)
                else:
                    wa.TerminateProcess.assert_not_called()
                timer.return_value.cancel.assert_called_once() if fault != 'assign' else None
            else:
                wp.CreateProcess.assert_not_called()
                client.assert_not_called()
                job.Close.assert_not_called()
                policy.assert_not_called()
                timer.assert_not_called()
                if fault != 'wait':
                    kernel.CreateJobObjectW.assert_called_once_with(None, None)
                    for handle in files:
                        handle.Close.assert_called_once()
                    if fault == 'create_job':
                        pywintypes.HANDLE.assert_not_called()
                        kernel.CloseHandle.assert_not_called()
                    else:
                        kernel.CloseHandle.assert_called_once_with(raw_job)
                        self.assertEqual(kernel.CloseHandle.argtypes, [q.ctypes.c_void_p])
                        self.assertIs(kernel.CloseHandle.restype, q.ctypes.c_int)
            mutex.close.assert_called_once()
            return result, client

    def test_job_api_and_handle_transfer_fail_closed_and_release_resources(self):
        for fault in ('create_job', 'wrap_job', 'close_job'):
            with self.subTest(fault=fault):
                result, _ = self.lifetime(fault)
                self.assertEqual(result['status'], 'FAIL')
                self.assertIn('handle wrapper failed' if fault == 'wrap_job' else 'native Job API failed', result['error'])
                self.assertFalse(result['cleanup_verified'])
                self.assertFalse(result['upstream']['started'])
                self.assertFalse(result['upstream']['cleanup_verified'])
                self.assertNotIn('pid', result['upstream'])
                self.assertNotIn('job_policy', result['upstream'])

    def test_lifetime_failures_cannot_keep_client_success(self):
        for fault in ('watchdog', 'client_exit'):
            with self.subTest(fault=fault):
                result, client = self.lifetime(fault)
                client.assert_called_once()
                self.assertEqual(result['status'], 'UPSTREAM_LIFETIME_FAILED')
                self.assertFalse(result['cleanup_verified'])
                self.assertTrue(result['upstream']['cleanup_verified'])
                self.assertEqual(result['upstream']['watchdog_expired'], fault == 'watchdog')

    def test_start_timeout_and_early_exit_never_call_client(self):
        for fault, error in (('start_timeout', 'UPSTREAM_START_TIMEOUT'), ('exited', 'UPSTREAM_EXITED')):
            with self.subTest(fault=fault):
                result, client = self.lifetime(fault)
                client.assert_not_called()
                self.assertEqual(result['error'], error)
                self.assertTrue(result['upstream']['cleanup_verified'])

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
