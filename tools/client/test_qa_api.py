"""Linux synthetic/static API contract tests. Not product evidence."""
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock

from qa_api import API_HOOKS, ApiCollector, judge_api
import qa_product_report as report
import qa_prestart_runner as runner
from test_qa_product_run import synthetic, write_json
from test_qa_protocol import install_capture

HERE = Path(__file__).parent


def observation() -> dict:
    return dict(source='product', ready=True, errors=[], hooks=[
        dict(api=api, module=module, address=hex(4096 + i * 16), installed=True)
        for i, (api, module) in enumerate(API_HOOKS.items())], calls=[])


def call(api='connect', value: int | None = -1, **extra):
    return dict(api=api, caller_module='G7MTClient.exe', caller_rva='0x123',
                caller_size=4096, completed=True, return_value=value,
                **extra)


class ApiTests(unittest.TestCase):
    def check(self, obs, resumed=True, complete=True):
        return judge_api(obs, 20000, resumed, complete)['status']

    def test_no_calls_and_allowed_loopback_pass(self):
        obs = observation()
        self.assertEqual(self.check(obs), 'PASS')
        for api in ('connect', 'WSAConnect'):
            for value in (0, -1):
                obs['calls'] = [call(api, value, address='127.0.0.1', port=20000, family=2)]
                self.assertEqual(self.check(obs), 'PASS')

    def test_external_wrong_port_ipv6_fail_even_failed_connect(self):
        for address, port, family in [('8.8.8.8', 20000, 2), ('127.0.0.1', 20001, 2),
                                      ('127.0.0.2', 20000, 2), ('::1', 20000, 23)]:
            obs = observation()
            obs['calls'] = [call(address=address, port=port, family=family)]
            self.assertEqual(self.check(obs), 'FAIL')

    def test_all_process_and_interaction_returns(self):
        for api in API_HOOKS:
            if api in ('connect', 'WSAConnect'):
                continue
            obs = observation()
            if api in ('keybd_event', 'mouse_event'):
                obs['calls'] = [call(api, None)]
                self.assertEqual(self.check(obs), 'FAIL')  # void cannot prove denial
                continue
            denied, success = (32, 33) if api in ('ShellExecuteA', 'ShellExecuteW') else (0, 1)
            obs['calls'] = [call(api, denied)]
            self.assertEqual(self.check(obs), 'PASS', api)
            obs['calls'][0]['return_value'] = success
            self.assertEqual(self.check(obs), 'FAIL', api)

    def test_hooks_missing_failed_null_duplicate_wrong_module(self):
        for fault in ('missing', 'failed', 'null', 'duplicate', 'module', 'ready', 'errors'):
            obs = observation()
            if fault == 'missing': obs['hooks'].pop()
            if fault == 'failed': obs['hooks'][0]['installed'] = False
            if fault == 'null': obs['hooks'][0]['address'] = '0x0'
            if fault == 'duplicate': obs['hooks'].append(copy.deepcopy(obs['hooks'][0]))
            if fault == 'module': obs['hooks'][0]['module'] = 'unknown.dll'
            if fault == 'ready': obs['ready'] = False
            if fault == 'errors': obs['errors'] = ['overflow']
            self.assertEqual(self.check(obs), 'FAIL', fault)

    def test_not_run_and_fail_precedence(self):
        obs = observation()
        self.assertEqual(self.check(obs, resumed=False, complete=False), 'NOT_RUN')
        self.assertEqual(self.check(obs, complete=False), 'NOT_RUN')
        obs['errors'] = ['install failed']
        self.assertEqual(self.check(obs, resumed=False, complete=False), 'FAIL')

    def test_malformed_fields_caller_pending_unknown_and_port(self):
        for field, value in [('caller_module', None), ('caller_rva', '0x1000'),
                             ('caller_size', True), ('completed', False),
                             ('return_value', True), ('api', 'unknown')]:
            obs = observation()
            obs['calls'] = [call('SendInput', 0)]
            obs['calls'][0][field] = value
            self.assertEqual(self.check(obs), 'FAIL', field)
        for key in observation():
            obs = observation()
            del obs[key]
            self.assertEqual(self.check(obs), 'FAIL', key)
        self.assertEqual(judge_api(observation(), True, True, True)['status'], 'FAIL')

    def test_collector_binding_rpc_failure_idempotence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'evidence').mkdir()
            (root / 'controller-config.json').write_text('{}')
            config = dict(controller_report=str(root / 'controller.json'), manifest_sha256='a' * 64)
            result = dict(target=dict(pid=42, creation_filetime=7, image_sha256='b' * 64))
            collector = ApiCollector(config, result)
            session = Mock()
            script = session.create_script.return_value
            script.exports_sync.apistatus.return_value = observation()
            collector.install(session)
            script.on.assert_called_once_with('message', collector.on_message)
            script.load.assert_called_once()
            collector.finish()
            collector.finish()
            saved = json.loads((root / 'evidence/api.json').read_text())
            self.assertEqual(saved['run_id'], root.name)
            self.assertEqual(saved['config_sha256'], hashlib.sha256(b'{}').hexdigest())
            self.assertEqual(saved['manifest_sha256'], config['manifest_sha256'])
            self.assertEqual(saved['target'], result['target'])
            self.assertEqual(saved['observation'], result['product_api_observation'])
            self.assertEqual(len(script.exports_sync.apistatus.call_args_list), 2)
            (root / 'evidence/api.json').unlink()
            collector.saved = False
            script.exports_sync.apistatus.side_effect = RuntimeError('lost target')
            collector.finish()
            self.assertEqual(self.check(json.loads((root / 'evidence/api.json').read_text())['observation']), 'FAIL')

    def test_early_exit_partial_snapshot_never_passes_and_async_error_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'evidence').mkdir()
            (root / 'controller-config.json').write_text('{}')
            collector = ApiCollector(dict(controller_report=str(root / 'controller.json'),
                manifest_sha256='a' * 64), dict(target={}))
            session = Mock()
            exports = session.create_script.return_value.exports_sync
            exports.apistatus.return_value = observation()
            collector.install(session)
            exports.apistatus.side_effect = RuntimeError('target exited')
            collector.finish(target_exited_early=True)
            path = root / 'evidence/api.json'
            obs = json.loads(path.read_text())['observation']
            self.assertEqual(self.check(obs, complete=False), 'NOT_RUN')
            self.assertEqual(self.check(obs), 'NOT_RUN')  # partial cannot become PASS
            path.unlink()
            collector.saved = False
            collector.on_message(dict(type='error', description='synthetic script failure'), None)
            collector.finish(target_exited_early=True)
            self.assertEqual(self.check(json.loads(path.read_text())['observation'], complete=False), 'FAIL')

    def test_adapter_recompute_missing_mismatch_and_no_platform_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'evidence').mkdir()
            obs = observation()
            c = dict(resumed=True, status='PRODUCT_WINDOW_COMPLETE', product_api_observation=obs)
            raw = dict(owned_temp=directory, controller=c, controller_config=dict(api_collector=True,
                protocol_port=20000, target_args=['127.0.0.1', '20000', 'ginei00', '1', 'dummy']))
            path = root / 'evidence/api.json'
            for expected, value in [('PASS', 0), ('FAIL', 1)]:
                obs['calls'] = [call('SendInput', value)]
                path.write_text(json.dumps(dict(observation=obs)))
                r = report.product_report('synthetic', 'c' * 40)
                report.apply_api_check(r, raw)
                self.assertEqual(r['platforms']['windows']['checks'][0]['status'], expected)
                self.assertNotEqual(r['platforms']['windows']['status'], 'PASS')
                self.assertFalse(r['product_pass'])
                self.assertIsNone(r['windows_qa_receipt'])
            path.write_text(json.dumps(dict(observation=observation())))
            with self.assertRaisesRegex(ValueError, 'mismatch'):
                report.apply_api_check(r, raw)
            path.unlink()
            with self.assertRaisesRegex(ValueError, 'Missing API'):
                report.apply_api_check(r, raw)

    def test_same_run_cli_bindings_and_recompute_all_branches(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            run, raw, manifest_pin = synthetic(root)
            repo = root / 'repo'
            repo.mkdir()
            def git(*args):
                return subprocess.check_output(['git', '-C', str(repo), *args],
                    stderr=subprocess.DEVNULL, text=True).strip()
            git('init')
            git('-c', 'user.name=Synthetic', '-c', 'user.email=test@example.invalid',
                'commit', '--allow-empty', '-m', 'synthetic only')
            head = git('rev-parse', 'HEAD')
            binding = report.read_object(run / 'invocation.json')
            binding.update(repo=str(repo), before=dict(head=head, dirty=''), after=dict(head=head, dirty=''))
            write_json(run / 'invocation.json', binding)
            raw['controller_config']['api_collector'] = True
            evidence = install_capture(raw)
            c = raw['controller']
            path = evidence / 'api.json'
            c['api_evidence'] = str(path)
            cases = [('normal', 'PASS', 3), ('external', 'FAIL', 2), ('port', 'FAIL', 2),
                     ('process', 'FAIL', 2), ('input', 'FAIL', 2), ('hook', 'FAIL', 2),
                     ('early', 'NOT_RUN', 3), ('binding', None, 2), ('hash', None, 2),
                     ('missing', None, 2)]
            for fault, expected, code in cases:
                with self.subTest(fault=fault):
                    obs = observation()
                    obs['calls'] = [call(address='127.0.0.1', port=21000, family=2)]
                    c.update(status='PRODUCT_WINDOW_COMPLETE', resumed=True)
                    if fault == 'external': obs['calls'][0]['address'] = '8.8.8.8'
                    if fault == 'port': obs['calls'][0]['port'] = 21001
                    if fault == 'process': obs['calls'] = [call('CreateProcessW', 1)]
                    if fault == 'input': obs['calls'] = [call('SendInput', 1)]
                    if fault == 'hook': obs['hooks'].pop()
                    if fault == 'early': c.update(resumed=False, status='COMMAND_FAILED')
                    c['product_api_observation'] = obs
                    envelope = dict(observation=obs, run_id=raw['run_id'],
                        manifest_sha256=manifest_pin, config_sha256=raw['config_sha256'], target=c['target'])
                    if fault == 'binding': envelope['run_id'] = 'wrong'
                    write_json(path, envelope)
                    if fault == 'missing': path.unlink()
                    raw['evidence_files'] = [dict(path=str(p), sha256=runner.digest(p))
                        for p in sorted(evidence.iterdir())]
                    if fault == 'hash': path.write_text('{}')
                    runtime_pin = write_json(run / 'runtime.json', raw)
                    result = subprocess.run([sys.executable, '-I', '-S', '-B',
                        str(HERE / 'qa_product_report.py'), '--repo', str(repo), '--head', head,
                        '--same-run', str(run), '--manifest-sha256', manifest_pin,
                        '--runtime-report', str(run / 'runtime.json'), '--runtime-report-sha256', runtime_pin,
                        '--out', str(run / (fault + '.json'))], capture_output=True, text=True)
                    self.assertEqual(result.returncode, code, result.stdout + result.stderr)
                    output = json.loads(result.stdout)
                    if expected is not None:
                        self.assertEqual(output['platforms']['windows']['checks'][0]['status'], expected)
                    else:
                        self.assertEqual(output['platforms']['windows']['status'], 'FAIL')
                    self.assertNotEqual(output['platforms']['windows']['status'], 'PASS')
                    self.assertFalse(output['product_pass'])
                    self.assertIsNone(output['windows_qa_receipt'])

    def test_observe_only_script_and_pre_resume_install_snapshot_before_stop(self):
        text = (HERE / 'qa_api_x86.js').read_text()
        for forbidden in ('Interceptor.replace', '.replace(', 'NativeFunction', 'NativeCallback',
                          'writeU', 'writePointer', 'Module.load', 'retval =', 'args[0] ='):
            self.assertNotIn(forbidden, text)
        for api in API_HOOKS:
            self.assertIn("'" + api + "'", text)
        source = (HERE / 'qa_prestart_runner.py').read_text()
        self.assertLess(source.index('api.install(guard.session)'), source.index('wp.ResumeThread(thread)'))
        begin = source.index('render.finish()')
        self.assertLess(source.index('api.finish()', begin), source.index('audio.stop_owned_handle(process)', begin))
        self.assertIn('qa_api.py', runner.OWN_CODE)
        self.assertIn('qa_api_x86.js', runner.OWN_CODE)


if __name__ == '__main__':
    unittest.main()
