// Tell server-driven own return-base changes from the answer to this tab's request. Author: Peppone Choi.
#pragma once

namespace logh {
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
