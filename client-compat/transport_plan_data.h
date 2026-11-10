// Multi-item 輸送計画 (command 28) rows collected by the transport editor. Pure data, no game memory.
// Wire (server StrategyActionMessages/GameExchange): every frame is the native fixed 31-byte 1200/7f00 command 28
// message (selector 0x46), phase 0, one character/card/source/destination and one nonzero token per transaction:
//   start  value 0xfffd, argument = row count 2..123, amount = supplies
//   row    value 0xfffc, argument = (item code << 16) | quantity, amount 0   (once per row)
//   commit value 0xfffe, argument = the same count,   amount = the same supplies
// Each frame waits for its own token-matched 1200/1201 reply (status 0) before the next is sent; only the commit's
// reply creates the package. Codes are ship kinds 0..199 or crew/marine kinds 1000..1015, each at most once.
// Fewer than two rows keep the original single-item message. Author: Peppone Choi.
#pragma once
namespace logh {
constexpr unsigned transportNoUnit=0xffff,transportPlanRowLimit=123;
constexpr unsigned transportRowValue=0xfffc,transportStartValue=0xfffd,transportCommitValue=0xfffe;
inline bool transportItemCode(unsigned code) {return code<=199 || (code>=1000 && code<=1015);}
struct TransportPlan {
    unsigned short codes[transportPlanRowLimit],amounts[transportPlanRowLimit];
    unsigned count,selected;
    int find(unsigned code) const {
        for(unsigned i=0;i<count;++i)if(codes[i]==code)return static_cast<int>(i);
        return -1;
    }
    /** Adds a row, or replaces the amount of the row with the same code. The touched row becomes selected. */
    bool put(unsigned code,unsigned amount) {
        if(!transportItemCode(code) || !amount || amount>65535)return false;
        int row=find(code);
        if(row<0) {
            if(count>=transportPlanRowLimit)return false;
            row=static_cast<int>(count++);codes[row]=static_cast<unsigned short>(code);
        }
        amounts[row]=static_cast<unsigned short>(amount);selected=static_cast<unsigned>(row);
        return true;
    }
    bool remove(unsigned row) {
        if(row>=count)return false;
        for(unsigned i=row+1;i<count;++i) {codes[i-1]=codes[i];amounts[i-1]=amounts[i];}
        --count;
        if(selected>=count)selected=count ? count-1 : 0;
        return true;
    }
    void step(int direction) {if(count)selected=direction<0 ? (selected+count-1)%count : (selected+1)%count;}
    /** 2..123 valid, distinct rows with positive amounts: the plan can go out as staged frames. */
    bool staged() const {
        if(count<2 || count>transportPlanRowLimit)return false;
        for(unsigned i=0;i<count;++i)
            if(!transportItemCode(codes[i]) || !amounts[i] || find(codes[i])!=static_cast<int>(i))return false;
        return true;
    }
};
/** Header value/argument of a single command 28 message. An empty plan sends the editor's single kind exactly as before;
 * one collected row uses that original single-item form. False for two or more rows, which go out staged instead. */
inline bool transportPlanHeader(const TransportPlan& plan,unsigned editorKind,unsigned editorCount,
    unsigned& value,unsigned& argument) {
    if(plan.count>=2)return false;
    if(plan.count==1) {value=plan.codes[0];argument=plan.amounts[0];}
    else {value=editorKind;argument=editorKind==transportNoUnit ? 0 : editorCount;}
    return true;
}
/** Frame `index` of a staged plan: 0 start, 1..count one row each, count+1 commit. False past the commit, for a plan
 * that cannot be staged, or for supplies that do not fit the 16-bit amount. */
inline bool transportStagedFrame(const TransportPlan& plan,unsigned supplies,unsigned index,
    unsigned& value,unsigned& argument,unsigned& amount) {
    if(!plan.staged() || supplies>65535 || index>plan.count+1)return false;
    if(!index || index==plan.count+1) {
        value=index ? transportCommitValue : transportStartValue;argument=plan.count;amount=supplies;
    } else {
        value=transportRowValue;argument=(static_cast<unsigned>(plan.codes[index-1])<<16)|plan.amounts[index-1];amount=0;
    }
    return true;
}
/** The 36-byte command 28 record that selector 0x46 packs into the fixed 31-byte message (phase byte +16 left 0). */
inline void transportPlanRecord(unsigned char record[36],unsigned character,unsigned card,unsigned value,unsigned destination,
    unsigned argument,unsigned amount,unsigned source,unsigned token) {
    for(unsigned i=0;i<36;++i)record[i]=0;
    *reinterpret_cast<unsigned*>(record)=character;
    *reinterpret_cast<unsigned short*>(record+4)=0x7f00;
    *reinterpret_cast<unsigned short*>(record+6)=28;
    *reinterpret_cast<unsigned short*>(record+8)=static_cast<unsigned short>(card);
    *reinterpret_cast<unsigned short*>(record+10)=static_cast<unsigned short>(value);
    *reinterpret_cast<unsigned*>(record+12)=destination;
    *reinterpret_cast<unsigned*>(record+20)=argument;
    *reinterpret_cast<unsigned short*>(record+24)=static_cast<unsigned short>(amount);
    *reinterpret_cast<unsigned*>(record+28)=source;
    *reinterpret_cast<unsigned short*>(record+32)=static_cast<unsigned short>(token);
}
/** One staged transaction. The packet reader only records a reply (receive); the GUI frame sends the next frame once
 * that reply's native search transaction has ended (ready). Source, destination and supplies are fixed at begin. */
struct TransportStaging {
    enum Reply {Ignored,Advance,Done,Rejected};
    unsigned token,source,destination,supplies,next,frames;
    bool acked;
    bool active() const {return frames!=0;}
    bool begin(const TransportPlan& plan,unsigned request,unsigned from,unsigned to,unsigned amount) {
        if(active() || !plan.staged() || !request || request>65535 || amount>65535)return false;
        *this=TransportStaging{};
        token=request;source=from;destination=to;supplies=amount;frames=plan.count+2;
        return true;
    }
    /** The token-matched reply to frame `next`: a rejection or the commit's reply ends the transaction. */
    Reply receive(unsigned replyStatus) {
        if(!active() || acked)return Ignored;
        if(replyStatus) {stop();return Rejected;}
        if(next+1>=frames) {stop();return Done;}
        acked=true;return Advance;
    }
    /** Once per GUI frame (`searching` = world+0x487471, a native 1200 transaction still open): true when the frame
     * now in `next` may be sent. */
    bool ready(bool searching) {
        if(!active() || !acked || searching)return false;
        acked=false;++next;return true;
    }
    void stop() {*this=TransportStaging{};}
};
}
