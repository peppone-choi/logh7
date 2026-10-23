"""Linux synthetic/static tests, never product rendering evidence."""

import json
from pathlib import Path
import tempfile
import hashlib
import shutil
import subprocess
import threading
import unittest
from unittest.mock import Mock, patch

from qa_render import check_render, frame_stddev, judge_render, RenderCollector
import qa_prestart_runner as runner
import qa_product_report as report
from test_qa_product_run import synthetic, write_json

HERE = Path(__file__).parent


def frame(width=400, height=300, flat=False):
    meta = dict(format=22, width=width, height=height, pitch=width * 4,
                interface='D3D9Ex', present_hr=0, frame_no=1)
    row = b''.join(bytes([0 if flat else x * 255 // width] * 3 + [255]) for x in range(width))
    return meta, row * height


class RenderTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'evidence').mkdir()
        self.config = dict(product_logh=True, controller_report=str(self.root / 'controller.json'),
                           manifest_sha256='a' * 64)
        (self.root / 'controller-config.json').write_text('{}')
        self.result = dict(target=dict(pid=42, creation_filetime=99, image_sha256='b' * 64))
        self.collector = RenderCollector(self.config, self.result)
        self.obs = dict(source='product', ready=True, finished=True, present_count=1,
                        present_failures=0, device_failures=0, errors=[])

    def send_frame(self, meta=None, raw=None):
        if meta is None:
            meta, raw = frame()
        deviation = frame_stddev(meta, raw)
        slot = 'both' if deviation is not None and deviation > 3 else 'last'
        self.collector.on_message(dict(type='send', payload=dict(type='render-frame', slot=slot, **meta)), raw)

    def finish(self):
        self.collector.on_message(dict(type='send', payload=dict(type='render-finished', observation=self.obs)), None)
        script = Mock()
        self.collector.script = script
        self.collector.finish()
        return json.loads((self.root / 'evidence/render.json').read_text())

    def test_valid_gradient_and_flat(self):
        meta, raw = frame()
        self.assertGreater(frame_stddev(meta, raw), 3)
        self.assertEqual(frame_stddev(*frame(flat=True)), 0)
        self.assertIsNone(frame_stddev(*frame(399, 300)))
        for key, val in [('format', 20), ('pitch', 1), ('width', 4097), ('height', True)]:
            broken = dict(meta, **{key: val})
            self.assertIsNone(frame_stddev(broken, raw))
        self.assertIsNone(frame_stddev(meta, raw[:-1]))

    def test_first_valid_and_last_bound(self):
        self.send_frame(*frame(flat=True))
        self.send_frame()
        meta, raw = frame(flat=True)
        meta['frame_no'] = 3
        self.obs['present_count'] = 3
        self.send_frame(meta, raw)
        envelope = self.finish()
        self.assertEqual(envelope['run_id'], self.root.name)
        self.assertEqual(envelope['target'], self.result['target'])
        self.assertEqual(check_render(self.root / 'evidence')['status'], 'PASS')
        self.assertEqual(json.loads((self.root / 'evidence/render-first.json').read_text())['frame_no'], 1)
        self.assertEqual(json.loads((self.root / 'evidence/render-last.json').read_text())['frame_no'], 3)
        p = self.root / 'evidence/render-first.bgrx'
        p.write_bytes(b'corrupt')
        self.assertEqual(check_render(p.parent)['status'], 'FAIL')

    def test_flat_and_small_not_pass(self):
        for meta, raw in [frame(flat=True), frame(200, 150)]:
            self.send_frame(meta, raw)
        self.finish()
        self.assertEqual(check_render(self.root / 'evidence')['status'], 'NOT_RUN')

    def test_failure_and_no_present(self):
        self.obs['present_count'] = 0
        self.finish()
        self.assertEqual(check_render(self.root / 'evidence')['status'], 'NOT_RUN')
        for field, value in [('device_failures', 1), ('present_failures', 1), ('errors', ['script error'])]:
            envelope = json.loads((self.root / 'evidence/render.json').read_text())
            envelope['observation'] = dict(self.obs, **{field: value})
            (self.root / 'evidence/render.json').write_text(json.dumps(envelope))
            self.assertEqual(check_render(self.root / 'evidence')['status'], 'FAIL')

    def test_negative_hr_and_script_error(self):
        meta, raw = frame()
        meta['present_hr'] = -2005530520
        self.send_frame(meta, raw)
        self.collector.on_message(dict(type='error', description='synthetic exception'), None)
        self.finish()
        self.assertEqual(check_render(self.root / 'evidence')['status'], 'FAIL')

    def test_first_only_fails(self):
        self.send_frame()
        envelope = self.finish()
        del envelope['frames']['last']
        (self.root / 'evidence/render.json').write_text(json.dumps(envelope))
        self.assertEqual(check_render(self.root / 'evidence')['status'], 'FAIL')

    def test_adapter_propagates_fail_and_not_run(self):
        self.send_frame(*frame(flat=True))
        envelope = self.finish()
        for status, errors in [('NOT_RUN', []), ('FAIL', ['isolated script error'])]:
            with self.subTest(status=status):
                envelope['observation'] = dict(self.obs, errors=errors)
                (self.root / 'evidence/render.json').write_text(json.dumps(envelope))
                r = report.product_report('synthetic', 'c' * 40)
                report.apply_render_check(r, dict(owned_temp=str(self.root), controller=self.result))
                self.assertEqual(r['platforms']['windows']['checks'][2]['status'], status)
                self.assertEqual(r['platforms']['windows']['status'], status)

    def test_adapter_no_aggregate_pass(self):
        self.send_frame()
        self.finish()
        r = report.product_report('synthetic', 'c' * 40)
        report.apply_render_check(r, dict(owned_temp=str(self.root), controller=self.result))
        self.assertEqual(r['platforms']['windows']['checks'][2]['status'], 'PASS')
        self.assertNotEqual(r['platforms']['windows']['status'], 'PASS')
        (self.root / 'evidence/render.json').unlink()
        with self.assertRaises(ValueError):
            report.apply_render_check(r, dict(owned_temp=str(self.root), controller=self.result))
        with self.assertRaises(ValueError):
            report.apply_render_check(r, dict(owned_temp=str(self.root), controller={},
                                             controller_config=dict(render_collector=True)))

    def test_static_order_and_no_interaction(self):
        text = (HERE / 'qa_render_x86.js').read_text()
        for forbidden in ['PrintWindow', 'GetForegroundWindow', 'GetFocus', 'SetForegroundWindow',
                          'SwitchDesktop', 'SendInput', 'keybd_event', 'mouse_event', 'SetCursorPos',
                          'BitBlt', 'capture-frame.js', 'ResetEx', 'ret.replace']:
            self.assertNotIn(forbidden, text)
        source = (HERE / 'qa_prestart_runner.py').read_text()
        self.assertLess(source.index("result['pre_resume_receipt'] = timed_receipt(guard)"), source.index('render.install(guard.session)'))
        self.assertLess(source.index('render.install(guard.session)'), source.index('wp.ResumeThread(thread)'))
        self.assertIn('qa_render.py', runner.OWN_CODE)
        self.assertIn('qa_render_x86.js', runner.OWN_CODE)

    def test_pure_threshold_and_fail_closed(self):
        meta, _ = frame()
        raw = (bytes([0,0,0,255]) * 200 + bytes([6,6,6,255]) * 200) * 300
        meta['raw_sha256'] = hashlib.sha256(raw).hexdigest()
        self.assertEqual(frame_stddev(meta, raw), 3)
        self.assertEqual(judge_render(self.obs, [(meta, raw)])['status'], 'NOT_RUN')
        for field, value in [('present_failures', False), ('device_failures', -1),
                             ('finished', False), ('present_count', True), ('errors', None)]:
            obs = dict(self.obs, **{field: value})
            self.assertEqual(judge_render(obs, [(meta, raw)])['status'], 'FAIL')

    def test_frame_binding_and_missing_companion(self):
        self.send_frame()
        self.finish()
        path = self.root / 'evidence/render-first.json'
        original = json.loads(path.read_text())
        for key in ('run_id', 'manifest_sha256', 'config_sha256', 'target'):
            meta = dict(original, **{key: 'wrong'})
            path.write_text(json.dumps(meta))
            self.assertEqual(check_render(path.parent)['status'], 'FAIL')
        path.write_text(json.dumps(original))
        path.unlink()
        self.assertEqual(check_render(path.parent)['status'], 'FAIL')

    def test_install_uses_same_session_separate_script_and_ready(self):
        session = Mock()
        session.create_script.return_value.exports_sync.renderstatus.return_value = {'ready': True}
        self.collector.install(session)
        session.create_script.assert_called_once()
        script = session.create_script.return_value
        script.on.assert_called_once_with('message', self.collector.on_message)
        script.load.assert_called_once()
        script.exports_sync.renderstatus.return_value = {'ready': False}
        with self.assertRaises(RuntimeError):
            self.collector.install(session)

    def test_drain_failure_records_fail_not_pass(self):
        self.collector.script = Mock()
        self.collector.script.exports_sync.finishrender.side_effect = RuntimeError('synthetic RPC failure')
        self.collector.finish()
        self.assertEqual(check_render(self.root / 'evidence')['status'], 'FAIL')
        self.collector.finish()  # Snapshot is idempotent; never overwrite the envelope.

    def test_async_drain_precedes_resize_outside_callback_lock(self):
        meta, raw = frame()
        def deliver():
            for slot in ('first', 'last'):
                self.collector.on_message(dict(type='send', payload=dict(
                    type='render-frame', slot=slot, **meta)), raw)
            self.collector.on_message(dict(type='send', payload=dict(
                type='render-finished', observation=self.obs)), None)
        worker = threading.Thread(target=deliver)
        self.addCleanup(worker.join)
        self.collector.script = Mock()
        self.collector.script.exports_sync.finishrender.side_effect = worker.start
        def resize_after_drain(*args):
            self.assertTrue(self.collector.finished.is_set())
            self.assertTrue(self.collector.lock.acquire(blocking=False))
            self.collector.lock.release()
            return 10
        with patch('qa_render.frame_stddev', side_effect=resize_after_drain) as resize:
            self.collector.finish()
        self.assertEqual(resize.call_count, 1)
        self.assertEqual(self.collector.errors, [])
        self.assertEqual(check_render(self.root / 'evidence')['status'], 'PASS')

    def test_invalid_first_slot_fails_even_with_valid_last(self):
        for meta, raw in (frame(flat=True), frame(200, 150),
                          (frame()[0], (bytes([0, 0, 0, 255]) * 200 +
                                        bytes([6, 6, 6, 255]) * 200) * 300)):
            with self.subTest(width=meta['width'], raw_prefix=raw[:4]):
                self.collector = RenderCollector(self.config, self.result)
                (self.root / 'evidence/render.json').unlink(missing_ok=True)
                self.collector.on_message(dict(type='send', payload=dict(
                    type='render-frame', slot='first', **meta)), raw)
                last_meta, last_raw = frame()
                self.collector.on_message(dict(type='send', payload=dict(
                    type='render-frame', slot='last', **last_meta)), last_raw)
                envelope = self.finish()
                self.assertIn('First slot is not valid', envelope['observation']['errors'])
                self.assertEqual(check_render(self.root / 'evidence')['status'], 'FAIL')

    def test_same_run_inventory_binds_raw_and_all_metadata(self):
        run, runtime, pin = synthetic(self.root)
        owned = Path(runtime['owned_temp'])
        config = dict(runtime['controller_config'], manifest_sha256=pin,
                      controller_report=str(owned / 'controller.json'))
        collector = RenderCollector(config, runtime['controller'])
        meta, raw = frame()
        collector.on_message(dict(type='send', payload=dict(type='render-frame', slot='both', **meta)), raw)
        collector.on_message(dict(type='send', payload=dict(type='render-finished', observation=self.obs)), None)
        collector.script = Mock()
        collector.finish()
        runtime['evidence_files'] = [dict(path=str(p), sha256=runner.digest(p))
                                     for p in (owned / 'evidence').iterdir()]
        write_json(run / 'runtime.json', runtime)
        args = (run, run / 'runtime.json', runtime, 'b' * 40, pin, self.root)
        self.assertTrue(report.same_run_provenance(*args))
        r = report.product_report('synthetic', 'b' * 40)
        report.apply_render_check(r, runtime)
        self.assertEqual(r['platforms']['windows']['checks'][2]['status'], 'PASS')
        (owned / 'evidence/render-last.bgrx').write_bytes(b'corrupt')
        with self.assertRaises(ValueError):
            report.same_run_provenance(*args)

    @unittest.skipUnless(shutil.which('node'), 'Node required for synthetic JS vtable test')
    def test_js_synthetic_vtables(self):
        result = subprocess.run(['node', str(HERE / 'test_qa_render.js')],
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()
