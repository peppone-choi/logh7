"""Portable probe configuration/recording tests; no client execution. Author: 최병호."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('model_probe', Path(__file__).with_name('probe-model-loader.py'))
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class ModelProbeTests(unittest.TestCase):
    def test_selected_models_are_explicit_and_bounded(self):
        self.assertEqual(probe.model_names(['y001', 'p030']), ['y001', 'p030'])
        for names in ([], ['y001', 'y001'], ['../y001'], ['y００１'], ['y000'] * 33):
            with self.assertRaises(ValueError):
                probe.model_names(names)
        self.assertIn('const probeModels = ["y001", "p030"];', probe.probe_source(['y001', 'p030']))

    def test_recorder_ignores_other_events_and_preserves_existing_buffer(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / 'capture'
            recorder = probe.Recorder(output)
            recorder.receive({'payload': {'type': 'render-frame'}}, b'pixels')
            self.assertFalse(output.exists())
            event = {'payload': {'type': 'analysis-model', 'name': 'y001-0-primary-0-vertices'}}
            recorder.receive(event, b'synthetic')
            with self.assertRaises(FileExistsError):
                recorder.receive(event, b'replacement')
            self.assertEqual((output / 'y001-0-primary-0-vertices.bin').read_bytes(), b'synthetic')

    def test_partial_error_and_missing_models_fail(self):
        recorder = probe.Recorder(Path('unused-test-path'))
        recorder.events = [{'name': 'y001-0-primary-0-vertices'}]
        self.assertFalse(recorder.complete(['y001']))
        recorder.events.append({'name': 'y001-loaded'})
        self.assertFalse(recorder.complete(['y001']))
        recorder.events.append({'name': 'y001-0-primary-0-indices'})
        self.assertTrue(recorder.complete(['y001']))
        self.assertFalse(recorder.complete(['y001', 'y002']))
        recorder.events.append({'name': 'y005-error'})
        self.assertFalse(recorder.complete(['y001']))

    def test_recorder_rejects_path_escape_and_unexpected_binary(self):
        recorder = probe.Recorder(Path('unused-test-path'))
        for name, data in (('../y001-loaded', None), ('y001-loaded', b'raw'),
                           ('y001-0-primary-0-vertices', None)):
            with self.assertRaises(ValueError):
                recorder.receive({'payload': {'type': 'analysis-model', 'name': name}}, data)


if __name__ == '__main__':
    unittest.main()
