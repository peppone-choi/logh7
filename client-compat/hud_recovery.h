// The CD HUD has an inverted profile check in its ability/influence refresh. Author: Peppone Choi.
#pragma once
static bool installHudRecovery() {
    // 0058ee70 resolves the character profile at 004c7290 and rebuilds the
    // ability/influence/merit text (widget 10) from the own character at ebp
    // and the profile's merit at +0x80 (as 0058d633 does at HUD creation).
    // The CD inverts the test: a hit jumps past the rebuild to 0058f8c4 and a
    // miss reads merit from absolute address 0x80. During return/bootstrap the
    // cache can be empty and the read faults; on a hit the text never refreshes,
    // so a 0356 change (speech influence, ability growth) stays stale until relogin.
    // Rebuild on a hit with the found profile; skip on a miss. Nothing is forged.
    const BYTE expected[]={0x0f,0x85,0x8a,0x00,0x00,0x00};
    const BYTE onHit[]={0x0f,0x84,0x8a,0x00,0x00,0x00};
    const BYTE invalidRead[]={0x8b,0x0d,0x80,0x00,0x00,0x00};
    const BYTE profileRead[]={0x8b,0x88,0x80,0x00,0x00,0x00};
    if (memcmp(reinterpret_cast<void*>(0x58f83a),invalidRead,sizeof(invalidRead))) return false;
    return patchBytes(reinterpret_cast<void*>(0x58f83a),invalidRead,profileRead,sizeof(invalidRead)) &&
        patchBytes(reinterpret_cast<void*>(0x58f834),expected,onHit,sizeof(expected));
}
