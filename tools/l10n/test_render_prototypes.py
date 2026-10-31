"""실제 제목판의 원본·배경·저장 형식 보존. 작성: Peppone Choi."""
from pathlib import Path
import unittest

import render_prototypes
import image_patch
from render_buttons import unpack


class PrototypeTests(unittest.TestCase):
    def test_actual_header_candidates_keep_unedited_pixels_and_format(self):
        source=Path('E:/logh7-original/extracted/install/ｱﾌﾟﾘｹｰｼｮﾝ実行可能ﾌｧｲﾙ/data/image')
        font=Path('C:/Windows/Fonts/malgun.ttf')
        if not source.is_dir() or not font.is_file():self.skipTest('original assets/font not present')
        for relative,spec in render_prototypes.HEADERS.items():
            path=source/relative;data=path.read_bytes();info,before,pos=unpack(data)
            replacement,report=render_prototypes.replace_header(data,spec,font)
            changed,after,newpos=unpack(replacement)
            image_patch.compatible(data,replacement)
            self.assertEqual(pos,newpos);self.assertEqual(data[:pos],replacement[:pos])
            box=render_prototypes.EDIT_BOX;different=0
            for y in range(before.height):
                for x in range(before.width):
                    if box[0]<=x<box[2] and box[1]<=y<box[3]:different+=before.getpixel((x,y))!=after.getpixel((x,y))
                    else:self.assertEqual(before.getpixel((x,y)),after.getpixel((x,y)))
            self.assertGreater(different,0);self.assertFalse(report['actual_reference_confirmed'])
            self.assertEqual(data,path.read_bytes())


if __name__=='__main__':unittest.main()
