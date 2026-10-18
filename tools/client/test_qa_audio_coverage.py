"""Linux synthetic/static only. These examples are not product evidence."""
import copy
import json
from pathlib import Path
import tempfile
import subprocess
import sys
import types
import unittest
from unittest.mock import Mock

from qa_audio_coverage import check_audio, DENIED
import qa_product_report as report
import qa_prestart_runner as runner
from test_qa_product_run import synthetic, write_json

HERE = Path(__file__).parent


def observation() -> dict:
    return dict(source='product', ready=True, failed=None, originals_called=0,
        unsupported_output_modules=[], main_module='C:/app/G7MTClient.exe',
        loaded_modules=[dict(name='G7MTClient.exe', path='C:/app/G7MTClient.exe', size=4096)],
        calls=[dict(api='IDirectSoundBuffer.Play', caller_module='G7MTClient.exe',
                    caller_path='C:/app/G7MTClient.exe', caller_rva='0x123', return_value=0,
                    count=1, disposition='suppressed', source='product')])


class AudioTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        _, self.raw, _ = synthetic(self.root)
        self.c = self.raw['controller']
        self.a = observation()

    def check(self):
        return check_audio(self.a, self.c)['status']

    def test_pass_and_all_denial_returns(self):
        for api, value in DENIED.items():
            with self.subTest(api=api):
                self.a['calls'][0].update(api=api, return_value=value)
                self.assertEqual(self.check(), 'PASS')
                self.a['calls'][0]['return_value'] = value + 1
                self.assertEqual(self.check(), 'FAIL')

    def test_fail_original_fatal_new_module(self):
        for key, value in [('originals_called', 1), ('failed', 'fatal'),
                           ('ready', False), ('unsupported_output_modules', ['xaudio2.dll'])]:
            with self.subTest(key=key):
                self.a = observation()
                self.a[key] = value
                self.assertEqual(self.check(), 'FAIL')
        self.a = observation()
        self.c['guard_messages'] = [{'payload': {'type': 'audio-fatal'}}]
        self.assertEqual(self.check(), 'FAIL')

    def test_no_calls_selftest_generic_com_failed_initialization_not_run(self):
        self.a['calls'] = []
        self.assertEqual(self.check(), 'NOT_RUN')
        self.a = observation()
        self.a['source'] = 'fixture'
        self.assertEqual(self.check(), 'NOT_RUN')
        self.a = observation()
        self.a['calls'][0]['source'] = 'selftest'
        self.assertEqual(self.check(), 'NOT_RUN')
        self.a['calls'][0].update(source='product', api='CoCreateInstance', return_value=-2147024891)
        self.assertEqual(self.check(), 'NOT_RUN')
        self.a['calls'][0].update(api='DirectSoundCreate', return_value=-1)
        self.assertEqual(self.check(), 'NOT_RUN')
        self.a['calls'][0].update(return_value=0, disposition='guarded-init')
        self.assertEqual(self.check(), 'PASS')
        self.a['calls'][0]['disposition'] = 'unprotected'
        self.assertEqual(self.check(), 'FAIL')

    def test_unknown_caller_gap_missing_fields_fail_closed(self):
        for key, value in [('caller_path', 'unknown'), ('caller_rva', None),
                           ('caller_rva', '0x1000'), ('count', True), ('return_value', None)]:
            self.a = observation()
            self.a['calls'][0][key] = value
            self.assertEqual(self.check(), 'FAIL')
        self.a = observation()
        self.c['target_stopped_mono'] = 10
        self.assertEqual(self.check(), 'FAIL')
        self.c['target_stopped_mono'] = 2
        for key in self.a:
            changed = copy.deepcopy(self.a)
            del changed[key]
            self.assertEqual(check_audio(changed, self.c)['status'], 'FAIL', key)

    def test_not_observed_not_coverage(self):
        result = check_audio(self.a, self.c)
        self.assertNotIn('DirectSound', result['NOT_OBSERVED'])
        self.assertIn('WASAPI', result['NOT_OBSERVED'])
        self.assertEqual(result['attempts'], 1)

    def test_same_run_adapter_and_missing_changed_evidence(self):
        self.c['product_audio_observation'] = self.a
        envelope = dict(run_id=self.raw['run_id'], manifest_sha256=self.raw['manifest_sha256'],
                        config_sha256=self.raw['config_sha256'], target=self.c['target'], observation=self.a)
        path = Path(self.raw['owned_temp']) / 'evidence/audio.json'
        self.c['audio_evidence'] = str(path)
        write_json(path, envelope)
        output = report.product_report('synthetic', 'b' * 40, self.raw)
        report.apply_audio_check(output, self.raw)
        self.assertEqual(output['platforms']['windows']['checks'][1]['status'], 'PASS')
        self.assertEqual(output['platforms']['windows']['status'], 'NOT_RUN')
        self.assertFalse(output['product_pass'])
        self.assertIsNone(output['windows_qa_receipt'])
        envelope['observation'] = dict(self.a, originals_called=1)
        write_json(path, envelope)
        with self.assertRaisesRegex(ValueError, 'mismatch'):
            report.apply_audio_check(output, self.raw)
        path.unlink()
        with self.assertRaisesRegex(ValueError, 'Missing audio'):
            report.apply_audio_check(output, self.raw)

    def test_payload_all_audio_hook_paths_record_and_manifest(self):
        text = (HERE / 'qa_prestart_silence_x86.js').read_text()
        self.assertIn('function recordCall(', text)
        self.assertIn('Process.findModuleByAddress', text)
        self.assertIn('this.returnAddress', text)
        self.assertIn('originals_called:0', text)
        for start, end in [('function deny(', 'function hook('), ('function qi(', 'function buffer('),
                           ('function device(', 'function audioClient('),
                           ('function immDevice(', 'function exp('),
                           ("if(name==='dsound.dll')", "if(name==='winmm.dll'")]:
            self.assertIn('recordCall(', text[text.index(start):text.index(end)])
        self.assertIn('qa_audio_coverage.py', runner.OWN_CODE)
        controller = (HERE / 'qa_prestart_runner.py').read_text()
        self.assertLess(controller.index('save_audio_evidence(config, result, guard)'),
                        controller.index('wj.TerminateJobObject(inner, 2)'))

    def test_final_snapshot_binds_actual_rpc_and_never_calls_selftest(self):
        root = Path(self.raw['owned_temp'])
        config = dict(self.raw['controller_config'], manifest_sha256=self.raw['manifest_sha256'],
                      controller_report=str(root / 'controller-result.json'))
        config_pin = write_json(root / 'controller-config.json', config)
        exports = Mock()
        exports.audiostatus.return_value = self.a
        guard = types.SimpleNamespace(script=types.SimpleNamespace(exports_sync=exports))
        runner.save_audio_evidence(config, self.c, guard)
        runner.save_audio_evidence(config, self.c, guard)  # idempotent, no overwrite
        exports.audiostatus.assert_called_once_with()
        exports.audioselftest.assert_not_called()
        saved = json.loads((root / 'evidence/audio.json').read_text())
        for key, value in [('run_id', root.name), ('manifest_sha256', config['manifest_sha256']),
                           ('config_sha256', config_pin), ('target', self.c['target'])]:
            self.assertEqual(saved[key], value)
        self.assertEqual(saved['observation'], self.c['product_audio_observation'])
        self.assertIn('recorded_mono', saved['observation'])

    def test_same_run_cli_recomputes_pass_fail_not_run_without_exit_zero(self):
        repo = self.root / 'repo'
        repo.mkdir()
        def git(*args):
            return subprocess.check_output(['git', '-C', str(repo), *args],
                                           stderr=subprocess.DEVNULL, text=True).strip()
        git('init')
        git('-c', 'user.name=Synthetic', '-c', 'user.email=test@example.invalid',
            'commit', '--allow-empty', '-m', 'synthetic only')
        head = git('rev-parse', 'HEAD')
        run = self.root / 'entry-run'
        binding = report.read_object(run / 'invocation.json')
        binding.update(repo=str(repo), before=dict(head=head, dirty=''), after=dict(head=head, dirty=''))
        write_json(run / 'invocation.json', binding)
        path = Path(self.raw['owned_temp']) / 'evidence/audio.json'
        self.c['audio_evidence'] = str(path)
        for fault, status, code in [('none', 'PASS', 3), ('original', 'FAIL', 2),
                                   ('return', 'FAIL', 2), ('fatal', 'FAIL', 2),
                                   ('module', 'FAIL', 2), ('empty', 'NOT_RUN', 3),
                                   ('fixture', 'NOT_RUN', 3), ('binding', 'FAIL', 2)]:
            with self.subTest(fault=fault):
                a = observation()
                if fault == 'original': a['originals_called'] = 1
                if fault == 'return': a['calls'][0]['return_value'] = 7
                if fault == 'fatal': a['failed'] = 'fatal'
                if fault == 'module': a['unsupported_output_modules'] = ['xaudio2.dll']
                if fault == 'empty': a['calls'] = []
                if fault == 'fixture': a['source'] = 'fixture'
                self.c['product_audio_observation'] = a
                envelope: dict = dict(run_id=self.raw['run_id'], manifest_sha256=self.raw['manifest_sha256'],
                                config_sha256=self.raw['config_sha256'], target=self.c['target'], observation=a)
                if fault == 'binding': envelope['run_id'] = 'wrong'
                self.raw['evidence_files'] = [dict(path=str(path), sha256=write_json(path, envelope))]
                # Retain synthetic companion in exact inventory.
                companion = path.with_name('synthetic.json')
                self.raw['evidence_files'].append(dict(path=str(companion), sha256=runner.digest(companion)))
                runtime_pin = write_json(run / 'runtime.json', self.raw)
                command = [sys.executable, '-I', '-S', '-B', str(HERE / 'qa_product_report.py'),
                    '--repo', str(repo), '--head', head, '--same-run', str(run),
                    '--manifest-sha256', self.raw['manifest_sha256'], '--runtime-report', str(run / 'runtime.json'),
                    '--runtime-report-sha256', runtime_pin, '--out', str(run / (fault + '.json'))]
                result = subprocess.run(command, capture_output=True, text=True)
                self.assertEqual(result.returncode, code, result.stdout + result.stderr)
                output = json.loads(result.stdout)
                if fault == 'binding':
                    self.assertEqual(output['platforms']['windows']['status'], 'FAIL')
                else:
                    self.assertEqual(output['platforms']['windows']['checks'][1]['status'], status)
                self.assertFalse(output['product_pass'])
                self.assertIsNone(output['windows_qa_receipt'])


if __name__ == '__main__':
    unittest.main()
