// Keep the live local character's unit state in sync with its authoritative 0325 reply.
// Author: 최병호.
#pragma once

static struct {void* world;void* element;BYTE enabled;} pausedFleetInput={};
static BYTE __fastcall availableChatInput(void* manager,void*,void* element) {
    auto world=*reinterpret_cast<BYTE**>(0x7ccffc);
    auto own=world ? at<BYTE*>(world,8) : nullptr;
    if (pausedFleetInput.world!=world) pausedFleetInput={};
    auto enable=reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x5024e0);
    if (own && !at<unsigned>(own,0x324) && *reinterpret_cast<BYTE*>(0x2216702)==2) {
        if (pausedFleetInput.element!=element) pausedFleetInput={world,element,at<BYTE>(element,0x15)};
        // Disable the editor itself, before the following frame can consume Enter and clear its draft.
        enable(manager,element,0);
        // This call site runs only for the active chat manager (005024a0). A disabled editor
        // loses focus, but its global IME submit latch still needs consuming in this context.
        *reinterpret_cast<BYTE*>(0x221463c)=0;
        // A submission already queued in this frame must not be sent later under a different audience.
        auto submitted=reinterpret_cast<void*(__thiscall*)(void*,void*)>(0x504bf0)(manager,element);
        reinterpret_cast<void(__cdecl*)(void*)>(0x4ea880)(submitted);
        return 0;
    }
    if (pausedFleetInput.element==element) {
        // A channel click can change the audience before this hook runs in the same frame.
        *reinterpret_cast<BYTE*>(0x221463c)=0;
        enable(manager,element,pausedFleetInput.enabled);
        pausedFleetInput={};
    }
    return reinterpret_cast<BYTE(__thiscall*)(void*,void*)>(0x5025c0)(manager,element);
}

static void __fastcall receivedUnitInformation(void* world,void*,int mode,void* character,const BYTE* unit) {
    auto apply=reinterpret_cast<void(__thiscall*)(void*,int,void*,const BYTE*)>(0x4c2c80);
    apply(world,mode,character,unit);
    // The original 0325 handler imports this unit into its query buffer (mode 1).
    // The chat panel reads the separate live character's unit fields at +0x318.
    // Mode 0 is the native importer for that local slot; never publish another ship there.
    auto own=world ? at<BYTE*>(world,8) : nullptr;
    if (mode!=1 || character || !unit || !own || own!=static_cast<BYTE*>(world)+0xc ||
        !at<BYTE>(world,0x2a58f8) || !at<BYTE>(own,0) || !at<unsigned>(own,0x48) ||
        at<unsigned>(own,0x48)!=logh::infoWord(unit,0)) return;
    apply(world,0,nullptr,unit);
}

static bool installUnitPresence() {
    auto target=reinterpret_cast<BYTE*>(0x4bb174);
    const BYTE expected[]={0xe8,0x07,0x7b,0,0};
    BYTE replacement[5]={0xe8};
    *reinterpret_cast<LONG*>(replacement+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&receivedUnitInformation)-target-5);
    auto chat=reinterpret_cast<BYTE*>(0x516db0);
    const BYTE chatExpected[]={0xe8,0x0b,0xb8,0xfe,0xff};
    BYTE chatReplacement[5]={0xe8};
    *reinterpret_cast<LONG*>(chatReplacement+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&availableChatInput)-chat-5);
    if (memcmp(target,expected,5) || memcmp(chat,chatExpected,5)) return false;
    return patchBytes(target,expected,replacement,5) && patchBytes(chat,chatExpected,chatReplacement,5);
}
