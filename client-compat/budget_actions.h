// Finance editor over the explicit 7f02/native 1200 envelope. Author: Peppone Choi.
#pragma once
static void strategyNextBase(int direction);
static struct BudgetWindow {
    unsigned currentRate,rate,quote,quotePhase,field,requestPhase;
    ULONGLONG national,local,amount,queryAt;
    bool loaded,dirty;
} budgetWindow={};
static bool budgetCommand(unsigned command) {return command==16 || command==18;}
static void sendBudgetAction(unsigned phase) {
    auto& state=taxWindow;auto& budget=budgetWindow;
    BYTE record[36]={};at<unsigned>(record,0)=state.character;
    at<unsigned short>(record,4)=0x7f02;at<unsigned short>(record,6)=static_cast<unsigned short>(state.strategyCommand);
    at<unsigned short>(record,8)=static_cast<unsigned short>(state.card);
    at<unsigned short>(record,10)=static_cast<unsigned short>(phase ? budget.rate : 0);
    at<unsigned>(record,12)=phase>=2 ? budget.quote : 0;at<BYTE>(record,16)=static_cast<BYTE>(phase);
    at<unsigned>(record,28)=state.strategyBase;
    strategyRequestToken=strategyRequestToken%65535+1;state.strategyToken=strategyRequestToken;
    at<unsigned short>(record,32)=static_cast<unsigned short>(state.strategyToken);
    budget.requestPhase=phase;budget.queryAt=GetTickCount64();state.pending=true;state.sentAt=budget.queryAt;
    state.message=phase==2 ? L"CP와 예산 이체를 함께 저장합니다." : L"서버의 직위·현재 예산을 확인합니다.";
    reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(state.world,0,0x46,record);
}
static bool readBudgetAction(void* record) {
    if(at<unsigned short>(record,4)!=0x7f02)return false;
    auto& state=taxWindow;auto& budget=budgetWindow;
    unsigned flag=at<BYTE>(record,16),token=at<unsigned short>(record,32),responseStatus=at<unsigned short>(record,10);
    if(state.visible && state.strategyActive && budgetCommand(state.strategyCommand) &&
        at<unsigned>(record,0)==state.character && at<unsigned short>(record,8)==state.card) {
        ULONGLONG value=(static_cast<ULONGLONG>(at<unsigned>(record,12))<<32)|at<unsigned>(record,20);
        if(!token && !responseStatus && (flag==14 || flag==15) && at<unsigned>(record,28)==state.strategyBase) {
            if((flag==14 ? budget.national : budget.local)!=value && !state.pending) {budget.quote=0;budget.dirty=true;}
            if(flag==14) {budget.national=value;budget.currentRate=at<unsigned short>(record,24);} else budget.local=value;
        } else if(state.pending && token==state.strategyToken && at<unsigned short>(record,6)==state.strategyCommand) {
            if(!responseStatus) {
                if(flag==10) {budget.national=value;budget.currentRate=at<unsigned short>(record,24);state.strategyBase=at<unsigned>(record,28);}
                if(flag==11)budget.local=value;
                if(flag==12) {
                    unsigned packed=at<unsigned short>(record,24);budget.quote=at<unsigned>(record,28);budget.quotePhase=packed%4;
                    budget.amount=value;
                    if(!budget.loaded || !budget.dirty)budget.rate=budget.quote ? packed/4 : state.strategyCommand==16 ? budget.currentRate : 10;
                    budget.loaded=true;
                    if(budget.requestPhase)budget.dirty=false;
                    state.message=budget.quotePhase==2 ? L"확정 결과가 저장됐습니다. 같은 견적은 다시 결제하지 않습니다." :
                        budget.quotePhase==3 ? L"견적을 취소했습니다. CP·예산을 사용하지 않았습니다." :
                        budget.quotePhase==1 ? L"확정 전에 금액을 확인하세요. 예산이 바뀌면 새 견적이 필요합니다." : L"비율 선택 후 견적을 요청하십시오.";
                }
            }
            if(flag==12) {
                state.pending=false;
                if(responseStatus) {budget.quote=0;state.message=responseStatus==9 ? L"CP가 부족합니다." : responseStatus==11 ? L"견적 만료/예산 변경 · 새 견적을 요청하세요." :
                    responseStatus==10 ? L"분배할 예산이 부족합니다. 1~100%를 선택하세요." : responseStatus==7 ? L"담당 수도에 머물러야 확정할 수 있습니다." :
                    L"요청 거부 · 실제 재정 직위·대상 진영·금고 상한을 확인하세요.";}
            }
        }
    }
    memset(static_cast<BYTE*>(record)+4,0,32);return true;
}
static void drawBudgetAction() {
    auto& state=taxWindow;auto& budget=budgetWindow;bool remit=state.strategyCommand==16;
    taxText(0,remit ? L"납입률 변경" : L"국가 예산 분배");
    wchar_t name[32]={},label[180];pickerName(state.strategyBase,name);
    swprintf_s(label,L"%ls · ID %u · 현재 납입률 %u%%",name[0] ? name : L"거점",state.strategyBase,budget.currentRate);taxText(1,label);
    swprintf_s(label,L"국고 %llu · 지방 금고 %llu",budget.national,budget.local);taxText(2,label);
    const wchar_t* fields[]={L"대상 거점",L"비율 ±1",L"비율 ±10"};
    swprintf_s(label,L"%ls · 선택 %u%%",fields[budget.field],budget.rate);taxText(3,label);
    if(remit)swprintf_s(label,L"견적 #%u · 세율 별도",budget.quote);
    else swprintf_s(label,L"이체 %llu",budget.amount);taxText(4,label);
    taxText(5,remit && budget.rate==budget.currentRate ? L"같은 납입률은 CP 무료 · 견적/취소 무료" :
        L"MCP 320 · 부족 시 PCP 640 · 견적/취소 무료 · 즉시 적용");
    taxText(6,budget.dirty && !state.pending ? L"선택값이나 잔고가 바뀌었습니다. 새 견적을 요청하세요." : state.message);
    taxText(10,L"선택 항목");taxText(11,L"이전 / −");taxText(12,L"다음 / +");
    taxText(13,budget.quote && budget.quotePhase==1 && !budget.dirty ? L"견적 취소" : L"견적 요청");taxText(14,L"금액 확인 · 확정");taxText(15,L"닫기");
    taxEnabled(10,!state.pending);taxEnabled(11,!state.pending);taxEnabled(12,!state.pending);
    taxEnabled(13,!state.pending && budget.loaded);taxEnabled(14,!state.pending && budget.loaded && !budget.dirty && budget.quote && budget.quotePhase==1);
}
static bool updateBudgetAction() {
    drawBudgetAction();auto& state=taxWindow;auto& budget=budgetWindow;
    if(state.pending && GetTickCount64()-state.sentAt>10000)state.message=L"응답 대기 · 재접속 후 저장된 견적/결과를 조회하세요.";
    if(GetTickCount64()<state.acceptAfter)return true;
    BYTE event[52]={};bool acted=false;
    for(int id=10;id<=15;++id)if(reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,void*,int)>(0x5015f0)(state.root,2,taxWidget(1,id),event,0)) {
        acted=true;state.acceptAfter=GetTickCount64()+250;
        if(id==15)closeTaxWindow();
        else if(!state.pending) {
            if(id==10)budget.field=(budget.field+1)%3;
            else if(id==13 && budget.loaded)sendBudgetAction(budget.quote && budget.quotePhase==1 && !budget.dirty ? 3 : 1);
            else if(id==14 && budget.quote && budget.quotePhase==1 && !budget.dirty)sendBudgetAction(2);
            else if(id==11 || id==12) {
                int step=id==11 ? -1 : 1;budget.quote=0;budget.dirty=true;
                if(!budget.field) {strategyNextBase(step);budget.loaded=false;budget.dirty=false;sendBudgetAction(0);}
                else {int value=static_cast<int>(budget.rate)+step*(budget.field==1 ? 1 : 10);budget.rate=value<0 ? 0 : value>100 ? 100 : static_cast<unsigned>(value);}
            }
        }
        break;
    }
    if(!acted && state.visible && !state.pending && !budget.dirty && GetTickCount64()-budget.queryAt>=3000)sendBudgetAction(0);
    return true;
}
