// Flagship information: an absent weapon slot versus a genuinely missing name. Author: Peppone Choi.
#pragma once
#include <cstring>

namespace logh {
// The redirected weapon-name group (flagship_info_groups.h) and the specification's "no weapon" kind.
constexpr unsigned flagshipWeaponGroup=0x74;
constexpr unsigned flagshipWeaponNone=255;
// Only kind 255 answered as an absent row is intentional. Any other unknown kind, a missing
// table (NO TABLE) and a catalogue that does name kind 255 keep the native result.
inline bool flagshipWeaponAbsent(unsigned group,unsigned index,const char* original) {
    return group==flagshipWeaponGroup && index==flagshipWeaponNone && original && !std::strcmp(original,"NO DATA");
}
}
