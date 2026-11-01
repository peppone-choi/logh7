// 命令/提案 send window (design-proposals-orders.md). The CD's 命令/提案 tab sends search 29 but never draws its rows
// (005912e0), so when that request is seen this window opens instead: pick a target from the server's same-institution
// list (0x7f00 command 98 preview) and order or propose the target's resignation (0x0709); the server picks both cards.
// Author: Peppone Choi.
#pragma once
static bool proposalTabRequested=false;
static bool proposalCommand(unsigned command) {return command==98;}
static void openProposalWindow() {
    auto world=*reinterpret_cast<void**>(0x7ccffc);auto own=world ? at<void*>(world,8) : nullptr;
    if(!own || !at<BYTE>(own,0) || *reinterpret_cast<void**>(0x21aa888)!=own || !createTaxWindow())return;
    closePlanetInformation();auto& state=taxWindow;
    state.world=world;state.character=at<unsigned>(own,0x24);state.card=0;
    state.strategyActive=true;state.strategyCommand=98;state.strategyOutfit=0;state.strategyTarget=0;state.strategyBase=0;
    state.strategyValue=0;state.strategyField=0;state.strategyAmount=0;state.strategyArgument=0;state.strategyToken=0;
    state.visible=true;state.loaded=true;state.pending=false;strategyDirectory={};
    state.acceptAfter=GetTickCount64()+250;state.message=L"같은 스폿(우주에서는 같은 그리드)의 같은 기관 카드 보유자에게 보냅니다.";
    reinterpret_cast<void(__thiscall*)(void*,int)>(0x506280)(state.root,12);
    reinterpret_cast<void(__thiscall*)(void*,void*)>(0x50beb0)(state.manager,state.root);
    sendStrategyAction(true);
}
/** Called every GUI frame from updateTaxWindow: opens the window after the CD asked for its 命令/提案 tab. */
static void proposalWindowTick() {
    if(!proposalTabRequested || taxWindow.visible)return;
    proposalTabRequested=false;openProposalWindow();
}
/** Commit reply. SocialRejection ordinal+1. */
static bool readProposalAction(void* record) {
    auto& state=taxWindow;
    if(at<unsigned short>(record,4)!=0x7f00 || !state.visible || !state.strategyActive || state.strategyCommand!=98 ||
        !state.pending || at<BYTE>(record,16)==1 || at<unsigned>(record,0)!=state.character ||
        at<unsigned short>(record,6)!=98 || at<unsigned short>(record,32)!=state.strategyToken)return false;
    state.pending=false;unsigned reply=at<unsigned short>(record,10);
    state.message=reply==4 ? L"보낼 수 있는 같은 기관의 직무 카드가 없습니다." :
        reply==5 ? L"영향력이 부족합니다(1 필요)." :
        reply==6 ? L"상대가 범위 밖이거나 전술 중입니다." :
        reply==7 ? L"진행 중인 작업이 끝난 뒤 보내십시오." :
        reply==8 ? L"MCP가 부족합니다(40 필요)." :
        reply==9 ? L"상대가 하루 안에 같은 요청을 거절했습니다." :
        reply ? L"보낼 수 없습니다. 대상·카드·위치를 확인하세요." :
        L"보냈습니다. 상대의 직무 편지함에 도착합니다(MCP 40·영향력 1 사용).";
    memset(static_cast<BYTE*>(record)+4,0,32);return true;
}
static bool updateProposalAction() {
    auto& state=taxWindow;
    taxText(0,L"명령 / 제안");
    const StrategyDirectory::Person* target=nullptr;
    for(unsigned i=0;i<strategyDirectory.count;++i)if(strategyDirectory.people[i].id==state.strategyTarget)target=&strategyDirectory.people[i];
    wchar_t label[160];
    if(target)swprintf_s(label,L"상대: %ls (계급 %u)",target->name,target->rank);else swprintf_s(label,L"보낼 수 있는 상대가 없습니다.");
    taxText(1,label);
    taxText(2,L"내용: 직무 사임 (상대의 같은 기관 카드)");
    taxText(3,L"계급 같거나 높으면 명령");
    taxText(4,L"낮으면 제안 · 承認 시 실행");
    taxText(5,L"MCP 80을 반씩 분담 · 보낼 때 영향력 1");
    taxText(6,state.message);
    taxText(10,L"선택 항목");taxText(11,L"이전");taxText(12,L"다음");taxText(13,L"목록 갱신");taxText(14,L"보내기");taxText(15,L"닫기");
    taxEnabled(10,false);taxEnabled(11,!state.pending && strategyDirectory.count>1);taxEnabled(12,!state.pending && strategyDirectory.count>1);
    taxEnabled(13,!state.pending);taxEnabled(14,!state.pending && target!=nullptr);
    if(state.pending && GetTickCount64()-state.sentAt>10000)state.message=L"응답 대기 중 · 재접속 후 편지함을 확인하세요.";
    if(GetTickCount64()<state.acceptAfter)return true;
    BYTE event[52]={};
    for(int id=10;id<=15;++id)if(reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,void*,int)>(0x5015f0)(state.root,2,taxWidget(1,id),event,0)) {
        state.acceptAfter=GetTickCount64()+250;
        if(id==15)closeTaxWindow();
        else if(!state.pending) {
            if(id==11 || id==12)strategyNextPerson(id==11 ? -1 : 1);
            else if(id==13)sendStrategyAction(true);
            else if(id==14 && target) {state.strategyValue=0x0709;sendStrategyAction(false);state.strategyValue=0;}
        }
        break;
    }
    return true;
}
