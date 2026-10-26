// Native 0041cba0 main standing-outfit record. Author: 최병호.
#pragma once
#include "planet_info_data.h"

namespace logh {
constexpr unsigned outfitPartyBytes = 0x45a0;
inline const unsigned char* outfitListRow(const unsigned char* cache, unsigned row) {
    if (!row || row > 100) return nullptr;
    for (unsigned i = 0; i < 100; ++i) {
        auto record = cache + i * 32;
        if (record[0] && --row == 0) return infoWord(record, 4) ? record + 4 : nullptr;
    }
    return nullptr;
}
inline bool validOutfitParty(const unsigned char* record, unsigned id) {
    if (!id || infoWord(record, 0) != id || !infoWord(record, 4) || infoWord(record, 4) > 65535 ||
        record[8] != 0 || record[9] < 2 || record[9] > 3 || infoWord(record, 12) > 3 ||
        infoWord(record, 16) > 255 || record[20] > 10 || record[0x180] > 60 || record[0x4504] > 24 ||
        infoWord(record, 0x4598) > 0x7fffffffu || infoWord(record, 0x459c) > 0x7fffffffu ||
        infoWord(record,0x4598)>0x7fffffffu-infoWord(record,0x459c)) return false;
    for (unsigned i = 0; i < record[20]; ++i) {
        auto member = record + 24 + i * 36;
        unsigned length = member[6];
        if (!infoWord(member, 0) || length > 13 || (length && infoShort(member, 8 + (length - 1) * 2))) return false;
    }
    for (unsigned i = 0; i < record[0x180]; ++i) {
        auto ship = record + 0x184 + i * 0x120;
        if (infoShort(ship, 0) >= 200 || !ship[2] || ship[2] > 70 || ship[6] != ship[2] || !infoShort(ship, 4)) return false;
        for (unsigned j = 0; j < ship[6]; ++j) if (!infoWord(ship, 8 + j * 4)) return false;
    }
    for (unsigned i = 0; i < record[0x4504]; ++i) {
        auto troop = record + 0x4506 + i * 6;
        unsigned kind = infoShort(troop, 0);
        if (kind < 1000 || kind > 1015 || troop[2] > 3 || !infoShort(troop, 4)) return false;
    }
    return true;
}
inline bool matchingOutfitParty(const unsigned char* record,unsigned id,unsigned power,unsigned kind,unsigned base=0) {
    return validOutfitParty(record,id) && record[9]==power && infoWord(record,12)==kind &&
        (!base || infoWord(record,4)==base);
}
inline unsigned outfitSupplyCapacity(const unsigned char* record) {
    return infoWord(record,0x4598)+infoWord(record,0x459c);
}
// Closed/reopened windows may leave earlier replies in flight. Keep their
// request tokens until receipt so an old same-ID reply cannot satisfy a refresh.
struct OutfitReplyQueue {
    struct Request { unsigned id, base, token; bool warehouse; } requests[8] = {};
    unsigned head = 0, count = 0;
    bool add(unsigned id, unsigned base, unsigned token, bool warehouse) {
        if (!id || !token || count == 8) return false;
        requests[(head + count++) % 8] = {id, base, token, warehouse};
        return true;
    }
    bool take(unsigned id, unsigned base, bool warehouse, unsigned& token) {
        if (!count) return false;
        const auto& request = requests[head];
        if (request.id != id || request.warehouse != warehouse || (request.base && request.base != base)) return false;
        token = request.token; head = (head + 1) % 8; --count;
        return true;
    }
};
}
