// Complete, ordered and fingerprinted public image transfers. Author: Peppone Choi.
#pragma once
#include "lobby_country_data.h"
namespace logh {
struct CountryImageData {
    unsigned id=0,flags=0,nativeEmblem=0;std::uint64_t revision=0;unsigned char rgba[8192]={};
    const unsigned char* pixels(bool map) const {
        if(map && (flags&2))return rgba+((flags&1) ? 4096 : 0);
        return flags&1 ? rgba : nullptr;
    }
};
inline unsigned countryImageHash(const unsigned char* bytes,unsigned size) {
    unsigned hash=2166136261u;for(unsigned i=0;i<size;++i)hash=(hash^bytes[i])*16777619u;return hash;
}
struct CountryImageDraft {
    CountryImageData value;unsigned nonce=0,bytes=0,hash=0,length=0,chunks=0,received=0,page=0,mode=17;
    std::uint64_t minimum=0;bool header=false,complete=false;char text[10929]={};
    void begin(unsigned country,unsigned token,std::uint64_t revision,unsigned queryMode=17) {
        *this={};value.id=country;nonce=token;minimum=revision;mode=queryMode;
    }
    int accept(const unsigned char* p,std::size_t size) {
        if(!nonce || complete || !p || size<24 || p[8]!=mode || p[9]!=2 || paintWord(p)!=value.id || paintWord(p+4)!=nonce)return 0;
        const unsigned kind=paintWord(p+12),number=paintWord(p+16);
        if(kind==0) {
            wchar_t fields[10][13]={};std::uint64_t n[5]={};std::uint64_t revision=0;
            if(header || number || !lobbyCountryFields(p,size,fields,mode) || wcscmp(fields[0],L"CI1") ||
                !lobbyCountryRevision(fields[1],fields[2],revision) || revision<minimum ||
                !lobbyCountryNumber(fields[3],8194,n[0]) || !lobbyCountryNumber(fields[4],0xffffffffULL,n[1]) ||
                !lobbyCountryNumber(fields[5],10928,n[2]) || !lobbyCountryNumber(fields[6],911,n[3]) ||
                wcscmp(fields[7],L"32") || fields[9][0])return 0;
            std::uint64_t emblem=0;
            if(mode==18 ? (!lobbyCountryNumber(fields[8],3,emblem) || emblem==1 || n[0]>4098) : fields[8][0]!=0)return 0;
            bytes=static_cast<unsigned>(n[0]);hash=static_cast<unsigned>(n[1]);length=static_cast<unsigned>(n[2]);chunks=static_cast<unsigned>(n[3]);
            if((bytes!=2 && bytes!=4098 && bytes!=8194) || ((mode>=20 && mode<=22) && (bytes>4098 || (revision>0)!=(bytes==4098))) ||
                length!=((bytes+2)/3)*4 || chunks!=(length+11)/12)return 0;
            value.revision=revision;value.nativeEmblem=static_cast<unsigned>(emblem);header=true;return 1;
        }
        const unsigned count=chunks-received<10 ? chunks-received : 10;
        if(kind!=1 || !header || number!=page || !count || p[20]!=count || size<24+count*36)return 0;
        char row[121]={};unsigned used=0;
        for(unsigned i=0;i<count;++i) {
            const auto member=p+24+i*36;wchar_t field[13]={};
            const unsigned expected=length-(received+i)*12<12 ? length-(received+i)*12 : 12;
            if(paintWord(member)!=received+i+1 || member[4] || member[5] || !countryMetadataText(member,field) || wcslen(field)!=expected)return 0;
            for(unsigned j=0;j<expected;++j) {
                if(field[j]>127)return 0;
                row[used++]=static_cast<char>(field[j]);
            }
        }
        memcpy(text+received*12,row,used);received+=count;++page;
        if(received!=chunks)return 1;
        unsigned char raw[8194]={};unsigned out=0;
        for(unsigned i=0;i<length;i+=4) {
            unsigned decoded=0,padding=0;
            for(unsigned j=0;j<4;++j) {
                const char c=text[i+j];unsigned digit=0;
                if(c>='A' && c<='Z')digit=c-'A';else if(c>='a' && c<='z')digit=c-'a'+26;
                else if(c>='0' && c<='9')digit=c-'0'+52;else if(c=='+')digit=62;else if(c=='/')digit=63;
                else if(c=='=' && i+4==length && j>=2)++padding;else return 0;
                if(padding && c!='=')return 0;
                decoded=(decoded<<6)|digit;
            }
            if(padding>2 || (padding==1 && (decoded&255)) || (padding==2 && (decoded&65535)))return 0;
            for(unsigned j=0;j<3-padding;++j) {if(out>=bytes)return 0;raw[out++]=static_cast<unsigned char>(decoded>>(16-j*8));}
        }
        if(out!=bytes || countryImageHash(raw,bytes)!=hash || raw[0]!=1 || raw[1]>((mode>=18) ? 1 : 3) ||
            bytes!=2u+4096u*static_cast<unsigned>((raw[1]&1)+((raw[1]>>1)&1)))return 0;
        value.flags=raw[1];memcpy(value.rgba,raw+2,bytes-2);complete=true;return 2;
    }
};
}
