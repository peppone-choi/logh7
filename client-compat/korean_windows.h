// Only the game's RT_STRING on an ACP 949 host. Author: 최병호.
#pragma once
#include "unicode_text.h"

namespace logh {
inline int copyKoreanWindowsString(unsigned id, char* buffer, int capacity, unsigned codepage) {
    if (id>65535 || !buffer || capacity<1 || codepage!=949) return -1;
    const auto row=koreanResourceEntry((static_cast<uint64_t>(65533)<<32)|id);
    if (!row) return -1;
    std::wstring wide;
    if (!japaneseFragment(row->text,wide)) return -1;
    BOOL usedDefault=FALSE;
    const int count=WideCharToMultiByte(codepage,WC_NO_BEST_FIT_CHARS,wide.data(),static_cast<int>(wide.size()),
                                      nullptr,0,nullptr,&usedDefault);
    if (count<=0 || usedDefault) return -1;
    std::string encoded(static_cast<size_t>(count),'\0');
    if (WideCharToMultiByte(codepage,WC_NO_BEST_FIT_CHARS,wide.data(),static_cast<int>(wide.size()),
                           &encoded[0],count,nullptr,&usedDefault)!=count || usedDefault) return -1;
    // LoadStringA's capacity includes NUL. Never leave a DBCS lead byte at the
    // truncation boundary even when the caller supplies an odd byte capacity.
    size_t copied=0;
    while (copied<encoded.size()) {
        const size_t width=IsDBCSLeadByteEx(codepage,static_cast<BYTE>(encoded[copied]))?2:1;
        if (copied+width>encoded.size() || copied+width>=static_cast<size_t>(capacity)) break;
        copied+=width;
    }
    memcpy(buffer,encoded.data(),copied);buffer[copied]=0;
    return static_cast<int>(copied);
}
}
