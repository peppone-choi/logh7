// Decoded mode-4 country display records; no game-state writes. Author: Peppone Choi (peppone-choi).
#pragma once
#include <cstddef>
#include <cstdint>
#include <cwchar>
namespace logh {
struct CountryPaint {unsigned id=0,mask=0,rgb[4]={};std::uint64_t revision=0;};
inline unsigned paintWord(const unsigned char* p) {
    return unsigned(p[0])|(unsigned(p[1])<<8)|(unsigned(p[2])<<16)|(unsigned(p[3])<<24);
}
inline bool paintText(const unsigned char* member,wchar_t (&text)[13]) {
    unsigned n=member[6];text[0]=0;
    if(!n)return true;
    if(n>13 || member[8+(n-1)*2] || member[9+(n-1)*2])return false;
    for(unsigned i=0;i+1<n;++i) {
        unsigned value=member[8+i*2]|(member[9+i*2]<<8);
        if(value<32 || value>126)return false;
        text[i]=static_cast<wchar_t>(value);
    }
    text[n-1]=0;return true;
}
inline bool readCountryPaint(const unsigned char* p,std::size_t size,unsigned country,unsigned nonce,CountryPaint& output) {
    if(!p || size<24+6*36 || !country || !nonce || paintWord(p)!=country || paintWord(p+4)!=nonce || p[8]!=4 || p[20]!=6)return false;
    CountryPaint candidate;candidate.id=country;wchar_t text[6][13]={};
    for(unsigned i=0;i<6;++i) {
        auto row=p+24+i*36;
        if(paintWord(row)!=i+1 || row[4]!=i || row[5] || !paintText(row,text[i]))return false;
        if(i>=4)continue;
        if(!std::wcscmp(text[i],L"inherit"))continue;
        if(std::wcslen(text[i])!=6)return false;
        unsigned color=0;
        for(unsigned j=0;j<6;++j) {
            wchar_t c=text[i][j];unsigned digit;
            if(c>=L'0' && c<=L'9')digit=c-L'0';else if(c>=L'A' && c<=L'F')digit=c-L'A'+10;else return false;
            color=(color<<4)|digit;
        }
        candidate.rgb[i]=color;candidate.mask|=1u<<i;
    }
    const auto first=std::wcslen(text[4]),second=std::wcslen(text[5]);
    if(!first || first+second>19 || (second && first!=12) || (first+second>1 && text[4][0]==L'0'))return false;
    for(unsigned i=4;i<6;++i)for(const wchar_t* c=text[i];*c;++c) {
        if(*c<L'0' || *c>L'9')return false;
        unsigned digit=*c-L'0';
        if(candidate.revision>(0x7fffffffffffffffULL-digit)/10)return false;
        candidate.revision=candidate.revision*10+digit;
    }
    output=candidate;return true;
}
}
