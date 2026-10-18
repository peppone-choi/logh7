"""I6 Linux synthetic reports only; never execute a game, DLL or fixture."""

import hashlib
import itertools
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock

import qa_product_report as qa
from qa_render import RenderCollector
from test_qa_api import observation as api_observation
from test_qa_audio_coverage import observation as audio_observation
from test_qa_product_run import synthetic, write_json
from test_qa_protocol import install_capture
from test_qa_render import frame

HERE = Path(__file__).resolve().parent
FLAGS = ('runtimeHeadProvenanceVerified', 'isolationVerified', 'silentVerified', 'cleanupVerified')


def complete_evidence(root):
    run, raw, pin = synthetic(root)
    install_capture(raw)
    owned = Path(raw['owned_temp'])
    config = raw['controller_config']
    config.update(manifest_sha256=pin, controller_report=str(owned / 'controller.json'),
                  api_collector=True, render_collector=True)
    raw['config_sha256'] = write_json(owned / 'controller-config.json', config)
    binding = dict(run_id=raw['run_id'], manifest_sha256=pin,
                   config_sha256=raw['config_sha256'], target=raw['controller']['target'])
    for name in ('synthetic.json', 'protocol.json'):
        path = owned / 'evidence' / name
        write_json(path, dict(qa.read_object(path), **binding))
    c = raw['controller']
    c.update(status='PRODUCT_WINDOW_COMPLETE', product_audio_observation=audio_observation(),
             product_api_observation=api_observation())
    for name, observation in (('audio', c['product_audio_observation']),
                              ('api', c['product_api_observation'])):
        write_json(owned / 'evidence' / (name + '.json'), dict(binding, observation=observation))
    collector = RenderCollector(config, c)
    meta, pixels = frame()
    collector.on_message(dict(type='send', payload=dict(type='render-frame', slot='both', **meta)), pixels)
    collector.on_message(dict(type='send', payload=dict(type='render-finished', observation=dict(
        source='product', ready=True, finished=True, present_count=1,
        present_failures=0, device_failures=0, errors=[]))), None)
    collector.script = Mock()
    collector.finish()
    repo = root / 'repo'
    repo.mkdir()
    def git(*args):
        return subprocess.run(['git', '-C', str(repo), *args], check=True,
                              capture_output=True, text=True).stdout.strip()
    git('init')
    git('-c', 'user.name=Synthetic', '-c', 'user.email=test@example.invalid',
        'commit', '--allow-empty', '-m', 'Synthetic report only')
    head = git('rev-parse', 'HEAD')
    invocation = qa.read_object(run / 'invocation.json')
    invocation.update(repo=str(repo), before=dict(head=head, dirty=''), after=dict(head=head, dirty=''))
    write_json(run / 'invocation.json', invocation)
    return run, raw, pin, repo, head


class ProductGateTests(unittest.TestCase):
    def test_all_boolean_gate_combinations(self):
        # 10 independent conditions: same-run, provenance, 3 safety, 4 checks, status.
        for bits in itertools.product((False, True), repeat=10):
            r = qa.product_report('synthetic', 'b' * 40,
                                  dict(status='PRODUCT_WINDOW_COMPLETE' if bits[9] else 'PRODUCT_OBSERVED'))
            r.update(zip(FLAGS, bits[1:5]))
            for check, passed in zip(r['platforms']['windows']['checks'], bits[5:9]):
                check.update(status='PASS' if passed else 'NOT_RUN', evidence='synthetic-path.json')
            code = qa.finalize_product_report(r, bits[0])
            with self.subTest(bits=bits):
                self.assertEqual(code, 0 if all(bits) else 3)
                self.assertIs(r['product_pass'], all(bits))
                self.assertEqual(r['platforms']['windows']['status'], 'PASS' if all(bits) else 'NOT_RUN')
                self.assertIsNone(r['windows_qa_receipt'])

    def test_each_check_fail_and_nonboolean_proof_rejected(self):
        def ready():
            r = qa.product_report('synthetic', 'b' * 40, dict(status='PRODUCT_WINDOW_COMPLETE'))
            r.update(dict.fromkeys(FLAGS, True))
            for check in r['platforms']['windows']['checks']:
                check.update(status='PASS', evidence='synthetic.json')
            return r
        for i in range(4):
            for status in ('NOT_RUN', 'FAIL'):
                r = ready()
                r['platforms']['windows']['checks'][i]['status'] = status
                if status == 'FAIL':
                    r['platforms']['windows']['status'] = 'FAIL'
                self.assertEqual(qa.finalize_product_report(r, True), 2 if status == 'FAIL' else 3)
        for flag in FLAGS:
            for value in (False, None, 1, 'true'):
                r = ready()
                r[flag] = value
                self.assertNotEqual(qa.finalize_product_report(r, True), 0)
        for evidence in ('', None, 1):
            r = ready()
            r['platforms']['windows']['checks'][0]['evidence'] = evidence
            self.assertNotEqual(qa.finalize_product_report(r, True), 0)
        for status in qa.RUNTIME_STATUSES - {'PRODUCT_WINDOW_COMPLETE'}:
            r = ready()
            r['runtimeStatus'] = status
            with self.subTest(status=status):
                self.assertNotEqual(qa.finalize_product_report(r, True), 0)
        for fault in ('missing', 'duplicate', 'renamed'):
            r = ready()
            checks = r['platforms']['windows']['checks']
            if fault == 'missing':
                checks.pop()
            elif fault == 'duplicate':
                checks.append(checks[0])
            else:
                checks[0]['name'] = 'untrusted check'
            self.assertNotEqual(qa.finalize_product_report(r, True), 0)

    def test_cli_real_collectors_and_each_missing_condition(self):
        cases = ['complete', 'forged', 'report', 'provenance', *FLAGS[1:],
                 'TIMEOUT', 'COMMAND_FAILED', 'CLEANUP_FAILED']
        cases += [name + ':' + status for name in ('api', 'audio', 'render', 'protocol')
                  for status in ('NOT_RUN', 'FAIL')]
        for case in cases:
            with self.subTest(case=case), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                run, raw, pin, repo, head = complete_evidence(root)
                evidence = Path(raw['owned_temp']) / 'evidence'
                c = raw['controller']
                raw.update(product_pass=True, productQA='PASS', windows_qa_receipt='forged',
                           **dict.fromkeys(FLAGS, True))  # Input verdicts must be ignored.
                if case == 'forged':
                    c['cleanup_verified'] = False
                elif case == 'isolationVerified':
                    raw['before']['flags'] = 1
                elif case == 'silentVerified':
                    c['guard_detached_mono'] = 1.8
                elif case == 'cleanupVerified':
                    raw['remaining_owned_pids'] = [42]
                elif case in ('TIMEOUT', 'COMMAND_FAILED', 'CLEANUP_FAILED'):
                    raw['status'] = case
                elif ':' in case:
                    name, status = case.split(':')
                    path = evidence / (name + '.json')
                    envelope = qa.read_object(path)
                    if name == 'api':
                        c['status'] = 'PRODUCT_OBSERVED' if status == 'NOT_RUN' else 'PRODUCT_WINDOW_COMPLETE'
                        if status == 'FAIL':
                            c['product_api_observation']['errors'] = ['synthetic error']
                        envelope['observation'] = c['product_api_observation']
                    elif name == 'audio':
                        c['product_audio_observation']['calls'] = []
                        if status == 'FAIL':
                            c['product_audio_observation']['failed'] = 'synthetic error'
                        envelope['observation'] = c['product_audio_observation']
                    elif name == 'render':
                        envelope['observation']['present_count'] = 0
                        envelope['frames'] = {}
                        if status == 'FAIL':
                            envelope['observation']['errors'] = ['synthetic error']
                    else:
                        install_capture(raw, upstream=None if status == 'NOT_RUN' else 21001,
                                        chunks=[], connections=0,
                                        errors=[] if status == 'NOT_RUN' else ['synthetic error'])
                        envelope = qa.read_object(path)
                    write_json(path, envelope)
                # install_capture rewrites config; rebind envelopes for protocol cases.
                for path in evidence.glob('*.json'):
                    envelope = qa.read_object(path)
                    envelope['config_sha256'] = raw['config_sha256']
                    write_json(path, envelope)
                raw['evidence_files'] = [dict(path=str(p), sha256=hashlib.sha256(p.read_bytes()).hexdigest())
                                         for p in sorted(evidence.iterdir())]
                if case == 'provenance':
                    raw['evidence_files'][0]['sha256'] = '0' * 64
                runtime_pin = write_json(run / 'runtime.json', raw)
                output = run / 'product.json'
                command = [sys.executable, '-I', '-S', '-B', str(HERE / 'qa_product_report.py'),
                    '--repo', str(repo), '--head', head, '--runtime-report', str(run / 'runtime.json'),
                    '--runtime-report-sha256', runtime_pin, '--out', str(output)]
                if case != 'report':
                    command += ['--same-run', str(run), '--manifest-sha256', pin]
                result = subprocess.run(command, capture_output=True, text=True)
                r = json.loads(result.stdout)
                self.assertEqual(result.stderr, '')
                if case == 'complete':
                    self.assertEqual(result.returncode, 0, r)
                    self.assertEqual(r['platforms']['windows']['status'], 'PASS')
                    self.assertTrue(r['product_pass'])
                    self.assertEqual(r['productQA'], 'PASS')
                    self.assertEqual(r['blockers'], [])
                    for check, filename in zip(r['platforms']['windows']['checks'],
                                               ('api.json', 'audio.json', 'render.json', 'protocol.json')):
                        self.assertEqual(check['status'], 'PASS')
                        self.assertEqual(Path(check['evidence']), evidence / filename)
                else:
                    self.assertIn(result.returncode, (2, 3), r)
                    self.assertNotEqual(r['platforms']['windows']['status'], 'PASS')
                    self.assertFalse(r['product_pass'])
                    if ':' in case:
                        name, status = case.split(':')
                        index = ('api', 'audio', 'render', 'protocol').index(name)
                        self.assertEqual(r['platforms']['windows']['checks'][index]['status'], status, r)
                self.assertIsNone(r['windows_qa_receipt'])
                # Exceptions deliberately do not publish a successful output file.
                if output.exists():
                    self.assertEqual(qa.read_object(output), r)

    def test_powershell_gate_contains_all_conditions_and_exit_consistency(self):
        source = (HERE / 'windows-product-qa.ps1').read_text()
        for token in (*FLAGS, *qa.CHECKS, "$Mode -ceq 'Run'", "'PRODUCT_WINDOW_COMPLETE'",
                      '$checks.Count -eq $expectedChecks.Count', '$checks[$i].status -cne',
                      '$parsed.$field -isnot [bool]', '$code -eq 0', '$code -eq 2', '$code -eq 3',
                      '$null -ne $parsed.windows_qa_receipt', '$parsed.product_pass -isnot [bool]'):
            self.assertIn(token, source)
        self.assertLess(source.index('$canPass ='), source.index('$stream = [IO.File]::Open'))


if __name__ == '__main__':
    unittest.main()
