// Verified native logistics captions and compact formats. Author: Peppone Choi.
#pragma once
#include <string>
static void __fastcall outfitTroopSummary(void* editor,void*) {
    reinterpret_cast<void(__thiscall*)(void*)>(0x533500)(editor);
    // Tab 1 already renders all troop values in element 12. Element 11 shares
    // its origin and otherwise draws the passenger/infantry text a second time.
    if(at<int>(editor,0x2d4)!=1)return;
    void* element=at<void*>(editor,8+11*4);
    if(element && at<BYTE>(element,8))
        reinterpret_cast<void(__cdecl*)(void*,const wchar_t*,int)>(0x4ea8b0)(
            reinterpret_cast<BYTE*>(element)+0xb04,L"",-1);
}
static bool installOutfitEditorLabels(bool apply) {
    const unsigned calls[]={0x53214f,0x5332fc};
    const BYTE expectedCalls[][5]={{0xe8,0xac,0x13,0,0},{0xe8,0xff,1,0,0}};
    for(unsigned i=0;i<_countof(calls);++i)
        if(memcmp(reinterpret_cast<void*>(calls[i]),expectedCalls[i],5))return false;
    struct Label {unsigned address; BYTE before;};
    const Label labels[]={
        {0x532df0,0x2f},
        {0x532e19,0x2f},
        {0x532e31,0x2e},
        {0x532e48,0x2e},
        {0x532e57,0x2d},
        {0x532e66,0x2c},
        {0x532f4e,0x2f},
        {0x532f77,0x2f},
        {0x532f8f,0x31},
        {0x532faa,0x31},
        {0x532fc5,0x30},
        {0x532fe4,0x30},
        {0x532ff3,0x2d},
        {0x533002,0x2c},
        {0x53309f,0x2f},
        {0x5330c8,0x2f},
        {0x5330e0,0x2e},
        {0x5330f7,0x2e},
        {0x533106,0x2d},
        {0x533115,0x2c},
        {0x5331a7,0x2f},
        {0x5331d0,0x2f},
        {0x5331e4,0x2e},
        {0x5331ff,0x2e},
        {0x53320e,0x2d},
        {0x53321d,0x2c},
        {0x533247,0x33},
        {0x533259,0x32},
        {0x5332b1,0x34},
        {0x5335a7,0x38},
        {0x5335bd,0x37},
        {0x5335d3,0x36},
        {0x533606,0x35},
        {0x533761,0x38},
        {0x533775,0x37},
        {0x533794,0x34},
        {0x5337a8,0x36},
        {0x5337c1,0x2f},
        {0x5337d6,0x35},
        {0x5337ed,0x2e},
    };
    struct Format {unsigned address,size,hash;const char* text;};
    const Format formats[]={
        {0x788768,72,0x63ad008cu,"%s %s%d%s/%d\n%s %d  %s %d"},
        {0x788754,19,0x8db3c58eu,"%s %s%d%s/%d"},
        {0x7886e8,107,0xbe9a1996u,"%s / %s\n%s %s%d%s/%d | %s %s%d%s/%d\n%s %d(%d) | %s %d(%d)"},
        {0x788654,145,0x19cdea35u,"%s / %s\n%s %s%d%s/%d | %s %s%d%s/%d\n%s %s%d%s/%d | %s %s%d%s/%d\n%s %d(%d) | %s %d(%d)"},
        {0x7885e8,105,0xeaaffc45u,"%s / %s\n%s %s%d%s/%d | %s %s%d%s/%d\n%s %d(%d) | %s %d(%d)"},
        {0x7885b4,17,0xa2c7d966u,"%s %d/%d"},
        {0x7888a8,109,0xfc32ce63u,"%s %d/%d\n%s %d\n%s %d\n%s %d"},
        {0x7887f8,176,0x132ca5c5u,"%s %s%d%s/%d\n%s %d/%d\n%s %d(%d)\n\n%s %d\n%s %s%d%s/%d\n%s %d\n%s %d"},
    };
    for(const auto& label:labels) {
        auto p=reinterpret_cast<const BYTE*>(label.address);
        if(p[0]!=0x6a || p[1]!=label.before || p[2]!=0x6a || p[3]!=0x16)return false;
    }
    for(const auto& format:formats) {
        auto p=reinterpret_cast<const BYTE*>(format.address);unsigned hash=2166136261u;
        for(unsigned i=0;i<format.size;++i)hash=(hash^p[i])*16777619u;
        if(hash!=format.hash || strlen(format.text)+1>format.size)return false;
    }
    if(!apply)return true;
    for(unsigned i=0;i<_countof(calls);++i) {
        BYTE call[5]={0xe8};
        *reinterpret_cast<LONG*>(call+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&outfitTroopSummary)-
            reinterpret_cast<BYTE*>(calls[i])-5);
        if(!patchBytes(reinterpret_cast<void*>(calls[i]),expectedCalls[i],call,5))return false;
    }
    for(const auto& label:labels) {
        BYTE after=label.before-1;
        if(!patchBytes(reinterpret_cast<void*>(label.address+1),&label.before,&after,1))return false;
    }
    for(const auto& format:formats) {
        const auto p=reinterpret_cast<const char*>(format.address);
        std::string before(p,format.size),after(format.size,0);
        memcpy(&after[0],format.text,strlen(format.text));
        if(!patchBytes(reinterpret_cast<void*>(format.address),before.data(),after.data(),format.size))return false;
    }
    return true;
}
