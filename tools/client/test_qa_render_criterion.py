"""Linux Pillow oracle and Node parity; synthetic raw pixels, no product run."""
import json
from pathlib import Path
import random
import shutil
import subprocess
import tempfile
import unittest

from PIL import Image, ImageStat
from qa_render import frame_stddev

HERE = Path(__file__).parent


class CriterionTests(unittest.TestCase):
    def test_pillow_python_and_node(self):
        rng = random.Random(7919)
        cases = [
            ('noise8', 800, 600, lambda x, y: 128 + rng.randint(-8, 8)),
            ('columns1', 800, 600, lambda x, y: 255 * (x % 2)),
            ('checker1', 800, 600, lambda x, y: 255 * ((x + y) % 2)),
            ('columns5', 800, 600, lambda x, y: 255 * (x % 5 == 0)),
            ('gradient', 640, 480, lambda x, y: x * 255 // 639),
            ('below3', 800, 600, lambda x, y: 122 if x < 304 else 128),
            ('above3', 800, 600, lambda x, y: 121 if x < 224 else 128),
            ('flat', 400, 300, lambda x, y: 128),
            ('text', 1024, 768, lambda x, y: 255 if x % 40 < 2 and y % 30 < 20 else 0),
            ('rgb-padding-odd', 403, 307, lambda x, y: (x * 71 + y * 17) % 256),
        ]
        with tempfile.TemporaryDirectory() as directory:
            inputs = []
            for name, w, h, pixel in cases:
                pitch = w * 4 + 12
                raw = bytearray([231]) * (pitch * h)
                for y in range(h):
                    for x in range(w):
                        v = pixel(x, y)
                        rgb = (v, (v * 3) % 256, 255 - v) if name == 'rgb-padding-odd' else (v, v, v)
                        i = y * pitch + x * 4
                        raw[i:i+4] = bytes((*rgb, (x + y) % 256))
                meta = dict(width=w, height=h, pitch=pitch, format=21)
                image = Image.frombytes('RGB', (w, h), bytes(raw), 'raw', 'BGRX', pitch, 1)
                expected = max(ImageStat.Stat(image.resize((160, 90))).stddev)
                actual = frame_stddev(meta, raw)
                assert actual is not None
                with self.subTest(name=name):
                    self.assertLess(abs(actual - expected), 0.05)
                    self.assertEqual(actual > 3, expected > 3)
                    if name == 'below3':
                        self.assertTrue(2.8 < expected < 3)
                    if name == 'above3':
                        self.assertTrue(3 < expected < 3.2)
                path = Path(directory) / (name + '.bgrx')
                path.write_bytes(raw)
                inputs.append(dict(name=name, meta=meta, path=str(path), expected=expected, python=actual))
            self.assertIsNotNone(shutil.which('node'), 'Node required for criterion parity')
            result = subprocess.run(['node', str(HERE / 'test_qa_render_criterion.js')],
                                    input=json.dumps(inputs), capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            values = json.loads(result.stdout)
            self.assertEqual(len(values), len(inputs))
            for item, value in zip(inputs, values):
                self.assertAlmostEqual(item['python'], value['deviation'], places=9)
                print(f"{item['name']}: Pillow={item['expected']:.9f} Python={item['python']:.9f} "
                      f"JS={value['deviation']:.9f} valid={value['valid']}")


if __name__ == '__main__':
    unittest.main()
