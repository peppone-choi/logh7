#!/usr/bin/env python3
"""PE 트리아지: 헤더·섹션·임포트(네트워크 API 강조)·의심 문자열 요약.

사용법: python tools/petriage.py <exe|dll> [--strings]
정적 분석 전용(실행하지 않는다).
"""
import re
import sys

import pefile

NET_HINT = ("ws2_32", "wsock32", "wininet", "winhttp", "urlmon", "mswsock")
CRYPT_HINT = ("crypt32", "advapi32", "bcrypt", "ncrypt", "secur32")
SOCK_FUNCS = ("socket", "connect", "send", "recv", "sendto", "recvfrom", "WSAStartup",
              "WSAConnect", "WSASend", "WSARecv", "gethostbyname", "getaddrinfo",
              "inet_addr", "htons", "bind", "listen", "accept", "select", "closesocket",
              "InternetOpen", "InternetConnect", "HttpOpenRequest", "HttpSendRequest",
              "WinHttpConnect", "WinHttpOpen")

MACHINE = {0x14C: "x86", 0x8664: "x64", 0x1C0: "ARM"}


def main(path, want_strings):
    pe = pefile.PE(path, fast_load=True)
    pe.parse_data_directories(directories=[
        pefile.DIRECTORY_ENTRY["IMAGE_DIRECTORY_ENTRY_IMPORT"],
        pefile.DIRECTORY_ENTRY["IMAGE_DIRECTORY_ENTRY_EXPORT"],
    ])
    fh = pe.FILE_HEADER
    oh = pe.OPTIONAL_HEADER
    ts = fh.TimeDateStamp
    import datetime
    dt = datetime.datetime.utcfromtimestamp(ts).isoformat() + "Z" if ts else "0"
    print(f"== {path}")
    print(f"machine={MACHINE.get(fh.Machine, hex(fh.Machine))} "
          f"timestamp={ts}({dt}) subsystem={oh.Subsystem} "
          f"entry=0x{oh.AddressOfEntryPoint:x} image_base=0x{oh.ImageBase:x} "
          f"dll_char=0x{oh.DllCharacteristics:x}")
    aslr = bool(oh.DllCharacteristics & 0x40)
    nx = bool(oh.DllCharacteristics & 0x100)
    print(f"aslr={aslr} nx={nx}")
    print("sections:")
    for s in pe.sections:
        name = s.Name.rstrip(b"\x00").decode("latin-1")
        ent = s.get_entropy()
        flag = " HIGH-ENTROPY(packed?)" if ent > 7.2 else ""
        print(f"  {name:8} vsz=0x{s.Misc_VirtualSize:06x} rsz=0x{s.SizeOfRawData:06x} entropy={ent:.2f}{flag}")

    print("imports:")
    if hasattr(pe, "DIRECTORY_ENTRY_IMPORT"):
        for entry in pe.DIRECTORY_ENTRY_IMPORT:
            dll = entry.dll.decode("latin-1")
            low = dll.lower()
            tag = ""
            if any(h in low for h in NET_HINT):
                tag = "  <== NETWORK"
            elif any(h in low for h in CRYPT_HINT):
                tag = "  <== CRYPTO?"
            funcs = []
            for imp in entry.imports:
                if imp.name:
                    funcs.append(imp.name.decode("latin-1"))
                elif imp.ordinal is not None:
                    funcs.append(f"#ord{imp.ordinal}")
            sock = [f for f in funcs if any(sf.lower() in f.lower() for sf in SOCK_FUNCS)]
            print(f"  {dll} ({len(funcs)} funcs){tag}")
            if sock:
                print(f"      sock/net: {', '.join(sorted(set(sock)))}")
    else:
        print("  (none)")

    if want_strings:
        data = open(path, "rb").read()
        # ASCII
        ascii_s = re.findall(rb"[\x20-\x7e]{5,}", data)
        pats = {
            "url": re.compile(rb"https?://[^\s\"'<>]{4,}"),
            "host": re.compile(rb"\b(?:[a-z0-9-]+\.)+(?:com|net|jp|co\.jp|org)\b", re.I),
            "ip": re.compile(rb"\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b"),
            "ini": re.compile(rb"[\w-]+\.(?:ini|cfg|dat|xml|conf)\b", re.I),
            "port": re.compile(rb"\b(?:port|PORT|Port)\b"),
            "reg": re.compile(rb"(?:SOFTWARE|Software)\\\\?[\w\\ ]+", ),
        }
        hits = {k: set() for k in pats}
        for s in ascii_s:
            for k, rx in pats.items():
                for m in rx.findall(s):
                    hits[k].add(m.decode("latin-1"))
        for k in ("url", "host", "ip", "reg", "ini"):
            vals = sorted(hits[k])
            if vals:
                print(f"strings[{k}] ({len(vals)}):")
                for v in vals[:60]:
                    print(f"    {v}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    main(sys.argv[1], "--strings" in sys.argv)
