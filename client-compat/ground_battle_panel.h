// Reconstructed ground battle panel (design-ground-battle-window.md). The CD has the unsent 0416~0418 troop
// orders and a missing rikusen/window.tga, but no battle screen. In tactics this polls the battlefield's bases
// with revival query 032e mode 3 and draws both sides' landed troops, strength and ordered targets. Author: Peppone Choi.
#pragma once
#include "unicode_text.h"
#include "planet_info_data.h"
#include "ground_panel_layout.h"
static void pickerName(unsigned id, wchar_t (&name)[32]);
static SRWLOCK groundPanelLock=SRWLOCK_INIT;
struct GroundPanelRow {unsigned id,kind,strength,target,morale,commander,relation;bool reserve;};
struct GroundPanelCommander {unsigned id;wchar_t name[13];};
static struct GroundPanel {
    BYTE* world; unsigned grid,count,cursor,activeBase,searchSlots;bool partial,searching,searchFailed;
    GroundPanelRow rows[70];
    GroundPanelCommander commanders[10];unsigned commanderCount;
    ULONGLONG pollAt,refreshAt,activeUntil;
} groundPanel={};
static struct GroundPanelInbox {
    BYTE* world; unsigned grid;
    bool pending,marked,recordPending; unsigned base,ready,count,flags; GroundPanelRow rows[70]; ULONGLONG sentAt;
    GroundPanelCommander commanders[10];unsigned commanderCount;
} groundPanelInbox={};
static bool groundPanelEmpire(unsigned kind) {return kind<=3 || kind==8 || kind==9;}
static bool groundPanelFriendly(const GroundPanelRow& row,bool viewerEmpire) {
    return logh::groundPanelFriendly(row.relation,groundPanelEmpire(row.kind),viewerEmpire);
}
// 032f decoder side: the mode-3 echo (record +8) identifies the next 0331 as this panel's roster.
static bool groundPanelMarked(const BYTE* party) {
    if (party[8]!=3) return false;
    AcquireSRWLockExclusive(&groundPanelLock);
    auto& q=groundPanelInbox;
    if (q.pending && !logh::infoWord(party,0) && logh::infoWord(party,4)==q.base) {
        q.marked=true;q.commanderCount=0;q.flags=logh::infoWord(party,16);
        for(unsigned i=0;i<party[20] && i<10;++i) {
            const auto member=party+24+i*36;const unsigned length=member[6];
            if(!logh::infoWord(member,0) || length<1 || length>13 || logh::infoShort(member,8+(length-1)*2))continue;
            auto& commander=q.commanders[q.commanderCount++];commander={};commander.id=logh::infoWord(member,0);
            memcpy(commander.name,member+8,(length-1)*sizeof(wchar_t));
        }
    }
    ReleaseSRWLockExclusive(&groundPanelLock);
    return true;
}
// 0331 decoder side: native 88-byte rows, base +64, morale +68, strength in damaged +70, ordered target in supplies +76.
static bool groundPanelRoster(const BYTE* record,unsigned result) {
    AcquireSRWLockExclusive(&groundPanelLock);
    auto& q=groundPanelInbox;bool mine=q.pending && q.marked;
    if (mine) {
        q.pending=q.marked=false;q.recordPending=true;q.count=0;
        unsigned n=result ? 0 : record[0];
        for (unsigned i=0;i<n && i<70;++i) {
            auto row=record+4+i*88;unsigned kind=logh::infoShort(row,4);
            if (kind<1000 || kind>1015 || logh::infoWord(row,64)!=q.base) continue;
            const unsigned relation=(q.flags&1) ? row[69] : 0;
            if((q.flags&1) && relation!=1 && relation!=2)continue;
            q.rows[q.count++]={logh::infoWord(row,0),kind-1000,logh::infoShort(row,70),logh::infoWord(row,76),row[68],logh::infoWord(row,80),relation,logh::infoShort(row,72)==1};
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
static void groundCommanderName(unsigned id,wchar_t (&name)[32]) {
    if(!id){wcscpy_s(name,L"미임명 · 자동 교전");return;}
    for(unsigned i=0;i<groundPanel.commanderCount;++i)if(groundPanel.commanders[i].id==id) {
        wcscpy_s(name,groundPanel.commanders[i].name);return;
    }
    _snwprintf_s(name,_countof(name),_TRUNCATE,L"지휘관 #%u",id);
}
static void searchNextGroundBattle() {
    auto& s=groundPanel;
    s.searching=true;s.searchFailed=false;s.searchSlots=350;s.pollAt=0;
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
    if (q.ready && q.world==world && q.grid==grid) {
        bool fight=(q.flags&2) && q.count;
        if(!(q.flags&1)) {
            bool empire=false,alliance=false;
            for (unsigned i=0;i<q.count;++i) (groundPanelEmpire(q.rows[i].kind) ? empire : alliance)=true;
            fight=empire && alliance;
        }
        if (fight || q.ready==s.activeBase) {
            if (fight && q.ready!=s.activeBase) {
                s.searching=false;s.searchFailed=false;s.refreshAt=now+3000;
            }
            s.activeBase=fight ? q.ready : 0;s.count=q.count;memcpy(s.rows,q.rows,sizeof(s.rows));
            s.partial=(q.flags&1) && (q.flags&4);
            s.commanderCount=q.commanderCount;memcpy(s.commanders,q.commanders,sizeof(s.commanders));
            s.activeUntil=fight ? now+8000 : 0;
        }
    }
    q.ready=0;
    if (q.pending && now-q.sentAt>=6000) q.pending=q.marked=false;
    if (!q.pending && !q.recordPending && now>=s.pollAt) {
        // Keep the displayed roster fresh while checking other bases on demand.
        unsigned base=s.activeBase && (!s.searching || now>=s.refreshAt) ? s.activeBase : 0;
        const bool scanning=!base;
        unsigned slots=s.searching ? s.searchSlots : 350;
        if (!base) while (slots && !base) {
            --slots;
            s.cursor=(s.cursor+1)%350;unsigned id=at<unsigned>(world,0x2eb288+s.cursor*4);
            if (id && id!=s.activeBase && id<=65535 && reinterpret_cast<void*(__cdecl*)(void*,unsigned,BYTE,BYTE,BYTE,BYTE)>(0x4c7cd0)
                (world+0x126718,id,0,255,255,255)) base=id;
        }
        if (s.searching && scanning) {
            s.searchSlots=slots;
            if (!base) {s.searching=false;s.searchFailed=true;}
        }
        if (base) {
            if (base==s.activeBase) s.refreshAt=now+3000;
            q.world=world;q.grid=grid;q.pending=true;q.marked=false;q.base=base;q.sentAt=now;query[1]=base;send=true;
        }
        s.pollAt=now+(s.searching ? 250 : s.activeBase ? 3000 : 1500);
    }
    ReleaseSRWLockExclusive(&groundPanelLock);
    if (send) reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(world,0,0x10,query);
}
static bool groundWindowShown();
// Reuse one opaque white texel in the CD radar atlas, through its existing sprite renderer.
// No new device, texture allocation or input surface is introduced by this read-only HUD.
static void groundPanelRect(void* texture,float depth,int x,int y,int width,int height,unsigned color) {
    if (!texture || width<=0 || height<=0) return;
    const float point[]={static_cast<float>(x),static_cast<float>(y),depth},uv[]={494,377,1,1};
    const float rgba[]={((color>>16)&255)/255.f,((color>>8)&255)/255.f,(color&255)/255.f,(color>>24)/255.f};
    reinterpret_cast<unsigned(__cdecl*)(void*,const float*,const float*,const float*,float,float)>(0x4ddc00)
        (texture,point,uv,rgba,static_cast<float>(width),static_cast<float>(height));
}
static void drawGroundPanel(BYTE* display,void* texture,float depth) {
    auto& s=groundPanel;
    if (!s.activeBase || GetTickCount64()>=s.activeUntil || groundWindowShown()) return;
    auto own=s.world ? at<BYTE*>(s.world,8) : nullptr;
    if (!own || !texture || at<int>(display,0x2a604)<743 || at<int>(display,0x2a608)<600) return;
    bool ownEmpire=own[0x28]==2;
    auto draw=reinterpret_cast<void(__cdecl*)(void*,void*,const wchar_t*,int,int,unsigned,int)>(0x4e8af0);
    reinterpret_cast<void(__thiscall*)(void*,float)>(0x4b0780)(reinterpret_cast<void*>(0xbffa48),1.0f);
    wchar_t base[32]={},line[128];pickerName(s.activeBase,base);
    if (!base[0]) _snwprintf_s(base,_countof(base),_TRUNCATE,L"거점 %u",s.activeBase);
    unsigned friends=0,foes=0,friendPower=0,foePower=0,friendReserve=0,foeReserve=0;
    for (unsigned i=0;i<s.count;++i) {
        bool mine=groundPanelFriendly(s.rows[i],ownEmpire);
        if (s.rows[i].reserve) {(mine ? friendReserve : foeReserve)++;continue;}
        (mine ? friends : foes)++;(mine ? friendPower : foePower)+=s.rows[i].strength;
    }
    const int left=logh::groundPanelLeft,top=logh::groundPanelTop,width=logh::groundPanelWidth,height=logh::groundPanelHeight;
    auto rect=[&](int x,int y,int w,int h,unsigned color) {groundPanelRect(texture,depth,x,y,w,h,color);};
    auto text=[&](const wchar_t* value,int x,int y,unsigned color=0xffdce8f4) {
        draw(at<void*>(display,0x2a418),reinterpret_cast<void*>(0xbffa48),value,x,y,color,6);
    };
    rect(left,top,width,height,0xff081522);
    rect(left,top,width,30,0xff12324e);
    rect(left,top,width,2,0xff4286b3);rect(left,top+height-2,width,2,0xff4286b3);
    rect(left,top,2,height,0xff4286b3);rect(left+width-2,top,2,height,0xff4286b3);
    rect(left+width/2,top+62,1,224,0xff294456);
    text(L"지상전 현황",left+12,top+8,0xffffffff);
    text(L"자동 교전 중",left+width-108,top+8,0xffffd382);
    _snwprintf_s(line,_countof(line),_TRUNCATE,L"전장 · %ls",base);text(line,left+12,top+38);
    for (unsigned side=0;side<2;++side) {
        const int x=left+12+static_cast<int>(side)*218;
        const unsigned color=side==0 ? 0xff80c0ff : 0xffff9292;
        rect(x,top+64,4,32,color);
        _snwprintf_s(line,_countof(line),_TRUNCATE,L"%ls · 교전 %u / 예비 %u",side==0 ? L"아군" : L"적군",side==0 ? friends : foes,side==0 ? friendReserve : foeReserve);
        text(line,x+10,top+64,color);
        _snwprintf_s(line,_countof(line),_TRUNCATE,L"남은 전력 합계 %u",side==0 ? friendPower : foePower);text(line,x+10,top+81);
        unsigned shown=0;
        for (unsigned i=0;i<s.count && shown<logh::groundPanelVisibleRows;++i) {
            auto& r=s.rows[i];
            if (groundPanelFriendly(r,ownEmpire)!=(side==0) || r.reserve) continue;
            const int y=top+102+static_cast<int>(shown)*34;
            wchar_t name[32];groundPanelName(r.kind,name);
            _snwprintf_s(line,_countof(line),_TRUNCATE,L"%ls #%u",name,r.id);text(line,x,y,color);
            rect(x,y+19,70,7,0xff293c4d);
            rect(x,y+19,static_cast<int>(logh::groundMeter(r.strength,1000,70)),7,color);
            _snwprintf_s(line,_countof(line),_TRUNCATE,L"%u%% · 사기 %u%ls",logh::groundMeter(r.strength,1000,100),r.morale,r.morale<=20 ? L" 혼란" : L"");
            text(line,x+78,y+16,r.morale<=20 ? 0xffffd382 : 0xffdce8f4);
            ++shown;
        }
    }
    unsigned first=0,commanders[70]={},commanderCount=0;
    for(unsigned i=0;i<s.count;++i)if(groundPanelFriendly(s.rows[i],ownEmpire) && s.rows[i].commander) {
        unsigned id=s.rows[i].commander;bool found=false;
        for(unsigned j=0;j<commanderCount;++j)if(commanders[j]==id)found=true;
        if(!found){commanders[commanderCount++]=id;if(!first)first=id;}
    }
    wchar_t commander[32];groundCommanderName(first,commander);
    if(commanderCount>1)_snwprintf_s(line,_countof(line),_TRUNCATE,L"아군 지휘 · %ls 외 %u명",commander,commanderCount-1);
    else _snwprintf_s(line,_countof(line),_TRUNCATE,L"아군 지휘 · %ls",commander);
    text(line,left+12,top+270,0xff80c0ff);
    rect(left+12,top+286,width-24,1,0xff294456);
    text(L"막대 = 남은 병력 · 사기 = 전투 의지 · 예비 = 대기 병력",left+12,top+290);
    text(s.partial ? L"일부 병력 집계 · 정보 → 육전대 정보" : L"각 진영 앞 5개 표시 · 정보 → 육전대 정보에서 상세/대상 지정",left+12,top+307,0xff9aafc2);
}
