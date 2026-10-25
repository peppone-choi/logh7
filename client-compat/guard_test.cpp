// Negative test: loading the proxy in another x86 process must not install game hooks.
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include "hud_atlas.h"
#include "planet_info_data.h"
#include "spot_presence.h"
#include "unicode_text.h"
#include "outfit_info_data.h"
int wmain(int argc, wchar_t** argv) {
    if (argc != 2) return 1;
    unsigned char outfitCache[100 * 32] = {};
    outfitCache[32] = 1; outfitCache[36] = 7;
    outfitCache[96] = 1; outfitCache[100] = 9;
    if (logh::outfitListRow(outfitCache, 0) || logh::outfitListRow(outfitCache, 101) ||
        logh::outfitListRow(outfitCache, 1) != outfitCache + 36 ||
        logh::outfitListRow(outfitCache, 2) != outfitCache + 100 || logh::outfitListRow(outfitCache, 3)) return 23;
    unsigned char party[logh::outfitPartyBytes] = {};
    party[0] = 7; party[4] = 1; party[9] = 2;
    if (!logh::validOutfitParty(party, 7) || logh::validOutfitParty(party, 9)) return 24;
    party[0x180] = 1; party[0x186] = 1; party[0x188] = 44; party[0x18a] = 1; party[0x18c] = 1;
    if (!logh::validOutfitParty(party, 7)) return 24;
    party[0x18a] = 71;
    if (logh::validOutfitParty(party, 7)) return 24;
    party[0x18a] = 1; party[0x4504] = 25;
    if (logh::validOutfitParty(party, 7)) return 24;
    logh::OutfitReplyQueue replies;
    unsigned replyToken = 0;
    if (!replies.add(7,0,1,false) || !replies.add(7,0,2,false) ||
        replies.take(9,1,false,replyToken) || !replies.take(7,1,false,replyToken) || replyToken != 1 ||
        !replies.take(7,1,false,replyToken) || replyToken != 2 || replies.count) return 25;
    if (!replies.add(7,1,3,true) || replies.take(7,2,true,replyToken) ||
        replies.take(7,1,false,replyToken) || !replies.take(7,1,true,replyToken) || replyToken != 3) return 25;
    for (unsigned i = 0; i < 8; ++i) if (!replies.add(7,1,i+4,true)) return 25;
    if (replies.add(7,1,12,true)) return 25;
    // Native chat appends colour controls after its UTF-16 body. The previous
    // ANSI round trip corrupted Hangul already present in that body.
    std::wstring body = L"한글 대화 日本語 ABC";
    std::wstring suffix;
    if (!logh::japaneseFragment("\b\x05", suffix)) return 14;
    body += suffix;
    if (body != L"한글 대화 日本語 ABC\b\x05") return 15;
    if (!logh::japaneseFragment("\x82\xa0", suffix) || suffix != L"あ") return 16;
    if (!logh::japaneseFragment("", suffix) || !suffix.empty()) return 17;
    suffix = L"keep";
    if (logh::japaneseFragment(nullptr, suffix) || suffix != L"keep" ||
        logh::japaneseFragment("\x82", suffix) || suffix != L"keep") return 18;
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
    logh::PlanetSystemIndex systems;
    systems.add(2487); systems.add(7); systems.add(4999); systems.add(2487); systems.add(5000);
    if (systems.count != 3 || systems.position(2487) != 1 || systems.position(8) != 3 ||
        systems.adjacent(2487, true) != 4999 || systems.adjacent(7, false) != 4999 ||
        systems.adjacent(4999, true) != 7 || systems.adjacent(8, true) != 7 ||
        systems.adjacent(8, false) != 4999) return 19;
    logh::PlanetSystemIndex emptySystems;
    if (emptySystems.adjacent(2487, true) != 2487 || emptySystems.adjacent(2487, false) != 2487) return 20;
    logh::PlanetSystemIndex fullSystems;
    for (unsigned i = 350; i; --i) fullSystems.add(i);
    fullSystems.add(4999);
    if (fullSystems.count != 350 || fullSystems.grids[0] != 1 || fullSystems.grids[349] != 350) return 21;
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
    logh::PlanetDirectory directory;
    if (!directory.add(id,grid,67) || !directory.add(other,grid,3) ||
        !directory.add(8000,2015,349) || !directory.add(8001,4000,1) || !directory.add(8002,1000,2) ||
        directory.add(id,grid,2) || directory.add(0,grid,2) || directory.add(9000,5000,2)) return 40;
    unsigned queryIds[4]={},view[350]={};
    if (directory.begin(queryIds)!=4 || directory.pending()!=4 || directory.begin(queryIds) ||
        directory.queried()!=0 || directory.view(view,true,grid)!=2 ||
        directory.rows[view[0]].id!=id || directory.rows[view[0]].slot!=67 ||
        directory.view(view,false,0)!=5 || directory.rows[view[0]].id!=8002) return 41;
    unsigned char reply[0x180]={};
    memcpy(reply,&id,4);unsigned wrongGrid=grid+1;memcpy(reply+8,&wrongGrid,4);
    if (directory.accept(reply)) return 42;
    memcpy(reply+8,&grid,4);reply[4]=3;reply[0x175]=1;
    if (!directory.accept(reply) || directory.accept(reply) || directory.rows[0].population!=0 ||
        directory.rows[0].state!=2 || directory.rows[0].power!=3 || directory.pending()!=3) return 43;
    directory.expire();
    if (directory.pending() || directory.queried()!=4 || directory.rows[1].state!=3 ||
        directory.begin(queryIds)!=1 || queryIds[0]!=8002) return 44;
    memcpy(reply,&other,4);
    if (directory.accept(reply)) return 48; // Timed-out rows cannot accept a late reply.
    directory.cancel();
    if (directory.pending() || directory.queried()!=4 || directory.received()!=1 ||
        directory.begin(queryIds)!=1 || queryIds[0]!=8002) return 47;
    directory.refresh();
    if (directory.queried() || directory.pending() || directory.begin(queryIds)!=4) return 45;
    if (logh::PlanetDirectory::viewRow(287,0,0)!=287 || logh::PlanetDirectory::viewRow(287,0,98)!=97 ||
        logh::PlanetDirectory::viewRow(287,1,1)!=98 || logh::PlanetDirectory::viewRow(287,2,91)!=286 ||
        logh::PlanetDirectory::viewRow(287,2,92)!=287 || logh::PlanetDirectory::viewRow(287,3,1)!=287 ||
        logh::PlanetDirectory::viewRow(4,~0u,1)!=4) return 46;
    logh::PlanetDirectory capacity;
    for (unsigned i=0;i<350;++i) if (!capacity.add(i+1,i,i)) return 49;
    if (capacity.add(351,350,0) || capacity.view(view,true,4999) ||
        capacity.view(view,false,0)!=350 || capacity.rows[view[349]].id!=350 ||
        logh::PlanetDirectory::viewRow(350,3,56)!=349) return 50;
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
