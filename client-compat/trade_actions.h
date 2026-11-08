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
static unsigned tradeIntelStats;
static bool tradeQuotesSupported;
static struct {
    bool requested,available;
    unsigned amount,tariff;
    wchar_t currency[13],symbol[5];
} tradeQuote;
static wchar_t tradeReportCurrency[13],tradeReportSymbol[5];
static const wchar_t* tradeCurrency(const wchar_t* label) {return label[0] ? label : L"회계 단위";}
static void sendTradeAction(bool preview,unsigned cursor=0,bool quote=false) {
    auto& state=taxWindow;unsigned command=state.strategyCommand;
    BYTE record[36]={};at<unsigned>(record,0)=state.character;
    at<unsigned short>(record,4)=0x7f00;at<unsigned short>(record,6)=static_cast<unsigned short>(command);
    at<unsigned short>(record,8)=static_cast<unsigned short>(state.card);
    if(command==20 || (!preview && command==17))at<unsigned short>(record,10)=static_cast<unsigned short>(tradeValue);
    at<unsigned>(record,12)=preview && !quote ? cursor : tradeHasTargets(command) ? state.strategyTarget : 0;
    at<BYTE>(record,16)=preview ? 1 : 0;
    if(preview && !quote)tradeCursor=cursor;
    if(preview) {tradeQuote={};tradeQuote.requested=quote;tradeReport[0]=0;}
    else {
        memset(tradeReportCurrency,0,sizeof(tradeReportCurrency));
        memset(tradeReportSymbol,0,sizeof(tradeReportSymbol));
    }
    if(command==40 && (!preview || quote))
        at<unsigned short>(record,24)=static_cast<unsigned short>(tradeAmounts[tradeStep%4]);
    if(quote && command!=40)at<int>(record,20)=-1;
    if(!preview && tradeHasTargets(command) && tradeQuotesSupported)
        at<unsigned>(record,20)=tradeQuote.amount+(command==40 ? 0 : 1);
    strategyRequestToken=strategyRequestToken%65535+1;state.strategyToken=strategyRequestToken;
    at<unsigned short>(record,32)=static_cast<unsigned short>(state.strategyToken);
    state.pending=true;state.sentAt=GetTickCount64();
    state.message=quote ? L"견적을 조회하고 있습니다." : preview ? L"목록을 조회하고 있습니다." : L"서버에 요청했습니다.";
    strategyDirectory.receiving=false;
    reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(state.world,0,0x46,record);
}
static const wchar_t* tradeRejection(unsigned reply,unsigned command) {
    return reply==4 ? L"이 명령을 쓸 카드가 아닙니다." :
        reply==6 ? (command==40 ? L"자국 거점에 정박한 자기 수송함대가 필요합니다." : L"이 위치에서 할 수 없습니다.") :
        reply==7 ? L"이미 무역로가 있거나 행동할 수 없습니다." : reply==8 ? L"CP가 부족합니다." : reply==9 ? L"선단은 하루에 한 번만 무역합니다." :
        reply==10 ? L"페잔 중립을 침범해 페잔 상선과 투자가 끊겼습니다." : reply==11 ? L"물자나 국고가 부족합니다." :
        reply==13 ? L"대상이 맞지 않습니다." : reply==14 ? L"금액이 바뀌었습니다. 목록을 갱신해 새 견적을 받으세요." : L"실행할 수 없습니다.";
}
static bool readTradeAction(void* record) {
    auto& state=taxWindow;
    if(at<unsigned short>(record,4)!=0x7f00 || !state.visible || !state.strategyActive || !tradeCommand(state.strategyCommand) ||
        at<unsigned>(record,0)!=state.character ||
        at<unsigned short>(record,6)!=state.strategyCommand || at<unsigned short>(record,32)!=state.strategyToken)return false;
    unsigned flag=at<BYTE>(record,16),command=state.strategyCommand;
    if(tradeHasTargets(command) && flag>=16 && flag<=20) {
        if(!state.pending && flag==19)tradeQuotesSupported=true;
        else if(state.pending && tradeQuote.requested && !tradeQuote.available && flag<=18)
            readStrategyCurrency(record,flag,tradeQuote.currency,tradeQuote.symbol);
        else if(state.pending && command==20 && tradeQuote.available && flag<=18)
            readStrategyCurrency(record,flag,tradeReportCurrency,tradeReportSymbol);
        else if(state.pending && tradeQuote.requested && flag==20) {
            unsigned reply=at<unsigned short>(record,10);
            tradeQuote.amount=at<unsigned>(record,28);tradeQuote.tariff=at<unsigned short>(record,24);
            tradeQuote.available=!reply;
            state.pending=false;
            state.message=reply ? tradeRejection(reply,command) : L"견적을 확인한 뒤 실행하세요.";
        }
        memset(static_cast<BYTE*>(record)+4,0,32);return true;
    }
    if(!state.pending || at<BYTE>(record,16)==1)return false;
    state.pending=false;unsigned reply=at<unsigned short>(record,10),outcome=at<unsigned>(record,20);
    unsigned found=at<unsigned>(record,28);tradeReport[0]=0;
    if(!reply && outcome==1) {
        if(command==39)swprintf_s(tradeReport,L"현재 일일 이익(거점당): %u %ls",found,tradeCurrency(tradeQuote.currency));
        else if(command==40)swprintf_s(tradeReport,L"국고 입금: %u %ls",found,tradeCurrency(tradeQuote.currency));
        else if(command==17)swprintf_s(tradeReport,L"관세율: %u%%",found);
        else if(tradeValue==2) {
            tradeIntelStats=at<unsigned short>(record,24);
            swprintf_s(tradeReport,L"상대 수도 금고 약 %u만 %ls",found,tradeCurrency(tradeReportCurrency));
        }
    }
    if(tradeHasTargets(command) && reply!=8 && (reply || outcome==1))tradeQuote.available=false;
    state.message=reply ? tradeRejection(reply,command) :
        outcome==1 ? (command==39 ? L"무역로를 열었습니다(게임 내 7일)." : command==40 ? L"선단 무역을 마쳤습니다." :
            command==17 ? L"관세율을 바꿨습니다." :
            command==20 && tradeValue==2 && tradeQuotesSupported ?
                (tradeQuote.amount ? L"정보를 확보하고 국고에서 대금을 지불했습니다." : L"유출 정보를 확보했습니다. 정보 대금 무료.") :
                L"교섭에 성공했습니다.") :
        command==20 && tradeValue==2 ? L"교섭이 결렬됐습니다. 정보 대금은 청구되지 않았습니다." : L"교섭이 결렬됐습니다.";
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
    tradeReport[0]=0;tradeQuote={};
}
static bool updateTradeAction() {
    auto& state=taxWindow;unsigned command=state.strategyCommand;
    static const wchar_t* deals[]={L"통상 협정(30일 선단 이익 +20%)",L"회랑 통행(30일)",L"정보 매입"};
    taxText(0,command==39 ? L"항로 무역" : command==40 ? L"선단 무역" : command==17 ? L"관세율 변경" : L"외교");
    const StrategyDirectory::Person* row=nullptr;
    for(unsigned i=0;i<strategyDirectory.count;++i)if(strategyDirectory.people[i].id==state.strategyTarget)row=&strategyDirectory.people[i];
    wchar_t label[160];
    if(command==39) {
        if(row)swprintf_s(label,L"무역 상대 거점: %ls",row->name);else swprintf_s(label,L"10칸 안에 무역할 자진영 거점이 없습니다.");
    } else if(command==40)swprintf_s(label,L"페잔에 팔 군수물자: %u",tradeAmounts[tradeStep%4]);
    else if(command==17)swprintf_s(label,L"자진영 관세율: %u%% (0~50)",tradeValue>50 ? 50 : tradeValue);
    else swprintf_s(label,L"교섭: %ls",deals[tradeValue%3]);
    taxText(1,label);
    if(tradeHasTargets(command) && command!=39) {
        if(row)swprintf_s(label,L"%ls: %ls (ID %u)",command==40 ? L"교역 상대국" : L"정보 대상국",row->name,row->id);
        else swprintf_s(label,L"선택할 외국이 없습니다. 목록을 갱신하십시오.");
        taxText(2,label);
    } else taxText(2,tradeReport);
    taxText(3,command==39 ? L"PCP 160 또는 MCP 320" : L"MCP 320 또는 PCP 640");
    taxText(4,command==39 ? (tradeQuotesSupported ? L"게임 내 7일간 무역로 운영" : L"이익: 작은 쪽 성인 인구÷1000") :
        command==20 ? L"정치 능력 판정" : L"");
    taxText(5,tradeHasTargets(command) && command!=39 ? tradeReport : L"");
    if(tradeQuote.available) {
        const auto currency=tradeCurrency(tradeQuote.currency);
        if(command==40) {
            swprintf_s(label,L"상대국 관세: %u%%",tradeQuote.tariff);taxText(4,label);
            swprintf_s(label,L"예상 국고 수입: %u %ls",tradeQuote.amount,currency);
        } else if(command==39)swprintf_s(label,L"현재 일일 이익(거점당): %u %ls",tradeQuote.amount,currency);
        else if(tradeQuote.amount && tradeQuote.amount%100000000==0)
            swprintf_s(label,L"성공 시 국고 지출: %u억 %ls",tradeQuote.amount/100000000,currency);
        else if(tradeQuote.amount)swprintf_s(label,L"성공 시 국고 지출: %u %ls",tradeQuote.amount,currency);
        else {
            taxText(4,L"유출 정보 바로 입수");
            wcscpy_s(label,L"정보 대금 무료 · 상대국의 페잔 침범으로 정보 유출 중");
        }
        taxText(5,label);
    } else if(command==20 && tradeValue==2 && tradeReport[0]) {
        swprintf_s(label,L"지지율 %u · 치안 %u",tradeIntelStats>>8,tradeIntelStats&255);
        taxText(4,label);
    } else if(command==20 && tradeValue==2 && !tradeQuotesSupported && !tradeReport[0]) {
        taxText(5,L"성공 시 국고 1억 · 상대국 페잔 침범 시 90일간 무료");
    }
    if(row && tradeHasTargets(command) && tradeQuotesSupported && !state.pending && !tradeQuote.requested)sendTradeAction(true,0,true);
    taxText(6,state.message);
    taxText(10,command==17 ? L"+10%" : command==20 ? L"교섭 종류" : command==40 ? L"수량 변경" : L"-");
    taxText(11,L"이전");taxText(12,L"다음");taxText(13,L"목록 갱신");taxText(14,L"실행");taxText(15,L"닫기");
    taxEnabled(10,!state.pending && command!=39);taxEnabled(11,!state.pending && tradeHasTargets(command) && (strategyDirectory.count>1 || tradeCursor));
    taxEnabled(12,!state.pending && tradeHasTargets(command) && strategyDirectory.count>1);taxEnabled(13,!state.pending && tradeHasTargets(command));
    const bool canExecute=!state.pending && (!tradeHasTargets(command) ||
        (row!=nullptr && (!tradeQuotesSupported || tradeQuote.available)));
    taxEnabled(14,canExecute);
    if(state.pending && GetTickCount64()-state.sentAt>10000)state.message=L"응답 대기 중입니다.";
    if(GetTickCount64()<state.acceptAfter)return true;
    BYTE event[52]={};
    for(int id=10;id<=15;++id)if(reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,void*,int)>(0x5015f0)(state.root,2,taxWidget(1,id),event,0)) {
        state.acceptAfter=GetTickCount64()+250;
        if(id==15)closeTaxWindow();
        else if(!state.pending) {
            if(id==10) {
                if(command==17)tradeValue=(tradeValue+10)%60;
                else if(command==20) {tradeValue=(tradeValue+1)%3;state.strategyTarget=0;strategyDirectory={};tradeReport[0]=0;tradeQuote={};
                    if(tradeHasTargets(command))sendTradeAction(true);}
                else {tradeStep=(tradeStep+1)%4;tradeQuote={};tradeReport[0]=0;}
            }
            else if(id==11 || id==12)tradeNextTarget(id==11 ? -1 : 1);
            else if(id==13) {strategyDirectory={};sendTradeAction(true);}
            else if(id==14 && canExecute)sendTradeAction(false);
        }
        break;
    }
    return true;
}
