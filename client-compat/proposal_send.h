// 命令/提案 send window (design-proposals-orders.md). The CD's 命令/提案 tab sends search 29 but never draws its rows
// (005912e0), so when that request is seen this window opens instead: pick a target from the server's same-institution
// list (0x7f00 command 98 preview) and order or propose the target's resignation (0x0709); the server picks both cards.
// Author: Peppone Choi.
#pragma once
static bool proposalTabRequested=false;
// strategyArgument: 0 resignation of the recipient's card, 1 dismissal of a third person's seat. In dismissal mode the
// directory lists seats (id = person*512+card) and strategyBase keeps the recipient.
static void sendProposal(bool preview,unsigned cursor=0) {
    auto& state=taxWindow;bool dismissal=state.strategyArgument==1;
    BYTE record[36]={};at<unsigned>(record,0)=state.character;
    at<unsigned short>(record,4)=0x7f00;at<unsigned short>(record,6)=98;
    at<unsigned short>(record,10)=static_cast<unsigned short>(preview ? (dismissal ? 0x0708 : 0) : dismissal ? 0x0708 : 0x0709);
    at<unsigned>(record,12)=preview ? cursor : dismissal ? state.strategyBase : state.strategyTarget;
    at<BYTE>(record,16)=preview ? 1 : 0;
    if(dismissal) {
        at<unsigned>(record,28)=preview ? state.strategyBase : state.strategyTarget/512;
        if(!preview)at<unsigned short>(record,24)=static_cast<unsigned short>(state.strategyTarget%512);
    }
    strategyRequestToken=strategyRequestToken%65535+1;state.strategyToken=strategyRequestToken;
    at<unsigned short>(record,32)=static_cast<unsigned short>(state.strategyToken);
    state.pending=true;state.sentAt=GetTickCount64();state.message=preview ? L"목록을 조회하고 있습니다." : L"서버에 요청했습니다.";
    strategyDirectory.receiving=false;
    reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(state.world,0,0x46,record);
}
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
    sendProposal(true);
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
        reply==13 ? L"상대가 파면할 수 없는 직무입니다." :
        reply ? L"보낼 수 없습니다. 대상·카드·위치를 확인하세요." :
        L"보냈습니다. 상대의 직무 편지함에 도착합니다(MCP 절반·영향력 1 사용).";
    memset(static_cast<BYTE*>(record)+4,0,32);return true;
}
static bool updateProposalAction() {
    auto& state=taxWindow;bool dismissal=state.strategyArgument==1;
    taxText(0,L"명령 / 제안");
    const StrategyDirectory::Person* row=nullptr;
    for(unsigned i=0;i<strategyDirectory.count;++i)if(strategyDirectory.people[i].id==state.strategyTarget)row=&strategyDirectory.people[i];
    wchar_t label[160];
    if(dismissal) {
        swprintf_s(label,L"받는 사람(파면 실행자): ID %u",state.strategyBase);taxText(1,label);
        if(row)swprintf_s(label,L"파면 대상: %ls · 직무 카드 %u",row->name,row->id%512);else swprintf_s(label,L"이 사람이 파면할 수 있는 직무가 없습니다.");
        taxText(2,label);
    } else {
        if(row)swprintf_s(label,L"상대: %ls (계급 %u)",row->name,row->rank);else swprintf_s(label,L"보낼 수 있는 상대가 없습니다.");
        taxText(1,label);taxText(2,L"내용: 직무 사임 (상대의 같은 기관 카드)");
    }
    taxText(3,L"계급 같거나 높으면 명령");
    taxText(4,L"낮으면 제안 · 承認 시 실행");
    taxText(5,dismissal ? L"MCP 160을 반씩 분담 · 보낼 때 영향력 1" : L"MCP 80을 반씩 분담 · 보낼 때 영향력 1");
    taxText(6,state.message);
    taxText(10,dismissal ? L"사임으로" : L"파면으로");taxText(11,L"이전");taxText(12,L"다음");taxText(13,L"목록 갱신");taxText(14,L"보내기");taxText(15,L"닫기");
    taxEnabled(10,!state.pending && (dismissal || row!=nullptr));taxEnabled(11,!state.pending && strategyDirectory.count>1);
    taxEnabled(12,!state.pending && strategyDirectory.count>1);taxEnabled(13,!state.pending);taxEnabled(14,!state.pending && row!=nullptr);
    if(state.pending && GetTickCount64()-state.sentAt>10000)state.message=L"응답 대기 중 · 재접속 후 편지함을 확인하세요.";
    if(GetTickCount64()<state.acceptAfter)return true;
    BYTE event[52]={};
    for(int id=10;id<=15;++id)if(reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,void*,int)>(0x5015f0)(state.root,2,taxWidget(1,id),event,0)) {
        state.acceptAfter=GetTickCount64()+250;
        if(id==15)closeTaxWindow();
        else if(!state.pending) {
            if(id==10) {
                // Switch mode: dismissal keeps the chosen recipient and lists the seats it may dismiss.
                if(!dismissal) {state.strategyBase=state.strategyTarget;state.strategyArgument=1;}
                else {state.strategyArgument=0;state.strategyTarget=state.strategyBase;}
                strategyDirectory={};sendProposal(true);
            }
            else if(id==11 || id==12)strategyNextPerson(id==11 ? -1 : 1);
            else if(id==13)sendProposal(true);
            else if(id==14 && row)sendProposal(false);
        }
        break;
    }
    return true;
}
