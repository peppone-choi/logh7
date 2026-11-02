"""이미지 추가·변경을 오래된 육안 확인으로 숨기지 않는 검사. 작성: Peppone Choi."""
import unittest

import review_images


class ReviewTests(unittest.TestCase):
    def inventory(self,**changes):
        return dict(assets=[dict(asset='icon/x.tga',sha256='a'*64,display_validated=True,**changes)])

    def document(self):
        return dict(schema=1,assets=[dict(asset='icon/x.tga',sha256='a'*64,
            classification='no_japanese_visible',sheet='icon-00.png')])

    def test_changed_or_new_asset_requires_a_new_review(self):
        for asset,digest in [('icon/x.tga','b'*64),('icon/new.tga','a'*64)]:
            inventory=dict(assets=[dict(asset=asset,sha256=digest)])
            result=review_images.review(inventory,self.document())
            self.assertEqual('pending',result['assets'][0]['text_review'])
            self.assertFalse(result['inventory_matches_review'])

    def test_visual_review_never_claims_client_validation(self):
        result=review_images.review(self.inventory(),self.document())
        self.assertTrue(result['inventory_matches_review'])
        self.assertEqual('no_japanese_visible',result['assets'][0]['text_review'])
        self.assertFalse(result['assets'][0]['display_validated'])
        self.assertFalse(result['display_validated'])

    def test_missing_payload_and_removed_assets_are_reported(self):
        document=self.document();document['assets'][0]['classification']='translation_candidate'
        result=review_images.review(self.inventory(),document)
        self.assertEqual(['icon/x.tga'],result['missing_replacements'])
        self.assertFalse(result['translation_candidates_packaged'])
        self.assertEqual(['icon/x.tga'],review_images.review(dict(assets=[]),document)['removed_assets'])

    def test_ambiguous_or_invalid_reviews_are_rejected(self):
        document=self.document();document['assets']*=2
        with self.assertRaisesRegex(ValueError,'duplicate'):review_images.review(self.inventory(),document)
        document=self.document();document['assets'][0]['classification']='done'
        with self.assertRaisesRegex(ValueError,'classification'):review_images.review(self.inventory(),document)
        document=self.document();document['assets'][0]['sha256']='invalid'
        with self.assertRaisesRegex(ValueError,'hash'):review_images.review(self.inventory(),document)


if __name__=='__main__':unittest.main()
