// Verified mail/messenger call sites only. Author: 최병호.
#pragma once
#include "mail_text.h"
#include "mail_delivery.h"
#include "mail_refresh.h"

static thread_local logh::MailDelivery mailDelivery;
static thread_local void* mailEditorContext=nullptr;
static thread_local logh::MailRefresh mailRefresh;
static bool mailRefreshView(void* context,void* world,unsigned owner,bool includeDraft=false) {
    const unsigned mode=at<unsigned>(context,0xa00);
    if(!world || !owner || mailDelivery.pending || at<unsigned>(context,0xa08)!=3 ||
        (mode!=1 && !(includeDraft && mode==2)) ||
        !reinterpret_cast<unsigned(__thiscall*)(void*)>(0x5357f0)(context))return false;
    // This helper operates on the original queue subobject, not the mail UI.
    return reinterpret_cast<unsigned(__thiscall*)(void*)>(0x53d090)(
        static_cast<BYTE*>(context)+0x14e81c4)==10;
}
static void mailDraftEnabled(void* context,bool enabled) {
    for (unsigned offset:{0x2cu,0x30u,0x190u,0x194u,0x1b4u}) {
        void* widget=at<void*>(context,offset);
        if (widget) reinterpret_cast<void(__thiscall*)(void*,void*,unsigned)>(0x5024e0)(
            at<void*>(context,0x24),widget,enabled?1u:0u);
    }
}
static bool mailRequestNonce(unsigned& high,unsigned& low) {
    HCRYPTPROV provider=0;
    if (!CryptAcquireContextW(&provider,nullptr,nullptr,PROV_RSA_AES,CRYPT_VERIFYCONTEXT)) return false;
    unsigned words[2]={};
    bool ok=CryptGenRandom(provider,sizeof(words),reinterpret_cast<BYTE*>(words))!=FALSE;
    CryptReleaseContext(provider,0);
    high=words[0]|0x80000000u;low=words[1];
    // -1 remains the native unset sentinel; reserve negative ID <= -2 for the
    // replacement server's account/generation-bound 63-bit request key.
    if (high==0xffffffffu) high=0xfffffffeu;
    return ok;
}
static BYTE __fastcall mailNativeSend(void* world,void*,unsigned seconds,unsigned selector,BYTE* record) {
    if (selector==0x4c && record && mailDelivery.pending && mailDelivery.world==world &&
        at<unsigned>(record,0x0c)==mailDelivery.sender && at<unsigned>(record,0x130)==mailDelivery.recipient) {
        at<unsigned>(record,0)=mailDelivery.requestId;
        at<unsigned>(record,4)=mailDelivery.requestReference;
    }
    // The original sender still serializes/encrypts the native record.
    return reinterpret_cast<BYTE(__thiscall*)(void*,unsigned,unsigned,BYTE*)>(0x4b78a0)(world,seconds,selector,record);
}
static void receivedMailOutcome(void* world,unsigned opcode,const BYTE* record) {
    // The shared outfit/session dispatcher already ran the original handler.
    // Observe that outcome without installing a second hook at 004b8a78.
    if (!record) return;
    if ((opcode&0xffff)==0x0f10)
        mailDelivery.accepted(world,at<unsigned>(const_cast<BYTE*>(record),0x0c),
            at<unsigned>(const_cast<BYTE*>(record),0x130),at<unsigned>(const_cast<BYTE*>(record),4));
    else if ((opcode&0xffff)==0x0500)
        mailDelivery.rejected(world,*reinterpret_cast<const unsigned short*>(record));
    else if ((opcode&0xffff)==0x0f08) {
        const auto data=const_cast<BYTE*>(record);
        mailRefresh.begin(world,at<unsigned>(data,0),at<BYTE>(data,0x125)!=0,
            logh::personalMailAddress(data));
    } else if ((opcode&0xffff)==0x0f09)mailRefresh.end(world);
}
static void __fastcall mailEditorTick(void* context,void*,void* input) {
    void* previous=mailEditorContext;mailEditorContext=context;
    struct RestoreContext {void* previous;~RestoreContext(){mailEditorContext=previous;}} restore={previous};
    void* world=*reinterpret_cast<void**>(0x7ccffc);
    if (mailDelivery.pending && (mailDelivery.world!=world || !world || !at<void*>(world,8) ||
        reinterpret_cast<unsigned(__thiscall*)(void*)>(0x4b5b80)(at<void*>(world,8))!=mailDelivery.sender)) {
        // An unknown outcome is never automatically retransmitted after logout.
        if (mailDelivery.context==context) mailDraftEnabled(context,true);
        mailDelivery.reset();
    }
    if (mailDelivery.pending && mailDelivery.context==context) {
        if (at<unsigned>(context,0xa00)!=2) mailDelivery.draftOpen=false;
        if (mailDelivery.response) {
            const bool accepted=mailDelivery.response==1;
            const bool active=mailDelivery.draftOpen && at<unsigned>(context,0xa00)==2;
            const unsigned mode=mailDelivery.mode,option=mailDelivery.option;
            if (active && !accepted) {
                // Keep the exact wide draft, including mixed Korean text, in
                // the live original edit controls; never reopen a closed draft.
                for (unsigned index=0;index<2;++index) {
                    void* widget=at<void*>(context,index?0x30:0x2c);
                    if (widget) reinterpret_cast<void(__thiscall*)(void*,void*,const wchar_t*)>(0x505000)(
                        at<void*>(context,0x24),widget,(index?mailDelivery.body:mailDelivery.title).c_str());
                }
            }
            mailDraftEnabled(context,true);mailDelivery.reset();
            if (accepted && active)
                reinterpret_cast<void(__thiscall*)(void*,unsigned,unsigned)>(0x53a670)(context,mode,option);
        } else if (mailDelivery.draftOpen) mailDraftEnabled(context,false);
    }
    const unsigned owner=world && at<void*>(world,8) ?
        reinterpret_cast<unsigned(__thiscall*)(void*)>(0x4b5b80)(at<void*>(world,8)) : 0;
    if(mailRefresh.take(world,owner,mailRefreshView(context,world,owner)))
        // Force the original same-mode refresh, including its native queries,
        // row allocation and normal completion. Never reset an active draft.
        reinterpret_cast<void(__thiscall*)(void*,unsigned,unsigned)>(0x53a670)(context,1,1);
    reinterpret_cast<void(__thiscall*)(void*,void*)>(0x539ce0)(context,input);
    if (mailDelivery.pending && mailDelivery.context==context && mailDelivery.draftOpen)
        mailDraftEnabled(context,false);
    // Receive during composition too; applying still requires the list view.
    mailRefresh.observe(world,owner,mailRefreshView(context,world,owner,true));
}

static void __cdecl mailReplyEditor(void* context,const BYTE* record,bool title) {
    try {
        std::wstring text;
        if (!context || !record || !logh::mailReplyText(
            reinterpret_cast<const wchar_t*>(record+(title?0x25a:0x35c)),title,
            at<int>(context,0xa50)==0,text)) return;
        void* widget=at<void*>(context,title?0x2c:0x30);
        if (widget) reinterpret_cast<void(__thiscall*)(void*,void*,const wchar_t*)>(0x505000)(
            at<void*>(context,0x24),widget,text.c_str());
    } catch (...) { /* Leave the cleared editor empty on allocation failure. */ }
}
static void __cdecl mailReplyTitle(void* context,const BYTE* record) { mailReplyEditor(context,record,true); }
static void __cdecl mailReplyBody(void* context,const BYTE* record) { mailReplyEditor(context,record,false); }
static unsigned mailTitleResume=0x53bd72,mailBodyResume=0x53bec6;
static void __declspec(naked) mailReplyTitleBridge() {
    __asm {
        pushfd
        pushad
        push ebx
        push ebp
        call mailReplyTitle
        add esp, 8
        popad
        popfd
        jmp dword ptr [mailTitleResume]
    }
}
static void __declspec(naked) mailReplyBodyBridge() {
    __asm {
        pushfd
        pushad
        push ebx
        push ebp
        call mailReplyBody
        add esp, 8
        popad
        popfd
        jmp dword ptr [mailBodyResume]
    }
}
static void __cdecl mailListTitle(void* list,int row,const BYTE* record) {
    try {
        std::wstring title;
        if (!list || !record || !logh::mailWideText(reinterpret_cast<const wchar_t*>(record+0x25a),
            logh::mailTitleUnits,title)) return;
        title.insert(0,4,L' '); // Original list indentation at 00788a00.
        reinterpret_cast<void(__thiscall*)(void*,void*,const wchar_t*,int,int)>(0x505b50)(
            at<void*>(list,0x18),at<void*>(list,0x20),title.c_str(),row,1);
    } catch (...) { }
}
static unsigned mailReadListResume=0x53b0d9,mailRefreshListResume=0x53c525;
static void __declspec(naked) mailReadListBridge() {
    __asm {
        pushfd
        pushad
        push eax
        mov eax, dword ptr [esp+0x28c] // Original ESP+264: row argument.
        push eax
        mov eax, dword ptr [esp+0x40]  // Original ESP+14: native list.
        push eax
        call mailListTitle
        add esp, 12
        popad
        popfd
        jmp dword ptr [mailReadListResume]
    }
}
static void __declspec(naked) mailRefreshListBridge() {
    __asm {
        pushfd
        pushad
        push ebp
        push ebx
        mov eax, dword ptr [esp+0x44] // Original ESP+18: native list.
        push eax
        call mailListTitle
        add esp, 12
        popad
        popfd
        mov esi, dword ptr [esp+0x18] // Also needed by the original date column.
        jmp dword ptr [mailRefreshListResume]
    }
}

struct MailSendFrame {
    const std::wstring& title;
    const std::wstring& body;
    MailSendFrame* previous;
};
static thread_local MailSendFrame* mailSendFrame=nullptr;
static thread_local bool mailSendRejected=false;
static void __cdecl mailTitlePool(void* pool,const char* text,int count) {
    if (mailSendFrame)
        reinterpret_cast<void(__cdecl*)(void*,const wchar_t*,int)>(0x4ea8b0)(pool,mailSendFrame->title.c_str(),count);
    else reinterpret_cast<void(__cdecl*)(void*,const char*,int)>(0x4eaaf0)(pool,text,count);
}
static void __cdecl mailBodyPool(void* pool,const char* text,int count) {
    if (mailSendFrame)
        reinterpret_cast<void(__cdecl*)(void*,const wchar_t*,int)>(0x4ea8b0)(pool,mailSendFrame->body.c_str(),count);
    else reinterpret_cast<void(__cdecl*)(void*,const char*,int)>(0x4eaaf0)(pool,text,count);
}
static BYTE __cdecl sendMailInput(unsigned sender,unsigned recipient,const char* const* title,const char* const* body) {
    // The native callers ignore the return value. Keep a rejected draft open
    // at their two subsequent mode-change calls instead of silently truncating.
    if (mailSendRejected || mailDelivery.pending) return 0;
    std::wstring wideTitle,wideBody;
    try {
        if (!sender || sender>0x7fefffff || !recipient || recipient>0x7fefffff || !mailEditorContext || !title || !body ||
            !logh::mailInputText(*title,logh::mailTitleUnits,wideTitle) ||
            !logh::mailInputText(*body,logh::mailBodyUnits,wideBody)) { mailSendRejected=true;return 0; }
        unsigned high=0,low=0;
        if (!mailRequestNonce(high,low) || !mailDelivery.begin(mailEditorContext,
            *reinterpret_cast<void**>(0x7ccffc),sender,recipient,high,low,wideTitle,wideBody)) {mailSendRejected=true;return 0;}
    } catch (...) { mailSendRejected=true;return 0; }
    MailSendFrame frame={wideTitle,wideBody,mailSendFrame};
    struct RestoreFrame {
        MailSendFrame* previous;
        ~RestoreFrame() { mailSendFrame=previous; }
    } restore={frame.previous};
    mailSendFrame=&frame;
    // Original constructors, copies, selector 4c and destructors; the verified
    // final send call only tags request ID/ref with the replacement-server key.
    return reinterpret_cast<BYTE(__cdecl*)(unsigned,unsigned,const char* const*,const char* const*)>(0x4b4b60)(
        sender,recipient,title,body);
}
static void __fastcall finishMailInput(void* context,void*,unsigned mode,unsigned option) {
    if (mailSendRejected) { mailSendRejected=false;return; }
    if (mailDelivery.pending && mailDelivery.context==context) {
        mailDelivery.mode=mode;mailDelivery.option=option;
        mailDraftEnabled(context,false);return;
    }
    reinterpret_cast<void(__thiscall*)(void*,unsigned,unsigned)>(0x53a670)(context,mode,option);
}
static const char* __fastcall mailDefaultTitle(void* catalogue,void*,unsigned table,unsigned index) {
    const char* original=reinterpret_cast<const char*(__thiscall*)(void*,unsigned,unsigned)>(0x522010)(catalogue,table,index);
    static thread_local std::string converted;
    try {
        std::wstring text;
        if (table==0x16 && index==0x4b) text=L"(제목 없음)";
        else if (!logh::japaneseFragment(original,text)) { mailSendRejected=true;return ""; }
        BOOL loss=FALSE;
        int bytes=WideCharToMultiByte(CP_ACP,WC_NO_BEST_FIT_CHARS,text.c_str(),-1,nullptr,0,nullptr,&loss);
        if (bytes<=0 || loss) { mailSendRejected=true;return ""; }
        converted.resize(static_cast<size_t>(bytes));
        if (WideCharToMultiByte(CP_ACP,WC_NO_BEST_FIT_CHARS,text.c_str(),-1,&converted[0],bytes,nullptr,&loss)!=bytes || loss)
            { mailSendRejected=true;return ""; }
        return converted.c_str(); // Immediately copied by native CString assignment.
    } catch (...) { mailSendRejected=true;return ""; }
}
static BYTE __stdcall mailLineLeadByte(const char* text) {
    return text && IsDBCSLeadByteEx(CP_ACP,static_cast<BYTE>(*text)) ? 1 : 0;
}
static BYTE __cdecl messengerInputPool(void* pool,const char* text,int count) {
    try {
        std::wstring wide;
        if (count!=-1 || !logh::mailInputText(text,logh::mailBodyUnits,wide)) return 0;
        reinterpret_cast<void(__cdecl*)(void*,const wchar_t*,int)>(0x4ea8b0)(pool,wide.c_str(),count);
        return 1;
    } catch (...) { return 0; }
}
static unsigned messengerEnterCleanup=0x543da1,messengerButtonCleanup=0x543f67;
static void __declspec(naked) messengerEnterBridge() {
    __asm {
        push dword ptr [esp+12]
        push dword ptr [esp+12]
        push dword ptr [esp+12]
        call messengerInputPool
        add esp, 12
        test al, al
        jz rejected
        ret
    rejected:
        add esp, 16 // Return address and three pending cdecl arguments.
        jmp dword ptr [messengerEnterCleanup]
    }
}
static void __declspec(naked) messengerButtonBridge() {
    __asm {
        push dword ptr [esp+12]
        push dword ptr [esp+12]
        push dword ptr [esp+12]
        call messengerInputPool
        add esp, 12
        test al, al
        jz rejected
        ret
    rejected:
        add esp, 16
        jmp dword ptr [messengerButtonCleanup]
    }
}
static void __cdecl messengerReceived(void* context) {
    try {
        BYTE* world=*reinterpret_cast<BYTE**>(0x7ccffc);
        void* widget=at<void*>(context,0x30);
        std::wstring body;
        if (!world || !widget || !logh::mailWideText(reinterpret_cast<const wchar_t*>(world+0x485f76),
            logh::mailBodyUnits,body)) return;
        auto append=reinterpret_cast<void(__thiscall*)(void*,void*,const wchar_t*)>(0x503610);
        void* gui=at<void*>(context,0x10);
        // Keep native backspace/indent/newline controls and Unicode name/body.
        append(gui,widget,L"\b\b");
        append(gui,widget,reinterpret_cast<const wchar_t*>(world+0x485e56));
        append(gui,widget,L"：\n　");
        append(gui,widget,L"\b\b");
        append(gui,widget,body.c_str());
        append(gui,widget,L"\n\n");
    } catch (...) { }
}
static unsigned messengerReceivedResume=0x5437af;
static void __declspec(naked) messengerReceivedBridge() {
    __asm {
        pushfd
        pushad
        push ebp
        call messengerReceived
        add esp, 4
        popad
        popfd
        jmp dword ptr [messengerReceivedResume]
    }
}
// The CD branches here for an away peer even when 00545a90 found no call slot.
// Keep valid call/accept behavior and return through its existing cleanup for null.
static unsigned messengerSlotResume=0x545508,messengerSlotCleanup=0x545617;
static void __declspec(naked) messengerCallSlotBridge() {
    __asm {
        test eax, eax
        jz missing
        mov ecx, dword ptr [eax]
        cmp ecx, -1
        jmp dword ptr [messengerSlotResume]
    missing:
        jmp dword ptr [messengerSlotCleanup]
    }
}
struct MailTextPatch { unsigned address; BYTE size; BYTE expected[6]; const void* target; bool call; };
#define MAIL_TARGET(function) reinterpret_cast<const void*>(&function)
static const MailTextPatch mailTextPatches[]={
    {0x53bc5e,6,{0x8d,0x83,0x5a,0x02,0,0},MAIL_TARGET(mailReplyTitleBridge),false},
    {0x53bd83,6,{0x81,0xc3,0x5c,0x03,0,0},MAIL_TARGET(mailReplyBodyBridge),false},
    {0x53b035,6,{0x81,0xc1,0x5a,0x02,0,0},MAIL_TARGET(mailReadListBridge),false},
    {0x53c4b6,6,{0x8d,0x95,0x5a,0x02,0,0},MAIL_TARGET(mailRefreshListBridge),false},
    {0x539ead,5,{0xe8,0xae,0xac,0xf7,0xff},MAIL_TARGET(sendMailInput),true},
    {0x53a3c7,5,{0xe8,0x94,0xa7,0xf7,0xff},MAIL_TARGET(sendMailInput),true},
    {0x4b4bb3,5,{0xe8,0x38,0x5f,0x03,0},MAIL_TARGET(mailTitlePool),true},
    {0x4b4bc9,5,{0xe8,0x22,0x5f,0x03,0},MAIL_TARGET(mailBodyPool),true},
    {0x539f5f,5,{0xe8,0x0c,0x07,0,0},MAIL_TARGET(finishMailInput),true},
    {0x53a44b,5,{0xe8,0x20,0x02,0,0},MAIL_TARGET(finishMailInput),true},
    {0x539e82,5,{0xe8,0x89,0x81,0xfe,0xff},MAIL_TARGET(mailDefaultTitle),true},
    {0x53a38b,5,{0xe8,0x80,0x7c,0xfe,0xff},MAIL_TARGET(mailDefaultTitle),true},
    {0x53ced2,5,{0xe8,0x89,0,0,0},MAIL_TARGET(mailLineLeadByte),true},
    {0x543d14,5,{0xe8,0xd7,0x6d,0xfa,0xff},MAIL_TARGET(messengerEnterBridge),true},
    {0x543ed8,5,{0xe8,0x13,0x6c,0xfa,0xff},MAIL_TARGET(messengerButtonBridge),true},
    {0x5436b3,6,{0x81,0xc2,0x76,0x5f,0x48,0},MAIL_TARGET(messengerReceivedBridge),false},
    {0x545503,5,{0x8b,0x08,0x83,0xf9,0xff},MAIL_TARGET(messengerCallSlotBridge),false},
    {0x53bc20,5,{0xe8,0xcb,0xf0,0xfa,0xff},MAIL_TARGET(displayNameCString),true},
    {0x4fd32f,5,{0xe8,0xac,0xc9,0x03,0},MAIL_TARGET(mailEditorTick),true},
    {0x4b4c93,5,{0xe8,0x08,0x2c,0,0},MAIL_TARGET(mailNativeSend),true}
};
#undef MAIL_TARGET
static bool installMailInput() {
    const BYTE visible[]={0x8b,0x41,0x24,0x85,0xc0};
    const BYTE mode[]={0x55,0x56,0x8b,0xf1};
    const BYTE queue[]={0x8b,0x41,0x04,0x8b,0x44,0x81,0x08,0xc3};
    if(memcmp(reinterpret_cast<void*>(0x5357f0),visible,sizeof(visible)) ||
        memcmp(reinterpret_cast<void*>(0x53a670),mode,sizeof(mode)) ||
        memcmp(reinterpret_cast<void*>(0x53d090),queue,sizeof(queue)))return false;
    const BYTE oldCleanup[]={0x8d,0x4c,0x24,0x24},newCleanup[]={0xeb,0x12,0x90,0x90};
    if (memcmp(reinterpret_cast<void*>(0x53c54f),oldCleanup,sizeof(oldCleanup))) return false;
    for (const auto& row:mailTextPatches)
        if (memcmp(reinterpret_cast<void*>(row.address),row.expected,row.size)) return false;
    for (const auto& row:mailTextPatches) {
        BYTE replacement[6]={static_cast<BYTE>(row.call?0xe8:0xe9),0,0,0,0,0x90};
        *reinterpret_cast<LONG*>(replacement+1)=static_cast<LONG>(static_cast<const BYTE*>(row.target)-
            reinterpret_cast<const BYTE*>(row.address)-5);
        if (!patchBytes(reinterpret_cast<void*>(row.address),row.expected,replacement,row.size)) return false;
    }
    // Refresh-list title no longer creates its temporary CString. Skip its
    // paired destructor/state write; the original date-column code still runs.
    return patchBytes(reinterpret_cast<void*>(0x53c54f),oldCleanup,newCleanup,sizeof(oldCleanup));
}
