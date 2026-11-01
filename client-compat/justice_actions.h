// U-25 逮捕·処断 window (design-arrest-judgment.md): 処断 19, 逮捕許可 69, 執行命令 70, 逮捕命令 71 over 0x7f00.
// strategyTarget is the chosen person, strategyValue the charge (69, 1..7), the verdict (19, 1..3) or grant/revoke (70);
// 執行命令 first picks the listed person (strategyBase, preview value 0) and then the grantee (preview value 1).
// Author: Peppone Choi.
#pragma once
static bool justiceCommand(unsigned command) {return command==19 || command==69 || command==70 || command==71;}
static bool justiceCard(unsigned card) {return card==7 || card==10 || card==32 || card==137 || card==157 || card==160;}
static const wchar_t* justiceCharge(unsigned value) {
    static const wchar_t* names[]={L"",L"부패",L"간첩",L"반역",L"위험 사상",L"실정 추궁",L"수뢰 혐의",L"추문 폭로"};
    return value<8 ? names[value] : L"";
}
static const wchar_t* justiceVerdict(unsigned value) {
    static const wchar_t* names[]={L"",L"무죄",L"관용",L"엄벌"};
    return value<4 ? names[value] : L"";
}
static void sendJusticeAction(bool preview,unsigned cursor=0) {
    auto& state=taxWindow;
    BYTE record[36]={};at<unsigned>(record,0)=state.character;
    at<unsigned short>(record,4)=0x7f00;at<unsigned short>(record,6)=static_cast<unsigned short>(state.strategyCommand);
    at<unsigned short>(record,8)=static_cast<unsigned short>(state.card);
    bool grantees=state.strategyCommand==70 && state.strategyBase!=0;
    at<unsigned short>(record,10)=static_cast<unsigned short>(preview ? (grantees ? 1 : 0) : state.strategyValue);
    at<unsigned>(record,12)=preview ? cursor : state.strategyTarget;
    at<BYTE>(record,16)=preview ? 1 : 0;
    if(!preview && state.strategyCommand==70)at<unsigned>(record,28)=state.strategyBase;
    strategyRequestToken=strategyRequestToken%65535+1;state.strategyToken=strategyRequestToken;
    at<unsigned short>(record,32)=static_cast<unsigned short>(state.strategyToken);
    state.pending=true;state.sentAt=GetTickCount64();state.message=preview ? L"목록을 조회하고 있습니다." : L"서버에 요청했습니다.";
    strategyDirectory.receiving=false;
    reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(state.world,0,0x46,record);
}
/** Commit reply: SocialRejection ordinal+1, 逮捕命令's outcome in the argument word (1 arrested, 2 evaded, 3 failed). */
static bool readJusticeAction(void* record) {
    auto& state=taxWindow;
    if(at<unsigned short>(record,4)!=0x7f00 || !state.visible || !state.strategyActive || !justiceCommand(state.strategyCommand) ||
        !state.pending || at<BYTE>(record,16)==1 || at<unsigned>(record,0)!=state.character ||
        at<unsigned short>(record,6)!=state.strategyCommand || at<unsigned short>(record,32)!=state.strategyToken)return false;
    state.pending=false;unsigned reply=at<unsigned short>(record,10),outcome=at<unsigned>(record,20);
    state.message=reply==4 ? L"이 직무 카드로 할 수 없습니다." : reply==6 ? L"대상이 같은 스폿이나 같은 부대에 없습니다." :
        reply==7 ? L"진행 중인 작업이 있거나 구금 중입니다." : reply==8 ? L"MCP가 부족합니다." :
        reply==10 ? L"체포 리스트에 없거나 기한이 지났습니다." : reply==13 ? L"직무 권한 밖의 대상입니다." :
        reply ? L"실행할 수 없습니다. 대상·카드·위치를 확인하세요." :
        state.strategyCommand==71 ? (outcome==1 ? L"체포했습니다. 대상은 구금실로 옮겨졌습니다." :
            outcome==2 ? L"대상이 피했습니다. 체포권은 남아 있습니다." : L"체포에 실패했습니다.") :
        state.strategyCommand==69 ? L"체포 리스트에 올렸습니다(게임 30일)." :
        state.strategyCommand==70 ? (state.strategyValue ? L"체포권을 회수했습니다." : L"체포권을 주었습니다(게임 7일, 1회).") :
        L"판결이 저장됐습니다.";
    memset(static_cast<BYTE*>(record)+4,0,32);return true;
}
static bool updateJusticeAction() {
    auto& state=taxWindow;unsigned command=state.strategyCommand;
    taxText(0,command==19 ? L"처단" : command==69 ? L"체포 허가" : command==70 ? L"집행 명령" : L"체포 명령");
    const StrategyDirectory::Person* row=nullptr;
    for(unsigned i=0;i<strategyDirectory.count;++i)if(strategyDirectory.people[i].id==state.strategyTarget)row=&strategyDirectory.people[i];
    wchar_t label[160];
    if(command==70 && !state.strategyBase) {
        if(row)swprintf_s(label,L"체포 리스트: %ls (ID %u)",row->name,row->id);else swprintf_s(label,L"체포 리스트가 비어 있습니다.");
        taxText(1,label);taxText(2,L"대상을 고른 뒤 '받을 사람'으로 넘어갑니다.");
    } else {
        if(row)swprintf_s(label,command==70 ? L"받을 사람: %ls (ID %u)" : L"대상: %ls (ID %u)",row->name,row->id);
        else swprintf_s(label,L"조건에 맞는 인물이 없습니다.");
        taxText(1,label);
        if(command==69)swprintf_s(label,L"혐의: %ls",justiceCharge(state.strategyValue));
        else if(command==19)swprintf_s(label,L"판결: %ls",justiceVerdict(state.strategyValue));
        else if(command==70)swprintf_s(label,L"체포 대상 ID %u · %ls",state.strategyBase,state.strategyValue ? L"회수" : L"부여");
        else swprintf_s(label,L"같은 스폿 또는 같은 부대의 리스트 인물");
        taxText(2,label);
    }
    taxText(3,command==69 || command==70 ? L"MCP 800" : command==19 ? L"MCP 320" : L"MCP 160 · 판정은 정보 능력 비교");
    taxText(4,L"");taxText(5,L"");taxText(6,state.message);
    taxText(10,command==70 && !state.strategyBase ? L"받을 사람" : command==71 ? L"-" : L"선택 바꾸기");
    taxText(11,L"이전");taxText(12,L"다음");taxText(13,L"목록 갱신");taxText(14,L"실행");taxText(15,L"닫기");
    taxEnabled(10,!state.pending && command!=71 && (command!=70 || row!=nullptr || state.strategyBase!=0));
    taxEnabled(11,!state.pending && strategyDirectory.count>1);taxEnabled(12,!state.pending && strategyDirectory.count>1);
    taxEnabled(13,!state.pending);taxEnabled(14,!state.pending && row!=nullptr && (command!=70 || state.strategyBase!=0));
    if(state.pending && GetTickCount64()-state.sentAt>10000)state.message=L"응답 대기 중입니다.";
    if(GetTickCount64()<state.acceptAfter)return true;
    BYTE event[52]={};
    for(int id=10;id<=15;++id)if(reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,void*,int)>(0x5015f0)(state.root,2,taxWidget(1,id),event,0)) {
        state.acceptAfter=GetTickCount64()+250;
        if(id==15)closeTaxWindow();
        else if(!state.pending) {
            if(id==10) {
                if(command==69)state.strategyValue=state.strategyValue%7+1;
                else if(command==19)state.strategyValue=state.strategyValue%3+1;
                else if(command==70 && !state.strategyBase) {state.strategyBase=state.strategyTarget;strategyDirectory={};sendJusticeAction(true);}
                else if(command==70)state.strategyValue^=1;
            }
            else if(id==11 || id==12)strategyNextPerson(id==11 ? -1 : 1);
            else if(id==13) {if(command==70)state.strategyBase=0;strategyDirectory={};sendJusticeAction(true);}
            else if(id==14 && row)sendJusticeAction(false);
        }
        break;
    }
    return true;
}
