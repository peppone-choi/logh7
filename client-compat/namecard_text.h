// Display-only composition beyond the native 128-byte scratch array. Author: Peppone Choi.
#pragma once
#include "display_text.h"
namespace logh {
inline bool nameCardDisplayText(const wchar_t* name,const char* suffix,std::string& result) {
    std::string encoded;
    if (!suffix || !storeDisplayName(name,encoded)) return false;
    encoded+=suffix;
    result.swap(encoded);
    return true;
}
}
