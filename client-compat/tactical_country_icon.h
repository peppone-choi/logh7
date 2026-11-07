// Tactical labels use actual country assets and paint, independently of ship models. Author: Peppone Choi.
#pragma once
static thread_local unsigned tacticalLabelUnit=0;
static BYTE tacticalLabelTrampoline[10],tacticalIconTrampoline[10];
using TacticalLabelDraw=void(__thiscall*)(void*,void*,void*);
static TacticalLabelDraw originalTacticalLabel=reinterpret_cast<TacticalLabelDraw>(&tacticalLabelTrampoline[0]);
using TacticalIconDraw=void(__thiscall*)(void*,const float*,char,float,float);
static TacticalIconDraw originalTacticalIcon=reinterpret_cast<TacticalIconDraw>(&tacticalIconTrampoline[0]);
static void __fastcall drawCountryTacticalLabel(void* manager,void*,void* unit,void* node) {
    const auto previous=tacticalLabelUnit;tacticalLabelUnit=node ? at<unsigned>(node,0x20) : 0;
    originalTacticalLabel(manager,unit,node);tacticalLabelUnit=previous;
}
static void __fastcall drawCountryTacticalIcon(void* manager,void*,const float* point,char affiliation,float size,float alpha) {
    const auto world=*reinterpret_cast<void**>(0x7ccffc);logh::TacticalSide side;
    const bool found=tacticalLabelUnit && runtimeTacticalSide(world,tacticalLabelUnit,side) && !side.kind;
    const unsigned configured=found ? runtimeCountryEmblem(side.owner) : 0;
    const unsigned emblem=configured ? configured : static_cast<unsigned>(affiliation<0 ? -affiliation : affiliation);
    const bool rebel=found ? side.insurgent!=0 : affiliation<0;
    const auto texture=at<BYTE*>(manager,0xa6c8);
    if(!point || (emblem!=2 && emblem!=3) || !texture ||
        !neutralizeCountryResource(at<BYTE*>(texture,8),"icon_set.tga",false)) {
        const char native=configured ? static_cast<char>(rebel ? -static_cast<int>(configured) : configured) : affiliation;
        originalTacticalIcon(manager,point,native,size,alpha);
        return;
    }
    if(!_finite(alpha) || alpha<0)alpha=0;if(alpha>1)alpha=1;
    const unsigned tile=emblem*2+static_cast<unsigned>(rebel);
    const auto palette=reinterpret_cast<const BYTE*>(0x771f68)+tile*3;
    const unsigned fallback=(palette[0]<<16)|(palette[1]<<8)|palette[2];
    const unsigned rgb=found ? countryPaintRgb(side.owner,0,fallback) : fallback;
    const float color[]={float((rgb>>16)&255)/255,float((rgb>>8)&255)/255,float(rgb&255)/255,alpha};
    const float rect[]={emblem==2 ? 0.f : 32.f,rebel ? 32.f : 0.f,32,32};
    reinterpret_cast<unsigned(__cdecl*)(void*,const float*,const float*,const float*,float,float)>(0x4ddc00)
        (texture,point,rect,color,size,size);
}
static bool installTacticalCountryIcons() {
    const unsigned entries[]={0x4f0260,0x4f0ec0};
    const BYTE expected[][5]={{0x83,0xec,0x48,0x53,0x55},{0x83,0xec,0x20,0x33,0xc0}};
    BYTE* trampolines[]={tacticalLabelTrampoline,tacticalIconTrampoline};
    const void* hooks[]={&drawCountryTacticalLabel,&drawCountryTacticalIcon};
    for(unsigned i=0;i<_countof(entries);++i)if(memcmp(reinterpret_cast<void*>(entries[i]),expected[i],5))return false;
    for(unsigned i=0;i<_countof(entries);++i) {
        auto entry=reinterpret_cast<BYTE*>(entries[i]);auto trampoline=trampolines[i];DWORD previous=0;
        if(!VirtualProtect(trampoline,10,PAGE_EXECUTE_READWRITE,&previous))return false;
        memcpy(trampoline,expected[i],5);trampoline[5]=0xe9;
        *reinterpret_cast<LONG*>(trampoline+6)=static_cast<LONG>(entry+5-trampoline-10);
        FlushInstructionCache(GetCurrentProcess(),trampoline,10);
        BYTE jump[]={0xe9,0,0,0,0};*reinterpret_cast<LONG*>(jump+1)=static_cast<LONG>(reinterpret_cast<const BYTE*>(hooks[i])-entry-5);
        if(!patchBytes(entry,expected[i],jump,5))return false;
    }
    return true;
}
