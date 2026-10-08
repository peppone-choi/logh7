"""Synthetic preview and native/offline comparison checks. Author: 최병호."""
import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest

from asset_repro import catalogue, compare, geometry_image, save_png, texture_image


class AssetReproTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.geometry = self.root / 'geometry'
        self.geometry.mkdir()
        self.native = self.root / 'native'
        self.native.mkdir()
        self.vertices = b''.join(struct.pack('<3f', *p) + bytes(12)
                                 for p in ((0, 0, 0), (1, 0, 0), (0, 1, 0)))
        self.indices = struct.pack('<3H', 0, 1, 2)
        self.document = {'models': [{'model': 'y001', 'streams': [
            {'group': 0, 'record': 0, 'vertex_count': 3, 'index_count': 3, 'stride': 24}]}]}
        self.path = self.geometry / 'geometry.json'
        self.path.write_text(json.dumps(self.document), encoding='utf-8')
        (self.geometry / 'y001-0-0-vertices.bin').write_bytes(self.vertices)
        (self.geometry / 'y001-0-0-indices.bin').write_bytes(self.indices)
        self.events = [{'name': 'y001-0-primary-0-vertices', 'model': 'y001',
                        'nv': 3, 'ni': 3, 'stride': 24}, {'name': 'y001-loaded'}]
        self.event_path = self.native / 'model-events.json'
        self.event_path.write_text(json.dumps(self.events), encoding='utf-8')
        (self.native / 'y001-0-primary-0-vertices.bin').write_bytes(self.vertices)
        (self.native / 'y001-0-primary-0-indices.bin').write_bytes(self.indices)

    def test_matching_pair_and_mismatch(self):
        models = catalogue(self.path)
        result = compare(models, self.event_path)
        self.assertTrue(result['complete_observed_pairs'])
        self.assertEqual(result['pairs'][0]['matches'], ['y001-0-0'])
        (self.native / 'y001-0-primary-0-indices.bin').write_bytes(struct.pack('<3H', 0, 2, 1))
        self.assertFalse(compare(models, self.event_path)['complete_observed_pairs'])

    def test_error_partial_and_empty_capture_are_not_success(self):
        for events in ([], self.events[:1], self.events + [{'name': 'y005-error'}],
                       self.events + [{'name': 'y002-loaded'}]):
            self.event_path.write_text(json.dumps(events), encoding='utf-8')
            if not events:
                with self.assertRaises(ValueError):
                    compare(catalogue(self.path), self.event_path)
            else:
                self.assertFalse(compare(catalogue(self.path), self.event_path)['complete_observed_pairs'])

    def test_metadata_cannot_select_external_paths_or_duplicate_streams(self):
        for name in ('../y001', 'y001/../../secret', 'y001\\secret'):
            self.document['models'][0]['model'] = name
            self.path.write_text(json.dumps(self.document), encoding='utf-8')
            with self.assertRaises(ValueError):
                catalogue(self.path)
        self.events[0]['name'] = '../y001-0-primary-0-vertices'
        self.event_path.write_text(json.dumps(self.events), encoding='utf-8')
        with self.assertRaises(ValueError):
            compare({}, self.event_path)

    def test_buffer_lengths_indices_and_nonfinite_coordinates(self):
        for vertices, indices in ((self.vertices[:-1], self.indices),
                                  (self.vertices, struct.pack('<3H', 0, 1, 3)),
                                  (struct.pack('<f', float('nan')) + self.vertices[4:], self.indices)):
            (self.geometry / 'y001-0-0-vertices.bin').write_bytes(vertices)
            (self.geometry / 'y001-0-0-indices.bin').write_bytes(indices)
            with self.assertRaises(ValueError):
                catalogue(self.path)

    def test_missing_selection_and_duplicate_stream(self):
        with self.assertRaises(ValueError):
            catalogue(self.path, ['y009'])
        self.document['models'][0]['streams'] *= 2
        self.path.write_text(json.dumps(self.document), encoding='utf-8')
        with self.assertRaises(ValueError):
            catalogue(self.path)

    @unittest.skipUnless(importlib.util.find_spec('PIL'), 'Pillow is optional for previews')
    def test_geometry_png_and_existing_output_preserved(self):
        from PIL import Image
        output = self.root / 'preview.png'
        save_png(geometry_image(catalogue(self.path)), output, self.geometry)
        before = output.read_bytes()
        with Image.open(output) as image:
            self.assertEqual(image.size, (1000, 500))
            self.assertGreater(len(image.getcolors(500000)), 1)
        with self.assertRaises(FileExistsError):
            save_png(geometry_image(catalogue(self.path)), output, self.geometry)
        self.assertEqual(output.read_bytes(), before)
        with self.assertRaises(ValueError):
            save_png(geometry_image(catalogue(self.path)), self.geometry / 'bad.png', self.geometry)

    @unittest.skipUnless(importlib.util.find_spec('PIL'), 'Pillow is optional for previews')
    def test_texture_atlas_uses_only_planet_bmps(self):
        from PIL import Image
        source = self.root / 'images'
        source.mkdir()
        Image.new('RGB', (20, 10), 'red').save(source / 'p001.bmp')
        Image.new('RGB', (20, 10), 'blue').save(source / 'unrelated.bmp')
        image = texture_image(source)
        self.assertEqual(image.size, (960, 160))
        self.assertEqual(image.getpixel((4, 24)), (255, 0, 0))


if __name__ == '__main__':
    unittest.main()
