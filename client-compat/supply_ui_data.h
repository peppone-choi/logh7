// Native supply record offsets verified against 120b and the selected variable. Author: Peppone Choi.
#pragma once
#include <string>
struct SupplyQuoteRow {unsigned source,target,amount,payment,status;};
static SRWLOCK supplyQuoteLock=SRWLOCK_INIT;
static struct SupplyQuoteCache {
    void* world;
    unsigned character,nonce,count;
    bool enabled;
    SupplyQuoteRow rows[300],confirmed;
} supplyQuoteCache={};

static bool receiveSupplyQuote(void* world,const BYTE* record) {
    if(logh::infoShort(record,4)!=0x7f00 || logh::infoShort(record,6)!=62)return false;
    SupplyQuoteRow row={logh::infoWord(record,12),logh::infoWord(record,20),logh::infoWord(record,28),
        record[16],logh::infoShort(record,10)};
    AcquireSRWLockExclusive(&supplyQuoteLock);
    auto& cache=supplyQuoteCache;
    if(cache.enabled && cache.world==world && logh::infoWord(record,0)==cache.character && logh::infoShort(record,8)==cache.nonce &&
        row.source && row.target && row.payment<=2 && row.status<=3 && cache.count<_countof(cache.rows))
        cache.rows[cache.count++]=row;
    ReleaseSRWLockExclusive(&supplyQuoteLock);
    return true;
}

static bool supplyQuotedTarget(unsigned target,SupplyQuoteRow& quote) {
    auto world=*reinterpret_cast<BYTE**>(0x7ccffc);
    auto own=world ? at<BYTE*>(world,8) : nullptr;
    AcquireSRWLockShared(&supplyQuoteLock);
    const auto& cache=supplyQuoteCache;
    const bool enabled=cache.enabled && cache.world==world && own && cache.character==at<unsigned>(own,0x24);
    if(enabled)for(unsigned i=0;i<cache.count;++i)if(cache.rows[i].target==target) {quote=cache.rows[i];break;}
    ReleaseSRWLockShared(&supplyQuoteLock);
    return enabled;
}

static void rememberSupplyConfirmation(const SupplyQuoteRow& quote) {
    AcquireSRWLockExclusive(&supplyQuoteLock);
    supplyQuoteCache.confirmed=quote;
    ReleaseSRWLockExclusive(&supplyQuoteLock);
}

static const wchar_t* supplyQuotePrice(const SupplyQuoteRow& quote) {
    return !quote.target ? L"견적 미수신 · 목록을 다시 여세요" :
        quote.status==1 ? L"CP 부족 · MCP 160 / PCP 320 필요" :
        quote.status==2 ? L"이동 또는 작업 진행 중" :
        quote.status==3 ? L"부대 편성이나 적재 상태 확인" :
        quote.payment==1 ? L"MCP 160" : quote.payment==2 ? L"대체 PCP 320" : L"결제 없음";
}

static bool supplyCommandContext() {
    auto command=*reinterpret_cast<BYTE**>(0xc9e2f8);
    unsigned authority=*reinterpret_cast<unsigned*>(0xc9eac0);
    auto world=*reinterpret_cast<BYTE**>(0x7ccffc);
    auto own=world ? at<BYTE*>(world,8) : nullptr;
    return command && own && own[0] && at<unsigned>(command,0x28)==62 &&
        (authority==44 || authority==180) && at<void*>(command,0x1c)==own;
}
static std::wstring supplyOutfitName(const BYTE* record) {
    if (!record || !logh::infoWord(record,8) || record[13]>3) return L"";
    const wchar_t* kinds[]={L"함대",L"수송부대",L"순찰대",L"지상부대"};
    return std::wstring(L"제")+std::to_wstring(record[12])+kinds[record[13]];
}
static const BYTE* selectedSupplyOutfit() {
    if (!supplyCommandContext()) return nullptr;
    auto command=*reinterpret_cast<BYTE**>(0xc9e2f8);
    auto steps=at<BYTE**>(command,0x10), end=at<BYTE**>(command,0x14);
    unsigned active=at<unsigned>(command,4);
    if (!steps || end<steps || end-steps>64 || active>=static_cast<unsigned>(end-steps)) return nullptr;
    if (!steps[active] || at<unsigned>(steps[active],0)!=0x675780 || at<unsigned>(steps[active],0x48)!=62) return nullptr;
    for (unsigned i=0;i<active;++i) {
        auto step=steps[i];
        if (!step || !at<BYTE>(step,0x18)) continue;
        auto vars=at<BYTE**>(step,0xc), varsEnd=at<BYTE**>(step,0x10);
        if (!vars || varsEnd<vars || varsEnd-vars>16) return nullptr;
        for (auto var=vars;var!=varsEnd;++var) {
            auto name=*var ? at<const char*>(*var,8) : nullptr;
            if (!name || strcmp(name,"TARGET_SELECT_S_SUPPLY_OUTFIT") || at<unsigned>(*var,0x14)!=1) continue;
            auto head=at<BYTE*>(*var,0x10);
            auto node=head ? at<BYTE*>(head,0) : nullptr;
            if (!node || node==head) return nullptr;
            return at<const BYTE*>(node,20);
        }
    }
    return nullptr;
}
