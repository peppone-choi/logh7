// Reconstructed ground battle panel (design-ground-battle-window.md). The CD has the unsent 0416~0418 troop
// orders and a missing rikusen/window.tga, but no battle screen. In tactics this polls the battlefield's bases
// with revival query 032e mode 3 and draws both sides' landed troops, strength and ordered targets. Author: Peppone Choi.
#pragma once
#include "unicode_text.h"
#include "planet_info_data.h"
static void pickerName(unsigned id, wchar_t (&name)[32]);
static SRWLOCK groundPanelLock=SRWLOCK_INIT;
struct GroundPanelRow {unsigned id,kind,strength,target;};
static struct GroundPanel {
    BYTE* world; unsigned grid,base,count,cursor,activeBase;
    GroundPanelRow rows[70];
    ULONGLONG pollAt,activeUntil;
} groundPanel={};
static struct GroundPanelInbox {
    bool pending,marked,recordPending; unsigned base,ready,count; GroundPanelRow rows[70]; ULONGLONG sentAt;
} groundPanelInbox={};
static bool groundPanelEmpire(unsigned kind) {return kind<=3 || kind==8 || kind==9;}
// 032f decoder side: the mode-3 echo (record +8) identifies the next 0331 as this panel's roster.
static bool groundPanelMarked(const BYTE* party) {
    if (party[8]!=3) return false;
    AcquireSRWLockExclusive(&groundPanelLock);
    auto& q=groundPanelInbox;
    if (q.pending && !logh::infoWord(party,0) && logh::infoWord(party,4)==q.base) q.marked=true;
    ReleaseSRWLockExclusive(&groundPanelLock);
    return true;
}
// 0331 decoder side: native 88-byte rows, base +64, strength in damaged +70, ordered target in supplies +76.
static bool groundPanelRoster(const BYTE* record,unsigned result) {
    AcquireSRWLockExclusive(&groundPanelLock);
    auto& q=groundPanelInbox;bool mine=q.pending && q.marked;
    if (mine) {
        q.pending=q.marked=false;q.recordPending=true;q.count=0;
        unsigned n=result ? 0 : record[0];
        for (unsigned i=0;i<n && i<70;++i) {
            auto row=record+4+i*88;unsigned kind=logh::infoShort(row,4);
            if (kind<1000 || kind>1015 || logh::infoWord(row,64)!=q.base) continue;
            q.rows[q.count++]={logh::infoWord(row,0),kind-1000,logh::infoShort(row,70),logh::infoWord(row,76)};
        }
        q.ready=q.base;
    }
    ReleaseSRWLockExclusive(&groundPanelLock);
    return mine;
}
// Dispatch side: keep both extension packets out of the native unit caches.
static bool groundPanelConsumes(unsigned opcode,const BYTE* record) {
    if ((opcode&65535)==0x032f && record[8]==3) return true;
    if ((opcode&65535)!=0x0331) return false;
    AcquireSRWLockExclusive(&groundPanelLock);
    bool consume=groundPanelInbox.recordPending;groundPanelInbox.recordPending=false;
    ReleaseSRWLockExclusive(&groundPanelLock);
    return consume;
}
static void groundPanelName(unsigned kind,wchar_t (&name)[32]) {
    auto original=kind<16 ? reinterpret_cast<const char*(__thiscall*)(void*,int,int)>(0x522010)
        (reinterpret_cast<void*>(0x2217400),86,kind) : nullptr;
    logh::catalogueText(original,name);
    if (!name[0]) _snwprintf_s(name,_countof(name),_TRUNCATE,L"육전대 %u",kind);
}
static void updateGroundPanel() {
    auto world=*reinterpret_cast<BYTE**>(0x7ccffc);auto own=world ? at<BYTE*>(world,8) : nullptr;
    auto& s=groundPanel;
    if (!world || !own || !own[0] || !at<BYTE>(world,0x126718)) {s={};return;}
    unsigned grid=at<unsigned>(world,0x2b6a70);
    if (world!=s.world || grid!=s.grid) {s={};s.world=world;s.grid=grid;}
    ULONGLONG now=GetTickCount64();unsigned query[3]={0,0,3};bool send=false;
    AcquireSRWLockExclusive(&groundPanelLock);
    auto& q=groundPanelInbox;
    if (q.ready) {
        bool fight=false,empire=false,alliance=false;
        for (unsigned i=0;i<q.count;++i) (groundPanelEmpire(q.rows[i].kind) ? empire : alliance)=true;
        fight=empire && alliance;
        if (fight || q.ready==s.activeBase) {
            s.activeBase=fight ? q.ready : 0;s.count=q.count;memcpy(s.rows,q.rows,sizeof(s.rows));
            s.activeUntil=fight ? now+8000 : 0;
        }
        q.ready=0;
    }
    if (q.pending && now-q.sentAt>=6000) q.pending=q.marked=false;
    if (!q.pending && !q.recordPending && now>=s.pollAt) {
        // Follow the running battle; otherwise visit this battlefield's bases one per poll.
        unsigned base=s.activeBase;
        for (unsigned step=0;!base && step<350;++step) {
            s.cursor=(s.cursor+1)%350;unsigned id=at<unsigned>(world,0x2eb288+s.cursor*4);
            if (id && id<=65535 && reinterpret_cast<void*(__cdecl*)(void*,unsigned,BYTE,BYTE,BYTE,BYTE)>(0x4c7cd0)
                (world+0x126718,id,0,255,255,255)) base=id;
        }
        if (base) {q.pending=true;q.marked=false;q.base=base;q.sentAt=now;query[1]=base;send=true;}
        s.pollAt=now+(s.activeBase ? 3000 : 1500);
    }
    ReleaseSRWLockExclusive(&groundPanelLock);
    if (send) reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(world,0,0x10,query);
}
static bool groundWindowShown();
static void drawGroundPanel(BYTE* display) {
    auto& s=groundPanel;
    if (!s.activeBase || GetTickCount64()>=s.activeUntil || groundWindowShown()) return;
    auto own=s.world ? at<BYTE*>(s.world,8) : nullptr;
    if (!own) return;
    bool ownEmpire=own[0x28]==2;
    auto draw=reinterpret_cast<void(__cdecl*)(void*,void*,const wchar_t*,int,int,unsigned,int)>(0x4e8af0);
    reinterpret_cast<void(__thiscall*)(void*,float)>(0x4b0780)(reinterpret_cast<void*>(0xbffa48),1.0f);
    wchar_t base[32]={},line[128];pickerName(s.activeBase,base);
    if (!base[0]) _snwprintf_s(base,_countof(base),_TRUNCATE,L"거점 %u",s.activeBase);
    unsigned friends=0,foes=0,friendPower=0,foePower=0;
    for (unsigned i=0;i<s.count;++i) {
        bool mine=groundPanelEmpire(s.rows[i].kind)==ownEmpire;
        (mine ? friends : foes)++;(mine ? friendPower : foePower)+=s.rows[i].strength;
    }
    int y=260;
    _snwprintf_s(line,_countof(line),_TRUNCATE,L"[지상전] %ls  아군 %u (전력 %u) · 적군 %u (전력 %u)",base,friends,friendPower,foes,foePower);
    draw(at<void*>(display,0x2a418),reinterpret_cast<void*>(0xbffa48),line,16,y,0xffffe080,6);
    for (unsigned side=0;side<2;++side) {
        unsigned shown=0;y+=4;
        for (unsigned i=0;i<s.count && shown<8;++i) {
            auto& r=s.rows[i];
            if ((groundPanelEmpire(r.kind)==ownEmpire)!=(side==0)) continue;
            wchar_t name[32],bar[11];groundPanelName(r.kind,name);
            unsigned filled=(r.strength*10+999)/1000;
            for (unsigned b=0;b<10;++b) bar[b]=b<filled ? L'■' : L'□';bar[10]=0;
            if (r.target) _snwprintf_s(line,_countof(line),_TRUNCATE,L"%ls #%u %ls %4u → #%u",name,r.id,bar,r.strength,r.target);
            else _snwprintf_s(line,_countof(line),_TRUNCATE,L"%ls #%u %ls %4u",name,r.id,bar,r.strength);
            y+=16;draw(at<void*>(display,0x2a418),reinterpret_cast<void*>(0xbffa48),line,24,y,side==0 ? 0xff80c0ff : 0xffff8080,6);
            ++shown;
        }
    }
}
