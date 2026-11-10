// Tell server-driven own return-base changes from the answer to this tab's request. Author: Peppone Choi.
#pragma once
#include "base_affiliation_data.h"

namespace logh {
// Navigation.returnBaseRejection: a habitable base owned by the player's own country in the
// player's camp. Only established facts refuse a base here; the server still decides the rest.
// owner: the 032f mode-12 row, null until received (older servers never send it).
// ownCountry: the stable mode-11 country ID (0 = no country), null until received. The native
// power byte is a display index shared by added countries, so it never stands in for this ID.
// habitable: 031f +0x175 when received; only 0 is an established refusal. The player's own camp
// has no client model, so the base camp is left to the server.
inline bool returnBaseCandidate(const BaseAffiliation::Owner* owner,const unsigned* ownCountry,int habitable) {
    if(habitable==0)return false;
    if(!owner || !ownCountry)return true;
    // A countryless player may own an independent base; the row does not name its holder.
    return *ownCountry ? owner->country==*ownCountry : !owner->country;
}
// Next/previous in catalogue order from the current choice, skipping refused bases. A choice that
// became refused still starts from its own slot, so a captured base is never revisited.
inline unsigned returnBaseStep(const unsigned* ids,const bool* allowed,unsigned count,unsigned selected,bool forward) {
    unsigned start=count;
    for(unsigned i=0;i<count;++i)if(ids[i]==selected) {start=i;break;}
    for(unsigned n=1;n<=count;++n) {
        const unsigned i=start==count ? (forward ? n-1 : count-n) : forward ? (start+n)%count : (start+count-n)%count;
        if(allowed[i])return ids[i];
    }
    return 0;
}
struct ReturnBaseRefresh {
    bool echo,due;
    unsigned previous;
    // The server answers 0f1a before its 0356; only an accepted change produces an own-value echo.
    void replied(bool accepted,bool changed) {echo=accepted && changed;}
    // While a request is in flight its reply and echo decide the final selection.
    void updated(unsigned before,unsigned after,bool pending,bool shown) {
        if(before==after || pending)return;
        const bool answered=echo;
        echo=false;
        if(!answered && shown) {due=true;previous=before;}
    }
    bool take(unsigned& excluded) {
        if(!due)return false;
        due=false;excluded=previous;
        return true;
    }
};
}
