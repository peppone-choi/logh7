"""Deterministic harmless x86 PE. Only Sleep and ExitProcess imports; no audio/UI.

No compiler/global install or external executable bytes. Generate in an exclusive
fixture directory. Image is fixed at 0x400000 (no relocations), Windows subsystem.
"""
import struct


def image(mode='success'):
    if mode not in ('success', 'failure', 'hang'):
        raise ValueError('Invalid native fixture mode')
    sleep = 60000 if mode == 'hang' else 350
    exit_code = 7 if mode == 'failure' else 0
    data = bytearray(1024)
    data[:2] = b'MZ'
    struct.pack_into('<I', data, 0x3c, 0x80)
    data[0x80:0x84] = b'PE\0\0'
    struct.pack_into('<HHIIIHH', data, 0x84, 0x14c, 1, 0, 0, 0, 224, 0x103)
    opt = 0x98
    struct.pack_into('<H', data, opt, 0x10b)
    for offset, value in {4: 512, 16: 0x1000, 20: 0x1000, 24: 0x1000,
                          28: 0x400000, 32: 4096, 36: 512, 56: 8192, 60: 512,
                          72: 0x100000, 76: 4096, 80: 0x100000, 84: 4096, 92: 16}.items():
        struct.pack_into('<I', data, opt + offset, value)
    struct.pack_into('<HH', data, opt + 40, 6, 0)
    struct.pack_into('<HH', data, opt + 48, 6, 0)
    struct.pack_into('<H', data, opt + 68, 2)
    struct.pack_into('<II', data, opt + 104, 0x1080, 40)
    struct.pack_into('<II', data, opt + 192, 0x10d0, 12)
    section = opt + 224
    data[section:section + 8] = b'.text\0\0\0'
    struct.pack_into('<IIII', data, section + 8, 512, 0x1000, 512, 512)
    struct.pack_into('<I', data, section + 36, 0xe0000020)
    code = b'\x68' + struct.pack('<I', sleep) + b'\xff\x15\xd0\x10\x40\0'
    code += b'\x6a' + bytes([exit_code]) + b'\xff\x15\xd4\x10\x40\0'
    data[512:512 + len(code)] = code
    struct.pack_into('<IIIII', data, 512 + 0x80, 0x10c0, 0, 0, 0x10e0, 0x10d0)
    for offset in (0xc0, 0xd0):
        struct.pack_into('<III', data, 512 + offset, 0x10f0, 0x1100, 0)
    for offset, text in ((0xe0, b'kernel32.dll\0'), (0xf0, b'\0\0Sleep\0'),
                         (0x100, b'\0\0ExitProcess\0')):
        data[512 + offset:512 + offset + len(text)] = text
    return bytes(data)
