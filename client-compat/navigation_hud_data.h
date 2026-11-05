// Native 360-day calendar and CP labels. Author: Peppone Choi (peppone-choi).
#pragma once
#include <cwchar>
#include "country_catalog_data.h"

namespace logh {
inline bool formatNavigationHud(unsigned seconds,unsigned pcp,unsigned mcp,const CountryCalendar& calendar,wchar_t (&cp)[32],wchar_t (&date)[96]) {
    if(pcp>1000 || mcp>1000)return false;
    unsigned minutes=seconds/60,hours=minutes/60,days=hours/24;
    swprintf_s(cp,L"%-4u             %-4u",pcp,mcp);
    unsigned year=795+days/360,month=days/30%12+1,day=days%30+1;
    int calendarYear=static_cast<int>(year)+calendar.yearOffset;
    if(calendarYear<1) {
        swprintf_s(date,L"%ls 개원전 %d년 %u/%u %02u:%02u",calendar.name,1-calendarYear,month,day,hours%24,minutes%60);
        return true;
    }
    swprintf_s(date,L"%ls %d년 %2u월 %2u일 %2u시 %2u분",calendar.name,calendarYear,month,day,hours%24,minutes%60);
    return true;
}
inline bool navigationHudText(unsigned seconds,unsigned pcp,unsigned mcp,unsigned country,wchar_t (&cp)[32],wchar_t (&date)[96]) {
    const auto calendar=countryCalendar(country);
    return calendar && formatNavigationHud(seconds,pcp,mcp,*calendar,cp,date);
}
}
