// 割当 직무 편지 (design-proposals-orders.md): the 命令/提案 window asks the server for a draft over the strategy
// directory (0x1200 search 0x7f00 command 98 value 0x0c0b, phase 0), opens the native 割当 editor (command 67) on
// the recipient's logistics card, and turns that editor's own 0x0c0b send (selector 0x69) into a mail envelope:
// record +8 recipient, +12 token, +24 kind|0x80. Phase 1 with the same recipient/token cancels the draft (no reply).
// Replies: status +10, phase +16 (2 granted/delivered, 3 refused), token +20, card +24 (draft only).
// Author: Peppone Choi. Pure state: the window and hooks live in proposal_send.h.
#pragma once
namespace logh {
constexpr unsigned short assignmentMailValue=0x0c0b;
constexpr unsigned assignmentMailCommand=67;               // native 割当 command window
constexpr unsigned long long assignmentDraftWait=10000;    // draft reply
constexpr unsigned long long assignmentDraftLife=115000;   // server grant is 120 s; stop a little earlier
constexpr unsigned long long assignmentSendWait=15000;     // mail confirmation
// The grant arrives as 1200+1201. 004c1dd0 (1200) zeroes every simple-list count and sets world+0x487471; 004c1e50
// (1201) clears it. The editor is opened from the GUI frame once no search has been open for a few frames, never from
// inside the packet reader. This is only a precaution: runs 02/04 show the draft's 1201 ending before search 32.
constexpr unsigned assignmentSettleFrames=4;
// The no-items modal (선택할 항목이 없습니다) has another source. When the CD is left on its 命令/提案 tab (the way this window
// is opened), the end of the editor's base search 32 makes that tab send search 29 again (runs 02/04: 6-7 ms after the
// 1204; the direct 割当 in run 03, CD on 직무 권한 카드, sends none). The 1200 reply to search 29 zeroes the base list that
// 1204 just filled (004c1dd0). The picker recounts it (0057b760, count +0xb28), sets state 1 (005796d8), and the base
// step shows the modal (00571d0e) and ends the command. The tab never draws rows, so the sender drops that search while
// this editor is pending or open.
constexpr unsigned short assignmentTabSearch=29;
inline void assignmentDraftRecord(unsigned char record[36],unsigned character,unsigned recipient,unsigned token,bool cancel) {
    for(unsigned i=0;i<36;++i)record[i]=0;
    *reinterpret_cast<unsigned*>(record)=character;
    *reinterpret_cast<unsigned short*>(record+4)=0x7f00;
    *reinterpret_cast<unsigned short*>(record+6)=98;
    *reinterpret_cast<unsigned short*>(record+10)=assignmentMailValue;
    *reinterpret_cast<unsigned*>(record+12)=recipient;
    record[16]=cancel ? 1 : 0;
    *reinterpret_cast<unsigned*>(record+20)=token;
}
/** The native 0x69 record keeps zero in its CP words (+8, +12); the mail reuses them. */
inline void assignmentMailEnvelope(unsigned char* record,unsigned recipient,unsigned token) {
    *reinterpret_cast<unsigned*>(record+8)=recipient;
    *reinterpret_cast<unsigned*>(record+12)=token;
    record[24]=static_cast<unsigned char>(record[24]|0x80);
}
/** A positive 31-bit token, never the previous one. */
inline unsigned assignmentMailToken(unsigned long long now,unsigned previous) {
    unsigned token=static_cast<unsigned>((now*2654435761ull)^(now>>17)^(previous*40503u+1u))&0x7fffffffu;
    if(!token || token==previous)token=(previous%0x7ffffffeu)+1u;
    return token;
}
struct AssignmentMail {
    enum Stage {Idle,Drafting,Granted,Editing,Sending};
    enum Reply {Ignored,Grant,Refused,Delivered,Failed};
    enum Event {None,Reset,NoReply,Stalled,Closed,Expired,Unconfirmed};
    enum Send {PassThrough,Envelope,Suppress};
    const void* world=nullptr;
    const void* editor=nullptr;  // the explicitly opened native editor (Editing/Sending)
    const void* stale=nullptr;   // an editor whose draft ended; its sends are suppressed until it closes
    unsigned actor=0,recipient=0,token=0,card=0,status=0;
    unsigned settled=0;          // Granted: GUI frames since the native search transaction ended
    Stage stage=Idle;
    unsigned long long since=0;
    bool active()const {return stage!=Idle;}
    void finish() {
        if(editor)stale=editor;
        editor=nullptr;stage=Idle;
    }
    /** One draft at a time. */
    bool begin(const void* session,unsigned character,unsigned person,unsigned request,unsigned long long now) {
        if(stage!=Idle || !session || !character || !person || !request || request>0x7fffffffu)return false;
        world=session;actor=character;recipient=person;token=request;card=0;status=0;stage=Drafting;since=now;
        return true;
    }
    /** A 1200/7f00/98 reply; anything but this draft's own token and recipient is ignored. */
    Reply receive(const void* session,unsigned character,unsigned target,unsigned phase,unsigned request,unsigned result,unsigned grant,
        unsigned long long now) {
        if((stage!=Drafting && stage!=Sending) || session!=world || character!=actor || target!=recipient ||
            request!=token || (phase!=2 && phase!=3))return Ignored;
        status=result;
        if(stage==Drafting) {
            if(!result && phase==2 && grant>0 && grant<=260) {card=grant;stage=Granted;since=now;settled=0;return Grant;}
            finish();return Refused;
        }
        bool delivered=!result && phase==2;
        finish();return delivered ? Delivered : Failed;
    }
    /** Granted, once per GUI frame (never from the packet reader): true when the editor may open, i.e. no native search
     * transaction is open (`searching` = world+0x487471) for assignmentSettleFrames frames in a row. */
    bool openable(bool searching) {
        if(stage!=Granted)return false;
        if(searching) {settled=0;return false;}
        return ++settled>=assignmentSettleFrames;
    }
    /** The CD tab's search 29 is held back from the grant until this editor closes (`command` = 0xc9e2f8). A direct
     * 割当 (another command) or a closed editor lets it through. */
    bool holdsTabSearch(const void* command)const {
        return stage==Granted || ((stage==Editing || stage==Sending) && command && command==editor);
    }
    /** The native editor class (0052f700-005339a1, e.g. its data load 005321a0) does not read the command object's +0x28:
     * it reads the CD selection, card ID 0xc9eac0 and command slot 0xc9eabc, in the card definition table. Only 割当 (0x43)
     * takes the base and outfit chosen in this command's own steps; any other command loads the sender's own unit (run 06:
     * 0326 base/0 twice, 032e 0/base, header 제0함대). The mail editor is opened from the 命令/提案 tab, so the CD never
     * selected 割当; those lookups see it only while this draft's editor is the open command. */
    bool editsAssignment(const void* session,const void* command)const {
        return command && command==editor && session==world && (stage==Editing || stage==Sending);
    }
    /** After a grant: the editor the native entry opened, or nullptr when it did not open (caller cancels). */
    bool attach(const void* command) {
        if(stage!=Granted)return false;
        if(!command) {finish();return false;}
        editor=command;stage=Editing;
        return true;
    }
    /** The editor's 0x69 send: only this world/actor/editor carries the mail; a stale or repeated one is held back. */
    Send send(const void* session,unsigned character,const void* command,unsigned long long now) {
        if(!command)return PassThrough;
        if(command==stale)return Suppress;
        if(command!=editor)return PassThrough;
        if(stage!=Editing || session!=world || character!=actor || now-since>=assignmentDraftLife)return Suppress;
        stage=Sending;since=now;
        return Envelope;
    }
    /** Every frame. Closed/Expired/NoReply/Stalled leave recipient and token set so the caller can send the cancel. */
    Event tick(const void* session,unsigned character,const void* command,unsigned long long now) {
        if(stale && command!=stale)stale=nullptr;
        if(!active())return None;
        if(session!=world || character!=actor) {*this=AssignmentMail{};return Reset;}
        if(stage==Drafting && now-since>=assignmentDraftWait) {finish();return NoReply;}
        if(stage==Granted && now-since>=assignmentDraftWait) {finish();return Stalled;} // the native search never settled
        if(stage==Editing && command!=editor) {editor=nullptr;finish();return Closed;}
        if(stage==Editing && now-since>=assignmentDraftLife) {finish();return Expired;}
        if(stage==Sending && command!=editor)editor=nullptr; // the editor may close itself after sending
        if(stage==Sending && now-since>=assignmentSendWait) {finish();return Unconfirmed;}
        return None;
    }
    /** The window was closed before a grant or an editor: the caller cancels. */
    bool abandon() {
        if(stage!=Drafting && stage!=Granted)return false;
        finish();return true;
    }
};
/** SocialRejection ordinal+1. */
inline const wchar_t* assignmentMailFailure(unsigned code) {
    switch(code) {
    case 4:return L"상대에게 이 할당을 맡길 병참 직무가 없습니다.";
    case 5:return L"영향력이 부족합니다(1 필요).";
    case 6:return L"상대가 범위 밖이거나 전술 중입니다.";
    case 7:return L"진행 중인 작업이 끝난 뒤 보내십시오.";
    case 8:return L"MCP가 부족합니다(80 필요).";
    case 9:return L"상대가 하루 안에 같은 요청을 거절했습니다.";
    case 10:return L"할당 준비가 만료됐습니다. 다시 준비하세요.";
    case 13:return L"상대가 이 내용으로 할당할 수 없습니다.";
    default:return L"보낼 수 없습니다. 대상·내용·위치를 확인하세요.";
    }
}
}
