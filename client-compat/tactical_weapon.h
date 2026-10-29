// Explicit AA request marker; native preview/rendering retains arms 2. Author: 최병호.
#pragma once
#include "tactical_weapon_data.h"
static logh::TacticalWeaponSelection tacticalWeaponSelection;
// The CD's weapon menu only loops over beam/gun/missile. AA power is a separate
// specification field, not a fourth element in that three-element array.
static void __cdecl enableTacticalAntiAir(void* menu) {
    auto world=*reinterpret_cast<BYTE**>(0x7ccffc);
    const unsigned count=*reinterpret_cast<unsigned*>(0x2216664);
    if (!menu || !world || !count || count>32) return;
    auto selected=reinterpret_cast<const unsigned*>(0x2215128);
    for (unsigned i=0;i<count;++i) {
        auto unit=reinterpret_cast<BYTE*(__cdecl*)(void*,unsigned,BYTE,BYTE,BYTE,BYTE)>(0x4c7cd0)
            (world+0x126718,selected[i],1,255,255,255);
        if (!unit) return;
        const unsigned kind=at<unsigned short>(unit,0x8bc);
        if (kind>=179 || !at<unsigned short>(world+0x2c1a78+kind*0x2a8,0x29c)) return;
    }
    reinterpret_cast<void(__thiscall*)(void*,unsigned,unsigned,unsigned,BYTE)>(0x508840)(menu,1,0x24,0x24,1);
    reinterpret_cast<void(__thiscall*)(void*,unsigned,unsigned,unsigned,BYTE)>(0x508890)(menu,1,0x24,0x24,1);
}
static unsigned tacticalWeaponMenuResume=0x4efa50;
static void __declspec(naked) finishTacticalWeaponMenu() {
    __asm {
        pushfd
        pushad
        push edi
        call enableTacticalAntiAir
        add esp,4
        popad
        popfd
        jmp dword ptr [tacticalWeaponMenuResume]
    }
}
static void __cdecl selectedTacticalWeapon(unsigned menu) { tacticalWeaponSelection.select(menu); }
// 005113c5 is reached only after the four weapon submenu branches. EBX is still
// 21/22/23/24 here; the very next instruction destroys that surviving distinction.
static void __declspec(naked) rememberTacticalWeapon() {
    __asm {
        pushfd
        push ecx
        push edx
        push ebx
        call selectedTacticalWeapon
        add esp, 4
        pop edx
        pop ecx
        popfd
        mov eax, dword ptr ds:[0x2216664]
        ret
    }
}
static void __fastcall requestTacticalWeapon(void* world, void*, BYTE pending, unsigned short ordinal, void* record) {
    if (!record || ordinal != 0x35 || pending) {
        reinterpret_cast<void(__thiscall*)(void*,BYTE,unsigned short,void*)>(0x4b78a0)(world,pending,ordinal,record);
        return;
    }
    BYTE& kind=at<BYTE>(record,0x91);
    const BYTE original=kind;
    // Only the outgoing record carries bit 7. Restore the native global record
    // immediately, so no native unit/weapon array ever receives a fourth arm.
    kind=static_cast<BYTE>(tacticalWeaponSelection.targetKind(at<BYTE>(record,0x90),kind));
    reinterpret_cast<void(__thiscall*)(void*,BYTE,unsigned short,void*)>(0x4b78a0)(world,pending,ordinal,record);
    kind=original;
    tacticalWeaponSelection.clear();
}
static bool installTacticalWeapon() {
    auto selection=reinterpret_cast<BYTE*>(0x5113c5),request=reinterpret_cast<BYTE*>(0x4b423d);
    const BYTE selectionExpected[]={0xa1,0x64,0x66,0x21,0x02},requestExpected[]={0xe8,0x5e,0x36,0,0};
    auto menu=reinterpret_cast<BYTE*>(0x51137a);
    const BYTE menuExpected[]={0xe8,0xd1,0xe6,0xfd,0xff};
    if (memcmp(selection,selectionExpected,5) || memcmp(request,requestExpected,5) || memcmp(menu,menuExpected,5)) return false;
    BYTE a[5]={0xe8},b[5]={0xe8},c[5]={0xe8};
    *reinterpret_cast<LONG*>(a+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&rememberTacticalWeapon)-selection-5);
    *reinterpret_cast<LONG*>(b+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&requestTacticalWeapon)-request-5);
    *reinterpret_cast<LONG*>(c+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&finishTacticalWeaponMenu)-menu-5);
    return patchBytes(selection,selectionExpected,a,5) && patchBytes(request,requestExpected,b,5) &&
        patchBytes(menu,menuExpected,c,5);
}
