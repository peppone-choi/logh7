// Native selector card identity. Author: 최병호.
#pragma once
namespace logh {
inline unsigned appointmentInformationCard(unsigned mode, unsigned rowValue, unsigned requestedCard) {
    unsigned card = mode == 12 ? rowValue : (mode == 13 || mode == 14 ? requestedCard : 0);
    return card > 0 && card <= 260 ? card : 0;
}
}
