// Imperial succession (SuccessionActions): 後継指名 233 on the emperor card picks an heir from the server's line of
// succession (target word); 継承拒否 234 on the personal card lets the runner-up of the last accession rise in revolt.
// Author: Peppone Choi.
#pragma once
static bool successionCommand(unsigned command) {return command==233 || command==234;}
static bool successionCard(unsigned command,unsigned card) {return command==233 ? card==1 : card==0;}
static void sendSuccessionAction(bool preview,unsigned cursor=0) {
    auto& state=taxWindow;unsigned command=state.strategyCommand;
    BYTE record[36]={};at<unsigned>(record,0)=state.character;
    at<unsigned short>(record,4)=0x7f00;at<unsigned short>(record,6)=static_cast<unsigned short>(command);
    at<unsigned short>(record,8)=static_cast<unsigned short>(state.card);
    at<unsigned>(record,12)=preview ? cursor : command==233 ? state.strategyTarget : 0;at<BYTE>(record,16)=preview ? 1 : 0;
    strategyRequestToken=strategyRequestToken%65535+1;state.strategyToken=strategyRequestToken;
    at<unsigned short>(record,32)=static_cast<unsigned short>(state.strategyToken);
    state.pending=true;state.sentAt=GetTickCount64();state.message=preview ? L"계승 서열을 조회하고 있습니다." : L"서버에 요청했습니다.";
    strategyDirectory.receiving=false;
    reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(state.world,0,0x46,record);
}
static bool readSuccessionAction(void* record) {
    auto& state=taxWindow;
    if(at<unsigned short>(record,4)!=0x7f00 || !state.visible || !state.strategyActive || !successionCommand(state.strategyCommand) ||
        !state.pending || at<BYTE>(record,16)==1 || at<unsigned>(record,0)!=state.character ||
        at<unsigned short>(record,6)!=state.strategyCommand || at<unsigned short>(record,32)!=state.strategyToken)return false;
    state.pending=false;unsigned reply=at<unsigned short>(record,10),command=state.strategyCommand;
    // SocialRejection ordinal + 1: CARD 4, BUSY 7, CP 8, STALE 10, TARGET 13.
    state.message=reply==4 ? (command==233 ? L"황제만 후계를 지명할 수 있습니다." : L"개인 카드로 실행하세요.") :
        reply==7 ? L"이미 반란 중이거나 행동할 수 없습니다." : reply==8 ? L"CP가 부족합니다." :
        reply==10 ? L"계승을 거부할 수 있는 2순위 후보가 아니거나 기한(즉위 후 7일)이 지났습니다." :
        reply==13 ? L"지명할 수 없는 인물입니다." : reply ? L"실행할 수 없습니다." :
        command==233 ? L"후계자를 지명했습니다." : L"계승을 거부하고 반란을 일으켰습니다. 수도를 7일 점거하면 즉위합니다.";
    memset(static_cast<BYTE*>(record)+4,0,32);return true;
}
static bool updateSuccessionAction() {
    auto& state=taxWindow;unsigned command=state.strategyCommand;
    taxText(0,command==233 ? L"후계 지명" : L"계승 거부");
    const StrategyDirectory::Person* row=nullptr;
    for(unsigned i=0;i<strategyDirectory.count;++i)if(strategyDirectory.people[i].id==state.strategyTarget)row=&strategyDirectory.people[i];
    wchar_t label[160];
    if(command==233) {
        if(row)swprintf_s(label,L"후계자: %ls (ID %u)",row->name,row->id);else swprintf_s(label,L"지명할 인물이 없습니다.");
    } else swprintf_s(label,L"새 황제의 즉위에 반대해 반란을 일으킵니다.");
    taxText(1,label);
    taxText(2,command==233 ? L"목록은 현재 계승 서열 순입니다. 지명된 후계자가 서열 1위가 됩니다." :
        L"직전 즉위의 2순위 후보만, 즉위 후 7일 안에 한 번 할 수 있습니다.");
    taxText(3,command==233 ? L"MCP 160" : L"PCP 640");
    taxText(4,command==233 ? L"" : L"현재 거점과 봉토가 반란 진영이 되고 맹주가 됩니다.");taxText(5,L"");taxText(6,state.message);
    taxText(10,L"-");taxText(11,L"이전");taxText(12,L"다음");taxText(13,L"목록 갱신");taxText(14,L"실행");taxText(15,L"닫기");
    bool listed=command==233;
    taxEnabled(10,false);taxEnabled(11,!state.pending && listed && strategyDirectory.count>1);
    taxEnabled(12,!state.pending && listed && strategyDirectory.count>1);taxEnabled(13,!state.pending && listed);
    taxEnabled(14,!state.pending && (!listed || row!=nullptr));
    if(state.pending && GetTickCount64()-state.sentAt>10000)state.message=L"응답 대기 중입니다.";
    if(GetTickCount64()<state.acceptAfter)return true;
    BYTE event[52]={};
    for(int id=10;id<=15;++id)if(reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,void*,int)>(0x5015f0)(state.root,2,taxWidget(1,id),event,0)) {
        state.acceptAfter=GetTickCount64()+250;
        if(id==15)closeTaxWindow();
        else if(!state.pending) {
            if(id==11 || id==12)strategyNextPerson(id==11 ? -1 : 1);
            else if(id==13) {strategyDirectory={};sendSuccessionAction(true);}
            else if(id==14)sendSuccessionAction(false);
        }
        break;
    }
    return true;
}
