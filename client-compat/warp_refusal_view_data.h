// One facility background after the server refuses a warp from a spot. Author: Peppone Choi (peppone-choi).
#pragma once

namespace logh {
// The CD picks a warp destination on the strategy map. An accepted warp changes the grid and the
// planet layout's auto view follows it. A refusal keeps the grid and the spot, so the map stays up.
// The native ACK argument stays 0 for the server's refused 0b01 (run-04), so the decoded record decides:
// accepted and reserved ACKs carry base 0 / mode 5, a refusal from a facility the current base and
// mode 4 (+0x1c/+0x20). 0b07 at the origin then 0b0b with the same base/mode/spot (+8/+12/+16) follow.
// Only that sequence for the own character, still at the same grid and spot, asks once for the view.
struct WarpRefusalView {
    enum Stage : unsigned char { Idle, Refused, Origin, Restore };
    static constexpr unsigned facilityMode = 4;               // measured docked mode (0b01 and 0b0b)
    static constexpr unsigned long long restoreWindow = 3000; // ms after 0b0b; never a later manual map
    const void* world = nullptr;
    unsigned character = 0, grid = 0, base = 0, mode = 0, spot = 0;
    unsigned long long deadline = 0;
    Stage stage = Idle;

    void clear() { *this = WarpRefusalView{}; }
    bool owns(const void* w, unsigned c) const { return stage != Idle && w == world && c == character; }
    /** Every decoded 0b01. An accepted/reserved ACK, another character or a space origin clears. */
    void refused(const void* w, unsigned messageCharacter, unsigned messageGrid, unsigned messageBase,
        unsigned messageMode, unsigned own, unsigned ownGrid, unsigned ownSpot, bool blocked) {
        clear();
        if (!w || blocked || !own || messageCharacter != own || ownGrid >= 5000 || messageGrid != ownGrid ||
            !messageBase || messageMode != facilityMode || !ownSpot) return;
        world = w; character = own; grid = ownGrid; base = messageBase; mode = messageMode; spot = ownSpot;
        stage = Refused;
    }
    /** 0b07 for the refused character ends its command wait at the origin. */
    void moved(const void* w, unsigned c) {
        if (!owns(w, c)) return;
        if (stage == Refused) stage = Origin; else clear();
    }
    /** 0b0b for the refused character must confirm the same facility it was refused from. */
    void arrived(const void* w, unsigned c, unsigned arrivedBase, unsigned arrivedMode, unsigned arrivedSpot,
        unsigned long long now) {
        if (!owns(w, c)) return;
        if (stage != Origin || arrivedBase != base || arrivedMode != mode || arrivedSpot != spot) { clear(); return; }
        stage = Restore; deadline = now + restoreWindow;
    }
    static unsigned u16(const unsigned char* p) { return static_cast<unsigned>(p[0]) | static_cast<unsigned>(p[1]) << 8; }
    static unsigned u32(const unsigned char* p) { return u16(p) | u16(p + 2) << 16; }
    /** Decoded 0b01: character +8, grid u16 +0x18, base +0x1c, mode u16 +0x20. */
    void refusedRecord(const void* w, const unsigned char* record, unsigned own, unsigned ownGrid, unsigned ownSpot,
        bool blocked) {
        if (!record) { clear(); return; }
        refused(w, u32(record + 8), u16(record + 0x18), u32(record + 0x1c), u16(record + 0x20), own, ownGrid,
            ownSpot, blocked);
    }
    /** Decoded 0b0b: character +4, base +8, mode u16 +12, spot +16. */
    void arrivedRecord(const void* w, const unsigned char* record, unsigned long long now) {
        if (record) arrived(w, u32(record + 4), u32(record + 8), u16(record + 12), u32(record + 16), now);
    }
    /** Strategy map frame. True once when the own character is still in the refused facility. */
    bool take(const void* w, unsigned own, unsigned ownGrid, unsigned ownSpot, bool blocked, unsigned long long now) {
        if (stage != Restore) return false;
        const bool match = w == world && own == character && ownGrid == grid && ownSpot == spot &&
            !blocked && now <= deadline;
        clear();
        return match;
    }
};
}
