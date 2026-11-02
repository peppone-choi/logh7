// Decode the native IME status CString at its ACP boundary. Author: Peppone Choi.
#pragma once
#include "input_text.h"

namespace logh {
inline bool imeStatusFragment(const char* text,std::wstring& result,LANGID keyboard,UINT codepage=CP_ACP) {
    std::wstring value;
    if(!inputFragment(text,value,codepage))return false;
    // The native IME helper assigns these wide literals through MFC's ACP.
    // Its last assignment is a mode symbol, not an input-buffer fragment.
    if(value==L"英")value=L"A";
    else if(PRIMARYLANGID(keyboard)==LANG_KOREAN && (value==L"あ" || value==L"ア"))value=L"한";
    result.swap(value);
    return true;
}
}
