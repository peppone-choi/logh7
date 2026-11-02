// Gathering editor: 会談 12 / 談話 13. Same-base people via the 7f00 directory, invite/accept/decline in 7f13.
// The server reports an open invitation in either direction for the selected person. Author: Peppone Choi.
#pragma once
static struct GatheringWindow {
    unsigned ticket,target,friendship,wait,inviteHost,inviteWait,grade,deltas,lastValue;
    ULONGLONG queryAt;
    bool loaded;
    wchar_t outcome[200];
} gatheringWindow={};
static bool gatheringCommand(unsigned command) {return command>=10 && command<=13;}
static bool gatheringIncoming() {
    auto& g=gatheringWindow;return g.inviteHost && g.inviteHost==taxWindow.strategyTarget;
}
static bool gatheringOutgoing() {
    auto& g=gatheringWindow;return g.inviteHost && g.inviteHost==taxWindow.character;
}
static void sendGatheringAction(unsigned phase,unsigned value) {
    auto& state=taxWindow;auto& gathering=gatheringWindow;
    if(!state.strategyTarget)return;
    BYTE record[36]={};at<unsigned>(record,0)=state.character;
    at<unsigned short>(record,4)=0x7f13;at<unsigned short>(record,6)=static_cast<unsigned short>(state.strategyCommand);
    at<unsigned short>(record,10)=static_cast<unsigned short>(value);
    at<unsigned>(record,12)=phase ? gathering.ticket : 0;at<BYTE>(record,16)=static_cast<BYTE>(phase);
    at<unsigned>(record,28)=state.strategyTarget;
    strategyRequestToken=strategyRequestToken%65535+1;state.strategyToken=strategyRequestToken;
    at<unsigned short>(record,32)=static_cast<unsigned short>(state.strategyToken);
    gathering.lastValue=value;gathering.queryAt=GetTickCount64();state.pending=true;state.sentAt=gathering.queryAt;
    state.message=!phase ? L"상대와의 초대 상태를 조회합니다." : value==0 ? L"초대를 보냅니다. CP는 상대가 수락할 때 씁니다." :
        value==1 ? L"초대를 수락합니다." : L"초대를 거절합니다.";
    reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(state.world,0,0x46,record);
}
static bool readGatheringAction(void* record) {
    if(at<unsigned short>(record,4)!=0x7f13)return false;
    auto& state=taxWindow;auto& gathering=gatheringWindow;
    unsigned flag=at<BYTE>(record,16);
    if(flag==13) {
        // Invitation pushed to this guest: refresh an open window for that host.
        if(state.visible && state.strategyActive && gatheringCommand(state.strategyCommand) && !state.pending &&
            at<unsigned>(record,28)==state.strategyTarget)gathering.queryAt=0;
    } else if(state.visible && state.strategyActive && gatheringCommand(state.strategyCommand) && state.pending &&
        at<unsigned>(record,0)==state.character && at<unsigned short>(record,6)==state.strategyCommand &&
        at<unsigned short>(record,32)==state.strategyToken) {
        unsigned responseStatus=at<unsigned short>(record,10),value=at<unsigned short>(record,24);
        if(flag==10) {
            gathering.ticket=at<unsigned>(record,12);gathering.wait=at<unsigned>(record,20);
            gathering.target=at<unsigned>(record,28);gathering.friendship=value;
        } else if(flag==11 || flag==12) {
            state.pending=false;gathering.inviteHost=at<unsigned>(record,12);gathering.inviteWait=at<unsigned>(record,20);
            gathering.grade=value;gathering.deltas=at<unsigned>(record,28);
            // SocialRejection ordinal+1: LOCATION 6, BUSY 7, CP 8, COOLDOWN 9, STALE 10, MAXIMUM 11.
            if(responseStatus)state.message=responseStatus==6 ? (state.strategyCommand==10 ? L"자기 수도 저택 무도회장(제국 작위·동맹 준장 이상/정치가)에서 같은 거점 인물에게만 할 수 있습니다." :
                state.strategyCommand==11 ? L"자기 봉토 저택의 숲에서, 같은 거점의 같은 진영 인물에게만 할 수 있습니다." :
                L"자기 호텔 객실에서, 같은 거점의 같은 진영 인물에게만 할 수 있습니다.") :
                responseStatus==7 ? L"다른 작업(보급·입항·워프 대기)을 마친 뒤 실행하세요." :
                responseStatus==8 ? L"주최자의 MCP가 부족합니다(320 필요)." : responseStatus==9 ? L"오늘은 이미 모임을 열었습니다." :
                responseStatus==10 ? L"초대가 없거나 만료됐습니다. 새로 조회하세요." :
                responseStatus==11 ? L"저장 한도에 도달했습니다." : L"요청 거부 · 세션·대상·현재 상태를 확인하세요.";
            else {
                gathering.loaded=true;
                if(flag==12 && gathering.grade) {
                    static const wchar_t* grades[]={L"",L"대성공",L"성공",L"실패"};
                    int host=static_cast<int>((gathering.deltas>>16)&255)-128,guest=static_cast<int>((gathering.deltas>>8)&255)-128,
                        friendship=static_cast<int>(gathering.deltas&255)-128;
                    swprintf_s(gathering.outcome,L"%ls · 주최자 영향력 %+d · 손님 영향력 %+d · 우호도 %+d → %u",
                        grades[gathering.grade<4 ? gathering.grade : 0],host,guest,friendship,gathering.friendship);
                    state.message=gathering.outcome;
                } else if(flag==12)state.message=gathering.inviteHost ? L"초대를 보냈습니다. 상대가 수락하면 결과가 나옵니다." :
                    // A guest who is not connected answers at once (friendship 50 or more accepts).
                    gathering.lastValue==0 ? L"접속하지 않은 상대가 초대를 거절했습니다(우호도 50 미만)." : L"초대를 거절했습니다.";
                else if(gathering.outcome[0])state.message=gathering.outcome;
                else state.message=gatheringIncoming() ? L"이 인물의 초대가 와 있습니다. 수락하거나 거절하세요." :
                    gatheringOutgoing() ? L"이 인물에게 보낸 초대가 수락을 기다리고 있습니다." :
                    state.strategyCommand==10 ? L"자기 진영 수도 저택의 무도회장에서 같은 거점의 인물을 초대합니다." :
                    state.strategyCommand==11 ? L"자기 봉토 저택의 숲에서 같은 거점의 인물을 초대합니다." :
                    L"자기 호텔 객실에서 같은 거점의 인물을 초대할 수 있습니다.";
            }
        }
    }
    memset(static_cast<BYTE*>(record)+4,0,32);return true;
}
static const wchar_t* gatheringName(unsigned id) {
    for(unsigned i=0;i<strategyDirectory.count;++i)if(strategyDirectory.people[i].id==id)return strategyDirectory.people[i].name;
    return L"-";
}
static void drawGatheringAction() {
    auto& state=taxWindow;auto& gathering=gatheringWindow;
    unsigned command=state.strategyCommand;bool discussion=command==11 || command==13;
    static const wchar_t* titles[]={L"야회",L"수렵",L"회담",L"담화"};
    taxText(0,titles[command-10]);
    wchar_t label[200];
    if(state.strategyTarget)swprintf_s(label,L"상대: %ls",gatheringName(state.strategyTarget));
    else swprintf_s(label,L"같은 거점에 초대할 인물이 없습니다");
    taxText(1,label);
    if(gatheringIncoming())swprintf_s(label,L"받은 초대 · 남은 %uG초",gathering.inviteWait);
    else if(gatheringOutgoing())swprintf_s(label,L"보낸 초대 · 수락 대기 %uG초",gathering.inviteWait);
    else swprintf_s(label,L"초대 없음");
    taxText(2,label);
    if(state.strategyTarget && gathering.target==state.strategyTarget)swprintf_s(label,L"나와의 우호도 %u/100",gathering.friendship);
    else swprintf_s(label,L"나와의 우호도 -");
    taxText(3,label);
    swprintf_s(label,L"다시 주최까지 %uG초",gathering.target==state.strategyTarget ? gathering.wait : 0);taxText(4,label);
    taxText(5,command==10 ? L"주최자 MCP 320 · 영향력 +5/+2/-2(1.5배)" : discussion ? L"주최자 MCP 320 · 영향력 +3/+1/-1 · 우호도 +10/+5/-3" : L"주최자 MCP 320 · 영향력 +3/+1/-1");
    taxText(6,state.message);
    bool incoming=gatheringIncoming();
    taxText(10,L"◀");taxText(11,incoming ? L"거절" : L"상대 변경");taxText(12,L"▶");
    taxText(13,L"조회");taxText(14,incoming ? L"수락" : L"초대");taxText(15,L"닫기");
    bool many=strategyDirectory.count>1;bool ready=!state.pending && gathering.loaded && gathering.ticket &&
        state.strategyTarget && gathering.target==state.strategyTarget;
    taxEnabled(10,!state.pending && many);taxEnabled(11,ready && incoming);taxEnabled(12,!state.pending && many);taxEnabled(13,!state.pending);
    taxEnabled(14,ready && (incoming || !gatheringOutgoing() && !gathering.wait));
}
static bool updateGatheringAction() {
    drawGatheringAction();auto& state=taxWindow;auto& gathering=gatheringWindow;
    if(state.pending && GetTickCount64()-state.sentAt>10000)state.message=L"응답 대기 · 재접속 후 다시 조회하세요.";
    if(GetTickCount64()<state.acceptAfter)return true;
    BYTE event[52]={};bool acted=false;
    for(int id=10;id<=15;++id)if(reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,void*,int)>(0x5015f0)(state.root,2,taxWidget(1,id),event,0)) {
        acted=true;state.acceptAfter=GetTickCount64()+250;
        if(id==15)closeTaxWindow();
        else if(!state.pending) {
            if((id==10 || id==12) && strategyDirectory.count) {
                unsigned selected=0;for(unsigned i=0;i<strategyDirectory.count;++i)if(strategyDirectory.people[i].id==state.strategyTarget)selected=i;
                state.strategyTarget=strategyDirectory.people[(selected+strategyDirectory.count+(id==10 ? -1 : 1))%strategyDirectory.count].id;
                gathering.outcome[0]=0;gathering.inviteHost=0;sendGatheringAction(0,0);
            }
            else if(id==13) {if(strategyDirectory.count)sendGatheringAction(0,0);else sendStrategyAction(true);}
            else if(id==11 && gatheringIncoming())sendGatheringAction(1,2);
            else if(id==14 && gathering.loaded && gathering.ticket)sendGatheringAction(1,gatheringIncoming() ? 1 : 0);
        }
        break;
    }
    if(!acted && state.visible && !state.pending && state.strategyTarget && GetTickCount64()-gathering.queryAt>=3000)sendGatheringAction(0,0);
    return true;
}
