// UTF-8 backing text for the ANSI CString in the same-spot roster only.
// The shared list-cell boundary removes the private marker. Author: 최병호.
#pragma once
#include "input_text.h"
#include <cstring>

namespace logh {
static constexpr char rosterTextMarker[]="LOGH7_SPOT_ROW:";
inline bool markedRosterText(const char* text) {
    return text && !strncmp(text,rosterTextMarker,sizeof(rosterTextMarker)-1);
}
inline std::string storedRosterText(const std::wstring& text) {
    int bytes=WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,text.c_str(),-1,nullptr,0,nullptr,nullptr);
    if (!bytes) return {};
    std::string encoded(static_cast<size_t>(bytes),'\0');
    if (WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,text.c_str(),-1,&encoded[0],bytes,nullptr,nullptr)!=bytes) return {};
    encoded.resize(static_cast<size_t>(bytes-1));
    return std::string(rosterTextMarker)+encoded;
}
inline bool rosterText(const char* text,std::wstring& decoded) {
    return markedRosterText(text) && inputFragment(text+sizeof(rosterTextMarker)-1,decoded,CP_UTF8);
}
}
