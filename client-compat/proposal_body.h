// 命令/提案 mail body keywords (design-proposals-orders.md). The CD's handler 00521670 copies 未実装 for 131 keywords;
// this fills the ones the resignation template (messages_0.dat row 8) uses. Handler: int __cdecl h(ctx), ctx+0x20 is the
// format scratch S (world+0x126454), output is an ACP string written at ctx+0x24 (yname writes up to 0x20 bytes).
// S: +0x66 sender name, +0x18a recipient name, +0x1a4 recipient rank, +0x2a8 time, +0x2ac command, +0x2ae type
// (0 proposal, 1 order), +0x2af influence, +0x2b0 PCP, +0x2b4 MCP. Native pair record at world+0x43c990 (+0x10 card).
// Offsets: work/mailbox-re (static analysis). Author: Peppone Choi.
#pragma once
// The CD text path is CP932 with the Korean glyphs in the private-use area (restoreKoreanGlyphs reverses it).
static int bodyText(BYTE* ctx,const wchar_t* text) {
    std::wstring mapped(text);
    for(auto& ch:mapped)for(unsigned i=0;i<logh::koreanGlyphCount;++i)if(logh::koreanGlyphs()[i]==ch) {ch=static_cast<wchar_t>(0xe000+i);break;}
    char out[32]={};WideCharToMultiByte(932,0,mapped.c_str(),-1,out,sizeof(out)-1,nullptr,nullptr);
    memcpy(ctx+0x24,out,strlen(out)+1);return 1;
}
static BYTE* bodyScratch(BYTE* ctx) {return at<BYTE*>(ctx,0x20);}
static int __cdecl bodyCommand(BYTE* ctx) {return bodyText(ctx,at<BYTE>(bodyScratch(ctx),0x2ae) ? L"[명령] " : L"[제안] ");}
static int __cdecl bodyDate(BYTE* ctx) {
    unsigned t=at<unsigned>(bodyScratch(ctx),0x2a8);wchar_t s[32];
    swprintf_s(s,L"%u일 %02u:%02u",t/86400+1,t/3600%24,t/60%60);return bodyText(ctx,s);
}
static int bodyName(BYTE* ctx,unsigned offset) {
    wchar_t name[13]={};memcpy(name,bodyScratch(ctx)+offset,12*sizeof(wchar_t));return bodyText(ctx,name);
}
static int __cdecl bodyProposer(BYTE* ctx) {return bodyName(ctx,0x66);}
static int __cdecl bodyExecutor(BYTE* ctx) {return bodyName(ctx,0x18a);}
static int __cdecl bodyHalfCp(BYTE* ctx) {
    BYTE* s=bodyScratch(ctx);wchar_t t[24];swprintf_s(t,L"MCP %u",at<unsigned>(s,0x2b0)+at<unsigned>(s,0x2b4));return bodyText(ctx,t);
}
static int __cdecl bodyInfluence(BYTE* ctx) {
    wchar_t t[24];swprintf_s(t,L"영향력 %u",max(1u,static_cast<unsigned>(at<BYTE>(bodyScratch(ctx),0x2af))));return bodyText(ctx,t);
}
static int catalogCopy(BYTE* ctx,unsigned group,unsigned index) {
    auto text=reinterpret_cast<const char*(__thiscall*)(void*,unsigned,unsigned)>(0x522010)(reinterpret_cast<void*>(0x2217400),group,index);
    if(!text)return 0;strncpy_s(reinterpret_cast<char*>(ctx+0x24),32,text,_TRUNCATE);return 1;
}
// Resignation: the executor resigns its own card, so the target is the recipient.
static int __cdecl bodyTargetName(BYTE* ctx) {return bodyName(ctx,0x18a);}
static int __cdecl bodyTargetRank(BYTE* ctx) {return catalogCopy(ctx,5,at<unsigned short>(bodyScratch(ctx),0x1a4));}
static int __cdecl bodyTargetCard(BYTE* ctx) {
    auto world=*reinterpret_cast<BYTE**>(0x7ccffc);
    return world ? catalogCopy(ctx,3,at<unsigned>(world,0x43c990+0x10)) : 0;
}
static int __cdecl bodyPlace(BYTE* ctx) {return bodyText(ctx,L"직무 소재지");}
static bool installProposalBody() {
    // Each 8-byte entry is {keyword, handler} starting at 0x786b78, so a keyword's handler slot is the next
    // pointer after its string pointer.
    struct Row {unsigned row;void* handler;} rows[]={
        {0x786bb4,&bodyCommand},{0x786bbc,&bodyPlace},{0x786bec,&bodyHalfCp},{0x786bf4,&bodyHalfCp},
        {0x786c14,&bodyDate},{0x786c2c,&bodyTargetCard},{0x786c34,&bodyExecutor},
        {0x786c74,&bodyInfluence},{0x786cbc,&bodyTargetName},{0x786d24,&bodyProposer},{0x786d34,&bodyTargetRank}};
    const unsigned stub=0x521670;
    for(auto& r:rows)if(r.handler && at<unsigned>(reinterpret_cast<void*>(r.row),0)!=stub)return false;
    for(auto& r:rows)if(r.handler) {
        unsigned replacement=reinterpret_cast<unsigned>(r.handler);
        if(!patchBytes(reinterpret_cast<void*>(r.row),&stub,&replacement,4))return false;
    }
    return true;
}

// A 0x0f13 row does not set the template variant (world+0x12670c, S+0x2b8), so it kept the previous 0x0f14 reply
// value and picked an empty or NO DATA variant. Reset it to variant 0 after storing each 0x0f13 (call 004c273a).
static constexpr unsigned suggestStoreCall=0x4c273a;
static constexpr BYTE suggestStoreExpected[5]={0xe8,0x51,0x00,0x00,0x00};
static void __fastcall suggestStore(BYTE* world,void*,int type,void* record) {
    reinterpret_cast<void(__thiscall*)(void*,int,void*)>(0x4c2790)(world,type,record);
    world[0x12670c]=0;
}
static bool installSuggestVariant() {
    if(memcmp(reinterpret_cast<void*>(suggestStoreCall),suggestStoreExpected,5))return false;
    BYTE call[5]={0xe8};
    *reinterpret_cast<LONG*>(call+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&suggestStore)-reinterpret_cast<BYTE*>(suggestStoreCall)-5);
    return patchBytes(reinterpret_cast<void*>(suggestStoreCall),suggestStoreExpected,call,5);
}
