// Native selector card identity. Author: Peppone Choi.
#pragma once
#include <cmath>
namespace logh {
struct WarpRouteFare { unsigned lightYears=0, mcp=0, waitHours=0; };

inline WarpRouteFare normalWarpFare(unsigned origin, unsigned target) {
    if (origin>=5000 || target>=5000 || origin==target) return {};
    const auto dx=static_cast<int>(target%100)-static_cast<int>(origin%100);
    const auto dy=static_cast<int>(target/100)-static_cast<int>(origin/100);
    // Production NavigationPolicy and WarpCourse charge by started 100 ly.
    const auto lightYears=static_cast<unsigned>(std::ceil(std::sqrt(10000.0*(dx*dx+dy*dy))));
    const auto intervals=(lightYears+99)/100;
    return {lightYears,40*(intervals<4 ? intervals : 4),intervals-1};
}

// Mirrors the production NavigationPolicy and the fuel interpolation in Navigation.kt.
inline unsigned fuelServiceHours(unsigned cruise) {
    if (cruise>=1000) return 0;
    const unsigned hours=((1000-cruise)*960+999)/1000;
    return hours<48 ? 48 : hours;
}

inline bool appointmentAuthorityCard(unsigned card) {
    return card>=1 && card<=58 || card>=68 && card<=122 ||
        card>=132 && card<=194 || card>=197 && card<=256;
}
// Regular national offices and the four capital offices omit the native base
// selector. Local and rebel seats must come from an explicit selection.
inline unsigned appointmentCapitalPower(unsigned card) {
    if(card>=1 && card<=38 || card==56 || card==57 || card==58)return 2;
    if(card>=132 && card<=174 || card>=192 && card<=194)return 3;
    return 0;
}
inline unsigned appointmentInformationCard(unsigned mode, unsigned rowValue, unsigned requestedCard) {
    unsigned card = mode == 12 ? rowValue : (mode == 13 || mode == 14 ? requestedCard : 0);
    return card > 0 && card <= 260 ? card : 0;
}
// Display preview of the server's concurrent-office rule: from four offices, 5% per office, capped at 20%.
// It applies only to the political/operations effective values used for CP recovery. It does not
// grant or remove authority, and the server remains the authority for the actual values.
inline unsigned concurrentOfficePenaltyPercent(unsigned offices) {
    if (offices < 4) return 0;
    if (offices >= 7) return 20;
    return (offices - 3) * 5;
}
// Expected-office marker 0x4c00|count at the person record's second u16 (+0x22, same carrier as
// the rank at +0x20 in 0x57a1f0). Only 0x4c00..0x4c0f is accepted; anything else keeps the general rule.
inline bool concurrentOfficeMarker(unsigned short value, unsigned& offices) {
    if ((value & 0xff00) != 0x4c00 || (value & 0xff) > 15) return false;
    offices = value & 0xff;
    return true;
}
}
