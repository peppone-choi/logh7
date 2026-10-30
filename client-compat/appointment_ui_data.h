// Native selector card identity. Author: 최병호.
#pragma once
namespace logh {
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
}
