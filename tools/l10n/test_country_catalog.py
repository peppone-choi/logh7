"""국가 데이터와 생성 헤더의 일치·확장·오류 경계 검사. 작성: 최병호."""
from pathlib import Path
import tempfile
import unittest
import shutil

import country_catalog

class CountryCatalogTest(unittest.TestCase):
    def test_checked_in_header_matches_country_definitions(self):
        root=country_catalog.ROOT
        self.assertEqual(country_catalog.header(root/'config/countries'),
                         (root/'client-compat/country_catalog_data.h').read_text(encoding='utf-8'))

    def test_country_identity_is_separate_from_native_appearance(self):
        with tempfile.TemporaryDirectory() as directory:
            target=Path(directory)
            for source in (country_catalog.ROOT/'config/countries').glob('*.csv'):shutil.copy2(source,target/source.name)
            with (target/'countries.csv').open('a',encoding='utf-8') as output:
                output.write('100,test_new_empire,시험 신제국,new_empire,2,0,empire\n')
            header=country_catalog.header(target)
            self.assertIn('{100,2}',header)
            self.assertIn('L"new_empire",L"신제국력",-798',header)
            self.assertIn('L"empire",L"제국력",-309',header)

    def test_unknown_family_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            target=Path(directory)
            for source in (country_catalog.ROOT/'config/countries').glob('*.csv'):shutil.copy2(source,target/source.name)
            with (target/'countries.csv').open('a',encoding='utf-8') as output:
                output.write('100,unknown,시험 국가,missing,2,0,empire\n')
            with self.assertRaisesRegex(ValueError,'identity/family'):country_catalog.load(target)

if __name__=='__main__':unittest.main()
