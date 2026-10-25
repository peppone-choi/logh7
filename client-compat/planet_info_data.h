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
// A row keeps its server ID independently of the native catalogue slot and
// display/filter order. At most one four-base condition request is outstanding.
struct PlanetDirectory {
    // The original list has 600 cells, not 600 rows. Six columns need an
    // empty terminating row: header + 98 entries + terminator = 600 cells.
    static constexpr unsigned pageCapacity = 98;
    static unsigned viewRow(unsigned visible, unsigned page, unsigned row) {
        if (!row || row>pageCapacity || page>visible/pageCapacity) return visible;
        unsigned index=page*pageCapacity+row-1;
        return index<visible ? index : visible;
    }
    struct Row {
        unsigned id, grid, slot, population;
        unsigned char state, power, camp, habitable;
    } rows[350] = {};
    unsigned count = 0;
    bool add(unsigned id, unsigned grid, unsigned slot) {
        if (!id || grid >= 5000 || slot >= 350 || count == 350) return false;
        for (unsigned i = 0; i < count; ++i) if (rows[i].id == id) return false;
        rows[count++] = {id, grid, slot, 0, 0, 0, 0, 0}; return true;
    }
    unsigned pending() const {
        unsigned n = 0; for (unsigned i = 0; i < count; ++i) if (rows[i].state == 1) ++n; return n;
    }
    unsigned queried() const {
        unsigned n = 0; for (unsigned i = 0; i < count; ++i) if (rows[i].state >= 2) ++n; return n;
    }
    unsigned received() const {
        unsigned n = 0; for (unsigned i = 0; i < count; ++i) if (rows[i].state == 2) ++n; return n;
    }
    unsigned begin(unsigned (&ids)[4]) {
        if (pending()) return 0;
        unsigned n = 0;
        for (unsigned i = 0; i < count && n < 4; ++i) if (!rows[i].state) {
            rows[i].state = 1; ids[n++] = rows[i].id;
        }
        return n;
    }
    bool accept(const unsigned char* record) {
        if (record[4] > 4 || record[5] > 1 || record[0x175] > 3) return false;
        for (unsigned i = 0; i < count; ++i) {
            auto& row = rows[i];
            if (row.state != 1 || row.id != infoWord(record, 0) || row.grid != infoWord(record, 8)) continue;
            row.power = record[4]; row.camp = record[5]; row.habitable = record[0x175];
            row.population = infoWord(record, 0x120); row.state = 2; return true;
        }
        return false;
    }
    void expire() {
        for (unsigned i = 0; i < count; ++i) if (rows[i].state == 1) rows[i].state = 3;
    }
    void cancel() {
        for (unsigned i = 0; i < count; ++i) if (rows[i].state == 1) rows[i].state = 0;
    }
    void refresh() {
        for (unsigned i = 0; i < count; ++i) rows[i].state = 0;
    }
    unsigned view(unsigned (&indices)[350], bool local, unsigned grid) const {
        unsigned n = 0;
        for (unsigned i = 0; i < count; ++i) if (!local || rows[i].grid == grid) {
            unsigned j = n++;
            while (j && (rows[indices[j - 1]].grid > rows[i].grid ||
                (rows[indices[j - 1]].grid == rows[i].grid && rows[indices[j - 1]].id > rows[i].id))) {
                indices[j] = indices[j - 1]; --j;
            }
            indices[j] = i;
        }
        return n;
    }
};
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
