// U-10 クーデター window (design-coup.md): 叛意 88, 説得 90, 叛乱 91 and 参加 92 only confirm on the personal card;
// 謀議 89 picks a same-spot person from the server's list; 査閲 72 runs on a police/intelligence card. Replies carry the
// outcome in the argument word (1 success, 3 failure) and a found value in the base word (謀議 target, 説得 loyalty,
// 叛乱 rebel base count, 査閲 leader id). Author: Peppone Choi.
#pragma once
static bool coupCommand(unsigned command) {return command==72 || command>=88 && command<=92;}
static bool coupCard(unsigned command,unsigned card) {
    if(command!=72)return card==0;
    static const unsigned cards[]={7,10,20,32,58,137,157,158,162,194};
    for(unsigned c:cards)if(c==card)return true;
    return false;
}
static wchar_t coupReport[160];
static void sendCoupAction(bool preview,unsigned cursor=0) {
    auto& state=taxWindow;
    BYTE record[36]={};at<unsigned>(record,0)=state.character;
    at<unsigned short>(record,4)=0x7f00;at<unsigned short>(record,6)=static_cast<unsigned short>(state.strategyCommand);
    at<unsigned short>(record,8)=static_cast<unsigned short>(state.card);
    at<unsigned>(record,12)=preview ? cursor : state.strategyCommand==89 ? state.strategyTarget : 0;at<BYTE>(record,16)=preview ? 1 : 0;
    strategyRequestToken=strategyRequestToken%65535+1;state.strategyToken=strategyRequestToken;
    at<unsigned short>(record,32)=static_cast<unsigned short>(state.strategyToken);
    state.pending=true;state.sentAt=GetTickCount64();state.message=preview ? L"목록을 조회하고 있습니다." : L"서버에 요청했습니다.";
    strategyDirectory.receiving=false;
    reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(state.world,0,0x46,record);
}
static bool readCoupAction(void* record) {
    auto& state=taxWindow;
    if(at<unsigned short>(record,4)!=0x7f00 || !state.visible || !state.strategyActive || !coupCommand(state.strategyCommand) ||
        !state.pending || at<BYTE>(record,16)==1 || at<unsigned>(record,0)!=state.character ||
        at<unsigned short>(record,6)!=state.strategyCommand || at<unsigned short>(record,32)!=state.strategyToken)return false;
    state.pending=false;unsigned reply=at<unsigned short>(record,10),outcome=at<unsigned>(record,20),command=state.strategyCommand;
    unsigned found=at<unsigned>(record,28);coupReport[0]=0;
    if(!reply && outcome==1) {
        if(command==90)swprintf_s(coupReport,L"부대 반란 충성도: %u",found);
        else if(command==91)swprintf_s(coupReport,L"반란 진영이 된 거점: %u곳",found);
        else if(command==72)swprintf_s(coupReport,L"쿠데타 징후: 수모자 인물 ID %u",found);
    }
    state.message=reply==4 ? L"이 명령을 쓸 직무 카드가 없습니다(叛意는 직무 보유 필요)." : reply==5 ? L"준장 이상만 수모자가 될 수 있습니다." :
        reply==6 ? L"자진영 정규 거점에서만 할 수 있습니다." : reply==7 ? L"이미 모의에 속해 있거나 구금 중입니다." :
        reply==8 ? (command==72 ? L"MCP가 부족합니다." : L"PCP가 부족합니다.") :
        reply==10 ? L"모의 상태가 맞지 않습니다(수모자·동의자·참가자 확인)." :
        reply==13 ? L"대상이 맞지 않습니다." : reply ? L"실행할 수 없습니다." :
        outcome==1 ? (command==88 ? L"쿠데타 수모자가 되었습니다." : command==89 ? L"상대가 쿠데타 참가에 동의했습니다." :
            command==90 ? L"부대를 설득했습니다." : command==91 ? L"반란을 일으켰습니다." : command==92 ? L"쿠데타에 참가했습니다." :
            L"쿠데타 징후를 찾았습니다.") :
        command==89 ? L"상대가 거절했습니다. 제안 사실이 상대에게 알려집니다." : L"쿠데타 징후를 찾지 못했습니다.";
    memset(static_cast<BYTE*>(record)+4,0,32);return true;
}
static bool updateCoupAction() {
    auto& state=taxWindow;unsigned command=state.strategyCommand;
    taxText(0,command==72 ? L"사열" : command==88 ? L"반의" : command==89 ? L"모의" : command==90 ? L"설득" : command==91 ? L"반란" : L"참가");
    const StrategyDirectory::Person* row=nullptr;
    for(unsigned i=0;i<strategyDirectory.count;++i)if(strategyDirectory.people[i].id==state.strategyTarget)row=&strategyDirectory.people[i];
    wchar_t label[160];
    if(command==89) {
        if(row)swprintf_s(label,L"참가를 교섭할 인물: %ls (ID %u)",row->name,row->id);else swprintf_s(label,L"같은 스폿에 교섭할 인물이 없습니다.");
        taxText(1,label);
    } else taxText(1,command==72 ? L"같은 진영의 쿠데타 징후를 찾습니다." : command==88 ? L"쿠데타의 수모자가 됩니다." :
        command==90 ? L"소속 부대 유닛의 반란 충성도를 올립니다(+10)." :
        command==91 ? L"쿠데타를 실행합니다. 이 거점과 참가자의 거점이 반란 진영이 됩니다." : L"동의한 쿠데타에 참가합니다.");
    taxText(2,coupReport);
    swprintf_s(label,L"%ls %u",command==72 ? L"MCP" : L"PCP",command==72 || command==92 ? 160u : 640u);taxText(3,label);
    taxText(4,command==91 ? L"수모자가 구금되거나 반란 거점을 모두 잃으면 진압됩니다." : command==88 ? L"직무 보유·준장 이상" : L"");
    taxText(5,L"");taxText(6,state.message);
    taxText(10,L"-");taxText(11,L"이전");taxText(12,L"다음");taxText(13,L"목록 갱신");taxText(14,L"실행");taxText(15,L"닫기");
    taxEnabled(10,false);taxEnabled(11,!state.pending && command==89 && strategyDirectory.count>1);
    taxEnabled(12,!state.pending && command==89 && strategyDirectory.count>1);taxEnabled(13,!state.pending && command==89);
    taxEnabled(14,!state.pending && (command!=89 || row!=nullptr));
    if(state.pending && GetTickCount64()-state.sentAt>10000)state.message=L"응답 대기 중입니다.";
    if(GetTickCount64()<state.acceptAfter)return true;
    BYTE event[52]={};
    for(int id=10;id<=15;++id)if(reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,void*,int)>(0x5015f0)(state.root,2,taxWidget(1,id),event,0)) {
        state.acceptAfter=GetTickCount64()+250;
        if(id==15)closeTaxWindow();
        else if(!state.pending) {
            if(id==11 || id==12)strategyNextPerson(id==11 ? -1 : 1);
            else if(id==13) {strategyDirectory={};sendCoupAction(true);}
            else if(id==14)sendCoupAction(false);
        }
        break;
    }
    return true;
}
