// Match native ANSI edit buffers to their Windows codepage. Author: 최병호.
#pragma once
#include <windows.h>
#include <string>

namespace logh {
inline bool inputFragment(const char* text, std::wstring& result, UINT codepage = CP_ACP) {
    if (!text) return false;
    int length = MultiByteToWideChar(codepage, MB_ERR_INVALID_CHARS, text, -1, nullptr, 0);
    if (!length) return false;
    std::wstring value(static_cast<size_t>(length), L'\0');
    if (MultiByteToWideChar(codepage, MB_ERR_INVALID_CHARS, text, -1, &value[0], length) != length) return false;
    value.resize(static_cast<size_t>(length - 1));
    result.swap(value);
    return true;
}
inline int inputPrintableByte(unsigned value) {
    char byte = static_cast<char>(value);
    if (IsDBCSLeadByteEx(CP_ACP, static_cast<BYTE>(value))) return 0;
    wchar_t character = 0;
    if (MultiByteToWideChar(CP_ACP, MB_ERR_INVALID_CHARS, &byte, 1, &character, 1) != 1) return 0;
    WORD type = 0;
    return GetStringTypeW(CT_CTYPE1, &character, 1, &type) &&
        (type & (C1_UPPER | C1_LOWER | C1_DIGIT | C1_PUNCT | C1_ALPHA)) ? 1 : 0;
}
}
