"""I1 synthetic files/static orchestration tests. No target/fixture is launched."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

import qa_product_report as qa
import qa_prestart_runner as runner
from qa_session_mutex import object_path

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location('driver', HERE / 'make-logh-qa-driver.py')
assert SPEC is not None and SPEC.loader is not None
driver = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(driver)
TARGET = 'bd19263c10decc3d58373165a82d42a9267868400d407da87d5f4f4109ab6e16'


def write_json(path, value):
    path.write_text(json.dumps(value), encoding='utf-8')
    return hashlib.sha256(path.read_bytes()).hexdigest()


def synthetic(root) -> tuple[Path, dict, str]:
    run = root / 'entry-run'
    run.mkdir()
    owned = root / ('a' * 32)
    (owned / 'evidence').mkdir(parents=True)
    config = dict(product_logh=True, executable='not-executed.exe', expected_runtime={},
                  desktop='private\\owned', runtime_report=str(run / 'runtime.json'))
    manifest = root / 'manifest.json'
    pin = write_json(manifest, dict(app={}, executable='not-executed.exe'))
    config_pin = write_json(owned / 'controller-config.json', config)
    receipt = dict(ready=True, effective_ui_flags=255, recorded_mono=1.0)
    before = dict(runner_session=0, active_console_session=1, sessions=[[0, 4], [1, 0]],
                  winsta='private', flags=0)
    target = dict(pid=42, creation_filetime=1234, image_sha256=TARGET)
    raw: dict = dict(status='PRODUCT_WINDOW_COMPLETE', manifest_sha256=pin, owned_temp=str(owned),
               run_id=owned.name, config_sha256=config_pin, controller_config=config,
               before=before, after=copy.deepcopy(before), session_ids_before=[0, 1],
               session_ids_after=[0, 1], private_desktop='owned', protected_processes=[],
               protected_processes_after=[], reference_processes_unchanged=True,
               mutexes=[dict(session=s, object_path=object_path(s), acquired=True, wait=0) for s in (0, 1)],
               cleanup_verified=True, outer_active_after=0, remaining_owned_pids=[],
               owned_processes=[dict(pid=42, creation_time='retained-time', signalled=True)],
               controller=dict(target=target, resumed=True, pre_resume_receipt=receipt,
                   guard_receipts=[dict(receipt, recorded_mono=1.1), dict(receipt, recorded_mono=1.9)],
                   resumed_mono=1.2, target_stopped_mono=2.0, guard_detached_mono=2.1,
                   guard_messages=[], product_audio_observation=dict(originals_called=0),
                   cleanup_verified=True, inner_active_after=0))
    evidence = owned / 'evidence' / 'synthetic.json'
    evidence_pin = write_json(evidence, dict(run_id=owned.name, manifest_sha256=pin,
                                            config_sha256=config_pin, target=target))
    raw['evidence_files'] = [dict(path=str(evidence), sha256=evidence_pin)]
    binding = dict(before=dict(head='b' * 40, dirty=''), after=dict(head='b' * 40, dirty=''),
                   repo=str(root), run_directory=str(run), manifest=str(manifest),
                   runner_exit=2, wait_exceeded=False)
    write_json(run / 'invocation.json', binding)
    write_json(run / 'runtime.json', raw)
    return run, raw, pin


class RunTests(unittest.TestCase):
    def test_no_site_supervisor_validates_before_dependency_import(self):
        with patch.object(runner, 'os', types.SimpleNamespace(name='nt')), \
                patch.object(runner, 'sys', types.SimpleNamespace(flags=types.SimpleNamespace(no_site=True))), \
                patch.object(runner, 'supervisor_prefix', return_value=Path('private/site')), \
                patch.object(runner, 'validate', side_effect=RuntimeError('pin mismatch')), \
                patch.object(runner, 'supervisor_dependencies') as dependencies:
            result = runner.run(types.SimpleNamespace())
        self.assertEqual(result['status'], 'FAIL')
        self.assertFalse(result['games_executed'])
        self.assertEqual(result['error'], 'pin mismatch')
        dependencies.assert_not_called()

    def test_explicit_supervisor_dependencies_do_not_execute_pth(self):
        with tempfile.TemporaryDirectory() as tmp:
            site = Path(tmp)
            marker = site / 'UNEXPECTED'
            (site / 'untrusted.pth').write_text('import pathlib; pathlib.Path(' + repr(str(marker)) + ').touch()\n')
            (site / 'pywin32_bootstrap.py').write_text('LOADED = True\n')
            command = ("import sys; sys.path.insert(0, " + repr(str(HERE)) + "); "
                       "from pathlib import Path; import qa_prestart_runner as r; "
                       "r.supervisor_dependencies(Path(" + repr(str(site)) + ")); "
                       "import pywin32_bootstrap; assert pywin32_bootstrap.LOADED; "
                       "print('EXPLICIT_ONLY')")
            result = subprocess.run([sys.executable, '-I', '-S', '-B', '-c', command],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.strip(), 'EXPLICIT_ONLY')
            self.assertFalse(marker.exists())

    def test_all_safety_conditions_and_independent_missing_fields(self):
        with tempfile.TemporaryDirectory() as tmp:
            _, raw, _ = synthetic(Path(tmp))
            self.assertTrue(all(qa.safety_fields(raw).values()))
            # Every required top-level proof must fail closed when absent.
            groups = {
                'isolationVerified': ['before', 'after', 'session_ids_before', 'session_ids_after',
                    'mutexes', 'private_desktop', 'controller_config', 'protected_processes',
                    'reference_processes_unchanged'],
                'cleanupVerified': ['cleanup_verified', 'outer_active_after', 'remaining_owned_pids',
                    'owned_processes', 'protected_processes_after', 'protected_processes']}
            for flag, keys in groups.items():
                for key in keys:
                    changed = copy.deepcopy(raw)
                    del changed[key]
                    with self.subTest(flag=flag, key=key):
                        self.assertFalse(qa.safety_fields(changed)[flag])
            for key in ('pre_resume_receipt', 'guard_receipts', 'resumed_mono', 'target_stopped_mono',
                        'guard_detached_mono', 'guard_messages', 'product_audio_observation', 'resumed'):
                changed = copy.deepcopy(raw)
                del changed['controller'][key]
                with self.subTest(key=key):
                    self.assertFalse(qa.safety_fields(changed)['silentVerified'])
            for key in ('cleanup_verified', 'inner_active_after'):
                changed = copy.deepcopy(raw)
                del changed['controller'][key]
                self.assertFalse(qa.safety_fields(changed)['cleanupVerified'])

    def test_controller_guard_errors_fail_closed_with_only_prior_good_receipts(self):
        with tempfile.TemporaryDirectory() as tmp:
            _, raw, _ = synthetic(Path(tmp))
            self.assertTrue(all(qa.safety_fields(raw).values()))
            for error in ('Target immediate Job UI restrictions not effective',
                          'Audio guard not ready'):
                changed = copy.deepcopy(raw)
                changed['status'] = 'FAIL'
                changed['controller'].update(status='FAIL', error=error)
                fields = qa.safety_fields(changed)
                for flag in ('isolationVerified', 'silentVerified'):
                    with self.subTest(error=error, flag=flag):
                        self.assertFalse(fields[flag])
                self.assertTrue(fields['cleanupVerified'])

    def test_bad_gap_detach_audio_ui_and_owned_handle(self):
        with tempfile.TemporaryDirectory() as tmp:
            _, raw, _ = synthetic(Path(tmp))
            for key, value, flag in (
                    ('guard_detached_mono', 1.8, 'silentVerified'),
                    ('target_stopped_mono', 4, 'silentVerified'),
                    ('resumed_mono', float('nan'), 'silentVerified'),
                    ('guard_messages', [dict(type='error')], 'silentVerified'),
                    ('product_audio_observation', {}, 'silentVerified'),
                    ('pre_resume_receipt', dict(ready=True, effective_ui_flags=0), 'isolationVerified')):
                changed = copy.deepcopy(raw)
                changed['controller'][key] = value
                self.assertFalse(qa.safety_fields(changed)[flag])
            raw['owned_processes'][0]['signalled'] = False
            self.assertFalse(qa.safety_fields(raw)['cleanupVerified'])

    def test_provenance_rejects_cross_run_manifest_evidence_target_and_config(self):
        for fault in ('directory', 'manifest', 'evidence', 'target', 'config', 'run_id', 'envelope', 'wait'):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                run, raw, pin = synthetic(root)
                if fault == 'directory':
                    run = root
                elif fault == 'manifest':
                    pin = '0' * 64
                elif fault == 'evidence':
                    raw['evidence_files'][0]['sha256'] = '0' * 64
                elif fault == 'target':
                    raw['controller']['target']['image_sha256'] = '0' * 64
                elif fault == 'config':
                    (Path(raw['owned_temp']) / 'controller-config.json').write_text('{}')
                elif fault == 'run_id':
                    raw['run_id'] = 'c' * 32
                elif fault == 'envelope':
                    path = Path(raw['evidence_files'][0]['path'])
                    raw['evidence_files'][0]['sha256'] = write_json(path, dict(run_id='wrong'))
                else:
                    binding = qa.read_object(run / 'invocation.json')
                    binding['wait_exceeded'] = True
                    write_json(run / 'invocation.json', binding)
                with self.assertRaises((ValueError, KeyError)):
                    qa.same_run_provenance(run, root / 'entry-run/runtime.json', raw, 'b' * 40, pin, root)

    def test_cli_same_run_all_true_still_not_run_and_nonzero(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            run, raw, pin = synthetic(root)
            repo = root / 'repo'
            repo.mkdir()
            def git(*args):
                return subprocess.check_output(['git', '-C', str(repo), *args], stderr=subprocess.DEVNULL, text=True).strip()
            git('init')
            git('-c', 'user.name=Synthetic', '-c', 'user.email=test@example.invalid',
                'commit', '--allow-empty', '-m', 'synthetic only')
            head = git('rev-parse', 'HEAD')
            binding = qa.read_object(run / 'invocation.json')
            binding.update(repo=str(repo), before=dict(head=head, dirty=''), after=dict(head=head, dirty=''))
            write_json(run / 'invocation.json', binding)
            runtime_pin = write_json(run / 'runtime.json', raw)
            command = [sys.executable, '-I', '-S', '-B', str(HERE / 'qa_product_report.py'),
                '--repo', str(repo), '--head', head, '--same-run', str(run),
                '--manifest-sha256', pin, '--runtime-report', str(run / 'runtime.json'),
                '--runtime-report-sha256', runtime_pin, '--out', str(run / 'product.json')]
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(result.returncode, 3, result.stderr + result.stdout)
            report = json.loads(result.stdout)
            self.assertTrue(report['runtimeHeadProvenanceVerified'])
            self.assertTrue(all(report[k] for k in qa.safety_fields(raw)))
            self.assertEqual(report['platforms']['windows']['status'], 'NOT_RUN')
            self.assertTrue(all(c['status'] == 'NOT_RUN' for c in report['platforms']['windows']['checks']))
            self.assertFalse(report['product_pass'])
            self.assertIsNone(report['windows_qa_receipt'])
            # Corrupted evidence must reach the CLI's single-JSON FAIL path.
            Path(raw['evidence_files'][0]['path']).write_text('{}')
            command[-1] = str(run / 'failed.json')
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(result.returncode, 2, result.stderr)
            report = json.loads(result.stdout)
            self.assertEqual(report['platforms']['windows']['status'], 'FAIL')
            self.assertFalse(report['runtimeHeadProvenanceVerified'])

    def test_observation_boundary_is_never_pass(self):
        self.assertIsNone(runner.observation_status(True, 0.99, 1))
        self.assertEqual(runner.observation_status(True, 1, 1), 'PRODUCT_WINDOW_COMPLETE')
        self.assertEqual(runner.observation_status(False, 0.99, 1), 'COMMAND_FAILED')
        self.assertEqual(runner.observation_status(False, 1, 1), 'COMMAND_FAILED')

    def test_wait_without_target_never_manufactures_provenance(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            run, raw, pin = synthetic(root)
            raw['status'] = 'WAIT_GUI_TEST_MUTEX_NAMESPACE: private'
            del raw['controller']
            self.assertFalse(qa.same_run_provenance(run, run / 'runtime.json', raw, 'b' * 40, pin, root))
            report = qa.product_report('device', 'b' * 40, raw)
            self.assertEqual(report['platforms']['windows']['status'], 'NOT_RUN')
            self.assertFalse(report['runtimeHeadProvenanceVerified'])

    def test_observe_argparse_rejects_invalid_without_launch(self):
        product = ['--product-logh', '--executable', 'unused', '--app-root', 'unused']
        for extra in (['--observe-seconds', '5'],
                      [*product, '--observe-seconds', '0'],
                      [*product, '--observe-seconds', '26'],
                      [*product, '--observe-seconds', '2', '--timeout', '2']):
            with self.subTest(extra=extra):
                result = subprocess.run([sys.executable, str(HERE / 'qa_prestart_runner.py'),
                    '--repo', 'unused', '--temp-parent', 'unused', '--manifest', 'unused',
                    '--report', 'unused', '--fixture-dir', 'unused', *extra], capture_output=True, text=True)
                self.assertEqual(result.returncode, 2)
                self.assertIn('observe-seconds', result.stderr)
                self.assertEqual(result.stdout, '')

    def test_powershell_run_output_is_redirected_and_bounded(self):
        source = (HERE / 'windows-product-qa.ps1').read_text()
        for needle in ("[string]$Mode = 'Report'", "'--product-logh'", 'qa_isolated_runner.py',
                       "@('-I', '-S', '-B', '-X'", '& $python @argv 1> $stdout 2> $stderr',
                       '$Timeout + 40', '--same-run', '--check-only', 'Get-FileHash'):
            self.assertIn(needle, source)
        self.assertLess(source.index('$beforeRaw ='), source.index('$pipeline.BeginInvoke()'))
        self.assertLess(source.index('$pipeline.EndInvoke'), source.index('$afterRaw ='))

    def test_driver_literals_validation_and_overwrite_refusal(self):
        values = dict(head='b' * 40, copy='E:/HermesQA/copy', python='E:/HermesQA/python.exe',
                      manifest='E:/HermesQA/manifest.json', manifest_sha256='a' * 64,
                      app_root='E:/HermesQA/app', temp_parent='E:/HermesQA/temp', port=20000,
                      observe_seconds=5, out_root='E:/HermesQA/out')
        line = driver.render_driver(**values)
        self.assertEqual(len(line.splitlines()), 1)
        self.assertTrue(line.startswith("& 'E:/HermesQA/copy/tools/client/windows-product-qa.ps1'"))
        for key, value in (('head', 'BAD'), ('manifest_sha256', 'BAD'), ('copy', 'D:/HermesQA/x'),
                           ('copy', "E:/HermesQA/x'; exit 0; '"), ('copy', 'E:/HermesQA/../x'),
                           ('python', 'E:/HermesQA/x\nexit 0'), ('port', 0), ('observe_seconds', 30)):
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                driver.render_driver(**dict(values, **{key: value}))
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / 'driver.ps1'
            argv = ['driver', '--output', str(output)]
            for key, value in values.items():
                argv += ['--' + key.replace('_', '-'), str(value)]
            with patch.object(driver, 'SERVER_ROOT', Path(tmp)), patch.object(sys, 'argv', argv):
                self.assertEqual(driver.main(), 0)
                previous = output.read_bytes()
                with self.assertRaises(SystemExit):
                    driver.main()
                self.assertEqual(output.read_bytes(), previous)


if __name__ == '__main__':
    unittest.main()
