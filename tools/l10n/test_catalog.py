"""번역 경계·원본 불변·손실 없는 문자열 검사. 작성: 최병호."""
import copy
import json
from pathlib import Path
import struct
import tempfile
import unittest

import catalog
import msgdat


def fixture(strings):
    return struct.pack('<4sIII4I', b'HFWR', 0, len(strings), 2, 0, len(strings), 0, 0) + \
        b''.join(s.encode('cp932') + b'\0' for s in strings)


class CatalogueTest(unittest.TestCase):
    def test_lossless_private_glyphs(self):
        # Private transport is confined to resources. All modern Hangul must
        # either round-trip or fail capacity checks; never silently become '?'.
        text = '한글 양준장 日本 ABC 123 · ‘함명’ %3d $xname$'
        glyphs, encode = catalog.glyph_encoding([text])
        encoded = encode(text)
        decoded = encoded.decode('cp932')
        decoded = ''.join(glyphs[ord(c)-0xe000] if 0xe000 <= ord(c) < 0xe000+len(glyphs) else c for c in decoded)
        self.assertEqual(text, decoded)
        self.assertEqual(catalog.signature(text), catalog.signature(encoded.decode('cp932')))
        with self.assertRaisesRegex(ValueError, 'capacity'):
            catalog.glyph_encoding([''.join(chr(0xac00+i) for i in range(1881))])

    def test_full_label_coverage_only(self):
        translate = catalog.composer({'旗艦': '기함', '名': '명', '型': '형'})
        self.assertEqual('기함명', translate('旗艦名'))
        self.assertEqual('기함 %3d $xname$', translate('旗艦 %3d $xname$'))
        self.assertIsNone(translate('旗艦を選択してください'))
        self.assertEqual('ABC 123', translate('ABC 123'))

    def test_tokens_printf_and_invalid_text(self):
        catalog.validate('$xname$ は %3d', '$xname$ 수량 %3d')
        for text in ('$xname$ %s', '$other$ %3d', '$xname$ %d', '$xname$ %3d\0', '$xname$ 日本 %3d', ''):
            with self.assertRaises(ValueError):
                catalog.validate('$xname$ は %3d', text)
        with self.assertRaises(UnicodeEncodeError):
            catalog.validate('test', '한글😀')
        with self.assertRaises(ValueError):
            catalog.validate('$name$ typo$', '$name$ 교정')
        catalog.validate('$name$ typo$', '$name$ 교정',True)
        with self.assertRaises(ValueError):
            catalog.validate('$name$ typo$', '$other$ 교정',True)

    def test_hash_structure_and_original_unchanged(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp)/'test.dat'
            raw = fixture(['旗艦名', '未翻訳', '%3d'])
            source.write_bytes(raw)
            doc = dict(source_sha256=msgdat.sha(raw), groups={'0':['기함명',None,'%3d']})
            ref, rows, missing = catalog.collect(source, doc, {})
            self.assertEqual(raw, source.read_bytes())
            self.assertEqual([(0,0)], [(r['group'],r['index']) for r in rows])
            self.assertEqual([(0,1)], [(r['group'],r['index']) for r in missing])
            self.assertIn('koreanResources', catalog.header(rows))
            bad = copy.deepcopy(doc); bad['source_sha256'] = '0'*64
            with self.assertRaisesRegex(ValueError, 'hash mismatch'):
                catalog.collect(source, bad, {})
            bad = copy.deepcopy(doc); bad['groups']['0'].append('추가')
            with self.assertRaisesRegex(ValueError, 'length mismatch'):
                catalog.collect(source, bad, {})
            self.assertEqual(ref['indices'], msgdat.decode(raw)['indices'])

    def test_catalogue_files_are_valid(self):
        doc = catalog.read_json(catalog.ROOT/'l10n/constmsg.ko.json')
        self.assertEqual('최병호',doc['author'])
        self.assertEqual(64,len(doc['source_sha256']))
        self.assertGreater(len(catalog.terms(catalog.ROOT/'l10n/ui-terms.ko.tsv')),500)
        for values in doc['groups'].values():
            for value in values:
                if value is not None:
                    self.assertIsNone(catalog.JAPANESE.search(value))

    def test_message_tables_are_distinct(self):
        rows=[dict(table=1,group=0,index=0,source=b'one',text='명령'),
              dict(table=37,group=0,index=0,source=b'two',text='승진')]
        generated=catalog.header(rows)
        self.assertIn('4294967296ULL',generated)
        self.assertIn('158913789952ULL',generated)

    def test_actual_sources_placeholders_and_generated_header(self):
        source=Path('E:/logh7-original/extracted/install/ｱﾌﾟﾘｹｰｼｮﾝ実行可能ﾌｧｲﾙ/data/MsgDat/constmsg.dat')
        if not source.is_file():self.skipTest('read-only original assets are not present')
        before={path:msgdat.sha(path.read_bytes()) for path in source.parent.glob('*.dat')}
        ref,rows,missing,tables,coverage=catalog.project(source)
        self.assertEqual(3143,sum(not row.get('table') for row in rows))
        self.assertFalse([row for row in missing if row['file']=='constmsg.dat'])
        self.assertFalse(missing)
        self.assertEqual(1437,sum(0<row.get('table',0)<65532 for row in rows))
        self.assertEqual(141,sum(row.get('table')==65535 for row in rows))
        self.assertEqual(121,sum(row.get('table')==65532 for row in rows))
        self.assertEqual(124,sum(row.get('table')==65533 for row in rows))
        self.assertEqual(20,len(coverage))
        self.assertEqual(0,coverage['messages_com_0.dat']['untranslated'])
        self.assertTrue(all(not values['untranslated'] for name,values in coverage.items() if '_tac_' in name))
        glyphs,encode=catalog.glyph_encoding([row['text'] for row in rows])
        for row in rows:
            restored=''.join(glyphs[ord(c)-0xe000] if 0xe000<=ord(c)<0xe000+len(glyphs) else c
                             for c in encode(row['text']).decode('cp932'))
            self.assertEqual(row['text'],restored)
        self.assertEqual(catalog.header(rows,tables),(catalog.ROOT/'client-compat/korean_catalog_data.h').read_text('utf-8'))
        self.assertEqual(before,{path:msgdat.sha(path.read_bytes()) for path in before})


if __name__ == '__main__':
    unittest.main()
