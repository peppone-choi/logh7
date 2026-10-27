// Preserve Unicode names in the native two-line portrait label. Author: 최병호.
#pragma once
#include <string>

namespace logh {
inline std::wstring portraitLineBreak(std::wstring text) {
    unsigned cells=0;
    for (size_t i=0;i<text.size();++i) {
        wchar_t ch=text[i];
        unsigned width=ch<128 || (ch>=0xff61 && ch<=0xff9f) ? 1u : 2u;
        if (cells+width>16) { text.insert(i,1,L'\n'); break; }
        cells+=width;
    }
    return text;
}
}
