// AA power/preview is separate; native weapon arrays and outgoing arm remain 0..2. Author: 최병호.
#pragma once
#include "tactical_weapon_data.h"
static logh::TacticalWeaponSelection tacticalWeaponSelection;
// All pointers below come from the current native world/selection, never another window.
static BYTE* tacticalWeaponWorld() { return *reinterpret_cast<BYTE**>(0x7ccffc); }
static BYTE* tacticalWeaponUnit(BYTE* world,unsigned id) {
    if (!world || !at<BYTE>(world,0x126718))return nullptr;
    return reinterpret_cast<BYTE*(__cdecl*)(void*,unsigned,BYTE,BYTE,BYTE,BYTE)>(0x4c7cd0)
        (world+0x126718,id,1,255,255,255);
}
static unsigned tacticalAntiAirPower(BYTE* world,BYTE* unit) {
    if (!world || !unit)return 0;
    const unsigned kind=at<unsigned short>(unit,0x8bc);
    return kind<179 ? at<unsigned short>(world+0x2c1a78+kind*0x2a8,0x29c) : 0;
}
static bool selectedTacticalAntiAir(BYTE* world,unsigned* powers) {
    const unsigned count=*reinterpret_cast<unsigned*>(0x2216664);
    auto ids=reinterpret_cast<const unsigned*>(0x2215128);
    if (!powers || !world || !at<BYTE>(world,0x126718) || !count || count>32)return false;
    for(unsigned i=0;i<count;++i)powers[i]=tacticalAntiAirPower(world,tacticalWeaponUnit(world,ids[i]));
    return logh::TacticalWeaponSelection::canSelect(ids,powers,count);
}
static void clearTacticalAntiAir() {
    auto world=tacticalWeaponWorld();
    // A world change invalidates the binding; never follow a pointer into the old world.
    if (world && world==tacticalWeaponSelection.world)for(unsigned i=0;i<tacticalWeaponSelection.count;++i) {
        auto unit=tacticalWeaponUnit(world,tacticalWeaponSelection.units[i]);
        if(unit)logh::TacticalAntiAirPreview::clear(unit+0x5e0);
    }
    tacticalWeaponSelection.clear();
}
// Menu 24 has its own power field, not a fourth beam/gun/missile element.
static void __cdecl enableTacticalAntiAir(void* menu) {
    clearTacticalAntiAir();
    unsigned powers[32]={};const BYTE enabled=selectedTacticalAntiAir(tacticalWeaponWorld(),powers) ? 1 : 0;
    if (!menu)return;
    reinterpret_cast<void(__thiscall*)(void*,unsigned,unsigned,unsigned,BYTE)>(0x508840)(menu,1,0x24,0x24,enabled);
    reinterpret_cast<void(__thiscall*)(void*,unsigned,unsigned,unsigned,BYTE)>(0x508890)(menu,1,0x24,0x24,enabled);
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
static void __cdecl selectedTacticalWeapon(unsigned menu) {
    clearTacticalAntiAir();tacticalWeaponSelection.select(menu);
    if (tacticalWeaponSelection.antiAir()) {
        auto world=tacticalWeaponWorld();unsigned powers[32]={};
        if(selectedTacticalAntiAir(world,powers))tacticalWeaponSelection.bind(world,
            reinterpret_cast<const unsigned*>(0x2215128),powers,*reinterpret_cast<unsigned*>(0x2216664));
    }
}
// 0051140c is cdecl(unit, unsigned arm, preview). Its caller cleans 12 bytes.
// Build only AA's existing 0xdc-byte preview; all three native arms still use 004c7820.
static unsigned __cdecl previewTacticalWeapon(BYTE* unit,unsigned arms,BYTE* preview) {
    if(!tacticalWeaponSelection.antiAir())return reinterpret_cast<unsigned(__cdecl*)(BYTE*,unsigned,BYTE*)>(0x4c7820)(unit,arms,preview);
    auto world=tacticalWeaponWorld();
    const bool selected=tacticalWeaponSelection.matches(world,reinterpret_cast<const unsigned*>(0x2215128),
        *reinterpret_cast<unsigned*>(0x2216664));
    return logh::TacticalAntiAirPreview::write(preview,selected && arms==2 ? tacticalAntiAirPower(world,unit) : 0) ? 1u : 0u;
}
static void synchronizeTacticalWeapon(BYTE* controller) {
    if(!tacticalWeaponSelection.antiAir())return;
    auto world=tacticalWeaponWorld();unsigned powers[32]={};
    const bool aiming=controller && logh::TacticalWeaponSelection::aiming(at<unsigned>(controller,4),
        *reinterpret_cast<BYTE*>(0x22166a9),*reinterpret_cast<unsigned short*>(0x77a8fc));
    if(aiming && tacticalWeaponSelection.matches(world,reinterpret_cast<const unsigned*>(0x2215128),
        *reinterpret_cast<unsigned*>(0x2216664)) && selectedTacticalAntiAir(world,powers))return;
    // Changed/invalid sources during AA aim must cancel, never fall through to a missile.
    if(aiming) {at<unsigned>(controller,4)=1;*reinterpret_cast<BYTE*>(0x22166a9)=0;}
    clearTacticalAntiAir();
}
// One existing per-frame UI CALL, no new timer/input/render loop. Native cancellation,
// source deselection, a different menu or a new world clears the bound request marker.
static void __fastcall updateTacticalWeapon(BYTE* controller,void*) {
    synchronizeTacticalWeapon(controller);
    reinterpret_cast<void(__thiscall*)(BYTE*)>(0x50d230)(controller);
    synchronizeTacticalWeapon(controller);
}
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
    if(tacticalWeaponSelection.antiAir()) {
        unsigned powers[32]={};
        const unsigned count=at<BYTE>(record,0xc);
        auto ids=reinterpret_cast<const unsigned*>(static_cast<BYTE*>(record)+0x10);
        // The native picker 004ef8e0/004f12b0 accepts enemy ships without a missile
        // profile lookup. Bind the exact picked source IDs and keep its target intact.
        if(at<BYTE>(record,0x90)!=2 || kind!=1 || world!=tacticalWeaponWorld() ||
            !tacticalWeaponSelection.matches(world,ids,count) ||
            !tacticalWeaponSelection.matches(world,reinterpret_cast<const unsigned*>(0x2215128),*reinterpret_cast<unsigned*>(0x2216664)) ||
            !selectedTacticalAntiAir(static_cast<BYTE*>(world),powers)) {
            clearTacticalAntiAir();return;
        }
        kind=static_cast<BYTE>(tacticalWeaponSelection.targetKind(at<BYTE>(record,0x90),kind));
    }
    // Restore the decoded record immediately. Native render/weapon arrays only see 0..2.
    reinterpret_cast<void(__thiscall*)(void*,BYTE,unsigned short,void*)>(0x4b78a0)(world,pending,ordinal,record);
    kind=original;
    clearTacticalAntiAir();
}
static bool installTacticalWeapon() {
    struct Hook {unsigned address;BYTE expected[5];const void* target;};
    const Hook hooks[]={
        {0x5113c5,{0xa1,0x64,0x66,0x21,0x02},reinterpret_cast<const void*>(&rememberTacticalWeapon)},
        {0x4b423d,{0xe8,0x5e,0x36,0,0},reinterpret_cast<const void*>(&requestTacticalWeapon)},
        {0x51137a,{0xe8,0xd1,0xe6,0xfd,0xff},reinterpret_cast<const void*>(&finishTacticalWeaponMenu)},
        {0x51140c,{0xe8,0x0f,0x64,0xfb,0xff},reinterpret_cast<const void*>(&previewTacticalWeapon)},
        {0x4b6dc2,{0xe8,0x69,0x64,0x05,0},reinterpret_cast<const void*>(&updateTacticalWeapon)}
    };
    for(const auto& hook:hooks)if(memcmp(reinterpret_cast<BYTE*>(hook.address),hook.expected,5))return false;
    for(const auto& hook:hooks) {
        auto address=reinterpret_cast<BYTE*>(hook.address);BYTE replacement[5]={0xe8};
        *reinterpret_cast<LONG*>(replacement+1)=static_cast<LONG>(reinterpret_cast<const BYTE*>(hook.target)-address-5);
        if(!patchBytes(address,hook.expected,replacement,5))return false;
    }
    return true;
}
