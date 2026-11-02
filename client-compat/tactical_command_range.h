// Restore the server's current CRC from the decoded 033f corps cache. Author: Peppone Choi.
#pragma once
static void applyTacticalCommandRange(void* world) {
    if (!world || !at<BYTE>(world,0x126718)) return;
    unsigned count=at<unsigned short>(world,0x4044b8);
    if (count>600) return;
    for (unsigned i=0;i<600;++i) {
        auto unit=static_cast<BYTE*>(world)+0x12671c+i*0x9ec;
        if (!at<BYTE>(unit,0) || at<BYTE>(unit,8)!=1 || at<BYTE>(unit,9)!=0) continue;
        unsigned character=at<unsigned>(unit,0x984);
        for (unsigned j=0;j<count;++j) {
            auto row=static_cast<BYTE*>(world)+0x4044bc+j*0x3c;
            if (at<unsigned>(row,0)!=character) continue;
            float radius=at<float>(row,0xc),maximum=at<float>(unit,0x8c8);
            if (!_finite(radius) || !_finite(maximum) || radius<0 || radius>maximum) break;
            for (unsigned k=0;k<600;++k) {
                auto person=static_cast<BYTE*>(world)+12+k*0x370;
                if (!at<BYTE>(person,0) || at<unsigned>(person,0x24)!=character) continue;
                unsigned stamina=at<BYTE>(person,0x1cd);
                if (stamina<80) stamina=80;
                double growth=at<short>(person,0x1bc)*stamina/80000.0;
                if (growth<0.0000001) growth=0.0000001;
                at<float>(unit,0x8cc)=static_cast<float>(maximum/growth);
                at<float>(unit,0x8d0)=static_cast<float>(radius/growth);
                break;
            }
            break;
        }
    }
}
static void applyTacticalSupplies(void* world);
static void __cdecl receivedCommandRange() {
    void* world=*reinterpret_cast<void**>(0x7ccffc);
    applyTacticalCommandRange(world);
    applyTacticalSupplies(world);
}
static void __declspec(naked) commandRangeReceived() {
    __asm {
        pushfd
        pushad
        call receivedCommandRange
        popad
        popfd
        push 0x4bdd33
        ret
    }
}
static bool installTacticalCommandRange() {
    auto site=reinterpret_cast<BYTE*>(0x4bb53e);
    const BYTE expected[]={0xe9,0xf0,0x27,0,0};
    BYTE jump[5]={0xe9};
    *reinterpret_cast<LONG*>(jump+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&commandRangeReceived)-site-5);
    return patchBytes(site,expected,jump,5);
}
