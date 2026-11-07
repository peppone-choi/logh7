// Native friendly ship totals use the viewer's actual country. Author: Peppone Choi.
#pragma once
static unsigned __cdecl ownFleetCountColor(unsigned color) {
    auto world=*reinterpret_cast<BYTE**>(0x7ccffc);auto own=world ? at<BYTE*>(world,8) : nullptr;
    if(!own || !own[0] || *reinterpret_cast<void**>(0x21aa888)!=own || own[0x29])return color;
    const auto country=runtimeOwnCountry(world,at<unsigned>(own,0x24),own[0x28]);
    return country ? (color&0xff000000)|countryPaintRgb(country,3,color&0xffffff) : color;
}
static unsigned fleetCountOriginal=0x4ce350;
static void __declspec(naked) drawOwnFleetCount() {
    __asm {
        pushfd
        pushad
        lea edx,[esp+36]
        push dword ptr [edx+16]
        call ownFleetCountColor
        add esp,4
        mov [esp+52],eax
        popad
        popfd
        jmp dword ptr [fleetCountOriginal]
    }
}
static bool installFleetCountryColor() {
    // 004d88da colors node+4 (friendly ships); node+8 is another contact.
    auto site=reinterpret_cast<BYTE*>(0x4d88da);BYTE expected[5]={0xe8},replacement[5]={0xe8};
    *reinterpret_cast<LONG*>(expected+1)=static_cast<LONG>(fleetCountOriginal-reinterpret_cast<unsigned>(site)-5);
    *reinterpret_cast<LONG*>(replacement+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&drawOwnFleetCount)-site-5);
    return patchBytes(site,expected,replacement,5);
}
