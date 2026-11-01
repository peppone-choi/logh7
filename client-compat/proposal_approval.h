// 承認 for 命令/提案 rows (design-proposals-orders.md, user decision 2026-11-01). The CD's fifth mailbox toolbar
// button is labelled 承認 but deletes every row (widget 0x66, 0053a5d4). On an unreplied received proposal row it
// sends 0x0f14 with reply value 3 instead, built like the native reply 0053b820. Offsets: work/mailbox-re (static).
// Author: Peppone Choi.
#pragma once
static constexpr unsigned approvalClickCall=0x53a5f3;
static constexpr BYTE approvalClickExpected[5]={0xe8,0xf8,0x6f,0xfc,0xff};
static BYTE __fastcall approvalClick(void* root,void*,int category,void* widget,BYTE* state,int flag) {
    BYTE clicked=reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,BYTE*,int)>(0x5015f0)(root,category,widget,state,flag);
    if(!clicked)return clicked;
    BYTE* mailbox=state-0xa10;
    if(at<unsigned>(mailbox,0xa50)!=0)return clicked; // received tab only
    BYTE* rows=mailbox+0x14e8214;
    int count=at<int>(rows,0x8e4),selected=at<int>(rows,0x8e8);
    if(selected<0 || selected>=count)return clicked;
    auto row=static_cast<BYTE*>(reinterpret_cast<void*(__thiscall*)(void*,int)>(0x576ec0)(rows,selected));
    unsigned kind=at<unsigned>(row,8)&0xff;
    if(!row || kind==0 || kind==0x32 || kind%10)return clicked; // not an unreplied proposal
    BYTE* box=mailbox+at<BYTE>(row,0xd)*0xa45bc+0x5ca48;unsigned item=at<unsigned>(row,0x10);
    if(at<unsigned>(box,0x4b008+item*4)==1)return clicked; // a reply row
    BYTE* proposal=box+0x4b4b8+item*0x264;
    static BYTE record[0x25c];memset(record,0,sizeof(record));
    at<unsigned>(record,4)=at<unsigned>(proposal,0);
    memcpy(record+0xc,proposal+0x130,0x124);memcpy(record+0x130,proposal+0xc,0x124);record[0x25b]=3;
    reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(*reinterpret_cast<void**>(0x7ccffc),0,0x50,record);
    // Like the native reply (+reply+1): a later digit stops the reply buttons; +10 keeps 22/77 (承諾) as the result text.
    at<unsigned>(row,8)+=10;at<int>(mailbox,0xa54)=-1;
    return 0; // the click was the approval, not the delete-all
}
static bool installProposalApproval() {
    if(memcmp(reinterpret_cast<void*>(approvalClickCall),approvalClickExpected,sizeof(approvalClickExpected)))return false;
    BYTE call[5]={0xe8};
    *reinterpret_cast<LONG*>(call+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&approvalClick)-reinterpret_cast<BYTE*>(approvalClickCall)-5);
    return patchBytes(reinterpret_cast<void*>(approvalClickCall),approvalClickExpected,call,sizeof(call));
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
