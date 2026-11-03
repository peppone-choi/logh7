// Reconstructed ground battle window (design-ground-battle-window.md): reopened from the info menu's ground
// category while a battle runs on this grid. It borrows the shared command popup (taxWindow root) and sends the
// CD's unsent 0417 CommandAttackTroop / 0418 CommandStopTroop records through the native transport. Author: Peppone Choi.
#pragma once
static struct GroundBattleWindow {
    bool visible,pending; unsigned own,target,base,orderUnit,orderTarget; ULONGLONG acceptAfter,sentAt; const wchar_t* message;
} groundWindow={};
static bool groundWindowShown() {return groundWindow.visible;}
static bool groundWindowAvailable() {return groundPanel.activeBase && GetTickCount64()<groundPanel.activeUntil;}
static bool groundWindowSide(unsigned i,bool friendly) {
    auto own=groundPanel.world ? at<BYTE*>(groundPanel.world,8) : nullptr;
    return own && (groundPanelEmpire(groundPanel.rows[i].kind)==(own[0x28]==2))==friendly;
}
static void groundWindowCycle(unsigned& id,bool friendly,int step) {
    unsigned list[70],n=0,at=0;
    for (unsigned i=0;i<groundPanel.count;++i) if (groundWindowSide(i,friendly)) list[n++]=groundPanel.rows[i].id;
    if (!n) {id=0;return;}
    for (unsigned i=0;i<n;++i) if (list[i]==id) at=i;
    id=list[(at+n+(id ? step : 0))%n];
}
static const GroundPanelRow* groundWindowRow(unsigned id) {
    for (unsigned i=0;i<groundPanel.count;++i) if (groundPanel.rows[i].id==id) return groundPanel.rows+i;
    return nullptr;
}
static void closeGroundWindow() {
    if (groundWindow.visible && taxWindow.root) reinterpret_cast<void(__thiscall*)(void*,int)>(0x502ea0)(taxWindow.root,0);
    groundWindow.visible=false;
}
static bool openGroundWindow(void* gui) {
    if (!groundWindowAvailable() || taxWindow.visible) return false;
    taxWindow.manager=gui;
    if (!createTaxWindow()) return false;
    auto& w=groundWindow;w={};w.visible=true;w.base=groundPanel.activeBase;w.acceptAfter=GetTickCount64()+250;
    groundWindowCycle(w.own,true,0);groundWindowCycle(w.target,false,0);
    auto first=groundWindowRow(w.own);
    if (first && first->target) w.target=first->target;
    w.message=L"내 육전대와 공격할 적을 고른 뒤 「공격 지정」을 누르세요.";
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
    if (!groundWindowRow(w.own)) groundWindowCycle(w.own,true,0);
    if (!groundWindowRow(w.target)) groundWindowCycle(w.target,false,0);
    wchar_t name[32]={},text[180];pickerName(w.base,name);
    unsigned friends=0,foes=0;for (unsigned i=0;i<groundPanel.count;++i) (groundWindowSide(i,true) ? friends : foes)++;
    taxText(0,L"지상전");
    _snwprintf_s(text,_countof(text),_TRUNCATE,L"%ls · 아군 %u유닛 · 적군 %u유닛",name,friends,foes);taxText(1,text);
    taxText(2,L"◀▶ 왼쪽: 내 육전대 · 오른쪽: 공격할 적");
    auto row=groundWindowRow(w.own),enemy=groundWindowRow(w.target);
    wchar_t kind[32];
    if (row) {groundPanelName(row->kind,kind);_snwprintf_s(text,_countof(text),_TRUNCATE,L"%ls #%u 전력 %u%ls",kind,row->id,row->strength,row->target ? L" (지정됨)" : L"");}
    else wcscpy_s(text,L"내 육전대 없음");
    taxText(3,text);
    if (enemy) {groundPanelName(enemy->kind,kind);_snwprintf_s(text,_countof(text),_TRUNCATE,L"%ls #%u 전력 %u",kind,enemy->id,enemy->strength);}
    else wcscpy_s(text,L"적 육전대 없음");
    taxText(4,text);
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
    taxText(14,assigned ? L"지정 해제" : L"공격 지정");taxText(15,L"닫기");
    for (int i=10;i<=13;++i) taxEnabled(i,!w.pending);
    taxEnabled(14,!w.pending && row && enemy);
    if (GetTickCount64()<w.acceptAfter) return true;
    BYTE event[52]={};
    for (int i=10;i<=15;++i) if (reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,void*,int)>(0x5015f0)(taxWindow.root,2,taxWidget(1,i),event,0)) {
        w.acceptAfter=GetTickCount64()+200;
        if (i==15) closeGroundWindow();
        else if (i<=11) groundWindowCycle(w.own,true,i==10 ? -1 : 1);
        else if (i<=13) groundWindowCycle(w.target,false,i==12 ? -1 : 1);
        else if (row && enemy && !w.pending) sendGroundOrder(row->id,assigned ? 0 : enemy->id);
        break;
    }
    return true;
}
