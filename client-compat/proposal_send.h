// 命令/提案 send window (design-proposals-orders.md). The CD's 命令/提案 tab sends search 29 but never draws its rows
// (005912e0), so when that request is seen this window opens instead: pick a target from the server's same-institution
// list (0x7f00 command 98 preview) and order or propose the target's resignation (0x0709); the server picks both cards.
// Author: Peppone Choi.
#pragma once
#include "assignment_mail_state.h"
static bool proposalTabRequested=false;
// strategyArgument: 0 resignation of the recipient's card, 1 dismissal of a third person's seat, 2 appointment of the
// sender to a vacancy the recipient may fill, 3 demotion (0x0706) and 4 special promotion (0x0705) of a third person,
// 5 割当 (0x0c0b): the recipient (strategyTarget, same list as mode 0) assigns stock that the sender fills in the
// native 割当 editor (assignment_mail_state.h).
// In modes 1-4 strategyBase keeps the recipient and the directory lists: dismissal id = person*512+card, appointment
// id = seat*512+card (name = the seat's base), demotion and promotion id = the plain character id (name and rank as in
// the general directory).
// Demotion/promotion: preview base = recipient, target = page cursor; commit target = recipient, base = person, amount = 0.
static bool proposalPlainPerson() {return taxWindow.strategyArgument==3 || taxWindow.strategyArgument==4;}
static bool proposalAssignment() {return taxWindow.strategyArgument==5;} // demotion or promotion: plain character ids
static unsigned proposalCursor=0; // the last preview's page cursor (plain-person modes page by character id)
static unsigned proposalCursors[512]={},proposalPage=0; // earlier pages' cursors, so 이전 returns one page (cursor 0 = first)
static void sendProposal(bool preview,unsigned cursor=0) {
    auto& state=taxWindow;bool dismissal=state.strategyArgument>=1 && state.strategyArgument<=4,plain=proposalPlainPerson();
    unsigned short command=state.strategyArgument==4 ? 0x0705 : state.strategyArgument==3 ? 0x0706 : state.strategyArgument==2 ? 0x0707 : 0x0708;
    BYTE record[36]={};at<unsigned>(record,0)=state.character;
    at<unsigned short>(record,4)=0x7f00;at<unsigned short>(record,6)=98;
    at<unsigned short>(record,10)=static_cast<unsigned short>(preview ? (dismissal ? command : 0) : dismissal ? command : 0x0709);
    at<unsigned>(record,12)=preview ? cursor : dismissal ? state.strategyBase : state.strategyTarget;
    at<BYTE>(record,16)=preview ? 1 : 0;
    if(dismissal) {
        at<unsigned>(record,28)=preview ? state.strategyBase : plain ? state.strategyTarget : state.strategyTarget/512;
        if(!preview && !plain)at<unsigned short>(record,24)=static_cast<unsigned short>(state.strategyTarget%512);
    }
    strategyRequestToken=strategyRequestToken%65535+1;state.strategyToken=strategyRequestToken;
    at<unsigned short>(record,32)=static_cast<unsigned short>(state.strategyToken);
    if(preview) {proposalCursor=cursor;if(!cursor)proposalPage=0;}
    state.pending=true;state.sentAt=GetTickCount64();state.message=preview ? L"목록을 조회하고 있습니다." : L"서버에 요청했습니다.";
    strategyDirectory.receiving=false;
    reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(state.world,0,0x46,record);
}
static bool proposalCommand(unsigned command) {return command==98;}
// strategyNextPerson pages through sendStrategyAction, which does not build 98 records; demotion and promotion list every
// eligible person, so its page edges (200 rows forward, the first row of a later page back) request the next preview here.
static void proposalNextPerson(int direction) {
    auto& state=taxWindow;auto& directory=strategyDirectory;
    if(proposalPlainPerson() && directory.count) {
        unsigned selected=0;
        for(unsigned i=0;i<directory.count;++i)if(directory.people[i].id==state.strategyTarget)selected=i;
        if(direction>0 && selected+1==directory.count && directory.count==200) {
            if(proposalPage<512)proposalCursors[proposalPage++]=proposalCursor;
            sendProposal(true,directory.people[selected].id);return;
        }
        if(direction<0 && selected==0 && proposalCursor) {sendProposal(true,proposalPage ? proposalCursors[--proposalPage] : 0);return;}
    }
    strategyNextPerson(direction);
}
// 割当 mail (mode 5). One draft at a time; the result is kept for the window because the native editor covers it.
static logh::AssignmentMail assignmentMail;
static unsigned assignmentMailLastToken=0;
static const wchar_t* assignmentNotice=nullptr; // shown on line 2 in mode 5
static bool assignmentNoticeOpen=false;         // reopen the window with the notice once the editor is gone
static bool proposalWindowShown() {return taxWindow.visible && taxWindow.strategyActive && taxWindow.strategyCommand==98;}
static void assignmentMailRecord(void* world,unsigned character,unsigned recipient,unsigned token,bool cancel) {
    if(!world || world!=*reinterpret_cast<void**>(0x7ccffc))return; // another session never saw this draft
    BYTE record[36];logh::assignmentDraftRecord(record,character,recipient,token,cancel);
    reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(world,0,0x46,record);
}
static void cancelAssignmentDraft() {
    assignmentMailRecord(const_cast<void*>(assignmentMail.world),assignmentMail.actor,assignmentMail.recipient,assignmentMail.token,true);
}
static void assignmentMailMessage(const wchar_t* text,bool reopen) {
    assignmentNotice=text;
    if(proposalWindowShown()) {taxWindow.pending=false;taxWindow.message=text;}
    else if(reopen)assignmentNoticeOpen=true;
}
static void sendAssignmentDraft() {
    auto& state=taxWindow;const auto now=GetTickCount64();
    const unsigned token=logh::assignmentMailToken(now,assignmentMailLastToken);
    if(!assignmentMail.begin(state.world,state.character,state.strategyTarget,token,now))return;
    assignmentMailLastToken=token;assignmentNotice=nullptr;assignmentNoticeOpen=false;
    state.pending=true;state.sentAt=now;state.message=L"할당 편지를 준비하고 있습니다.";
    strategyDirectory.receiving=false;
    assignmentMailRecord(state.world,state.character,state.strategyTarget,token,false);
}
/** Grant: the native 割当 entry on the recipient's logistics card. 004f93c0 registers the command (00585150)
 * without a card ownership check or 0x0f13; the server lets that card's base/outfit/warehouse lookups pass for 120 s.
 * Called from the GUI frame once the grant's own 1200/1201 transaction has settled (AssignmentMail::openable). */
static void openAssignmentEditor() {
    void* command=nullptr;
    if(!*reinterpret_cast<void**>(0xc9e2f8)) {
        closeTaxWindow();
        if(reinterpret_cast<BYTE(__thiscall*)(void*,unsigned,unsigned)>(0x4f93c0)(reinterpret_cast<void*>(0xc9e2e0),
            logh::assignmentMailCommand,assignmentMail.card))command=*reinterpret_cast<void**>(0xc9e2f8);
        if(command && at<unsigned>(command,0x28)!=logh::assignmentMailCommand)command=nullptr;
    }
    if(assignmentMail.attach(command)) {assignmentNotice=L"할당 창에서 거점·부대·물자를 정해 실행하면 편지로 보냅니다.";return;}
    cancelAssignmentDraft();
    assignmentMailMessage(L"할당 창을 열 수 없습니다. 다른 명령 창을 닫고 다시 준비하세요.",true);
}
/** 1200/7f00/98 replies that carry this draft's token at +20 (draft grant or mail confirmation). */
static bool readAssignmentMail(void* record) {
    if(!assignmentMail.active() || at<unsigned short>(record,4)!=0x7f00 || at<unsigned short>(record,6)!=98)return false;
    const unsigned code=at<unsigned short>(record,10);
    const auto reply=assignmentMail.receive(*reinterpret_cast<void**>(0x7ccffc),at<unsigned>(record,0),at<unsigned>(record,12),
        at<BYTE>(record,16),at<unsigned>(record,20),code,at<unsigned short>(record,24),GetTickCount64());
    if(reply==logh::AssignmentMail::Ignored)return false;
    memset(static_cast<BYTE*>(record)+4,0,32);
    // Not here: this record is still inside the 1200 reader and its 1201 follows (see assignmentSettleFrames).
    if(reply==logh::AssignmentMail::Grant) {if(proposalWindowShown())taxWindow.message=L"할당 창을 여는 중입니다.";}
    else if(reply==logh::AssignmentMail::Refused)
        assignmentMailMessage(code ? logh::assignmentMailFailure(code) : L"상대에게 이 할당을 맡길 병참 직무가 없습니다.",true);
    else if(reply==logh::AssignmentMail::Delivered)
        assignmentMailMessage(L"할당 편지를 보냈습니다. 상대가 承認하면 서버가 할당을 실행합니다(MCP 절반·영향력 1 사용).",true);
    else {
        cancelAssignmentDraft(); // a failed mail leaves the server grant; release it
        assignmentMailMessage(code ? logh::assignmentMailFailure(code) : L"이미 처리된 할당 편지입니다. 직무 편지함을 확인하세요.",true);
    }
    return true;
}
/** Selector 0x69 (0x0c0b) from 004b51c0. Only the editor this window opened becomes a mail; a direct 割当 is untouched. */
static constexpr unsigned assignmentSendCall=0x4b5276;
static constexpr BYTE assignmentSendExpected[5]={0xe8,0x25,0x26,0x00,0x00};
static unsigned __fastcall assignmentSend(void* world,void*,char pending,unsigned selector,BYTE* record) {
    auto dispatch=reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0);
    switch(assignmentMail.send(world,at<unsigned>(record,4),*reinterpret_cast<void**>(0xc9e2f8),GetTickCount64())) {
    case logh::AssignmentMail::Suppress:
        assignmentMailMessage(L"이 할당 창의 편지는 이미 보냈거나 만료됐습니다. 창을 닫고 다시 준비하세요.",true);
        return 0;
    case logh::AssignmentMail::Envelope:
        logh::assignmentMailEnvelope(record,assignmentMail.recipient,assignmentMail.token);
        assignmentNotice=L"할당 편지를 보내고 있습니다.";
        break;
    default:break;
    }
    return dispatch(world,pending,selector,record);
}
/** From the 0x1200 search hook (supply_selector.h, 004b587d): drop the CD 命令/提案 tab's search 29 while this window's
 * 割当 editor is pending or open (assignmentTabSearch). Nothing is sent, so its 1200 reply never empties the editor's
 * base list and never reopens this window. */
static bool assignmentHoldsSearch(const BYTE* record) {
    return *reinterpret_cast<const unsigned short*>(record+4)==logh::assignmentTabSearch &&
        assignmentMail.holdsTabSearch(*reinterpret_cast<void**>(0xc9e2f8));
}
/** The editor's command lookups (AssignmentMail::editsAssignment). Each of the twelve sites reads the CD slot
 * (`mov eax,[0xc9eabc]`), range-checks it (0..0x17), calls 004c8700 for the card definition table and reads the command
 * word at table + card*0x46 + 0x20 + slot*2, reloading the card (0xc9eac0) and slot from the globals right after the call.
 * While this draft's editor is open the slot read passes the check and the table pointer is shifted so that this word is
 * 割当. Nothing is written: the CD, the selection steps (0057xxxx) and a direct 割当 keep their own lookups. */
static constexpr unsigned assignmentEditorLookups[][2]={
    {0x52f73b,0x52f74f},{0x530c70,0x530c90},{0x530f74,0x530f92},{0x531301,0x531315},{0x531421,0x531437},
    {0x531d5f,0x531d7f},{0x53222b,0x53223f},{0x5324a6,0x5324bc},{0x5329ce,0x5329fd},{0x533527,0x533549},
    {0x5338e7,0x5338fc},{0x533983,0x5339a1}};
static constexpr unsigned assignmentCardTable=0x4c8700;
static constexpr BYTE assignmentSlotRead[5]={0xa1,0xbc,0xea,0xc9,0x00};
static const unsigned short assignmentEditorCommand=static_cast<unsigned short>(logh::assignmentMailCommand);
static bool __cdecl assignmentEditorActive() {
    return assignmentMail.editsAssignment(*reinterpret_cast<void**>(0x7ccffc),*reinterpret_cast<void**>(0xc9e2f8));
}
static BYTE* __cdecl assignmentEditorCards() {
    auto table=reinterpret_cast<BYTE*(__cdecl*)()>(assignmentCardTable)();
    if(!table || !assignmentEditorActive())return table;
    const uintptr_t card=*reinterpret_cast<unsigned*>(0xc9eac0),slot=*reinterpret_cast<unsigned*>(0xc9eabc);
    return reinterpret_cast<BYTE*>(reinterpret_cast<uintptr_t>(&assignmentEditorCommand)-0x20-card*0x46-slot*2);
}
// Both keep ecx/edx like the instructions they replace; the sites recompute them anyway.
static void __declspec(naked) assignmentEditorSlot() {
    __asm {
        push ecx
        push edx
        call assignmentEditorActive
        pop edx
        pop ecx
        test al,al
        jnz assigned
        mov eax,dword ptr ds:[0xc9eabc]
        ret
    assigned:
        xor eax,eax
        ret
    }
}
static void __declspec(naked) assignmentEditorTable() {
    __asm {
        push ecx
        push edx
        call assignmentEditorCards
        pop edx
        pop ecx
        ret
    }
}
static void assignmentCall(BYTE code[5],unsigned site,const void* target) {
    code[0]=0xe8;
    at<LONG>(code,1)=static_cast<LONG>(reinterpret_cast<uintptr_t>(target)-(site+5));
}
static bool installAssignmentEditorLookups() {
    constexpr unsigned count=_countof(assignmentEditorLookups);
    BYTE cardCalls[count][5];
    for(unsigned i=0;i<count;++i) {
        assignmentCall(cardCalls[i],assignmentEditorLookups[i][1],reinterpret_cast<const void*>(assignmentCardTable));
        if(memcmp(reinterpret_cast<void*>(assignmentEditorLookups[i][0]),assignmentSlotRead,5) ||
            memcmp(reinterpret_cast<void*>(assignmentEditorLookups[i][1]),cardCalls[i],5))return false;
    }
    for(unsigned i=0;i<count;++i) {
        BYTE slot[5],table[5];
        assignmentCall(slot,assignmentEditorLookups[i][0],reinterpret_cast<const void*>(&assignmentEditorSlot));
        assignmentCall(table,assignmentEditorLookups[i][1],reinterpret_cast<const void*>(&assignmentEditorTable));
        if(!patchBytes(reinterpret_cast<void*>(assignmentEditorLookups[i][0]),assignmentSlotRead,slot,5) ||
            !patchBytes(reinterpret_cast<void*>(assignmentEditorLookups[i][1]),cardCalls[i],table,5))return false;
    }
    return true;
}
static bool installAssignmentMail() {
    auto call=reinterpret_cast<BYTE*>(assignmentSendCall);
    if(memcmp(call,assignmentSendExpected,5) || !installAssignmentEditorLookups())return false;
    BYTE replacement[5]={0xe8};
    at<LONG>(replacement,1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&assignmentSend)-call-5);
    return patchBytes(call,assignmentSendExpected,replacement,5);
}
static void openProposalWindow(unsigned mode=0) {
    auto world=*reinterpret_cast<void**>(0x7ccffc);auto own=world ? at<void*>(world,8) : nullptr;
    if(!own || !at<BYTE>(own,0) || *reinterpret_cast<void**>(0x21aa888)!=own || !createTaxWindow())return;
    closePlanetInformation();auto& state=taxWindow;
    state.world=world;state.character=at<unsigned>(own,0x24);state.card=0;
    state.strategyActive=true;state.strategyCommand=98;state.strategyOutfit=0;state.strategyTarget=0;state.strategyBase=0;
    state.strategyValue=0;state.strategyField=0;state.strategyAmount=0;state.strategyArgument=mode;state.strategyToken=0;
    state.visible=true;state.loaded=true;state.pending=false;strategyDirectory={};
    state.acceptAfter=GetTickCount64()+250;state.message=L"같은 스폿(우주에서는 같은 그리드)의 같은 기관 카드 보유자에게 보냅니다.";
    reinterpret_cast<void(__thiscall*)(void*,int)>(0x506280)(state.root,12);
    reinterpret_cast<void(__thiscall*)(void*,void*)>(0x50beb0)(state.manager,state.root);
    sendProposal(true);
}
/** Called every GUI frame from updateTaxWindow: opens the window after the CD asked for its 命令/提案 tab. */
static void proposalWindowTick() {
    auto world=*reinterpret_cast<void**>(0x7ccffc);auto own=world ? at<BYTE*>(world,8) : nullptr;
    const unsigned character=own && own[0] ? at<unsigned>(own,0x24) : 0;
    auto command=*reinterpret_cast<void**>(0xc9e2f8);
    // Closing the window before the grant (or before the editor opened) withdraws the draft.
    if((assignmentMail.stage==logh::AssignmentMail::Drafting || assignmentMail.stage==logh::AssignmentMail::Granted) &&
        !proposalWindowShown() && assignmentMail.abandon())cancelAssignmentDraft();
    switch(assignmentMail.tick(world,character,command,GetTickCount64())) {
    case logh::AssignmentMail::Reset:assignmentNotice=nullptr;assignmentNoticeOpen=false;break;
    case logh::AssignmentMail::NoReply:cancelAssignmentDraft();assignmentMailMessage(L"할당 준비 응답이 없습니다. 다시 준비하세요.",false);break;
    case logh::AssignmentMail::Stalled:cancelAssignmentDraft();
        assignmentMailMessage(L"할당 창을 열 수 없습니다. 진행 중인 조회가 끝난 뒤 다시 준비하세요.",true);break;
    case logh::AssignmentMail::Closed:cancelAssignmentDraft();assignmentMailMessage(L"할당 창을 닫아 편지를 보내지 않았습니다.",false);break;
    case logh::AssignmentMail::Expired:cancelAssignmentDraft();assignmentMailMessage(L"할당 준비 시간(2분)이 지났습니다. 창을 닫고 다시 준비하세요.",true);break;
    case logh::AssignmentMail::Unconfirmed:assignmentMailMessage(L"할당 편지 응답이 없습니다. 직무 편지함에서 도착 여부를 확인하세요.",true);break;
    default:break;
    }
    if(assignmentMail.openable(world && at<BYTE>(world,0x487471)!=0)) {openAssignmentEditor();return;}
    if(assignmentNoticeOpen && !taxWindow.visible && !command && character) {
        assignmentNoticeOpen=false;proposalTabRequested=false;openProposalWindow(5);return;
    }
    if(assignmentMail.holdsTabSearch(command))proposalTabRequested=false; // a late search 29 reply never covers the editor
    if(!proposalTabRequested || taxWindow.visible)return;
    proposalTabRequested=false;openProposalWindow();
}
/** Commit reply. SocialRejection ordinal+1. */
static bool readProposalAction(void* record) {
    if(readAssignmentMail(record))return true;
    auto& state=taxWindow;
    if(at<unsigned short>(record,4)!=0x7f00 || !state.visible || !state.strategyActive || state.strategyCommand!=98 ||
        !state.pending || at<BYTE>(record,16)==1 || at<unsigned>(record,0)!=state.character ||
        at<unsigned short>(record,6)!=98 || at<unsigned short>(record,32)!=state.strategyToken)return false;
    state.pending=false;unsigned reply=at<unsigned short>(record,10);
    state.message=reply==4 ? L"보낼 수 있는 같은 기관의 직무 카드가 없습니다." :
        reply==5 ? L"영향력이 부족합니다(1 필요)." :
        reply==6 ? L"상대가 범위 밖이거나 전술 중입니다." :
        reply==7 ? L"진행 중인 작업이 끝난 뒤 보내십시오." :
        reply==8 ? (state.strategyArgument==4 ? L"MCP가 부족합니다(320 필요)." : state.strategyArgument==3 ? L"MCP가 부족합니다(160 필요)." :
            L"MCP가 부족합니다(40 필요).") :
        reply==9 ? L"상대가 하루 안에 같은 요청을 거절했습니다." :
        reply==13 ? (state.strategyArgument==2 ? L"상대가 그 자리에 임명할 수 없습니다." :
            state.strategyArgument==3 ? L"상대가 강등할 수 없는 사람입니다." :
            state.strategyArgument==4 ? L"상대가 발탁할 수 없는 사람입니다." : L"상대가 파면할 수 없는 직무입니다.") :
        reply ? L"보낼 수 없습니다. 대상·카드·위치를 확인하세요." :
        L"보냈습니다. 상대의 직무 편지함에 도착합니다(MCP 절반·영향력 1 사용).";
    memset(static_cast<BYTE*>(record)+4,0,32);return true;
}
static bool updateProposalAction() {
    auto& state=taxWindow;bool dismissal=state.strategyArgument==1,appointment=state.strategyArgument==2,demotion=state.strategyArgument==3,
        promotion=state.strategyArgument==4,assignment=proposalAssignment();
    taxText(0,L"명령 / 제안");
    const StrategyDirectory::Person* row=nullptr;
    for(unsigned i=0;i<strategyDirectory.count;++i)if(strategyDirectory.people[i].id==state.strategyTarget)row=&strategyDirectory.people[i];
    wchar_t label[160];
    if(appointment) {
        swprintf_s(label,L"받는 사람(임명 실행자): ID %u",state.strategyBase);taxText(1,label);
        if(row)swprintf_s(label,L"나를 임명: 직무 카드 %u · %ls",row->id%512,row->name);else swprintf_s(label,L"이 사람이 나를 임명할 수 있는 빈자리가 없습니다.");
        taxText(2,label);
    } else if(demotion) {
        swprintf_s(label,L"받는 사람(강등 실행자): ID %u",state.strategyBase);taxText(1,label);
        if(row)swprintf_s(label,L"강등 대상: %ls (계급 %u)",row->name,row->rank);else swprintf_s(label,L"이 사람이 강등할 수 있는 대상이 없습니다.");
        taxText(2,label);
    } else if(promotion) {
        swprintf_s(label,L"받는 사람(발탁 실행자): ID %u",state.strategyBase);taxText(1,label);
        if(row)swprintf_s(label,L"발탁 대상: %ls (계급 %u)",row->name,row->rank);else swprintf_s(label,L"이 사람이 발탁할 수 있는 대상이 없습니다.");
        taxText(2,label);
    } else if(dismissal) {
        swprintf_s(label,L"받는 사람(파면 실행자): ID %u",state.strategyBase);taxText(1,label);
        if(row)swprintf_s(label,L"파면 대상: %ls · 직무 카드 %u",row->name,row->id%512);else swprintf_s(label,L"이 사람이 파면할 수 있는 직무가 없습니다.");
        taxText(2,label);
    } else if(assignment) {
        if(row)swprintf_s(label,L"할당 실행자: %ls (계급 %u)",row->name,row->rank);else swprintf_s(label,L"보낼 수 있는 상대가 없습니다.");
        taxText(1,label);
        taxText(2,assignmentNotice ? assignmentNotice : L"내용: 할당 · 준비 후 원래 할당 창에서 거점·부대·물자를 정합니다");
    } else {
        if(row)swprintf_s(label,L"상대: %ls (계급 %u)",row->name,row->rank);else swprintf_s(label,L"보낼 수 있는 상대가 없습니다.");
        taxText(1,label);taxText(2,L"내용: 직무 사임 (상대의 같은 기관 카드)");
    }
    taxText(3,L"계급 같거나 높으면 명령");
    taxText(4,L"낮으면 제안 · 承認 시 실행");
    taxText(5,promotion ? L"MCP 640을 반씩 분담 · 보낼 때 영향력 1" : demotion ? L"MCP 320을 반씩 분담 · 보낼 때 영향력 1" :
        dismissal || appointment || assignment ? L"MCP 160을 반씩 분담 · 보낼 때 영향력 1" : L"MCP 80을 반씩 분담 · 보낼 때 영향력 1");
    taxText(6,state.message);
    taxText(10,assignment ? L"사임으로" : promotion ? L"할당으로" : demotion ? L"발탁으로" : appointment ? L"강등으로" : dismissal ? L"임명으로" : L"파면으로");
    taxText(11,L"이전");taxText(12,L"다음");taxText(13,L"목록 갱신");taxText(14,assignment ? L"할당 준비" : L"보내기");taxText(15,L"닫기");
    const bool drafting=assignmentMail.active();
    taxEnabled(10,!state.pending && !drafting && (dismissal || appointment || demotion || promotion || assignment || row!=nullptr));
    taxEnabled(11,!state.pending && (strategyDirectory.count>1 || (proposalPlainPerson() && proposalCursor)));
    taxEnabled(12,!state.pending && strategyDirectory.count>1);taxEnabled(13,!state.pending);taxEnabled(14,!state.pending && row!=nullptr && !(assignment && drafting));
    if(state.pending && GetTickCount64()-state.sentAt>10000)state.message=L"응답 대기 중 · 재접속 후 편지함을 확인하세요.";
    if(GetTickCount64()<state.acceptAfter)return true;
    BYTE event[52]={};
    for(int id=10;id<=15;++id)if(reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,void*,int)>(0x5015f0)(state.root,2,taxWidget(1,id),event,0)) {
        state.acceptAfter=GetTickCount64()+250;
        if(id==15)closeTaxWindow();
        else if(!state.pending) {
            if(id==10) {
                // Cycle resignation -> dismissal -> appointment -> demotion -> promotion -> 割当: modes 1-4 keep the
                // chosen recipient and list its seats or demotable/promotable people; 割当 lists recipients as mode 0.
                if(state.strategyArgument==0) {state.strategyBase=state.strategyTarget;state.strategyArgument=1;}
                else if(state.strategyArgument>=1 && state.strategyArgument<=3)++state.strategyArgument;
                else if(state.strategyArgument==4) {state.strategyArgument=5;state.strategyTarget=state.strategyBase;}
                else state.strategyArgument=0; // 割当 already keeps the recipient in strategyTarget
                strategyDirectory={};sendProposal(true);
            }
            else if(id==11 || id==12)proposalNextPerson(id==11 ? -1 : 1);
            else if(id==13)sendProposal(true);
            else if(id==14 && row && assignment)sendAssignmentDraft();
            else if(id==14 && row)sendProposal(false);
        }
        break;
    }
    return true;
}
