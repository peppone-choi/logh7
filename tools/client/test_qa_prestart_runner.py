import ast
import hashlib
from pathlib import Path
import struct
import types
import unittest
from unittest.mock import Mock
from unittest.mock import patch
import sys
import tempfile
import os
import py_compile
import subprocess

import qa_prestart_fixture as fixture
import qa_prestart_runner as runner
import qa_session_mutex as mutex

HERE = Path(__file__).parent


class PrestartTests(unittest.TestCase):
    def test_explicit_app_root_covers_sibling_data_and_payload(self):
        tree = ast.parse((HERE / 'qa_prestart_audio.py').read_text())
        names = ('_plain_path', '_file_hash', 'runtime_manifest', 'verify_runtime_manifest')
        namespace = {'Path': Path, 'hashlib': hashlib}
        body = [n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name in names]
        exec(compile(ast.Module(body=body, type_ignores=[]), '<closure>', 'exec'), namespace)
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / 'app'
            (root / 'exe').mkdir(parents=True)
            (root / 'data').mkdir()
            exe = root / 'exe/game.exe'
            exe.write_bytes(b'not executed')
            data = root / 'data/asset'
            data.write_bytes(b'asset')
            payload = Path(temp) / 'payload.js'
            payload.write_bytes(b'payload')
            manifest = namespace['runtime_manifest'](exe, [payload], app_root=root)
            self.assertEqual(set(manifest), {'app/exe/game.exe', 'app/data/asset', 'injected/payload.js'})
            namespace['verify_runtime_manifest'](exe, manifest, [payload], app_root=root)
            data.write_bytes(b'mutated')
            with self.assertRaisesRegex(RuntimeError, 'mismatch'):
                namespace['verify_runtime_manifest'](exe, manifest, [payload], app_root=root)
            with self.assertRaisesRegex(RuntimeError, 'outside'):
                namespace['runtime_manifest'](exe, app_root=root / 'data')

    def test_exit_race_never_accepts_live_or_guard_killed_target(self):
        self.assertTrue(runner.normal_fixture_exit(True, 0, []))
        self.assertTrue(runner.normal_fixture_exit(True, 7, []))
        self.assertFalse(runner.normal_fixture_exit(False, 0, []))
        self.assertFalse(runner.normal_fixture_exit(True, 0xA001, []))
        self.assertFalse(runner.normal_fixture_exit(True, 0, [{'type': 'error'}]))
        self.assertFalse(runner.normal_fixture_exit(True, 0, [{'payload': {'type': 'audio-fatal'}}]))

    def test_ui_nodes_only_on_assigned_target(self):
        api = Mock(JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE=8192, JOB_OBJECT_LIMIT_ACTIVE_PROCESS=8,
                   JobObjectExtendedLimitInformation=9, JobObjectBasicUIRestrictions=4)
        for target, apply_ui, expected in ((False, True, 0), (True, False, 0), (True, True, 255)):
            api.reset_mock()
            info = {'BasicLimitInformation': {'LimitFlags': 8200, 'ActiveProcessLimit': 1 if target else 5}}
            api.QueryInformationJobObject.side_effect = [info, info, {'UIRestrictionsClass': expected}]
            with patch.dict(sys.modules, win32job=api):
                runner.job_policy('owned', target, apply_ui)
            ui_sets = [c for c in api.SetInformationJobObject.call_args_list if c.args[1] == 4]
            self.assertEqual(len(ui_sets), 1 if expected else 0)

    def test_fixture_has_no_audio_ui_or_child_imports(self):
        for mode in ('success', 'failure', 'hang'):
            data = fixture.image(mode)
            self.assertEqual(len(data), 1024)
            self.assertEqual(data[:2], b'MZ')
            self.assertEqual(data[0x80:0x84], b'PE\0\0')
            self.assertEqual(struct.unpack_from('<H', data, 0x84)[0], 0x14c)
            self.assertEqual(data[512 + 0xe0:512 + 0xe0 + 13], b'kernel32.dll\0')
            self.assertEqual(data[512 + 0xf0:512 + 0xf0 + 8], b'\0\0Sleep\0')
            self.assertEqual(data[512 + 0x100:512 + 0x100 + 14], b'\0\0ExitProcess\0')
            self.assertEqual(struct.unpack_from('<III', data, 512 + 0xc0), (0x10f0, 0x1100, 0))
            self.assertNotIn(b'CreateProcess', data)
            self.assertNotIn(b'winmm', data)
        self.assertEqual(len({hashlib.sha256(fixture.image(m)).hexdigest() for m in ('success', 'failure', 'hang')}), 3)
        with self.assertRaises(ValueError):
            fixture.image('product')

    def test_legacy_mutex_namespaces(self):
        self.assertEqual(mutex.object_path(0), '\\BaseNamedObjects\\' + mutex.NAME)
        self.assertEqual(mutex.object_path(1), '\\Sessions\\1\\BaseNamedObjects\\' + mutex.NAME)
        self.assertNotEqual(mutex.object_path(0), mutex.object_path(1))
        with self.assertRaises(ValueError):
            mutex.object_path(-1)

    def test_product_target_requires_exact_hash_and_private_full_root(self):
        audio = types.SimpleNamespace(TARGET_SHA256='pinned', _file_hash=lambda p: 'wrong',
                                      _plain_path=lambda p: Path(p))
        args = types.SimpleNamespace(product_logh=True, executable=Path('E:/HermesQA/app/G7.exe'),
                                     app_root=Path('E:/HermesQA/app'))
        with patch.dict(sys.modules, qa_prestart_audio=audio):
            with self.assertRaisesRegex(RuntimeError, 'target hash'):
                runner.target_spec(args)
            audio._file_hash = lambda p: 'pinned'
            self.assertEqual(runner.target_spec(args)[0], args.executable)
            args.app_root = Path('E:/user')
            with self.assertRaisesRegex(ValueError, 'private'):
                runner.target_spec(args)

    def test_controller_pycache_is_empty_owned_and_redirected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            command = runner.controller_command(root, root / 'config.json', 'digest')
            self.assertIn('-X', command)
            self.assertIn('pycache_prefix=' + str(root / 'pyc'), command)
            self.assertEqual(list((root / 'pyc').iterdir()), [])
            with self.assertRaises(FileExistsError):
                runner.controller_command(root, root / 'config.json', 'digest')

    def test_product_guard_uses_nonfixture_manifest_and_no_synthetic_rpc(self):
        source = (HERE / 'qa_prestart_runner.py').read_text()
        self.assertIn("fixture=not config['product_logh']", source)
        self.assertIn("expected_runtime=config['expected_runtime']", source)
        self.assertIn("app_root=config['app_root']", source)
        self.assertIn("if not config['product_logh']:", source)
        self.assertIn("report['status'] = 'WAIT_USER_GAME_OR_STEAM'", source)

    def test_controller_flags_bypass_matching_malicious_pyc(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            module = root / 'probe.py'
            module.write_text('VALUE = "evil"\n')
            stamp = module.stat().st_mtime_ns
            # Explicit in-tree location: the test runner itself may have an
            # isolated sys.pycache_prefix, which py_compile otherwise inherits.
            cached = root / '__pycache__' / ('probe.' + sys.implementation.cache_tag + '.pyc')
            cached.parent.mkdir()
            py_compile.compile(str(module), cfile=str(cached), doraise=True)
            module.write_text('VALUE = "safe"\n')
            os.utime(module, ns=(stamp, stamp))
            code = f'import sys; sys.path.insert(0, {str(root)!r}); import probe; print(probe.VALUE)'
            old = subprocess.check_output([sys.executable, '-I', '-S', '-B', '-c', code], text=True)
            self.assertEqual(old.strip(), 'evil')  # demonstrates why -B is insufficient
            command = runner.controller_command(root, root / 'config', 'unused')
            flags = command[:command.index(str(runner.HERE / 'qa_prestart_runner.py'))]
            actual = subprocess.check_output([*flags, '-c', code], text=True)
            self.assertEqual(actual.strip(), 'safe')
            self.assertEqual(list((root / 'pyc').iterdir()), [])

    def test_ui_gate_rejects_unobservable_or_unrestricted_target(self):
        tree = ast.parse((HERE / 'qa_prestart_audio.py').read_text())
        cls = next(n for n in tree.body if isinstance(n, ast.ClassDef) and n.name == 'AudioGuard')
        method = next(n for n in cls.body if isinstance(n, ast.FunctionDef) and n.name == 'assert_ready')
        namespace = {}
        exec(compile(ast.Module(body=[method], type_ignores=[]), '<isolated-gate>', 'exec'), namespace)
        for flags in (None, 0, 254, 255):
            callback = Mock()
            exports = types.SimpleNamespace(audiostatus=lambda: dict(ready=True, failed=None,
                                            arch='ia32', effective_ui_flags=flags))
            obj = types.SimpleNamespace(failure=None, helper_identities=None,
                                        script=types.SimpleNamespace(exports_sync=exports), on_failure=callback)
            if flags == 255:
                self.assertEqual(namespace['assert_ready'](obj)['effective_ui_flags'], 255)
                callback.assert_not_called()
            else:
                with self.assertRaisesRegex(RuntimeError, 'immediate Job'):
                    namespace['assert_ready'](obj)
                callback.assert_called_once()

    def test_static_suspend_gate_and_direct_controller(self):
        source = (HERE / 'qa_prestart_runner.py').read_text()
        self.assertIn("[str(base_python()), '-I', '-S', '-B', '-X'", source)
        controller = source[source.index('def controller('):source.index('def run(')]
        self.assertLess(controller.index('wj.AssignProcessToJobObject'), controller.index('audio.AudioGuard'))
        self.assertLess(controller.index("result['guard_receipts'].append(guard.assert_ready())"),
                        controller.index('wp.ResumeThread(thread)'))
        self.assertLess(controller.index('wj.TerminateJobObject(inner'), controller.index('guard.close()'))
        self.assertNotIn('SessionMute', controller)
        self.assertNotIn('logh_prestart_readiness', source)
        payload = (HERE / 'qa_prestart_silence_x86.js').read_text()
        self.assertIn('audit.effective_ui_flags=effectiveUi();return audit', payload)
        self.assertIn("value!==255", payload)


if __name__ == '__main__':
    unittest.main()
