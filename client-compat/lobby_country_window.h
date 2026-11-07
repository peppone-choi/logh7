// One native selector for session statistics, creation and lottery countries. Author: Peppone Choi.
#pragma once
static struct LobbyCountryWindow {
    void* gui=nullptr;void* root=nullptr;void* world=nullptr;
    unsigned mode=0,stage=0,session=0,country=0,offset=0,sessionOffset=0,rendered=~0u;
    unsigned widths[114]={},heights[114]={};bool buttons[114]={};
    bool active=false,lotteryChosen=false,selecting=false,selectionError=false,selectionBound=false;
    unsigned lotteryCountry=0;ULONGLONG acceptAfter=0,pollAt=0;
    logh::LobbyCountryPage page={};logh::LobbySessionList sessions={};
    void* icons[6]={};void* bars[6]={};void* backdrop=nullptr;void* lotteryButton=nullptr;
} lobbyCountryWindow;
static UploadedCountryTexture lobbyEmblemTextures[logh::lobbyCountryRows];
static bool lobbyEmblemVisible[logh::lobbyCountryRows]={};

using NativeLobbyIconDraw=void(__thiscall*)(void*,const int*,const int*,const float*,unsigned,float,float,
    void* const*,const unsigned*,unsigned);

static void __fastcall drawLobbyCountryIcon(void* renderer,void*,const int* origin,const int* bounds,const float* color,
    unsigned mode,float rotation,float opacity,void* const* holders,const unsigned* tiles,unsigned state) {
    void* replacementHolders[4]={};
    unsigned replacementTiles[20]={};
    BYTE replacementQuad[20]={};
    const auto& view=lobbyCountryWindow;
    if(view.active && state<4 && holders && holders[state])for(unsigned i=0;i<view.page.count;++i) {
        if(!lobbyEmblemVisible[i] || !view.icons[i] || holders!=reinterpret_cast<void* const*>(static_cast<BYTE*>(view.icons[i])+0xcf4))continue;
        // The draw queue copies the descriptor. The native widget keeps its own atlas and lifetime.
        memcpy(replacementQuad,holders[state],sizeof(replacementQuad));
        at<void*>(replacementQuad,8)=lobbyEmblemTextures[i].resource;
        replacementHolders[state]=replacementQuad;
        replacementTiles[state*5]=tiles[state*5];
        replacementTiles[state*5+3]=replacementTiles[state*5+4]=32;
        holders=replacementHolders;
        tiles=replacementTiles;
        break;
    }
    reinterpret_cast<NativeLobbyIconDraw>(0x50cd10)(renderer,origin,bounds,color,mode,rotation,opacity,holders,tiles,state);
}

static bool installLobbyCountryIconDraw() {
    BYTE expected[]={0xe8,0x0e,0x60,0,0},replacement[]={0xe8,0,0,0,0};
    auto site=reinterpret_cast<BYTE*>(0x506cfd);
    *reinterpret_cast<LONG*>(replacement+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&drawLobbyCountryIcon)-site-5);
    return patchBytes(site,expected,replacement,5);
}

static unsigned prepareLobbyEmblem(unsigned index,const logh::LobbyCountry& row) {
    auto& entry=lobbyEmblemTextures[index];
    logh::CountryImageData image;
    std::uint64_t epoch=0;
    AcquireSRWLockShared(&lobbyCountryLock);
    const auto& view=lobbyCountryWindow;
    const auto& page=lobbyCountryInbox.published;
    const bool supported=lobbyEmblemSupported && lobbyEmblemProbeSession==view.session;
    if(page.session==view.session && page.offset==view.offset && page.generation==view.page.generation &&
        lobbyEmblemInbox.rows[index].id==row.id)image=lobbyEmblemInbox.rows[index];
    epoch=lobbyEmblemInbox.epoch;
    ReleaseSRWLockShared(&lobbyCountryLock);

    const auto pixels=image.pixels(false);
    auto device=*reinterpret_cast<void**>(0x2229400);
    lobbyEmblemVisible[index]=false;
    if(!pixels || !device) {
        releaseUploadedCountryTexture(entry);
    } else {
        const bool cached=entry.id==row.id && entry.epoch==epoch && entry.revision==image.revision && entry.device==device;
        if(cached || uploadCountryTexture(entry,pixels,device)) {
            entry.id=row.id;entry.epoch=epoch;entry.revision=image.revision;
            lobbyEmblemVisible[index]=true;
        }
    }
    return !supported && (row.nativePower==2 || row.nativePower==3) ? row.nativePower : image.nativeEmblem;
}
static void lobbyCountryText(unsigned id,int x,int y,int width,int height,const wchar_t* text,bool button=false) {
    auto& view=lobbyCountryWindow;auto widget=planetWidget(id);if(!widget || id>=114)return;
    if(view.widths[id]!=static_cast<unsigned>(width) || view.heights[id]!=static_cast<unsigned>(height) || view.buttons[id]!=button) {
        planetRect(id,x,y,width,height);
        reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x5029e0)(view.root,widget,button ? 2 : 3);
        if(button)planetButtonArt(widget,width,height);
        view.widths[id]=width;view.heights[id]=height;view.buttons[id]=button;
    }
    planetCell(id,text);planetEnabled(id,true);
    reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502eb0)(view.root,widget,1);
}
static void hideLobbyCountryWindow() {
    auto& view=lobbyCountryWindow;
    if(view.root && view.gui && at<void*>(view.gui,4+planetWindowId*4)==view.root) {
        for(unsigned i=0;i<6;++i)for(void* widget:{view.icons[i],view.bars[i]})if(widget)
            reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502eb0)(view.root,widget,0);
        if(view.backdrop)reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502eb0)(view.root,view.backdrop,0);
        reinterpret_cast<void(__thiscall*)(void*)>(0x506920)(view.root);
    }
    view.active=false;resetLobbyCountries();
}
static unsigned lobbyCountryRgb(const logh::LobbyCountry& row,unsigned emblem) {
    if(row.hasRgb)return row.rgb;
    if(emblem==2 || emblem==3) {
        const auto color=reinterpret_cast<const BYTE*>(0x771f68)+emblem*6;
        return (color[0]<<16)|(color[1]<<8)|color[2];
    }
    return 0xc0c0c0;
}
static const logh::LobbyCountry* selectedLobbyCountry() {
    auto& view=lobbyCountryWindow;
    for(unsigned i=0;i<view.page.count;++i)if(view.page.rows[i].id==view.country)return view.page.rows+i;
    return nullptr;
}
static bool lobbyCountryEligible(const logh::LobbyCountry* row) {
    auto& view=lobbyCountryWindow;if(!row)return false;
    if(view.mode==1)return true;
    return (row->nativePower==2 || row->nativePower==3) && (row->eligibility&(view.mode==2 ? 1u : 2u));
}
static void renderLobbyCountries() {
    auto& view=lobbyCountryWindow;wchar_t text[160]={};
    const bool listing=view.mode==1;
    lobbyCountryText(0,184,91,640,26,listing ? L"세션 선택 · 국가 현황" : view.mode==2 ? L"캐릭터 생성 · 국가 선택" : L"캐릭터 추첨 · 국가 선택");
    lobbyCountryText(81,184,383,640,22,L"막대: 전체 정규 거점 중 비율 · 등록: 이 세션의 캐릭터 수");
    for(unsigned i=0;i<3;++i) {
        const unsigned index=view.sessionOffset+i;const bool present=listing && index<view.sessions.count;
        if(present) {
            const auto& session=view.sessions.rows[index];
            lobbyCountryText(10+i,184,130+i*55,192,46,session.name,true);planetSelected(10+i,session.id==view.session);
        } else reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502eb0)(view.root,planetWidget(10+i),0);
    }
    if(listing) {
        lobbyCountryText(29,184,302,92,32,L"이전 세션",true);planetEnabled(29,view.sessionOffset>0);
        lobbyCountryText(30,282,302,92,32,L"다음 세션",true);planetEnabled(30,view.sessionOffset+3<view.sessions.count);
        if(view.page.session && view.page.generation)swprintf_s(text,L"국가 %u개 · 경과 %u일",view.page.total,view.page.seconds/86400);
        else wcscpy_s(text,L"현재 세션 집계 조회 중");
        lobbyCountryText(100,184,340,196,22,text);
        if(view.page.session && view.page.generation)swprintf_s(text,L"정규 거점 %u개",view.page.totalBases);
        else text[0]=0;
        lobbyCountryText(101,184,363,196,18,text);
    }
    for(unsigned i=0;i<6;++i) {
        const bool present=view.page.session==view.session && i<view.page.count;
        lobbyEmblemVisible[i]=false;
        const unsigned button=72+i;const int y=130+static_cast<int>(i)*45;
        for(unsigned id:{button,40+i*2,41+i*2})reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502eb0)(view.root,planetWidget(id),present ? 1 : 0);
        reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502eb0)(view.root,planetWidget(60+i),0);
        for(void* widget:{view.icons[i],view.bars[i]})reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502eb0)(view.root,widget,present ? 1 : 0);
        if(!present)continue;
        const auto& row=view.page.rows[i];const int x=listing ? 390 : 184,width=listing ? 260 : 466;
        auto label=logh::fitCountryLabel(row.name,listing ? 38 : 64);
        lobbyCountryText(button,x+38,y,width,30,label.c_str(),true);planetSelected(button,row.id==view.country);
        swprintf_s(text,L"거점 %u",row.bases);lobbyCountryText(40+i*2,700,y,126,16,text);
        swprintf_s(text,L"등록 %u명",row.characters);lobbyCountryText(41+i*2,700,y+16,126,16,text);
        const unsigned emblem=prepareLobbyEmblem(i,row);
        const unsigned rgb=lobbyCountryRgb(row,emblem);const float red=((rgb>>16)&255)/255.f,green=((rgb>>8)&255)/255.f,blue=(rgb&255)/255.f;
        int rect[]={x,y,0,30,30};reinterpret_cast<void(__thiscall*)(void*,void*,const int*)>(0x502940)(view.root,view.icons[i],rect);
        unsigned art[]={reinterpret_cast<unsigned>("/../data/image/Field/icon_country32.tga"),emblem==3 ? 32u : 0u,0,32,32};
        for(unsigned state=0;state<4;++state)reinterpret_cast<void(__thiscall*)(void*,void*,const void*,int)>(0x503920)(view.root,view.icons[i],art,state);
        reinterpret_cast<void(__thiscall*)(void*,void*,float,float,float,float)>(0x502f40)(view.root,view.icons[i],red,green,blue,1.f);
        if(!lobbyEmblemVisible[i] && !emblem) {
            reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502eb0)(view.root,view.icons[i],0);
            lobbyCountryText(60+i,x,y,30,30,L"◇");
            reinterpret_cast<void(__thiscall*)(void*,void*,float,float,float,float)>(0x502f40)(view.root,planetWidget(60+i),red,green,blue,1.f);
        }
        const int barWidth=view.page.totalBases ? static_cast<int>(static_cast<unsigned long long>(width)*row.bases/view.page.totalBases) : 0;
        int barRect[]={x+38,y+34,0,barWidth,3};
        reinterpret_cast<void(__thiscall*)(void*,void*,const int*)>(0x502940)(view.root,view.bars[i],barRect);
        reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502eb0)(view.root,view.bars[i],barWidth ? 1 : 0);
        reinterpret_cast<void(__thiscall*)(void*,void*,float,float,float,float)>(0x502f40)(view.root,view.bars[i],red,green,blue,1.f);
    }
    const auto selected=selectedLobbyCountry();
    if(view.selecting)wcscpy_s(text,L"선택한 국가를 확인하고 있습니다.");
    else if(view.selectionError)wcscpy_s(text,L"국가 선택을 확인하지 못했습니다. 다시 선택하세요.");
    else if(selected)swprintf_s(text,L"%s  ·  국가 ID %u%s",selected->name,selected->id,
        view.mode!=1 && !lobbyCountryEligible(selected) ? L"  ·  현재 참가 준비 중" : L"");
    else wcscpy_s(text,!view.page.session ? L"선택한 세션의 국가 정보를 불러오는 중입니다." :
        !view.page.generation ? L"이 세션은 더 이상 참가할 수 없습니다. 다른 세션을 선택하세요." : L"국가를 선택하면 이름 전체와 참가 가능 여부를 확인할 수 있습니다.");
    lobbyCountryText(80,184,408,640,26,text);
    lobbyCountryText(23,390,440,100,30,L"이전 국가",true);planetEnabled(23,view.offset>0);
    lobbyCountryText(24,500,440,100,30,L"다음 국가",true);planetEnabled(24,view.page.session && view.offset+view.page.count<view.page.total);
    lobbyCountryText(25,610,440,100,30,L"새로고침",true);
    lobbyCountryText(26,720,477,100,42,listing ? L"세션 선택" : L"국가 선택",true);
    planetEnabled(26,!view.selecting && (listing ? view.page.session==view.session && view.page.generation>0 : lobbyCountryEligible(selected)));
    lobbyCountryText(27,184,477,192,42,L"돌아가기",true);
}
static bool openLobbyCountries(void* controller,unsigned mode) {
    auto& view=lobbyCountryWindow;auto gui=at<void*>(controller,12);auto world=*reinterpret_cast<void**>(0x7ccffc);
    if(!gui || !world || !createPlanetWindow(gui,false))return false;
    view.gui=gui;view.world=world;view.root=planetWindowRoot;view.mode=mode;view.active=true;
    view.offset=0;view.page={};view.rendered=~0u;view.pollAt=0;view.acceptAfter=GetTickCount64()+350;
    view.selecting=false;view.selectionError=false;
    memset(view.widths,0,sizeof(view.widths));memset(view.heights,0,sizeof(view.heights));
    AcquireSRWLockShared(&lobbyCountryLock);view.sessions=lobbyCountryInbox.sessions;ReleaseSRWLockShared(&lobbyCountryLock);
    if(mode==1) {view.session=view.sessions.count ? view.sessions.rows[0].id : 0;view.country=0;view.sessionOffset=0;}
    else {view.session=*reinterpret_cast<unsigned*>(0x2216c3c)&65535;
        view.country=mode==3 && view.lotteryCountry ? view.lotteryCountry : *reinterpret_cast<unsigned*>(mode==2 ? 0x2227f68 : 0x22283c0);}
    for(unsigned id=0;id<114;++id)if(auto widget=planetWidget(id)) {
        reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502eb0)(view.root,widget,0);planetEnabled(id,false);
    }
    for(int id=0;id<planetDecorationCount;++id) {
        auto widget=reinterpret_cast<void*(__thiscall*)(void*,int,int)>(0x502780)(view.root,2,id);
        reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502eb0)(view.root,widget,0);
    }
    if(planetDirectoryList)reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502eb0)(view.root,planetDirectoryList,0);
    for(unsigned i=0;i<6;++i) {
        // Native lookup returns a shared placeholder until the slot has been initialized.
        view.icons[i]=reinterpret_cast<void*(__thiscall*)(void*,int,int)>(0x502780)(view.root,2,120+i);
        if(!view.icons[i] || !at<BYTE>(view.icons[i],8)) {
            unsigned art[20]={};for(unsigned state=0;state<4;++state) {
                art[state*5]=reinterpret_cast<unsigned>("/../data/image/Field/icon_country32.tga");art[state*5+3]=art[state*5+4]=32;
            }
            view.icons[i]=reinterpret_cast<void*(__thiscall*)(void*,int,int,const void*)>(0x503a10)(view.root,2,120+i,art);
        }
        view.bars[i]=reinterpret_cast<void*(__thiscall*)(void*,int,int)>(0x502780)(view.root,2,130+i);
        if(!view.bars[i] || !at<BYTE>(view.bars[i],8)) {
            view.bars[i]=reinterpret_cast<void*(__thiscall*)(void*,int,int,const void*)>(0x503a10)(view.root,2,130+i,nullptr);
            unsigned fill[45]={};for(unsigned tile=0;tile<9;++tile) {
                fill[tile*5]=reinterpret_cast<unsigned>(planetPartsPath);fill[tile*5+1]=470;fill[tile*5+2]=286;fill[tile*5+3]=fill[tile*5+4]=1;
            }
            for(unsigned state=0;state<4;++state)reinterpret_cast<void(__thiscall*)(void*,void*,const void*,int,int,int)>(0x502fe0)(view.root,view.bars[i],fill,3,3,state);
        }
    }
    // Decorations paint from high to low; the original backing surface would cover the added icons.
    view.backdrop=reinterpret_cast<void*(__thiscall*)(void*,int,int)>(0x502780)(view.root,2,149);
    if(!view.backdrop || !at<BYTE>(view.backdrop,8)) {
        view.backdrop=reinterpret_cast<void*(__thiscall*)(void*,int,int,const void*)>(0x503a10)(view.root,2,149,nullptr);
        unsigned fill[45]={};for(unsigned tile=0;tile<9;++tile) {
            fill[tile*5]=reinterpret_cast<unsigned>(planetPartsPath);fill[tile*5+1]=470;fill[tile*5+2]=286;fill[tile*5+3]=fill[tile*5+4]=1;
        }
        for(unsigned state=0;state<4;++state)reinterpret_cast<void(__thiscall*)(void*,void*,const void*,int,int,int)>(0x502fe0)(view.root,view.backdrop,fill,3,3,state);
    }
    int backingRect[]={178,106,0,650,421};
    reinterpret_cast<void(__thiscall*)(void*,void*,const int*)>(0x502940)(view.root,view.backdrop,backingRect);
    reinterpret_cast<void(__thiscall*)(void*,void*,float,float,float,float)>(0x502f40)(view.root,view.backdrop,.015f,.035f,.065f,.97f);
    reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502eb0)(view.root,view.backdrop,1);
    if(auto root=at<void*>(gui,4+(mode==1 ? 0x36 : mode==2 ? 0x39 : 0x38)*4)) {
        // Hide and disable input without closing the native window and losing its parent placement.
        reinterpret_cast<void(__thiscall*)(void*,int)>(0x502ea0)(root,0);
        reinterpret_cast<void(__thiscall*)(void*,int)>(0x5024b0)(root,0);
    }
    resetLobbyCountries();renderLobbyCountries();
    reinterpret_cast<void(__thiscall*)(void*,int)>(0x506280)(view.root,12);
    reinterpret_cast<void(__thiscall*)(void*,void*)>(0x50beb0)(gui,view.root);return true;
}
static bool lobbyCountryClick(unsigned id) {
    BYTE event[52]={};return reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,void*,int)>(0x5015f0)
        (lobbyCountryWindow.root,2,planetWidget(id),event,0)!=0;
}
static bool lotteryCountryButton(void* controller) {
    auto& view=lobbyCountryWindow;auto gui=at<void*>(controller,12);
    auto root=gui ? at<void*>(gui,4+0x38*4) : nullptr;if(!root)return false;
    auto old=reinterpret_cast<void*(__thiscall*)(void*,int,int)>(0x502780)(root,1,2);
    auto other=reinterpret_cast<void*(__thiscall*)(void*,int,int)>(0x502780)(root,1,3);
    if(!old || !other)return false;
    auto widget=reinterpret_cast<void*(__thiscall*)(void*,int,int)>(0x502780)(root,1,20);
    if(!widget || !at<BYTE>(widget,8)) {
        widget=reinterpret_cast<void*(__thiscall*)(void*,int,int,const void*)>(0x503a10)(root,1,20,nullptr);
        if(!widget)return false;
    }
    // Slot 20 is an existing native caption; it still needs the button layout.
    if(view.lotteryButton!=widget) {
        int rect[5];memcpy(rect,static_cast<BYTE*>(old)+0x20,sizeof(rect));
        rect[3]=at<int>(other,0x20)+at<int>(other,0x2c)-rect[0];
        if(rect[3]<32 || rect[3]>300 || rect[4]<12 || rect[4]>60)return false;
        rect[1]-=4;rect[4]=28;
        reinterpret_cast<void(__thiscall*)(void*,void*,const int*)>(0x502940)(root,widget,rect);
        reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x5029e0)(root,widget,2);
        planetButtonArt(widget,rect[3],rect[4],root);
        reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502b00)(root,widget,1);
        reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502b30)(root,widget,1);
        reinterpret_cast<void(__thiscall*)(void*)>(0x503910)(root);
        view.lotteryButton=widget;
    }
    for(void* native:{old,other}) {
        reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x5024e0)(root,native,0);
        reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502eb0)(root,native,0);
    }
    AcquireSRWLockShared(&lobbyCountryLock);
    const bool pending=lobbyCountryInbox.pending;
    if(lobbyCountryInbox.published.session==view.session && !lobbyCountryInbox.published.offset)view.page=lobbyCountryInbox.published;
    ReleaseSRWLockShared(&lobbyCountryLock);
    const auto now=GetTickCount64();
    if(now>=view.pollAt && !pending && requestLobbyCountries(view.world,view.session,0))view.pollAt=now+5000;
    view.country=view.lotteryCountry ? view.lotteryCountry : *reinterpret_cast<unsigned*>(0x22283c0);const auto selected=selectedLobbyCountry();
    const auto name=selected ? logh::fitCountryLabel(selected->name,14) : std::wstring(L"국가 선택");
    reinterpret_cast<void(__thiscall*)(void*,void*,const wchar_t*)>(0x503560)(root,widget,name.c_str());
    reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x5024e0)(root,widget,1);
    reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502eb0)(root,widget,1);
    BYTE event[52]={};
    if(reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,void*,int)>(0x5015f0)(root,2,widget,event,0))
        return openLobbyCountries(controller,3);
    // The original controller polls its two hidden country buttons directly. Consume those hits here too.
    for(void* native:{old,other}) {
        reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x5024e0)(root,native,1);
        bool clicked=reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,void*,int)>(0x5015f0)(root,2,native,event,1)!=0;
        reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x5024e0)(root,native,0);
        if(clicked)return openLobbyCountries(controller,3);
    }
    return false;
}
static bool updateLobbyCountryWindow(void* controller) {
    auto& view=lobbyCountryWindow;const unsigned stage=at<unsigned>(controller,4);auto gui=at<void*>(controller,12);
    if(view.gui && view.gui!=gui) {view={};resetLobbyCountries(true);resetLobbyCountrySelection();}
    const auto now=GetTickCount64();
    if(view.active && ((view.mode==1 && stage!=0x2f) || (view.mode==2 && stage!=0x55) || (view.mode==3 && stage!=0x46)))hideLobbyCountryWindow();
    if(!view.active) {
        if(stage==0x16 || stage==0x40 || stage==0x2f) {
            // The game connection has closed; sending cancel now would target the new lobby connection.
            resetLobbyCountrySelection();
            view.selectionBound=false;view.lotteryChosen=false;view.lotteryCountry=0;
        }
        if(stage==0x46 && view.lotteryChosen && lotteryCountryButton(controller))return true;
        const bool enter=stage==0x2f || stage==0x55 || (stage==0x46 && !view.lotteryChosen);
        if(enter && !openLobbyCountries(controller,stage==0x2f ? 1 : stage==0x55 ? 2 : 3))return false;
        if(!view.active) {view.stage=stage;return false;}
    }
    if(view.selecting) {
        logh::LobbyCountrySelection choice;ULONGLONG selectionSentAt=0;
        AcquireSRWLockShared(&lobbyCountryLock);
        choice=lobbyCountrySelectionInbox.choice;selectionSentAt=lobbyCountrySelectionInbox.sentAt;
        ReleaseSRWLockShared(&lobbyCountryLock);
        if(choice.complete && choice.session==view.session && choice.country==view.country && choice.mode==view.mode+6) {
            view.selecting=false;
            if(choice.accepted) {
                const unsigned mode=view.mode;view.selectionBound=true;hideLobbyCountryWindow();resetLobbyCountrySelection();
                // This value selects the native creation preset. The server retains actual membership.
                if(mode==2) {
                    *reinterpret_cast<unsigned*>(0x2227f68)=choice.power;
                    reinterpret_cast<void(__cdecl*)(int)>(0x595ec0)(0x16);reinterpret_cast<void(__cdecl*)()>(0x5960b0)();
                } else {
                    view.lotteryCountry=choice.country;*reinterpret_cast<unsigned*>(0x22283c0)=choice.power;view.lotteryChosen=true;
                    if(auto root=at<void*>(gui,4+0x38*4)) {
                        reinterpret_cast<void(__thiscall*)(void*,int)>(0x506280)(root,12);
                        reinterpret_cast<void(__thiscall*)(void*,void*)>(0x50beb0)(gui,root);
                    }
                    reinterpret_cast<unsigned(__cdecl*)(int)>(0x597ff0)(1);
                }
                return true;
            }
            view.selectionError=true;renderLobbyCountries();
        } else if(now-selectionSentAt>=5000) {
            view.selecting=false;view.selectionError=true;
            requestLobbyCountrySelection(view.world,view.session,0,10);view.selectionBound=false;renderLobbyCountries();
        }
        if(view.selecting && !lobbyCountryClick(27))return true;
        if(view.selecting) {
            if(view.mode==3 && view.lotteryCountry) {
                view.country=view.lotteryCountry;
                view.selecting=requestLobbyCountrySelection(view.world,view.session,view.country,9);
                view.selectionError=!view.selecting;renderLobbyCountries();return true;
            }
            requestLobbyCountrySelection(view.world,view.session,0,10);view.selecting=false;
            // Leave the selector open after cancelling an in-flight request.
            view.selectionBound=false;view.acceptAfter=now+200;renderLobbyCountries();return true;
        }
    }
    for(auto icon:view.icons)if(icon && at<BYTE>(icon,8)) {
        auto holder=at<BYTE*>(icon,0xcf4);
        if(holder)neutralizeCountryResource(at<BYTE*>(holder,8));
    }
    unsigned sequence=0;bool pending=false;ULONGLONG sentAt=0;
    AcquireSRWLockShared(&lobbyCountryLock);
    sequence=lobbyCountryInbox.sequence;pending=lobbyCountryInbox.pending;sentAt=lobbyCountryInbox.sentAt;
    if(lobbyCountryInbox.published.session==view.session && lobbyCountryInbox.published.offset==view.offset)view.page=lobbyCountryInbox.published;
    ReleaseSRWLockShared(&lobbyCountryLock);
    if(view.page.session && view.page.generation && view.page.total && view.offset>=view.page.total) {
        view.offset=0;view.page={};view.pollAt=0;view.rendered=~0u;resetLobbyCountries();pending=false;
    }
    if(view.rendered!=sequence) {view.rendered=sequence;renderLobbyCountries();}
    requestLobbyEmblem(view.world,view.session,view.offset,now);
    if(now>=view.pollAt && !pending) {if(requestLobbyCountries(view.world,view.session,view.offset))view.pollAt=now+5000;}
    if(pending && now-sentAt>=5000) {
        lobbyCountryText(80,184,408,640,26,L"국가 정보를 불러오지 못했습니다. 새로고침으로 다시 시도하세요.");
    }
    if(now<view.acceptAfter)return true;
    auto refresh=[&]() {resetLobbyCountries();view.page={};view.pollAt=0;view.rendered=~0u;view.acceptAfter=now+200;};
    for(unsigned i=0;i<3;++i)if(view.mode==1 && view.sessionOffset+i<view.sessions.count && lobbyCountryClick(10+i)) {
        view.session=view.sessions.rows[view.sessionOffset+i].id;view.offset=0;view.country=0;refresh();return true;
    }
    for(unsigned i=0;i<view.page.count;++i)if(lobbyCountryClick(72+i)) {view.country=view.page.rows[i].id;renderLobbyCountries();view.acceptAfter=now+200;return true;}
    if(view.mode==1 && lobbyCountryClick(29) && view.sessionOffset>=3) {view.sessionOffset-=3;renderLobbyCountries();view.acceptAfter=now+200;return true;}
    if(view.mode==1 && lobbyCountryClick(30) && view.sessionOffset+3<view.sessions.count) {view.sessionOffset+=3;renderLobbyCountries();view.acceptAfter=now+200;return true;}
    if(lobbyCountryClick(23) && view.offset>=6) {view.offset-=6;refresh();return true;}
    if(lobbyCountryClick(24) && view.page.session && view.offset+view.page.count<view.page.total) {view.offset+=6;refresh();return true;}
    if(lobbyCountryClick(25)) {refresh();return true;}
    if(lobbyCountryClick(27)) {
        if(view.mode==3 && view.lotteryCountry) {
            view.country=view.lotteryCountry;view.selecting=requestLobbyCountrySelection(view.world,view.session,view.country,9);
            view.selectionError=!view.selecting;renderLobbyCountries();return true;
        }
        if(view.mode!=1 && view.selectionBound)requestLobbyCountrySelection(view.world,view.session,0,10);
        view.selectionBound=false;
        const unsigned mode=view.mode;hideLobbyCountryWindow();
        if(mode==1)at<unsigned>(controller,4)=0x16;
        // Use the native lobby-return path after cancelling this connection's uncommitted choice.
        else if(mode==2)at<unsigned>(controller,4)=0x6b;
        else {
            view.lotteryChosen=true;
            if(auto root=at<void*>(gui,4+0x38*4)) {
                reinterpret_cast<void(__thiscall*)(void*,int)>(0x506280)(root,12);
                reinterpret_cast<void(__thiscall*)(void*,void*)>(0x50beb0)(gui,root);
            }
        }
        return true;
    }
    if(lobbyCountryClick(26)) {
        const unsigned mode=view.mode;const auto selected=selectedLobbyCountry();
        if(mode==1 && view.page.session==view.session && view.page.generation) {
            *reinterpret_cast<unsigned*>(0x2216c3c)=view.session;at<unsigned>(controller,4)=*reinterpret_cast<unsigned*>(0x221670c);
            hideLobbyCountryWindow();return true;
        }
        if(mode!=1 && lobbyCountryEligible(selected)) {
            view.selecting=requestLobbyCountrySelection(view.world,view.session,selected->id,mode+6);
            view.selectionError=!view.selecting;renderLobbyCountries();
            return true;
        }
    }
    return true;
}
