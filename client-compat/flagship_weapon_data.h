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
static_assert(flagshipWeaponNone*9==255*9,"The rate-row thunks compare the native kind*9 value with 255*9.");
}

// 0054d4e0's weapon detail computes one 0x12-byte accuracy row per slot from the kind: flag at +0, eight
// rates at +2. The table has 200 rows, so kind 255 lands past it and printed unrelated memory as rates.
// A cleared flag already takes the native branch that prints dashes without reading any rate, so kind 255
// selects this empty row. Each thunk replaces one 7-byte LEA and keeps its registers; other kinds compute as before.
static const unsigned char flagshipAbsentWeaponRow[0x12]={};
static void __declspec(naked) flagshipBeamRateRow() { // world=ecx, kind*9=eax, row=ebp
    __asm {
        cmp eax,255*9
        lea ebp,[ecx+eax*2+0x340828]
        jne done
        mov ebp,offset flagshipAbsentWeaponRow
    done:
        ret
    }
}
static void __declspec(naked) flagshipGunRateRow() { // world=ecx, kind*9=eax, row=eax
    __asm {
        cmp eax,255*9
        lea eax,[ecx+eax*2+0x340828]
        jne done
        mov eax,offset flagshipAbsentWeaponRow
    done:
        ret
    }
}
static void __declspec(naked) flagshipMissileRateRow() { // world=eax, kind*9=edx, row=eax
    __asm {
        cmp edx,255*9
        lea eax,[eax+edx*2+0x340828]
        jne done
        mov eax,offset flagshipAbsentWeaponRow
    done:
        ret
    }
}
static void __declspec(naked) flagshipAntiAirRateRow() { // world=edx, kind*9=ecx, row=eax
    __asm {
        cmp ecx,255*9
        lea eax,[edx+ecx*2+0x340828]
        jne done
        mov eax,offset flagshipAbsentWeaponRow
    done:
        ret
    }
}
