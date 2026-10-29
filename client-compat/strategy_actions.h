// Missing strategic factories and a dedicated revival form over the native simple-data codec.
// Author: 최병호. No original server opcode is assigned to the reconstructed commands.
#pragma once
#include "character_cards.h"
static struct StrategyDirectory {
    struct Person {unsigned id,rank;wchar_t name[13];} people[200];
    unsigned count;
    bool receiving;
} strategyDirectory={};
static unsigned strategyRequestToken=0;
static bool operationStrategy(unsigned command) {return command>=22 && command<=24;}
static bool personnelStrategy(unsigned command) {return command<=4 || command==8 || command==9;}
static bool armyStrategy(unsigned command) {return command==48 || command==50 || command==53;}
static bool strategyActionCommand(unsigned command) {
    return personnelStrategy(command) || operationStrategy(command) || armyStrategy(command) || command==84 || command==85;
}
static const wchar_t* strategyActionName(unsigned command) {
    switch(command) {
    case 0:return L"승진";case 1:return L"발탁";case 2:return L"강등";
    case 3:return L"서작";case 4:return L"서훈";case 8:return L"봉토 수여";case 9:return L"봉토 직할";
    case 22:return L"작전 계획";case 23:return L"작전 철회";case 24:return L"발령";
    case 48:return L"육전 훈련";case 50:return L"육전 전술 훈련";
    case 53:return L"무력 진압";case 84:return L"퇴역";case 85:return L"지원";
    default:return L"전략 명령";
    }
}
static unsigned strategyOutfits(void* own,unsigned* values) {
    logh::CharacterCards cards;if(!logh::CharacterCards::read(own,0x370,cards))return 0;
    unsigned count=0;
    for(unsigned i=0;i<cards.count;++i) {
        unsigned kind=cards.rows[i].kind;
        if(kind==39 || kind==44 || kind==47 || kind==50 || kind==175 || kind==180 || kind==183 || kind==186)
            values[count++]=cards.rows[i].target;
    }
    return count;
}
static void sendStrategyAction(bool preview=false,unsigned cursor=0) {
    auto& state=taxWindow;
    BYTE record[36]={};at<unsigned>(record,0)=state.character;
    at<unsigned short>(record,4)=0x7f00;at<unsigned short>(record,6)=static_cast<unsigned short>(state.strategyCommand);
    at<unsigned short>(record,8)=static_cast<unsigned short>(state.card);
    at<unsigned short>(record,10)=static_cast<unsigned short>(state.strategyValue);
    at<unsigned>(record,12)=preview ? cursor : armyStrategy(state.strategyCommand) ? state.strategyOutfit : state.strategyTarget;
    at<BYTE>(record,16)=preview ? 1 : 0;at<unsigned>(record,28)=preview ? 0 : state.strategyBase;
    if(state.strategyCommand==22) {at<unsigned>(record,20)=state.strategyArgument;at<unsigned short>(record,24)=static_cast<unsigned short>(state.strategyAmount);}
    strategyRequestToken=strategyRequestToken%65535+1;state.strategyToken=strategyRequestToken;
    at<unsigned short>(record,32)=static_cast<unsigned short>(state.strategyToken);
    state.pending=true;state.sentAt=GetTickCount64();state.message=preview ? L"대상 인물 목록을 조회하고 있습니다." : L"서버에 요청했습니다.";
    strategyDirectory.receiving=false;
    // The dispatcher subtracts one before its switch: case 0x45 (1200) uses selector 0x46.
    reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(state.world,0,0x46,record);
}
static BYTE openStrategyAction(void* manager,int command,unsigned card) {
    auto world=*reinterpret_cast<void**>(0x7ccffc);auto own=world ? at<void*>(world,8) : nullptr;
    if(!own || !at<BYTE>(own,0) || at<void*>(manager,0x18) || *reinterpret_cast<void**>(0x21aa888)!=own || !createTaxWindow())return 0;
    logh::CharacterCards cards;if(!logh::CharacterCards::read(own,0x370,cards))return 0;
    bool held=false;for(unsigned i=0;i<cards.count;++i)if(cards.rows[i].kind==card)held=true;
    if(!held || (!personnelStrategy(command) && !operationStrategy(command) && (command==84 ? card!=0 : card!=59 && card!=195)))return 0;
    closePlanetInformation();auto& state=taxWindow;
    state.world=world;state.character=at<unsigned>(own,0x24);state.card=card;
    state.strategyActive=true;state.strategyCommand=command;state.strategyOutfit=0;
    state.strategyTarget=0;state.strategyBase=0;state.strategyValue=command==3 ? 5 : 0;state.strategyField=0;
    state.strategyAmount=1;state.strategyArgument=0;state.strategyToken=0;
    state.visible=true;state.loaded=true;state.pending=false;strategyDirectory={};
    state.acceptAfter=GetTickCount64()+250;state.message=L"실행을 누르면 서버가 권한·위치·CP를 확인합니다.";
    unsigned outfits[16]={};unsigned count=strategyOutfits(own,outfits);
    if(armyStrategy(command) && count)state.strategyOutfit=outfits[0];
    reinterpret_cast<void(__thiscall*)(void*,int)>(0x506280)(state.root,12);
    reinterpret_cast<void(__thiscall*)(void*,void*)>(0x50beb0)(state.manager,state.root);
    if(personnelStrategy(command) || command==24)sendStrategyAction(true);
    return 1;
}
static void strategyNextBase(int direction) {
    auto& state=taxWindow;auto world=static_cast<BYTE*>(state.world);unsigned ids[350]={},count=0,selected=0;
    for(unsigned i=0;i<350;++i)if(world[0x2eb800+i*0x250]) {
        unsigned id=at<unsigned>(world,0x2eb288+i*4);
        if(!id || id>65535)continue;
        if(id==state.strategyBase)selected=count;ids[count++]=id;
    }
    if(count)state.strategyBase=ids[(selected+count+direction)%count];
}
static void strategyNextPerson(int direction) {
    auto& state=taxWindow;auto& directory=strategyDirectory;
    if(!directory.count)return;
    unsigned selected=0;unsigned& target=state.strategyCommand==24 ? state.strategyBase : state.strategyTarget;
    for(unsigned i=0;i<directory.count;++i)if(directory.people[i].id==target)selected=i;
    if(direction>0 && selected+1==directory.count && directory.count==200) {sendStrategyAction(true,directory.people[selected].id);return;}
    if(direction<0 && selected==0 && directory.people[0].id>state.character+200) {sendStrategyAction(true);return;}
    target=directory.people[(selected+directory.count+direction)%directory.count].id;
}
static bool updateStrategyOperation() {
    auto& state=taxWindow;unsigned command=state.strategyCommand;
    taxText(0,strategyActionName(command));taxText(1,L"목표와 참가 병력을 지정하십시오.");
    static const wchar_t* fields[]={L"현재 선택: 목표 X 좌표",L"현재 선택: 목표 Y 좌표",L"현재 선택: 목적",L"현재 선택: 참가 유닛 수",L"현재 선택: 발동까지 게임 일수"};
    taxText(2,command==24 && state.strategyField==2 ? L"현재 선택: 발령할 부대 / 독행함" : fields[state.strategyField]);
    wchar_t label[160];swprintf_s(label,L"목표 그리드: %u (%u, %u)",state.strategyTarget,state.strategyTarget%100,state.strategyTarget/100);taxText(3,label);
    if(command==22) {
        static const wchar_t* purposes[]={L"점령",L"방위",L"소탕 (독행함 전용)"};
        swprintf_s(label,L"%ls · %u 유닛 · %u일 뒤 발동",purposes[state.strategyValue%3],state.strategyAmount,state.strategyArgument);taxText(4,label);
        unsigned cost=1280/(state.strategyArgument+1);if(cost<10)cost=10;
        swprintf_s(label,L"MCP %u · 부족 시 PCP %u 전액 대용",cost,cost*2);taxText(5,label);
    } else if(command==24) {
        const wchar_t* name=L"선택 필요";for(unsigned i=0;i<strategyDirectory.count;++i)if(strategyDirectory.people[i].id==state.strategyBase)name=strategyDirectory.people[i].name;
        swprintf_s(label,L"발령 대상: %ls (%u)",name,state.strategyBase);taxText(4,label);taxText(5,L"MCP 1~320 · 게임 30일 후 결과 판정");
    } else {taxText(4,L"본인이 이 카드에서 계획한 목표만 철회합니다.");taxText(5,L"MCP 5~320 · 철회 후 부대 할당 해제");}
    taxText(6,state.message);taxText(10,L"선택 항목");taxText(11,L"이전 / −");taxText(12,L"다음 / +");taxText(13,L"부대 목록 갱신");taxText(14,L"실행");
    taxEnabled(10,!state.pending);taxEnabled(11,!state.pending);taxEnabled(12,!state.pending);
    taxEnabled(13,!state.pending && command==24);taxEnabled(14,!state.pending && (command!=24 || state.strategyBase>0));
    if(GetTickCount64()<state.acceptAfter)return true;
    BYTE event[52]={};
    for(int id=10;id<=15;++id)if(reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,void*,int)>(0x5015f0)(state.root,2,taxWidget(1,id),event,0)) {
        state.acceptAfter=GetTickCount64()+250;
        if(id==15)closeTaxWindow();
        else if(!state.pending) {
            if(id==10)state.strategyField=(state.strategyField+1)%(command==22 ? 5 : command==24 ? 3 : 2);
            else if(id==13 && command==24)sendStrategyAction(true);
            else if(id==14 && (command!=24 || state.strategyBase>0))sendStrategyAction();
            else if(id==11 || id==12) {
                int step=id==11 ? -1 : 1;
                if(state.strategyField==0)state.strategyTarget=state.strategyTarget/100*100+(state.strategyTarget%100+100+step)%100;
                else if(state.strategyField==1)state.strategyTarget=(state.strategyTarget/100+50+step)%50*100+state.strategyTarget%100;
                else if(command==24)strategyNextPerson(step);
                else if(state.strategyField==2)state.strategyValue=(state.strategyValue+3+step)%3;
                else if(state.strategyField==3)state.strategyAmount=(state.strategyAmount-1+65535+step)%65535+1;
                else state.strategyArgument=(state.strategyArgument+128+step)%128;
            }
        }
        break;
    }
    return true;
}
static bool updateStrategyAction(void*) {
    auto& state=taxWindow;auto world=*reinterpret_cast<void**>(0x7ccffc);auto own=world ? at<void*>(world,8) : nullptr;
    if(world!=state.world || !own || !at<BYTE>(own,0) || at<unsigned>(own,0x24)!=state.character ||
        !at<BYTE>(world,0x2b6a6c) || at<BYTE>(world,0x3579cc)) {closeTaxWindow();return false;}
    if(operationStrategy(state.strategyCommand))return updateStrategyOperation();
    unsigned command=state.strategyCommand;unsigned outfits[16]={};unsigned count=strategyOutfits(own,outfits);
    bool army=armyStrategy(command),personnel=personnelStrategy(command),estate=command==8 || command==9,title=command==3 || command==4;
    taxText(0,strategyActionName(command));taxText(1,L"명령 확정 후에만 CP를 사용합니다.");
    const wchar_t* field=personnel ? (state.strategyField==0 ? L"현재 선택: 대상 인물" : estate ? L"현재 선택: 봉토 행성" : L"현재 선택: 수여 종류") : L"대상 부대 선택";
    taxText(2,personnel ? field : command==53 ? L"대기 게임 24시간 · 치안 상승, 정부 지지 하락" :
        command==84 ? L"정치가 전환 · 게임 30일 재지원 불가" : command==85 ? L"소령으로 입대 · 표준 전함 지급" : L"현 위치의 부대 병력을 훈련합니다.");
    wchar_t label[160];
    if(personnel) {
        const StrategyDirectory::Person* person=nullptr;
        for(unsigned i=0;i<strategyDirectory.count;++i)if(strategyDirectory.people[i].id==state.strategyTarget)person=&strategyDirectory.people[i];
        if(person)swprintf_s(label,L"대상  %ls · 계급 %u",person->name,person->rank);else swprintf_s(label,L"대상 인물을 선택해 주세요.");
    } else if(army)swprintf_s(label,L"대상 부대 ID  %u",state.strategyOutfit);else swprintf_s(label,L"대상: 본인");
    taxText(3,label);
    if(estate) {wchar_t name[32]={};pickerName(state.strategyBase,name);swprintf_s(label,L"봉토: %ls (%u)",name,state.strategyBase);taxText(4,label);}
    else if(title) {static const wchar_t* titles[]={L"공작",L"후작",L"백작",L"자작",L"남작",L"제국기사"};
        if(command==3)swprintf_s(label,L"작위: %ls",titles[state.strategyValue%6]);else swprintf_s(label,L"훈장 종류: %u",state.strategyValue+1);taxText(4,label);}
    else taxText(4,L"");
    unsigned cost=command==1 || estate ? 640 : command==2 ? 320 : command==48 || command==50 ? 80 : 160;
    swprintf_s(label,L"MCP %u · 부족 시 PCP %u 전액 대용",cost,cost*2);taxText(5,label);
    taxText(6,state.message);taxText(10,L"선택 항목");taxText(11,L"이전 / −");taxText(12,L"다음 / +");taxText(13,L"목록 갱신");taxText(14,L"실행");
    taxEnabled(10,personnel && (estate || title) && !state.pending);taxEnabled(13,personnel && !state.pending);
    taxEnabled(11,!state.pending && (army && count>1 || personnel));taxEnabled(12,!state.pending && (army && count>1 || personnel));
    taxEnabled(14,!state.pending && (!army || state.strategyOutfit>0) && (!personnel || state.strategyTarget>0) && (!estate || state.strategyBase>0));
    if(state.pending && GetTickCount64()-state.sentAt>10000)state.message=L"응답 대기 중 · 재접속 후 실제 상태를 확인해 주세요.";
    if(GetTickCount64()<state.acceptAfter)return true;
    BYTE event[52]={};
    for(int id=10;id<=15;++id)if(reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,void*,int)>(0x5015f0)(state.root,2,taxWidget(1,id),event,0)) {
        state.acceptAfter=GetTickCount64()+250;
        if(id==15)closeTaxWindow();
        else if(!state.pending) {
            if(id==10 && personnel && (estate || title))state.strategyField=1-state.strategyField;
            else if(id==13 && personnel)sendStrategyAction(true);
            else if(id==11 || id==12) {
                int direction=id==11 ? -1 : 1;
                if(personnel) {
                    if(state.strategyField==0)strategyNextPerson(direction);
                    else if(estate)strategyNextBase(direction);
                    else {unsigned maximum=command==3 ? 6 : 128;state.strategyValue=(state.strategyValue+maximum+direction)%maximum;}
                } else if(army && count) {
                    unsigned selected=0;for(unsigned i=0;i<count;++i)if(outfits[i]==state.strategyOutfit)selected=i;
                    state.strategyOutfit=outfits[(selected+count+direction)%count];
                }
            } else if(id==14 && (!army || state.strategyOutfit) && (!personnel || state.strategyTarget) && (!estate || state.strategyBase))sendStrategyAction();
        }
        break;
    }
    return true;
}
static unsigned __fastcall readStrategyAction(void* codec,void*,void* record,void* stream) {
    auto result=reinterpret_cast<unsigned(__thiscall*)(void*,void*,void*)>(0x55b3a0)(codec,record,stream);
    if(at<unsigned short>(record,4)==0x7f00) {
        auto& state=taxWindow;
        if(state.visible && state.strategyActive && state.pending && at<unsigned>(record,0)==state.character &&
            at<unsigned short>(record,6)==state.strategyCommand && at<unsigned short>(record,32)==state.strategyToken) {
            if(at<BYTE>(record,16)==1)strategyDirectory.receiving=true;
            else {
                state.pending=false;
                state.message=at<unsigned short>(record,10) ? L"명령 거부 · 직무·대상·계급·병력·CP를 확인하세요." :
                    state.strategyCommand==53 ? L"진압 예약이 저장됐습니다. 게임 24시간 뒤 적용됩니다." : L"명령 결과가 서버에 저장됐습니다.";
            }
        }
        memset(static_cast<BYTE*>(record)+4,0,32);
    }
    return result;
}
static unsigned __fastcall readStrategyPeople(void* codec,void*,void* record,void* stream) {
    auto result=reinterpret_cast<unsigned(__thiscall*)(void*,void*,void*)>(0x55ba80)(codec,record,stream);
    if(strategyDirectory.receiving) {
        strategyDirectory.receiving=false;auto& state=taxWindow;
        if(state.visible && state.strategyActive && (personnelStrategy(state.strategyCommand) || state.strategyCommand==24)) {
            unsigned count=at<BYTE>(record,0);if(count>200)return result;
            strategyDirectory.count=count;
            for(unsigned i=0;i<count;++i) {
                auto row=static_cast<BYTE*>(record)+4+i*0x120;auto& person=strategyDirectory.people[i];
                person.id=at<unsigned>(row,0);person.rank=at<unsigned short>(row,0x20);
                memset(person.name,0,sizeof(person.name));memcpy(person.name,row+6,12*sizeof(wchar_t));
            }
            if(state.strategyCommand==24)state.strategyBase=count ? strategyDirectory.people[0].id : 0;
            else state.strategyTarget=count ? strategyDirectory.people[0].id : 0;
            state.pending=false;
            state.message=count ? L"항목을 선택한 뒤 실행하십시오. 서버에서 조건을 다시 확인합니다." : L"이 페이지에 대상 인물이 없습니다. 목록 갱신으로 처음부터 조회합니다.";
        }
    }
    return result;
}
static bool installStrategyActions() {
    const unsigned reader=0x55b3a0,peopleReader=0x55ba80;
    unsigned replacement=reinterpret_cast<unsigned>(&readStrategyAction),peopleReplacement=reinterpret_cast<unsigned>(&readStrategyPeople);
    if(*reinterpret_cast<unsigned*>(0x674864)!=reader || *reinterpret_cast<unsigned*>(0x6749b4)!=peopleReader)return false;
    return patchBytes(reinterpret_cast<void*>(0x674864),&reader,&replacement,4) &&
        patchBytes(reinterpret_cast<void*>(0x6749b4),&peopleReader,&peopleReplacement,4);
}
