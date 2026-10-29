// Standing-outfit details using the original tab frame and scrolling sheets.
// Missing CD category-4 contents are reconstructed from server replies. Author: 최병호.
#pragma once
#include "outfit_info_data.h"
#include "ground_info_data.h"
static void renderOutfitInformation();
static void receivedMailOutcome(void* world,unsigned opcode,const BYTE* record);

static struct OutfitInformation {
    void* manager; void* root; void* world; void* pages[4]; void* lists[4];
    unsigned character, grid, id, power, kind, number, token, expectedBase;
    bool selected, loaded, warehouseRequested, warehouseLoaded, timeoutShown;
    ULONGLONG sentAt, groundSentAt, groundAcceptAfter;
    unsigned groundArmed, groundPending;
    unsigned groundToken, groundPage, groundCursors[14286];
    bool groundLoaded, groundWanted, groundFailed;
    ULONGLONG groundPollAt;
    logh::GroundPage groundData;
    const wchar_t* groundMessage;
    BYTE party[logh::outfitPartyBytes], warehouse[0x300];
} outfitInformation = {};
static SRWLOCK outfitReplyLock = SRWLOCK_INIT;
static struct OutfitInbox {
    logh::OutfitReplyQueue queue;
    unsigned token, partyToken, warehouseToken, character, grid; void* world;
    unsigned groundCharacter, groundOutfit, groundAction, groundReply;
    struct GroundRequest {bool pending,marked;unsigned outfit,cursor,token;ULONGLONG sentAt;} groundRequest;
    unsigned groundReady;bool groundFailed;logh::GroundPage groundData;bool groundRecordPending,groundDispatchMarked;
    BYTE party[logh::outfitPartyBytes], warehouse[0x300];
} outfitInbox = {};
static unsigned outfitToken = 0;

static void* outfitWidget(void* root, int type, int index) {
    return reinterpret_cast<void*(__thiscall*)(void*,int,int)>(0x502780)(root,type,index);
}
static void outfitText(void* root, int index, const wchar_t* value) {
    auto widget=outfitWidget(root,1,index);
    if (widget) reinterpret_cast<void(__thiscall*)(void*,void*,const wchar_t*)>(0x503560)(root,widget,value);
}
static void outfitBox(void* root, void* widget, int x, int y, int width, int height) {
    int rect[]={x,y,0,width,height};
    reinterpret_cast<void(__thiscall*)(void*,void*,const int*)>(0x502940)(root,widget,rect);
    reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502eb0)(root,widget,1);
    reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x5024e0)(root,widget,1);
}
static void outfitCell(unsigned page, unsigned row, unsigned col, const wchar_t* value) {
    auto& state=outfitInformation;
    // Native rows stop at the first empty first-column cell. A space preserves
    // the breathing room without hiding all subsequent records.
    if (row && !col) reinterpret_cast<void(__thiscall*)(void*,void*,const wchar_t*,int,int)>(0x505b50)
        (state.pages[page],state.lists[page],L" ",row*2-1,0);
    reinterpret_cast<void(__thiscall*)(void*,void*,const wchar_t*,int,int)>(0x505b50)
        (state.pages[page],state.lists[page],value,row*2,col);
}
static void outfitClear(unsigned page, const wchar_t* a, const wchar_t* b, const wchar_t* c) {
    auto& state=outfitInformation;
    reinterpret_cast<void(__thiscall*)(void*,void*)>(0x5061c0)(state.pages[page],state.lists[page]);
    reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x5053d0)(state.pages[page],state.lists[page],3);
    const int widths[]={300,150,190};
    const wchar_t* headers[]={a,b,c};
    for (unsigned i=0;i<3;++i) {
        reinterpret_cast<void(__thiscall*)(void*,void*,int,int)>(0x5054b0)
            (state.pages[page],state.lists[page],widths[i],i);
        outfitCell(page,0,i,headers[i]);
    }
}
static void outfitNumber(unsigned page,unsigned row,unsigned col,unsigned number) {
    wchar_t value[32];_snwprintf_s(value,_countof(value),_TRUNCATE,L"%u",number);outfitCell(page,row,col,value);
}
static void outfitItemName(bool ship,unsigned kind,wchar_t (&name)[64]) {
    name[0]=0;
    if (kind < (ship ? 179u : 16u)) {
        auto value=reinterpret_cast<const char*(__thiscall*)(void*,int,int)>(0x522010)
            (reinterpret_cast<void*>(0x2217400),ship ? 85 : 86,kind);
        if (value) MultiByteToWideChar(932,0,value,-1,name,_countof(name));
    }
    if (!name[0]) _snwprintf_s(name,_countof(name),_TRUNCATE,L"미등록 품목 %u",kind);
}
static bool outfitWarehouseAccess() {
    auto& state=outfitInformation;
    auto own=state.world ? at<void*>(state.world,8) : nullptr;
    if (!own || at<BYTE>(own,0x270)>16) return false;
    for (unsigned i=0;i<at<BYTE>(own,0x270);++i) {
        unsigned card=at<unsigned short>(own,0x274+i*8);
        if ((card==39 || card==44 || card==47 || card==50 || card==175 || card==180 || card==183 || card==186) &&
            at<unsigned>(own,0x278+i*8)==state.id) return true;
    }
    return false;
}
static bool requestOutfit(bool warehouse) {
    auto& state=outfitInformation;
    unsigned base=warehouse ? logh::infoWord(state.party,4) : 0;
    if(warehouse && !base)return false;
    AcquireSRWLockExclusive(&outfitReplyLock);
    bool queued=outfitInbox.queue.add(state.id,base,state.token,warehouse);
    ReleaseSRWLockExclusive(&outfitReplyLock);
    if (!queued) return false;
    unsigned query[]={warehouse ? base : state.id,warehouse ? state.id : 0,0};
    reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)
        (state.world,0,warehouse ? 0x17 : 0x10,query);
    return true;
}
static void invalidateOutfitGround(bool reset=false) {
    auto& state=outfitInformation;
    state.groundToken=0;state.groundWanted=true;state.groundPollAt=0;state.groundFailed=false;
    if(reset){state.groundPage=0;state.groundCursors[0]=0;state.groundLoaded=false;}
}
// Extension pages are view-only records. Keep them out of the native party/unit
// editor caches, including when a delayed page arrives after the view is closed.
static void __fastcall receivedOutfitPagePacket(void* world,void*,unsigned opcode,void* record) {
    bool consume=(opcode&65535)==0x032f && at<BYTE>(record,8)==2;
    AcquireSRWLockExclusive(&outfitReplyLock);
    auto& q=outfitInbox.groundRequest;
    if(consume && q.outfit==at<unsigned>(record,0) && q.cursor==at<unsigned>(record,4))outfitInbox.groundDispatchMarked=true;
    // The native input queue owns a copy, rather than the decoder's temporary
    // object. Match the marked page's fields, never a freed decoder pointer.
    if((opcode&65535)==0x0331 && outfitInbox.groundRecordPending && outfitInbox.groundDispatchMarked &&
        (outfitInbox.groundFailed || outfitInbox.groundData.matches(static_cast<const BYTE*>(record)))) {
        outfitInbox.groundRecordPending=false;outfitInbox.groundDispatchMarked=false;consume=true;
    }
    ReleaseSRWLockExclusive(&outfitReplyLock);
    if(!consume)reinterpret_cast<void(__thiscall*)(void*,unsigned,void*)>(0x4ba2b0)(world,opcode,record);
    if(!consume && (opcode&65535)==0x035a)receivedSessionEnding(world,static_cast<const BYTE*>(record));
    if(!consume)receivedMailOutcome(world,opcode,static_cast<const BYTE*>(record));
}
static unsigned __stdcall decodedOutfitGround(void* record,void* reader) {
    unsigned result=reinterpret_cast<unsigned(__stdcall*)(void*,void*)>(0x41f3d0)(record,reader);
    AcquireSRWLockExclusive(&outfitReplyLock);
    auto& q=outfitInbox.groundRequest;
    if(q.pending && q.marked) {
        outfitInbox.groundFailed=result || !outfitInbox.groundData.read(static_cast<const BYTE*>(record),q.outfit,q.cursor);
        outfitInbox.groundRecordPending=true;outfitInbox.groundReady=q.token;q.pending=false;q.marked=false;
    }
    ReleaseSRWLockExclusive(&outfitReplyLock);return result;
}
static bool updateOutfitGroundPage() {
    auto& state=outfitInformation;auto now=GetTickCount64();bool changed=false,send=false;
    unsigned query[3]={state.id,state.groundCursors[state.groundPage],2};
    AcquireSRWLockExclusive(&outfitReplyLock);
    if(outfitInbox.groundReady) {
        if(outfitInbox.groundReady==state.groundToken) {
            changed=!state.groundLoaded || state.groundFailed!=outfitInbox.groundFailed ||
                !state.groundData.same(outfitInbox.groundData);
            state.groundData=outfitInbox.groundData;state.groundFailed=outfitInbox.groundFailed;
            state.groundLoaded=!state.groundFailed;state.groundPollAt=now+2000;
        }
        outfitInbox.groundReady=0;
    }
    auto& q=outfitInbox.groundRequest;
    if(q.pending && q.token==state.groundToken && now-q.sentAt>=10000 && !state.groundFailed) {
        // Keep the outstanding identity until its marked reply is consumed. A late
        // empty response must never satisfy a different selection or page.
        state.groundFailed=true;changed=true;
    }
    if(state.loaded && !q.pending && !outfitInbox.groundRecordPending && !state.groundPending && (state.groundWanted || now>=state.groundPollAt)) {
        state.groundToken=++outfitToken;if(!state.groundToken)state.groundToken=++outfitToken;
        q={true,false,state.id,query[1],state.groundToken,now};
        state.groundWanted=false;state.groundPollAt=now+10000;send=true;
    }
    ReleaseSRWLockExclusive(&outfitReplyLock);
    if(send) {
        requestOutfit(false);
        reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(state.world,0,0x10,query);
    }
    return changed;
}
static void renderOutfitGroundPage(unsigned start) {
    auto& state=outfitInformation;wchar_t label[64],place[100];
    _snwprintf_s(label,_countof(label),_TRUNCATE,L"병력 배치 · %u쪽",state.groundPage+1);
    outfitCell(2,start,0,label);outfitCell(2,start,1,L"탑승함 / 주둔 거점");outfitCell(2,start,2,L"배치 상태");
    if(!state.groundLoaded || state.groundFailed) {
        outfitCell(2,start+1,0,state.groundFailed ? L"배치 응답 확인 실패" : L"병력 배치 조회 중");return;
    }
    if(!state.groundData.count){outfitCell(2,start+1,0,L"이 페이지에 육전대가 없습니다.");return;}
    for(unsigned i=0;i<state.groundData.count;++i) {
        auto row=state.groundData.rows+i*88;unsigned mode=row[6],carrier=logh::infoWord(row,16),base=logh::infoWord(row,64);
        outfitItemName(false,logh::infoShort(row,4)-1000,label);outfitCell(2,start+1+i,0,label);place[0]=0;
        if(carrier) {
            for(unsigned j=0;j<state.party[0x180];++j) {
                auto ship=state.party+0x184+j*0x120;
                for(unsigned k=0;k<ship[6];++k)if(logh::infoWord(ship,8+k*4)==carrier) {
                    wchar_t name[64];outfitItemName(true,logh::infoShort(ship,0),name);
                    _snwprintf_s(place,_countof(place),_TRUNCATE,L"%ls",name);
                }
            }
            if(!place[0])wcscpy_s(place,L"함종 정보 미수신");
        } else if(base) {
            for(unsigned j=0;j<350;++j)if(at<unsigned>(state.world,0x2eb288+j*4)==base) {
                memcpy(place,static_cast<BYTE*>(state.world)+0x2eb800+j*0x250+0x48,24);break;
            }
            if(!place[0])_snwprintf_s(place,_countof(place),_TRUNCATE,L"거점 %u (이름 미수신)",base);
        } else wcscpy_s(place,L"부대 소속");
        outfitCell(2,start+1+i,1,place);
        outfitCell(2,start+1+i,2,mode==10 ? L"탑승" : mode==9 ? L"강하" : mode==4 ? L"주둔" : L"배치 미확정");
    }
}
// Ground commands are reconstructed controls on the existing native tab frame.
// The second click confirms the whole-outfit operation; changing selection cancels it.
static bool outfitGroundAccess() {
    auto& state=outfitInformation;
    auto own=state.world ? at<BYTE*>(state.world,8) : nullptr;
    return state.loaded && logh::infoWord(state.party,4)!=0 && own && at<unsigned>(own,0x358)==logh::infoWord(state.party,4) && outfitWarehouseAccess();
}
static void buildOutfitGroundControls() {
    auto& state=outfitInformation;
    for(int n=0;n<4;++n) {
        int x=n<2 ? 16+n*202 : 426+(n-2)*124,y=450,width=n<2 ? 192 : 112,height=26,decoration=30+n*6;
        const int sx[]={459,467,659},widths[]={8,width-16,8};int top=y;
        for(int row=0;row<2;++row) {
            int h=height/2,left=x;
            for(int col=0;col<3;++col) {
                unsigned art[40]={};
                for(int i=0;i<4;++i) {
                    art[i*5]=reinterpret_cast<unsigned>(planetPartsPath);
                    art[i*5+1]=sx[col];art[i*5+2]=row ? 314-h : 264;art[i*5+3]=widths[col];art[i*5+4]=h;
                }
                auto piece=reinterpret_cast<void*(__thiscall*)(void*,int,int,const void*)>(0x503a10)(state.root,2,decoration++,art);
                outfitBox(state.root,piece,left,top,widths[col],h);left+=widths[col];
            }
            top+=h;
        }
        auto button=reinterpret_cast<void*(__thiscall*)(void*,int,int,const void*)>(0x503a10)(state.root,1,n<2 ? 22+n : 23+n,nullptr);
        outfitBox(state.root,button,x,y,width,height);
        reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x5029e0)(state.root,button,2);
        reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502b00)(state.root,button,1);
        reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502b30)(state.root,button,1);
    }
    auto message=reinterpret_cast<void*(__thiscall*)(void*,int,int,const void*)>(0x503a10)(state.root,1,24,nullptr);
    outfitBox(state.root,message,16,478,660,22);
    reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x5029e0)(state.root,message,3);
}
static void updateOutfitGroundControls() {
    auto& state=outfitInformation;auto now=GetTickCount64();
    unsigned reply=0;
    AcquireSRWLockExclusive(&outfitReplyLock);
    if(outfitInbox.groundCharacter==state.character && outfitInbox.groundOutfit==state.id &&
        outfitInbox.groundAction==state.groundPending)reply=outfitInbox.groundReply;
    outfitInbox.groundReply=0;
    ReleaseSRWLockExclusive(&outfitReplyLock);
    if(reply && state.groundPending) {
        state.groundPending=0;state.groundArmed=0;
        state.groundMessage=L"서버 응답을 받았습니다. 최신 편성을 조회합니다.";
        invalidateOutfitGround();requestOutfit(false);
    }
    if(state.groundPending && now-state.groundSentAt>=10000) {
        state.groundPending=0;state.groundArmed=0;
        state.groundMessage=L"실행 결과를 확인하지 못했습니다. CP와 현재 상태를 확인하십시오.";
        requestOutfit(false);
    }
    bool enabled=outfitGroundAccess() && !state.groundPending && !at<BYTE>(state.world,0x3579cc);
    outfitText(state.root,22,state.groundArmed==0x40 ? L"육전대 출격 확정" : L"육전대 출격 · MCP 80");
    outfitText(state.root,23,state.groundArmed==0x41 ? L"육전대 철수 확정" : L"육전대 철수 · MCP 80");
    outfitText(state.root,25,L"배치 이전");outfitText(state.root,26,L"배치 다음");
    outfitText(state.root,24,state.groundMessage ? state.groundMessage :
        enabled ? L"전체 병력을 대상으로 합니다. 버튼을 다시 누르면 실행합니다." : L"같은 아군 거점에 정박한 부대의 지휘관만 실행할 수 있습니다.");
    BYTE event[52]={};
    for(int n=0;n<4;++n) {
        auto button=outfitWidget(state.root,1,n<2 ? 22+n : 23+n);
        bool available=n<2 ? enabled : !state.groundPending && state.groundLoaded && !state.groundFailed &&
            (n==2 ? state.groundPage>0 : state.groundData.count==70 && state.groundPage+1<_countof(state.groundCursors));
        reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x5024e0)(state.root,button,available ? 1 : 0);
        if(!available || now<state.groundAcceptAfter || !reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,void*,int)>(0x5015f0)(state.root,2,button,event,0))continue;
        state.groundAcceptAfter=now+250;
        if(n>=2) {
            if(n==2)--state.groundPage;
            else {state.groundCursors[state.groundPage+1]=state.groundData.last();++state.groundPage;}
            state.groundLoaded=false;invalidateOutfitGround();renderOutfitInformation();break;
        }
        unsigned action=0x40+n;
        if(state.groundArmed!=action) {
            state.groundArmed=action;state.groundMessage=action==0x40 ?
                L"전 육전대를 현재 거점에 주둔시킵니다. 다시 눌러 확정하십시오." :
                L"현재 거점의 전 육전대를 함정에 태웁니다. 다시 눌러 확정하십시오.";
        } else {
            BYTE record[36]={};at<unsigned>(record,4)=state.character;at<unsigned>(record,16)=state.id;
            // Count zero is the explicit whole-outfit extension. Opaque native values stay empty.
            state.groundPending=action;state.groundSentAt=now;state.groundArmed=0;
            state.groundMessage=L"서버에 전체 승하선을 요청했습니다.";
            AcquireSRWLockExclusive(&outfitReplyLock);
            outfitInbox.groundCharacter=state.character;outfitInbox.groundOutfit=state.id;
            outfitInbox.groundAction=action;outfitInbox.groundReply=0;
            ReleaseSRWLockExclusive(&outfitReplyLock);
            // Native selectors 0x40/0x41 have no queued response ID. Serialize this
            // stack record immediately, as fuel/departure do, without retaining its pointer.
            reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(state.world,0,action,record);
        }
        break;
    }
}
static void receivedOutfitGround(void* world,void* record,unsigned flags,unsigned action) {
    reinterpret_cast<void(__thiscall*)(void*,void*,unsigned)>(action==0x40 ? 0x4c00c0 : 0x4c0130)(world,record,flags);
    AcquireSRWLockExclusive(&outfitReplyLock);
    if(outfitInbox.world==world && outfitInbox.groundAction==action &&
        outfitInbox.groundCharacter==at<unsigned>(record,4) && outfitInbox.groundOutfit==at<unsigned>(record,16))
        outfitInbox.groundReply=1;
    ReleaseSRWLockExclusive(&outfitReplyLock);
}
static void __fastcall receivedOutfitUnload(void* world,void*,void* record,unsigned flags) {receivedOutfitGround(world,record,flags,0x40);}
static void __fastcall receivedOutfitLoad(void* world,void*,void* record,unsigned flags) {receivedOutfitGround(world,record,flags,0x41);}
static void renderOutfitInformation() {
    auto& state=outfitInformation;
    if (!state.root || !state.id) return;
    const wchar_t* kinds[]={L"함대",L"수송함대",L"순찰대",L"지상부대"};
    wchar_t title[96],summary[160],name[64];
    _snwprintf_s(title,_countof(title),_TRUNCATE,L"제%u%ls · 부대 정보",state.number,kinds[state.kind]);
    outfitText(state.root,2,title);
    _snwprintf_s(summary,_countof(summary),_TRUNCATE,L"%ls · 제%u%ls",
        state.power==2 ? L"은하제국" : L"자유행성동맹",state.number,kinds[state.kind]);
    outfitText(state.root,20,summary);
    outfitClear(0,L"부대 현황",L"값",L"설명");
    outfitClear(1,L"함종",L"함선 유닛",L"척수");
    outfitClear(2,L"병종",L"숙련도",L"병력 유닛");
    outfitClear(3,L"창고 품목",L"유닛",L"척수 / 숙련도");
    if (!state.loaded) {
        auto own=at<void*>(state.world,8);
        const wchar_t* message=own && at<BYTE>(own,0x28)!=state.power ? L"다른 세력의 상세 편성은 조회할 수 없습니다." :
            GetTickCount64()-state.sentAt >= 5000 ? L"응답을 받지 못했습니다. 창을 다시 열어 조회해 주세요." : L"서버에서 편성을 조회하고 있습니다.";
        outfitText(state.root,21,message);return;
    }
    auto p=state.party;
    unsigned ships=0,boats=0,troops=0;
    for (unsigned i=0;i<p[0x180];++i) {
        auto row=p+0x184+i*0x120;
        outfitItemName(true,logh::infoShort(row,0),name);outfitCell(1,i+1,0,name);
        outfitNumber(1,i+1,1,row[2]);outfitNumber(1,i+1,2,logh::infoShort(row,4));
        ships+=row[2];boats+=logh::infoShort(row,4);
    }
    const wchar_t* grades[]={L"그린",L"노멀",L"베테랑",L"엘리트"};
    for (unsigned i=0;i<p[0x4504];++i) {
        auto row=p+0x4506+i*6;
        outfitItemName(false,logh::infoShort(row,0)-1000,name);outfitCell(2,i+1,0,name);
        outfitCell(2,i+1,1,grades[row[2]]);outfitNumber(2,i+1,2,logh::infoShort(row,4));
        troops+=logh::infoShort(row,4);
    }
    renderOutfitGroundPage(p[0x4504]+1);
    _snwprintf_s(summary,_countof(summary),_TRUNCATE,L"함선 %u유닛 / %u척 · 병력 %u유닛 · 군수물자 %u / %u",
        ships,boats,troops,logh::infoWord(p,0x4598),logh::outfitSupplyCapacity(p));
    outfitText(state.root,21,summary);
    const wchar_t* labels[]={L"함선 유닛",L"총 함선",L"병력 유닛",L"군수물자",L"물자 적재 상한"};
    const unsigned values[]={ships,boats,troops,logh::infoWord(p,0x4598),logh::outfitSupplyCapacity(p)};
    for (unsigned i=0;i<5;++i) {outfitCell(0,i+1,0,labels[i]);outfitNumber(0,i+1,1,values[i]);}
    wchar_t baseName[13]={}; unsigned base=logh::infoWord(p,4);
    for (unsigned i=0;i<350;++i) if (at<unsigned>(state.world,0x2eb288+i*4)==base) {
        memcpy(baseName,static_cast<BYTE*>(state.world)+0x2eb800+i*0x250+0x48,24);break;
    }
    outfitCell(0,6,0,L"현재 거점");outfitCell(0,6,1,!base ? L"우주 항행 중" : baseName[0] ? baseName : L"이름 미수신");
    if (!p[20]) {outfitCell(0,7,0,L"지휘관");outfitCell(0,7,1,L"미임명");}
    for (unsigned i=0;i<p[20];++i) {
        auto member=p+24+i*36;wchar_t memberName[13]={};
        if (member[6]) memcpy(memberName,member+8,(member[6]-1)*2);
        outfitCell(0,7+i,0,memberName[0] ? memberName : L"이름 없음");
        wchar_t rank[64]={};
        if (member[5]<21) {
            auto value=reinterpret_cast<const char*(__thiscall*)(void*,int,int)>(0x522010)
                (reinterpret_cast<void*>(0x2217400),5,member[5]);
            if (value) MultiByteToWideChar(932,0,value,-1,rank,_countof(rank));
        }
        outfitCell(0,7+i,1,rank[0] ? rank : L"계급 미확인");outfitCell(0,7+i,2,L"등록 인물");
    }
    if (!state.warehouseLoaded) {
        outfitCell(3,1,0,!logh::infoWord(state.party,4) ? L"정박 후 부대 창고를 조회할 수 있습니다." : outfitWarehouseAccess() ? L"창고 응답 대기 중" : L"부대 지휘관만 창고를 조회할 수 있습니다.");
        return;
    }
    auto w=state.warehouse;
    for (unsigned i=0;i<unsigned(w[12])+w[0x260];++i) {
        bool ship=i<w[12];auto row=w+(ship ? 14+i*6 : 0x262+(i-w[12])*6);
        outfitItemName(ship,logh::infoShort(row,0)-(ship ? 0 : 1000),name);outfitCell(3,i+1,0,name);
        outfitNumber(3,i+1,1,ship ? row[2] : logh::infoShort(row,4));
        if (ship) outfitNumber(3,i+1,2,logh::infoShort(row,4));
        else outfitCell(3,i+1,2,row[2]<4 ? grades[row[2]] : L"미확인");
    }
    if (!w[12] && !w[0x260]) outfitCell(3,1,0,L"보관 중인 함선·병력이 없습니다.");
}
static void __cdecl buildOutfitInformation(void* manager,int kind) {
    outfitInformation.selected=false;
    if (kind!=4) {reinterpret_cast<void(__cdecl*)(void*,int)>(0x54c4b0)(manager,kind);return;}
    auto& state=outfitInformation;
    // Closed native tab frames retain their sheet links. Reuse the complete
    // frame in the same context rather than destroy/rebuild it during input.
    auto currentWorld=*reinterpret_cast<void**>(0x7ccffc);
    auto currentOwn=currentWorld ? at<void*>(currentWorld,8) : nullptr;
    bool reusable=currentOwn && state.manager==manager && state.world==currentWorld && state.root &&
        state.character==at<unsigned>(currentOwn,0x24) && state.grid==at<unsigned>(currentWorld,0x2b6a70) &&
        at<void*>(manager,4+13*4)==state.root;
    for (unsigned i=0;i<4 && reusable;++i)
        reusable=state.pages[i] && at<void*>(manager,4+(14+i)*4)==state.pages[i];
    if (reusable) return;
    state={};state.manager=manager;state.world=*reinterpret_cast<void**>(0x7ccffc);
    auto own=state.world ? at<void*>(state.world,8) : nullptr;
    if (!own) return;
    state.character=at<unsigned>(own,0x24);state.grid=at<unsigned>(state.world,0x2b6a70);
    AcquireSRWLockExclusive(&outfitReplyLock);
    if (outfitInbox.world!=state.world || outfitInbox.character!=state.character || outfitInbox.grid!=state.grid) {
        outfitInbox.queue={};outfitInbox.partyToken=outfitInbox.warehouseToken=0;
        outfitInbox.world=state.world;outfitInbox.character=state.character;outfitInbox.grid=state.grid;
        // An earlier page may still be in flight on the same connection.
        // Its marker/record must be consumed before a new page can be requested.
    }
    ReleaseSRWLockExclusive(&outfitReplyLock);
    reinterpret_cast<void(__thiscall*)(void*,int)>(0x50bc90)(manager,13);
    for (unsigned i=0;i<4;++i) {
        reinterpret_cast<void(__thiscall*)(void*,int)>(0x50bc90)(manager,14+i);
        reinterpret_cast<void(__cdecl*)(void*,int,int,int,const wchar_t*)>(0x5187b0)(manager,14+i,676,350,nullptr);
        auto root=at<void*>(manager,4+(14+i)*4);state.pages[i]=root;state.lists[i]=outfitWidget(root,4,0);
        outfitBox(root,outfitWidget(root,0,0),0,40,676,350);
        reinterpret_cast<void(__thiscall*)(void*,void*,float,float,float,float)>(0x502f40)
            (root,outfitWidget(root,0,0),0.01f,0.025f,0.05f,1.f);
        // The sheet supplies scrolling behavior; its standalone title/frame
        // must not overlap the enclosing tab frame.
        for (int j=0;j<2;++j) reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502eb0)(root,outfitWidget(root,2,j),0);
        for (int j=0;j<4;++j) reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502eb0)(root,outfitWidget(root,1,j),0);
        reinterpret_cast<void(__thiscall*)(void*,void*,int,int,int,int,int,int,int,int,int,int,int)>(0x503680)
            (root,state.lists[i],0,0,1,1,1,1,12,12,18,28,1);
    }
    static const wchar_t* labels[]={L"부대 개요",L"함선 편성",L"병력 편성",L"부대 창고"};
    static const int ids[]={14,15,16,17};
    reinterpret_cast<void(__cdecl*)(void*,int,int,int,const wchar_t*,int,void*,int,const wchar_t**,int,int,const int*,char)>(0x5148b0)
        (manager,13,700,505,L"부대 정보",-1,reinterpret_cast<void*>(0x22166fc),-24516,labels,4,-1,ids,0);
    state.root=at<void*>(manager,4+13*4);
    auto surface=reinterpret_cast<void*(__thiscall*)(void*,int,int,const void*)>(0x503a10)(state.root,2,20,nullptr);
    outfitBox(state.root,surface,10,44,680,400);
    unsigned fill[45]={};
    for (unsigned i=0;i<9;++i) {
        fill[i*5]=reinterpret_cast<unsigned>(planetPartsPath);
        fill[i*5+1]=470;fill[i*5+2]=286;fill[i*5+3]=fill[i*5+4]=1;
    }
    for (int i=0;i<4;++i) reinterpret_cast<void(__thiscall*)(void*,void*,const void*,int,int,int)>(0x502fe0)
        (state.root,surface,fill,3,3,i);
    reinterpret_cast<void(__thiscall*)(void*,void*,float,float,float,float)>(0x502f40)
        (state.root,surface,0.015f,0.035f,0.065f,1.f);
    for (int i=0;i<2;++i) {
        auto control=reinterpret_cast<void*(__thiscall*)(void*,int,int,const void*)>(0x503a10)(state.root,1,20+i,nullptr);
        outfitBox(state.root,control,16,48+i*26,660,24);
        reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x5029e0)(state.root,control,3);
    }
    buildOutfitGroundControls();
    reinterpret_cast<void(__thiscall*)(void*)>(0x503910)(state.root);
}
static bool selectOutfitIdentity(unsigned id,unsigned kind,unsigned power,unsigned number,unsigned base=0) {
    auto& state=outfitInformation;
    if (!state.root || !state.world || !id || kind>3 || power<2 || power>3 || number>255 || base>65535) return false;
    state.id=id;state.kind=kind;state.power=power;state.number=number;state.expectedBase=base;
    state.loaded=state.warehouseLoaded=state.warehouseRequested=state.timeoutShown=false;state.selected=true;
    state.groundArmed=state.groundPending=0;state.groundMessage=nullptr;state.groundAcceptAfter=GetTickCount64()+250;
    invalidateOutfitGround(true);
    memset(state.party,0,sizeof(state.party));memset(state.warehouse,0,sizeof(state.warehouse));
    if (++outfitToken==0) ++outfitToken;state.token=outfitToken;state.sentAt=GetTickCount64();
    AcquireSRWLockExclusive(&outfitReplyLock);outfitInbox.token=state.token;ReleaseSRWLockExclusive(&outfitReplyLock);
    if (at<BYTE>(at<void*>(state.world,8),0x28)==state.power) requestOutfit(false);
    renderOutfitInformation();return true;
}
static int __cdecl selectOutfitInformation(void* manager,int kind) {
    int row=reinterpret_cast<int(__cdecl*)(void*,int)>(0x54bee0)(manager,kind);
    auto& state=outfitInformation;
    if (kind!=4 || row<1 || !state.root || state.manager!=manager || !state.world) return row;
    auto definition=logh::outfitListRow(static_cast<BYTE*>(state.world)+0x811fc,row);
    if (definition) selectOutfitIdentity(logh::infoWord(definition,0),definition[4],definition[5],definition[7]);
    return row;
}
static bool openStationedOutfit(void* manager,unsigned id,unsigned kind,unsigned power,unsigned number,unsigned base) {
    auto world=*reinterpret_cast<void**>(0x7ccffc);auto own=world ? at<BYTE*>(world,8) : nullptr;
    if (!own || own[0x28]!=power || !base || base>65535 || !id || kind>3 || number>255) return false;
    buildOutfitInformation(manager,4);
    if (!selectOutfitIdentity(id,kind,power,number,base)) return false;
    reinterpret_cast<void(__thiscall*)(void*,int)>(0x506280)(outfitInformation.root,12);
    reinterpret_cast<void(__thiscall*)(void*,void*)>(0x50beb0)(manager,outfitInformation.root);
    return true;
}
static bool stationedOutfitVisible(void* manager) {
    auto& state=outfitInformation;
    return state.expectedBase && state.manager==manager && state.root && at<void*>(manager,4+13*4)==state.root && at<BYTE>(state.root,4);
}
static bool outfitInformationPending() {
    AcquireSRWLockShared(&outfitReplyLock);bool pending=outfitInbox.queue.count!=0;
    ReleaseSRWLockShared(&outfitReplyLock);return pending;
}
static bool updateStationedOutfit(void* manager) {
    if (!stationedOutfitVisible(manager)) return false;
    // 0054be23 normally ticks this cdecl tab controller for root 13. A direct
    // planet entry has no native information-menu selection to drive it.
    reinterpret_cast<void(__cdecl*)(void*)>(0x515950)(outfitInformation.root);
    return stationedOutfitVisible(manager);
}
static void closeStationedOutfit(void* manager) {
    if (!outfitInformation.expectedBase || outfitInformation.manager!=manager) return;
    if (stationedOutfitVisible(manager)) reinterpret_cast<void(__thiscall*)(void*,int)>(0x502ea0)(outfitInformation.root,0);
    outfitInformation.selected=false;
}
static unsigned __stdcall decodedOutfitInformation(void* record,void* reader) {
    unsigned result=reinterpret_cast<unsigned(__stdcall*)(void*,void*)>(0x41cba0)(record,reader);
    auto p=static_cast<const BYTE*>(record);unsigned id=logh::infoWord(p,0),token=0;
    if(!result && p[8]==2) {
        AcquireSRWLockExclusive(&outfitReplyLock);
        auto& q=outfitInbox.groundRequest;
        if(q.pending && q.outfit==id && q.cursor==logh::infoWord(p,4))q.marked=true;
        ReleaseSRWLockExclusive(&outfitReplyLock);return result;
    }
    if (result || !logh::validOutfitParty(p,id)) return result;
    planetUnitsParty(p);
    AcquireSRWLockExclusive(&outfitReplyLock);
    if (outfitInbox.queue.take(id,logh::infoWord(p,4),false,token) && token==outfitInbox.token) {
        memcpy(outfitInbox.party,p,sizeof(outfitInbox.party));outfitInbox.partyToken=token;
    }
    ReleaseSRWLockExclusive(&outfitReplyLock);return result;
}
static void outfitWarehouseResponse(void*,const BYTE* record) {
    unsigned token=0;
    if (record[12]>99 || record[0x260]>24) return;
    AcquireSRWLockExclusive(&outfitReplyLock);
    if (outfitInbox.queue.take(logh::infoWord(record,4),logh::infoWord(record,0),true,token) && token==outfitInbox.token) {
        memcpy(outfitInbox.warehouse,record,sizeof(outfitInbox.warehouse));outfitInbox.warehouseToken=token;
    }
    ReleaseSRWLockExclusive(&outfitReplyLock);
}
static void updateOutfitInformation(void* manager) {
    auto& state=outfitInformation;if (!state.world) return;
    auto world=*reinterpret_cast<void**>(0x7ccffc);auto own=world ? at<void*>(world,8) : nullptr;
    if (manager!=state.manager || world!=state.world || !own || !at<BYTE>(own,0) ||
        at<unsigned>(own,0x24)!=state.character || at<unsigned>(world,0x2b6a70)!=state.grid ||
        !at<BYTE>(world,0x2a58f8) || (at<BYTE>(world,0x3579cc) && !state.groundPending)) {
        AcquireSRWLockExclusive(&outfitReplyLock);
        outfitInbox.queue={};outfitInbox.token=outfitInbox.partyToken=outfitInbox.warehouseToken=0;
        outfitInbox.world=nullptr;
        if(!world || !at<BYTE>(world,0x2a58f8)) {
            outfitInbox.groundRequest={};outfitInbox.groundReady=0;outfitInbox.groundRecordPending=outfitInbox.groundDispatchMarked=false;
        }
        ReleaseSRWLockExclusive(&outfitReplyLock);
        state.selected=false;return;
    }
    if (!state.selected) return;
    if (at<void*>(manager,4+13*4)!=state.root || !at<BYTE>(state.root,4)) {state.selected=false;return;}
    bool changed=false;
    AcquireSRWLockExclusive(&outfitReplyLock);
    if (outfitInbox.partyToken==state.token) {
        changed=!state.loaded || !logh::sameOutfitParty(state.party,outfitInbox.party);
        memcpy(state.party,outfitInbox.party,sizeof(state.party));outfitInbox.partyToken=0;
        state.loaded=logh::matchingOutfitParty(state.party,state.id,state.power,state.kind,state.expectedBase);
    }
    if (outfitInbox.warehouseToken==state.token) {
        changed=changed || !state.warehouseLoaded || memcmp(state.warehouse,outfitInbox.warehouse,sizeof(state.warehouse));
        memcpy(state.warehouse,outfitInbox.warehouse,sizeof(state.warehouse));outfitInbox.warehouseToken=0;
        state.warehouseLoaded=true;
    }
    ReleaseSRWLockExclusive(&outfitReplyLock);
    if (state.loaded && logh::infoWord(state.party,4) && !state.warehouseRequested && outfitWarehouseAccess()) state.warehouseRequested=requestOutfit(true);
    if (!state.loaded && !state.timeoutShown && GetTickCount64()-state.sentAt>=5000) {state.timeoutShown=true;changed=true;}
    changed=updateOutfitGroundPage() || changed;
    if (changed) renderOutfitInformation();
    updateOutfitGroundControls();
}
static bool installOutfitInformation() {
    const unsigned addresses[]={0x54bcac,0x54bcde,0x54bd2f,0x4bcc68,0x4bcc9f,0x4b8a78};
    const BYTE expected[][5]={{0xe8,0xff,7,0,0},{0xe8,0xcd,7,0,0},{0xe8,0xac,1,0,0},{0xe8,0x53,0x34,0,0},{0xe8,0x8c,0x34,0,0},{0xe8,0x33,0x18,0,0}};
    const void* functions[]={&buildOutfitInformation,&buildOutfitInformation,&selectOutfitInformation,&receivedOutfitUnload,&receivedOutfitLoad,&receivedOutfitPagePacket};
    const unsigned decoder=0x41cba0,groundDecoder=0x41f3d0;
    if(memcmp(reinterpret_cast<void*>(0x66c48c),&groundDecoder,4))return false;
    if (memcmp(reinterpret_cast<void*>(0x66c4a0),&decoder,4)) return false;
    for (unsigned i=0;i<_countof(addresses);++i) if (memcmp(reinterpret_cast<void*>(addresses[i]),expected[i],5)) return false;
    for (unsigned i=0;i<_countof(addresses);++i) {
        BYTE patch[5]={0xe8};*reinterpret_cast<LONG*>(patch+1)=static_cast<LONG>(static_cast<const BYTE*>(functions[i])-reinterpret_cast<BYTE*>(addresses[i])-5);
        if (!patchBytes(reinterpret_cast<void*>(addresses[i]),expected[i],patch,5)) return false;
    }
    const auto replacement=reinterpret_cast<unsigned>(&decodedOutfitInformation);
    const auto groundReplacement=reinterpret_cast<unsigned>(&decodedOutfitGround);
    return patchBytes(reinterpret_cast<void*>(0x66c4a0),&decoder,&replacement,4) &&
        patchBytes(reinterpret_cast<void*>(0x66c48c),&groundDecoder,&groundReplacement,4);
}
