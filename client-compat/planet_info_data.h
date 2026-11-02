// Decoded CD records, not wire packets. Author: Peppone Choi.
#pragma once
#include <stdint.h>
#include <string.h>

namespace logh {
// 031f carries owning power and camp separately. The two military powers
// can have a rebel camp; never discard that distinction in a detail view.
inline const wchar_t* planetAffiliation(unsigned power, unsigned camp) {
    if (power>4 || camp>1) return L"미확인";
    if (camp) return power==2 ? L"제국 반란군" : power==3 ? L"동맹 반란군" : L"기타 반란 진영";
    return power==2 ? L"은하제국" : power==3 ? L"자유행성동맹" : power==0 ? L"중립" : L"기타 세력";
}
// The CD accepts habitability codes 0..3, but only the server's current
// 0/1 meanings are established. Other values do not prove habitability.
inline const wchar_t* planetHabitability(unsigned value) {
    return value==0 ? L"불가" : value==1 ? L"가능" : L"미확인";
}
inline const wchar_t* planetAtmosphere(unsigned value) {
    return value==0 ? L"없음" : value==1 ? L"있음" : L"미확인";
}
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
struct PlanetOfficeKinds { unsigned first=0, second=0; };
// Decoded catalogue names are UTF-16, independent of the display locale.
inline unsigned planetCapitalPower(const unsigned char* base) {
    if (!base || infoShort(base,0x14)!=0) return 0;
    const uint16_t odin[]={0x30aa,0x30fc,0x30c7,0x30a3,0x30f3,0};
    const uint16_t heinessen[]={0x30cf,0x30a4,0x30cd,0x30bb,0x30f3,0};
    const uint16_t odinKo[]={0xc624,0xb518,0};
    const uint16_t heinessenKo[]={0xd558,0xc774,0xb124,0xc13c,0};
    unsigned grid=infoShort(base,0x10);
    if (grid==2487 && (!memcmp(base+0x48,odin,sizeof(odin)) || !memcmp(base+0x48,odinKo,sizeof(odinKo)))) return 2;
    if (grid==2015 && (!memcmp(base+0x48,heinessen,sizeof(heinessen)) || !memcmp(base+0x48,heinessenKo,sizeof(heinessenKo)))) return 3;
    return 0;
}
inline PlanetOfficeKinds planetOfficeKinds(const unsigned char* base,unsigned power,unsigned camp) {
    if (!base || camp || (power!=2 && power!=3)) return {};
    unsigned capital=planetCapitalPower(base);
    if (capital) return capital!=power ? PlanetOfficeKinds{} :
        power==2 ? PlanetOfficeKinds{56,57} : PlanetOfficeKinds{192,193};
    unsigned offset=power==3 ? 136 : 0;
    return infoShort(base,0x14)==1 ? PlanetOfficeKinds{0,52+offset} : PlanetOfficeKinds{54+offset,55+offset};
}
// 004167f0 decoded layout: four 0x2378-byte bases, 36 institutions,
// twenty 12-byte spots each. Preserve IDs independently of display rows.
struct PlanetPlaces {
    struct Row { unsigned institution, institutionKind, spot, spotKind, image; } rows[720] = {};
    unsigned count = 0, institutions = 0;
    bool accept(const unsigned char* response, unsigned id) {
        if (response[0] > 4 || !id) return false;
        const unsigned char* base = nullptr;
        for (unsigned i=0;i<response[0];++i) {
            auto candidate=response+4+i*0x2378;
            if (infoWord(candidate,0)==id) { base=candidate;break; }
        }
        if (!base || base[4]>36) return false;
        // Validate before changing a previously received snapshot.
        for (unsigned i=0;i<base[4];++i) {
            auto facility=base+8+i*0xfc;
            if (infoShort(facility,0)>38 || !infoWord(facility,4) || facility[8]>20) return false;
            for (unsigned j=0;j<facility[8];++j) {
                auto spot=facility+12+j*12;
                if (infoShort(spot,0)>104 || !infoWord(spot,4) || !infoShort(spot,8)) return false;
            }
        }
        count=0;institutions=base[4];
        for (unsigned i=0;i<institutions;++i) {
            auto facility=base+8+i*0xfc;
            unsigned n=facility[8];
            for (unsigned j=0;j<(n?n:1);++j) {
                auto spot=facility+12+j*12;
                rows[count++]={infoWord(facility,4),infoShort(facility,0),
                    n?infoWord(spot,4):0,n?infoShort(spot,0):0,n?infoShort(spot,8):0};
            }
        }
        return true;
    }
};
// 034f: header card u16/count u8; 64 ordinary 0x2d4 character records.
struct PlanetOfficeHolder {
    unsigned id=0;
    uint16_t surname[13]={},givenName[13]={};
    bool accept(const unsigned char* response,unsigned card,unsigned base) {
        if ((card!=52 && card!=54 && card!=55 && card!=56 && card!=57 && card!=188 && card!=190 && card!=191 && card!=192 && card!=193) || infoShort(response,0)!=card || response[2]>64 || !base) return false;
        const unsigned char* holder=nullptr;
        for (unsigned i=0;i<response[2];++i) {
            auto person=response+4+i*0x2d4;
            if (!infoWord(person,0) || person[0x24c]>16 || person[0x7d]>2) return false;
            for (unsigned j=0;j<person[0x24c];++j) {
                auto office=person+0x250+j*8;
                if (infoShort(office,0)==card && infoWord(office,4)==base) {
                    if (holder && infoWord(holder,0)!=infoWord(person,0)) return false;
                    holder=person;
                }
            }
        }
        id=holder?infoWord(holder,0):0;
        memset(surname,0,sizeof(surname));memset(givenName,0,sizeof(givenName));
        if (holder && holder[0x7d]) {
            memcpy(surname,holder+0x82,24);memcpy(givenName,holder+0x9c,24);
        }
        return true;
    }
};
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
        unsigned id, grid, slot, population, supplies;
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
            row.population = infoWord(record, 0x120); row.supplies = infoWord(record, 0x18); row.state = 2; return true;
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
