// Native mail date prefix fits in 16 bytes before its numeric buffer. Author: Peppone Choi.
#pragma once
#include "unicode_text.h"

namespace logh {
inline unsigned mailCalendarKind(uintptr_t caller,unsigned group,unsigned index) {
    if(group!=0x16)return 2;
    if(caller==0x53cc65 && index==0x56)return 0;
    if(caller==0x53cc9d && index==0x57)return 1;
    return 2;
}
inline unsigned mailFooterKind(uintptr_t caller,unsigned group,unsigned index) {
    if(group!=0x16 || index!=4)return 3;
    switch(caller) {
    case 0x537eb7:case 0x53c2d9:return 0; // List footer, factory and completion reset.
    case 0x537f53:return 1; // Compose footer beside Send/Cancel.
    case 0x52a0ff:case 0x52d30e:case 0x52d580:case 0x52d8ee:return 2; // Shared character picker.
    default:return 3;
    }
}
inline bool mailCalendarText(bool imperial,std::string& result) {
    // CD calls 56/57 but the supplied data stores these at 55/56.
    // Reuse the verified compact private CP932 catalogue, not the longer
    // display-name escape which exceeds the native 16-byte prefix slot.
    const auto row=koreanResourceEntry((22u<<16)|(imperial?0x55u:0x56u));
    std::wstring decoded;
    if(!row || strlen(row->text)>=16 || !japaneseFragment(row->text,decoded) ||
        decoded!=(imperial?L"제국력":L"우주력"))return false;
    result=row->text;
    return true;
}
}
