// 7f14 national intelligence report for one base over the native 1200 record. Author: Peppone Choi.
// Wire (29 bytes, network order): character u32 @0, 7f14 u16 @4, base u16 @6, ticket u32 @8, status u8 @12
// (0 empty/request, 1 report), observedAt game seconds u32 @13, treasury u64 @17, approval u8 @25, peace u8 @26,
// constant 1 u16 @27. The native 1200 codec (0055b3a0 / 004b78a0 selector 0x46) moves the same bytes through its
// own fields, each swapped whole: u32 @0, u16 @4, u16 @6, u16 @8, u16 @10, u32 @12, u8 @16, u32 @20, u16 @24,
// u32 @28, u16 @32 (36-byte decoded record). The wire fields above are packed across those native fields.
#pragma once
#include <cstring>

namespace logh {
constexpr unsigned short homeReportSearch=0x7f14;
// The server reads character and ticket as positive signed 32-bit values.
constexpr unsigned homeReportIdLimit=0x7fffffffu;
struct HomeReport {
    unsigned character,base,ticket,status,observedAt,approval,peace;
    unsigned long long treasury;
};
namespace home_report_detail {
template<class T> inline T get(const unsigned char* record,unsigned offset) {T value;memcpy(&value,record+offset,sizeof(value));return value;}
template<class T> inline void put(unsigned char* record,unsigned offset,T value) {memcpy(record+offset,&value,sizeof(value));}
}
/** Request: every report field is zero and the trailing constant is 1. */
inline bool homeReportQuery(unsigned char (&record)[36],unsigned character,unsigned base,unsigned ticket) {
    using namespace home_report_detail;
    memset(record,0,sizeof(record));
    if(!character || character>homeReportIdLimit || !base || base>65535 || !ticket || ticket>homeReportIdLimit)return false;
    put<unsigned>(record,0,character);put<unsigned short>(record,4,homeReportSearch);
    put<unsigned short>(record,6,static_cast<unsigned short>(base));
    put<unsigned short>(record,8,static_cast<unsigned short>(ticket>>16));
    put<unsigned short>(record,10,static_cast<unsigned short>(ticket&0xffff));
    put<unsigned short>(record,32,1);
    return true;
}
inline bool isHomeReport(const unsigned char* record) {
    return home_report_detail::get<unsigned short>(record,4)==homeReportSearch;
}
/** Shape checks only. Which report a viewer may read is decided by the server. */
inline bool readHomeReport(const unsigned char* record,HomeReport& out) {
    using namespace home_report_detail;
    out={};
    if(!isHomeReport(record) || get<unsigned short>(record,32)!=1)return false;
    const unsigned word12=get<unsigned>(record,12),byte16=record[16],word20=get<unsigned>(record,20),
        short24=get<unsigned short>(record,24),word28=get<unsigned>(record,28);
    HomeReport value={};
    value.character=get<unsigned>(record,0);value.base=get<unsigned short>(record,6);
    value.ticket=(static_cast<unsigned>(get<unsigned short>(record,8))<<16) | get<unsigned short>(record,10);
    value.status=word12>>24;
    value.observedAt=((word12&0xffffffu)<<8) | byte16;
    value.treasury=(static_cast<unsigned long long>(word20)<<32) | (static_cast<unsigned long long>(short24)<<16) | (word28>>16);
    value.approval=(word28>>8)&255;value.peace=word28&255;
    if(!value.character || value.character>homeReportIdLimit || !value.base ||
        !value.ticket || value.ticket>homeReportIdLimit || value.status>1)return false;
    if(!value.status && (value.observedAt || value.treasury || value.approval || value.peace))return false;
    if(value.status && (value.approval>100 || value.peace>100 || value.treasury>0x7fffffffffffffffULL))return false;
    out=value;return true;
}
}
