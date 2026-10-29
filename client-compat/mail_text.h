// Native mail/editor boundaries; no packet construction. Author: 최병호.
#pragma once
#include "input_text.h"

namespace logh {
constexpr size_t mailTitleUnits=128, mailBodyUnits=512; // Includes the NUL.
inline bool mailWideText(const wchar_t* text,size_t capacity,std::wstring& result) {
    if (!text || !capacity) return false;
    size_t length=0;
    while (length<capacity && text[length]) ++length;
    if (length==capacity) return false;
    for (size_t i=0;i<length;++i) {
        unsigned ch=static_cast<unsigned short>(text[i]);
        if (ch>=0xd800 && ch<=0xdbff) {
            if (++i==length || text[i]<0xdc00 || text[i]>0xdfff) return false;
        } else if (ch>=0xdc00 && ch<=0xdfff) return false;
    }
    std::wstring value(text,length);result.swap(value);return true;
}
inline bool mailInputText(const char* text,size_t capacity,std::wstring& result,UINT codepage=CP_ACP) {
    if (!text || !capacity || capacity>mailBodyUnits) return false;
    // Every supported ANSI character occupies at most two bytes. Bound reads
    // before MultiByteToWideChar's NUL-terminated interface is used.
    size_t bytes=0;
    while (bytes<capacity*2 && text[bytes]) ++bytes;
    if (bytes==capacity*2) return false;
    std::wstring value,checked;
    if (!inputFragment(text,value,codepage) || value.size()>=capacity ||
        !mailWideText(value.c_str(),capacity,checked)) return false;
    result.swap(checked);return true;
}
inline bool mailReplyText(const wchar_t* text,bool title,bool reply,std::wstring& result) {
    std::wstring source;
    if (!mailWideText(text,title?mailTitleUnits:mailBodyUnits,source)) return false;
    std::wstring value;
    if (reply && title) value=L"Re:";
    if (reply && !title && !source.empty()) value+=L'>';
    for (size_t i=0;i<source.size();++i) {
        value+=source[i];
        if (reply && !title && source[i]==L'\n' && i+1<source.size()) value+=L'>';
    }
    // The original editor setter applies its own configured UTF-16 limit.
    // Do not expand a 1,024-byte ANSI stack array with per-line quote marks.
    result.swap(value);return true;
}
}
