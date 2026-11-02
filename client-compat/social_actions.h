// First social editor: public-square speech. Explicit 7f10/native1200. Author: Peppone Choi.
#pragma once
static struct SocialWindow {
    unsigned ticket,base,influence,approval,wait,last,lastBase,at;
    int influenceDelta,approvalDelta;
    ULONGLONG queryAt;
    bool loaded;
    wchar_t outcome[180];
} socialWindow={};
static bool socialCommand(unsigned command) {return command==14;}
static void sendSocialAction(unsigned phase) {
    auto& state=taxWindow;auto& social=socialWindow;
    BYTE record[36]={};at<unsigned>(record,0)=state.character;
    at<unsigned short>(record,4)=0x7f10;at<unsigned short>(record,6)=14;
    at<unsigned>(record,12)=phase ? social.ticket : 0;at<BYTE>(record,16)=static_cast<BYTE>(phase);
    strategyRequestToken=strategyRequestToken%65535+1;state.strategyToken=strategyRequestToken;
    at<unsigned short>(record,32)=static_cast<unsigned short>(state.strategyToken);
    social.queryAt=GetTickCount64();state.pending=true;state.sentAt=social.queryAt;
    state.message=phase ? L"연설·CP·효과를 함께 저장합니다." : L"현재 장소와 저장된 연설 결과를 조회합니다.";
    reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(state.world,0,0x46,record);
}
static bool readSocialAction(void* record) {
    if(at<unsigned short>(record,4)!=0x7f10)return false;
    auto& state=taxWindow;auto& social=socialWindow;
    if(state.visible && state.strategyActive && socialCommand(state.strategyCommand) && state.pending &&
        at<unsigned>(record,0)==state.character && at<unsigned short>(record,6)==14 &&
        at<unsigned short>(record,8)==0 && at<unsigned short>(record,32)==state.strategyToken) {
        unsigned flag=at<BYTE>(record,16),responseStatus=at<unsigned short>(record,10),value=at<unsigned short>(record,24);
        if(!responseStatus && flag==10) {
            social.ticket=at<unsigned>(record,12);social.wait=at<unsigned>(record,20);social.base=at<unsigned>(record,28);
            social.influence=value/128;social.approval=value%128;
        }
        if(flag==11 || flag==12) {
            state.pending=false;
            if(responseStatus)state.message=responseStatus==5 ? L"정치가 계급의 개인 카드에서 실행할 수 있습니다." :
                responseStatus==6 ? L"현재 아군 행성/요새의 중앙 광장에 머물러야 합니다." :
                responseStatus==8 ? L"CP가 부족합니다." : responseStatus==9 ? L"본인 또는 광장의 하루 제한이 남아 있습니다." :
                responseStatus==10 ? L"조회 뒤 다른 명령이 처리됐습니다. 새로 조회하세요." :
                responseStatus==11 ? L"더 적용할 수치가 없거나 저장 한도에 도달했습니다." : L"요청 거부 · 세션·카드·현재 상태를 확인하세요.";
            else {
                social.loaded=true;social.last=at<unsigned>(record,12);social.at=at<unsigned>(record,20);social.lastBase=at<unsigned>(record,28);
                social.influenceDelta=social.last ? static_cast<int>(value/256)-128 : 0;
                social.approvalDelta=social.last ? static_cast<int>(value%256)-128 : 0;
                if(social.last) {
                    swprintf_s(social.outcome,L"%ls #%u · 영향력 %+d · 지지 %+d · 거점 %u",flag==12 ? L"연설 결과" : L"저장 결과",
                        social.last,social.influenceDelta,social.approvalDelta,social.lastBase);
                    state.message=social.outcome;
                } else state.message=L"기본 연설 · 정치력 50 이상이면 호응, 미만이면 반감 · 실행 전 조건을 확인하세요.";
            }
        }
    }
    memset(static_cast<BYTE*>(record)+4,0,32);return true;
}
static void drawSocialAction() {
    auto& state=taxWindow;auto& social=socialWindow;
    taxText(0,L"중앙 광장 연설");
    wchar_t name[32]={},label[180];pickerName(social.base,name);
    swprintf_s(label,L"%ls · 거점 %u · 정치가/개인 카드",name[0] ? name : L"현재 거점",social.base);taxText(1,label);
    taxText(2,L"본인·광장 각각 게임 하루 1회 · 실제 중앙 광장에서 실행");
    swprintf_s(label,L"영향력 %u · 지지 %u%%",social.influence,social.approval);taxText(3,label);
    swprintf_s(label,L"재실행까지 %uG초",social.wait);taxText(4,label);
    taxText(5,L"MCP 320 · 부족 시 PCP 640 · 반감 결과도 결제");taxText(6,state.message);
    taxText(10,L"기본 연설");taxText(11,L"—");taxText(12,L"—");taxText(13,L"현재/결과 조회");taxText(14,L"연설 실행");taxText(15,L"닫기");
    taxEnabled(10,false);taxEnabled(11,false);taxEnabled(12,false);taxEnabled(13,!state.pending);
    taxEnabled(14,!state.pending && social.loaded && social.ticket && !social.wait);
}
static bool updateSocialAction() {
    drawSocialAction();auto& state=taxWindow;auto& social=socialWindow;
    if(state.pending && GetTickCount64()-state.sentAt>10000)state.message=L"응답 대기 · 재접속 후 저장 결과를 조회하세요.";
    if(GetTickCount64()<state.acceptAfter)return true;
    BYTE event[52]={};bool acted=false;
    for(int id=13;id<=15;++id)if(reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,void*,int)>(0x5015f0)(state.root,2,taxWidget(1,id),event,0)) {
        acted=true;state.acceptAfter=GetTickCount64()+250;
        if(id==15)closeTaxWindow();
        else if(!state.pending) {
            if(id==13)sendSocialAction(0);
            else if(id==14 && social.loaded && social.ticket && !social.wait)sendSocialAction(1);
        }
        break;
    }
    if(!acted && state.visible && !state.pending && GetTickCount64()-social.queryAt>=3000)sendSocialAction(0);
    return true;
}
