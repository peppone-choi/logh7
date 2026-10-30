"""TGA 원바이트·원점·팔레트·편집 범위 보존. 작성: 최병호."""
import struct
import unittest

import render_buttons


class ButtonEncodingTest(unittest.TestCase):
    def test_duplicate_palette_indices_keep_original_bytes(self):
        header=struct.pack('<BBBHHBHHHHBB',0,1,1,0,2,32,0,0,2,1,8,0x20)
        color=bytes([10,20,30,255]);data=header+color*2+bytes([1,0])
        _,image,_=render_buttons.unpack(data)
        self.assertEqual(data,render_buttons.repack(data,image,(0,0,2,1)))

    def test_unedited_bytes_and_origins_are_lossless(self):
        for descriptor in (0,0x10,0x20,0x30):
            for kind,depth,palette_bits in ((1,8,24),(1,8,32),(2,24,0),(2,32,0)):
                alpha_bits=8 if depth==32 else 0
                header=struct.pack('<BBBHHBHHHHBB',0,int(kind==1),kind,0,2 if kind==1 else 0,
                                   palette_bits,7,8,4,3,depth,descriptor|alpha_bits)
                if kind==1:
                    palette=(b'\x10\x20\x30'+(b'\x00' if palette_bits==32 else b'')+
                             b'\x90\xa0\xb0'+(b'\xff' if palette_bits==32 else b''))
                    data=header+palette+bytes([0,1]*6)
                else:
                    data=header+(bytes([12,34,56,200])[:depth//8])*12
                info,image,pos=render_buttons.unpack(data)
                self.assertEqual(data,render_buttons.repack(data,image,(0,0,0,0)))
                image.putpixel((1,1),(176,160,144,255))
                output=render_buttons.repack(data,image,(1,1,2,2))
                self.assertEqual(data[:pos],output[:pos])
                _,changed,_=render_buttons.unpack(output)
                _,old,_=render_buttons.unpack(data)
                for y in range(3):
                    for x in range(4):
                        if (x,y)!=(1,1):
                            self.assertEqual(old.getpixel((x,y)),changed.getpixel((x,y)))

    def test_change_outside_declared_glyph_area_is_rejected(self):
        header=struct.pack('<BBBHHBHHHHBB',0,0,2,0,0,0,0,0,4,3,32,0x28)
        data=header+bytes([10,20,30,255])*12
        _,image,_=render_buttons.unpack(data)
        image.putpixel((0,0),(255,255,255,255))
        with self.assertRaisesRegex(ValueError,'outside edit box'):
            render_buttons.repack(data,image,(1,1,2,2))


if __name__=='__main__':
    unittest.main()
