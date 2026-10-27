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
    def test_expanded_candidates_preserve_manual_placement_and_unknown_values(self):
        from npc_catalogue import expanded_roster
        root = Path(__file__).resolve().parents[2]
        rows = expanded_roster(root / 'docs/manual/data/initial-card-holders.csv')['characters']
        self.assertEqual(len(rows), 450)
        self.assertEqual(sum(bool(c['manual_holder']) for c in rows), 70)
        self.assertEqual(len({c['name'] for c in rows}), len(rows))
        self.assertTrue(any(c['name'] == '양 웬리' for c in rows))
        mer = next(c for c in rows if c['name'] == '빌리바르트 요아힘 폰 메르카츠')
        self.assertEqual(mer['faction'], 'empire')
        self.assertTrue(mer['posts'])
        assigned = [c for c in rows if c['face_id'] is not None]
        self.assertEqual(len(assigned), 19)
        self.assertEqual(len({c['face_id'] for c in assigned}), 19)
        self.assertTrue(all(c['face_evidence']['status'] == 'guess' and
                            c['face_comparison']['edition_caption'] == 'OVA' for c in assigned))
        self.assertEqual(next(c for c in rows if c['name'] == '양 웬리')['face_id'], 100074)
        self.assertEqual(next(c for c in rows if c['name'] == '라인하르트 폰 로엔그람')['face_id'], 112)
        self.assertTrue(all(all(v is None for v in c['abilities'].values()) for c in rows))
        self.assertEqual(sum(c['face_id'] is None for c in rows), 431)
        validate(dict(schema=1, characters=rows), [dict(face_id=c['face_id'], faction=c['faction']) for c in assigned])

    def test_separate_names_preserve_cultural_order_compound_given_name_and_wire_limit(self):
        from npc_catalogue import expanded_roster
        root = Path(__file__).resolve().parents[2]
        rows = expanded_roster(root / 'docs/manual/data/initial-card-holders.csv')['characters']
        reviewed = [c for c in rows if c['name_parts']['status'] == 'reviewed']
        self.assertEqual(len(reviewed), 47)
        by_name = {c['name']: c['name_parts'] for c in rows}
        self.assertEqual((by_name['양 웬리']['surname'], by_name['양 웬리']['given_name'], by_name['양 웬리']['order']), ('양', '웬리', 'family_first'))
        self.assertEqual(by_name['지크프리트 키르히아이스']['given_name'], '지크프리트')
        self.assertEqual(by_name['라인하르트 폰 로엔그람']['surname'], '로엔그람')
        self.assertEqual(by_name['라인하르트 폰 로엔그람']['prefix'], '폰')
        self.assertEqual(by_name['라인하르트 폰 로엔그람']['aliases'], ['라인하르트 폰 뮈젤', '라인하르트 폰 뮤젤'])
        self.assertEqual(sum(bool(c['name_parts']['prefix']) for c in reviewed), 14)
        self.assertEqual(by_name['빌리바르트 요아힘 폰 메르카츠']['given_name'], '빌리바르트 요아힘')
        self.assertIsNone(by_name['무라이']['given_name'])
        for c in reviewed:
            p = c['name_parts']
            family = [p['prefix'], p['surname']]
            parts = family + [p['given_name']] if p['order'] == 'family_first' else [p['given_name']] + family
            self.assertEqual(' '.join(filter(None, parts)), c['name'])
        c = blank_character('wire', 'empire', 'Display name remains independent')
        c['name_parts']['surname'] = 'A' * 12
        validate(dict(schema=1, characters=[c]), [])
        for invalid in ('A' * 13, '\x00', '\U0001f600', '   ', ''):
            c['name_parts']['surname'] = invalid
            with self.assertRaises(ValueError):
                validate(dict(schema=1, characters=[c]), [])

    def test_legacy_von_prefix_migration_preserves_other_data_and_explicit_prefix(self):
        from npc_catalogue import migrate_name_parts
        c = blank_character('legacy', 'empire', '라인하르트 폰 로엔그람')
        c['name_parts'] = dict(surname='폰 로엔그람', given_name='라인하르트', order='given_first', status='reviewed', source='검토', note='직접 남긴 메모')
        c['abilities']['attack'] = 0
        c['abilities_evidence'] = dict(status='guess', source='검사 수치')
        original = dict(schema=1, characters=[c])
        migrated = migrate_name_parts(original)
        parts = migrated['characters'][0]['name_parts']
        self.assertEqual((parts['surname'], parts['prefix'], parts['note']), ('로엔그람', '폰', '직접 남긴 메모'))
        self.assertEqual(c['name_parts']['surname'], '폰 로엔그람')
        self.assertNotIn('prefix', c['name_parts'])
        self.assertEqual(migrated['characters'][0]['name'], c['name'])
        self.assertEqual(migrated['characters'][0]['abilities']['attack'], 0)
        validate(migrated, [])
        self.assertEqual(migrate_name_parts(migrated), migrated)
        parts['aliases'] = ['라인하르트 폰 뮈젤', '라인하르트 폰 뮤젤']
        validate(migrated, [])
        self.assertEqual(migrate_name_parts(migrated)['characters'][0]['name_parts']['aliases'], parts['aliases'])
        for invalid in ('not a list', ['\x00'], ['A' * 201], ['\U0001f600'], [' '], ['A'] * 21):
            bad = copy.deepcopy(migrated)
            bad['characters'][0]['name_parts']['aliases'] = invalid
            with self.assertRaises(ValueError):
                validate(bad, [])
        parts['surname'] = '폰 로엔그람'
        with self.assertRaises(ValueError):
            validate(migrated, [])

    def test_ova_comparison_cannot_survive_a_different_face_or_edition(self):
        from npc_catalogue import expanded_roster
        root = Path(__file__).resolve().parents[2]
        c = next(c for c in expanded_roster(root / 'docs/manual/data/initial-card-holders.csv')['characters'] if c.get('face_comparison'))
        faces = [dict(face_id=c['face_id'], faction=c['faction'])]
        for field, value in [('face_id', 999), ('edition', 'DNT'), ('edition_caption', 'DNT'), ('image_url', 'javascript:alert(1)')]:
            bad = copy.deepcopy(c)
            bad['face_comparison'][field] = value
            with self.assertRaises(ValueError):
                validate(dict(schema=1, characters=[bad]), faces)

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
