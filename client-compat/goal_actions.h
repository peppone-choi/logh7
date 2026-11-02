// 国家目標 15 / 統治目標 21 window (GoalActions): the server lists enemy bases (15) or the planets this character governs
// (21); 統治目標 also sends the focus (1 production, 2 approval, 3 public order) in the value word. Author: Peppone Choi.
#pragma once
static bool goalCommand(unsigned command) {return command==15 || command==21;}
static unsigned goalFocus=1;
static void sendGoalAction(bool preview,unsigned cursor=0) {
    auto& state=taxWindow;
    BYTE record[36]={};at<unsigned>(record,0)=state.character;
    at<unsigned short>(record,4)=0x7f00;at<unsigned short>(record,6)=static_cast<unsigned short>(state.strategyCommand);
    at<unsigned short>(record,8)=static_cast<unsigned short>(state.card);
    if(!preview && state.strategyCommand==21)at<unsigned short>(record,10)=static_cast<unsigned short>(goalFocus);
    at<unsigned>(record,12)=preview ? cursor : state.strategyTarget;at<BYTE>(record,16)=preview ? 1 : 0;
    strategyRequestToken=strategyRequestToken%65535+1;state.strategyToken=strategyRequestToken;
    at<unsigned short>(record,32)=static_cast<unsigned short>(state.strategyToken);
    state.pending=true;state.sentAt=GetTickCount64();state.message=preview ? L"목록을 조회하고 있습니다." : L"서버에 요청했습니다.";
    strategyDirectory.receiving=false;
    reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(state.world,0,0x46,record);
}
static bool readGoalAction(void* record) {
    auto& state=taxWindow;
    if(at<unsigned short>(record,4)!=0x7f00 || !state.visible || !state.strategyActive || !goalCommand(state.strategyCommand) ||
        !state.pending || at<BYTE>(record,16)==1 || at<unsigned>(record,0)!=state.character ||
        at<unsigned short>(record,6)!=state.strategyCommand || at<unsigned short>(record,32)!=state.strategyToken)return false;
    state.pending=false;unsigned reply=at<unsigned short>(record,10);
    state.message=reply==4 ? L"이 명령을 쓸 직무 카드가 아닙니다." : reply==7 ? L"구금 중이거나 행동할 수 없습니다." :
        reply==8 ? L"MCP가 부족합니다." : reply==13 ? L"대상이 맞지 않습니다." : reply ? L"실행할 수 없습니다." :
        state.strategyCommand==15 ? L"국가 목표를 정했습니다. 점령한 지휘관은 공적을 받습니다." : L"통치 목표를 정했습니다.";
    memset(static_cast<BYTE*>(record)+4,0,32);return true;
}
static bool updateGoalAction() {
    auto& state=taxWindow;unsigned command=state.strategyCommand;
    static const wchar_t* focuses[]={L"생산",L"정부 지지",L"치안"};
    taxText(0,command==15 ? L"국가 목표" : L"통치 목표");
    const StrategyDirectory::Person* row=nullptr;
    for(unsigned i=0;i<strategyDirectory.count;++i)if(strategyDirectory.people[i].id==state.strategyTarget)row=&strategyDirectory.people[i];
    wchar_t label[160];
    if(row)swprintf_s(label,L"%ls: %ls (ID %u)",command==15 ? L"목표 거점" : L"통치 행성",row->name,row->id);
    else swprintf_s(label,command==15 ? L"적 거점 목록이 없습니다." : L"통치하는 행성이 없습니다.");
    taxText(1,label);
    if(command==21) {swprintf_s(label,L"중점: %ls (버튼으로 변경)",focuses[(goalFocus-1)%3]);taxText(2,label);}
    else taxText(2,L"점령하면 그 거점을 차지한 지휘관에게 공적 100");
    taxText(3,command==15 ? L"MCP 320" : L"MCP 80");
    taxText(4,command==21 ? L"하루마다 생산 +10% / 지지 +1 / 치안 +1" : L"");taxText(5,L"");taxText(6,state.message);
    taxText(10,command==21 ? L"중점 변경" : L"-");taxText(11,L"이전");taxText(12,L"다음");taxText(13,L"목록 갱신");taxText(14,L"실행");taxText(15,L"닫기");
    taxEnabled(10,!state.pending && command==21);taxEnabled(11,!state.pending && strategyDirectory.count>1);
    taxEnabled(12,!state.pending && strategyDirectory.count>1);taxEnabled(13,!state.pending);
    taxEnabled(14,!state.pending && row!=nullptr);
    if(state.pending && GetTickCount64()-state.sentAt>10000)state.message=L"응답 대기 중입니다.";
    if(GetTickCount64()<state.acceptAfter)return true;
    BYTE event[52]={};
    for(int id=10;id<=15;++id)if(reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,void*,int)>(0x5015f0)(state.root,2,taxWidget(1,id),event,0)) {
        state.acceptAfter=GetTickCount64()+250;
        if(id==15)closeTaxWindow();
        else if(!state.pending) {
            if(id==10)goalFocus=goalFocus%3+1;
            else if(id==11 || id==12)strategyNextPerson(id==11 ? -1 : 1);
            else if(id==13) {strategyDirectory={};sendGoalAction(true);}
            else if(id==14)sendGoalAction(false);
        }
        break;
    }
    return true;
}
