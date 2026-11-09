// 承認 for 命令/提案 rows (design-proposals-orders.md, user decision 2026-11-01). The CD's fifth mailbox toolbar
// button is labelled 承認 but deletes every row (widget 0x66, 0053a5d4). On an unreplied received proposal row it
// sends strategy directory 0x7f00 command 99 instead, because 0x0f14 has no correlated result. The row changes only
// after the server confirms status 0 flag 2. Native reply buttons keep 0x0f14. Offsets: work/mailbox-re (static).
// Author: Peppone Choi.
#pragma once
#include "proposal_approval_state.h"
#include "proposal_detail_caption.h"
static constexpr unsigned approvalClickCall=0x53a5f3;
static constexpr BYTE approvalClickExpected[5]={0xe8,0xf8,0x6f,0xfc,0xff};
static constexpr unsigned approvalRowsOffset=0x14e8214;
static logh::ProposalApproval proposalApproval;
static BYTE* proposalApprovalMailbox=nullptr;
static unsigned proposalApprovalOwner(void* world) {
    auto own=world ? at<void*>(world,8) : nullptr;
    return own && at<BYTE>(own,0) ? at<unsigned>(own,0x24) : 0;
}
/** The stored 0x0f13 item of a received proposal row; null for personal mail and reply rows. */
static BYTE* approvalProposal(BYTE* mailbox,BYTE* row) {
    if(!row)return nullptr;
    const unsigned kind=at<unsigned>(row,8)&0xff,box=at<BYTE>(row,0xd),item=at<unsigned>(row,0x10);
    if(kind==0 || kind==0x32 || box>=32 || item>=300)return nullptr;
    BYTE* store=mailbox+box*0xa45bc+0x5ca48;
    return at<unsigned>(store,0x4b008+item*4)==1 ? nullptr : store+0x4b4b8+item*0x264;
}
/** Rows are rebuilt by every mailbox query, so a request finds its row again by mail ID instead of keeping a pointer. */
static int approvalRow(BYTE* mailbox,unsigned mail,BYTE*& row,BYTE*& proposal) {
    BYTE* rows=mailbox+approvalRowsOffset;
    const int count=at<int>(rows,0x8e4);
    for(int i=0;i<count && i<1024;++i) {
        row=static_cast<BYTE*>(reinterpret_cast<void*(__thiscall*)(void*,int)>(0x576ec0)(rows,i));
        proposal=approvalProposal(mailbox,row);
        if(proposal && at<unsigned>(proposal,0)==mail)return i;
    }
    return -1;
}
/** The list's title column, through the same cell setter as mailListTitle; the next query rebuilds it. */
static void approvalRowText(BYTE* mailbox,int row,BYTE* proposal,const wchar_t* text) {
    BYTE* rows=mailbox+approvalRowsOffset;
    void* root=at<void*>(rows,0x18);void* table=at<void*>(rows,0x20);
    if(!root || !table)return;
    wchar_t label[96];
    swprintf_s(label,L"    %ls %ls",at<BYTE>(proposal,0x25a) ? L"명령" : L"제안",text);
    reinterpret_cast<void(__thiscall*)(void*,void*,const wchar_t*,int,int)>(0x505b50)(root,table,label,row,1);
}
/** Commit reply for the outstanding request; a stale one is dropped here so no other reader sees command 99. */
static bool readProposalApproval(void* record) {
    if(at<unsigned short>(record,4)!=0x7f00 || at<unsigned short>(record,6)!=logh::proposalApprovalCommand)return false;
    proposalApproval.receive(*reinterpret_cast<void**>(0x7ccffc),at<unsigned>(record,0),at<unsigned>(record,12),
        at<unsigned short>(record,32),at<unsigned short>(record,10),at<BYTE>(record,16));
    memset(static_cast<BYTE*>(record)+4,0,32);
    return true;
}
/** Called from the mailbox tick before its input. A closed or switched mailbox holds the result: only the list view of the received tab shows it. */
static void proposalApprovalTick(void* context) {
    auto& state=proposalApproval;
    auto world=*reinterpret_cast<void**>(0x7ccffc);
    const unsigned owner=proposalApprovalOwner(world);
    state.observe(world,owner);
    state.expire(GetTickCount64());
    if(!state.settled() || context!=proposalApprovalMailbox || at<unsigned>(context,0xa50)!=0 ||
        !mailRefreshView(context,world,owner))return;
    const auto outcome=state.outcome;const unsigned mail=state.mail,responseStatus=state.status;
    state.clear();
    BYTE* row=nullptr;BYTE* proposal=nullptr;
    const int index=approvalRow(proposalApprovalMailbox,mail,row,proposal);
    if(index<0)return; // answered mails leave the next query's list
    if(outcome==logh::ProposalApproval::Accepted) {
        // Same row state as the server's approved reply row (kind +5, read): no 답변 대기 중, no reply buttons, 22/77.
        const unsigned kind=at<unsigned>(row,8)&0xff;
        at<unsigned>(row,8)+=5-kind%10;at<BYTE>(proposal,8)=1;
        at<int>(proposalApprovalMailbox,0xa54)=-1;
        approvalRowText(proposalApprovalMailbox,index,proposal,L"승인 완료");
    }
    else if(outcome==logh::ProposalApproval::Rejected) {
        wchar_t text[64];
        swprintf_s(text,L"승인 실패: %ls",logh::proposalApprovalFailure(responseStatus));
        approvalRowText(proposalApprovalMailbox,index,proposal,text);
    }
    else approvalRowText(proposalApprovalMailbox,index,proposal,L"승인 응답 미확인: 편지함을 다시 여세요");
}
static BYTE __fastcall approvalClick(void* root,void*,int category,void* widget,BYTE* state,int flag) {
    BYTE clicked=reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,BYTE*,int)>(0x5015f0)(root,category,widget,state,flag);
    if(!clicked)return clicked;
    BYTE* mailbox=state-0xa10;
    if(at<unsigned>(mailbox,0xa50)!=0)return clicked; // received tab only
    // While a request is open this button must neither send again nor reach the native delete-all.
    if(proposalApproval.outstanding || proposalApproval.settled())return 0;
    BYTE* rows=mailbox+approvalRowsOffset;
    int count=at<int>(rows,0x8e4),selected=at<int>(rows,0x8e8);
    if(selected<0 || selected>=count)return clicked;
    auto row=static_cast<BYTE*>(reinterpret_cast<void*(__thiscall*)(void*,int)>(0x576ec0)(rows,selected));
    BYTE* proposal=approvalProposal(mailbox,row);
    if(!proposal)return clicked;
    const unsigned digit=(at<unsigned>(row,8)&0xff)%10;
    if(digit==5)return 0; // already approved: the row stays, the mailbox is not emptied
    if(digit)return clicked; // natively replied
    auto world=*reinterpret_cast<void**>(0x7ccffc);
    const unsigned owner=proposalApprovalOwner(world),mail=at<unsigned>(proposal,0);
    const unsigned short token=static_cast<unsigned short>(strategyRequestToken%65535+1);
    if(!proposalApproval.begin(world,owner,mail,token,GetTickCount64()))return 0;
    strategyRequestToken=token;proposalApprovalMailbox=mailbox;
    BYTE record[36];logh::proposalApprovalRecord(record,owner,mail,token);
    // The dispatcher subtracts one before its switch: case 0x45 (1200) uses selector 0x46.
    reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(world,0,0x46,record);
    approvalRowText(mailbox,selected,proposal,L"승인 확인 중");
    return 0; // the click was the approval, not the delete-all
}
static bool installProposalApproval() {
    if(memcmp(reinterpret_cast<void*>(approvalClickCall),approvalClickExpected,sizeof(approvalClickExpected)))return false;
    BYTE call[5]={0xe8};
    *reinterpret_cast<LONG*>(call+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&approvalClick)-reinterpret_cast<BYTE*>(approvalClickCall)-5);
    return patchBytes(reinterpret_cast<void*>(approvalClickCall),approvalClickExpected,call,sizeof(call));
}
static bool installProposalDetailCaption() {
    if(memcmp(reinterpret_cast<void*>(proposalDetailCaptionCall),proposalDetailCaptionExpected,sizeof(proposalDetailCaptionExpected)))return false;
    BYTE call[5]={0xe8};
    *reinterpret_cast<LONG*>(call+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&proposalDetailCaption)-reinterpret_cast<BYTE*>(proposalDetailCaptionCall)-5);
    return patchBytes(reinterpret_cast<void*>(proposalDetailCaptionCall),proposalDetailCaptionExpected,call,sizeof(call));
}

// Reply rows (0x0f14) in an office mailbox: 0053c1e0 keeps the 0x0f13 item pointer [esp+0x3c] at 0 for them and
// then reads its +0x25a at 0x53c933 without a null check, so any reply row crashed the CD (static analysis,
// work/mailbox-re). Remember the reply item at 0x53c8f2 and read its own +0x25a when the 0x0f13 pointer is null.
static void* proposalReplyItem=nullptr;
static constexpr unsigned replyItemAt=0x53c8f2,replyKindAt=0x53c931;
static constexpr BYTE replyItemExpected[8]={0x85,0xc9,0x0f,0x84,0x20,0x01,0x00,0x00};
static constexpr BYTE replyKindExpected[8]={0x33,0xc0,0x8a,0x87,0x5a,0x02,0x00,0x00};
static __declspec(naked) void replyItemStub() {
    __asm {
        mov proposalReplyItem,ecx
        test ecx,ecx
        jz skip
        push 0x53c8fa
        ret
    skip:
        push 0x53ca1a
        ret
    }
}
static __declspec(naked) void replyKindStub() {
    __asm {
        xor eax,eax
        test edi,edi
        jz reply
        mov al,byte ptr [edi+0x25a]
        push 0x53c939
        ret
    reply:
        push ecx
        mov ecx,proposalReplyItem
        test ecx,ecx
        jz done
        mov al,byte ptr [ecx+0x25a]
    done:
        pop ecx
        push 0x53c939
        ret
    }
}
static bool installProposalReplyRows() {
    if(memcmp(reinterpret_cast<void*>(replyItemAt),replyItemExpected,8) || memcmp(reinterpret_cast<void*>(replyKindAt),replyKindExpected,8))return false;
    BYTE item[8]={0xe9,0,0,0,0,0x90,0x90,0x90},kind[8]={0xe9,0,0,0,0,0x90,0x90,0x90};
    *reinterpret_cast<LONG*>(item+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&replyItemStub)-reinterpret_cast<BYTE*>(replyItemAt)-5);
    *reinterpret_cast<LONG*>(kind+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&replyKindStub)-reinterpret_cast<BYTE*>(replyKindAt)-5);
    return patchBytes(reinterpret_cast<void*>(replyItemAt),replyItemExpected,item,8) &&
        patchBytes(reinterpret_cast<void*>(replyKindAt),replyKindExpected,kind,8);
}
