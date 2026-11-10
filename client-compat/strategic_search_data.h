// Strategic search clear-report matcher and 0b03 capability words. Author: Peppone Choi.
#pragma once
#include <windows.h>
#include <cstring>
#include "tactical_detection_data.h"
namespace logh {
// Server marker words, shared with server StrategicSearchMessages (CLEAR_CLIENT / CLEAR_REPORT).
constexpr unsigned strategicSearchClientWord=0x4c374753,strategicSearchClearWord=0x434c5231;
// 0044c960 decodes 0b0d as time +0, wait +4, character +8, row count u8 +0xc, then rows:
// grid u16 +0xe, group count u8 +0x10, first group power +0x12, camp +0x13, ships u16 +0x14.
constexpr unsigned strategicReportWait=4,strategicReportRows=0xc,strategicReportGroups=0x10,strategicReportShips=0x14;
// 004bfe1a inside the 004bfd30 importer: jbe 004bff84 (no rows -> native redraw). The patch covers only
// these 6 bytes; 004bfe20 onward stays intact because 004bff7e's jl 004bfe22 re-enters the row loop there.
constexpr BYTE strategicSearchSeamBytes[6]={0x0f,0x86,0x64,0x01,0x00,0x00};
constexpr unsigned strategicSearchSeam=0x4bfe1a,strategicSearchSeamContinue=0x4bfe20,strategicSearchRedraw=0x4bff84;
constexpr unsigned strategicSearchLoopTarget=0x4bfe22;
// 004b49a0 is the CommandSearch sender; it reaches 004b78a0 with selector 0x51 (0b03) before fuel's 004b49d0.
constexpr unsigned strategicSearchSender=0x4b49a0,strategicSearchSenderSize=0x30,strategicSearchSelector=0x51;

// Only the explicit server clear (one row, one group, zero ships, marked wait) skips the native group
// insert, which would otherwise turn 0 ships into a fake 1-ship marker. Every other report stays native.
inline bool strategicSearchClearReport(const BYTE* report) {
    if(!report || tacticalWord(report+strategicReportWait)!=strategicSearchClearWord)return false;
    unsigned short ships;memcpy(&ships,report+strategicReportShips,2);
    return report[strategicReportRows]==1 && report[strategicReportGroups]==1 && !ships;
}
inline bool strategicSearchSeamIntact(const BYTE* code) {
    return code && !memcmp(code,strategicSearchSeamBytes,sizeof(strategicSearchSeamBytes));
}
// Target of the guarded jbe rel32 when it sits at strategicSearchSeam.
inline unsigned strategicSearchSeamTarget(const BYTE* code) {
    LONG rel;memcpy(&rel,code+2,4);
    return strategicSearchSeam+static_cast<unsigned>(sizeof(strategicSearchSeamBytes))+static_cast<unsigned>(rel);
}
// The sender's single call to the serializer, preceded in the same function by push 0x51.
inline BYTE* strategicSearchSendCall(BYTE* start,unsigned size,const void* serializer) {
    BYTE* site=nullptr;
    if(findTacticalCalls(start,size,serializer,&site,1)!=1)return nullptr;
    for(BYTE* p=start;p+2<=site;++p)if(p[0]==0x6a && p[1]==strategicSearchSelector)return site;
    return nullptr;
}
// Fill only the request time/wait words; the character ID at +8 and the CP words stay native.
inline bool advertiseStrategicSearchClear(unsigned selector,BYTE* record) {
    if(selector!=strategicSearchSelector || !record)return false;
    memcpy(record,&strategicSearchClientWord,4);
    memcpy(record+4,&strategicSearchClearWord,4);
    return true;
}
}
