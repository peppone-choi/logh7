// 命令/提案 mail body keywords (design-proposals-orders.md). The CD's handler 00521670 copies 未実装 for 131 keywords;
// this fills the ones the resignation template (messages_0.dat row 8) uses. Handler: int __cdecl h(ctx), ctx+0x20 is the
// format scratch S (world+0x126454), output is an ACP string written at ctx+0x24 (yname writes up to 0x20 bytes).
// S: +0x60 sender and +0x184 recipient records (0x124 each: id +0, name +6 (13 UTF-16), origin +0x20, rank +0x22), +0x2a8 time, +0x2ac command, +0x2ae type
// (0 proposal, 1 order), +0x2af influence, +0x2b0 PCP, +0x2b4 MCP. Native pair record at world+0x43c990 (+0x10 card).
// Offsets: work/mailbox-re (static analysis). Author: Peppone Choi.
#pragma once
// The CD text path is CP932 with the Korean glyphs in the private-use area (restoreKoreanGlyphs reverses it).
// False when the text needs more than the 31 output bytes.
static bool bodyEncode(const wchar_t* text,char (&out)[32]) {
    std::wstring mapped(text);
    for(auto& ch:mapped) {size_t i=0;if(logh::koreanGlyphIndex(ch,i))ch=static_cast<wchar_t>(0xe000+i);}
    memset(out,0,sizeof(out));
    return WideCharToMultiByte(932,0,mapped.c_str(),-1,out,sizeof(out)-1,nullptr,nullptr)!=0;
}
static int bodyText(BYTE* ctx,const wchar_t* text) {
    char out[32];bodyEncode(text,out);memcpy(ctx+0x24,out,strlen(out)+1);return 1;
}
static int bodyFallback(BYTE* ctx) {return reinterpret_cast<int(__cdecl*)(BYTE*)>(0x521670)(ctx);}
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
/** A third person's name from what this client has seen: the 0x1202 directory (world+0x4c83a0, 0x120 records),
 * the mail address book (world+0x448808, 0x124 records) and the compatibility windows' directory; else "#id". */
static bool knownName(unsigned id,wchar_t (&name)[13]) {
    auto world=*reinterpret_cast<BYTE**>(0x7ccffc);if(!world || !id)return false;
    unsigned count=at<unsigned>(world,0x4c83a0);
    for(unsigned i=0;i<count && i<2000;++i)if(at<unsigned>(world,0x4c83a4+i*0x120)==id) {memcpy(name,world+0x4c83a4+i*0x120+6,24);name[12]=0;return true;}
    unsigned book=world[0x448808];
    for(unsigned i=0;i<book && i<100;++i)if(at<unsigned>(world,0x448808+4+i*0x124)==id) {memcpy(name,world+0x448808+4+i*0x124+6,24);name[12]=0;return true;}
    for(unsigned i=0;i<strategyDirectory.count;++i)if(strategyDirectory.people[i].id==id) {memcpy(name,strategyDirectory.people[i].name,24);name[12]=0;return true;}
    return false;
}
// Resignation: the executor resigns its own card, so the target is the recipient. Dismissal (command 6): the 0x0708
// pair record (world+0x43c8f0, receiver 004bd*) names the third person at +0x10 and its card at +0x14.
static bool bodyDismissal(BYTE* ctx) {return at<unsigned short>(bodyScratch(ctx),0x2ac)==6;}
// Appointment (command 5): the sender asks to be appointed; the 0x0707 pair record (world+0x43c8c8) has the card at +0x14.
static bool bodyAppointment(BYTE* ctx) {return at<unsigned short>(bodyScratch(ctx),0x2ac)==5;}
// Demotion (command 2): the 0x0706 pair record (world+0x43c820, RankDown reader 0043d080) has the target's rank
// at +0x10 (u8, filled by the server with the rank at send time) and the target character at +0x14.
static bool bodyDemotion(BYTE* ctx) {return at<unsigned short>(bodyScratch(ctx),0x2ac)==2;}
// Special promotion (command 1, template messages 2/0): the 0x0705 pair record (world+0x4388f8) has the target
// character at +0x10 and the rank after promotion at +0x14 (u8); a valid promotion is one step up, so before = after+1.
static bool bodyPromotion(BYTE* ctx) {return at<unsigned short>(bodyScratch(ctx),0x2ac)==1;}
static int __cdecl bodyTargetName(BYTE* ctx) {
    if(bodyAppointment(ctx))return bodyName(ctx,0x66);
    if(!bodyDismissal(ctx) && !bodyDemotion(ctx) && !bodyPromotion(ctx))return bodyName(ctx,0x18a);
    auto world=*reinterpret_cast<BYTE**>(0x7ccffc);
    unsigned id=!world ? 0 : at<unsigned>(world,bodyDemotion(ctx) ? 0x43c820+0x14 : bodyPromotion(ctx) ? 0x4388f8+0x10 : 0x43c8f0+0x10);
    // The mail's own peers (sender SIC at S+0x60, recipient at S+0x184; id +0, name +6) come first.
    for(unsigned peer:{0x60u,0x184u})if(id && at<unsigned>(bodyScratch(ctx),peer)==id)return bodyName(ctx,peer+6);
    wchar_t name[13]={};if(knownName(id,name))return bodyText(ctx,name);
    wchar_t text[16];swprintf_s(text,L"#%u",id);return bodyText(ctx,text);
}
static int __cdecl bodyTargetRank(BYTE* ctx) {
    if(bodyDemotion(ctx)) {auto world=*reinterpret_cast<BYTE**>(0x7ccffc);return world ? catalogCopy(ctx,5,world[0x43c820+0x10]) : 0;}
    if(bodyDismissal(ctx)) {ctx[0x24]=0;return 1;} // the third person's rank is not known to this client
    if(bodyAppointment(ctx))return catalogCopy(ctx,5,at<unsigned short>(bodyScratch(ctx),0x82)); // sender SIC rank
    return catalogCopy(ctx,5,at<unsigned short>(bodyScratch(ctx),0x1a6));
}
// $xrankold$/$xranknew$ (messages 3/0 demotion, 2/0 promotion): demotion from the 0x0706 rank (one step down is +1),
// promotion from the 0x0705 rank after promotion (before is +1); other commands keep the CD's own handler.
static int changedRank(BYTE* ctx,bool after) {
    bool demotion=bodyDemotion(ctx);
    if(!demotion && !bodyPromotion(ctx))return bodyFallback(ctx);
    auto world=*reinterpret_cast<BYTE**>(0x7ccffc);if(!world)return 0;
    unsigned rank=demotion ? world[0x43c820+0x10]+(after ? 1u : 0u) : world[0x4388f8+0x14]+(after ? 0u : 1u);
    return catalogCopy(ctx,5,rank);
}
static int __cdecl bodyRankOld(BYTE* ctx) {return changedRank(ctx,false);}
static int __cdecl bodyRankNew(BYTE* ctx) {return changedRank(ctx,true);}
static int __cdecl bodyTargetCard(BYTE* ctx) {
    if(bodyDemotion(ctx)) {ctx[0x24]=0;return 1;} // demotion carries no card
    auto world=*reinterpret_cast<BYTE**>(0x7ccffc);
    return world ? catalogCopy(ctx,3,bodyAppointment(ctx) ? at<unsigned>(world,0x43c8c8+0x14) :
        bodyDismissal(ctx) ? at<unsigned>(world,0x43c8f0+0x14) : at<unsigned>(world,0x43c990+0x10)) : 0;
}
// The executor is the mailbox owner, so the place is its current base (own record +0x358); in space it is "우주".
static int __cdecl bodyPlace(BYTE* ctx) {
    auto world=*reinterpret_cast<BYTE**>(0x7ccffc);auto own=world ? at<BYTE*>(world,8) : nullptr;
    unsigned base=own ? at<unsigned>(own,0x358) : 0;wchar_t name[32]={};
    if(base && base<=65535)pickerName(base,name);
    return bodyText(ctx,name[0] ? name : L"우주");
}
// Assignment (command 67, template messages 68/0): the 0x0c0b case of the receiver (004bd037) copies the record whole
// (0x8dc bytes) to world+0x447b60 and then calls 004bfcd0 (call 004bd059), which renders a mail body only while the
// list flag world+0x3579cc is set, i.e. for the packet right after its 0x0f13/0x0f14 (004bdd3b). 004c2920 renders
// that body once and stores it per row (world+0x81ea4+n*0x400, count +0x81ea0); opening a row does not render again
// (005227d0's only caller is 004c2950). So the server's [0x0f13/0x0f14, 0x0c0b] order pairs each row with its own
// record, and the handlers read the record only inside that call: a direct 割当 response (flag clear), another row's
// record or a 67 row with a different pair falls back instead. Base +0x10, outfit +0x14; ship moves count +0x19,
// rows +0x1a (6 bytes: kind u16, signed units +2); troop moves count +0x26c, rows +0x26e (6 bytes: kind u16, signed
// units +4); signed supplies +0x300. Moves go from the base to the outfit, so the planet/fortress side the body shows
// is the opposite sign. Static analysis of the 2004-04 G7MTClient.exe (sha256 bd19263c…).
static BYTE* assignmentPairRecord=nullptr;
static constexpr unsigned assignmentPairCall=0x4bd059;
static constexpr BYTE assignmentPairExpected[5]={0xe8,0x72,0x2c,0x00,0x00};
static void __fastcall assignmentPairRender(BYTE* world,void*,BYTE* record,int unused) {
    assignmentPairRecord=record;
    reinterpret_cast<void(__thiscall*)(void*,BYTE*,int)>(0x4bfcd0)(world,record,unused);
    assignmentPairRecord=nullptr;
}
// 00522850 (generic message path) reaches the same keyword table with no scratch.
static bool bodyAssignment(BYTE* ctx) {BYTE* s=bodyScratch(ctx);return s && at<unsigned short>(s,0x2ac)==67;}
static BYTE* assignmentRecord() {return assignmentPairRecord;}
static int signedText(BYTE* ctx,long long value) {
    wchar_t text[24];swprintf_s(text,value ? L"%+lld" : L"0",value);return bodyText(ctx,text);
}
static int __cdecl bodyAssignmentBase(BYTE* ctx) {
    BYTE* record=bodyAssignment(ctx) ? assignmentRecord() : nullptr;if(!record)return bodyFallback(ctx);
    unsigned base=at<unsigned>(record,0x10);wchar_t name[32]={};
    if(base && base<=65535)pickerName(base,name);
    if(!name[0])swprintf_s(name,L"거점 #%u",base);
    return bodyText(ctx,name);
}
// The grid outfit cache (world+0x811fc, 32-byte records: +0 used, +4 id, +8 kind, +0xb number) that 0054bf40 lists;
// patrols numbered 201..255 are guard units as in outfit_list_names.h.
static int __cdecl bodyAssignmentOutfit(BYTE* ctx) {
    BYTE* record=bodyAssignment(ctx) ? assignmentRecord() : nullptr;if(!record)return bodyFallback(ctx);
    auto world=*reinterpret_cast<BYTE**>(0x7ccffc);
    unsigned id=at<unsigned>(record,0x14);wchar_t name[32]={};
    const wchar_t* kinds[]={L"함대",L"수송함대",L"순찰대",L"지상부대"};
    for(unsigned i=0;id && i<100;++i) {
        BYTE* outfit=world+0x811fc+i*32;
        if(!outfit[0] || at<unsigned>(outfit,4)!=id || outfit[8]>3)continue;
        unsigned number=outfit[0xb];
        swprintf_s(name,L"제%u%ls",number,outfit[8]==2 && number>=201 ? L"경비대" : kinds[outfit[8]]);break;
    }
    if(!name[0])swprintf_s(name,L"부대 #%u",id);
    return bodyText(ctx,name);
}
// Crew kinds are 3 (empire) and 7 (alliance) 艦隊乗組員; the other troop kinds are ground forces (UnitSpecifications).
enum AssignmentUnits {assignShips,assignCrew,assignGround};
static int assignmentUnits(BYTE* ctx,AssignmentUnits category) {
    BYTE* record=bodyAssignment(ctx) ? assignmentRecord() : nullptr;if(!record)return bodyFallback(ctx);
    const bool ship=category==assignShips;
    const unsigned count=ship ? record[0x19] : record[0x26c];
    if(count>(ship ? 99u : 24u))return bodyText(ctx,L"-");
    int total=0;unsigned kinds=0,kind=0;
    for(unsigned i=0;i<count;++i) {
        BYTE* row=record+(ship ? 0x1a : 0x26e)+i*6;
        const unsigned k=at<unsigned short>(row,0);
        const int units=ship ? at<signed char>(row,2) : at<short>(row,4);
        if(!units || (!ship && (k==3 || k==7)!=(category==assignCrew)))continue;
        total-=units;++kinds;kind=k;
    }
    if(kinds==1) {
        wchar_t name[64],text[80];char probe[32];outfitItemName(ship,kind,name);
        swprintf_s(text,L"%ls %+d",name,total);
        if(bodyEncode(text,probe))return bodyText(ctx,text);
    }
    if(kinds<=1)return signedText(ctx,total);
    wchar_t text[32];swprintf_s(text,L"%+d (%u종)",total,kinds);return bodyText(ctx,text);
}
static int __cdecl bodyAssignmentShips(BYTE* ctx) {return assignmentUnits(ctx,assignShips);}
static int __cdecl bodyAssignmentCrew(BYTE* ctx) {return assignmentUnits(ctx,assignCrew);}
static int __cdecl bodyAssignmentGround(BYTE* ctx) {return assignmentUnits(ctx,assignGround);}
static int __cdecl bodyAssignmentSupplies(BYTE* ctx) {
    BYTE* record=bodyAssignment(ctx) ? assignmentRecord() : nullptr;if(!record)return bodyFallback(ctx);
    return signedText(ctx,-static_cast<long long>(at<int>(record,0x300)));
}
static bool installProposalBody() {
    // Each 8-byte entry is {keyword, handler} starting at 0x786b78, so a keyword's handler slot is the next
    // pointer after its string pointer.
    struct Row {unsigned row;void* handler;} rows[]={
        {0x786bb4,&bodyCommand},{0x786bbc,&bodyPlace},{0x786bec,&bodyHalfCp},{0x786bf4,&bodyHalfCp},
        {0x786c14,&bodyDate},{0x786c2c,&bodyTargetCard},{0x786c34,&bodyExecutor},
        {0x786c74,&bodyInfluence},{0x786cbc,&bodyTargetName},{0x786d24,&bodyProposer},{0x786d34,&bodyTargetRank},
        {0x786d3c,&bodyRankNew},{0x786d44,&bodyRankOld},
        {0x786bfc,&bodyAssignmentCrew},{0x786c44,&bodyAssignmentOutfit},{0x786c94,&bodyAssignmentGround},
        {0x786cb4,&bodyAssignmentSupplies},{0x786d04,&bodyAssignmentBase},{0x786e44,&bodyAssignmentShips}};
    const unsigned stub=0x521670;
    for(auto& r:rows)if(r.handler && at<unsigned>(reinterpret_cast<void*>(r.row),0)!=stub)return false;
    if(memcmp(reinterpret_cast<void*>(assignmentPairCall),assignmentPairExpected,5))return false;
    for(auto& r:rows)if(r.handler) {
        unsigned replacement=reinterpret_cast<unsigned>(r.handler);
        if(!patchBytes(reinterpret_cast<void*>(r.row),&stub,&replacement,4))return false;
    }
    BYTE call[5]={0xe8};
    *reinterpret_cast<LONG*>(call+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&assignmentPairRender)-reinterpret_cast<BYTE*>(assignmentPairCall)-5);
    return patchBytes(reinterpret_cast<void*>(assignmentPairCall),assignmentPairExpected,call,5);
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

// A reply row's +0x25b both picks the row kind (5 = approved, buttons off) and is copied to the template variant
// (world+0x12670c), where 5 has no variant and showed NO DATA. Keep the item's value but render variant 0
// (call 004c277a in the 0x0f14 store).
static constexpr unsigned replyStoreCall=0x4c277a;
static constexpr BYTE replyStoreExpected[5]={0xe8,0x11,0x00,0x00,0x00};
static void __fastcall replyStore(BYTE* world,void*,int type,void* record) {
    reinterpret_cast<void(__thiscall*)(void*,int,void*)>(0x4c2790)(world,type,record);
    world[0x12670c]=0;
}
static bool installReplyVariant() {
    if(memcmp(reinterpret_cast<void*>(replyStoreCall),replyStoreExpected,5))return false;
    BYTE call[5]={0xe8};
    *reinterpret_cast<LONG*>(call+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&replyStore)-reinterpret_cast<BYTE*>(replyStoreCall)-5);
    return patchBytes(reinterpret_cast<void*>(replyStoreCall),replyStoreExpected,call,5);
}
