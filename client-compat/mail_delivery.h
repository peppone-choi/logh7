// One outstanding personal mail; the native editor remains the draft. Author: 최병호.
#pragma once
#include <string>
#include <utility>

namespace logh {
struct MailDelivery {
    void* context=nullptr;
    void* world=nullptr;
    unsigned sender=0,recipient=0,requestId=0,requestReference=0;
    unsigned mode=1,option=0,response=0;
    bool pending=false,draftOpen=false;
    std::wstring title,body;
    bool begin(void* editor,void* session,unsigned from,unsigned to,unsigned high,unsigned low,
        const std::wstring& subject,const std::wstring& text) {
        if (pending || !editor || !session || !from || !to || high==0xffffffff || !(high&0x80000000)) return false;
        MailDelivery next;
        next.context=editor;next.world=session;next.sender=from;next.recipient=to;
        next.requestId=high;next.requestReference=low;next.title=subject;next.body=text;
        next.pending=true;next.draftOpen=true;
        *this=std::move(next);return true;
    }
    void accepted(void* session,unsigned from,unsigned to,unsigned reference) {
        if (pending && world==session && sender==from && recipient==to && requestReference==reference) response=1;
    }
    void rejected(void* session,unsigned type) {
        // 0500 has no nonce. Only one personal send may be pending on this UI thread.
        if (pending && world==session && type==0x0f10) response=2;
    }
    void reset() { *this=MailDelivery{}; }
};
}
