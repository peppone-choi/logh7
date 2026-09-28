"""최병호 — evidence:client/guess, candidate. Fixed synthetic deep-login vectors.
Run: python -B docs/protocol/generate_deep_vectors.py CLIENT.exe OUTPUT_DIRECTORY
Does not run or modify the client. Cipher implementation reused from generate_vectors.
"""
import hashlib
import json
from pathlib import Path
import struct
import sys
from generate_vectors import Cipher, GUID, checked, envelope, tables_from_pe


def u16(n): return struct.pack('>H', n)
def u32(n): return struct.pack('>I', n)
def text16(s): return (s + '\0').encode('utf-16-be')


def main():
    words, source_hash, _ = tables_from_pe(sys.argv[1])
    a, b = bytes(range(16)), bytes(range(240, 256))
    wrap = Cipher(words, GUID)
    rows = []
    def add(name, kind, plain, key, expected, direction, **extra):
        cipher = Cipher(words, key).encrypt(plain)
        assert Cipher(words, key).decrypt(cipher) == plain + bytes(-len(plain) % 8)
        frame = u16(len(cipher)+2)+u16(kind)+cipher
        rows.append(dict(name=name, direction=direction, outer_type=kind,
                         key_hex=key.hex(), plaintext_hex=plain.hex(),
                         ciphertext_hex=cipher.hex(), frame_hex=frame.hex(),
                         expected=expected, **extra))
    phase2 = u16(16)+a+u16(16)+b+u32(2)
    add('phase3-valid', 0x35, checked(phase2), GUID, 'phase3 accepts; replies with phase4-echo-b', 'S2C')
    wrong_a = bytes([a[0]^1])+a[1:]
    add('phase3-wrong-echo', 0x35, checked(u16(16)+wrong_a+u16(16)+b+u32(2)), GUID,
        'reject at 0x6457e9; close; no 0x36', 'S2C')
    invalid = bytearray(checked(phase2)); invalid[0] ^= 1
    add('phase3-bad-checksum', 0x35, bytes(invalid), GUID,
        'reject at 0x645905; close; no 0x36', 'S2C')
    add('phase3-wrong-length', 0x35, checked(u16(15)+a[:15]+u16(16)+b+u32(2)), GUID,
        'reject at 0x645795; close; no 0x36', 'S2C')
    add('phase4-echo-b', 0x36, checked(u16(16)+b), GUID, 'expected response to phase3-valid', 'C2S')
    def app(name, opcode, body, direction='S2C', login=False, expected='parser candidate; dynamic pending'):
        header = u16(opcode) if direction=='C2S' else (u16(opcode)+bytes(4) if login else bytes(4)+u16(opcode))
        message = header+body
        add(name,0x30,envelope(1 if direction=='C2S' else 2,message), a if direction=='C2S' else b,
            expected,direction,opcode=opcode,body_hex=body.hex(),message_hex=message.hex(),
            auxiliary_header='synthetic zero; meaning unverified' if direction=='S2C' else 'none')
    login_body=b'GIN7'+u16(1)+u16(0)+bytes([0,2])+text16('A')+bytes([2])+text16('B')
    app('first-login',0x7000,login_body,'C2S',expected='first application message after login-connection kex')
    assert len(rows[-1]['message_hex'])//2==21
    app('login-redirect',0x7001,u16(0)+u32(0x0100007f)+u16(47901)+u32(0x11223344),login=True)
    for code in (0,1,2,255):
        app('login-error-'+str(code),0x7002,u16(0)+bytes([code])+u16(0),login=True)
    app('session-transport-token',0x20,u32(0x11223344),'C2S',expected='after redirect and new kex; followed by 0x2000')
    app('lobby-login-ok',0x2001,bytes(3))
    app('lobby-login-ng',0x2002,bytes([1,0]))
    app('session-list-empty',0x2006,bytes(2))
    power = bytes(1)+u32(0)*3+bytes(1)
    item = u16(1)+bytes([0,3])+text16('S1')+bytes(1)+u32(0)+power*2+bytes(1)
    assert len(item)==44
    app('session-list-one',0x2006,bytes([0,1])+item)
    app('lobby-session-select',0x2009,u16(1),'C2S')
    app('lobby-session-ok',0x200a,u32(0x0100007f)+u16(47902)+u32(0x55667788))
    app('lobby-session-ng',0x200b,bytes([1,0]))
    app('ss-login-ok',0x201,bytes(1))
    app('ss-login-ng',0x202,bytes([1,0]))
    out=Path(sys.argv[2])/'mps-login-deep.json'
    payload=dict(author='최병호',status='candidate',evidence=['E-310','E-311','E-312','E-314'],
                 provenance='Synthetic fixed inputs; no captured game bytes. Each application vector is independent, not one sequence.',
                 source_sha256=source_hash,client_key_hex=a.hex(),server_key_hex=b.hex(),vectors=rows)
    out.write_text(json.dumps(payload,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    check=json.loads(out.read_text(encoding='utf-8'))
    assert len(check['vectors'])==len(rows)
    print(f'{out}: {len(rows)} vectors; all cipher roundtrips passed; sha256={hashlib.sha256(out.read_bytes()).hexdigest()}')


if __name__=='__main__': main()
