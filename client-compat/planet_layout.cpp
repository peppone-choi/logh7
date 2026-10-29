// LOGH7 CD client compatibility module. Author: 최병호
// The original executable stays unchanged. DINPUT8 forwards to the Windows DLL.
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <wincrypt.h>
#include <math.h>
#include <float.h>
#include <string.h>
#include "hud_atlas.h"
#include "spot_presence.h"

#pragma comment(lib, "advapi32.lib")
#pragma comment(lib, "gdi32.lib")
#pragma comment(lib, "user32.lib")
#pragma comment(linker, "/EXPORT:DirectInput8Create=_ForwardDirectInput8Create@20")
#pragma comment(linker, "/EXPORT:LoghPlanetLayoutStatus=_LoghPlanetLayoutStatus@0")

static volatile LONG status = 0;
static int lastGrid = -1;
static ULONGLONG autoViewAt = 0;
static bool patchBytes(void* target, const void* expected, const void* replacement, SIZE_T size) {
    if (memcmp(target, expected, size)) return false;
    DWORD previous = 0;
    if (!VirtualProtect(target, size, PAGE_EXECUTE_READWRITE, &previous)) return false;
    memcpy(target, replacement, size);
    FlushInstructionCache(GetCurrentProcess(), target, size);
    DWORD ignored = 0; VirtualProtect(target, size, previous, &ignored);
    return true;
}
static bool verifiedClient() {
    if (GetModuleHandleW(nullptr) != reinterpret_cast<HMODULE>(0x400000)) return false;
    wchar_t path[MAX_PATH];
    DWORD length = GetModuleFileNameW(nullptr, path, MAX_PATH);
    if (!length || length == MAX_PATH) return false;
    HANDLE file = CreateFileW(path, GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING, 0, nullptr);
    if (file == INVALID_HANDLE_VALUE) return false;
    HCRYPTPROV provider = 0;
    HCRYPTHASH hash = 0;
    bool ok = CryptAcquireContextW(&provider, nullptr, nullptr, PROV_RSA_AES, CRYPT_VERIFYCONTEXT) != FALSE;
    if (ok) ok = CryptCreateHash(provider, CALG_SHA_256, 0, 0, &hash) != FALSE;
    BYTE buffer[16384]; DWORD read = 0;
    while (ok) {
        if (!ReadFile(file, buffer, sizeof(buffer), &read, nullptr)) { ok = false; break; }
        if (!read) break;
        ok = CryptHashData(hash, buffer, read, 0) != FALSE;
    }
    BYTE digest[32]; DWORD size = sizeof(digest);
    const BYTE expected[] = {0xbd,0x19,0x26,0x3c,0x10,0xde,0xcc,0x3d,0x58,0x37,0x31,0x65,0xa8,0x2d,0x42,0xa9,
        0x26,0x78,0x68,0x40,0x0d,0x40,0x7d,0xa8,0x7d,0x5f,0x4f,0x41,0x09,0xab,0x6e,0x16};
    if (ok) ok = CryptGetHashParam(hash, HP_HASHVAL, digest, &size, 0) && size == 32 && !memcmp(digest, expected, 32);
    if (hash) CryptDestroyHash(hash);
    if (provider) CryptReleaseContext(provider, 0);
    CloseHandle(file);
    return ok;
}

template<class T> static T& at(void* base, unsigned offset) {
    return *reinterpret_cast<T*>(static_cast<BYTE*>(base) + offset);
}

// The native spot window supplies both the background portraits (23 slots)
// and the same-spot roster. Its scan compares only the spot ID; same-faction
// characters elsewhere in the grid can therefore leak into private rooms.
static BYTE __cdecl sharedSpot(void* peer) {
    void* world = *reinterpret_cast<void**>(0x7ccffc);
    void* own = world ? at<void*>(world, 8) : nullptr;
    if (!own || !peer || !at<BYTE>(own, 0) || !at<BYTE>(peer, 0)) return 0;
    return logh::sameSpotOccupant(at<unsigned>(own, 0x24), at<unsigned>(own, 0x40),
        at<unsigned>(own, 0x44), at<unsigned>(peer, 0x24), at<unsigned>(peer, 0x40),
        at<unsigned>(peer, 0x44)) ? 1 : 0;
}
static unsigned spotAccepted = 0x54ab2b, spotRejected = 0x54aba1;
static BYTE __cdecl privateSpotOwnerPresent() {
    void* world = *reinterpret_cast<void**>(0x7ccffc);
    void* own = world ? at<void*>(world, 8) : nullptr;
    *reinterpret_cast<unsigned*>(0x788ad0) = ~0u; // native owner-name marker
    if (!own || !at<BYTE>(own, 0)) return 0;
    unsigned spot = at<unsigned>(own, 0x40), owner = at<unsigned>(own, 0x44);
    for (int i = 0; i < 600; ++i) {
        void* member = static_cast<BYTE*>(world) + 12 + i * 0x370;
        if (at<BYTE>(member, 0) && logh::spotOwnerPresent(owner, spot,
            at<unsigned>(member, 0x24), at<unsigned>(member, 0x40), at<unsigned>(member, 0x44))) {
            *reinterpret_cast<unsigned*>(0x788ad0) = owner;
            return 1;
        }
    }
    return 0;
}
// Hotel/residence visibility requires the room owner to be present. The CD
// compares the owner's character ID to the room's spot ID, hiding every guest.
static unsigned privateSpotResume = 0x54aaf2;
static void __declspec(naked) filterPrivateSpot() {
    __asm {
        push eax
        push ecx
        push edx
        call privateSpotOwnerPresent
        test al, al
        pop edx
        setnz dl
        pop ecx
        pop eax
        jmp dword ptr [privateSpotResume]
    }
}
static void __declspec(naked) filterSpot() {
    __asm {
        push eax
        push ecx
        push edx
        push esi
        call sharedSpot
        add esp, 4
        test al, al
        pop edx
        pop ecx
        pop eax
        jz rejected
        jmp dword ptr [spotAccepted]
    rejected:
        jmp dword ptr [spotRejected]
    }
}

static bool updateTaxWindow(void* gui);
static bool strategyActionCommand(unsigned command);
static BYTE openStrategyAction(void* manager,int command,unsigned card);
static bool updateStrategyAction(void* gui);
static void taxConditions(void* world);
static void updateOutfitInformation(void* manager);
static void outfitWarehouseResponse(void* world,const BYTE* record);
static void updateCharacterInformation(void* manager);
static bool updateSessionEnding(void* manager);
static void receivedSessionEnding(void* world,const BYTE* record);
#include "system_affiliation.h"
#include "planet_info.h"
#include "outfit_information.h"
#include "outfit_warnings.h"
#include "outfit_editor.h"
#include "docking_information.h"
#include "navigation_status.h"
#include "return_base.h"
#include "planet_tax.h"
#include "strategy_actions.h"
#include "session_ending.h"
#include "appointment_information.h"
#include "supply_selector.h"
#include "unicode_text.h"
#include "spot_labels.h"
#include "character_information.h"
#include "input_editor.h"
#include "peer_notices.h"
#include "tactical_notices.h"
#include "unit_presence.h"
#include "tactical_labels.h"
#include "hud_recovery.h"
#include "hud_text.h"
#include "tactical_command_range.h"
#include "tactical_support.h"
#include "tactical_motion.h"
#include "tactical_weapon.h"
#include "tactical_radar.h"
#include "korean_resources.h"
#include "display_hooks.h"
#include "mail_input.h"
#include "korean_dialogs.h"
#include "literal_text.h"

#include "tactical_base_ui.h"
#include "tactical_departure.h"
#include "environment_shader.h"

// The surviving message catalogue ends before tactical HUD group 119.
// Supply the two known titles only when the native lookup reports NO TABLE;
// preserve a future complete catalogue and every other lookup unchanged.
static const char* __fastcall tacticalHudTitle(void* table, void*, unsigned group, unsigned index) {
    const char* text = reinterpret_cast<const char*(__thiscall*)(void*, unsigned, unsigned)>(0x522010)
        (table, group, index);
    if (!text || strcmp(text, "NO TABLE") || group != 119) return text;
    if (index < 2) return logh::koreanResource(65534,0,index,text);
    return text;
}

// 005035a0 appends chat colour markers through 004eab10. That routine converts
// the entire existing UTF-16 pool to the host ANSI codepage, then decodes it as
// Japanese. Preserve the pool and decode only the incoming Japanese fragment.
static void __cdecl appendTextFragment(void* pool, const char* fragment) {
    std::wstring decoded;
    bool converted = false;
    if (fragment && !strcmp(fragment, peerNoticeMarker) && !pendingPeerNotice.empty()) {
        decoded.swap(pendingPeerNotice);
        converted = true;
    } else converted = logh::tacticalNoticeFragment(fragment, decoded) || logh::japaneseFragment(fragment, decoded);
    if (pool && converted)
        reinterpret_cast<void(__cdecl*)(void*, const wchar_t*)>(0x4eabb0)(pool, decoded.c_str());
}

// Keep the native UTF-16 glyph key through GDI. The CD's Japanese CRT -> ANSI
// GDI boundary otherwise drops Hangul and misdecodes Japanese on Korean Windows.
// These three call sites belong only to the synchronous glyph-cache routine.
static thread_local wchar_t currentGlyph = 0;
static int __cdecl encodeGlyph(char* destination, const wchar_t* source, size_t capacity) {
    if (!destination || !source || capacity != 2) return -1;
    currentGlyph = *source;
    bool narrow = currentGlyph < 128 || (currentGlyph >= 0xff61 && currentGlyph <= 0xff9f);
    // Only the native strlen-based half/full-width branch consumes these bytes.
    // Measurement and rendering below use the actual Unicode glyph, not bytes.
    destination[0] = currentGlyph < 128 ? static_cast<char>(currentGlyph) : narrow ? '?' : static_cast<char>(0x81);
    destination[1] = narrow ? 0 : 0x40;
    return narrow ? 1 : 2;
}
static BOOL WINAPI glyphExtent(HDC dc, LPCSTR, int, LPSIZE size) {
    return GetTextExtentPoint32W(dc, &currentGlyph, 1, size);
}
static BOOL WINAPI glyphOutput(HDC dc, int x, int y, UINT options, const RECT* rectangle, LPCSTR, UINT, const INT* spacing) {
    return ExtTextOutW(dc, x, y, options, rectangle, &currentGlyph, 1, spacing);
}

// Native 004d21c0 sprite layout. Borrow the existing atlas resource each frame;
// the queue stores these descriptors by pointer until the frame is rendered.
struct HudSprite {
    void* texture;
    int textureWidth, textureHeight, x, y, width, height;
    float u0, v0, u1, v1;
};
static_assert(sizeof(HudSprite) == 44, "CD sprite ABI");
static HudSprite hudSprites[5] = {};

// The CD's 004b49d0 initializes only the character ID in its stack record.
// Supply the selected captain card and a fully initialized native record to the
// existing serializer. Balances, eligibility and deadlines remain server-owned.
struct FuelRequest {
    unsigned time, wait, character;
    unsigned short card, padding;
    unsigned pcp, mcp;
};
static_assert(sizeof(FuelRequest) == 24, "CD fuel request ABI");
// Strategy command 60 uses 0b06, not the 0b00 docking record. The CD zeroes
// its captain-card field; fill only that authenticated command's request.
static unsigned switchModeResume = 0x4b4a06;
static void __declspec(naked) __cdecl originalSwitchMode(BYTE) {
    __asm {
        sub esp, 0x164
        jmp dword ptr [switchModeResume]
    }
}
static void __cdecl requestDeparture(BYTE mode) {
    void* world = *reinterpret_cast<void**>(0x7ccffc);
    void* command = *reinterpret_cast<void**>(0xc9e2f8);
    if (!world || mode != 5 || !command || at<int>(command, 0x28) != 60) { originalSwitchMode(mode); return; }
    if (!at<BYTE>(world, 0x2a58f8) || at<BYTE>(world, 0x3579cc)) return;
    void* character = at<void*>(world, 8);
    if (!character || !at<BYTE>(character, 0) ||
        at<void*>(command, 0x1c) != character) return;
    unsigned card = at<unsigned>(command, 0x20);
    if (card != (at<BYTE>(character, 0x28) == 2 ? 59u : 195u)) return;
    BYTE record[0x164] = {};
    at<unsigned>(record, 4) = at<unsigned>(character, 0x24);
    at<unsigned short>(record, 8) = static_cast<unsigned short>(card);
    at<unsigned short>(record, 0x14) = mode;
    reinterpret_cast<unsigned(__thiscall*)(void*, char, unsigned, const void*)>(0x4b78a0)(world, 0, 0x42, record);
}
static void __cdecl requestFuel() {
    void* world = *reinterpret_cast<void**>(0x7ccffc);
    if (!world || !at<BYTE>(world, 0x2a58f8)) return;
    void* character = at<void*>(world, 8);
    void* command = *reinterpret_cast<void**>(0xc9e2f8);
    if (!character || !at<BYTE>(character, 0) || !command || at<int>(command, 0x28) != 44 ||
        at<void*>(command, 0x1c) != character) return;
    int power = at<BYTE>(character, 0x28);
    int card = at<int>(command, 0x20);
    if ((power != 2 || card != 59) && (power != 3 || card != 195)) return;
    FuelRequest record = {};
    record.character = at<unsigned>(character, 0x24);
    record.card = static_cast<unsigned short>(card);
    // Internal selector is one greater than the switch case: 0x52 -> 0x0b02.
    reinterpret_cast<unsigned(__thiscall*)(void*, char, unsigned, const void*)>(0x4b78a0)(world, 0, 0x52, &record);
}

static void __fastcall suppliedFuel(void* world, void*, void* message) {
    // 0044c450 has already validated the received count and decoded alignment.
    if (!at<BYTE>(world, 0x3579cc) && at<BYTE>(world, 0x2a58f8)) {
        unsigned count = at<BYTE>(message, 12);
        if (count <= 70) {
            for (unsigned i = 0; i < count; ++i) {
                unsigned id = at<unsigned>(message, 16 + i * 8);
                unsigned cruise = at<unsigned>(message, 20 + i * 8);
                for (int slot = 0; slot < 600; ++slot) {
                    BYTE* character = static_cast<BYTE*>(world) + 12 + slot * 0x370;
                    if (at<BYTE>(character, 0) && at<unsigned>(character, 0x48) == id &&
                        at<unsigned>(character, 0x318) == id) {
                        at<float>(character, 0x36c) = static_cast<float>(cruise);
                        break;
                    }
                }
            }
        }
    }
    reinterpret_cast<void(__thiscall*)(void*, void*)>(0x4c0860)(world, message);
}

static void drawHudIcon(int slot, int atlasX, int atlasY, float x, float y, unsigned color) {
    auto panel = *reinterpret_cast<HudSprite**>(0x9d2ed0);
    if (!panel || !panel->texture || panel->textureWidth != 1024 || panel->textureHeight != 1024) return;
    auto& sprite = hudSprites[slot];
    sprite = *panel;
    sprite.x = atlasX; sprite.y = atlasY; sprite.width = sprite.height = 33;
    reinterpret_cast<void(__thiscall*)(void*)>(0x4d2030)(&sprite);
    float scale[] = {1, 1, 1};
    reinterpret_cast<int(__thiscall*)(void*, const float*, float, float, float, float, void*, int, unsigned, int, int)>(0x4cb2b0)
        (reinterpret_cast<void*>(0x9d29a0), scale, x, y, 33, 33, &sprite, 0, color, 0, 0);
}

static void __fastcall drawHud(void* map, void*) {
    reinterpret_cast<void(__thiscall*)(void*)>(0x4d5610)(map);
    drawDockingStatus();
    drawSystemAffiliationIcons(map);
    void* world = *reinterpret_cast<void**>(0x7ccffc);
    if (!world || !at<BYTE>(world, 0x2b6a6c)) return;
    int grid = at<int>(world, 0x2b6a70);
    if (grid < 0 || grid >= 5000) return;
    auto type = reinterpret_cast<BYTE*(__stdcall*)(int, int)>(0x4c8b70)(grid % 100, grid / 100);
    if (!type || type[1] != 3 || type[2] > 6) return;
    BYTE* bases[4]; int count = 0;
    for (int i = 0; i < 350; ++i) {
        BYTE* base = static_cast<BYTE*>(world) + 0x2eb800 + i * 0x250;
        if (!at<BYTE>(base, 0) || at<int>(base, 8) != 2 || at<unsigned short>(base, 0x10) != grid) continue;
        if (count == 4) return; // The original HUD has four orbit slots.
        bases[count++] = base;
    }
    // Order by the actual received orbit radius, independent of catalogue order.
    for (int i = 1; i < count; ++i) {
        BYTE* base = bases[i]; int j = i;
        while (j && at<float>(bases[j - 1], 0x34) > at<float>(base, 0x34)) {
            bases[j] = bases[j - 1]; --j;
        }
        bases[j] = base;
    }
    void* display = *reinterpret_cast<void**>(0x7c1b4c);
    if (!display) return;
    int width = at<int>(display, 0x2a604), height = at<int>(display, 0x2a608);
    if (width < 743 || width > 4096 || height < 600 || height > 4096) return;
    float left = static_cast<float>(width - 743), top = static_cast<float>(height - 201);
    const unsigned stars[] = {0xff809fff,0xffb0c8ff,0xffeeeeff,0xfffff4d0,0xffffdf70,0xffffa050,0xffff6060};
    drawHudIcon(0, 758, 99, left + 381, top + 47, stars[type[2]]);
    // Measured at atlas y=263: place the body's centre on each orbit stroke,
    // rather than in the gaps. The 33-pixel icon cells have centre (16,16).
    for (int i = 0; i < count; ++i) {
        int x = 758, y = 0; // Neutral silhouette for an unmapped model, never another body's image.
        unsigned color = 0xff909090;
        if (logh::hudIconRect(at<unsigned short>(bases[i], 0x12), x, y)) color = 0xffffffff;
        drawHudIcon(i + 1, x, y, left + logh::orbitStrokeX[i] - 16, top + 47, color);
    }
}

static void hideModels() {
    auto models = reinterpret_cast<void**>(0x9d2f74);
    for (int i = 0; i < 8; ++i) if (models[i]) at<int>(models[i], 0xb0) = 1;
}

// The original manager owns both resources and instance wrappers. Rebind its
// existing eight slots instead of accumulating an instance on every warp.
static void** managedPair(unsigned listOffset, void* value) {
    void* manager = reinterpret_cast<void*>(0x982fb0);
    auto head = at<void**>(manager, listOffset);
    if (!head) return nullptr;
    auto node = static_cast<void**>(head[0]);
    for (int i = 0; node && node != head && i < 4096; ++i) {
        auto pair = static_cast<void**>(node[2]);
        if (pair && pair[0] == value) return pair;
        node = static_cast<void**>(node[0]);
    }
    return nullptr;
}

static void* typedModel(int slot, unsigned model) {
    struct Resource { void* value; void** owner; void* file; };
    static Resource resources[130] = {};
    if (model >= 130) return nullptr;
    const char* path = reinterpret_cast<const char**>(0x775310)[model];
    if (!path) return nullptr; // No unrelated model fallback for unsupported IDs.
    // These table entries carry a virtual-root slash. The strategy resource
    // loader accepts paths relative to exe/, as its own pNNN_low calls do.
    if (strncmp(path, "/../data/model/Planets/", 23)) return nullptr;
    ++path;
    auto models = reinterpret_cast<void**>(0x9d2f74);
    auto pair = managedPair(0x20, models[slot]);
    if (!models[slot] || !pair) return nullptr;
    auto& resource = resources[model];
    // Never dereference cached scene objects after the native manager unloads them.
    auto owner = resource.value ? managedPair(0x0c, resource.value) : nullptr;
    if (!owner || owner != resource.owner || owner[1] != resource.file) {
        resource = {};
        resource.value = reinterpret_cast<void*(__thiscall*)(void*, const char*)>(0x4d1db0)
            (reinterpret_cast<void*>(0x982fb0), path);
        if (!resource.value) return nullptr;
        resource.owner = managedPair(0x0c, resource.value);
        if (!resource.owner) return nullptr;
        resource.file = resource.owner[1];
    }
    if (at<int>(resource.value, 0x2c) != 0)
        reinterpret_cast<void(__cdecl*)(void*, int)>(0x5e56c0)(resource.value, 0);
    if (at<void*>(models[slot], 8) != resource.value) {
        bool ok = reinterpret_cast<bool(__thiscall*)(void*, void*, int)>(0x4d1710)(pair, resource.value, 10);
        // This routine releases the previous instance, including on failure.
        models[slot] = pair[0];
        if (!ok) return nullptr;
    }
    return models[slot];
}

// Replacement for 004d68d0: the CD function always draws four dummy planets.
// Read the consumed 031d catalogue; never create, move or edit world entities.
static void __fastcall drawLayout(void* map, void*, char) {
    hideModels();
    void* world = *reinterpret_cast<void**>(0x7ccffc);
    if (!world || !at<BYTE>(world, 0x2b6a6c)) return;
    int ownGrid = at<int>(world, 0x2b6a70);
    if (ownGrid < 0 || ownGrid >= 5000) return;
    auto kind = reinterpret_cast<int(__cdecl*)(int, int)>(0x4d35b0);
    int mode = at<int>(map, 4);
    if (ownGrid != lastGrid) {
        lastGrid = ownGrid;
        autoViewAt = GetTickCount64() + 750;
    }
    if (autoViewAt && GetTickCount64() >= autoViewAt && (mode == 1 || mode == 4)) {
        autoViewAt = 0;
        at<int>(map, 0x18) = ownGrid % 100;
        at<int>(map, 0x1c) = ownGrid / 100;
        auto setMode = reinterpret_cast<void(__thiscall*)(void*, int)>(0x4d5030);
        // A persisted room must resume its native background, rather than the system map.
        int spot = at<int>(world, 0x4c); // own character slot +0x0c, spot +0x40
        setMode(map, spot > 0 ? 8 : (kind(ownGrid % 100, ownGrid / 100) == 3 ? 4 : 1));
        at<int>(map, 0x18) = ownGrid % 100;
        at<int>(map, 0x1c) = ownGrid / 100;
        float center[3] = {};
        reinterpret_cast<void(__cdecl*)(float*, int, int)>(0x4d3540)(center, ownGrid % 100, ownGrid / 100);
        memcpy(reinterpret_cast<void*>(0xc5153c), center, sizeof(center));
        reinterpret_cast<void(__cdecl*)()>(0x4f6ee0)();
        mode = at<int>(map, 4);
    }
    // Destination selection must keep the client's normal input and range drawing.
    if (mode != 1 && mode != 4) return;
    int x = at<int>(map, 0x18), y = at<int>(map, 0x1c);
    if (x < 0 || x >= 100 || y < 0 || y >= 50 || kind(x, y) != 3) {
        x = ownGrid % 100; y = ownGrid / 100;
    }
    if (kind(x, y) != 3) return;
    int grid = y * 100 + x;
    BYTE* bases[8]; int count = 0; float maxRadius = 0;
    for (int i = 0; i < 350; ++i) {
        BYTE* base = static_cast<BYTE*>(world) + 0x2eb800 + i * 0x250;
        if (!at<BYTE>(base, 0) || at<int>(base, 8) != 2 || at<unsigned short>(base, 0x10) != grid) continue;
        if (count == 8) return; // Fail closed rather than silently truncating a new catalogue.
        float radius = at<float>(base, 0x34), angle = at<float>(base, 0x40);
        if (!_finite(radius) || !_finite(angle) || radius < 0) return;
        bases[count++] = base;
        if (radius > maxRadius) maxRadius = radius;
    }
    if (!count) return;
    float center[3] = {};
    reinterpret_cast<void(__cdecl*)(float*, int, int)>(0x4d3540)(center, x, y);
    auto models = reinterpret_cast<void**>(0x9d2f74);
    float extent = mode == 4 ? 1.25f : 1.0f;
    for (int i = 0; i < count; ++i) {
        float radius = maxRadius > 0 ? at<float>(bases[i], 0x34) / maxRadius * extent : 0;
        float angle = at<float>(bases[i], 0x40) * 0.017453292519943295f;
        float size[3] = {radius, 0, radius};
        reinterpret_cast<void(__thiscall*)(void*, const float*, const float*, int, int, unsigned, int, int)>(0x4cdf10)
            (reinterpret_cast<void*>(0x9d1320), center, size, 48, 1, 0xff8090a0, 0, 0);
        if (!typedModel(i, at<unsigned short>(bases[i], 0x12))) continue;
        float matrix[16] = {};
        float diameter = at<float>(bases[i], 0x44);
        if (!_finite(diameter) || diameter <= 0) continue;
        if (diameter > 4) diameter = 4;
        matrix[0] = matrix[5] = matrix[10] = diameter * (mode == 4 ? 0.0125f : 0.0045f);
        matrix[15] = 1;
        matrix[12] = center[0] + cosf(angle) * radius;
        matrix[13] = center[1];
        matrix[14] = center[2] + sinf(angle) * radius;
        void* object = at<void*>(models[i], 4);
        if (!object) continue;
        at<int>(object, 0xdc) = 1;
        reinterpret_cast<void(__cdecl*)(void*, const float*)>(0x5dd480)(static_cast<BYTE*>(object) + 0x18, matrix);
        at<int>(models[i], 0xc0) = 10;
        at<int>(models[i], 0xb0) = 0;
    }
}

static void install() {
    if (InterlockedCompareExchange(&status, 1, 0)) return;
    if (!verifiedClient()) { status = -1; return; }
    if (!installEnvironmentShader()) { status = -3; return; }
    if (!verifyTacticalNotices()) { status = -2; return; }
    if (!installSystemAffiliation()) { status = -2; return; }
    BYTE* target = reinterpret_cast<BYTE*>(0x4d68d0);
    const BYTE expected[] = {0x83,0xec,0x58,0x53,0x56};
    if (memcmp(target, expected, sizeof(expected))) { status = -2; return; }
    auto spotFilter = reinterpret_cast<BYTE*>(0x54ab25);
    const BYTE expectedSpotFilter[] = {0x39,0x44,0x24,0x14,0x75,0x76};
    if (memcmp(spotFilter, expectedSpotFilter, sizeof(expectedSpotFilter))) { status = -2; return; }
    auto privateFilter = reinterpret_cast<BYTE*>(0x54aab6);
    const BYTE expectedPrivateFilter[] = {0xa1,0xfc,0xcf,0x7c,0};
    if (memcmp(privateFilter, expectedPrivateFilter, sizeof(expectedPrivateFilter))) { status = -2; return; }
    BYTE* hudCall = reinterpret_cast<BYTE*>(0x4d7e5f);
    const BYTE expectedHudCall[] = {0xe8,0xac,0xd7,0xff,0xff};
    if (memcmp(hudCall, expectedHudCall, sizeof(expectedHudCall))) { status = -2; return; }
    BYTE* fuel = reinterpret_cast<BYTE*>(0x4b49d0);
    const BYTE expectedFuel[] = {0x83,0xec,0x18,0xe8,0xb8};
    BYTE* supplied = reinterpret_cast<BYTE*>(0x4bce39);
    const BYTE expectedSupplied[] = {0xe8,0x22,0x3a,0x00,0x00};
    if (memcmp(fuel, expectedFuel, 5) || memcmp(supplied, expectedSupplied, 5)) { status = -2; return; }
    auto departure = reinterpret_cast<BYTE*>(0x4b4a00);
    const BYTE expectedDeparture[] = {0x81,0xec,0x64,0x01,0x00,0x00};
    if (memcmp(departure, expectedDeparture, sizeof(expectedDeparture))) { status = -2; return; }
    BYTE* glyph = reinterpret_cast<BYTE*>(0x4b0a97);
    const BYTE expectedGlyph[] = {0xe8,0xe0,0xed,0x14,0x00};
    if (memcmp(glyph, expectedGlyph, 5)) { status = -2; return; }
    auto append = reinterpret_cast<BYTE*>(0x5035d3);
    const BYTE expectedAppend[] = {0xe8,0x38,0x75,0xfe,0xff};
    if (memcmp(append, expectedAppend, 5)) { status = -2; return; }
    const unsigned noticeAddresses[] = {0x4c2bc1, 0x4bed3a};
    const BYTE expectedNotices[][5] = {{0xe8,0x4a,0xf4,0x05,0}, {0xe8,0xd1,0x32,0x06,0}};
    for (unsigned i = 0; i < 2; ++i)
        if (memcmp(reinterpret_cast<void*>(noticeAddresses[i]), expectedNotices[i], 5)) { status = -2; return; }
    const unsigned tacticalTitleAddresses[] = {0x513181, 0x513855};
    const BYTE expectedTacticalTitles[][5] = {{0xe8,0x8a,0xee,0,0}, {0xe8,0xb6,0xe7,0,0}};
    for (unsigned i = 0; i < 2; ++i)
        if (memcmp(reinterpret_cast<void*>(tacticalTitleAddresses[i]), expectedTacticalTitles[i], 5)) { status = -2; return; }
    const unsigned glyphAddresses[] = {0x4b0c62, 0x4b0cb5, 0x4b0d29};
    const BYTE expectedGdi[][6] = {{0xff,0x15,0xa0,0xb0,0x66,0}, {0xff,0x15,0xa4,0xb0,0x66,0}, {0xff,0x15,0xa4,0xb0,0x66,0}};
    for (unsigned i = 0; i < 3; ++i)
        if (memcmp(reinterpret_cast<void*>(glyphAddresses[i]), expectedGdi[i], 6)) { status = -2; return; }
    // Dialog buttons and related card conditions request login errors (103).
    // Their matching labels are in group 98. Patch only verified call sites,
    // never the login error catalogue itself.
    const unsigned dialogGroups[] = {0x56f305, 0x56f39b,
        0x4f51df, 0x5786bf, 0x57875d, 0x5787fc, 0x578a24,
        0x57c4fe, 0x57c669, 0x58d3ba};
    const BYTE oldDialogGroup = 103, newDialogGroup = 98;
    for (unsigned address : dialogGroups)
        if (*reinterpret_cast<BYTE*>(address) != oldDialogGroup) { status = -2; return; }
    // Strategy tabs, selection headings and card descriptions share group 96.
    // Group 101 contains system/sound settings, not these card labels.
    const unsigned cardGroups[] = {0x4f5069, 0x4f52a5, 0x4f5344, 0x4f53ed, 0x4f5e62,
        0x4fcb79, 0x4fcc0c, 0x4fcdb6, 0x4fce80};
    const BYTE oldCardGroup = 101, newCardGroup = 96;
    for (unsigned address : cardGroups)
        if (*reinterpret_cast<BYTE*>(address) != oldCardGroup) { status = -2; return; }
    if (!installSessionEnding()) { status = -3; return; }
    if (!installPlanetInformation()) { status = -3; return; }
    if (!installOutfitInformation()) { status = -3; return; }
    if (!installOutfitWarnings()) { status = -3; return; }
    if (!installOutfitEditor()) { status = -3; return; }
    if (!installInputEditor()) { status = -3; return; }
    if (!installSpotLabels()) { status = -3; return; }
    if (!installCharacterInformation()) { status = -3; return; }
    if (!installDockingInformation()) { status = -3; return; }
    if (!installNavigationStatus()) { status = -3; return; }
    if (!installReturnSettings()) { status = -3; return; }
    if (!installTaxWindow()) { status = -3; return; }
    if (!installStrategyActions()) { status = -3; return; }
    if (!installAppointmentInformation()) { status = -3; return; }
    if (!installSupplySelector()) { status = -3; return; }
    if (!installUnitPresence()) { status = -3; return; }
    if (!installTacticalSupport()) { status = -3; return; }
    if (!installTacticalMotion()) { status = -3; return; }
    if (!installTacticalWeapon()) { status = -3; return; }
    if (!installTacticalNotices()) { status = -3; return; }
    if (!installTacticalLabels()) { status = -3; return; }
    if (!installHudRecovery()) { status = -3; return; }
    if (!installHudText()) { status = -3; return; }
    if (!installTacticalCommandRange()) { status = -3; return; }
    BYTE spotJump[6] = {0xe9,0,0,0,0,0x90};
    *reinterpret_cast<LONG*>(spotJump + 1) = static_cast<LONG>(reinterpret_cast<BYTE*>(&filterSpot) - spotFilter - 5);
    if (!patchBytes(spotFilter, expectedSpotFilter, spotJump, sizeof(spotJump))) { status = -3; return; }
    BYTE privateJump[5] = {0xe9};
    *reinterpret_cast<LONG*>(privateJump + 1) = static_cast<LONG>(reinterpret_cast<BYTE*>(&filterPrivateSpot) - privateFilter - 5);
    if (!patchBytes(privateFilter, expectedPrivateFilter, privateJump, sizeof(privateJump))) { status = -3; return; }
    for (unsigned address : dialogGroups)
        if (!patchBytes(reinterpret_cast<void*>(address), &oldDialogGroup, &newDialogGroup, 1)) { status = -3; return; }
    for (unsigned address : cardGroups)
        if (!patchBytes(reinterpret_cast<void*>(address), &oldCardGroup, &newCardGroup, 1)) { status = -3; return; }
    SIZE_T region = hudCall + 5 - target;
    DWORD previous = 0;
    if (!VirtualProtect(target, region, PAGE_EXECUTE_READWRITE, &previous)) { status = -3; return; }
    target[0] = 0xe9;
    *reinterpret_cast<LONG*>(target + 1) = static_cast<LONG>(reinterpret_cast<BYTE*>(&drawLayout) - target - 5);
    *reinterpret_cast<LONG*>(hudCall + 1) = static_cast<LONG>(reinterpret_cast<BYTE*>(&drawHud) - hudCall - 5);
    FlushInstructionCache(GetCurrentProcess(), target, region);
    DWORD ignored = 0; VirtualProtect(target, region, previous, &ignored);
    BYTE jump[5] = {0xe9};
    *reinterpret_cast<LONG*>(jump + 1) = static_cast<LONG>(reinterpret_cast<BYTE*>(&requestFuel) - fuel - 5);
    BYTE call[5] = {0xe8};
    *reinterpret_cast<LONG*>(call + 1) = static_cast<LONG>(reinterpret_cast<BYTE*>(&suppliedFuel) - supplied - 5);
    if (!patchBytes(fuel, expectedFuel, jump, 5) || !patchBytes(supplied, expectedSupplied, call, 5)) { status = -3; return; }
    BYTE departureJump[6] = {0xe9,0,0,0,0,0x90};
    *reinterpret_cast<LONG*>(departureJump + 1) = static_cast<LONG>(reinterpret_cast<BYTE*>(&requestDeparture) - departure - 5);
    if (!patchBytes(departure, expectedDeparture, departureJump, sizeof(departureJump))) { status = -3; return; }
    *reinterpret_cast<LONG*>(call + 1) = static_cast<LONG>(reinterpret_cast<BYTE*>(&encodeGlyph) - glyph - 5);
    if (!patchBytes(glyph, expectedGlyph, call, 5)) { status = -3; return; }
    *reinterpret_cast<LONG*>(call + 1) = static_cast<LONG>(reinterpret_cast<BYTE*>(&appendTextFragment) - append - 5);
    if (!patchBytes(append, expectedAppend, call, 5)) { status = -3; return; }
    for (unsigned i = 0; i < 2; ++i) {
        auto address = reinterpret_cast<BYTE*>(noticeAddresses[i]);
        auto function = i ? reinterpret_cast<BYTE*>(&lookupPeerExit) : reinterpret_cast<BYTE*>(&lookupPeerEntry);
        *reinterpret_cast<LONG*>(call + 1) = static_cast<LONG>(function - address - 5);
        if (!patchBytes(address, expectedNotices[i], call, 5)) { status = -3; return; }
    }
    for (unsigned i = 0; i < 2; ++i) {
        auto address = reinterpret_cast<BYTE*>(tacticalTitleAddresses[i]);
        *reinterpret_cast<LONG*>(call + 1) = static_cast<LONG>(reinterpret_cast<BYTE*>(&tacticalHudTitle) - address - 5);
        if (!patchBytes(address, expectedTacticalTitles[i], call, 5)) { status = -3; return; }
    }
    if (!installTacticalRadar()) { status = -3; return; }
    if (!installKoreanResources()) { status = -3; return; }

    if (!installTacticalBaseUi()) { status = -3; return; }
    if (!installTacticalDeparture()) { status = -3; return; }
    if (!installDisplayNames()) { status = -3; return; }
    if (!installMailInput()) { status = -3; return; }
    if (!installKoreanDialogs() || !installKoreanLiteralText()) { status = -3; return; }
    for (unsigned i = 0; i < 3; ++i) {
        auto address = reinterpret_cast<BYTE*>(glyphAddresses[i]);
        BYTE replacement[6] = {0xe8,0,0,0,0,0x90};
        auto function = i ? reinterpret_cast<BYTE*>(&glyphOutput) : reinterpret_cast<BYTE*>(&glyphExtent);
        *reinterpret_cast<LONG*>(replacement + 1) = static_cast<LONG>(function - address - 5);
        if (!patchBytes(address, expectedGdi[i], replacement, 6)) { status = -3; return; }
    }
    // Correct the strategy HUD group only at its verified call sites.
    const unsigned hudGroups[] = {0x58d561,0x58d570,0x58d57f,0x58d58e,0x58d59d,0x58d5ac,0x58d5bb,
        0x58d5ca,0x58d5d9,0x58d5e8,0x58d6d3,0x58effb,0x58f01e,0x58f038,0x58f053,0x58f070,
        0x58f085,0x58f0e3,0x58f24c,0x58f339,0x58f38d};
    const BYTE oldGroup = 104, newGroup = 99;
    for (unsigned address : hudGroups) patchBytes(reinterpret_cast<void*>(address), &oldGroup, &newGroup, 1);
    // Both HUD paths pass cruising as a double, but the CD format consumes an int.
    patchBytes(reinterpret_cast<void*>(0x78d574), "%-4d\n", "%-4.f", 6);
    status = 2;
}

extern "C" LONG WINAPI LoghPlanetLayoutStatus() { return status; }
extern "C" HRESULT WINAPI ForwardDirectInput8Create(HINSTANCE instance, DWORD version, REFIID iid, LPVOID* output, void* outer) {
    static HMODULE real = nullptr;
    if (!real) {
        wchar_t path[MAX_PATH];
        UINT length = GetSystemDirectoryW(path, MAX_PATH);
        if (!length || length + 13 >= MAX_PATH) return E_FAIL;
        memcpy(path + length, L"\\dinput8.dll", 13 * sizeof(wchar_t));
        real = LoadLibraryW(path);
    }
    if (!real) return E_FAIL;
    auto create = reinterpret_cast<HRESULT(WINAPI*)(HINSTANCE, DWORD, REFIID, LPVOID*, void*)>(GetProcAddress(real, "DirectInput8Create"));
    if (!create) return E_FAIL;
    install();
    return create(instance, version, iid, output, outer);
}
