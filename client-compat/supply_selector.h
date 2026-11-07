// Supply is the first selection, so TARGET_SELECT_S_BASE has no preceding value.
// Keep the native filter vector and resolve its grid from the current unit. Author: Peppone Choi.
#pragma once
static void __fastcall supplySelectFilter(void* step, void*, unsigned kind, const char* variable) {
    auto command=*reinterpret_cast<BYTE**>(0xc9e2f8);
    unsigned authority=*reinterpret_cast<unsigned*>(0xc9eac0);
    if (step && command && at<unsigned>(command,0x28)==62 &&
        (authority==44 || authority==180) && at<unsigned>(step,0x28)==7 &&
        kind==3 && variable && !strcmp(variable,"TARGET_SELECT_S_BASE")) {
        auto world=*reinterpret_cast<BYTE**>(0x7ccffc);
        auto own=world ? at<BYTE*>(world,8) : nullptr;
        if (own && own[0] && at<void*>(command,0x1c)==own) variable=nullptr;
    }
    reinterpret_cast<void(__thiscall*)(void*,unsigned,const char*)>(0x5713b0)(step,kind,variable);
}
using SupplyDispatch=unsigned(__thiscall*)(void*,char,unsigned,const void*);

static unsigned __fastcall supplySearch(void* world,void*,char pending,unsigned selector,const BYTE* record) {
    auto dispatch=reinterpret_cast<SupplyDispatch>(0x4b78a0);
    if(logh::infoShort(record,4)!=14 || !supplyCommandContext())return dispatch(world,pending,selector,record);
    const auto character=logh::infoWord(record,0);
    const bool enabled=supplyQuoteCapability(world,character);
    AcquireSRWLockExclusive(&supplyQuoteLock);
    auto& cache=supplyQuoteCache;
    cache.world=world;cache.character=character;cache.enabled=enabled;cache.count=0;cache.confirmed={};
    const auto nonce=cache.nonce=cache.nonce%65535+1;
    ReleaseSRWLockExclusive(&supplyQuoteLock);
    if(!enabled)return dispatch(world,pending,selector,record);
    // Search mode 14 reserves these two words. The server returns an ordinary search echo.
    BYTE query[36];memcpy(query,record,sizeof(query));
    at<unsigned short>(query,8)=0x5351;
    at<unsigned short>(query,10)=static_cast<unsigned short>(nonce);
    return dispatch(world,pending,selector,query);
}

static unsigned __fastcall supplyCommit(void* world,void*,char pending,unsigned selector,BYTE* record) {
    auto dispatch=reinterpret_cast<SupplyDispatch>(0x4b78a0);
    AcquireSRWLockExclusive(&supplyQuoteLock);
    auto& cache=supplyQuoteCache;
    const bool quoted=cache.enabled && cache.world==world && cache.character==at<unsigned>(record,4);
    const auto quote=cache.confirmed;cache.confirmed={};
    ReleaseSRWLockExclusive(&supplyQuoteLock);
    if(quoted) {
        if(!quote.source || quote.target!=at<unsigned>(record,20) || !quote.amount || !quote.payment || quote.status)return 0;
        // Use the original result slots as a quote guard, never as the resulting balances.
        at<unsigned>(record,8)=0x53510000|quote.payment;
        at<unsigned>(record,16)=quote.source;
        at<unsigned>(record,24)=quote.amount;
    }
    return dispatch(world,pending,selector,record);
}

static bool installSupplySelector() {
    DWORD expected=0x5713b0;
    DWORD replacement=reinterpret_cast<DWORD>(&supplySelectFilter);
    auto search=reinterpret_cast<BYTE*>(0x4b587d),commit=reinterpret_cast<BYTE*>(0x4b4ded);
    const BYTE expectedSearch[]={0xe8,0x1e,0x20,0,0},expectedCommit[]={0xe8,0xae,0x2a,0,0};
    BYTE searchCall[5]={0xe8},commitCall[5]={0xe8};
    at<LONG>(searchCall,1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&supplySearch)-search-5);
    at<LONG>(commitCall,1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&supplyCommit)-commit-5);
    return patchBytes(reinterpret_cast<void*>(0x67576c),&expected,&replacement,sizeof(expected)) &&
        patchBytes(search,expectedSearch,searchCall,sizeof(searchCall)) &&
        patchBytes(commit,expectedCommit,commitCall,sizeof(commitCall));
}
