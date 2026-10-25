// Decoded CD records, not wire packets. Author: 최병호.
#pragma once
#include <stdint.h>
#include <string.h>

namespace logh {
// The native base catalogue has at most 350 entries. Keep a sorted, unique
// system index so next/previous never depends on the server's record order.
struct PlanetSystemIndex {
    unsigned grids[350] = {}, count = 0;
    void add(unsigned grid) {
        if (grid >= 5000 || count == 350) return;
        unsigned i = 0;
        while (i < count && grids[i] < grid) ++i;
        if (i < count && grids[i] == grid) return;
        for (unsigned j = count; j > i; --j) grids[j] = grids[j - 1];
        grids[i] = grid; ++count;
    }
    unsigned position(unsigned grid) const {
        for (unsigned i = 0; i < count; ++i) if (grids[i] == grid) return i;
        return count;
    }
    unsigned adjacent(unsigned grid, bool forward) const {
        if (!count) return grid;
        unsigned i = position(grid);
        if (i == count) return forward ? grids[0] : grids[count - 1];
        return grids[forward ? (i + 1) % count : (i + count - 1) % count];
    }
};
inline uint32_t infoWord(const unsigned char* record, unsigned offset) {
    uint32_t value;
    memcpy(&value, record + offset, sizeof(value));
    return value;
}
inline unsigned infoShort(const unsigned char* record, unsigned offset) {
    uint16_t value;
    memcpy(&value, record + offset, sizeof(value));
    return value;
}
// 00414c70: count + padding, then at most four 0x180-byte records.
inline const unsigned char* findPlanetCondition(const unsigned char* query, unsigned id, unsigned grid) {
    if (!id || grid >= 5000 || query[0] > 4) return nullptr;
    for (unsigned i = 0; i < query[0]; ++i) {
        const auto record = query + 4 + i * 0x180;
        if (infoWord(record, 0) == id && infoWord(record, 8) == grid) return record;
    }
    return nullptr;
}
// 0041a870: the planet warehouse has outfit ID zero; another target is stale.
inline bool matchingPlanetWarehouse(const unsigned char* query, unsigned id) {
    return id && infoWord(query, 0) == id && infoWord(query, 4) == 0 && query[12] < 100 && query[0x260] < 25;
}
}
