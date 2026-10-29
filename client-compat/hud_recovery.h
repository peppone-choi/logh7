// The CD HUD has a malformed cache-miss block during flagship return. Author: 최병호.
#pragma once
static bool installHudRecovery() {
    // 0058ee70 resolves the character profile at 004c7290. A cache hit already
    // jumps to 0058f8c4; a miss falls into an absolute read from address 0x80 at
    // 0058f83a. It is not a valid profile-relative load. During return/bootstrap
    // the cache can be empty while the old HUD is still rendering. Use the
    // existing cache-hit continuation in both cases; do not forge a profile,
    // skip world initialization or change the authoritative return state.
    const BYTE expected[]={0x0f,0x85,0x8a,0x00,0x00,0x00};
    const BYTE guarded[]={0xe9,0x8b,0x00,0x00,0x00,0x90};
    const BYTE invalidRead[]={0x8b,0x0d,0x80,0x00,0x00,0x00};
    if (memcmp(reinterpret_cast<void*>(0x58f83a),invalidRead,sizeof(invalidRead))) return false;
    return patchBytes(reinterpret_cast<void*>(0x58f834),expected,guarded,sizeof(expected));
}
