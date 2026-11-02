// Meeting editor: 会見 87. Same-spot people via the 7f00 directory, query/commit in 7f12. Author: Peppone Choi.
#pragma once
static struct MeetingWindow {
    unsigned ticket,target,friendship,wait,gained;
    ULONGLONG queryAt;
    bool loaded;
    wchar_t outcome[180];
} meetingWindow={};
static bool meetingCommand(unsigned command) {return command==87;}
static const wchar_t* meetingName(unsigned id) {
    for(unsigned i=0;i<strategyDirectory.count;++i)if(strategyDirectory.people[i].id==id)return strategyDirectory.people[i].name;
    return L"-";
}
static void sendMeetingAction(unsigned phase) {
    auto& state=taxWindow;auto& meeting=meetingWindow;
    if(!state.strategyTarget)return;
    BYTE record[36]={};at<unsigned>(record,0)=state.character;
    at<unsigned short>(record,4)=0x7f12;at<unsigned short>(record,6)=87;
    at<unsigned>(record,12)=phase ? meeting.ticket : 0;at<BYTE>(record,16)=static_cast<BYTE>(phase);
    at<unsigned>(record,28)=state.strategyTarget;
    strategyRequestToken=strategyRequestToken%65535+1;state.strategyToken=strategyRequestToken;
    at<unsigned short>(record,32)=static_cast<unsigned short>(state.strategyToken);
    meeting.queryAt=GetTickCount64();state.pending=true;state.sentAt=meeting.queryAt;
    state.message=phase ? L"MCP와 우호도를 함께 저장합니다." : L"상대와의 우호도와 오늘 회견 여부를 조회합니다.";
    reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(state.world,0,0x46,record);
}
static bool readMeetingAction(void* record) {
    if(at<unsigned short>(record,4)!=0x7f12)return false;
    auto& state=taxWindow;auto& meeting=meetingWindow;
    if(state.visible && state.strategyActive && meetingCommand(state.strategyCommand) && state.pending &&
        at<unsigned>(record,0)==state.character && at<unsigned short>(record,6)==87 &&
        at<unsigned short>(record,32)==state.strategyToken) {
        unsigned flag=at<BYTE>(record,16),responseStatus=at<unsigned short>(record,10),value=at<unsigned short>(record,24);
        if(flag==10) {
            meeting.ticket=at<unsigned>(record,12);meeting.wait=at<unsigned>(record,20);
            meeting.target=at<unsigned>(record,28);meeting.friendship=value;
            return true;
        }
        if(flag==11 || flag==12) {
            state.pending=false;meeting.gained=at<unsigned>(record,28);
            if(responseStatus)state.message=responseStatus==6 ? L"상대가 같은 스폿에 있어야 합니다." :
                responseStatus==7 ? L"다른 작업(보급·입항·워프 대기)을 마친 뒤 실행하세요." :
                responseStatus==8 ? L"MCP가 부족합니다." : responseStatus==9 ? L"이 상대와는 오늘 이미 회견했습니다." :
                responseStatus==10 ? L"조회 뒤 다른 명령이 처리됐습니다. 새로 조회하세요." :
                responseStatus==11 ? L"우호도가 이미 최대이거나 저장 한도에 도달했습니다." : L"요청 거부 · 세션·대상·현재 상태를 확인하세요.";
            else {
                meeting.loaded=true;
                if(flag==12) {
                    swprintf_s(meeting.outcome,L"%ls와 회견 · 우호도 +%u → %u",meetingName(meeting.target),meeting.gained,meeting.friendship);
                    state.message=meeting.outcome;
                } else if(meeting.outcome[0])state.message=meeting.outcome;
                else state.message=L"같은 스폿의 인물과 회견하면 우호도가 오릅니다.";
            }
        }
    }
    memset(static_cast<BYTE*>(record)+4,0,32);return true;
}
static void drawMeetingAction() {
    auto& state=taxWindow;auto& meeting=meetingWindow;
    taxText(0,L"회견");
    wchar_t label[180];
    if(state.strategyTarget)swprintf_s(label,L"상대: %ls",meetingName(state.strategyTarget));
    else swprintf_s(label,L"같은 스폿에 회견할 인물이 없습니다");
    taxText(1,label);
    taxText(2,L"같은 스폿의 인물(사실은 같은 소유자) · 상대 접속 불필요");
    if(state.strategyTarget && meeting.target==state.strategyTarget)swprintf_s(label,L"나와의 우호도 %u/100",meeting.friendship);
    else swprintf_s(label,L"나와의 우호도 -");
    taxText(3,label);
    swprintf_s(label,L"같은 상대와 다시 회견까지 %uG초",meeting.target==state.strategyTarget ? meeting.wait : 0);taxText(4,label);
    taxText(5,L"MCP 10 · 우호도 +3 · 같은 상대 하루 1회");taxText(6,state.message);
    taxText(10,L"◀");taxText(11,L"상대 변경");taxText(12,L"▶");
    taxText(13,L"조회");taxText(14,L"회견 실행");taxText(15,L"닫기");
    bool many=strategyDirectory.count>1;
    taxEnabled(10,!state.pending && many);taxEnabled(11,false);taxEnabled(12,!state.pending && many);taxEnabled(13,!state.pending);
    taxEnabled(14,!state.pending && meeting.loaded && meeting.ticket && state.strategyTarget && meeting.target==state.strategyTarget && !meeting.wait);
}
static bool updateMeetingAction() {
    drawMeetingAction();auto& state=taxWindow;auto& meeting=meetingWindow;
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
                meeting.outcome[0]=0;sendMeetingAction(0);
            }
            else if(id==13) {if(strategyDirectory.count)sendMeetingAction(0);else sendStrategyAction(true);}
            else if(id==14 && meeting.loaded && meeting.ticket && !meeting.wait)sendMeetingAction(1);
        }
        break;
    }
    // The directory arrives first; then each idle 3 s refreshes the selected partner.
    if(!acted && state.visible && !state.pending && state.strategyTarget && GetTickCount64()-meeting.queryAt>=3000)sendMeetingAction(0);
    return true;
}
