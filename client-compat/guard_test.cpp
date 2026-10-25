// Negative test: loading the proxy in another x86 process must not install game hooks.
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include "hud_atlas.h"
#include "planet_info_data.h"
#include "spot_presence.h"
int wmain(int argc, wchar_t** argv) {
    if (argc != 2) return 1;
    if (!logh::spotOwnerPresent(10000, 10493414, 10000, 10493414, 10000) ||
        logh::spotOwnerPresent(10000, 10493414, 10001, 10493414, 10000) ||
        logh::spotOwnerPresent(10000, 10493414, 10000, 10493414, 10001) ||
        logh::spotOwnerPresent(10000, 10493414, 10000, 10490003, 10000) ||
        logh::spotOwnerPresent(0, 10493414, 0, 10493414, 0) ||
        logh::spotOwnerPresent(10000, 0, 10000, 0, 10000)) return 13;
    // Grid visibility includes friendly characters in other private instances.
    // Neither their background portrait nor their roster entry belongs here.
    if (!logh::sameSpotOccupant(10000, 10490003, 0, 10001, 10490003, 0) ||
        !logh::sameSpotOccupant(10000, 10493414, 10000, 10001, 10493414, 10000) ||
        logh::sameSpotOccupant(10000, 10493414, 10000, 10001, 10493414, 10001) ||
        logh::sameSpotOccupant(10000, 10490003, 0, 10001, 10490001, 0) ||
        logh::sameSpotOccupant(10000, 0, 0, 10001, 0, 0) ||
        logh::sameSpotOccupant(10000, 10490003, 0, 10000, 10490003, 0) ||
        logh::sameSpotOccupant(0, 10490003, 0, 10001, 10490003, 0) ||
        logh::sameSpotOccupant(10000, 10490003, 0, 0, 10490003, 0)) return 12;
    // Known CD model families and variants must not alias unrelated artwork.
    const int icons[][3] = {{0,791,0},{61,824,198},{30,791,99},{90,791,297},
        {102,857,330},{110,923,0},{111,956,0},{112,923,33},{115,923,132}};
    for (const auto& icon : icons) {
        int x = -1, y = -1;
        if (!logh::hudIconRect(icon[0], x, y) || x != icon[1] || y != icon[2]) return 5;
    }
    const unsigned unsupported[] = {2u,32u,103u,116u,119u,129u,130u};
    for (unsigned model : unsupported) {
        int x = -1, y = -1;
        if (logh::hudIconRect(model, x, y) || x != -1 || y != -1) return 6;
    }
    // Decoded replies must identify both the selected base and its grid. A
    // previous target's cache and malformed counts must never populate the UI.
    unsigned char conditions[4 + 4 * 0x180] = {};
    conditions[0] = 2;
    const unsigned id = 1049, grid = 2487, other = 1050;
    memcpy(conditions + 4, &other, 4);
    memcpy(conditions + 4 + 8, &grid, 4);
    memcpy(conditions + 4 + 0x180, &id, 4);
    memcpy(conditions + 4 + 0x180 + 8, &grid, 4);
    if (logh::findPlanetCondition(conditions, id, grid) != conditions + 4 + 0x180 ||
        logh::findPlanetCondition(conditions, id, grid + 1) || logh::findPlanetCondition(conditions, id + 2, grid)) return 7;
    conditions[0] = 5;
    if (logh::findPlanetCondition(conditions, id, grid)) return 8;
    conditions[0] = 0;
    if (logh::findPlanetCondition(conditions, id, grid)) return 8;
    unsigned char warehouse[0x300] = {};
    memcpy(warehouse, &id, 4);
    if (!logh::matchingPlanetWarehouse(warehouse, id) || logh::matchingPlanetWarehouse(warehouse, other)) return 9;
    warehouse[4] = 1;
    if (logh::matchingPlanetWarehouse(warehouse, id)) return 10;
    warehouse[4] = 0; warehouse[12] = 100;
    if (logh::matchingPlanetWarehouse(warehouse, id)) return 11;
    warehouse[12] = 0; warehouse[0x260] = 25;
    if (logh::matchingPlanetWarehouse(warehouse, id)) return 11;
    HMODULE module = LoadLibraryW(argv[1]);
    if (!module) return 2;
    auto status = reinterpret_cast<LONG(WINAPI*)()>(GetProcAddress(module, "LoghPlanetLayoutStatus"));
    auto create = reinterpret_cast<HRESULT(WINAPI*)(HINSTANCE, DWORD, const GUID&, void**, void*)>(GetProcAddress(module, "DirectInput8Create"));
    if (!status || !create || status() != 0) return 3;
    GUID iid = {}; void* input = nullptr;
    HRESULT result = create(GetModuleHandleW(nullptr), 0, iid, &input, nullptr);
    if (status() != -1 || SUCCEEDED(result) || input) return 4;
    FreeLibrary(module);
    return 0;
}
