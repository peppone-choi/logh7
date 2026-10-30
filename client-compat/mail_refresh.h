// One native mailbox refresh for an unsolicited completed inbox transaction.
// Author: 최병호. No wire, editor or native queue is reconstructed here.
#pragma once
namespace logh {
inline bool personalMailAddress(const void* memory) {
    if(!memory)return false;
    const auto peer=static_cast<const unsigned char*>(memory);
    // +118/+11c are the portrait and age clock, not office-address flags.
    return !peer[0x120] && !peer[0x121] && !peer[0x122] && !peer[0x123];
}
struct MailRefresh {
    void* world=nullptr;
    unsigned owner=0;
    bool readyView=false,armed=false,pending=false;
    void observe(void* session,unsigned character,bool ready) {
        if(!session || !character) {*this=MailRefresh{};return;}
        if(world!=session || owner!=character) {
            *this=MailRefresh{};world=session;owner=character;
        }
        readyView=ready;
    }
    void begin(void* session,unsigned character,bool inbox,bool personal) {
        armed=world==session && owner==character && readyView && inbox && personal;
    }
    void end(void* session) {
        if(world==session && armed)pending=true;
        armed=false;
    }
    bool take(void* session,unsigned character,bool idle) {
        if(world!=session || owner!=character || !idle || !pending)return false;
        pending=false;return true;
    }
};
}
