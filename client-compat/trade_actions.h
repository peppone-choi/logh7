// Trade window (design-trade.md): 航路貿易 39 picks an own base from the server's list; 船団貿易 40 sends the supplies
// to sell in the amount word; 関税率変更 17 and 外交 20 send the tariff or the negotiation in the value word. Replies carry
// the outcome in the argument word, a found value in the base word and 外交's report in the amount word. Author: Peppone Choi.
#pragma once
static bool tradeCommand(unsigned command) {return command==17 || command==20 || command==39 || command==40;}
static bool tradeCard(unsigned command,unsigned card) {
    if(command==39)return card==0;
    if(command==40)return card==44 || card==180;
    if(command==17)return card==8 || card==136;
    return card==5 || card==132 || card==14 || card==144;
}
static unsigned tradeValue,tradeStep,tradeCursor;
static bool tradeHasTargets(unsigned command) {return command==39 || command==40 || (command==20 && tradeValue==2);}
static const unsigned tradeAmounts[]={1000,5000,10000,50000};
static wchar_t tradeReport[160];
static void sendTradeAction(bool preview,unsigned cursor=0) {
    auto& state=taxWindow;unsigned command=state.strategyCommand;
    BYTE record[36]={};at<unsigned>(record,0)=state.character;
    at<unsigned short>(record,4)=0x7f00;at<unsigned short>(record,6)=static_cast<unsigned short>(command);
    at<unsigned short>(record,8)=static_cast<unsigned short>(state.card);
    if(command==20 || (!preview && command==17))at<unsigned short>(record,10)=static_cast<unsigned short>(tradeValue);
    at<unsigned>(record,12)=preview ? cursor : tradeHasTargets(command) ? state.strategyTarget : 0;at<BYTE>(record,16)=preview ? 1 : 0;
    if(preview)tradeCursor=cursor;
    if(!preview && command==40)at<unsigned short>(record,24)=static_cast<unsigned short>(tradeAmounts[tradeStep%4]);
    strategyRequestToken=strategyRequestToken%65535+1;state.strategyToken=strategyRequestToken;
    at<unsigned short>(record,32)=static_cast<unsigned short>(state.strategyToken);
    state.pending=true;state.sentAt=GetTickCount64();state.message=preview ? L"목록을 조회하고 있습니다." : L"서버에 요청했습니다.";
    strategyDirectory.receiving=false;
    reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(state.world,0,0x46,record);
}
static bool readTradeAction(void* record) {
    auto& state=taxWindow;
    if(at<unsigned short>(record,4)!=0x7f00 || !state.visible || !state.strategyActive || !tradeCommand(state.strategyCommand) ||
        !state.pending || at<BYTE>(record,16)==1 || at<unsigned>(record,0)!=state.character ||
        at<unsigned short>(record,6)!=state.strategyCommand || at<unsigned short>(record,32)!=state.strategyToken)return false;
    state.pending=false;unsigned reply=at<unsigned short>(record,10),outcome=at<unsigned>(record,20),command=state.strategyCommand;
    unsigned found=at<unsigned>(record,28);tradeReport[0]=0;
    if(!reply && outcome==1) {
        if(command==39)swprintf_s(tradeReport,L"하루 무역 이익(각 금고): %u",found);
        else if(command==40)swprintf_s(tradeReport,L"국고 수입(관세 뒤): %u",found);
        else if(command==17)swprintf_s(tradeReport,L"관세율: %u%%",found);
        else if(tradeValue==2) {unsigned mixed=at<unsigned short>(record,24);
            swprintf_s(tradeReport,L"선택국 수도 금고 %u만 · 지지율 %u · 치안 %u",found,mixed>>8,mixed&255);}
    }
    state.message=reply==4 ? L"이 명령을 쓸 카드가 아닙니다." : reply==6 ? L"이 위치에서 할 수 없습니다(정박한 자진영 거점·자기 수송함대)." :
        reply==7 ? L"이미 무역로가 있거나 행동할 수 없습니다." : reply==8 ? L"CP가 부족합니다." : reply==9 ? L"선단은 하루에 한 번만 무역합니다." :
        reply==10 ? L"페잔 중립을 침범해 페잔 상선과 투자가 끊겼습니다." : reply==11 ? L"물자나 국고가 부족합니다." : reply==13 ? L"대상이 맞지 않습니다." : reply ? L"실행할 수 없습니다." :
        outcome==1 ? (command==39 ? L"무역로를 열었습니다(게임 내 7일)." : command==40 ? L"선단 무역을 마쳤습니다." :
            command==17 ? L"관세율을 바꿨습니다." : L"교섭에 성공했습니다.") : L"교섭이 결렬됐습니다.";
    memset(static_cast<BYTE*>(record)+4,0,32);return true;
}
static void tradeNextTarget(int direction) {
    auto& state=taxWindow;auto& directory=strategyDirectory;
    if(!directory.count) {if(direction<0 && tradeCursor)sendTradeAction(true);return;}
    unsigned selected=0;
    for(unsigned i=0;i<directory.count;++i)if(directory.people[i].id==state.strategyTarget)selected=i;
    if(direction>0 && selected+1==directory.count && directory.count==200) {sendTradeAction(true,directory.people[selected].id);return;}
    if(direction<0 && selected==0 && tradeCursor) {sendTradeAction(true);return;}
    state.strategyTarget=directory.people[(selected+directory.count+direction)%directory.count].id;
    tradeReport[0]=0;
}
static bool updateTradeAction() {
    auto& state=taxWindow;unsigned command=state.strategyCommand;
    static const wchar_t* deals[]={L"통상 협정(30일 선단 이익 +20%)",L"회랑 통행(30일)",L"정보 매입(국고 1억, 상대 페잔 침범 90일 동안 무료)"};
    taxText(0,command==39 ? L"항로 무역" : command==40 ? L"선단 무역" : command==17 ? L"관세율 변경" : L"외교");
    const StrategyDirectory::Person* row=nullptr;
    for(unsigned i=0;i<strategyDirectory.count;++i)if(strategyDirectory.people[i].id==state.strategyTarget)row=&strategyDirectory.people[i];
    wchar_t label[160];
    if(command==39) {
        if(row)swprintf_s(label,L"무역 상대 거점: %ls (ID %u)",row->name,row->id);else swprintf_s(label,L"10칸 안에 무역할 자진영 거점이 없습니다.");
    } else if(command==40)swprintf_s(label,L"페잔에 팔 군수물자: %u (1.5배 국고 입금)",tradeAmounts[tradeStep%4]);
    else if(command==17)swprintf_s(label,L"자진영 관세율: %u%% (0~50)",tradeValue>50 ? 50 : tradeValue);
    else swprintf_s(label,L"교섭: %ls",deals[tradeValue%3]);
    taxText(1,label);
    if(tradeHasTargets(command) && command!=39) {
        if(row)swprintf_s(label,L"%ls: %ls (ID %u)",command==40 ? L"교역 상대국" : L"정보 대상국",row->name,row->id);
        else swprintf_s(label,L"선택할 외국이 없습니다. 목록을 갱신하십시오.");
        taxText(2,label);
    } else taxText(2,tradeReport);
    taxText(3,command==39 ? L"PCP 160" : L"MCP 320");
    taxText(4,command==39 ? L"매일 두 거점 금고에 작은 쪽 성인 인구÷1000" : command==40 ? L"선택국 관세를 해당 국가 국고에 지급합니다" :
        command==20 ? L"정치 능력 판정" : L"");
    taxText(5,tradeHasTargets(command) && command!=39 ? tradeReport : L"");taxText(6,state.message);
    taxText(10,command==17 ? L"+10%" : command==20 ? L"교섭 종류" : command==40 ? L"수량 변경" : L"-");
    taxText(11,L"이전");taxText(12,L"다음");taxText(13,L"목록 갱신");taxText(14,L"실행");taxText(15,L"닫기");
    taxEnabled(10,!state.pending && command!=39);taxEnabled(11,!state.pending && tradeHasTargets(command) && (strategyDirectory.count>1 || tradeCursor));
    taxEnabled(12,!state.pending && tradeHasTargets(command) && strategyDirectory.count>1);taxEnabled(13,!state.pending && tradeHasTargets(command));
    taxEnabled(14,!state.pending && (!tradeHasTargets(command) || row!=nullptr));
    if(state.pending && GetTickCount64()-state.sentAt>10000)state.message=L"응답 대기 중입니다.";
    if(GetTickCount64()<state.acceptAfter)return true;
    BYTE event[52]={};
    for(int id=10;id<=15;++id)if(reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,void*,int)>(0x5015f0)(state.root,2,taxWidget(1,id),event,0)) {
        state.acceptAfter=GetTickCount64()+250;
        if(id==15)closeTaxWindow();
        else if(!state.pending) {
            if(id==10) {
                if(command==17)tradeValue=(tradeValue+10)%60;
                else if(command==20) {tradeValue=(tradeValue+1)%3;state.strategyTarget=0;strategyDirectory={};tradeReport[0]=0;
                    if(tradeHasTargets(command))sendTradeAction(true);}
                else tradeStep=(tradeStep+1)%4;
            }
            else if(id==11 || id==12)tradeNextTarget(id==11 ? -1 : 1);
            else if(id==13) {strategyDirectory={};sendTradeAction(true);}
            else if(id==14)sendTradeAction(false);
        }
        break;
    }
    return true;
}
