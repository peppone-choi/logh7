"""PNG 형식·백업 복구와 전략/HUD 편집 범위 검사. 작성: Peppone Choi."""
import json
from pathlib import Path
import struct
import tempfile
import unittest
import zlib

from PIL import Image

import catalog
import image_patch
import render_mockups
from render_buttons import unpack


def chunk(kind, content):
    return struct.pack('>I', len(content))+kind+content+struct.pack('>I', zlib.crc32(kind+content) & 0xffffffff)


def png(pixels, width=2, height=2, mode=0):
    raw=bytearray()
    for y in range(height):
        raw.append(mode)
        for x in range(width*4):
            i=y*width*4+x
            a=pixels[i-4] if x>=4 else 0
            b=pixels[i-width*4] if y else 0
            c=pixels[i-width*4-4] if y and x>=4 else 0
            p=a+b-c
            distance=[abs(p-v) for v in (a,b,c)]
            prediction=(0,a,b,(a+b)//2,(a,b,c)[distance.index(min(distance))])[mode]
            raw.append((pixels[i]-prediction)&255)
    return (image_patch.PNG_SIGNATURE+chunk(b'IHDR',struct.pack('>IIBBBBB',width,height,8,6,0,0,0))+
            chunk(b'gAMA',struct.pack('>I',45454))+chunk(b'IDAT',zlib.compress(raw))+chunk(b'IEND',b''))


class MockupTests(unittest.TestCase):
    def test_every_png_filter_and_repack_preserve_pixels_alpha_and_ancillary_data(self):
        pixels=bytes((1,20,50,0,200,100,20,128,20,30,50,255,220,250,90,255))
        for mode in range(5):
            original=png(pixels,mode=mode)
            self.assertEqual(pixels,image_patch.png_pixels(original)[2])
            image=Image.frombytes('RGBA',(2,2),pixels)
            replacement=render_mockups.png_repack(original,image)
            self.assertEqual(pixels,image_patch.png_pixels(replacement)[2])
            self.assertEqual(image_patch.png_info(original),image_patch.png_info(replacement))
            self.assertEqual(0,image_patch.png_info(original)['alpha_min'])
            self.assertEqual(2,image_patch.png_info(original)['opaque_pixels'])

    def test_corrupt_truncated_and_changed_png_formats_are_rejected(self):
        original=png(bytes([255]*16))
        changed=bytearray(original);changed[-2]^=1
        for invalid in (bytes(changed),original[:-1],original+b'junk'):
            with self.assertRaises(ValueError):image_patch.png_info(invalid)
        with self.assertRaisesRegex(ValueError,'canvas'):
            image_patch.compatible(original,png(bytes([255]*16),width=1,height=4))
        with self.assertRaisesRegex(ValueError,'extension'):
            image_patch.compatible(original,original,'wrong.tga')
        changed=original.replace(chunk(b'gAMA',struct.pack('>I',45454)),chunk(b'gAMA',struct.pack('>I',10000)))
        with self.assertRaisesRegex(ValueError,'metadata'):
            image_patch.compatible(original,changed)

    def test_png_package_apply_restore_preserves_source_bytes_and_metadata(self):
        work=catalog.ROOT/'work';work.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=work) as temp:
            root=Path(temp)
            original=png(bytes((10,20,30,255))*4)
            replacement=png(bytes((40,50,60,255))*4,mode=4)
            for folder,content in (('source',original),('replacement',replacement),('target',original)):
                path=root/folder/'strategy/underpanel.png';path.parent.mkdir(parents=True);path.write_bytes(content)
            selected=[dict(asset='strategy/underpanel.png',original_sha256=image_patch.sha(original),replacement_sha256=image_patch.sha(replacement))]
            (root/'selection.json').write_text(json.dumps(selected),encoding='utf-8')
            image_patch.prepare(root/'source',root/'replacement',root/'selection.json',root/'package')
            self.assertEqual(1,len(image_patch.verify(root/'package')))
            self.assertEqual(1,image_patch.install(root/'package',root/'target')['changed'])
            self.assertEqual(replacement,(root/'target/strategy/underpanel.png').read_bytes())
            self.assertEqual(1,image_patch.install(root/'package',root/'target',restore=True)['changed'])
            self.assertEqual(original,(root/'target/strategy/underpanel.png').read_bytes())
            self.assertEqual(original,(root/'source/strategy/underpanel.png').read_bytes())

    def test_actual_mockup_translations_preserve_every_unedited_pixel_and_alpha(self):
        source=Path('E:/logh7-original/extracted/install/ｱﾌﾟﾘｹｰｼｮﾝ実行可能ﾌｧｲﾙ/data/image')
        font=Path('C:/Windows/Fonts/malgun.ttf')
        if not source.is_dir() or not font.is_file():self.skipTest('read-only original/font not present')
        for relative in render_mockups.SOURCES:
            data=(source/relative).read_bytes()
            replacement,image,report=render_mockups.translate(data,relative,font)
            before=unpack(data)[1] if relative.endswith('.tga') else Image.open(source/relative).convert('RGBA')
            image_patch.compatible(data,replacement,relative)
            self.assertEqual(before.getchannel('A').tobytes(),image.getchannel('A').tobytes())
            mask=Image.new('1',image.size)
            from PIL import ImageDraw,ImageChops
            draw=ImageDraw.Draw(mask)
            for spec in report:
                self.assertIsNone(catalog.JAPANESE.search(spec['text']))
                x0,y0,x1,y1=spec['box'];draw.rectangle((x0,y0,x1-1,y1-1),fill=1)
                b=spec['ink_box'];self.assertTrue(x0<=b[0]<b[2]<=x1 and y0<=b[1]<b[3]<=y1)
            difference=ImageChops.difference(before.convert('RGB'),image.convert('RGB'))
            difference.paste((0,0,0),(0,0,*image.size),mask)
            self.assertIsNone(difference.getbbox())
            self.assertNotEqual(data,replacement)
            self.assertEqual(data,(source/relative).read_bytes())
            with self.assertRaisesRegex(ValueError,'hash'):
                render_mockups.translate(data+b'changed',relative,font)


if __name__=='__main__':unittest.main()
