"""검수 TGA/PNG의 백업·실행 사본 적용·복구. 작성자: 최병호."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import struct
import tempfile
import zlib


WORK = Path('E:/logh7/work')
ORIGINAL = Path('E:/logh7-original')
FORMAT = 'logh7-image-patch-v1'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def writable(path):
    path = Path(path).resolve()
    if not path.is_relative_to(WORK.resolve()) or path.is_relative_to(ORIGINAL.resolve()):
        raise ValueError('writes are restricted to E:/logh7/work')
    return path


def asset_path(root, relative):
    """Windows에서도 탈출·ADS·장치명·대소문자 충돌을 허용하지 않는다."""
    if not isinstance(relative, str) or '\\' in relative:
        raise ValueError('asset must use relative forward slashes')
    parts = relative.split('/')
    devices = {'CON', 'PRN', 'AUX', 'NUL'} | {
        f'{name}{i}' for name in ('COM', 'LPT') for i in range(1, 10)}
    if (any(not p or p in ('.', '..') or p.endswith((' ', '.')) or
            any(ord(c) < 32 or c in '<>:"|?*' for c in p) or
            p.split('.')[0].upper() in devices for p in parts) or
            PurePosixPath(relative).suffix.lower() not in ('.tga', '.png')):
        raise ValueError('invalid relative image asset path')
    root = Path(root).resolve()
    path = root.joinpath(*parts).resolve()
    if not path.is_relative_to(root):
        raise ValueError('asset escapes root')
    return path


def tga_info(data):
    """CD 조사 범위의 비압축 팔레트/true-color만 취급한다. 재인코딩하지 않는다."""
    if len(data) < 18:
        raise ValueError('truncated TGA header')
    ident, cmap, kind, first, colors, bits, x, y, width, height, depth, desc = \
        struct.unpack_from('<BBBHHBHHHHBB', data)
    if not width or not height or desc & 0xc0:
        raise ValueError('unsupported TGA dimensions or interleave')
    if kind == 1:
        if cmap != 1 or depth != 8 or bits not in (24, 32) or not 1 <= colors <= 256 or first + colors > 256:
            raise ValueError('unsupported indexed TGA')
    elif kind == 2:
        if cmap or first or colors or bits or depth not in (24, 32):
            raise ValueError('unsupported true-color TGA')
    else:
        raise ValueError('only uncompressed indexed/true-color TGA is supported')
    start = 18 + ident + colors * (bits // 8)
    end = start + width * height * (depth // 8)
    if end > len(data):
        raise ValueError('truncated TGA image')
    # Only an empty v2 footer is accepted: offsets to extension/developer data
    # would require additional parsing before claiming format preservation.
    tail = data[end:]
    if tail and tail != b'\0' * 8 + b'TRUEVISION-XFILE.\0':
        raise ValueError('unsupported TGA extension/trailing data')
    pixels = data[start:end]
    if kind == 1:
        if min(pixels) < first or max(pixels) >= first + colors:
            raise ValueError('TGA pixel outside palette')
        palette = data[18 + ident:start]
        alpha = ([palette[(index - first) * 4 + 3] for index in pixels]
                 if bits == 32 else [255] * (width * height))
    else:
        alpha = pixels[3::4] if depth == 32 else [255] * (width * height)
    return dict(width=width, height=height, image_type=kind, pixel_depth=depth,
                palette_first=first, palette_size=colors, palette_depth=bits,
                x_origin=x, y_origin=y, descriptor=desc, id_hex=data[18:18 + ident].hex(),
                footer=bool(tail), alpha_min=min(alpha), alpha_max=max(alpha),
                transparent_pixels=alpha.count(0), opaque_pixels=alpha.count(255))


PNG_SIGNATURE = b'\x89PNG\r\n\x1a\n'


def png_chunks(data):
    """프로젝트의 RGBA PNG만 검증한다. 알 수 없는 중요 chunk/애니메이션은 거부."""
    if not data.startswith(PNG_SIGNATURE):
        raise ValueError('invalid PNG signature')
    chunks = []
    position = 8
    while position < len(data):
        if len(data)-position < 12:
            raise ValueError('truncated PNG chunk')
        length, kind = struct.unpack_from('>I4s', data, position)
        end = position+12+length
        if end > len(data):
            raise ValueError('truncated PNG content')
        content = data[position+8:end-4]
        if zlib.crc32(kind+content) & 0xffffffff != struct.unpack_from('>I', data, end-4)[0]:
            raise ValueError('PNG checksum mismatch')
        if kind not in (b'IHDR', b'IDAT', b'IEND') and (not kind[0] & 32 or kind in (b'acTL', b'fcTL', b'fdAT')):
            raise ValueError('unsupported PNG chunk')
        chunks.append((kind, content))
        position = end
    kinds = [kind for kind, _ in chunks]
    if (not kinds or kinds[0] != b'IHDR' or kinds[-1] != b'IEND' or
            kinds.count(b'IHDR') != 1 or kinds.count(b'IEND') != 1 or b'IDAT' not in kinds or
            chunks[-1][1]):
        raise ValueError('invalid PNG chunk order')
    first, last = kinds.index(b'IDAT'), len(kinds)-1-kinds[::-1].index(b'IDAT')
    if any(kind != b'IDAT' for kind in kinds[first:last+1]):
        raise ValueError('nonconsecutive PNG image chunks')
    return chunks


def png_pixels(data):
    chunks = png_chunks(data)
    if len(chunks[0][1]) != 13:
        raise ValueError('invalid PNG header')
    width, height, bits, color, compression, filtering, interlace = struct.unpack('>IIBBBBB', chunks[0][1])
    if (not width or not height or width*height > 16_777_216 or
            (bits, color, compression, filtering, interlace) != (8, 6, 0, 0, 0)):
        raise ValueError('only noninterlaced 8-bit RGBA PNG is supported')
    stride = width*4
    size = (stride+1)*height
    decoder = zlib.decompressobj()
    scanlines = decoder.decompress(b''.join(value for kind, value in chunks if kind == b'IDAT'), size+1)
    if len(scanlines) != size or not decoder.eof or decoder.unused_data or decoder.unconsumed_tail:
        raise ValueError('invalid PNG pixel stream')
    pixels = bytearray(stride*height)
    for y in range(height):
        start = y*(stride+1)
        mode = scanlines[start]
        if mode > 4:
            raise ValueError('invalid PNG filter')
        for x in range(stride):
            left = pixels[y*stride+x-4] if x >= 4 else 0
            above = pixels[(y-1)*stride+x] if y else 0
            corner = pixels[(y-1)*stride+x-4] if x >= 4 and y else 0
            if mode == 4:
                estimate = left+above-corner
                distances = [abs(estimate-v) for v in (left, above, corner)]
                prediction = (left, above, corner)[distances.index(min(distances))]
            else:
                prediction = (0, left, above, (left+above)//2)[mode]
            pixels[y*stride+x] = (scanlines[start+x+1]+prediction) & 255
    return width, height, bytes(pixels), chunks


def png_info(data):
    width, height, pixels, chunks = png_pixels(data)
    alpha = pixels[3::4]
    return dict(width=width, height=height, format='PNG', pixel_depth=32,
                # Preserve original color profile, density and any ancillary data.
                ancillary=[[kind.decode('ascii'), value.hex()] for kind, value in chunks if kind != b'IDAT'],
                alpha_min=min(alpha), alpha_max=max(alpha),
                transparent_pixels=alpha.count(0), opaque_pixels=alpha.count(255))


def image_info(data):
    return png_info(data) if data.startswith(PNG_SIGNATURE) else tga_info(data)


def compatible(original, replacement, asset=None):
    if asset is not None and any(data.startswith(PNG_SIGNATURE) != asset.lower().endswith('.png')
                                 for data in (original, replacement)):
        raise ValueError('image encoding does not match asset extension')
    left, right = image_info(original), image_info(replacement)
    # Alpha content may change when glyphs change; retain its storage format and
    # report counts for visual review instead of silently dropping transparency.
    keys = [key for key in left if not key.startswith('alpha_') and
            key not in ('transparent_pixels', 'opaque_pixels')]
    if any(left[key] != right[key] for key in keys):
        raise ValueError('image canvas, origin, encoding, palette, descriptor, ID, footer or PNG metadata changed')
    return left, right


def read_json(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def entries(rows):
    if not isinstance(rows, list) or not rows:
        raise ValueError('expected nonempty asset list')
    seen = set()
    for row in rows:
        relative = row['asset']
        asset_path(WORK, relative)
        if relative.casefold() in seen:
            raise ValueError('duplicate asset path')
        seen.add(relative.casefold())
        for key in ('original_sha256', 'replacement_sha256'):
            digest = row[key]
            if not isinstance(digest, str) or len(digest) != 64 or any(c not in '0123456789abcdef' for c in digest):
                raise ValueError('expected lowercase SHA256')
    return rows


def prepare(source_root, replacement_root, selection, output):
    """모든 파일을 먼저 대조한 뒤 새로운 패키지에 원바이트를 복사한다."""
    output = writable(output)
    if output.exists():
        raise FileExistsError(output)
    source_root, replacement_root = Path(source_root).resolve(), Path(replacement_root).resolve()
    if output.is_relative_to(source_root) or output.is_relative_to(replacement_root):
        raise ValueError('package must be separate from input roots')
    loaded, report = [], []
    for row in entries(read_json(selection)):
        source = asset_path(source_root, row['asset'])
        replacement = asset_path(replacement_root, row['asset'])
        original, translated = source.read_bytes(), replacement.read_bytes()
        if sha(original) != row['original_sha256'] or sha(translated) != row['replacement_sha256']:
            raise ValueError(f'input hash mismatch: {row["asset"]}')
        before, after = compatible(original, translated, row['asset'])
        loaded.append((row['asset'], original, translated))
        report.append(dict(asset=row['asset'], source=str(source), replacement_source=str(replacement),
                           original_sha256=sha(original), replacement_sha256=sha(translated),
                           original=before, replacement=after))
    output.mkdir(parents=True)
    for relative, original, translated in loaded:
        for folder, data in (('backup', original), ('payload', translated)):
            target = writable(asset_path(output / folder, relative))
            target.parent.mkdir(parents=True, exist_ok=True)
            with target.open('xb') as stream:
                stream.write(data)
    manifest = dict(format=FORMAT, author='최병호', display_validated=False, assets=report)
    with (output / 'manifest.json').open('x', encoding='utf-8', newline='\n') as stream:
        json.dump(manifest, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
    verify(output)
    return manifest


def verify(package):
    """원본 설치 경로 없이도 백업과 payload를 독립적으로 재검사한다."""
    package = Path(package).resolve()
    manifest = read_json(package / 'manifest.json')
    if manifest.get('format') != FORMAT:
        raise ValueError('unknown image package format')
    loaded = []
    for row in entries(manifest['assets']):
        original = asset_path(package / 'backup', row['asset']).read_bytes()
        translated = asset_path(package / 'payload', row['asset']).read_bytes()
        if sha(original) != row['original_sha256'] or sha(translated) != row['replacement_sha256']:
            raise ValueError(f'package hash mismatch: {row["asset"]}')
        before, after = compatible(original, translated, row['asset'])
        if before != row['original'] or after != row['replacement']:
            raise ValueError(f'package metadata mismatch: {row["asset"]}')
        loaded.append((row, original, translated))
    return loaded


def atomic_write(target, data):
    target = writable(target)
    # Use a sibling temporary file so replacement is on the same filesystem.
    fd, name = tempfile.mkstemp(prefix='.l10n-', suffix='.tmp', dir=target.parent)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(name, target)
    finally:
        if Path(name).exists():
            Path(name).unlink()


def install(package, target_root, restore=False):
    """중단 뒤 혼합 상태도 재실행할 수 있으며 제3의 변경은 덮어쓰지 않는다."""
    package = Path(package).resolve()
    target_root = writable(target_root)
    if package.is_relative_to(target_root) or target_root.is_relative_to(package):
        raise ValueError('target and package must be separate')
    pending = []
    for row, original, translated in verify(package):
        target = writable(asset_path(target_root, row['asset']))
        if target in (Path(row['source']).resolve(), Path(row['replacement_source']).resolve()):
            raise ValueError('cannot modify source or replacement input image')
        current = target.read_bytes()  # Never create a missing game asset.
        if sha(current) not in (row['original_sha256'], row['replacement_sha256']):
            raise ValueError(f'target has unrelated changes: {row["asset"]}')
        desired = original if restore else translated
        if current != desired:
            pending.append((target, current, desired))
    changed = []
    try:
        for target, current, desired in pending:
            # Detect edits made after preflight (the client must be stopped).
            if target.read_bytes() != current:
                raise ValueError(f'target changed during apply: {target}')
            atomic_write(target, desired)
            changed.append((target, current, desired))
            if target.read_bytes() != desired:
                raise OSError(f'write verification failed: {target}')
    except Exception:
        for target, previous, desired in reversed(changed):
            if target.read_bytes() == desired:
                atomic_write(target, previous)
        raise
    return dict(action='restore' if restore else 'apply', changed=len(changed),
                target=str(target_root), display_validated=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    inspect = commands.add_parser('inspect')
    inspect.add_argument('image', type=Path)
    build = commands.add_parser('prepare')
    for name in ('source_root', 'replacement_root', 'selection', 'output'):
        build.add_argument(name, type=Path)
    check = commands.add_parser('verify')
    check.add_argument('package', type=Path)
    for name in ('apply', 'restore'):
        command = commands.add_parser(name)
        command.add_argument('package', type=Path)
        command.add_argument('target_root', type=Path)
    args = parser.parse_args()
    if args.command == 'inspect':
        data = args.image.read_bytes()
        result = dict(sha256=sha(data), **image_info(data))
    elif args.command == 'prepare':
        result = prepare(args.source_root, args.replacement_root, args.selection, args.output)
    elif args.command == 'verify':
        result = dict(verified_assets=len(verify(args.package)), display_validated=False)
    else:
        result = install(args.package, args.target_root, args.command == 'restore')
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
