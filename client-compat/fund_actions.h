// 資金投入 95 window (FundActions): the mode (local treasury / confidence box / support box) travels in the value word,
// the chosen row (governed planet, 1 for or 2 against, candidate) in the target and the money in the amount word.
// A reply carries the remaining private money in the base word. Author: Peppone Choi.
#pragma once
static unsigned fundMode,fundStep;
static const unsigned fundAmounts[]={1000,5000,10000,50000};
static wchar_t fundReport[160];
static void sendFundAction(bool preview,unsigned cursor=0) {
    auto& state=taxWindow;
    BYTE record[36]={};at<unsigned>(record,0)=state.character;
    at<unsigned short>(record,4)=0x7f00;at<unsigned short>(record,6)=95;at<unsigned short>(record,8)=static_cast<unsigned short>(state.card);
    at<unsigned short>(record,10)=static_cast<unsigned short>(fundMode);
    at<unsigned>(record,12)=preview ? cursor : state.strategyTarget;at<BYTE>(record,16)=preview ? 1 : 0;
    if(!preview)at<unsigned short>(record,24)=static_cast<unsigned short>(fundAmounts[fundStep%4]);
    strategyRequestToken=strategyRequestToken%65535+1;state.strategyToken=strategyRequestToken;
    at<unsigned short>(record,32)=static_cast<unsigned short>(state.strategyToken);
    state.pending=true;state.sentAt=GetTickCount64();state.message=preview ? L"목록을 조회하고 있습니다." : L"서버에 요청했습니다.";
    strategyDirectory.receiving=false;
    reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(state.world,0,0x46,record);
}
static bool readFundAction(void* record) {
    auto& state=taxWindow;
    if(at<unsigned short>(record,4)!=0x7f00 || !state.visible || !state.strategyActive || state.strategyCommand!=95 ||
        !state.pending || at<BYTE>(record,16)==1 || at<unsigned>(record,0)!=state.character ||
        at<unsigned short>(record,6)!=95 || at<unsigned short>(record,32)!=state.strategyToken)return false;
    state.pending=false;unsigned reply=at<unsigned short>(record,10),outcome=at<unsigned>(record,20);
    fundReport[0]=0;
    if(!reply && outcome==1)swprintf_s(fundReport,L"남은 사적 자금: %u",at<unsigned>(record,28));
    state.message=reply==7 ? L"구금 중이거나 행동할 수 없습니다." : reply==8 ? L"PCP가 부족합니다." :
        reply==11 ? L"사적 자금이 부족합니다." : reply==13 ? L"대상이 맞지 않습니다." : reply ? L"실행할 수 없습니다." :
        L"자금을 투입했습니다.";
    memset(static_cast<BYTE*>(record)+4,0,32);return true;
}
static bool updateFundAction() {
    auto& state=taxWindow;
    static const wchar_t* modes[]={L"지방 금고",L"신임 박스",L"지지 박스"};
    taxText(0,L"자금 투입");
    const StrategyDirectory::Person* row=nullptr;
    for(unsigned i=0;i<strategyDirectory.count;++i)if(strategyDirectory.people[i].id==state.strategyTarget)row=&strategyDirectory.people[i];
    wchar_t label[160];
    if(row)swprintf_s(label,L"%ls: %ls",modes[fundMode%3],row->name);
    else swprintf_s(label,L"%ls: %ls",modes[fundMode%3],fundMode==0 ? L"통치하는 행성이 없습니다." : L"대상이 없습니다.");
    taxText(1,label);
    swprintf_s(label,L"투입액: %u (사적 계좌에서)",fundAmounts[fundStep%4]);taxText(2,label);
    taxText(3,L"PCP 80");taxText(4,fundReport);
    taxText(5,fundMode==0 ? L"자기 통치 행성의 금고에 원조 자금을 넣습니다." : fundMode==1 ? L"현 황제·의장의 지지/불지지에 자금을 넣습니다." :
        L"차기 후보로 지지할 인물에 자금을 넣습니다.");
    taxText(6,state.message);
    taxText(10,L"대상 종류");taxText(11,L"이전");taxText(12,L"다음");taxText(13,L"금액 변경");taxText(14,L"실행");taxText(15,L"닫기");
    taxEnabled(10,!state.pending);taxEnabled(11,!state.pending && strategyDirectory.count>1);
    taxEnabled(12,!state.pending && strategyDirectory.count>1);taxEnabled(13,!state.pending);
    taxEnabled(14,!state.pending && row!=nullptr);
    if(state.pending && GetTickCount64()-state.sentAt>10000)state.message=L"응답 대기 중입니다.";
    if(GetTickCount64()<state.acceptAfter)return true;
    BYTE event[52]={};
    for(int id=10;id<=15;++id)if(reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,void*,int)>(0x5015f0)(state.root,2,taxWidget(1,id),event,0)) {
        state.acceptAfter=GetTickCount64()+250;
        if(id==15)closeTaxWindow();
        else if(!state.pending) {
            if(id==10) {fundMode=(fundMode+1)%3;strategyDirectory={};state.strategyTarget=0;sendFundAction(true);}
            else if(id==11 || id==12)strategyNextPerson(id==11 ? -1 : 1);
            else if(id==13)fundStep=(fundStep+1)%4;
            else if(id==14)sendFundAction(false);
        }
        break;
    }
    return true;
}
