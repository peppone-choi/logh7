"""Synthetic safety/regression tests; contains no game text. Author: 최병호."""
import copy
import struct
import tempfile
from pathlib import Path
import unittest
import msgdat


class CodecTests(unittest.TestCase):
    def sample(self):
        return b'HFWR' + struct.pack('<III4I', 0, 2, 2, 0, 2, 0, 0) + b'$name$ test\0\0'

    def test_roundtrip_and_korean(self):
        data = self.sample()
        ref = msgdat.decode(data)
        self.assertEqual(msgdat.encode(ref), data)
        doc = copy.deepcopy(ref)
        doc['strings'][0] = '$name$ 한글'
        out = msgdat.encode(doc, ref, 'cp949')
        self.assertEqual(msgdat.decode(out, 'cp949')['strings'][0], '$name$ 한글')
        with self.assertRaises(UnicodeEncodeError):
            msgdat.encode(doc, ref, 'cp932')

    def test_tokens_and_structure_rejected(self):
        ref = msgdat.decode(self.sample())
        for value in ('test', '$other$ test', '$name$ $name$'):
            doc = copy.deepcopy(ref)
            doc['strings'][0] = value
            with self.assertRaises(ValueError):
                msgdat.encode(doc, ref)
        doc = copy.deepcopy(ref)
        doc['indices'][1] = 1
        with self.assertRaises(ValueError):
            msgdat.encode(doc, ref)

    def test_all_truncations_rejected(self):
        data = self.sample()
        for n in range(len(data)):
            with self.subTest(n=n), self.assertRaises((ValueError, struct.error)):
                msgdat.decode(data[:n])
        with self.assertRaises(ValueError):
            msgdat.decode(data + b'junk')

    def test_gfwr_utf16_units(self):
        word = '가😀'.encode('utf-16-le')
        data = b'GFWR' + struct.pack('<IIII', 0, 123, 1, len(word)//2) + word
        ref = msgdat.decode(data)
        self.assertEqual(msgdat.encode(ref), data)
        ref['strings'][0] = '😀나'
        self.assertEqual(msgdat.decode(msgdat.encode(ref))['strings'], ['😀나'])

    def test_duplicate_cp932_mapping(self):
        data = b'HFWR' + struct.pack('<III4I', 0, 1, 2, 0, 1, 0, 0) + bytes.fromhex('fa40') + b'\0'
        self.assertEqual(msgdat.encode(msgdat.decode(data)), data)

    def test_patch_slots_and_rejections(self):
        data = bytearray(0x800)
        data[:2] = b'MZ'
        struct.pack_into('<I', data, 0x3c, 0x80)
        data[0x80:0x84] = b'PE\0\0'
        struct.pack_into('<H', data, 0x86, 1)
        struct.pack_into('<H', data, 0x94, 0xe0)
        struct.pack_into('<H', data, 0x98, 0x10b)
        struct.pack_into('<I', data, 0x98 + 28, 0x400000)
        struct.pack_into('<IIII', data, 0x178 + 8, 0x500, 0x36e000, 0x500, 0x200)
        data[0x440:0x44e] = 'ＭＳ ゴシック'.encode('cp932') + b'\0'
        data[0x5fc:0x605] = b'Japanese\0'
        root = Path(__file__).resolve().parents[2] / 'work/l10n/tests'
        root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=root) as folder:
            source, output = Path(folder) / 'source.exe', Path(folder) / 'copy.exe'
            source.write_bytes(data)
            with self.assertRaises(ValueError):
                msgdat.patch(source, output, expected_sha256='0' * 64)
            with self.assertRaises(ValueError):
                msgdat.patch(source, output, font='가' * 7)
            report = msgdat.patch(source, output, expected_sha256=msgdat.sha(data))
            self.assertTrue(report['source_unchanged'])
            self.assertEqual(source.read_bytes(), data)
            self.assertEqual(output.stat().st_size, len(data))
            result = output.read_bytes()
            self.assertEqual(result[0x5fc:0x605], b'Korean\0\0\0')
            self.assertEqual(result[0x440:0x44e], '굴림'.encode('cp949').ljust(14, b'\0'))
            with self.assertRaises(ValueError):
                msgdat.patch(source, source)
            with self.assertRaises(ValueError):
                msgdat.patch(source, output)
            source.write_bytes(bytes(len(data)))
            with self.assertRaises(ValueError):
                msgdat.patch(source, Path(folder) / 'bad.exe')

    def test_copy_guard(self):
        root = Path(__file__).resolve().parents[2] / 'work/l10n/tests'
        root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=root) as folder:
            target = Path(folder) / 'existing'
            target.write_bytes(b'keep')
            with self.assertRaises(FileExistsError):
                msgdat.write_new(target, b'change')
            self.assertEqual(target.read_bytes(), b'keep')
        with self.assertRaises(ValueError):
            msgdat.write_new(Path('E:/logh7-original/forbidden'), b'no')


if __name__ == '__main__':
    unittest.main()

