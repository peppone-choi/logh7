// Preserve the CD menu distinction lost by its shared missile/AA arm byte. Author: 최병호.
#pragma once
namespace logh {
struct TacticalWeaponSelection {
    unsigned menu = 0;
    void select(unsigned value) { menu = value >= 0x21 && value <= 0x24 ? value : 0; }
    unsigned targetKind(unsigned arms, unsigned kind) const {
        return menu == 0x24 && arms == 2 && kind == 1 ? 0x81 : kind;
    }
    void clear() { menu = 0; }
};
}
