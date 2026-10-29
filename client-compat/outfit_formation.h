// Revival unit formation/dissolution editor. Native 1200 selector 0x46. Author: 최병호.
#pragma once
static struct OutfitFormationWindow {
    struct Item {unsigned code,available,selected;wchar_t name[13];} items[200];
    unsigned kind,lease,base,count,row,field,quantity,supplies,phase,outfit;
    bool receiving,dirty,completed;
} formationWindow={};
static bool formationStrategy(unsigned command) {return command==25 || command==26;}
static unsigned formationKinds(unsigned card,unsigned* values) {
    switch(card) {
    case 24:values[0]=0;return 1;
    case 25:values[0]=1;values[1]=2;values[2]=3;return 3;
    case 164:values[0]=0;values[1]=1;values[2]=2;return 3;
    case 152:values[0]=3;return 1;
    default:return 0;
    }
}
static void sendOutfitFormation(unsigned phase) {
    auto& state=taxWindow;auto& form=formationWindow;
    BYTE record[36]={};at<unsigned>(record,0)=state.character;
    at<unsigned short>(record,4)=0x7f01;at<unsigned short>(record,6)=static_cast<unsigned short>(state.strategyCommand);
    at<unsigned short>(record,8)=static_cast<unsigned short>(state.card);at<unsigned short>(record,10)=static_cast<unsigned short>(form.kind);
    at<unsigned>(record,12)=phase==0 ? 0 : phase==6 && form.count ? form.items[form.row].code : form.lease;
    at<BYTE>(record,16)=static_cast<BYTE>(phase);at<unsigned>(record,28)=form.base;
    if(phase==1 && form.count) {at<unsigned>(record,20)=form.items[form.row].code;at<unsigned short>(record,24)=static_cast<unsigned short>(form.quantity);}
    if(phase==2)at<unsigned>(record,20)=form.supplies;
    strategyRequestToken=strategyRequestToken%65535+1;state.strategyToken=strategyRequestToken;
    at<unsigned short>(record,32)=static_cast<unsigned short>(state.strategyToken);
    form.phase=phase;form.receiving=false;state.pending=true;state.sentAt=GetTickCount64();state.message=L"서버에서 재고와 조건을 확인합니다.";
    reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(state.world,0,0x46,record);
}
static bool readOutfitFormationBegin(void* record) {
    if(at<unsigned short>(record,4)!=0x7f01)return false;
    auto& state=taxWindow;auto& form=formationWindow;
    form.receiving=false;
    if(state.visible && state.strategyActive && formationStrategy(state.strategyCommand) && state.pending &&
        at<unsigned>(record,0)==state.character && at<unsigned short>(record,6)==state.strategyCommand &&
        at<unsigned short>(record,8)==state.card && at<unsigned short>(record,32)==state.strategyToken) {
        unsigned responseStatus=at<unsigned short>(record,10);unsigned flag=at<BYTE>(record,16);
        if(!responseStatus) {
            form.base=at<unsigned>(record,28);form.supplies=at<unsigned>(record,20);form.dirty=false;
            if(flag==1) {form.lease=at<unsigned>(record,12);form.receiving=true;}
            else {
                state.pending=false;form.lease=0;
                if(flag==2) {form.completed=true;form.outfit=at<unsigned>(record,12);form.count=0;
                    state.message=state.strategyCommand==25 ? L"결성을 저장했습니다. 직속 상관이 지휘관을 임명할 수 있습니다." : L"해산과 잔존 병력·재고 반환을 저장했습니다.";}
                else state.message=L"편집 예약을 해제했습니다. CP를 사용하지 않았습니다.";
            }
        } else {
            state.pending=false;
            state.message=responseStatus==16 ? L"결성에 필요한 승조원이 부족합니다." : responseStatus==11 ? L"CP가 부족합니다." :
                responseStatus==13 ? L"현재 재고나 군수물자가 부족합니다." : responseStatus==9 ? L"편성·창고·그리드 한도를 확인하세요." :
                responseStatus==15 ? L"편집 예약이나 부대가 만료됐습니다. 닫고 다시 여세요." :
                L"명령 거부 · 직무·장소·손상·진행 중 명령을 확인하세요.";
        }
    }
    // Keep extension selections out of the original directory/cache callbacks.
    memset(static_cast<BYTE*>(record)+4,0,32);return true;
}
static bool readOutfitFormationPeople(void* record) {
    auto& form=formationWindow;if(!form.receiving)return false;form.receiving=false;
    auto& state=taxWindow;
    if(!state.visible || !state.strategyActive || !formationStrategy(state.strategyCommand))return true;
    unsigned count=at<BYTE>(record,0);if(count>200) {state.pending=false;state.message=L"목록이 클라이언트 한도를 초과했습니다.";return true;}
    unsigned old=form.count && form.row<form.count ? form.items[form.row].code : 0;form.count=count;form.row=0;
    for(unsigned i=0;i<count;++i) {
        auto row=static_cast<BYTE*>(record)+4+i*0x120;auto& item=form.items[i];
        item.code=at<unsigned>(row,0);item.available=at<unsigned short>(row,0x20);item.selected=at<unsigned short>(row,0x22);
        memset(item.name,0,sizeof(item.name));memcpy(item.name,row+6,12*sizeof(wchar_t));if(item.code==old)form.row=i;
    }
    form.quantity=count ? form.items[form.row].selected : 0;state.pending=false;
    state.message=count ? L"선택값 저장 후 확정하십시오. 확정 때 현재 조건을 다시 검사합니다." : L"현재 장소에 선택 가능한 병력이나 부대가 없습니다.";
    return true;
}
static void drawOutfitFormation() {
    auto& state=taxWindow;auto& form=formationWindow;bool create=state.strategyCommand==25;
    const wchar_t* kinds[]={L"함대",L"수송 함대",L"순찰 부대",L"육전 부대"};
    taxText(0,create ? L"부대 결성" : L"부대 해산");taxText(1,create ? L"거점 병력으로 결성 · 지휘관은 별도 임명" : L"같은 거점에 주류 중인 부대를 해산");
    static const wchar_t* fields[]={L"병종 선택",L"수량 ±1",L"수량 ±10",L"군수 ±100",L"군수 ±1000"};
    taxText(2,create && form.lease ? fields[form.field] : L"부대 종류 선택 후 목록을 여십시오.");
    wchar_t label[160];swprintf_s(label,L"%ls · 거점 %u",kinds[form.kind],form.base);taxText(3,label);
    if(form.completed)swprintf_s(label,L"저장된 부대 ID: %u",form.outfit);
    else if(form.count) {auto& item=form.items[form.row];swprintf_s(label,L"%ls · %u/%u · %u개",item.name,form.row+1,form.count,item.available);}
    else swprintf_s(label,L"선택 가능한 항목 없음");taxText(4,label);
    if(create)swprintf_s(label,L"수량 %u · 군수 %u · MCP 320",form.quantity,form.supplies);
    else swprintf_s(label,L"MCP 160 · 잔존 병력 반환 · 손상·명령 중 해산 불가");taxText(5,label);
    taxText(6,form.dirty ? L"아직 저장하지 않은 선택값이 있습니다." : state.message);
    taxText(10,create && form.lease ? L"편집 항목" : L"부대 종류");taxText(11,L"이전 / −");taxText(12,L"다음 / +");
    taxText(13,create && form.lease ? L"선택값 저장" : L"목록 열기");taxText(14,create ? L"결성 확정" : L"해산 확정");taxText(15,L"취소 / 닫기");
    taxEnabled(10,!state.pending && !form.completed && !form.dirty);
    taxEnabled(11,!state.pending && !form.completed);taxEnabled(12,!state.pending && !form.completed);
    taxEnabled(13,!state.pending && !form.completed);taxEnabled(14,!state.pending && !form.completed && !form.dirty &&
        (create ? form.lease>0 : form.count>0));
}
static bool updateOutfitFormation() {
    drawOutfitFormation();
    auto& state=taxWindow;auto& form=formationWindow;bool create=state.strategyCommand==25;
    if(state.pending && GetTickCount64()-state.sentAt>10000)state.message=L"응답 대기 중 · 재접속 후 실제 상태를 확인하세요.";
    if(GetTickCount64()<state.acceptAfter)return true;
    BYTE event[52]={};
    for(int id=10;id<=15;++id)if(reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,void*,int)>(0x5015f0)(state.root,2,taxWidget(1,id),event,0)) {
        state.acceptAfter=GetTickCount64()+250;
        if(id==15) {if(!state.pending && create && form.lease)sendOutfitFormation(4);closeTaxWindow();}
        else if(!state.pending && !form.completed) {
            if(id==10 && !form.dirty) {
                if(create && form.lease)form.field=(form.field+1)%5;
                else {unsigned values[4]={},n=formationKinds(state.card,values),index=0;
                    for(unsigned i=0;i<n;++i)if(values[i]==form.kind)index=i;
                    if(n)form.kind=values[(index+1)%n];form.count=0;}
            } else if(id==13) {
                if(create && form.lease) {if(form.field>=3)sendOutfitFormation(2);else if(form.count)sendOutfitFormation(1);}
                else sendOutfitFormation(0);
            } else if(id==14 && !form.dirty && (create ? form.lease>0 : form.count>0))sendOutfitFormation(create ? 3 : 6);
            else if(id==11 || id==12) {
                int direction=id==11 ? -1 : 1;
                if(!create || form.field==0) {
                    if(form.count && !form.dirty) {form.row=(form.row+form.count+direction)%form.count;form.quantity=form.items[form.row].selected;}
                } else if(form.field<=2 && form.count) {
                    unsigned limit=form.items[form.row].available;int value=static_cast<int>(form.quantity)+direction*(form.field==1 ? 1 : 10);
                    form.quantity=value<0 ? 0 : static_cast<unsigned>(value)>limit ? limit : static_cast<unsigned>(value);form.dirty=true;
                } else if(form.field>=3) {
                    long long value=static_cast<long long>(form.supplies)+direction*(form.field==3 ? 100 : 1000);
                    form.supplies=value<0 ? 0 : value>0x7fffffff ? 0x7fffffff : static_cast<unsigned>(value);form.dirty=true;
                }
            }
        }
        break;
    }
    return true;
}
