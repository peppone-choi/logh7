// U-08 production window (ProductionActions): 施設建設 31·休止 32·再稼動 33·破棄 34 confirm on the planet where the
// governor/lord stands; 艦艇建造 35 and 募兵 36 pick a unit kind from the server's list (row id = kind + 1) and send the
// kind in the value word. Author: Peppone Choi.
#pragma once
static bool productionCommand(unsigned command) {return command>=31 && command<=36;}
static bool productionCard(unsigned card) {return card==54 || card==66 || card==190 || card==192;}
static void sendProductionAction(bool preview,unsigned cursor=0) {
    auto& state=taxWindow;unsigned command=state.strategyCommand;
    BYTE record[36]={};at<unsigned>(record,0)=state.character;
    at<unsigned short>(record,4)=0x7f00;at<unsigned short>(record,6)=static_cast<unsigned short>(command);
    at<unsigned short>(record,8)=static_cast<unsigned short>(state.card);
    if(!preview && (command==35 || command==36) && state.strategyTarget>0)
        at<unsigned short>(record,10)=static_cast<unsigned short>(state.strategyTarget-1);
    at<unsigned>(record,12)=preview ? cursor : 0;at<BYTE>(record,16)=preview ? 1 : 0;
    strategyRequestToken=strategyRequestToken%65535+1;state.strategyToken=strategyRequestToken;
    at<unsigned short>(record,32)=static_cast<unsigned short>(state.strategyToken);
    state.pending=true;state.sentAt=GetTickCount64();state.message=preview ? L"목록을 조회하고 있습니다." : L"서버에 요청했습니다.";
    strategyDirectory.receiving=false;
    reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(state.world,0,0x46,record);
}
static bool readProductionAction(void* record) {
    auto& state=taxWindow;
    if(at<unsigned short>(record,4)!=0x7f00 || !state.visible || !state.strategyActive || !productionCommand(state.strategyCommand) ||
        !state.pending || at<BYTE>(record,16)==1 || at<unsigned>(record,0)!=state.character ||
        at<unsigned short>(record,6)!=state.strategyCommand || at<unsigned short>(record,32)!=state.strategyToken)return false;
    state.pending=false;unsigned reply=at<unsigned short>(record,10),command=state.strategyCommand;
    state.message=reply==4 ? L"통치 카드(지사·사정관·봉토)가 필요합니다." : reply==6 ? L"자기가 통치하는 행성에서만 할 수 있습니다." :
        reply==7 ? L"이미 주문 중이거나 행동할 수 없습니다." : reply==8 ? L"MCP가 부족합니다." :
        reply==11 ? L"금고·자원·인구가 부족합니다." : reply==13 ? L"이 행성에서는 할 수 없습니다(조병창 상태 확인)." : reply ? L"실행할 수 없습니다." :
        command==31 ? L"조병창 건설을 시작했습니다(30일 뒤 완공, 지방 금고 1억)." : command==32 ? L"조병창을 휴지했습니다." :
        command==33 ? L"조병창을 재가동했습니다." : command==34 ? L"조병창을 파기했습니다." :
        command==35 ? L"함정 1유닛 건조를 주문했습니다." : L"육전병 1유닛을 모병했습니다.";
    memset(static_cast<BYTE*>(record)+4,0,32);return true;
}
static bool updateProductionAction() {
    auto& state=taxWindow;unsigned command=state.strategyCommand;
    static const wchar_t* titles[]={L"시설 건설",L"시설 휴지",L"시설 재가동",L"시설 파기",L"함정 건조",L"모병"};
    taxText(0,titles[command-31]);
    const StrategyDirectory::Person* row=nullptr;
    for(unsigned i=0;i<strategyDirectory.count;++i)if(strategyDirectory.people[i].id==state.strategyTarget)row=&strategyDirectory.people[i];
    wchar_t label[160];bool listed=command==35 || command==36;
    if(listed) {if(row)swprintf_s(label,L"%ls: %ls",command==35 ? L"건조할 함종" : L"모병할 병종",row->name);else swprintf_s(label,L"목록이 없습니다.");}
    else swprintf_s(label,L"%ls",command==31 ? L"이 행성에 조병창을 짓습니다." : command==32 ? L"조병창의 자동 함정 생산을 멈춥니다." :
        command==33 ? L"멈춘 조병창을 다시 가동합니다." : L"지은 조병창을 없앱니다.");
    taxText(1,label);
    taxText(2,command==35 ? L"자원·시간은 자동 생산과 같습니다. 완성되면 거점 창고로 들어갑니다." : command==36 ? L"성인 1000명·식량·물자를 씁니다." : L"");
    taxText(3,command==36 ? L"MCP 160" : L"MCP 80");taxText(4,L"");taxText(5,L"");taxText(6,state.message);
    taxText(10,L"-");taxText(11,L"이전");taxText(12,L"다음");taxText(13,L"목록 갱신");taxText(14,L"실행");taxText(15,L"닫기");
    taxEnabled(10,false);taxEnabled(11,!state.pending && listed && strategyDirectory.count>1);
    taxEnabled(12,!state.pending && listed && strategyDirectory.count>1);taxEnabled(13,!state.pending && listed);
    taxEnabled(14,!state.pending && (!listed || row!=nullptr));
    if(state.pending && GetTickCount64()-state.sentAt>10000)state.message=L"응답 대기 중입니다.";
    if(GetTickCount64()<state.acceptAfter)return true;
    BYTE event[52]={};
    for(int id=10;id<=15;++id)if(reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,void*,int)>(0x5015f0)(state.root,2,taxWidget(1,id),event,0)) {
        state.acceptAfter=GetTickCount64()+250;
        if(id==15)closeTaxWindow();
        else if(!state.pending) {
            if(id==11 || id==12)strategyNextPerson(id==11 ? -1 : 1);
            else if(id==13) {strategyDirectory={};sendProductionAction(true);}
            else if(id==14)sendProductionAction(false);
        }
        break;
    }
    return true;
}
