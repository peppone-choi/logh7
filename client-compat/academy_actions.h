// Academy editor: 受講 93, 兵棋演習 94, 講義 27. Explicit 7f11/native1200. Author: Peppone Choi.
#pragma once
static struct AcademyWindow {
    unsigned argument,ticket,base,content,remaining,usesLeft,ability,value,experience,wait,gained;
    ULONGLONG queryAt;
    bool loaded;
    wchar_t outcome[180];
} academyWindow={};
static bool academyCommand(unsigned command) {return command==27 || command==93 || command==94;}
static bool academyCard(unsigned command,unsigned card) {
    return command==27 ? card==37 || card==38 || card==173 || card==174 : card==0;
}
// Ability order of W p.14; content pairs follow the approved design (戦術/戦史/参謀要務/特殊技能).
static const wchar_t* academyAbility(unsigned ability) {
    static const wchar_t* names[8]={L"통솔",L"정치",L"운영",L"정보",L"지휘",L"기동",L"공격",L"방어"};
    return ability<8 ? names[ability] : L"-";
}
static const wchar_t* academyContent(unsigned content) {
    static const wchar_t* names[4]={L"전술 관계(지휘·기동)",L"전사 관계(공격·방어)",L"참모 요무(통솔·운영)",L"특수 기능(정치·정보)"};
    return content<4 ? names[content] : L"-";
}
static void sendAcademyAction(unsigned phase) {
    auto& state=taxWindow;auto& academy=academyWindow;
    BYTE record[36]={};at<unsigned>(record,0)=state.character;
    at<unsigned short>(record,4)=0x7f11;at<unsigned short>(record,6)=static_cast<unsigned short>(state.strategyCommand);
    at<unsigned short>(record,8)=static_cast<unsigned short>(state.card);
    at<unsigned>(record,12)=phase ? academy.ticket : 0;at<BYTE>(record,16)=static_cast<BYTE>(phase);
    at<unsigned short>(record,24)=static_cast<unsigned short>(academy.argument);
    strategyRequestToken=strategyRequestToken%65535+1;state.strategyToken=strategyRequestToken;
    at<unsigned short>(record,32)=static_cast<unsigned short>(state.strategyToken);
    academy.queryAt=GetTickCount64();state.pending=true;state.sentAt=academy.queryAt;
    state.message=phase ? L"MCP와 경험치를 함께 저장합니다." : L"현재 교실·강의·횟수를 조회합니다.";
    reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(state.world,0,0x46,record);
}
static bool readAcademyAction(void* record) {
    if(at<unsigned short>(record,4)!=0x7f11)return false;
    auto& state=taxWindow;auto& academy=academyWindow;
    if(state.visible && state.strategyActive && academyCommand(state.strategyCommand) && state.pending &&
        at<unsigned>(record,0)==state.character && at<unsigned short>(record,6)==state.strategyCommand &&
        at<unsigned short>(record,8)==state.card &&
        at<unsigned short>(record,32)==state.strategyToken) {
        unsigned flag=at<BYTE>(record,16),responseStatus=at<unsigned short>(record,10),value=at<unsigned short>(record,24);
        if(flag==10) {
            if(!responseStatus || !academy.loaded) {
                academy.ticket=at<unsigned>(record,12);academy.remaining=at<unsigned>(record,20);academy.base=at<unsigned>(record,28);
                academy.content=value/256;academy.usesLeft=value%256;
            }
            return true;
        }
        if(flag==11 || flag==12) {
            state.pending=false;
            academy.ability=at<unsigned>(record,12);academy.wait=at<unsigned>(record,20);
            academy.value=value/128;academy.experience=value%128;academy.gained=at<unsigned>(record,28);
            if(responseStatus)state.message=responseStatus==4 ? L"사관학교 교장·교관 직책 카드(강의) 또는 개인 카드(수강·병기연습)로 실행합니다." :
                responseStatus==6 ? L"아군 거점 사관학교의 교실(병기연습은 시뮬레이터실)에 머물러야 합니다." :
                responseStatus==7 ? L"다른 작업(보급·입항·워프 대기)을 마친 뒤 실행하세요." :
                responseStatus==8 ? L"MCP가 부족합니다." : responseStatus==9 ? L"아직 대기 시간이 남아 있습니다." :
                responseStatus==10 ? L"조회 뒤 다른 명령이 처리됐습니다. 새로 조회하세요." :
                responseStatus==11 ? L"능력치가 이미 상한이거나 저장 한도에 도달했습니다." : L"요청 거부 · 세션·카드·현재 상태를 확인하세요.";
            else {
                academy.loaded=true;
                if(flag==12) {
                    if(state.strategyCommand==27)swprintf_s(academy.outcome,L"강의 개시 · %ls · 120G분",academyContent(academy.content ? academy.content-1 : 4));
                    else swprintf_s(academy.outcome,L"경험치 +%u · %ls %u (경험 %u/100)",academy.gained,academyAbility(academy.ability),academy.value,academy.experience);
                    state.message=academy.outcome;
                } else if(academy.outcome[0])state.message=academy.outcome;
                else state.message=state.strategyCommand==27 ? L"강의 내용을 고른 뒤 실행하세요. 교실을 떠나면 강의가 끝납니다." :
                    state.strategyCommand==94 ? L"지휘·기동·공격·방어 중 하나를 골라 연습합니다." :
                    academy.content ? L"진행 중인 강의 내용으로 두 능력의 경험치를 얻습니다." : L"강의가 없으면 고른 능력의 경험치를 얻습니다.";
            }
        }
    }
    memset(static_cast<BYTE*>(record)+4,0,32);return true;
}
static void drawAcademyAction() {
    auto& state=taxWindow;auto& academy=academyWindow;unsigned command=state.strategyCommand;
    taxText(0,command==27 ? L"사관학교 강의" : command==93 ? L"사관학교 수강" : L"병기 연습");
    wchar_t name[32]={},label[180];pickerName(academy.base,name);
    swprintf_s(label,L"%ls · %ls",name[0] ? name : L"현재 거점",command==94 ? L"시뮬레이터실" : L"교실");taxText(1,label);
    if(command==27)swprintf_s(label,L"강의 내용: %ls",academyContent(academy.argument));
    else if(command==93 && academy.content)swprintf_s(label,L"진행 중 강의: %ls · 남은 %uG초",academyContent(academy.content-1),academy.remaining);
    else swprintf_s(label,L"선택 능력: %ls",academyAbility(academy.argument));
    taxText(2,label);
    swprintf_s(label,L"%ls %u · 경험 %u/100",academyAbility(academy.ability),academy.value,academy.experience);taxText(3,label);
    if(command==94)swprintf_s(label,L"오늘 남은 횟수 %u/5 · 대기 %uG초",academy.usesLeft,academy.wait);
    else if(command==27 && academy.content)swprintf_s(label,L"내 강의 진행 중 · 남은 %uG초",academy.remaining);
    else swprintf_s(label,L"재실행까지 %uG초",academy.wait);
    taxText(4,label);
    taxText(5,command==94 ? L"MCP 10 · 경험치 +5" : command==27 ? L"MCP 160 · 120G분, 수강생 두 능력 +50" : L"MCP 160 · 강의 중 두 능력 +50, 없으면 +20 · 하루 1회");
    taxText(6,state.message);
    bool fixed=command==93 && academy.content;
    taxText(10,L"◀");taxText(11,command==27 ? L"내용 변경" : L"능력 변경");taxText(12,L"▶");
    taxText(13,L"조회");taxText(14,command==27 ? L"강의 실행" : command==93 ? L"수강 실행" : L"연습 실행");taxText(15,L"닫기");
    taxEnabled(10,!state.pending && !fixed);taxEnabled(11,false);taxEnabled(12,!state.pending && !fixed);taxEnabled(13,!state.pending);
    taxEnabled(14,!state.pending && academy.loaded && academy.ticket && !academy.wait);
}
static void academyNextArgument(int direction) {
    auto& academy=academyWindow;unsigned command=taxWindow.strategyCommand;
    unsigned low=command==94 ? 4 : 0,count=command==27 ? 4 : command==94 ? 4 : 8;
    academy.argument=low+(academy.argument-low+count+direction)%count;
}
static bool updateAcademyAction() {
    drawAcademyAction();auto& state=taxWindow;auto& academy=academyWindow;
    if(state.pending && GetTickCount64()-state.sentAt>10000)state.message=L"응답 대기 · 재접속 후 다시 조회하세요.";
    if(GetTickCount64()<state.acceptAfter)return true;
    BYTE event[52]={};bool acted=false;
    for(int id=10;id<=15;++id)if(reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,void*,int)>(0x5015f0)(state.root,2,taxWidget(1,id),event,0)) {
        acted=true;state.acceptAfter=GetTickCount64()+250;
        if(id==15)closeTaxWindow();
        else if(!state.pending) {
            if(id==10 || id==12) {academyNextArgument(id==10 ? -1 : 1);sendAcademyAction(0);}
            else if(id==13)sendAcademyAction(0);
            else if(id==14 && academy.loaded && academy.ticket && !academy.wait)sendAcademyAction(1);
        }
        break;
    }
    if(!acted && state.visible && !state.pending && GetTickCount64()-academy.queryAt>=3000)sendAcademyAction(0);
    return true;
}
