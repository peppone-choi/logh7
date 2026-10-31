// Repair only the supported CD editor's missing overflow marker. Author: Peppone Choi.
#pragma once
static bool installOutfitWarnings() {
    // 005329c0 and 00533500 wrap excessive counts with constmsg 22/139, absent from the CD.
    // The surviving catalogue's 22/122 is the native backspace/07 colour control.
    const unsigned addresses[]={0x532c34,0x532d8f,0x532dae,0x532e96,0x532eba,0x532eeb,
        0x532f0a,0x53303e,0x53305d,0x533152,0x533171,0x533714,0x53373d};
    const BYTE expected[]={0x68,0x8b,0,0,0},replacement[]={0x68,0x7a,0,0,0};
    for (unsigned address:addresses)
        if (memcmp(reinterpret_cast<void*>(address),expected,sizeof(expected))) return false;
    for (unsigned address:addresses)
        if (!patchBytes(reinterpret_cast<void*>(address),expected,replacement,sizeof(expected))) return false;
    return true;
}
