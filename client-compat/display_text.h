// Lossless names inside display-only native ANSI CStrings. Author: 최병호.
// These escaped segments never replace input, network or saved strings.
#pragma once
#include <string>
#include <cstring>
#include <cwchar>

namespace logh {
static constexpr unsigned displayNameUnits=28;
static constexpr char displayNamePrefix[]="\x1eL7U:";
static constexpr wchar_t wideDisplayNamePrefix[]=L"\x1eL7U:";
inline bool validDisplayUnits(const std::wstring& text) {
    for (size_t i=0;i<text.size();++i) {
        unsigned ch=static_cast<unsigned>(text[i]);
        if (!ch || ch>0xffff || (ch>=0xdc00 && ch<=0xdfff)) return false;
        if (ch>=0xd800 && ch<=0xdbff) {
            if (++i==text.size() || text[i]<0xdc00 || text[i]>0xdfff) return false;
        }
    }
    return true;
}
inline bool storeDisplayName(const wchar_t* text,std::string& result,unsigned capacity=displayNameUnits) {
    if (!text || !capacity || capacity>displayNameUnits) return false;
    std::wstring original;
    unsigned i=0;
    for (;i<capacity && text[i];++i) original+=text[i];
    // The supported native name fields include their NUL in this bound.
    // Leave longer/unavailable fields to the original conversion.
    if (i==capacity || !validDisplayUnits(original)) return false;
    static constexpr char digits[]="0123456789ABCDEF";
    std::string stored(displayNamePrefix);
    for (wchar_t ch:original) {
        unsigned unit=static_cast<unsigned>(ch);
        for (int shift=12;shift>=0;shift-=4) stored+=digits[(unit>>shift)&15];
    }
    stored+='\x1f';
    result.swap(stored);
    return true;
}
inline int displayHex(wchar_t ch) {
    if (ch>=L'0' && ch<=L'9') return ch-L'0';
    if (ch>=L'A' && ch<=L'F') return ch-L'A'+10;
    return -1;
}
inline bool restoreDisplayNames(std::wstring& text) {
    std::wstring result;
    bool changed=false;
    const size_t prefix=sizeof(wideDisplayNamePrefix)/sizeof(wchar_t)-1;
    for (size_t i=0;i<text.size();) {
        if (text.compare(i,prefix,wideDisplayNamePrefix)) {result+=text[i++];continue;}
        changed=true;i+=prefix;
        std::wstring name;
        while (i<text.size() && text[i]!=L'\x1f') {
            if (name.size()>=displayNameUnits-1 || text.size()-i<4) return false;
            unsigned unit=0;
            for (unsigned digit=0;digit<4;++digit) {
                int value=displayHex(text[i++]);
                if (value<0) return false;
                unit=(unit<<4)|static_cast<unsigned>(value);
            }
            name+=static_cast<wchar_t>(unit);
        }
        if (i==text.size() || !validDisplayUnits(name)) return false;
        ++i;result+=name;
    }
    if (changed) text.swap(result);
    return changed;
}
inline bool restoreDisplayNames(wchar_t* text) {
    if (!text) return false;
    std::wstring decoded(text);
    if (!restoreDisplayNames(decoded)) return false;
    // Every escaped segment shrinks; keep the original allocator and lifetime.
    memcpy(text,decoded.c_str(),(decoded.size()+1)*sizeof(wchar_t));
    return true;
}
}
