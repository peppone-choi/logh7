// Reconstructed ground battle window (design-ground-battle-window.md): reopened from the info menu's ground
// category while a battle runs on this grid. It borrows the shared command popup (taxWindow root) and sends the
// CD's unsent 0417 CommandAttackTroop / 0418 CommandStopTroop records through the native transport. Author: Peppone Choi.
#pragma once
static struct GroundBattleWindow {
    bool visible,pending; unsigned own,target,base,orderUnit,orderTarget,commander; ULONGLONG acceptAfter,sentAt; const wchar_t* message;
} groundWindow={};
// Thumbnail/Unit it001~013 (64x36): crew 1/2, light 3/4, grenadier 5/6, armored 7/8, 擲弾兵教導 9, 薔薇の騎士 10.
// Faction pairs follow the art (empire dark/blue, alliance green/tan); the kind mapping is evidence:guess
// except 9/10 (user 2026-11-03). Server kinds: empire 0 light,1 grenadier,2 armored,3 crew,8 guard,9 教導;
// alliance 4 light,5 grenadier,6 armored,7 crew,10 薔薇の騎士.
static unsigned groundThumbnail(unsigned kind) {
    static const unsigned art[]={3,5,7,1,4,6,8,2,3,9,10};
    return kind<_countof(art) ? art[kind] : 0;
}
static void* groundArtRoot=nullptr;
static void* groundControlsRoot=nullptr;
static constexpr int groundArtBase=120;
static void createGroundArt() {
    if (groundArtRoot==taxWindow.root) return;
    groundArtRoot=taxWindow.root;
    static char paths[13][48];
    for (unsigned i=0;i<13;++i) {
        sprintf_s(paths[i],"/../data/image/Thumbnail/Unit/it%03u.tga",i+1);
        for (unsigned side=0;side<2;++side) {
            unsigned art[40]={};
            for (unsigned s=0;s<4;++s) {art[s*5]=reinterpret_cast<unsigned>(paths[i]);art[s*5+3]=64;art[s*5+4]=36;}
            auto image=reinterpret_cast<void*(__thiscall*)(void*,int,int,const void*)>(0x503a10)
                (taxWindow.root,2,groundArtBase+i*2+side,art);
            int rect[]={side ? 716 : 430,264,0,64,36};
            reinterpret_cast<void(__thiscall*)(void*,void*,const int*)>(0x502940)(taxWindow.root,image,rect);
            reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502eb0)(taxWindow.root,image,0);
        }
    }
}
static void showGroundArt(unsigned side,unsigned thumbnail) {
    for (unsigned i=0;i<13;++i) {
        auto image=taxWidget(2,groundArtBase+i*2+side);
        if (image) reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502eb0)(taxWindow.root,image,thumbnail==i+1 ? 1 : 0);
    }
}
static void hideGroundArt() {if (groundArtRoot && groundArtRoot==taxWindow.root) {showGroundArt(0,0);showGroundArt(1,0);}}
static bool groundWindowShown() {return groundWindow.visible;}
static bool groundWindowAvailable() {return groundPanel.activeBase && GetTickCount64()<groundPanel.activeUntil;}
static bool groundWindowSide(unsigned i,bool friendly) {
    auto own=groundPanel.world ? at<BYTE*>(groundPanel.world,8) : nullptr;
    return own && groundPanelFriendly(groundPanel.rows[i],own[0x28]==2)==friendly;
}
static void groundWindowCommander(int step) {
    unsigned ids[70]={},n=0,selected=0;
    for(unsigned i=0;i<groundPanel.count;++i)if(groundWindowSide(i,true) && !groundPanel.rows[i].reserve) {
        const unsigned id=groundPanel.rows[i].commander;bool found=false;
        for(unsigned j=0;j<n;++j)if(ids[j]==id)found=true;
        if(!found)ids[n++]=id;
    }
    if(!n){groundWindow.commander=0;groundWindow.own=0;return;}
    for(unsigned i=0;i<n;++i)if(ids[i]==groundWindow.commander)selected=i;
    groundWindow.commander=ids[(selected+n+step)%n];groundWindow.own=0;
}
static void groundWindowCycle(unsigned& id,bool friendly,int step) {
    unsigned list[70],n=0,at=0;
    for (unsigned i=0;i<groundPanel.count;++i) if (groundWindowSide(i,friendly) && !groundPanel.rows[i].reserve &&
        (!friendly || groundPanel.rows[i].commander==groundWindow.commander)) list[n++]=groundPanel.rows[i].id;
    if (!n) {id=0;return;}
    for (unsigned i=0;i<n;++i) if (list[i]==id) at=i;
    id=list[(at+n+(id ? step : 0))%n];
}
static const GroundPanelRow* groundWindowRow(unsigned id) {
    for (unsigned i=0;i<groundPanel.count;++i) if (groundPanel.rows[i].id==id) return groundPanel.rows+i;
    return nullptr;
}
static void closeGroundWindow() {
    hideGroundArt();
    if(groundWindow.visible && taxWindow.root) {
        for(int id : {7,8,16,17})if(auto widget=taxWidget(1,id))
            reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502eb0)(taxWindow.root,widget,0);
        for(int id=96;id<108;++id)if(auto widget=taxWidget(2,id))
            reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502eb0)(taxWindow.root,widget,0);
        int rect[]={234,218,0,550,30};auto widget=taxWidget(1,2);
        reinterpret_cast<void(__thiscall*)(void*,void*,const int*)>(0x502940)(taxWindow.root,widget,rect);
    }
    if (groundWindow.visible && taxWindow.root) reinterpret_cast<void(__thiscall*)(void*,int)>(0x502ea0)(taxWindow.root,0);
    groundWindow.visible=false;
}
static bool openGroundWindow(void* gui) {
    if (!groundWindowAvailable() || taxWindow.visible) return false;
    taxWindow.manager=gui;
    if (!createTaxWindow()) return false;
    createGroundArt();
    // Extra controls belong only to the ground view; shared tax/ship screens keep their original layout.
    if(groundControlsRoot!=taxWindow.root) {
        groundControlsRoot=taxWindow.root;
        for(int id : {7,8,16,17}) {
            if(id<=8)taxControl(id,id==7 ? 234 : 520,296,260,18,L"",false);
            else taxControl(id,id==16 ? 624 : 704,212,72,32,id==16 ? L"◀ 지휘관" : L"지휘관 ▶",true);
        }
    }
    for(int id : {7,8,16,17})reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502eb0)(taxWindow.root,taxWidget(1,id),1);
    for(int id=96;id<108;++id)if(auto widget=taxWidget(2,id))
        reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502eb0)(taxWindow.root,widget,1);
    int commanderRect[]={234,218,0,380,30};
    reinterpret_cast<void(__thiscall*)(void*,void*,const int*)>(0x502940)(taxWindow.root,taxWidget(1,2),commanderRect);
    auto& w=groundWindow;w={};w.visible=true;w.base=groundPanel.activeBase;w.acceptAfter=GetTickCount64()+250;
    auto self=groundPanel.world ? at<BYTE*>(groundPanel.world,8) : nullptr;
    w.commander=self ? at<unsigned>(self,0x24) : 0;
    bool commanding=false;
    for(unsigned i=0;i<groundPanel.count;++i)if(groundWindowSide(i,true) && groundPanel.rows[i].commander==w.commander && !groundPanel.rows[i].reserve)commanding=true;
    if(!commanding)groundWindowCommander(0);
    groundWindowCycle(w.own,true,0);groundWindowCycle(w.target,false,0);
    auto first=groundWindowRow(w.own);
    if (first && first->target) w.target=first->target;
    w.message=L"기존 지휘관을 선택해 담당 병력을 확인하세요.";
    reinterpret_cast<void(__thiscall*)(void*,int)>(0x506280)(taxWindow.root,12);
    reinterpret_cast<void(__thiscall*)(void*,void*)>(0x50beb0)(gui,taxWindow.root);
    return true;
}
static void sendGroundOrder(unsigned own,unsigned target) {
    auto world=groundPanel.world;auto self=world ? at<BYTE*>(world,8) : nullptr;
    auto service=*reinterpret_cast<BYTE**>(0x7c25f4);
    if (!self || !service) {groundWindow.message=L"통신 연결이 없습니다.";return;}
    // CD record (debug printer 004a1d10): +8 character, +0xc count, +0x10 units[32], +0x90 target.
    BYTE record[0x94]={};at<unsigned>(record,8)=at<unsigned>(self,0x24);record[0xc]=1;at<unsigned>(record,0x10)=own;
    if (target) at<unsigned>(record,0x90)=target;
    auto transport=at<void*>(service,0x44);auto table=at<void**>(transport,0);
    groundWindow.pending=true;groundWindow.sentAt=GetTickCount64();groundWindow.orderUnit=own;groundWindow.orderTarget=target;
    groundPanel.pollAt=0;
    groundWindow.message=target ? L"공격 대상 지정을 요청했습니다." : L"공격 대상 해제를 요청했습니다.";
    reinterpret_cast<unsigned(__thiscall*)(void*,void*,unsigned,void*)>(table[6])(transport,at<void*>(service,0x40),target ? 0x0417 : 0x0418,record);
}
// 0417/0418 replies reach the world dispatcher, which has no case for them; a refused reply carries time 1.
static void groundOrderReplied(unsigned opcode,const BYTE* record) {
    if (((opcode&65535)!=0x0417 && (opcode&65535)!=0x0418) || !groundWindow.pending) return;
    groundWindow.pending=false;
    bool refused=logh::infoWord(record,0)==1;
    groundWindow.message=refused ? L"지정할 수 없습니다(지휘권·위치·대상 확인)." : (opcode&65535)==0x0417 ? L"공격 대상을 지정했습니다." : L"공격 대상을 해제했습니다.";
    groundPanel.pollAt=0;
}
static bool updateGroundWindow() {
    auto& w=groundWindow;
    if (!w.visible) return false;
    if (!groundWindowAvailable() || groundPanel.activeBase!=w.base || !taxWindow.root || !at<BYTE>(taxWindow.root,4)) {closeGroundWindow();return false;}
    auto selected=groundWindowRow(w.own);
    if(!selected || selected->reserve || selected->commander!=w.commander) {
        groundWindowCycle(w.own,true,0);
        if(!w.own){groundWindowCommander(0);groundWindowCycle(w.own,true,0);}
    }
    if (!groundWindowRow(w.target)) groundWindowCycle(w.target,false,0);
    wchar_t name[32]={},text[180];pickerName(w.base,name);
    unsigned friends=0,foes=0,reserve=0;
    for (unsigned i=0;i<groundPanel.count;++i) if (groundPanel.rows[i].reserve) ++reserve; else (groundWindowSide(i,true) ? friends : foes)++;
    taxText(0,L"지상전");
    _snwprintf_s(text,_countof(text),_TRUNCATE,L"%ls · 교전 아군 %u · 적군 %u · 예비 %u%ls",
        name,friends,foes,reserve,groundPanel.partial ? L" · 일부 집계" : L"");taxText(1,text);
    wchar_t commanderName[32];groundCommanderName(w.commander,commanderName);
    auto self=at<BYTE*>(groundPanel.world,8);
    bool commanding=w.commander && self && w.commander==at<unsigned>(self,0x24);
    _snwprintf_s(text,_countof(text),_TRUNCATE,L"담당: %ls%ls",commanderName,commanding ? L" (본인 지휘)" : L" (관찰)");taxText(2,text);
    auto row=groundWindowRow(w.own),enemy=groundWindowRow(w.target);
    wchar_t kind[32];
    showGroundArt(0,row ? groundThumbnail(row->kind) : 0);showGroundArt(1,enemy ? groundThumbnail(enemy->kind) : 0);
    if (row) {groundPanelName(row->kind,kind);_snwprintf_s(text,_countof(text),_TRUNCATE,L"%ls #%u",kind,row->id);}
    else wcscpy_s(text,L"내 육전대 없음");
    taxText(3,text);
    if (enemy) {groundPanelName(enemy->kind,kind);_snwprintf_s(text,_countof(text),_TRUNCATE,L"%ls #%u",kind,enemy->id);}
    else wcscpy_s(text,L"적 육전대 없음");
    taxText(4,text);
    for(unsigned side=0;side<2;++side) {
        auto unit=side ? enemy : row;
        if(unit)_snwprintf_s(text,_countof(text),_TRUNCATE,L"전력 %u/1000 · 사기 %u%ls",unit->strength,unit->morale,unit->morale<=20 ? L" (혼란)" : L"");
        else text[0]=0;
        taxText(side ? 8 : 7,text);
    }
    if (row && row->target) _snwprintf_s(text,_countof(text),_TRUNCATE,L"현재 공격 대상 #%u",row->target);
    else wcscpy_s(text,L"공격 대상 없음(순서대로 공격)");
    taxText(5,text);
    // The CD has no world case for the 0417/0418 reply; confirm the order from the next roster instead.
    if (w.pending && GetTickCount64()-w.sentAt>=500) {
        auto ordered=groundWindowRow(w.orderUnit);
        if (ordered && ordered->target==w.orderTarget) {
            w.pending=false;w.message=w.orderTarget ? L"공격 대상을 지정했습니다." : L"공격 대상을 해제했습니다.";
        }
    }
    if (w.pending && GetTickCount64()-w.sentAt>8000) {w.pending=false;w.message=L"지정되지 않았습니다(지휘권·위치·대상 확인).";}
    taxText(6,w.message ? w.message : L"");
    taxText(10,L"◀");taxText(11,L"▶");taxText(12,L"◀");taxText(13,L"▶");
    bool assigned=row && enemy && row->target==enemy->id;
    taxText(14,!commanding ? L"담당 지휘관만 명령" : assigned ? L"지정 해제" : L"공격 지정");taxText(15,L"닫기");
    for (int i=10;i<=13;++i) taxEnabled(i,!w.pending);
    taxEnabled(14,!w.pending && commanding && row && enemy);
    taxEnabled(16,!w.pending);taxEnabled(17,!w.pending);
    if (GetTickCount64()<w.acceptAfter) return true;
    BYTE event[52]={};
    for (int i=10;i<=17;++i) if (reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,void*,int)>(0x5015f0)(taxWindow.root,2,taxWidget(1,i),event,0)) {
        w.acceptAfter=GetTickCount64()+200;
        if (i==15) closeGroundWindow();
        else if(i>=16){groundWindowCommander(i==16 ? -1 : 1);groundWindowCycle(w.own,true,0);}
        else if (i<=11) groundWindowCycle(w.own,true,i==10 ? -1 : 1);
        else if (i<=13) groundWindowCycle(w.target,false,i==12 ? -1 : 1);
        else if (row && enemy && commanding && !w.pending) sendGroundOrder(row->id,assigned ? 0 : enemy->id);
        break;
    }
    return true;
}
