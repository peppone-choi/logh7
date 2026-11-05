// U-17 工作 window (design-espionage.md): 潜入 75 picks a spot of this base from the server's list; 脱出 76, 情報 77,
// 破壊 78 and 煽動 79 only confirm; 侵入 80 and 帰還 81 pick a base
// within 10 cells from the server's list, and 侵入 also picks the forged surname (value word). Replies carry the outcome in the argument word (1 success, 3 failure), 情報's
// treasury in the base word and approval*256+peace in the amount word. Author: Peppone Choi.
#pragma once
static bool espionageCommand(unsigned command) {return command>=75 && command<=81;}
static unsigned espionageAlias;
static const wchar_t* espionageAliases[]={L"Muller",L"Schmidt",L"Fischer",L"Weber",L"Wagner",L"Becker",L"Hoffmann",L"Schulz"};
static bool espionageListed(unsigned command) {return command==75 || command>=80;}
static bool espionageCard(unsigned card) {return card==20 || card==58 || card==162 || card==194;}
static void sendEspionageAction(bool preview,unsigned cursor=0) {
    auto& state=taxWindow;
    BYTE record[36]={};at<unsigned>(record,0)=state.character;
    at<unsigned short>(record,4)=0x7f00;at<unsigned short>(record,6)=static_cast<unsigned short>(state.strategyCommand);
    at<unsigned short>(record,8)=static_cast<unsigned short>(state.card);
    at<unsigned>(record,12)=preview ? cursor : 0;at<BYTE>(record,16)=preview ? 1 : 0;
    if(!preview && espionageListed(state.strategyCommand))at<unsigned>(record,28)=state.strategyTarget;
    if(!preview && state.strategyCommand==80)at<unsigned short>(record,10)=static_cast<unsigned short>(espionageAlias);
    strategyRequestToken=strategyRequestToken%65535+1;state.strategyToken=strategyRequestToken;
    at<unsigned short>(record,32)=static_cast<unsigned short>(state.strategyToken);
    state.pending=true;state.sentAt=GetTickCount64();state.message=preview ? L"목록을 조회하고 있습니다." : L"서버에 요청했습니다.";
    strategyDirectory.receiving=false;
    reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(state.world,0,0x46,record);
}
static wchar_t espionageReport[160];
static bool readEspionageAction(void* record) {
    auto& state=taxWindow;
    if(at<unsigned short>(record,4)!=0x7f00 || !state.visible || !state.strategyActive || !espionageCommand(state.strategyCommand) ||
        !state.pending || at<BYTE>(record,16)==1 || at<unsigned>(record,0)!=state.character ||
        at<unsigned short>(record,6)!=state.strategyCommand || at<unsigned short>(record,32)!=state.strategyToken)return false;
    state.pending=false;unsigned reply=at<unsigned short>(record,10),outcome=at<unsigned>(record,20),command=state.strategyCommand;
    espionageReport[0]=0;
    if(!reply && command==77 && outcome==1) {
        unsigned mixed=at<unsigned short>(record,24);
        swprintf_s(espionageReport,L"금고 %u · 지지율 %u · 치안 %u",at<unsigned>(record,28),mixed>>8,mixed&255);
    }
    state.message=reply==4 ? L"첩보 직무 카드가 필요합니다." : reply==6 ? L"거점 안에서만 할 수 있습니다." :
        reply==7 ? L"진행 중인 작업이 있거나 구금 중입니다." : reply==8 ? L"MCP가 부족합니다." :
        reply==10 ? L"잠입한 상태가 아닙니다." : reply==13 ? L"대상이 맞지 않습니다(선동·침입은 타 진영, 귀환은 자진영 거점)." :
        reply ? L"실행할 수 없습니다." :
        outcome==1 ? (command==75 ? L"잠입했습니다(게임 7일)." : command==76 ? L"중앙 광장으로 탈출했습니다." :
            command==77 ? L"정보를 얻었습니다." : command==78 ? L"시한폭탄을 설치했습니다. 게임 내 4시간 안에 탈출하십시오." :
            command==79 ? L"선동에 성공했습니다. 지지율과 치안이 내려갔습니다." :
            command==80 ? L"위조 신분으로 침입했습니다." : L"자진영 거점으로 귀환했습니다.") :
        command==75 || command==76 || command==80 ? L"발각되어 체포됐습니다. 구금실로 옮겨집니다." :
        command==77 ? L"정보를 얻지 못했습니다." : command==78 ? L"시한장치를 설치하지 못했습니다." : L"시민이 응하지 않았습니다.";
    memset(static_cast<BYTE*>(record)+4,0,32);return true;
}
static bool updateEspionageAction() {
    auto& state=taxWindow;unsigned command=state.strategyCommand;
    static const wchar_t* titles[]={L"잠입 공작",L"탈출 공작",L"정보 공작",L"파괴 공작",L"선동 공작",L"침입 공작",L"귀환 공작"};
    taxText(0,titles[command-75]);
    const StrategyDirectory::Person* row=nullptr;
    for(unsigned i=0;i<strategyDirectory.count;++i)if(strategyDirectory.people[i].id==state.strategyTarget)row=&strategyDirectory.people[i];
    wchar_t label[160];
    if(command==75) {
        if(row)swprintf_s(label,L"잠입할 스폿: %ls (ID %u)",row->name,row->id);else swprintf_s(label,L"이 거점의 스폿 목록이 없습니다.");
        taxText(1,label);
    } else if(command>=80) {
        if(row)swprintf_s(label,L"%ls 거점: %ls (ID %u)",command==80 ? L"침입할" : L"귀환할",row->name,row->id);
        else swprintf_s(label,L"10칸 안에 %ls 거점이 없습니다.",command==80 ? L"타 진영" : L"자진영");
        taxText(1,label);
    } else taxText(1,command==76 ? L"잠입한 스폿에서 중앙 광장으로 나갑니다." : command==77 ? L"잠입한 스폿의 정보를 얻습니다." :
        command==78 ? L"잠입한 시설에 시한폭탄을 설치합니다(게임 내 4시간 뒤 폭발, 거점 생산 7일 정지)." : L"타 진영 거점의 시민을 선동합니다.");
    if(command==80) {swprintf_s(label,L"위조 성명: %ls (버튼으로 변경)",espionageAliases[espionageAlias%8]);taxText(2,label);}
    else taxText(2,espionageReport);
    taxText(3,command>=80 ? L"MCP 320 · 정보 능력 판정" : L"MCP 160 · 정보 능력 판정 · 특별경비 중이면 어려움");
    taxText(4,command==75 || command==76 || command==80 ? L"실패하면 발각되어 체포됩니다." : L"");taxText(5,command==80 ? L"위조 신분은 하루마다 발각 판정을 받습니다." : L"");taxText(6,state.message);
    taxText(10,command==80 ? L"성명 변경" : L"-");taxText(11,L"이전");taxText(12,L"다음");taxText(13,L"목록 갱신");taxText(14,L"실행");taxText(15,L"닫기");
    taxEnabled(10,!state.pending && command==80);bool listed=espionageListed(command);
    taxEnabled(11,!state.pending && listed && strategyDirectory.count>1);
    taxEnabled(12,!state.pending && listed && strategyDirectory.count>1);taxEnabled(13,!state.pending && listed);
    taxEnabled(14,!state.pending && (!listed || row!=nullptr));
    if(state.pending && GetTickCount64()-state.sentAt>10000)state.message=L"응답 대기 중입니다.";
    if(GetTickCount64()<state.acceptAfter)return true;
    BYTE event[52]={};
    for(int id=10;id<=15;++id)if(reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,void*,int)>(0x5015f0)(state.root,2,taxWidget(1,id),event,0)) {
        state.acceptAfter=GetTickCount64()+250;
        if(id==15)closeTaxWindow();
        else if(!state.pending) {
            if(id==10)espionageAlias=(espionageAlias+1)%8;
            else if(id==11 || id==12)strategyNextPerson(id==11 ? -1 : 1);
            else if(id==13) {strategyDirectory={};sendEspionageAction(true);}
            else if(id==14)sendEspionageAction(false);
        }
        break;
    }
    return true;
}
