// Generated-character deletion from the CD lobby. Author: Peppone Choi (peppone-choi).
// Revival extension: the 0x032e mode 24 request below is the replacement-server contract,
// not a recovered original format.
#pragma once
#include <cstddef>
#include <cstdint>
#include <cstring>
namespace logh {
// Native lobby cache (world+0x35975c): count byte, then up to two 0x36c-byte rows.
// A charged row has +0x2a0==2/+0x2a1==1, InformationSession at +4 (id u16 at +4,
// name length at +7, UTF-16 name at +8) and the character id at +0x2a4.
// Same offsets as SessionEndingData::readLobby and lobby_portrait_transport.h.
constexpr std::size_t lobbyRowBytes=0x36c;
constexpr std::size_t lobbyListBytes=0x6d8;
// Only server-generated characters (id >= 10000) can be deleted; original NPC ids drawn in the
// lottery (e.g. 1001) stay disabled. Rank/location eligibility is the server's saved judgment.
constexpr unsigned lobbyGeneratedCharacterMin=10000;
struct LobbyDeletionRow {
    unsigned session=0,character=0;wchar_t name[14]={};
    bool same(const LobbyDeletionRow& other) const {return session==other.session && character==other.character;}
};
inline bool readLobbyDeletionRow(const unsigned char* p,std::size_t length,unsigned index,LobbyDeletionRow& row) {
    if(!p || !p[0] || p[0]>2 || index>=p[0] || length<(index+1)*lobbyRowBytes)return false;
    const auto q=p+index*lobbyRowBytes;
    if(q[0x2a0]!=2 || q[0x2a1]!=1 || q[7]>13)return false;
    LobbyDeletionRow next;
    std::uint16_t session=0;std::uint32_t character=0;
    std::memcpy(&session,q+4,2);std::memcpy(&character,q+0x2a4,4);
    if(!session || character<lobbyGeneratedCharacterMin)return false;
    next.session=session;next.character=character;
    // The 2004 length counts the terminating NUL (same contract as readLobbySessionList). Show
    // the name only when that last unit is NUL and none before it is; otherwise no name.
    if(const unsigned n=q[7]) {
        const auto name=q+8;
        if(!name[(n-1)*2] && !name[(n-1)*2+1]) {
            std::memcpy(next.name,name,(n-1)*2u);next.name[n-1]=0;
            for(unsigned i=0;i+1<n;++i)if(!next.name[i]) {next.name[0]=0;break;}
        }
    }
    row=next;return true;
}
// C2S 0x032e mode 24: character u32 BE, session u32 BE, mode u8. The query is the native
// 0x4b78a0(world,0,0x10,query[3]) argument, the same sender as lobby_country_transport.h.
constexpr unsigned lobbyDeletionMode=24;
constexpr std::size_t lobbyDeletionBodyBytes=9;
inline bool lobbyDeletionQuery(const LobbyDeletionRow& row,unsigned (&query)[3]) {
    if(!row.session || row.session>0xffff || row.character<lobbyGeneratedCharacterMin)return false;
    query[0]=row.character;query[1]=row.session;query[2]=lobbyDeletionMode;
    return true;
}
// Body bytes after the 0x032e opcode, derived from the query the native sender serializes.
inline bool encodeLobbyDeletion(const LobbyDeletionRow& row,unsigned char (&body)[lobbyDeletionBodyBytes]) {
    unsigned query[3]={};
    if(!lobbyDeletionQuery(row,query))return false;
    for(unsigned i=0;i<4;++i) {
        body[i]=static_cast<unsigned char>(query[0]>>(24-8*i));
        body[4+i]=static_cast<unsigned char>(query[1]>>(24-8*i));
    }
    body[8]=static_cast<unsigned char>(query[2]);
    return true;
}
enum class LobbyDeletionOutcome { Unknown,Deleted,Remaining };
// Judged only on a 2004 list refreshed after the request: the row is gone or still listed.
inline LobbyDeletionOutcome lobbyDeletionOutcome(const unsigned char* p,std::size_t length,const LobbyDeletionRow& target) {
    // A session count of 0 is the native server-error state (0x0051ab07), not an answer.
    if(!p || length<lobbyRowBytes || !p[0] || p[0]>2)return LobbyDeletionOutcome::Unknown;
    for(unsigned i=0;i<p[0];++i) {
        LobbyDeletionRow row;
        if(readLobbyDeletionRow(p,length,i,row) && row.same(target))return LobbyDeletionOutcome::Remaining;
    }
    return LobbyDeletionOutcome::Deleted;
}
}
