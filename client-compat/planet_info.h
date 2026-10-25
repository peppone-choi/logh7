// Dedicated planet browser. Native GUI and authoritative decoded responses.
// Reconstructed layout; original command dialogs are not borrowed.
// All quantities come from fresh decoded server responses. Author: 최병호.
#pragma once
#include <stdio.h>
#include <stdlib.h>
#include "planet_info_data.h"

static struct PlanetInformation {
    void* world;
    void* popup;
    unsigned character, originGrid, grid, ids[4], count, index, page;
    logh::PlanetSystemIndex systems;
    logh::PlanetDirectory directory;
    logh::PlanetPlaces places;
    bool placesReady;
    bool directoryVisible, directoryLocal;
    unsigned directoryRows[350], directoryCount, directoryPage;
    ULONGLONG directorySentAt;
    bool conditionsReady, warehouseReady, displayed, timeoutShown;
    ULONGLONG requestedAt, acceptAfter;
    BYTE condition[0x180], warehouse[0x300];
} planetInformation = {};

#include "planet_window.h"

static bool selectPlanetSystem(unsigned grid) {
    auto& info = planetInformation;
    unsigned ids[4] = {}, count = 0;
    const BYTE* bases[4] = {};
    for (unsigned slot = 0; slot < 350; ++slot) {
        auto base = static_cast<const BYTE*>(info.world) + 0x2eb800 + slot * 0x250;
        if (!base[0] || logh::infoWord(base, 8) != 2 || logh::infoShort(base, 0x10) != grid) continue;
        if (count == 4) return false;
        float radius; memcpy(&radius, base + 0x34, sizeof(radius));
        unsigned index = count++;
        while (index) {
            float previous; memcpy(&previous, bases[index - 1] + 0x34, sizeof(previous));
            if (previous <= radius) break;
            bases[index] = bases[index - 1]; ids[index] = ids[index - 1]; --index;
        }
        bases[index] = base;
        ids[index] = logh::infoWord(static_cast<const BYTE*>(info.world), 0x2eb288 + slot * 4);
    }
    info.grid = grid; info.count = count; info.index = info.page = 0;
    memcpy(info.ids, ids, sizeof(ids));
    return true;
}

static void closePlanetInformation() {
    if (planetInformation.popup)
        reinterpret_cast<void(__thiscall*)(void*, int)>(0x502ea0)(planetInformation.popup, 0);
    planetInformation.popup = nullptr;
    planetInformation.world = nullptr;
}

static void requestPlanetInformation() {
    auto& info = planetInformation;
    info.conditionsReady = info.warehouseReady = info.placesReady = info.displayed = false;
    info.places = {};
    info.timeoutShown = false;
    info.page = 0;
    info.requestedAt = GetTickCount64();
    info.acceptAfter = info.requestedAt + 200;
    memset(info.condition, 0, sizeof(info.condition));
    memset(info.warehouse, 0, sizeof(info.warehouse));
    // Preserve the original network serializer and encryption. No CP command.
    struct Query { BYTE count, padding[3]; unsigned ids[4]; } query = {};
    query.count = 1; query.ids[0] = info.ids[info.index];
    auto send = reinterpret_cast<unsigned(__thiscall*)(void*, char, unsigned, const void*)>(0x4b78a0);
    send(info.world, 0, 0x21, &query); // 031e -> 031f conditions
    send(info.world, 0, 0x22, &query); // 0320 -> 0321 institutions/spots
    unsigned warehouse[] = {query.ids[0], 0};
    send(info.world, 0, 0x17, warehouse); // 0326 -> 0327 warehouse
}

// Both callbacks run in the native world dispatcher, after its validated decode
// and copy, on the game thread. Never reuse an earlier query cache on opening.
static void __cdecl receivedPlanetConditions(void* world) {
    taxConditions(world);
    auto& info = planetInformation;
    if (!info.popup || info.world != world) return;
    auto response = static_cast<BYTE*>(world) + 0x3facf4;
    if (info.directoryVisible && response[0] <= 4) {
        for (unsigned i = 0; i < response[0]; ++i)
            if (info.directory.accept(response + 4 + i * 0x180)) info.displayed = false;
        return;
    }
    if (!info.count) return;
    auto record = logh::findPlanetCondition(static_cast<BYTE*>(world) + 0x3facf4, info.ids[info.index], info.grid);
    if (record) { memcpy(info.condition, record, sizeof(info.condition)); info.conditionsReady = true; info.displayed = false; }
}
static void __cdecl receivedPlanetPlaces(void* world) {
    auto& info=planetInformation;
    if (!info.popup || info.world!=world || info.directoryVisible || !info.count) return;
    if (info.places.accept(static_cast<const BYTE*>(world)+0x3fb2f8,info.ids[info.index])) {
        info.placesReady=true;info.displayed=false;
    }
}
static unsigned placesResume=0x4bdd33;
static void __declspec(naked) planetPlacesCopied() {
    __asm {
        pushfd
        pushad
        push edx
        call receivedPlanetPlaces
        add esp,4
        popad
        popfd
        jmp dword ptr [placesResume]
    }
}
static unsigned conditionsResume = 0x4bdd33;
static void __declspec(naked) planetConditionsCopied() {
    __asm {
        pushfd
        pushad
        push eax
        call receivedPlanetConditions
        add esp, 4
        popad
        popfd
        jmp dword ptr [conditionsResume]
    }
}
static void __fastcall receivedPlanetWarehouse(void* world, void*, void* message) {
    outfitWarehouseResponse(world,static_cast<const BYTE*>(message));
    auto& info = planetInformation;
    if (info.popup && info.world == world && info.count &&
        logh::matchingPlanetWarehouse(static_cast<BYTE*>(message), info.ids[info.index])) {
        memcpy(info.warehouse, message, sizeof(info.warehouse));
        info.warehouseReady = true;
        info.displayed = false;
    }
    reinterpret_cast<void(__thiscall*)(void*, void*)>(0x4bef50)(world, message);
}

static void showPlanetInformation() {
    renderPlanetWindow();
    planetInformation.displayed = true;
    if (GetTickCount64() - planetInformation.requestedAt >= 5000) planetInformation.timeoutShown = true;
}

static void updatePlanetDirectory() {
    auto& info=planetInformation;
    auto now=GetTickCount64();
    if (info.directory.pending() && now-info.directorySentAt>=5000) {
        info.directory.expire();info.displayed=false;
    }
    if (info.directory.pending() || now-info.directorySentAt<100) return;
    struct Query { BYTE count,padding[3];unsigned ids[4]; } query={};
    query.count=static_cast<BYTE>(info.directory.begin(query.ids));
    if (!query.count) return;
    info.directorySentAt=now;
    reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(info.world,0,0x21,&query);
}

static bool openDirectoryPlanet(unsigned row) {
    auto& info=planetInformation;
    unsigned index=logh::PlanetDirectory::viewRow(info.directoryCount,info.directoryPage,row);
    if (index==info.directoryCount) return false;
    auto entry=info.directory.rows[info.directoryRows[index]];
    if (!selectPlanetSystem(entry.grid)) return false;
    for (unsigned i=0;i<info.count;++i) if (info.ids[i]==entry.id) {
        info.index=i;info.directoryVisible=false;info.directory.cancel();
        requestPlanetInformation();return true;
    }
    return false;
}

static int __cdecl planetInfoMenu(void* gui) {
    updateOutfitInformation(gui);
    if (updateTaxWindow(gui)) return -1;
    auto& info = planetInformation;
    void* world = *reinterpret_cast<void**>(0x7ccffc);
    if (info.popup) {
        auto character = world ? at<void*>(world, 8) : nullptr;
        if (world != info.world || !character || !at<BYTE>(character, 0) || !at<BYTE>(world, 0x2a58f8) ||
            at<BYTE>(world, 0x3579cc) || !at<BYTE>(world, 0x2b6a6c) ||
            at<unsigned>(character, 0x24) != info.character || at<unsigned>(world, 0x2b6a70) != info.originGrid)
            closePlanetInformation();
        else {
            if (info.directoryVisible) {
                updatePlanetDirectory();
                BYTE event[52]={};
                if (GetTickCount64()>=info.acceptAfter) {
                    if (reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,void*,int)>(0x5015f0)
                        (info.popup,0xe,planetDirectoryList,event,0)) {
                        unsigned row=logh::infoWord(event,28);
                        if (openDirectoryPlanet(row)) info.acceptAfter=GetTickCount64()+200;
                    }
                    if (info.directoryVisible) for (int i=23;i<=28;++i) {
                        if (!reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,void*,int)>(0x5015f0)
                            (info.popup,2,planetWidget(i),event,0)) continue;
                        info.acceptAfter=GetTickCount64()+200;
                        if (i==26) {closePlanetInformation();break;}
                        if (i==25) {info.directory.refresh();info.directorySentAt=GetTickCount64();info.displayed=false;}
                        else if (i==23 && info.directoryPage) {--info.directoryPage;rebuildPlanetDirectory();}
                        else if (i==24 && (info.directoryPage+1)*logh::PlanetDirectory::pageCapacity<info.directoryCount) {
                            ++info.directoryPage;rebuildPlanetDirectory();
                        } else if (i>=27) {info.directoryLocal=i==27;info.directoryPage=0;rebuildPlanetDirectory();}
                        break;
                    }
                }
                if (info.popup && !info.displayed) showPlanetInformation();
                return -1;
            }
            BYTE event[52] = {};
            if (GetTickCount64() >= info.acceptAfter) for (int index = 10; index <= 31; ++index) {
                if (index > 13 && index < 20) continue;
                auto widget = planetWidget(index);
                if (!reinterpret_cast<BYTE(__thiscall*)(void*, int, void*, void*, int)>(0x5015f0)
                    (info.popup, 2, widget, event, 0)) continue;
                info.acceptAfter = GetTickCount64() + 200;
                if (index == 26) { closePlanetInformation(); break; }
                if (index == 30) {info.directoryVisible=true;rebuildPlanetDirectory();break;}
                if (index <= 13 && static_cast<unsigned>(index - 10) < info.count && info.index != static_cast<unsigned>(index - 10)) {
                    info.index = index - 10; requestPlanetInformation();
                } else if ((index >= 20 && index <= 22) || index == 31) {
                    planetWindowTab = index == 31 ? 3 : index - 20; info.page = 0; info.displayed = false;
                } else if (index == 23 && info.page) { --info.page; info.displayed = false; }
                else if (index == 24 && info.page + 1 < planetStockPages()) { ++info.page; info.displayed = false; }
                else if (index == 25 && info.count) requestPlanetInformation();
                else if (index >= 27 && index <= 29) {
                    unsigned target = index == 29 ? info.originGrid : info.systems.adjacent(info.grid, index == 28);
                    if (target != info.grid && selectPlanetSystem(target)) {
                        if (info.count) requestPlanetInformation();
                        else { info.displayed = false; info.conditionsReady = info.warehouseReady = false; }
                    }
                }
                break;
            }
            if (info.popup && (!info.displayed || (!info.timeoutShown && (!info.conditionsReady || !info.warehouseReady || !info.placesReady) &&
                GetTickCount64() - info.requestedAt >= 5000))) showPlanetInformation();
            return -1;
        }
    }
    int selected = reinterpret_cast<int(__cdecl*)(void*)>(0x54be80)(gui);
    if (selected != 6 || !world || !at<BYTE>(world, 0x2a58f8) || at<BYTE>(world, 0x3579cc) ||
        !at<BYTE>(world, 0x2b6a6c) || *reinterpret_cast<void**>(0xc9e2f8)) return selected;
    auto character = at<void*>(world, 8);
    unsigned grid = at<unsigned>(world, 0x2b6a70);
    if (!character || grid >= 5000 || !createPlanetWindow(gui)) return selected;
    info = {}; info.world = world; info.character = at<unsigned>(character, 0x24); info.originGrid = grid;
    info.popup = planetWindowRoot; planetWindowTab = 0;
    for (unsigned slot = 0; slot < 350; ++slot) {
        auto base = static_cast<BYTE*>(world) + 0x2eb800 + slot * 0x250;
        if (at<BYTE>(base, 0) && at<int>(base, 8) == 2) {
            info.systems.add(at<unsigned short>(base, 0x10));
            info.directory.add(at<unsigned>(world,0x2eb288+slot*4),at<unsigned short>(base,0x10),slot);
        }
    }
    if (!selectPlanetSystem(grid)) { closePlanetInformation(); return selected; }
    info.acceptAfter = GetTickCount64() + 250;
    reinterpret_cast<void(__thiscall*)(void*, int)>(0x506280)(info.popup, 12);
    reinterpret_cast<void(__thiscall*)(void*, void*)>(0x50beb0)(gui, info.popup);
    info.directoryVisible=true;rebuildPlanetDirectory();
    showPlanetInformation();
    return selected;
}

static bool installPlanetInformation() {
    const unsigned addresses[] = {0x54bc01, 0x4bae89, 0x4babc2, 0x4baeb4};
    const BYTE expected[][5] = {{0xe8,0x7a,0x02,0,0}, {0xe9,0xa5,0x2e,0,0}, {0xe8,0x89,0x43,0,0}, {0xe9,0x7a,0x2e,0,0}};
    const void* functions[] = {&planetInfoMenu, &planetConditionsCopied, &receivedPlanetWarehouse, &planetPlacesCopied};
    for (unsigned i = 0; i < 4; ++i)
        if (memcmp(reinterpret_cast<void*>(addresses[i]), expected[i], 5)) return false;
    for (unsigned i = 0; i < 4; ++i) {
        auto target = reinterpret_cast<BYTE*>(addresses[i]);
        BYTE replacement[5]; replacement[0] = expected[i][0];
        *reinterpret_cast<LONG*>(replacement + 1) = static_cast<LONG>(static_cast<const BYTE*>(functions[i]) - target - 5);
        if (!patchBytes(target, expected[i], replacement, 5)) return false;
    }
    return true;
}
