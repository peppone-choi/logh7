"""검토한 EXE 고정 UI 주소의 번역. 원본은 읽기 전용. 작성: 최병호."""
from pathlib import Path
import struct
import re

import msgdat


def collect(source, document, validate):
    data=Path(source).read_bytes()
    if msgdat.sha(data)!=document['source_sha256']:
        raise ValueError('static UI EXE hash mismatch')
    pe=struct.unpack_from('<I',data,0x3c)[0]
    if data[pe:pe+4]!=b'PE\0\0':raise ValueError('expected PE image')
    count=struct.unpack_from('<H',data,pe+6)[0]
    optional=pe+24;table=optional+struct.unpack_from('<H',data,pe+20)[0]
    if struct.unpack_from('<H',data,optional)[0]!=0x10b:raise ValueError('expected PE32')
    base=struct.unpack_from('<I',data,optional+28)[0]
    sections=[]
    for index in range(count):
        position=table+index*40
        name=data[position:position+8].rstrip(b'\0')
        size,va,raw_size,offset=struct.unpack_from('<IIII',data,position+8)
        if name in (b'.data',b'.rdata'):sections.append((base+va,raw_size,offset))
    rows=[]
    for address,text in document['entries'].items():
        address=int(address,16)
        section=next((row for row in sections if row[0]<=address<row[0]+row[1]),None)
        if not section:raise ValueError('static UI address outside original data sections')
        start=section[2]+address-section[0]
        end=data.find(b'\0',start,min(start+4096,section[2]+section[1]))
        if end<0:raise ValueError('unterminated static UI')
        raw=data[start:end];validate(raw.decode('cp932'),text)
        rows.append(dict(table=65535,group=address>>16,index=address&0xffff,source=raw,text=text))
    return rows


def windows_strings(data):
    pe=struct.unpack_from('<I',data,0x3c)[0];optional=pe+24
    table=optional+struct.unpack_from('<H',data,pe+20)[0]
    sections=[struct.unpack_from('<IIII',data,table+i*40+8)
              for i in range(struct.unpack_from('<H',data,pe+6)[0])]
    def offset(rva):
        row=next((row for row in sections if row[1]<=rva<row[1]+row[2]),None)
        if not row:raise ValueError('resource RVA outside original image')
        return row[3]+rva-row[1]
    rva,size=struct.unpack_from('<II',data,optional+96+2*8);root=offset(rva)
    def entries(relative):
        if not 0<=relative<=size-16:raise ValueError('resource directory bounds')
        named,ids=struct.unpack_from('<HH',data,root+relative+12)
        if relative+16+(named+ids)*8>size:raise ValueError('resource entry bounds')
        return [struct.unpack_from('<II',data,root+relative+16+i*8) for i in range(named+ids)]
    string_directory=next(value&0x7fffffff for kind,value in entries(0) if kind==6 and value&0x80000000)
    result={}
    for block,value in entries(string_directory):
        if block&0x80000000 or not value&0x80000000:raise ValueError('unexpected string block')
        for language,entry in entries(value&0x7fffffff):
            if entry&0x80000000 or entry>size-16:raise ValueError('unexpected string resource data')
            location,length=struct.unpack_from('<II',data,root+entry)
            content=data[offset(location):offset(location)+length];position=0
            for index in range(16):
                if position+2>len(content):raise ValueError('truncated string length')
                units=struct.unpack_from('<H',content,position)[0];position+=2
                if position+units*2>len(content):raise ValueError('truncated Unicode string')
                text=content[position:position+units*2].decode('utf-16-le');position+=units*2
                if text:
                    key=(block-1)*16+index
                    if key in result and result[key]!=text:raise ValueError('conflicting resource languages')
                    result[key]=text
    return result


def collect_windows(source,document,validate):
    data=Path(source).read_bytes()
    if msgdat.sha(data)!=document['source_sha256']:raise ValueError('Windows UI EXE hash mismatch')
    original=windows_strings(data);rows=[]
    for key,new in document['entries'].items():
        identifier=int(key);old=original[identifier];validate(old,new)
        if re.findall(r'%\d+(?:![^!]*!)?',old)!=re.findall(r'%\d+(?:![^!]*!)?',new):
            raise ValueError('Windows insert arguments changed')
        rows.append(dict(table=65533,group=0,index=identifier,source=old.encode('cp932'),text=new))
    untranslated=[identifier for identifier,text in original.items()
                  if re.search(r'[\u3040-\u30ff\u3400-\u9fff\uff66-\uff9f]',text) and str(identifier) not in document['entries']]
    if untranslated:raise ValueError(f'untranslated Windows resource IDs: {untranslated}')
    return rows
