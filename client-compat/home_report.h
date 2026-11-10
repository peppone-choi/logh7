// Stored national intelligence report on the base selected in the planet window (7f14, home_report_data.h).
// Shown only after an explicit click, separately from the public 031f details. The client sends only its
// character, the base and a fresh ticket; the server decides whether a stored report may be read.
// 7f14 is sent only after the authenticated own-country mode 11 record of this world/character carried the 0x40
// support bit (homeReportCapability). Older servers close the connection on an unknown 1200 search value, so an
// unsupported or not yet confirmed server never sees 7f14; the button is disabled and the view shows unavailable.
// Author: Peppone Choi.
#pragma once
#include "home_report_data.h"
static SRWLOCK homeReportLock=SRWLOCK_INIT;
enum HomeReportPhase : unsigned {homeReportIdle,homeReportWaiting,homeReportReady,homeReportTimedOut};
static struct HomeReportInbox {
    void* world;unsigned character,base,ticket,phase,sequence;
    ULONGLONG sentAt;
    logh::HomeReport report;
} homeReportInbox={};
// Never reset in this process: a reply to an earlier window, base or session cannot match a later ticket.
static unsigned homeReportTickets=0;
static unsigned homeReportSeen=0;
static bool planetReportView=false;
static constexpr ULONGLONG homeReportReplyLimit=5000;
// Last capability shown by the planet window (-2: none yet), so a mode 11 change re-renders the button.
static int homeReportGateSeen=-2;
static bool homeReportAvailable(void* world,unsigned character) {return world && homeReportCapability(world,character)==1;}

static void abandonHomeReport() {
    AcquireSRWLockExclusive(&homeReportLock);auto& q=homeReportInbox;
    const unsigned sequence=q.sequence+1;q={};q.sequence=sequence;
    ReleaseSRWLockExclusive(&homeReportLock);
}
static void requestHomeReport(void* world,unsigned character,unsigned base) {
    homeReportTickets=homeReportTickets%logh::homeReportIdLimit+1;
    BYTE record[36];
    if(!homeReportAvailable(world,character) || !logh::homeReportQuery(record,character,base,homeReportTickets)) {abandonHomeReport();return;}
    // Bind before sending so the reply can never arrive ahead of its ticket.
    AcquireSRWLockExclusive(&homeReportLock);auto& q=homeReportInbox;
    q.world=world;q.character=character;q.base=base;q.ticket=homeReportTickets;
    q.phase=homeReportWaiting;q.sentAt=GetTickCount64();q.report={};++q.sequence;
    ReleaseSRWLockExclusive(&homeReportLock);
    reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(world,0,0x46,record);
}
// Called from the 1200 reader before any other 7fxx reader. Every 7f14 record is consumed and blanked so the
// native handler never sees it; only the exact pending world/character/base/ticket is accepted.
static bool receiveHomeReport(void* record) {
    auto bytes=static_cast<BYTE*>(record);
    if(!logh::isHomeReport(bytes))return false;
    logh::HomeReport report;
    if(logh::readHomeReport(bytes,report)) {
        void* world=*reinterpret_cast<void**>(0x7ccffc);
        AcquireSRWLockExclusive(&homeReportLock);auto& q=homeReportInbox;
        if(q.phase==homeReportWaiting && q.world && q.world==world && q.ticket==report.ticket &&
            q.character==report.character && q.base==report.base) {
            q.report=report;q.phase=homeReportReady;q.ticket=0;++q.sequence;
        }
        ReleaseSRWLockExclusive(&homeReportLock);
    }
    memset(bytes+4,0,32);return true;
}
// A server without 7f14 sends nothing: the wait ends here and a late reply is dropped (ticket cleared).
static unsigned pollHomeReport() {
    const auto now=GetTickCount64();
    AcquireSRWLockExclusive(&homeReportLock);auto& q=homeReportInbox;
    if(q.phase==homeReportWaiting && now-q.sentAt>=homeReportReplyLimit) {q.phase=homeReportTimedOut;q.ticket=0;++q.sequence;}
    const unsigned sequence=q.sequence;
    ReleaseSRWLockExclusive(&homeReportLock);return sequence;
}
static unsigned homeReportSnapshot(void* world,unsigned character,unsigned base,logh::HomeReport& report) {
    report={};unsigned phase=homeReportIdle;
    AcquireSRWLockShared(&homeReportLock);const auto& q=homeReportInbox;
    if(q.world==world && q.character==character && q.base==base) {phase=q.phase;report=q.report;}
    ReleaseSRWLockShared(&homeReportLock);return phase;
}
static void restartHomeReport() {
    abandonHomeReport();
    auto& info=planetInformation;
    if(planetReportView && info.count && info.world) requestHomeReport(info.world,info.character,info.ids[info.index]);
}
static void closeHomeReport() {planetReportView=false;abandonHomeReport();}
// Per planet-window tick. True when the 7f14 capability changed since the last render: an open report view
// re-requests (now supported) or drops its pending ticket (no longer supported), and the button is re-rendered.
static bool pollHomeReportGate() {
    const auto& info=planetInformation;
    const int gate=info.world ? homeReportCapability(info.world,info.character) : -1;
    if(gate==homeReportGateSeen)return false;
    homeReportGateSeen=gate;
    if(planetReportView)restartHomeReport();
    return true;
}

static void renderHomeReport() {
    auto& info=planetInformation;
    logh::HomeReport report;
    const unsigned phase=homeReportSnapshot(info.world,info.character,info.ids[info.index],report);
    planetCell(80,L"항목");planetRect(80,392,206,140,24);
    planetCell(81,L"보고 내용");planetRect(81,536,206,280,24);
    planetCell(84,L"정보 공작 저장분 · 공개 현황과 별개");
    const int gate=info.world ? homeReportCapability(info.world,info.character) : -1;
    if(gate!=1) {
        planetCell(1,gate ? L"서버 기능 확인 중 · 첩보 보고 사용 불가" : L"이 서버는 첩보 보고를 지원하지 않음");
        planetCell(40,gate ? L"서버 기능을 확인한 뒤 조회할 수 있습니다." : L"첩보 보고를 조회할 수 없습니다.");
        planetRect(40,392,234,424,24);
        return;
    }
    if(phase==homeReportWaiting || phase==homeReportIdle) {planetCell(1,L"서버에서 첩보 보고를 조회하고 있습니다.");return;}
    if(phase==homeReportTimedOut) {
        planetCell(1,L"응답 없음 · 첩보 보고 조회 실패");
        planetCell(40,L"응답을 받지 못했습니다. 새로고침으로 다시 조회합니다.");planetRect(40,392,234,424,24);
        return;
    }
    if(!report.status) {
        planetCell(1,L"열람할 수 있는 저장 보고 없음");
        planetCell(40,L"이 거점에 대해 열람할 수 있는 첩보 보고가 없습니다.");planetRect(40,392,234,424,24);
        return;
    }
    planetCell(1,L"자국 첩보 보고 · 서버 저장분");
    wchar_t text[96];
    auto row=[&](unsigned index,const wchar_t* label,const wchar_t* value) {
        planetCell(40+index*4,label);planetRect(40+index*4,392,234+index*26,140,24);
        planetCell(41+index*4,value);planetRect(41+index*4,536,234+index*26,280,24);
    };
    auto own=at<BYTE*>(info.world,8);
    const unsigned country=own ? runtimeOwnCountry(info.world,info.character,at<BYTE>(own,0x28)) : 0;
    wchar_t cp[32],date[96];
    if(runtimeNavigationHud(report.observedAt,0,0,country,cp,date))row(0,L"관측 시각",date);
    else {
        _snwprintf_s(text,_countof(text),_TRUNCATE,L"게임 경과 %u일 %02u:%02u",
            report.observedAt/86400,report.observedAt/3600%24,report.observedAt/60%60);
        row(0,L"관측 시각",text);
    }
    const unsigned now=reinterpret_cast<unsigned(__thiscall*)(void*)>(0x4c53b0)(info.world);
    if(now>=report.observedAt) {
        const unsigned age=now-report.observedAt;
        _snwprintf_s(text,_countof(text),_TRUNCATE,L"%u시간 %02u분",age/3600,age/60%60);
    } else wcscpy_s(text,L"—");
    row(1,L"관측 후 경과",text);
    _snwprintf_s(text,_countof(text),_TRUNCATE,L"%llu",report.treasury);row(2,L"금고",text);
    _snwprintf_s(text,_countof(text),_TRUNCATE,L"%u",report.approval);row(3,L"지지율",text);
    _snwprintf_s(text,_countof(text),_TRUNCATE,L"%u",report.peace);row(4,L"치안",text);
}
