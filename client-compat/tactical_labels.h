// Tactical labels describe the ship rather than its commander. Author: 최병호.
#pragma once
static void applyTacticalLabels(void* world) {
    if (!world || !at<BYTE>(world,0x126718)) return;
    for (unsigned i=0;i<600;++i) {
        auto unit=static_cast<BYTE*>(world)+0x12671c+i*0x9ec;
        if (!at<BYTE>(unit,0) || at<BYTE>(unit,8)!=1) continue;
        unsigned id=at<unsigned>(unit,4);
        wchar_t label[256]={};
        if (at<BYTE>(unit,9)==0) {
            for (unsigned j=0;j<600;++j) {
                auto person=static_cast<BYTE*>(world)+12+j*0x370;
                if (!at<BYTE>(person,0) || at<unsigned>(person,0x48)!=id) continue;
                unsigned count=at<BYTE>(person,0x4c);
                auto name=reinterpret_cast<const wchar_t*>(person+0x4e);
                if (count>1 && count<=13 && !name[count-1]) {
                    memcpy(label,name,count*sizeof(wchar_t));
                }
                break;
            }
        }
        if (!label[0]) {
            unsigned kind=at<unsigned short>(unit,0x8bc);
            wchar_t type[64]={};
            if (kind<179) {
                auto value=reinterpret_cast<const char*(__thiscall*)(void*,int,int)>(0x522010)
                    (reinterpret_cast<void*>(0x2217400),85,kind);
                if (value) MultiByteToWideChar(932,0,value,-1,type,_countof(type));
            }
            if (type[0]) _snwprintf_s(label,_countof(label),_TRUNCATE,L"%s #%u",type,id);
            else _snwprintf_s(label,_countof(label),_TRUNCATE,L"함정 #%u",id);
        }
        memcpy(unit+0x6bc,label,sizeof(label));
    }
}
static bool installTacticalLabels() {
    // Preserve renderer availability, visibility and the existing 100-label limit.
    const BYTE first[]={0x74,0x40},second[]={0x74,0x3e},flagshipOnly[]={0x74,0x42},enabled[]={0x90,0x90};
    auto a=reinterpret_cast<void*>(0x4c804e),b=reinterpret_cast<void*>(0x4c80ce);
    auto c=reinterpret_cast<void*>(0x4f04e8);
    if (memcmp(a,first,2) || memcmp(b,second,2) || memcmp(c,flagshipOnly,2)) return false;
    // The caller also gates the text on the flagship bit. Keep its full-identification
    // check (+0x2e == 0) and every shield/status-bar condition unchanged.
    return patchBytes(a,first,enabled,2) && patchBytes(b,second,enabled,2) &&
        patchBytes(c,flagshipOnly,enabled,2);
}
