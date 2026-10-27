"""Malformed resources and unverified roster data must not become confirmed NPCs. Author: 최병호."""
import copy
from pathlib import Path
import struct
import tempfile
import unittest

from npc_catalogue import blank_character, extract, face_identity, manual_roster, tga_length, validate


def image():
    return (struct.pack('<BBBHHBHHHHBB', 0, 1, 1, 0, 2, 32, 0, 0, 2, 2, 8, 0) +
            bytes([0, 0, 255, 255, 255, 0, 0, 255]) + bytes([0, 1, 1, 0]))


class NpcCatalogueTests(unittest.TestCase):
    def test_review_roster_is_korean_without_assigning_unrecovered_data(self):
        root = Path(__file__).resolve().parents[2]
        rows = manual_roster(root / 'docs/manual/data/initial-card-holders.csv')['characters']
        self.assertEqual(len(rows), 70)
        self.assertTrue(all(any('\uac00' <= ch <= '\ud7a3' for ch in row['name']) for row in rows))
        self.assertTrue(all(row['manual_holder'] and row['name_translation']['source'] for row in rows))
        self.assertTrue(all(row['face_id'] is None and all(v is None for v in row['abilities'].values()) for row in rows))
        validate(dict(schema=1, characters=rows), [])

    def test_hed_holes_and_tga_boundaries(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            hed = bytearray(10840)
            for name, slot in [('oem', 1), ('oam', 201), ('o', 361)]:
                (root / (name + '.tcf')).write_bytes(bytes(50) + image())
                struct.pack_into('<II', hed, slot * 8, 50, len(image()))
            (root / 'tcf.hed').write_bytes(hed)
            rows = extract(root)
            self.assertEqual([r['face_id'] for r in rows], [1, 100001, 110001])
            self.assertEqual([r['index'] for r in rows], [1, 1, 11])
            import base64
            import zlib
            png = base64.b64decode(rows[0]['image'].split(',')[1])
            self.assertEqual(png[:8], b'\x89PNG\r\n\x1a\n')
            length = struct.unpack_from('>I', png, 33)[0]
            raw = zlib.decompress(png[41:41 + length])
            self.assertEqual(raw[:5], bytes([0, 0, 0, 255, 255]))
            self.assertEqual(raw[9:14], bytes([0, 255, 0, 0, 255]))
            struct.pack_into('<II', hed, 8, 50, len(image()) + 1)
            (root / 'tcf.hed').write_bytes(hed)
            with self.assertRaises(ValueError):
                extract(root)
        self.assertEqual(tga_length(image(), 0), len(image()))
        for data in (image()[:-1], image()[:17], image()[:-1] + b'\x02'):
            with self.assertRaises(ValueError):
                tga_length(data, 0)

    def test_shared_images_are_not_invented_character_ids(self):
        self.assertEqual(face_identity('o', 21), (200001, 'other'))
        self.assertEqual(face_identity('o', 41), (None, 'other'))

    def test_manual_holders_merge_posts_not_names_across_factions(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'manual.csv'
            path.write_text('faction,page,post,initial_holder,department,unit\n'
                            'empire,60,P,Test元帥,D,U\nempire,60,Q,Test元帥,D,U\n'
                            'alliance,66,P,Test元帥,D,U\n', encoding='utf-8')
            rows = manual_roster(path)['characters']
            self.assertEqual(len(rows), 2)
            self.assertEqual(len(rows[0]['posts']), 2)
            self.assertEqual(rows[0]['name'], 'Test')
            self.assertTrue(all(v is None for v in rows[0]['abilities'].values()))

    def test_null_zero_provenance_duplicates_and_wrong_faction(self):
        faces = [dict(face_id=1, faction='empire')]
        c = blank_character('one', 'empire', 'Test')
        doc = dict(schema=1, characters=[c])
        validate(doc, faces)
        c['abilities']['attack'] = 0
        with self.assertRaises(ValueError):
            validate(doc, faces)
        c['abilities_evidence'] = dict(status='guess', source='Local policy')
        validate(doc, faces)
        for invalid in (-1, 65536, True, 1.5, '80'):
            bad = copy.deepcopy(doc)
            bad['characters'][0]['abilities']['attack'] = invalid
            with self.assertRaises(ValueError):
                validate(bad, faces)
        c['face_id'] = 1
        c['face_evidence'] = dict(status='client', source='Observation')
        validate(doc, faces)
        duplicate = copy.deepcopy(c)
        duplicate['key'] = 'two'
        with self.assertRaises(ValueError):
            validate(dict(schema=1, characters=[c, duplicate]), faces)
        c['faction'] = 'alliance'
        with self.assertRaises(ValueError):
            validate(doc, faces)


if __name__ == '__main__':
    unittest.main()
