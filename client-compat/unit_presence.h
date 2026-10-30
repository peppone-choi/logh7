// Keep live local and peer unit state in sync with authoritative 0325 replies.
// Author: Peppone Choi.
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

// Unit replies can precede the enter-grid character import. Keep a short,
// bounded inbox for matching live peer slots. A grid transition can import the
// same character twice, so keep recent replies through both native imports.
static struct {void* world;struct Row {unsigned id;ULONGLONG until;BYTE unit[0x58];} rows[600];} pendingPeerUnits={};
static bool publishPeerUnit(void* world,unsigned id,const BYTE* unit) {
    auto own=world ? at<BYTE*>(world,8) : nullptr;
    if(!own || own!=static_cast<BYTE*>(world)+0xc || !at<BYTE>(own,0) ||
        !id || at<unsigned>(own,0x48)==id)return false;
    BYTE* peer=nullptr;
    for(unsigned i=1;i<600;++i) {
        auto row=static_cast<BYTE*>(world)+0xc+i*0x370;
        if(at<BYTE>(row,0) && at<unsigned>(row,0x48)==id) {
            if(peer)return false;
            peer=row;
        }
    }
    // Native mode setters affect the local HUD; copy only the peer's unit.
    if(peer)memcpy(peer+0x318,unit,0x58);
    return peer!=nullptr;
}
static void __fastcall receivedUnitInformation(void* world,void*,int mode,void* character,const BYTE* unit) {
    auto apply=reinterpret_cast<void(__thiscall*)(void*,int,void*,const BYTE*)>(0x4c2c80);
    apply(world,mode,character,unit);
    if(mode!=1 || character || !unit || !world)return;
    unsigned id=logh::infoWord(unit,0);
    auto own=at<BYTE*>(world,8);
    if(own && own==static_cast<BYTE*>(world)+0xc && at<BYTE>(own,0) && at<unsigned>(own,0x48)==id) {
        if(at<BYTE>(world,0x2a58f8))apply(world,0,nullptr,unit);
        return;
    }
    publishPeerUnit(world,id,unit);
    if(pendingPeerUnits.world!=world) {pendingPeerUnits={};pendingPeerUnits.world=world;}
    auto now=GetTickCount64();
    decltype(pendingPeerUnits)::Row* selected=nullptr;
    for(auto& row:pendingPeerUnits.rows)if(row.id==id) {selected=&row;break;}
    if(!selected)for(auto& row:pendingPeerUnits.rows)if(!row.id || row.until<now) {selected=&row;break;}
    if(selected) {selected->id=id;selected->until=now+5000;memcpy(selected->unit,unit,0x58);}
}
static void __fastcall importedPeerCharacter(void* world,void*,int mode,void* character,const BYTE* unit) {
    reinterpret_cast<void(__thiscall*)(void*,int,void*,const BYTE*)>(0x4c2c80)(world,mode,character,unit);
    if(mode!=2 || !character || pendingPeerUnits.world!=world)return;
    unsigned id=logh::infoWord(static_cast<const BYTE*>(character),0x24);
    auto now=GetTickCount64();
    for(auto& row:pendingPeerUnits.rows)if(row.id==id && row.until>=now) {
        publishPeerUnit(world,id,row.unit);
        return;
    }
}

static bool installUnitPresence() {
    auto target=reinterpret_cast<BYTE*>(0x4bb174);
    const BYTE expected[]={0xe8,0x07,0x7b,0,0};
    BYTE replacement[5]={0xe8};
    *reinterpret_cast<LONG*>(replacement+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&receivedUnitInformation)-target-5);
    auto imported=reinterpret_cast<BYTE*>(0x4c2bab);
    const BYTE importExpected[]={0xe8,0xd0,0,0,0};
    BYTE importReplacement[5]={0xe8};
    *reinterpret_cast<LONG*>(importReplacement+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&importedPeerCharacter)-imported-5);
    auto chat=reinterpret_cast<BYTE*>(0x516db0);
    const BYTE chatExpected[]={0xe8,0x0b,0xb8,0xfe,0xff};
    BYTE chatReplacement[5]={0xe8};
    *reinterpret_cast<LONG*>(chatReplacement+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&availableChatInput)-chat-5);
    if (memcmp(target,expected,5) || memcmp(imported,importExpected,5) || memcmp(chat,chatExpected,5)) return false;
    return patchBytes(target,expected,replacement,5) && patchBytes(imported,importExpected,importReplacement,5) &&
        patchBytes(chat,chatExpected,chatReplacement,5);
}
