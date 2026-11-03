// Fills the info-menu outfit list's 種類/艦艇隻数 columns, which the CD's
// 0054bf40 (category 4) leaves as empty strings. Kind comes from the grid
// outfit cache; ship counts from one friendly 032e query per row. Author: Peppone Choi.
#pragma once
#include "unicode_text.h"
#include "outfit_info_data.h"
static SRWLOCK outfitColumnLock=SRWLOCK_INIT;
static struct OutfitListColumns {
    void* manager; void* world; void* root; void* list;
    unsigned ids[100], rows[100], count, next, pending, ready[100], boats[100];
    bool active;
    ULONGLONG sentAt;
} outfitColumns={};
static void outfitColumnCell(unsigned row,unsigned col,const wchar_t* text) {
    auto& s=outfitColumns;
    reinterpret_cast<void(__thiscall*)(void*,void*,const wchar_t*,int,int)>(0x505b50)(s.root,s.list,text,row,col);
}
static int __cdecl buildOutfitListColumns(void* manager,int category,void* selection) {
    int result=reinterpret_cast<int(__cdecl*)(void*,int,void*)>(0x54bf40)(manager,category,selection);
    auto world=*reinterpret_cast<BYTE**>(0x7ccffc);auto own=world ? at<BYTE*>(world,8) : nullptr;
    AcquireSRWLockExclusive(&outfitColumnLock);
    auto& s=outfitColumns;s.active=false;s.count=s.next=s.pending=0;
    ReleaseSRWLockExclusive(&outfitColumnLock);
    if (category!=4 || !(result&0xff) || !world || !own || !own[0]) return result;
    auto root=reinterpret_cast<void*(__thiscall*)(void*,int)>(0x50cf40)(manager,12);
    auto list=root ? reinterpret_cast<void*(__thiscall*)(void*,int,int)>(0x502780)(root,4,0) : nullptr;
    if (!list) return result;
    AcquireSRWLockExclusive(&outfitColumnLock);
    s.manager=manager;s.world=world;s.root=root;s.list=list;
    ReleaseSRWLockExclusive(&outfitColumnLock);
    // Same row order as 0054bf40: every occupied 32-byte cache record is one row.
    unsigned row=0;
    for (unsigned i=0;i<100;++i) {
        auto record=world+0x811fc+i*32;
        if (!record[0]) continue;
        ++row;
        unsigned id=logh::infoWord(record,4),kind=record[8],power=record[9];
        wchar_t name[32];
        logh::catalogueText(kind<4 ? reinterpret_cast<const char*(__thiscall*)(void*,int,int)>(0x522010)
            (reinterpret_cast<void*>(0x2217400),83,kind) : nullptr,name);
        outfitColumnCell(row,2,name[0] ? name : L"—");
        // The server answers 032e only for the viewer's own faction; others stay unknown.
        if (id && power==own[0x28] && s.count<100) {s.ids[s.count]=id;s.rows[s.count]=row;s.ready[s.count]=0;++s.count;}
        else outfitColumnCell(row,3,L"—");
    }
    AcquireSRWLockExclusive(&outfitColumnLock);s.active=s.count!=0;ReleaseSRWLockExclusive(&outfitColumnLock);
    return result;
}
static void outfitListParty(const BYTE* p) {
    AcquireSRWLockExclusive(&outfitColumnLock);
    auto& s=outfitColumns;
    unsigned id=logh::infoWord(p,0);
    if (s.active && s.pending==id) {
        unsigned boats=0;
        for (unsigned i=0;i<p[0x180];++i) boats+=logh::infoShort(p+0x184+i*0x120,4);
        for (unsigned i=0;i<s.count;++i) if (s.ids[i]==id) {s.boats[i]=boats;s.ready[i]=1;}
        s.pending=0;
    }
    ReleaseSRWLockExclusive(&outfitColumnLock);
}
static void updateOutfitListColumns(void* manager) {
    unsigned selector=0,query[3]={},row=0,boats=0;bool write=false;void* world=nullptr;
    AcquireSRWLockExclusive(&outfitColumnLock);
    auto& s=outfitColumns;
    if (s.active && (manager!=s.manager || *reinterpret_cast<void**>(0x7ccffc)!=s.world ||
        *reinterpret_cast<int*>(0x788bb0)!=4 || !at<BYTE>(s.root,4))) s.active=false;
    if (s.active) {
        for (unsigned i=0;i<s.count && !write;++i) if (s.ready[i]==1) {s.ready[i]=2;row=s.rows[i];boats=s.boats[i];write=true;}
        if (s.pending && GetTickCount64()-s.sentAt>=5000) s.pending=0;
        if (!s.pending && s.next<s.count) {
            s.pending=s.ids[s.next++];s.sentAt=GetTickCount64();world=s.world;
            selector=0x10;query[0]=s.pending; // base 0, mode 0: friendly public party (032e).
        }
    }
    ReleaseSRWLockExclusive(&outfitColumnLock);
    if (write) {wchar_t text[16];_snwprintf_s(text,_countof(text),_TRUNCATE,L"%u",boats);outfitColumnCell(row,3,text);}
    if (selector) reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(world,0,selector,query);
}
static bool installOutfitListColumns() {
    const BYTE expected[5]={0xe8,0xa7,2,0,0};
    auto site=reinterpret_cast<BYTE*>(0x54bc94);
    if (memcmp(site,expected,5)) return false;
    BYTE patch[5]={0xe8};*reinterpret_cast<LONG*>(patch+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&buildOutfitListColumns)-site-5);
    return patchBytes(site,expected,patch,5);
}
