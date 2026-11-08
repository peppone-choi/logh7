// One outstanding 承認 request over the strategy directory (0x1200 search 0x7f00 command 99).
// Author: Peppone Choi. Pure state: the row UI lives in proposal_approval.h.
#pragma once
namespace logh {
constexpr unsigned short proposalApprovalCommand=99;
constexpr unsigned short proposalApprovalAnswer=3;
constexpr unsigned long long proposalApprovalWait=10000;
inline void proposalApprovalRecord(unsigned char record[36],unsigned character,unsigned mail,unsigned short token) {
    for(unsigned i=0;i<36;++i)record[i]=0;
    *reinterpret_cast<unsigned*>(record)=character;
    *reinterpret_cast<unsigned short*>(record+4)=0x7f00;
    *reinterpret_cast<unsigned short*>(record+6)=proposalApprovalCommand;
    *reinterpret_cast<unsigned short*>(record+10)=proposalApprovalAnswer;
    *reinterpret_cast<unsigned*>(record+12)=mail;
    *reinterpret_cast<unsigned short*>(record+32)=token;
}
struct ProposalApproval {
    enum Outcome {Waiting,Accepted,Rejected,Unconfirmed};
    const void* world=nullptr;
    unsigned owner=0,mail=0,status=0,flag=0;
    unsigned short token=0;
    bool outstanding=false;
    unsigned long long sentAt=0;
    Outcome outcome=Waiting;
    bool settled()const {return !outstanding && outcome!=Waiting;}
    void clear() {*this=ProposalApproval{};}
    /** Refuses a second request until the first is answered and shown. */
    bool begin(const void* session,unsigned character,unsigned id,unsigned short request,unsigned long long now) {
        if(outstanding || outcome!=Waiting || !session || !character || !id || !request)return false;
        world=session;owner=character;mail=id;token=request;sentAt=now;outstanding=true;
        return true;
    }
    /** Anything but the outstanding request's own answer leaves the state alone. */
    bool receive(const void* session,unsigned character,unsigned id,unsigned short request,unsigned result,unsigned committed) {
        if(!outstanding || session!=world || character!=owner || id!=mail || request!=token ||
            (committed!=2 && committed!=3))return false;
        outstanding=false;status=result;flag=committed;
        outcome=!result && committed==2 ? Accepted : Rejected;
        return true;
    }
    /** A logout or another character ends the request without touching any row. */
    void observe(const void* session,unsigned character) {
        if((outstanding || outcome!=Waiting) && (session!=world || character!=owner))clear();
    }
    /** The server may still have committed: only a new mailbox query can show it. */
    bool expire(unsigned long long now) {
        if(!outstanding || now-sentAt<proposalApprovalWait)return false;
        outstanding=false;outcome=Unconfirmed;
        return true;
    }
};
/** SocialRejection ordinal+1 for the reply path; 0 with flag 3 changed nothing. */
inline const wchar_t* proposalApprovalFailure(unsigned code) {
    switch(code) {
    case 0:return L"변경된 내용이 없습니다";
    case 1:return L"접속 상태를 확인하세요";
    case 2:return L"권한을 확인할 수 없습니다";
    case 3:return L"처리할 수 없는 명령입니다";
    case 4:return L"직무 조건이 맞지 않습니다";
    case 7:return L"진행 중인 작업이 있습니다";
    case 8:return L"MCP가 부족합니다";
    case 10:return L"이미 처리됐거나 만료됐습니다";
    default:return L"조건을 확인하세요";
    }
}
}
