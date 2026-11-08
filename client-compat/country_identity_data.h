// Authenticated own membership, distinct from the original character's display byte. Author: Peppone Choi.
#pragma once
#include "lobby_country_data.h"
namespace logh {
// record[10] bit 0x20: the server's authoritative persistent wounded state (absent on older servers).
constexpr unsigned char ownWoundedFlag=0x20;
struct CountryIdentity {unsigned character=0,power=0;bool registered=false,wounded=false;CountryMetadata country={};};
inline bool readCountryIdentity(const unsigned char* p,std::size_t size,unsigned character,unsigned nonce,CountryIdentity& output) {
    if(!p || size<24+10*36 || !character || !nonce || nonce>65535 || paintWord(p)!=character || paintWord(p+4)!=nonce ||
        paintWord(p+12) || paintWord(p+16))return false;
    wchar_t fields[10][13]={};if(!lobbyCountryFields(p,size,fields,11) || std::wcscmp(fields[0],L"NI1"))return false;
    std::uint64_t id=0,power=0,registered=0;
    CountryIdentity candidate;candidate.character=character;
    if(!lobbyCountryNumber(fields[1],0x7fffffff,id) || !lobbyCountryNumber(fields[8],3,power) || power<2 ||
        !lobbyCountryNumber(fields[9],1,registered) || (registered && !id) || !fields[2][0] || !fields[4][0] ||
        (fields[3][0] && std::wcslen(fields[2])!=12) || !countryMetadataYearOffset(fields[5],candidate.country.yearOffset) ||
        !lobbyCountryRevision(fields[6],fields[7],candidate.country.revision))return false;
    candidate.power=static_cast<unsigned>(power);candidate.registered=registered!=0;candidate.country.id=static_cast<unsigned>(id);
    candidate.wounded=(p[10]&ownWoundedFlag)!=0;
    wcscpy_s(candidate.country.name,fields[2]);wcscat_s(candidate.country.name,fields[3]);wcscpy_s(candidate.country.calendar,fields[4]);
    output=candidate;return true;
}
}
