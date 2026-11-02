"""CD 얼굴 묶음의 읽기 전용 TGA/인덱스 검사. 작성: Peppone Choi.

관찰한 CD 형식만 허용한다. 픽셀/패키지 재작성이나 게임 실행은 하지 않는다.
"""
import struct

import image_patch

HEADER_BYTES = 50
MAGIC = bytes.fromhex('ba da ca be')
# tcf.hed에서 실제 TGA 경계와 전수 대조한 고정 슬롯 구간.
INDEX_BLOCKS = (('oem.tcf', 200, 1), ('oam.tcf', 150, 1), ('o.tcf', 100, 1),
                ('gem.tcf', 100, 3), ('gef.tcf', 50, 3),
                ('gam.tcf', 100, 3), ('gaf.tcf', 50, 3))


def frames(data):
    if not HEADER_BYTES <= len(data) <= 64*1024*1024:
        raise ValueError('TCF size outside inspected limits')
    if data[:4] != MAGIC or data[40:HEADER_BYTES] != bytes(10):
        raise ValueError('unknown TCF header')
    result = []
    position = HEADER_BYTES
    while position < len(data):
        if len(result) >= 4096 or position+18 > len(data):
            raise ValueError('TCF truncated TGA header or excess frame count')
        identifier, mapped, kind, first, colors, palette, x, y, width, height, depth, descriptor = \
            struct.unpack_from('<BBBHHBHHHHBB', data, position)
        if (identifier or mapped != 1 or kind != 1 or first or
                not 1 <= colors <= 256 or palette != 32 or
                x or y or not 62 <= width <= 64 or height != 80 or depth != 8 or descriptor):
            raise ValueError('TCF TGA differs from inspected CD portrait format')
        length = 18+colors*4+width*height
        if position+length > len(data):
            raise ValueError('TCF truncated TGA payload')
        raw = data[position:position+length]
        info = image_patch.tga_info(raw)
        result.append(dict(offset=position, bytes=length, sha256=image_patch.sha(raw),
                           format=info))
        position += length
    if not result:
        raise ValueError('TCF has no portrait frames')
    return result


def verify_index(data, containers):
    required = sum(slots*variants*8 for _, slots, variants in INDEX_BLOCKS)
    if len(data) != required+40 or data[required:] != bytes(40):
        raise ValueError('unknown TCF index size or trailer')
    position = 0
    result = []
    for name, slots, variants in INDEX_BLOCKS:
        known = {(row['offset'], row['bytes']) for row in frames(containers[name])}
        seen = set()
        for slot in range(slots):
            offsets = struct.unpack_from('<'+'I'*variants, data, position)
            lengths = struct.unpack_from('<'+'I'*variants, data, position+variants*4)
            position += variants*8
            for variant, pair in enumerate(zip(offsets, lengths)):
                if pair == (0, 0):
                    continue
                if pair not in known or pair in seen:
                    raise ValueError('TCF index overlaps or does not name a whole TGA frame')
                seen.add(pair)
                result.append(dict(container=name, slot=slot, variant=variant,
                                   offset=pair[0], bytes=pair[1]))
        if seen != known:
            raise ValueError('TCF index omits a decoded frame')
    return result
