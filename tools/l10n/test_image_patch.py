"""게임 자산 없는 이미지 적용/복구 회귀 검사. 작성자: Peppone Choi."""
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch

import image_patch as images


def tga(index=0, width=2, height=2, footer=False):
    header = struct.pack('<BBBHHBHHHHBB', 0, 1, 1, 0, 2, 32, 0, 0,
                         width, height, 8, 0)
    palette = bytes((0, 0, 0, 0, 255, 255, 255, 255))
    return header + palette + bytes([index] * (width * height)) + (
        b'\0' * 8 + b'TRUEVISION-XFILE.\0' if footer else b'')


class ImagePatchTests(unittest.TestCase):
    def setUp(self):
        work = Path(__file__).resolve().parents[2] / 'work/l10n/image-tests'
        work.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=work)
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source, self.drafts, self.target, self.package = (
            self.root / name for name in ('source', 'drafts', 'client', 'package'))
        self.selection = self.root / 'selection.json'
        self.rows = []
        for relative in ('icon_normal/test.tga', 'icon_down/test.tga'):
            for root, data in ((self.source, tga()), (self.target, tga()), (self.drafts, tga(1))):
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
            self.rows.append(dict(asset=relative, original_sha256=images.sha(tga()),
                                  replacement_sha256=images.sha(tga(1))))
        self.save_selection()

    def save_selection(self):
        self.selection.write_text(json.dumps(self.rows), encoding='utf-8')

    def prepare(self):
        return images.prepare(self.source, self.drafts, self.selection, self.package)

    def test_backup_apply_restore_roundtrip_and_idempotence(self):
        manifest = self.prepare()
        self.assertFalse(manifest['display_validated'])
        self.assertEqual(len(images.verify(self.package)), 2)
        for row in self.rows:
            self.assertEqual((self.package / 'backup' / row['asset']).read_bytes(), tga())
        self.assertEqual(images.install(self.package, self.target)['changed'], 2)
        self.assertEqual(images.install(self.package, self.target)['changed'], 0)
        for row in self.rows:
            self.assertEqual((self.target / row['asset']).read_bytes(), tga(1))
            self.assertEqual((self.source / row['asset']).read_bytes(), tga())
        self.assertEqual(images.install(self.package, self.target, restore=True)['changed'], 2)
        self.assertEqual(images.install(self.package, self.target, restore=True)['changed'], 0)
        for row in self.rows:
            self.assertEqual((self.target / row['asset']).read_bytes(), tga())

    def test_source_hash_mismatch_creates_no_package(self):
        self.rows[1]['original_sha256'] = '0' * 64
        self.save_selection()
        with self.assertRaisesRegex(ValueError, 'input hash mismatch'):
            self.prepare()
        self.assertFalse(self.package.exists())

    def test_replacement_hash_mismatch_and_existing_package(self):
        self.rows[0]['replacement_sha256'] = '0' * 64
        self.save_selection()
        with self.assertRaises(ValueError):
            self.prepare()
        self.assertFalse(self.package.exists())
        self.package.mkdir()
        with self.assertRaises(FileExistsError):
            self.prepare()

    def test_source_and_original_tree_are_never_targets(self):
        self.prepare()
        for path in (self.source, self.drafts, Path('E:/logh7-original/never-created'), self.package / 'client'):
            with self.subTest(path=path), self.assertRaises(ValueError):
                images.install(self.package, path)
        with self.assertRaises(ValueError):
            images.prepare(self.source, self.drafts, self.selection,
                           Path('E:/logh7-original/never-created'))
        self.assertEqual((self.source / self.rows[0]['asset']).read_bytes(), tga())

    def test_target_preflight_does_not_partially_apply_or_restore(self):
        self.prepare()
        second = self.target / self.rows[1]['asset']
        second.write_bytes(b'unrelated user edit')
        for restore in (False, True):
            with self.assertRaisesRegex(ValueError, 'unrelated changes'):
                images.install(self.package, self.target, restore)
            self.assertEqual((self.target / self.rows[0]['asset']).read_bytes(), tga())
            self.assertEqual(second.read_bytes(), b'unrelated user edit')

    def test_package_corruption_is_rejected_before_target_write(self):
        self.prepare()
        for folder in ('backup', 'payload'):
            path = self.package / folder / self.rows[1]['asset']
            before = path.read_bytes()
            path.write_bytes(before[:-1])
            with self.assertRaisesRegex(ValueError, 'package hash mismatch'):
                images.install(self.package, self.target)
            self.assertEqual((self.target / self.rows[0]['asset']).read_bytes(), tga())
            path.write_bytes(before)

    def test_missing_target_and_mixed_interrupted_state(self):
        self.prepare()
        first = self.target / self.rows[0]['asset']
        first.write_bytes(tga(1))
        self.assertEqual(images.install(self.package, self.target)['changed'], 1)
        first.unlink()
        with self.assertRaises(FileNotFoundError):
            images.install(self.package, self.target, restore=True)
        self.assertEqual((self.target / self.rows[1]['asset']).read_bytes(), tga(1))

    def test_write_failure_rolls_back_already_changed_files(self):
        self.prepare()
        real_write = images.atomic_write
        calls = []

        def fail_second(target, data):
            calls.append(target)
            if len(calls) == 2:
                raise OSError('injected second-file failure')
            return real_write(target, data)

        with patch.object(images, 'atomic_write', side_effect=fail_second):
            with self.assertRaisesRegex(OSError, 'injected'):
                images.install(self.package, self.target)
        for row in self.rows:
            self.assertEqual((self.target / row['asset']).read_bytes(), tga())
        self.assertFalse(list(self.target.rglob('.l10n-*.tmp')))

    def test_path_traversal_devices_and_duplicate_case_are_rejected(self):
        for name in ('../test.tga', '/test.tga', 'a\\test.tga', 'a//b.tga',
                     'C:/test.tga', 'a.tga:stream', 'NUL.tga', 'a./b.tga',
                     'a/COM1.tga', 'a/test.jpg'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                images.asset_path(self.target, name)
        self.rows[1]['asset'] = self.rows[0]['asset'].upper()
        self.save_selection()
        with self.assertRaisesRegex(ValueError, 'duplicate'):
            self.prepare()
        self.assertFalse(self.package.exists())

    def test_tga_metadata_and_alpha_counts(self):
        info = images.tga_info(tga())
        self.assertEqual(info['transparent_pixels'], 4)
        self.assertEqual(info['palette_depth'], 32)
        self.assertEqual(images.tga_info(tga(1))['opaque_pixels'], 4)
        truecolor = struct.pack('<BBBHHBHHHHBB', 0, 0, 2, 0, 0, 0, 0, 0, 2, 2, 32, 8)
        truecolor += bytes((0, 0, 255, 128)) * 4 + b'\0' * 8 + b'TRUEVISION-XFILE.\0'
        self.assertEqual(images.tga_info(truecolor)['alpha_min'], 128)
        self.assertTrue(images.tga_info(truecolor)['footer'])
        for offset, value in ((8, 1), (12, 1), (17, 0x20)):
            changed = bytearray(tga())
            changed[offset] = value
            if offset == 12:  # Keep image length valid after reducing canvas.
                changed = changed[:-2]
            with self.subTest(offset=offset), self.assertRaises(ValueError):
                images.compatible(tga(), bytes(changed))
        with self.assertRaises(ValueError):
            images.compatible(tga(), tga(footer=True))

    def test_tga_truncation_bad_palette_rle_and_extension_rejected(self):
        data = tga()
        for size in range(len(data)):
            with self.subTest(size=size), self.assertRaises(ValueError):
                images.tga_info(data[:size])
        with self.assertRaises(ValueError):
            images.tga_info(tga(2))
        changed = bytearray(data)
        changed[2] = 9
        with self.assertRaises(ValueError):
            images.tga_info(changed)
        with self.assertRaises(ValueError):
            images.tga_info(data + b'trailing metadata')


if __name__ == '__main__':
    unittest.main()
