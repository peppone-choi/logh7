// Transport editor: 輸送計画 28 / 輸送中止 29 / 搬出入 65 (U-15). The CD has no window for them.
// 28 sends base=source, target=destination, amount=supplies, value=unit kind (0xffff none), argument=unit count;
// 29 target=package; 65 argument=transport outfit, target=package, value=commit intent copied from the selected row rank:
// 1=load from warehouse, 2=unload from fleet, 0=preview or an older server whose rows carry neither.
// Package rows read "destination/supplies[+][*]" and can be truncated, so the star (aboard a fleet) is only the fallback when rank is not 1 or 2.
// Author: Peppone Choi.
#pragma once
static void strategyNextBase(int direction);
static void strategyNextPerson(int direction);
// Package pages: id>cursor ascending, 200 rows, at most 4096 packages. The fixed request record carries one
// cursor, so earlier ones stay here. 21 slots also hold the empty page after an exactly full 20th page.
static constexpr unsigned transportPageLimit=4096/200+1;
static struct TransportWindow {
    unsigned kind,count;
    bool refresh;
    wchar_t receipt[160];
    unsigned page,requestedPage,cursors[transportPageLimit];
} transportWindow={};
static bool transportCommand(unsigned command) {return command==28 || command==29 || command==65;}
static bool transportCard(unsigned command,unsigned card) {
    return command==65 ? card==44 || card==180 : card==24 || card==25 || card==150;
}
static const StrategyDirectory::Person* transportPackage() {
    for(unsigned i=0;i<strategyDirectory.count;++i)if(strategyDirectory.people[i].id==taxWindow.strategyTarget)return &strategyDirectory.people[i];
    return nullptr;
}
static void sendTransportAction(bool preview,unsigned cursor=0) {
    auto& state=taxWindow;auto& transport=transportWindow;unsigned command=state.strategyCommand;
    auto cargo=command==65 && !preview ? transportPackage() : nullptr;
    if(command==65 && !preview && !cargo)return;
    BYTE record[36]={};at<unsigned>(record,0)=state.character;
    at<unsigned short>(record,4)=0x7f00;at<unsigned short>(record,6)=static_cast<unsigned short>(command);
    at<unsigned short>(record,8)=static_cast<unsigned short>(state.card);
    at<BYTE>(record,16)=preview ? 1 : 0;
    if(command==28) {
        at<unsigned short>(record,10)=static_cast<unsigned short>(transport.kind);at<unsigned>(record,12)=state.strategyTarget;
        at<unsigned>(record,20)=transport.kind==0xffff ? 0 : transport.count;
        at<unsigned short>(record,24)=static_cast<unsigned short>(state.strategyAmount);at<unsigned>(record,28)=state.strategyBase;
    } else {
        at<unsigned>(record,12)=preview ? cursor : state.strategyTarget;
        if(command==65)at<unsigned>(record,20)=state.strategyOutfit;
        if(cargo)at<unsigned short>(record,10)=cargo->rank==1 || cargo->rank==2 ? static_cast<unsigned short>(cargo->rank) : 0;
    }
    strategyRequestToken=strategyRequestToken%65535+1;state.strategyToken=strategyRequestToken;
    at<unsigned short>(record,32)=static_cast<unsigned short>(state.strategyToken);
    state.pending=true;state.sentAt=GetTickCount64();
    state.message=preview ? L"수송 패키지 목록을 조회하고 있습니다." : L"서버에 요청했습니다.";
    strategyDirectory.receiving=false;
    reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(state.world,0,0x46,record);
}
/** The page number applies when the rows arrive; a rejection keeps the page on screen. */
static void requestTransportPage(unsigned page,unsigned cursor) {
    auto& transport=transportWindow;
    transport.requestedPage=page;transport.cursors[page]=cursor;
    sendTransportAction(true,cursor);
}
/** Commit reply. StrategyActionRejection ordinal+1. */
static bool readTransportAction(void* record) {
    auto& state=taxWindow;
    if(at<unsigned short>(record,4)!=0x7f00 || !state.visible || !state.strategyActive || !transportCommand(state.strategyCommand) ||
        !state.pending || at<BYTE>(record,16)==1 || at<unsigned>(record,0)!=state.character ||
        at<unsigned short>(record,6)!=state.strategyCommand || at<unsigned short>(record,32)!=state.strategyToken)return false;
    state.pending=false;unsigned reply=at<unsigned short>(record,10);unsigned command=state.strategyCommand;
    transportWindow.requestedPage=transportWindow.page;
    state.message=reply==4 ? L"이 명령을 실행할 직무 카드가 아닙니다." :
        reply==5 ? L"본인이 지휘하는 수송함대가 아니거나 다른 부대가 실은 패키지입니다." :
        reply==6 ? (command==65 ? L"기함과 수송함대를 함께 정박시키세요. 반입은 수송처 또는 목적지 상실 시 다른 아군 거점." : L"수송원 거점이 아군 거점이 아닙니다.") :
        reply==7 ? (command==29 ? L"이미 수송함대에 실은 패키지는 중지할 수 없습니다." : L"진행 중인 작업이 끝난 뒤 실행하십시오.") :
        reply==8 ? L"수송함대의 물자 탑재량 또는 유닛 칸이 부족합니다." :
        reply==9 ? (command==28 ? L"이 거점의 열린 패키지가 20개에 도달했습니다." : L"창고의 물자 또는 유닛 한도를 넘겨 처리하지 못했습니다. 여유를 만든 뒤 다시 시도하세요.") :
        reply==11 ? L"CP가 부족합니다." :
        reply==13 ? L"수송원 창고의 물자나 유닛이 부족합니다." :
        reply==15 ? (command==28 ? L"수송원·수송처는 서로 다른 아군 거점이어야 합니다." : L"패키지가 없거나 이미 처리됐습니다. 목록을 갱신하세요.") :
        reply==17 ? L"화물 상태가 바뀌었습니다. 목록을 갱신한 뒤 다시 시도하세요." :
        reply ? L"명령 거부 · 직무·장소·대상·CP를 확인하세요." :
        command==28 ? L"수송 패키지를 만들어 수송원 수송 창고에 두었습니다." :
        command==29 ? L"수송을 중지하고 패키지를 수송원 창고로 되돌렸습니다." : L"반출입을 마쳤습니다.";
    if(reply)transportWindow.receipt[0]=0;
    else if(command==28)wcscpy_s(transportWindow.receipt,state.message);
    else swprintf_s(transportWindow.receipt,L"패키지 %u · %ls",state.strategyTarget,state.message);
    if(!reply && command!=28)transportWindow.refresh=true;
    memset(static_cast<BYTE*>(record)+4,0,32);return true;
}
static bool transportExecutable() {
    auto& state=taxWindow;
    return state.strategyCommand==28 ? state.strategyBase && state.strategyTarget && (state.strategyAmount || transportWindow.kind!=0xffff) :
        transportPackage()!=nullptr && (state.strategyCommand!=65 || state.strategyOutfit);
}
static void transportNextPackage(int direction) {
    auto& state=taxWindow;auto& transport=transportWindow;auto& directory=strategyDirectory;
    unsigned selected=0;
    for(unsigned i=0;i<directory.count;++i)if(directory.people[i].id==state.strategyTarget)selected=i;
    if(direction<0 && !selected && transport.page) {requestTransportPage(transport.page-1,transport.cursors[transport.page-1]);return;}
    if(!directory.count)return;
    if(direction>0 && selected+1==directory.count && directory.count==200 && transport.page+1<transportPageLimit) {
        requestTransportPage(transport.page+1,directory.people[selected].id);return;
    }
    state.strategyTarget=directory.people[(selected+directory.count+direction)%directory.count].id;
}
static void transportSystemLabel(unsigned destination,wchar_t (&label)[96]) {
    wcscpy_s(label,L"미수신");
    if(!destination){wcscpy_s(label,L"선택 없음");return;}
    auto base=pickerBase(destination);
    if(!base)return;
    unsigned grid=logh::infoShort(base,0x10);
    if(grid>=5000)return;
    auto cell=reinterpret_cast<const BYTE*(__stdcall*)(int,int)>(0x4c8b70)(grid%100,grid/100);
    if(!cell || cell[0]<3 || cell[0]>88)return;
    auto catalog=reinterpret_cast<const char*(__stdcall*)(unsigned)>(0x4c8c90)(cell[0]);
    std::wstring system;
    if(catalog && !logh::japaneseFragment(catalog,system)) {
        wchar_t raw[64]={};MultiByteToWideChar(932,0,catalog,-1,raw,_countof(raw));system=raw;
    }
    wcscpy_s(label,system.empty() ? L"이름 미수신" : system.c_str());
}
static void drawTransportAction() {
    auto& state=taxWindow;auto& transport=transportWindow;unsigned command=state.strategyCommand;
    wchar_t label[200],name[32];
    taxText(0,command==28 ? L"수송 계획" : command==29 ? L"수송 중지" : L"반출입");
    if(command==28) {
        static const wchar_t* fields[]={L"수송원 거점",L"수송처 거점",L"군수물자",L"유닛 종류",L"유닛 수"};
        wchar_t system[96];transportSystemLabel(state.strategyTarget,system);
        swprintf_s(label,L"현재 선택: %ls · 수송처 성계: %ls",fields[state.strategyField%5],system);taxText(1,label);
        pickerName(state.strategyBase,name);swprintf_s(label,L"수송원: %ls",name[0] ? name : L"-");taxText(2,label);
        pickerName(state.strategyTarget,name);swprintf_s(label,L"수송처: %ls",name[0] ? name : L"-");taxText(3,label);
        if(transport.kind==0xffff)swprintf_s(label,L"군수물자 %u · 유닛 없음",state.strategyAmount);
        else {
            wchar_t item[64];
            outfitItemName(transport.kind<1000,transport.kind>=1000 ? transport.kind-1000 : transport.kind,item);
            swprintf_s(label,L"물자 %u · %ls × %u",state.strategyAmount,item,transport.count);
        }
        taxText(4,label);taxText(5,L"MCP 80 · 수송원 창고에서 빼서 수송 창고에 둡니다.");
    } else {
        auto pack=transportPackage();
        taxText(1,command==29 ? L"아직 싣지 않은 본 진영 패키지를 수송원 창고로 되돌립니다." :
            L"수송원에서 반출 · 수송처에서 반입. 목적지를 잃으면 다른 아군 거점에도 반입합니다.");
        unsigned destination=0,supplies=0;
        if(pack)swscanf_s(pack->name,L"%u/%u",&destination,&supplies);
        wchar_t system[96];transportSystemLabel(destination,system);
        if(command==65)swprintf_s(label,L"수송함대 ID %u · 수송처 성계: %ls",state.strategyOutfit,system);
        else swprintf_s(label,L"패키지 %u개 · 수송처 성계: %ls",strategyDirectory.count,system);
        taxText(2,label);
        if(pack) {
            pickerName(destination,name);
            swprintf_s(label,L"패키지 %u → %ls",pack->id,name[0] ? name : L"-");taxText(3,label);
            bool units=wcschr(pack->name,L'+')!=nullptr,aboard=pack->rank==2 || pack->rank!=1 && wcschr(pack->name,L'*')!=nullptr;
            swprintf_s(label,L"%ls %u%ls · %ls",aboard ? L"출항 물자" : L"군수물자",supplies,units ? L" + 유닛" : L"",aboard ? L"적재 중(반입 대상)" : L"수송 창고(반출 대상)");
            taxText(4,label);
        } else {taxText(3,L"선택할 패키지가 없습니다.");taxText(4,L"");}
        taxText(5,command==65 ? L"MCP 160 · 격침 비율만큼 반입량이 줄며, 유닛 수는 소수점을 버립니다." : L"MCP 80");
    }
    taxText(6,transport.receipt[0] && !state.pending ? transport.receipt : state.message);
    taxText(10,L"선택 항목");taxText(11,L"이전 / −");taxText(12,L"다음 / +");taxText(13,L"목록 갱신");taxText(14,L"실행");taxText(15,L"닫기");
    bool plan=command==28;
    taxEnabled(10,!state.pending && plan);taxEnabled(11,!state.pending && (plan || strategyDirectory.count>1 || transport.page));
    taxEnabled(12,!state.pending && (plan || strategyDirectory.count>1));taxEnabled(13,!state.pending && !plan);
    taxEnabled(14,!state.pending && transportExecutable());
}
static void transportStep(int step) {
    auto& state=taxWindow;auto& transport=transportWindow;
    switch(state.strategyField%5) {
    case 0:strategyNextBase(step);break;
    case 1: {unsigned source=state.strategyBase;state.strategyBase=state.strategyTarget;strategyNextBase(step);
        state.strategyTarget=state.strategyBase;state.strategyBase=source;break;}
    case 2: {unsigned unit=state.strategyAmount>=1000 || step>0 && state.strategyAmount+100>=1000 ? 1000 : 100;
        int next=static_cast<int>(state.strategyAmount)+step*static_cast<int>(unit);
        state.strategyAmount=next<0 ? 0 : next>65000 ? 65000 : static_cast<unsigned>(next);break;}
    case 3: {
        // 0xffff (none) -> ship kinds 0..199 -> crew/marine kinds 1000..1015 -> none.
        unsigned order=transport.kind==0xffff ? 0 : transport.kind>=1000 ? 201+transport.kind-1000 : transport.kind+1;
        order=(order+217+step)%217;
        transport.kind=order==0 ? 0xffff : order<=200 ? order-1 : 1000+order-201;
        if(transport.kind!=0xffff && !transport.count)transport.count=1;
        break;
    }
    default:if(transport.kind!=0xffff) {int next=static_cast<int>(transport.count)+step;transport.count=next<1 ? 1 : next>999 ? 999 : next;}
    }
}
static bool updateTransportAction() {
    auto& state=taxWindow;auto& transport=transportWindow;unsigned command=state.strategyCommand;
    if(!state.pending)transport.page=transport.requestedPage;
    drawTransportAction();
    if(state.pending && GetTickCount64()-state.sentAt>10000)state.message=L"응답 대기 중 · 재접속 후 실제 상태를 확인해 주세요.";
    if(transport.refresh && !state.pending) {transport.refresh=false;requestTransportPage(0,0);}
    if(GetTickCount64()<state.acceptAfter)return true;
    BYTE event[52]={};
    for(int id=10;id<=15;++id)if(reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,void*,int)>(0x5015f0)(state.root,2,taxWidget(1,id),event,0)) {
        state.acceptAfter=GetTickCount64()+250;
        if(id==15)closeTaxWindow();
        else if(!state.pending) {
            transport.receipt[0]=0;
            if(id==10 && command==28)state.strategyField=(state.strategyField+1)%5;
            else if(id==13 && command!=28)requestTransportPage(0,0);
            else if(id==11 || id==12) {
                if(command==28)transportStep(id==11 ? -1 : 1);else transportNextPackage(id==11 ? -1 : 1);
            } else if(id==14 && transportExecutable())sendTransportAction(false);
        }
        break;
    }
    return true;
}
static void openTransportAction(void* own) {
    auto& state=taxWindow;transportWindow={0xffff,0,false,{}};
    if(state.strategyCommand==28) {
        unsigned base=at<unsigned>(own,0x358);
        if(base && base<=65535)state.strategyBase=base;else strategyNextBase(1);
        state.strategyTarget=state.strategyBase;
        unsigned source=state.strategyBase;strategyNextBase(1);state.strategyTarget=state.strategyBase;state.strategyBase=source;
        state.strategyAmount=1000;state.message=L"수송원·수송처·품목을 고른 뒤 실행하십시오.";
    }
    drawTransportAction();
}
