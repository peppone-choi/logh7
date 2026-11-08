// Registered portraits in the native same-room background slots. Author: Peppone Choi.
#pragma once
#include "spot_presence.h"

static constexpr unsigned spotPortraitMode=22;
static SRWLOCK spotPortraitLock=SRWLOCK_INIT;
static struct SpotPortraitSlot {
    BYTE* peer=nullptr;
    void* widget=nullptr;
    void* quads[4]={};
    unsigned id=0,serial=0;
    ULONGLONG seenAt=0,dueAt=0;
    bool present=false;
    BYTE pixels[4096]={};
    UploadedCountryTexture texture;
    unsigned loaded=0;
} spotPortraitSlots[23];
static struct SpotPortraitInbox {
    void* world=nullptr;
    unsigned own=0,spot=0,owner=0,nonce=0,pendingId=0,slot=0,cursor=0;
    ULONGLONG deadline=0,sendAt=0;
    logh::CountryImageDraft draft;
} spotPortraitInbox;

static BYTE* spotPortraitOwn(void* world) {
    auto own=world ? at<BYTE*>(world,8) : nullptr;
    return own && at<BYTE>(own,0) && at<BYTE>(world,0x2a58f8) &&
        !at<BYTE>(world,0x126718) && at<unsigned>(own,0x40) ? own : nullptr;
}

static bool validSpotPortraitPeer(BYTE* own,BYTE* peer,unsigned id) {
    return own && peer && id && id<10000 && at<BYTE>(peer,0) && at<BYTE>(peer,0xa1) && at<unsigned>(peer,0x24)==id &&
        logh::sameSpotOccupant(at<unsigned>(own,0x24),at<unsigned>(own,0x40),at<unsigned>(own,0x44),
            id,at<unsigned>(peer,0x40),at<unsigned>(peer,0x44));
}

// Caller holds spotPortraitLock. Texture storage survives room changes for the renderer to reuse.
static void refreshSpotPortraitRoom(void* world,BYTE* own) {
    auto& inbox=spotPortraitInbox;
    const unsigned id=own ? at<unsigned>(own,0x24) : 0;
    const unsigned spot=own ? at<unsigned>(own,0x40) : 0;
    const unsigned owner=own ? at<unsigned>(own,0x44) : 0;
    if(inbox.world==world && inbox.own==id && inbox.spot==spot && inbox.owner==owner)return;
    inbox.world=world;inbox.own=id;inbox.spot=spot;inbox.owner=owner;inbox.pendingId=0;
    for(auto& slot:spotPortraitSlots){slot.id=0;slot.present=false;slot.widget=nullptr;}
}

static BYTE __fastcall loadSpotPortraitSlot(void* manager,BYTE* profile,void* widget,int face,int age,unsigned index) {
    const BYTE result=reinterpret_cast<BYTE(__thiscall*)(void*,void*,int,int,unsigned)>(0x5933e0)
        (manager,widget,face,age,index);
    auto world=*reinterpret_cast<void**>(0x7ccffc);
    auto own=spotPortraitOwn(world);
    if(!own || !widget || index>=4)return result;
    // The native call already carries the peer's profile at row+0xa4 in EDX.
    const auto start=reinterpret_cast<ULONG_PTR>(world)+0xc+0xa4;
    const auto address=reinterpret_cast<ULONG_PTR>(profile);
    const auto peer=address>=start && address-start<600*0x370 && (address-start)%0x370==0 ? profile-0xa4 : nullptr;
    const unsigned id=peer ? at<unsigned>(peer,0x24) : 0;
    AcquireSRWLockExclusive(&spotPortraitLock);
    refreshSpotPortraitRoom(world,own);
    SpotPortraitSlot* target=nullptr;
    for(auto& slot:spotPortraitSlots) {
        if(slot.widget==widget){target=&slot;break;}
        if(!target && !validSpotPortraitPeer(own,slot.peer,slot.id))target=&slot;
    }
    if(target) {
        if(target->widget!=widget || target->id!=id || target->peer!=peer) {
            target->widget=widget;target->peer=peer;target->id=id;target->present=false;
            target->seenAt=target->dueAt=0;memset(target->quads,0,sizeof(target->quads));
        }
        target->quads[index]=result && validSpotPortraitPeer(own,peer,id) ? at<void*>(widget,0xcf4+index*4) : nullptr;
    }
    ReleaseSRWLockExclusive(&spotPortraitLock);
    return result;
}

static bool receiveSpotPortrait(const BYTE* record) {
    if(record[8]!=spotPortraitMode)return false;
    AcquireSRWLockExclusive(&spotPortraitLock);
    auto& inbox=spotPortraitInbox;
    if(inbox.pendingId && inbox.draft.accept(record,24+10*36)==2) {
        auto& slot=spotPortraitSlots[inbox.slot];
        if(slot.id==inbox.pendingId) {
            const auto pixels=inbox.draft.value.pixels(false);
            if(pixels && (!slot.present || memcmp(slot.pixels,pixels,sizeof(slot.pixels)))) {
                memcpy(slot.pixels,pixels,sizeof(slot.pixels));++slot.serial;
            }
            slot.present=pixels!=nullptr;
        }
        inbox.pendingId=0;
    }
    ReleaseSRWLockExclusive(&spotPortraitLock);
    return true;
}

static void pollSpotPortraits(void* world) {
    auto own=spotPortraitOwn(world);
    AcquireSRWLockShared(&countryPaintLock);
    const bool supported=countryPaintInbox.world==world && countryPaintInbox.spotPortraitSupported;
    ReleaseSRWLockShared(&countryPaintLock);
    const auto now=GetTickCount64();
    unsigned query[3]={};
    AcquireSRWLockExclusive(&spotPortraitLock);
    auto& inbox=spotPortraitInbox;
    refreshSpotPortraitRoom(world,own);
    if(inbox.pendingId && now>=inbox.deadline)inbox.pendingId=0;
    if(supported && own && !inbox.pendingId && now>=inbox.sendAt)for(unsigned i=0;i<_countof(spotPortraitSlots);++i) {
        const unsigned index=inbox.cursor++%_countof(spotPortraitSlots);
        auto& slot=spotPortraitSlots[index];
        if(!slot.seenAt || now-slot.seenAt>1500 || now<slot.dueAt || !validSpotPortraitPeer(own,slot.peer,slot.id))continue;
        inbox.nonce=inbox.nonce%65535+1;inbox.pendingId=slot.id;inbox.slot=index;
        inbox.deadline=now+5000;inbox.sendAt=now+1100;slot.dueAt=now+15000;
        inbox.draft.begin(slot.id,inbox.nonce,0,spotPortraitMode);
        query[0]=slot.id;query[1]=inbox.nonce;query[2]=spotPortraitMode;
        break;
    }
    ReleaseSRWLockExclusive(&spotPortraitLock);
    if(query[2])reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(world,0,0x10,query);
}

static UploadedCountryTexture* spotPortraitTexture(void* world,void* quad) {
    auto own=spotPortraitOwn(world);
    if(!own)return nullptr;
    auto device=*reinterpret_cast<void**>(0x2229400);
    if(!device)return nullptr;
    UploadedCountryTexture* result=nullptr;
    AcquireSRWLockExclusive(&spotPortraitLock);
    refreshSpotPortraitRoom(world,own);
    for(auto& slot:spotPortraitSlots) {
        bool matches=false;
        for(auto native:slot.quads)if(native==quad){matches=true;break;}
        if(!matches || !validSpotPortraitPeer(own,slot.peer,slot.id) || !slot.widget)continue;
        slot.seenAt=GetTickCount64();
        if(slot.present) {
            if(slot.serial!=slot.loaded || slot.texture.device!=device) {
                if(!uploadPortraitTexture(slot.texture,slot.pixels,device))break;
                slot.loaded=slot.serial;
            }
            slot.texture.quad[0x10]=at<BYTE>(quad,0x10);
            slot.texture.quad[0x12]=at<BYTE>(quad,0x12);
            result=&slot.texture;
        }
        break;
    }
    ReleaseSRWLockExclusive(&spotPortraitLock);
    return result;
}
