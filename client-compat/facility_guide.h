// Read-only descriptions; no arbitrary URLs, scripts or commands from the server. Author: Peppone Choi.
#pragma once
#include "facility_guide_data.h"
static SRWLOCK facilityGuideLock=SRWLOCK_INIT;
static struct FacilityGuideInbox {
    void* world;unsigned target,nonce,sequence;bool pending,sent,failed,nameOnly;
    ULONGLONG sentAt,pollAt;
    logh::FacilityGuideDraft draft;logh::FacilityGuide current;
    logh::FacilityNameCache names;
} facilityGuideInbox={};
static unsigned facilityGuideSelected=0,facilityGuideTab=0;
static void resetFacilityGuideConnection() {
    AcquireSRWLockExclusive(&facilityGuideLock);facilityGuideInbox={};ReleaseSRWLockExclusive(&facilityGuideLock);
}
static void resetFacilityGuide() {
    AcquireSRWLockExclusive(&facilityGuideLock);auto& q=facilityGuideInbox;
    q.target=0;q.pending=q.sent=q.failed=false;q.current={};q.pollAt=0;++q.sequence;
    ReleaseSRWLockExclusive(&facilityGuideLock);
    facilityGuideSelected=facilityGuideTab=0;
}
static bool facilityGuideView(unsigned id,logh::FacilityGuide& value,bool& loading,bool& failed,unsigned& sequence) {
    AcquireSRWLockShared(&facilityGuideLock);const auto& q=facilityGuideInbox;
    loading=q.target==id && q.pending;failed=q.target==id && q.failed;sequence=q.sequence;
    bool found=q.current.id==id;if(found)value=q.current;
    ReleaseSRWLockShared(&facilityGuideLock);return found;
}
static bool facilityGuideName(unsigned id,wchar_t (&name)[64]) {
    bool found=false;AcquireSRWLockShared(&facilityGuideLock);
    if(const auto row=facilityGuideInbox.names.find(id)){wcscpy_s(name,row->text);found=name[0]!=0;}
    ReleaseSRWLockShared(&facilityGuideLock);return found;
}
static bool facilityGuideNeedsRefresh(unsigned id) {
    bool needed=true;auto now=GetTickCount64();AcquireSRWLockShared(&facilityGuideLock);
    if(const auto row=facilityGuideInbox.names.find(id))needed=now-row->loadedAt>=10000;
    ReleaseSRWLockShared(&facilityGuideLock);return needed;
}
static bool receiveFacilityGuide(const BYTE* record) {
    if(record[8]!=6)return false;
    AcquireSRWLockExclusive(&facilityGuideLock);auto& q=facilityGuideInbox;
    if(q.pending && q.sent) {
        int result=q.draft.accept(record,24+10*36);
        if(result==-1) {
            q.nonce=q.nonce%4095+1;q.draft.begin(q.target,q.nonce);q.sent=false;
        } else if(result>0) {
            q.sent=false;
            if(result==2 || (q.nameOnly && q.draft.next==1)) {
                if((q.current.id!=q.target || q.draft.value.revision>=q.current.revision) &&
                    q.names.publish(q.draft.value,GetTickCount64())) {
                    if(!q.nameOnly)q.current=q.draft.value;
                    ++q.sequence;
                }
                q.pending=false;q.failed=false;q.pollAt=GetTickCount64()+10000;
            }
        }
    }
    ReleaseSRWLockExclusive(&facilityGuideLock);return true;
}
static void pollFacilityGuide(void* world,unsigned target,bool nameOnly=false) {
    auto own=world ? at<BYTE*>(world,8) : nullptr;
    if(!target || !own || !at<BYTE>(own,0) || !at<BYTE>(world,0x2a58f8))return;
    unsigned query[3]={};auto now=GetTickCount64();
    AcquireSRWLockExclusive(&facilityGuideLock);auto& q=facilityGuideInbox;
    if(q.world!=world){q={};q.world=world;}
    if(q.target!=target || q.nameOnly!=nameOnly) {q.target=target;q.nameOnly=nameOnly;q.pending=q.sent=q.failed=false;q.pollAt=0;++q.sequence;}
    if(q.pending && q.sent && now-q.sentAt>=5000) {q.pending=q.sent=false;q.failed=true;q.pollAt=now+5000;++q.sequence;}
    if(!q.pending && now>=q.pollAt) {q.nonce=q.nonce%4095+1;q.draft.begin(target,q.nonce);q.pending=true;q.sent=false;}
    if(q.pending && !q.sent) {
        query[0]=target;query[1]=q.nonce*16+q.draft.next;query[2]=6;q.sent=true;q.sentAt=now;
    }
    ReleaseSRWLockExclusive(&facilityGuideLock);
    if(query[0])reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(world,0,0x10,query);
}
