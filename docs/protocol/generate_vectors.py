#!/usr/bin/env python3
"""최병호 — evidence:client, candidate; static LOGH7 cipher vectors, no target execution.
Usage: python -B docs/protocol/generate_vectors.py CLIENT.exe OUTPUT_DIRECTORY
Only stdlib required. PE constants are read without modifying the input.
"""
import hashlib
import json
from pathlib import Path
import struct
import sys

MASK = 0xffffffff
GUID = b"{A4C13748-0159-4c54-AEB3-1D68575761B3}"


def tables_from_pe(path):
    data = Path(path).read_bytes()
    pe = struct.unpack_from('<I', data, 0x3c)[0]
    assert data[pe:pe+4] == b'PE\0\0'
    count, optsize = struct.unpack_from('<H', data, pe+6)[0], struct.unpack_from('<H', data, pe+20)[0]
    base = struct.unpack_from('<I', data, pe+24+28)[0]
    sections = pe+24+optsize
    def read(va, size):
        rva = va-base
        for i in range(count):
            vs, start, rawsize, raw = struct.unpack_from('<IIII', data, sections+i*40+8)
            if start <= rva and rva+size <= start+rawsize:
                return data[raw+rva-start:raw+rva-start+size]
        raise ValueError('VA outside raw sections')
    decoded = bytes(x ^ 0x91 for x in read(0x7b6ae4, 72)+read(0x7b6ba8, 4096))
    words = list(struct.unpack('<1042I', decoded))
    return words, hashlib.sha256(data).hexdigest(), hashlib.sha256(decoded).hexdigest()


class Cipher:
    def __init__(self, words, key):
        if not 1 <= len(key) <= 65535:
            raise ValueError('nonempty u16 key required')
        self.p, self.s = words[:18], words[18:]
        for i in range(18):
            self.p[i] ^= int.from_bytes(bytes(key[(4*i+j) % len(key)] for j in range(4)), 'big')
        left = right = 0
        for target in (self.p, self.s):
            for i in range(0, len(target), 2):
                left, right = self.block(left, right)
                target[i:i+2] = left, right

    def f(self, x):
        s = self.s
        return (((s[x >> 24] + s[256 + ((x >> 16) & 255)]) & MASK) ^ s[512 + ((x >> 8) & 255)]) + s[768 + (x & 255)] & MASK

    def block(self, left, right, decrypt=False):
        p = self.p[::-1] if decrypt else self.p
        for i in range(16):
            left ^= p[i]
            right ^= self.f(left)
            left, right = right, left
        left, right = right, left
        return left ^ p[17], right ^ p[16]

    def encrypt(self, data):
        data += b'\0' * (-len(data) % 8)
        return b''.join(struct.pack('<II', *self.block(*struct.unpack('<II', data[i:i+8]))) for i in range(0, len(data), 8))

    def decrypt(self, data):
        if len(data) % 8:
            raise ValueError('unaligned ciphertext')
        return b''.join(struct.pack('<II', *self.block(*struct.unpack('<II', data[i:i+8]), decrypt=True)) for i in range(0, len(data), 8))


def checksum(data):
    result = 0
    end = len(data) // 4 * 4
    for i in range(0, end, 4):
        result ^= int.from_bytes(data[i:i+4], 'little')
    for value in data[end:]:
        result ^= value
    return ((result >> 16) ^ result) & 65535


def checked(data):
    return struct.pack('>H', checksum(data)) + data


def envelope(sequence, body):
    return checked(struct.pack('>IH', sequence, len(body)) + body)


def main():
    words, source_hash, table_hash = tables_from_pe(sys.argv[1])
    out = Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    meta = dict(author='최병호', status='candidate', evidence='evidence:client', source_sha256=source_hash,
                validation='static calculation; not captured from a running client')
    def save(name, value):
        (out/name).write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    save('mps-tables.json', dict(meta, table_encoding='numeric u32 hexadecimal; extracted bytes decoded XOR 0x91 then LE32',
                              decoded_le_bytes_sha256=table_hash, p=[f'{v:08x}' for v in words[:18]],
                              s=[[f'{v:08x}' for v in words[18+i*256:18+(i+1)*256]] for i in range(4)]))
    vectors = []
    for key in (bytes(8), bytes(range(16)), GUID):
        cipher = Cipher(words, key)
        for plain in (bytes(8), bytes(range(8)), bytes(range(17))):
            encrypted = cipher.encrypt(plain)
            assert cipher.decrypt(encrypted) == plain + bytes(-len(plain) % 8)
            vectors.append(dict(key_hex=key.hex(), plaintext_hex=plain.hex(), ciphertext_hex=encrypted.hex()))
    save('mps-blowfish.json', dict(meta, block_words='little-endian', key_words='big-endian cyclic',
                                 padding='zero to multiple of 8, no extra aligned block', vectors=vectors))
    cipher = Cipher(words, bytes(range(16)))
    ev = []
    for n in range(10):
        body = bytes(range(n))
        plain = envelope(0x01020304, body)
        ev.append(dict(sequence_hex='01020304', body_hex=body.hex(), checksum_hex=plain[:2].hex(),
                       plaintext_hex=plain.hex(), ciphertext_hex=cipher.encrypt(plain).hex()))
    save('mps-envelope.json', dict(meta, key_hex=bytes(range(16)).hex(), vectors=ev))
    a, b = bytes(range(16)), bytes(range(0xf0, 0x100))
    kex = [checked(struct.pack('>H', len(a))+a+struct.pack('>I', 1)),
           checked(struct.pack('>H', len(a))+a+struct.pack('>H', len(b))+b+struct.pack('>I', 2)),
           checked(struct.pack('>H', len(b))+b)]
    static = Cipher(words, GUID)
    save('mps-kex.json', dict(meta, wrapping_key_hex=GUID.hex(), initiator_tx_key_hex=a.hex(), responder_tx_key_hex=b.hex(),
         vectors=[dict(frame_type_hex=f'{t:04x}', plaintext_hex=p.hex(), ciphertext_hex=static.encrypt(p).hex(),
                       socket_frame_hex=(struct.pack('>HH', 2+len(static.encrypt(p)), t)+static.encrypt(p)).hex())
                  for t, p in zip((0x34, 0x35, 0x36), kex)]))
    print('Wrote four JSON files; 9 cipher round trips passed; tables SHA256='+table_hash)


if __name__ == '__main__':
    main()
