"""I2 Linux-only synthetic bytes/sockets. Never launch a product or fixture."""
import copy
import hashlib
import json
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

import qa_prestart_runner as runner
import qa_product_report as report
import qa_protocol as protocol
from test_qa_product_run import synthetic, write_json, driver

HERE = Path(__file__).resolve().parent


def frame(kind, body=b''):
    return (2 + len(body)).to_bytes(2, 'big') + kind.to_bytes(2, 'big') + body


def capture(chunks):
    streams = dict(c2s=b'', s2c=b'')
    events = []
    for direction, data in chunks:
        events.append(dict(t_mono=len(events) + 1.0, dir=direction,
                           offset=len(streams[direction]), len=len(data)))
        streams[direction] += data
    return streams['c2s'], streams['s2c'], events


GOOD = [('c2s', frame(0x34, b'opaque-key')),
        ('s2c', frame(0x35, b'opaque-response')),
        ('c2s', frame(0x36) + frame(0x30, b'opaque-encrypted-body'))]


def classify(chunks, upstream: int | None = 21001, connections=1, errors=None):
    return protocol.classify(*capture(chunks), upstream_port=upstream,
                             connections=connections, errors=errors or [])


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def wait_until(condition):
    deadline = time.monotonic() + 2
    while not condition():
        if time.monotonic() > deadline:
            raise AssertionError('Synthetic socket observation timed out')
        time.sleep(0.005)


def recv_exact(sock, count):
    data = b''
    while len(data) < count:
        chunk = sock.recv(count - len(data))
        if not chunk:
            raise AssertionError('Unexpected synthetic EOF')
        data += chunk
    return data


def install_capture(raw, chunks=GOOD, upstream: int | None = 21001, connections=1, errors=None):
    """Write explicitly synthetic collector files for adapter tests only."""
    root = Path(raw['owned_temp']) / 'evidence'
    config = raw['controller_config']
    config.update(protocol_port=21000, upstream_port=upstream,
                  target_args=['127.0.0.1', '21000', 'ginei00', '1', 'dummy'])
    raw['config_sha256'] = write_json(root.parent / 'controller-config.json', config)
    binding = dict(run_id=raw['run_id'], manifest_sha256=raw['manifest_sha256'],
                   config_sha256=raw['config_sha256'], target=raw['controller']['target'])
    # Update the I1 synthetic envelope to the new config binding.
    write_json(root / 'synthetic.json', binding)
    c2s, s2c, events = capture(chunks)
    (root / 'protocol-c2s.bin').write_bytes(c2s)
    (root / 'protocol-s2c.bin').write_bytes(s2c)
    (root / 'protocol.jsonl').write_text(''.join(json.dumps(e) + '\n' for e in events))
    result = protocol.classify(c2s, s2c, events, upstream_port=upstream,
                               connections=connections, errors=errors or [])
    write_json(root / 'protocol.json', dict(binding, port=21000, upstream_port=upstream,
        connections=connections, errors=errors or [], result=result,
        captures={n: hashlib.sha256((root / n).read_bytes()).hexdigest()
                  for n in (*protocol.RAW_NAMES.values(), 'protocol.jsonl')}))
    raw['protocol_check'] = result
    raw['evidence_files'] = [dict(path=str(p), sha256=hashlib.sha256(p.read_bytes()).hexdigest())
                             for p in sorted(root.iterdir())]
    return root


class ClassificationTests(unittest.TestCase):
    def test_complete_order_passes_without_body_decryption(self):
        result = classify(GOOD)
        self.assertEqual(result['status'], 'PASS')
        self.assertEqual([f['type'] for f in result['frames']], [0x34, 0x35, 0x36, 0x30])

    def test_fragmented_headers_bodies_and_coalesced_frames(self):
        for width in range(1, 12):
            chunks = [(direction, data[i:i + width]) for direction, data in GOOD
                      for i in range(0, len(data), width)]
            self.assertEqual(classify(chunks)['status'], 'PASS')

    def test_partials_and_no_connection_are_not_run(self):
        for chunks, connections in (([], 0), (GOOD[:1], 1), (GOOD[:2], 1),
                                    ([*GOOD[:2], ('c2s', frame(0x36))], 1)):
            self.assertEqual(classify(chunks, connections=connections)['status'], 'NOT_RUN')
        self.assertEqual(classify(GOOD[:1], upstream=None)['status'], 'NOT_RUN')
        self.assertIn('partial: first 0x34', classify(GOOD[:1])['reason'])

    def test_truncated_length_header_and_body_fail_even_without_upstream(self):
        for data in (b'\x00', b'\x00\x01\x34', b'\x00\x02\x00', frame(0x34)[:-1], b'\xff\xff\x00\x34'):
            for upstream in (None, 21001):
                with self.subTest(data=data, upstream=upstream):
                    self.assertEqual(classify([('c2s', data)], upstream=upstream)['status'], 'FAIL')
        self.assertEqual(classify([*GOOD, ('c2s', b'\x00')])['status'], 'FAIL')

    def test_order_direction_duplicate_handshake_and_clear_header_fail(self):
        for chunks in ([GOOD[1], GOOD[0], GOOD[2]],
                       [('s2c', frame(0x34))], [GOOD[0], GOOD[0]],
                       [*GOOD[:1], ('s2c', b'\x00\x06\x00\x00\x00\x00\x00\x35')],
                       [*GOOD, ('c2s', frame(0x36))], [('c2s', frame(0x30))]):
            self.assertEqual(classify(chunks)['status'], 'FAIL')

    def test_additional_connection_upstream_error_and_no_upstream_response_fail(self):
        self.assertEqual(classify(GOOD, connections=2)['status'], 'FAIL')
        self.assertEqual(classify(GOOD, errors=['Upstream disconnected'])['status'], 'FAIL')
        self.assertEqual(classify(GOOD, upstream=None)['status'], 'FAIL')
        self.assertEqual(classify(GOOD, connections=0)['status'], 'FAIL')

    def test_timeline_offsets_lengths_times_and_unindexed_bytes_fail(self):
        c2s, s2c, events = capture(GOOD)
        for key, value in (('offset', 1), ('len', 0), ('len', 99999), ('dir', 'bad'),
                           ('t_mono', float('nan')), ('t_mono', -1)):
            bad = copy.deepcopy(events)
            bad[1][key] = value
            self.assertEqual(protocol.classify(c2s, s2c, bad, upstream_port=21001,
                                              connections=1, errors=[])['status'], 'FAIL')
        self.assertEqual(protocol.classify(c2s + b'x', s2c, events, upstream_port=21001,
                                          connections=1, errors=[])['status'], 'FAIL')

    def test_capture_and_frame_count_limits_fail_closed(self):
        for name, limit in (('MAX_BYTES', 4), ('MAX_EVENTS', 1), ('MAX_FRAMES', 3)):
            with self.subTest(limit=name), patch.object(protocol, name, limit):
                self.assertEqual(classify(GOOD)['status'], 'FAIL')


class RecorderTests(unittest.TestCase):
    def test_only_loopback_and_distinct_valid_ports(self):
        for host in ('0.0.0.0', '::', 'localhost', '192.168.1.1'):
            with self.assertRaises(ValueError):
                protocol.LoopbackRecorder(Path('unused'), 21000, host=host)
        for port in (0, 1023, 65536, True):
            with self.assertRaises(ValueError):
                protocol.LoopbackRecorder(Path('unused'), port)
        with self.assertRaises(ValueError):
            protocol.LoopbackRecorder(Path('unused'), 21000, 21000)

    def test_no_upstream_never_synthesizes_and_detects_second_connection(self):
        for extra in (False, True):
            with self.subTest(extra=extra), tempfile.TemporaryDirectory() as tmp:
                recorder = protocol.LoopbackRecorder(tmp, free_port()).start()
                assert recorder.listener is not None and recorder.thread is not None
                self.assertEqual(recorder.listener.getsockname()[0], '127.0.0.1')
                try:
                    with socket.create_connection(('127.0.0.1', recorder.port), timeout=1) as client:
                        client.sendall(GOOD[0][1])
                        wait_until(lambda: recorder.offsets['c2s'] == len(GOOD[0][1]))
                        client.settimeout(0.05)
                        with self.assertRaises(socket.timeout):
                            client.recv(1)
                        if extra:
                            with socket.create_connection(('127.0.0.1', recorder.port), timeout=1):
                                wait_until(lambda: recorder.connections == 2)
                finally:
                    result = recorder.finish(dict(run_id='synthetic-only'))
                self.assertEqual(result['status'], 'FAIL' if extra else 'NOT_RUN')
                self.assertFalse(recorder.thread.is_alive())
                self.assertEqual(Path(tmp, 'protocol-c2s.bin').read_bytes(), GOOD[0][1])
                self.assertEqual(Path(tmp, 'protocol-s2c.bin').read_bytes(), b'')

    def test_no_connection_and_port_collision(self):
        with tempfile.TemporaryDirectory() as tmp:
            recorder = protocol.LoopbackRecorder(tmp, free_port()).start()
            try:
                with self.assertRaises(OSError):
                    protocol.LoopbackRecorder(tmp, recorder.port).start()
            finally:
                result = recorder.finish({})
            self.assertEqual(result['status'], 'NOT_RUN')

    def test_byte_exact_bidirectional_relay(self):
        with tempfile.TemporaryDirectory() as tmp, socket.socket() as upstream:
            upstream.bind(('127.0.0.1', 0))
            upstream.listen(1)
            upstream.settimeout(2)
            release, done = threading.Event(), threading.Event()
            server_errors = []
            def serve():
                try:
                    with upstream.accept()[0] as peer:
                        peer.settimeout(2)
                        self.assertEqual(recv_exact(peer, len(GOOD[0][1])), GOOD[0][1])
                        peer.sendall(GOOD[1][1])
                        self.assertEqual(recv_exact(peer, len(GOOD[2][1])), GOOD[2][1])
                        done.set()
                        release.wait(2)
                except Exception as error:
                    server_errors.append(str(error))
            thread = threading.Thread(target=serve)
            thread.start()
            recorder = protocol.LoopbackRecorder(tmp, free_port(), upstream.getsockname()[1]).start()
            try:
                with socket.create_connection(('127.0.0.1', recorder.port), timeout=2) as client:
                    client.sendall(GOOD[0][1])
                    self.assertEqual(recv_exact(client, len(GOOD[1][1])), GOOD[1][1])
                    client.sendall(GOOD[2][1])
                    self.assertTrue(done.wait(2))
            finally:
                result = recorder.finish(dict(run_id='synthetic-only'))
                release.set()
                thread.join(3)
            self.assertFalse(thread.is_alive())
            self.assertEqual(server_errors, [])
            self.assertEqual(result['status'], 'PASS', result)
            self.assertEqual(Path(tmp, 'protocol-c2s.bin').read_bytes(), GOOD[0][1] + GOOD[2][1])
            self.assertEqual(Path(tmp, 'protocol-s2c.bin').read_bytes(), GOOD[1][1])

    def test_upstream_disconnect_or_connection_refused_fails(self):
        with tempfile.TemporaryDirectory() as tmp, socket.socket() as upstream:
            upstream.bind(('127.0.0.1', 0))
            upstream.listen(1)
            upstream.settimeout(2)
            recorder = protocol.LoopbackRecorder(tmp, free_port(), upstream.getsockname()[1]).start()
            try:
                with socket.create_connection(('127.0.0.1', recorder.port), timeout=1):
                    peer, _ = upstream.accept()
                    peer.close()
                    wait_until(lambda: bool(recorder.errors))
            finally:
                result = recorder.finish({})
            self.assertEqual(result['status'], 'FAIL')
            self.assertIn('Upstream disconnected', recorder.errors)
        with tempfile.TemporaryDirectory() as tmp:
            recorder = protocol.LoopbackRecorder(tmp, free_port(), free_port()).start()
            try:
                with socket.create_connection(('127.0.0.1', recorder.port), timeout=1):
                    wait_until(lambda: bool(recorder.errors))
            finally:
                result = recorder.finish({})
            self.assertEqual(result['status'], 'FAIL')


class AdapterTests(unittest.TestCase):
    def test_bound_evidence_recomputed_but_platform_never_passes(self):
        for chunks, upstream, expected in ((GOOD, 21001, 'PASS'), (GOOD[:1], None, 'NOT_RUN'),
                                           ([('c2s', b'\x00')], 21001, 'FAIL')):
            with self.subTest(expected=expected), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                run, raw, pin = synthetic(root)
                evidence = install_capture(raw, chunks, upstream)
                self.assertTrue(report.same_run_provenance(run, run / 'runtime.json', raw, 'b' * 40, pin, root))
                result = report.product_report('synthetic', 'b' * 40, raw)
                report.apply_protocol_check(result, raw)
                self.assertEqual(result['platforms']['windows']['checks'][-1]['status'], expected)
                self.assertEqual(result['platforms']['windows']['status'], 'FAIL' if expected == 'FAIL' else 'NOT_RUN')
                self.assertFalse(result['product_pass'])
                self.assertIsNone(result['windows_qa_receipt'])
                self.assertEqual(protocol.check_evidence(evidence, raw['controller_config']), raw['protocol_check'])

    def test_missing_changed_companion_ports_or_claimed_result_rejected(self):
        for fault in ('missing', 'changed', 'port', 'target-port', 'claim', 'runtime', 'missing-envelope'):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory() as tmp:
                _, raw, _ = synthetic(Path(tmp))
                root = install_capture(raw)
                if fault == 'missing':
                    (root / 'protocol-c2s.bin').unlink()
                elif fault == 'changed':
                    (root / 'protocol.jsonl').write_text('{}\n')
                elif fault == 'port':
                    raw['controller_config']['upstream_port'] = None
                elif fault == 'target-port':
                    raw['controller_config']['target_args'][1] = '9999'
                elif fault == 'runtime':
                    raw['protocol_check']['status'] = 'NOT_RUN'
                elif fault == 'missing-envelope':
                    (root / 'protocol.json').unlink()
                else:
                    envelope = report.read_object(root / 'protocol.json')
                    envelope['result']['status'] = 'NOT_RUN'
                    write_json(root / 'protocol.json', envelope)
                with self.assertRaises((OSError, ValueError)):
                    report.apply_protocol_check(report.product_report('synthetic', 'b' * 40, raw), raw)

    def test_static_inventory_and_supervisor_placement(self):
        self.assertIn('qa_protocol.py', runner.OWN_CODE)
        source = (HERE / 'qa_prestart_runner.py').read_text()
        controller = source[source.index('def controller('):source.index('def run(')]
        self.assertNotIn('LoopbackRecorder', controller)
        supervisor = source[source.index('def run('):source.index('def main(')]
        self.assertLess(supervisor.index('recorder.start()'), supervisor.index('wp.ResumeThread(thread)'))
        self.assertLess(supervisor.index('wj.TerminateJobObject(outer'), supervisor.index('recorder.finish('))
        self.assertLess(supervisor.index('recorder.finish('), supervisor.index("report['evidence_files']"))

    def test_cli_check_pass_keeps_exit3_and_check_fail_exit2_single_json(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            run, raw, pin = synthetic(root)
            repo = root / 'repo'
            repo.mkdir()
            def git(*args):
                return subprocess.check_output(['git', '-C', str(repo), *args], stderr=subprocess.DEVNULL, text=True).strip()
            git('init')
            git('-c', 'user.name=Synthetic', '-c', 'user.email=test@example.invalid', 'commit', '--allow-empty', '-m', 'synthetic')
            head = git('rev-parse', 'HEAD')
            binding = report.read_object(run / 'invocation.json')
            binding.update(repo=str(repo), before=dict(head=head, dirty=''), after=dict(head=head, dirty=''))
            write_json(run / 'invocation.json', binding)
            for index, (chunks, status, code) in enumerate(((GOOD, 'PASS', 3), ([('c2s', b'\x00')], 'FAIL', 2))):
                install_capture(raw, chunks)
                runtime_pin = write_json(run / 'runtime.json', raw)
                result = subprocess.run([sys.executable, '-I', '-S', '-B', str(HERE / 'qa_product_report.py'),
                    '--repo', str(repo), '--head', head, '--same-run', str(run), '--manifest-sha256', pin,
                    '--runtime-report', str(run / 'runtime.json'), '--runtime-report-sha256', runtime_pin,
                    '--out', str(run / f'product-{index}.json')], capture_output=True, text=True)
                self.assertEqual(result.returncode, code, result.stderr + result.stdout)
                document = json.loads(result.stdout)
                self.assertTrue(document['runtimeHeadProvenanceVerified'])
                self.assertEqual(document['platforms']['windows']['checks'][-1]['status'], status)
                self.assertNotEqual(document['platforms']['windows']['status'], 'PASS')
                self.assertFalse(document['product_pass'])

    def test_upstream_cli_driver_and_powershell_forwarding(self):
        values = dict(head='b' * 40, copy='E:/HermesQA/copy', python='E:/HermesQA/python.exe',
                      manifest='E:/HermesQA/manifest.json', manifest_sha256='a' * 64,
                      app_root='E:/HermesQA/app', temp_parent='E:/HermesQA/temp', port=21000,
                      observe_seconds=5, out_root='E:/HermesQA/out')
        self.assertIn("-UpstreamPort '21001'", driver.render_driver(**values, upstream_port=21001))
        self.assertNotIn('UpstreamPort', driver.render_driver(**values))
        for port in (21000, 0, 65536, True):
            with self.assertRaises(ValueError):
                driver.render_driver(**values, upstream_port=port)
        source = (HERE / 'windows-product-qa.ps1').read_text()
        self.assertIn("@('--upstream-port', [string]$UpstreamPort)", source)
        for port in (0, 20000, 65536):
            result = subprocess.run([sys.executable, str(HERE / 'qa_prestart_runner.py'), '--repo', 'unused',
                '--temp-parent', 'unused', '--manifest', 'unused', '--report', 'unused', '--product-logh',
                '--executable', 'unused', '--app-root', 'unused', '--upstream-port', str(port)],
                capture_output=True, text=True)
            self.assertEqual(result.returncode, 2)
            self.assertIn('upstream-port', result.stderr)
            self.assertEqual(result.stdout, '')


if __name__ == '__main__':
    unittest.main()
