"""Reference extraction guards; no network access. Author: 최병호."""
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch
from npc_wiki import Images, MinorImages, collect, image_type, safe_url


class WikiTests(unittest.TestCase):
    def test_minor_entry_images_are_not_shared_across_unrelated_people(self):
        parser = MinorImages()
        parser.feed('''<li>First<br/><img alt="file:first.png" data-src="//i.namu.wiki/i/first.webp"></li>
          <li>Second: No image.</li>
          <li>Third, Fourth<br/><img alt="file:group.png" data-src="//i.namu.wiki/i/group.webp"></li>''')
        self.assertEqual(set(parser.people), {'First', 'Third', 'Fourth'})
        self.assertEqual(parser.people['First'][0]['image_url'], 'https://i.namu.wiki/i/first.webp')
        self.assertEqual(parser.people['Third'], parser.people['Fourth'])

    def test_image_candidates_skip_icons_signatures_and_noscript_duplicates(self):
        parser = Images()
        parser.feed('''<img alt="파일:flag.jpg" width="20" data-original="//file.namu.moe/file/flag">
          <img alt="파일:yang_old.png" width="65%" data-original="//file.namu.moe/file/face">
          <img alt="파일:yang_old.png" width="65%" src="//file.namu.moe/file/face">
          <noscript><img alt="파일:yang_old.png" src="//file.namu.moe/file/face"></noscript>
          <img alt="파일:yang_sign.png" src="//file.namu.moe/file/sign">
          <img alt="파일:bad.png" src="https://unrelated.test/face">
          <img alt="파일:icon.svg" src="//file.namu.moe/file/icon">''')
        self.assertEqual(len(parser.items), 1)
        self.assertEqual(parser.items[0]['image_url'], 'https://file.namu.moe/file/face')
        with self.assertRaises(ValueError):
            safe_url('http://namu.moe/w/Test')
        with self.assertRaises(ValueError):
            image_type(b'<html>blocked</html>')

    def test_download_failure_and_cached_success_are_distinct(self):
        entry = dict(page='https://namu.wiki/w/Test', mirror='https://namu.moe/w/Test')
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'pages').mkdir()
            (root / 'images').mkdir()
            with patch('npc_wiki.read_url', side_effect=OSError('offline')):
                self.assertEqual(collect(entry, root, 3)['status'], 'unavailable')
            page = b'<img alt="file:face.png" src="//file.namu.moe/file/face">'
            with patch('npc_wiki.read_url', side_effect=[page, b'\x89PNG\r\n\x1a\n']) as read:
                result = collect(entry, root, 3)
                self.assertEqual(result['status'], 'cached')
                self.assertEqual(len(result['images']), 1)
                self.assertEqual(read.call_count, 2)
            with patch('npc_wiki.read_url', side_effect=AssertionError('must use cache')):
                self.assertEqual(collect(entry, root, 3)['status'], 'cached')


if __name__ == '__main__':
    unittest.main()
