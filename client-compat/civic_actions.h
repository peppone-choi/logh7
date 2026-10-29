// Timed local duties over the explicit 7f00 revival codec. Author: 최병호.
#pragma once
static struct CivicWindowState {
    int phase;
    unsigned base,readyAt,effect;
    ULONGLONG queryAt;
} civicWindow={-1,0,0,0,0};
static bool civicCommand(unsigned command) {return command==52 || command==54 || command==55 || command==56;}
static bool civicArmy(unsigned command) {return command==52 || command==54 || command==56;}
static void sendCivicAction(unsigned mode) {
    taxWindow.strategyValue=mode;taxWindow.strategyBase=0;civicWindow.queryAt=GetTickCount64();sendStrategyAction();
}
static void readCivicAction(void* record) {
    auto& state=taxWindow;unsigned responseStatus=at<unsigned short>(record,10);state.pending=false;
    if(responseStatus) {
        state.message=responseStatus==11 ? L"CP가 부족합니다." : responseStatus==8 ? L"현재 거점에 주류한 육전대가 필요합니다." :
            responseStatus==13 ? L"징발할 군수나 부대의 적재 공간이 없습니다." :
            L"명령 거부 · 직무·장소·진행 명령·현재 수치를 확인하세요.";return;
    }
    unsigned flag=at<BYTE>(record,16);civicWindow.phase=flag>=10 && flag<=15 ? static_cast<int>(flag-10) : -1;
    civicWindow.base=at<unsigned>(record,28);civicWindow.readyAt=at<unsigned>(record,20);civicWindow.effect=at<unsigned short>(record,24);
    if(at<unsigned>(record,12)>0)state.strategyOutfit=at<unsigned>(record,12);
    switch(civicWindow.phase) {
    case 0:state.message=L"접수 저장 · 게임 24시간 대기 · 자격/주류 병력을 유지하세요.";break;
    case 1:state.message=L"특별경비 유지 중 · 스폿을 이탈하면 종료됩니다.";break;
    case 2:state.message=L"완료 결과가 저장됐습니다.";break;
    case 3:state.message=L"취소됐습니다. 접수 때 쓴 CP는 반환하지 않습니다.";break;
    case 4:state.message=L"장소·직무·병력 조건이 사라져 효과 없이 종료됐습니다.";break;
    case 5:state.message=L"대기가 끝났으나 적용할 수치나 군수 여유가 없었습니다.";break;
    default:state.message=L"이 명령의 저장된 진행/완료 기록이 없습니다.";
    }
}
static bool updateCivicAction() {
    auto& state=taxWindow;bool guard=state.strategyCommand==55,active=civicWindow.phase==0 || civicWindow.phase==1;
    taxText(0,strategyActionName(state.strategyCommand));
    taxText(1,guard ? L"현재 경계 로비의 경비를 강화합니다." : L"현재 거점에 주류한 지휘 부대의 육전대를 사용합니다.");
    taxText(2,guard ? L"소요 게임 24시간 · 현재 스폿 이탈 시 종료" : L"대기 게임 24시간 · 이탈/자격/병력 상실 시 효과 없음");
    wchar_t label[160];
    if(guard)swprintf_s(label,L"경비 강도 %u · 거점 %u",civicWindow.phase==1 ? 2u : 1u,civicWindow.base);
    else swprintf_s(label,L"대상 부대 ID %u · 거점 %u",state.strategyOutfit,civicWindow.base);taxText(3,label);
    if(active)swprintf_s(label,L"완료 예정 게임 시각 %u",civicWindow.readyAt);
    else swprintf_s(label,L"마지막 적용값 %u",civicWindow.effect);taxText(4,label);
    taxText(5,guard ? L"MCP 160 · 부족 시 PCP 320 · 경비 시작 시 결제" : L"MCP 160 · 부족 시 PCP 320 · 취소/조건 상실 시 환불 없음");
    taxText(6,state.message);taxText(10,L"명령 취소");taxText(11,L"이전 부대");taxText(12,L"다음 부대");
    taxText(13,L"진행 조회");taxText(14,L"접수 확정");taxText(15,L"닫기");
    taxEnabled(10,!state.pending && active);taxEnabled(11,!state.pending && !active && !guard);
    taxEnabled(12,!state.pending && !active && !guard);taxEnabled(13,!state.pending);
    taxEnabled(14,!state.pending && !active && (guard || state.strategyOutfit>0));
    if(state.pending && GetTickCount64()-state.sentAt>10000)state.message=L"응답 대기 중 · 재접속 후 진행 조회로 확인하세요.";
    if(GetTickCount64()<state.acceptAfter)return true;
    BYTE event[52]={};bool acted=false;
    for(int id=10;id<=15;++id)if(reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,void*,int)>(0x5015f0)(state.root,2,taxWidget(1,id),event,0)) {
        acted=true;state.acceptAfter=GetTickCount64()+250;
        if(id==15)closeTaxWindow();
        else if(!state.pending) {
            if(id==10 && active)sendCivicAction(1);
            else if(id==13)sendCivicAction(2);
            else if(id==14 && !active && (guard || state.strategyOutfit>0))sendCivicAction(0);
            else if((id==11 || id==12) && !active && !guard) {
                auto own=at<void*>(state.world,8);unsigned ids[16]={},count=strategyOutfits(own,ids),selected=0;
                if(state.card!=59 && state.card!=195)count=0;
                for(unsigned i=0;i<count;++i)if(ids[i]==state.strategyOutfit)selected=i;
                if(count)state.strategyOutfit=ids[(selected+count+(id==11 ? -1 : 1))%count];
            }
        }
        break;
    }
    if(!acted && state.visible && !state.pending && GetTickCount64()-civicWindow.queryAt>=3000)sendCivicAction(2);
    return true;
}
