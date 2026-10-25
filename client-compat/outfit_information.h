// Standing-outfit details using the original tab frame and scrolling sheets.
// Missing CD category-4 contents are reconstructed from server replies. Author: 최병호.
#pragma once
#include "outfit_info_data.h"

static struct OutfitInformation {
    void* manager; void* root; void* world; void* pages[4]; void* lists[4];
    unsigned character, grid, id, power, kind, number, token;
    bool selected, loaded, warehouseRequested, warehouseLoaded, timeoutShown;
    ULONGLONG sentAt;
    BYTE party[logh::outfitPartyBytes], warehouse[0x300];
} outfitInformation = {};
static SRWLOCK outfitReplyLock = SRWLOCK_INIT;
static struct OutfitInbox {
    logh::OutfitReplyQueue queue;
    unsigned token, partyToken, warehouseToken, character, grid; void* world;
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
    AcquireSRWLockExclusive(&outfitReplyLock);
    bool queued=outfitInbox.queue.add(state.id,base,state.token,warehouse);
    ReleaseSRWLockExclusive(&outfitReplyLock);
    if (!queued) return false;
    unsigned query[]={warehouse ? base : state.id,warehouse ? state.id : 0,0};
    reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)
        (state.world,0,warehouse ? 0x17 : 0x10,query);
    return true;
}
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
    _snwprintf_s(summary,_countof(summary),_TRUNCATE,L"함선 %u유닛 / %u척 · 병력 %u유닛 · 군수물자 %u / %u",
        ships,boats,troops,logh::infoWord(p,0x4598),logh::infoWord(p,0x459c));
    outfitText(state.root,21,summary);
    const wchar_t* labels[]={L"함선 유닛",L"총 함선",L"병력 유닛",L"군수물자",L"물자 적재 상한"};
    const unsigned values[]={ships,boats,troops,logh::infoWord(p,0x4598),logh::infoWord(p,0x459c)};
    for (unsigned i=0;i<5;++i) {outfitCell(0,i+1,0,labels[i]);outfitNumber(0,i+1,1,values[i]);}
    wchar_t baseName[13]={}; unsigned base=logh::infoWord(p,4);
    for (unsigned i=0;i<350;++i) if (at<unsigned>(state.world,0x2eb288+i*4)==base) {
        memcpy(baseName,static_cast<BYTE*>(state.world)+0x2eb800+i*0x250+0x48,24);break;
    }
    outfitCell(0,6,0,L"현재 거점");outfitCell(0,6,1,baseName[0] ? baseName : L"이름 미수신");
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
        outfitCell(3,1,0,outfitWarehouseAccess() ? L"창고 응답 대기 중" : L"부대 지휘관만 창고를 조회할 수 있습니다.");
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
    state={};state.manager=manager;state.world=*reinterpret_cast<void**>(0x7ccffc);
    auto own=state.world ? at<void*>(state.world,8) : nullptr;
    if (!own) return;
    state.character=at<unsigned>(own,0x24);state.grid=at<unsigned>(state.world,0x2b6a70);
    AcquireSRWLockExclusive(&outfitReplyLock);
    if (outfitInbox.world!=state.world || outfitInbox.character!=state.character || outfitInbox.grid!=state.grid) {
        outfitInbox.queue={};outfitInbox.partyToken=outfitInbox.warehouseToken=0;
        outfitInbox.world=state.world;outfitInbox.character=state.character;outfitInbox.grid=state.grid;
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
    outfitBox(state.root,surface,10,44,680,449);
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
    reinterpret_cast<void(__thiscall*)(void*)>(0x503910)(state.root);
}
static int __cdecl selectOutfitInformation(void* manager,int kind) {
    int row=reinterpret_cast<int(__cdecl*)(void*,int)>(0x54bee0)(manager,kind);
    auto& state=outfitInformation;
    if (kind!=4 || row<1 || !state.root || state.manager!=manager || !state.world) return row;
    auto definition=logh::outfitListRow(static_cast<BYTE*>(state.world)+0x811fc,row);
    if (!definition || definition[4]>3 || definition[5]<2 || definition[5]>3) return row;
    state.id=logh::infoWord(definition,0);state.kind=definition[4];state.power=definition[5];state.number=definition[7];
    state.loaded=state.warehouseLoaded=state.warehouseRequested=state.timeoutShown=false;state.selected=true;
    memset(state.party,0,sizeof(state.party));memset(state.warehouse,0,sizeof(state.warehouse));
    if (++outfitToken==0) ++outfitToken;state.token=outfitToken;state.sentAt=GetTickCount64();
    AcquireSRWLockExclusive(&outfitReplyLock);outfitInbox.token=state.token;ReleaseSRWLockExclusive(&outfitReplyLock);
    if (at<BYTE>(at<void*>(state.world,8),0x28)==state.power) requestOutfit(false);
    renderOutfitInformation();return row;
}
static unsigned __stdcall decodedOutfitInformation(void* record,void* reader) {
    unsigned result=reinterpret_cast<unsigned(__stdcall*)(void*,void*)>(0x41cba0)(record,reader);
    auto p=static_cast<const BYTE*>(record);unsigned id=logh::infoWord(p,0),token=0;
    if (result || !logh::validOutfitParty(p,id)) return result;
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
        !at<BYTE>(world,0x2a58f8) || at<BYTE>(world,0x3579cc)) {
        AcquireSRWLockExclusive(&outfitReplyLock);
        outfitInbox.queue={};outfitInbox.token=outfitInbox.partyToken=outfitInbox.warehouseToken=0;
        outfitInbox.world=nullptr;
        ReleaseSRWLockExclusive(&outfitReplyLock);
        state.selected=false;return;
    }
    if (!state.selected) return;
    if (at<void*>(manager,4+13*4)!=state.root || !at<BYTE>(state.root,4)) {state.selected=false;return;}
    bool changed=false;
    AcquireSRWLockExclusive(&outfitReplyLock);
    if (outfitInbox.partyToken==state.token) {
        memcpy(state.party,outfitInbox.party,sizeof(state.party));outfitInbox.partyToken=0;
        state.loaded=state.party[9]==state.power && logh::infoWord(state.party,12)==state.kind;changed=true;
    }
    if (outfitInbox.warehouseToken==state.token) {
        memcpy(state.warehouse,outfitInbox.warehouse,sizeof(state.warehouse));outfitInbox.warehouseToken=0;
        state.warehouseLoaded=true;changed=true;
    }
    ReleaseSRWLockExclusive(&outfitReplyLock);
    if (state.loaded && !state.warehouseRequested && outfitWarehouseAccess()) state.warehouseRequested=requestOutfit(true);
    if (!state.loaded && !state.timeoutShown && GetTickCount64()-state.sentAt>=5000) {state.timeoutShown=true;changed=true;}
    if (changed) renderOutfitInformation();
}
static bool installOutfitInformation() {
    const unsigned addresses[]={0x54bcac,0x54bcde,0x54bd2f};
    const BYTE expected[][5]={{0xe8,0xff,7,0,0},{0xe8,0xcd,7,0,0},{0xe8,0xac,1,0,0}};
    const void* functions[]={&buildOutfitInformation,&buildOutfitInformation,&selectOutfitInformation};
    const unsigned decoder=0x41cba0;
    if (memcmp(reinterpret_cast<void*>(0x66c4a0),&decoder,4)) return false;
    for (unsigned i=0;i<3;++i) if (memcmp(reinterpret_cast<void*>(addresses[i]),expected[i],5)) return false;
    for (unsigned i=0;i<3;++i) {
        BYTE patch[5]={0xe8};*reinterpret_cast<LONG*>(patch+1)=static_cast<LONG>(static_cast<const BYTE*>(functions[i])-reinterpret_cast<BYTE*>(addresses[i])-5);
        if (!patchBytes(reinterpret_cast<void*>(addresses[i]),expected[i],patch,5)) return false;
    }
    const auto replacement=reinterpret_cast<unsigned>(&decodedOutfitInformation);
    return patchBytes(reinterpret_cast<void*>(0x66c4a0),&decoder,&replacement,4);
}
