// Natural Korean spot labels. Author: 최병호.
#pragma once
#include <string>
namespace logh {
inline std::wstring spotName(const std::wstring& name,unsigned rank) {
    // Original constmsg group 5: emperor, politician, then military ranks.
    static const wchar_t* const ranks[]={L"황제",L"정치가",L"원수",L"상급대장",
        L"대장",L"중장",L"소장",L"준장",L"대령",L"중령",L"소령",L"대위",
        L"중위",L"소위",L"준위",L"상사",L"중사",L"하사",L"상등병",L"일등병",L"이등병"};
    if (rank>=sizeof(ranks)/sizeof(ranks[0])) return name;
    if (name.empty()) return ranks[rank];
    return rank<=1 ? std::wstring(ranks[rank])+L" "+name : name+ranks[rank];
}
}
