// Dedicated planet browser. Native GUI and authoritative decoded responses.
// Reconstructed layout; original command dialogs are not borrowed.
// All quantities come from fresh decoded server responses. Author: Peppone Choi.
#pragma once
#include <stdio.h>
#include <stdlib.h>
#include "planet_info_data.h"
#include "planet_units.h"
static bool openStationedOutfit(void* manager,unsigned id,unsigned kind,unsigned power,unsigned number,unsigned base);
static bool stationedOutfitVisible(void* manager);
static bool openOwnOutfit(void* manager);
// The own-outfit window opened from info categories 1/3/5 needs the same per-frame tab driving.
static bool ownOutfitShown=false;
static bool updateStationedOutfit(void* manager);
static void closeStationedOutfit(void* manager);
static bool outfitInformationPending();

static struct PlanetInformation {
    void* world;
    void* popup;
    unsigned character, originGrid, grid, ids[4], count, index, page, unitsRevision, unitReturnPage, unitReplyWait;
    logh::PlanetSystemIndex systems;
    logh::PlanetDirectory directory;
    logh::PlanetPlaces places;
    bool placesReady;
    logh::PlanetOfficeHolder governor,garrison;
    unsigned governorCard,garrisonCard;
    bool governorSent,governorReady,garrisonSent,garrisonReady;
    bool directoryVisible, directoryLocal, tactical;
    unsigned directoryRows[350], directoryCount, directoryPage;
    ULONGLONG directorySentAt;
    bool conditionsReady, warehouseReady, displayed, timeoutShown, detailOpen;
    ULONGLONG requestedAt, acceptAfter;
    BYTE condition[0x180], warehouse[0x300];
} planetInformation = {};

#include "planet_window.h"

// Strategy and tactical views have different ready/grid fields. Queries use the
// same authoritative world cache; never borrow the strategy grid during combat.
static unsigned planetInformationGrid(void* world) {
    if (!world || at<BYTE>(world,0x3579cc)) return 5000;
    auto own=at<void*>(world,8);
    if (!own || !at<BYTE>(own,0)) return 5000;
    if (at<BYTE>(world,0x126718)) {
        unsigned grid=at<unsigned>(own,0x320);
        return grid<5000 ? grid : 5000;
    }
    if (!at<BYTE>(world,0x2a58f8) || !at<BYTE>(world,0x2b6a6c)) return 5000;
    return at<unsigned>(world,0x2b6a70);
}

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
    stopPlanetUnits();
    if (planetInformation.detailOpen) closeStationedOutfit(planetWindowManager);
    planetInformation.detailOpen=false;
    if (planetInformation.popup)
        reinterpret_cast<void(__thiscall*)(void*, int)>(0x502ea0)(planetInformation.popup, 0);
    planetInformation.popup = nullptr;
    planetInformation.world = nullptr;
}

static void requestPlanetInformation() {
    stopPlanetUnits();
    auto& info = planetInformation;
    info.conditionsReady = info.warehouseReady = info.placesReady = info.displayed = false;
    info.places = {};
    info.governor={};info.garrison={};info.governorCard=info.garrisonCard=0;
    info.governorSent=info.governorReady=info.garrisonSent=info.garrisonReady=false;
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
    receiveSystemAffiliation(world);
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
static void __cdecl receivedPlanetGovernor(void* world) {
    auto& info=planetInformation;
    if (!info.popup || info.world!=world || info.directoryVisible || !info.count || !info.governorSent) return;
    if (info.governorSent && info.governor.accept(static_cast<const BYTE*>(world)+0x3d4994,info.governorCard,info.ids[info.index])) {
        info.governorReady=true;info.displayed=false;
    }
    if (info.garrisonSent && info.garrison.accept(static_cast<const BYTE*>(world)+0x3d4994,info.garrisonCard,info.ids[info.index])) {
        info.garrisonReady=true;info.displayed=false;
    }
}
static unsigned placesResume=0x4bdd33;
static void __declspec(naked) planetGovernorCopied() {
    __asm {
        pushfd
        pushad
        push dword ptr [ebp-0x14]
        call receivedPlanetGovernor
        add esp,4
        popad
        popfd
        jmp dword ptr [placesResume]
    }
}
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

static void updatePlanetGovernor() {
    auto& info=planetInformation;
    if (!info.conditionsReady) return;
    auto base=planetBase(info.ids[info.index]);
    auto offices=logh::planetOfficeKinds(base,info.condition[4],info.condition[5]);
    auto send=reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0);
    if (!info.governorSent) {
        info.governorCard=offices.first;info.governorSent=true;
        if (offices.first) { unsigned query=offices.first;send(info.world,0,0x16,&query);return; }
        info.governorReady=true;
    }
    if (!info.governorReady && GetTickCount64()-info.requestedAt<5000) return;
    if (!info.garrisonSent) {
        info.garrisonCard=offices.second;
        info.garrisonSent=true;
        if (offices.second) { unsigned query=offices.second;send(info.world,0,0x16,&query); }
        else info.garrisonReady=true;
    }
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
    if(updateSessionEnding(gui))return -1;
    updateCharacterInformation(gui);
    updateOutfitInformation(gui);
    if (updateTaxWindow(gui)) return -1;
    auto& info = planetInformation;
    void* world = *reinterpret_cast<void**>(0x7ccffc);
    if (planetInformationGrid(world)>=5000) resetPlanetUnitConnection();
    if (ownOutfitShown) {
        if (updateStationedOutfit(gui)) return -1;
        ownOutfitShown=false;
    }
    if (info.popup) {
        auto character = world ? at<void*>(world, 8) : nullptr;
        if (world != info.world || !character || !at<BYTE>(character, 0) ||
            planetInformationGrid(world) != info.originGrid ||
            (at<BYTE>(world,0x126718)!=0) != info.tactical ||
            at<unsigned>(character, 0x24) != info.character)
            closePlanetInformation();
        else {
            if (info.detailOpen) {
                if (updateStationedOutfit(gui)) return -1;
                info.detailOpen=false;
                unsigned page=info.unitReturnPage;
                requestPlanetInformation();info.page=page;
                info.unitReplyWait=outfitInformationPending() ? 1 : 0;
                reinterpret_cast<void(__thiscall*)(void*,int)>(0x506280)(info.popup,12);
                reinterpret_cast<void(__thiscall*)(void*,void*)>(0x50beb0)(gui,info.popup);
                info.acceptAfter=GetTickCount64()+250;
            }
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
            updatePlanetGovernor();
            if (planetWindowTab==4 && info.count) {
                auto own=at<BYTE*>(info.world,8);
                unsigned power=own ? own[0x28] : 0;
                bool pending=outfitInformationPending();
                if (pending && !info.unitReplyWait) {info.unitReplyWait=1;info.displayed=false;}
                if (info.unitReplyWait && !pending) {info.unitReplyWait=0;info.displayed=false;}
                if (info.unitReplyWait==1 && GetTickCount64()-info.requestedAt>=5000) {info.unitReplyWait=2;info.displayed=false;}
                if (!info.unitReplyWait && (power==2 || power==3)) updatePlanetUnits(info.world,info.ids[info.index],info.grid,power);
                unsigned revision=planetUnitsRevision();
                if (revision!=info.unitsRevision) {info.unitsRevision=revision;info.displayed=false;}
            }
            BYTE event[52] = {};
            if (planetWindowTab==4 && GetTickCount64()>=info.acceptAfter) {
                for (unsigned button=72;button<80;++button) {
                    if (!reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,void*,int)>(0x5015f0)
                        (info.popup,2,planetWidget(button),event,0)) continue;
                    logh::PlanetUnitRows::Row row={};unsigned power=0;
                    AcquireSRWLockShared(&planetUnitLock);
                    const auto& q=planetUnitQuery;
                    unsigned index=info.page*8+button-72;
                    if (q.active && q.world==info.world && q.base==info.ids[info.index] && q.phase==3 && index<q.data.count) {
                        row=q.data.rows[index];power=q.power;
                    }
                    ReleaseSRWLockShared(&planetUnitLock);
                    info.acceptAfter=GetTickCount64()+250;
                    if (row.id && openStationedOutfit(gui,row.id,row.kind,power,row.number,info.ids[info.index])) {
                        info.detailOpen=true;info.unitReturnPage=info.page;stopPlanetUnits();
                        reinterpret_cast<void(__thiscall*)(void*,int)>(0x502ea0)(info.popup,0);
                        return -1;
                    }
                    break;
                }
            }
            if (GetTickCount64() >= info.acceptAfter) for (int index = 10; index <= 32; ++index) {
                if (index > 13 && index < 20) continue;
                auto widget = planetWidget(index);
                if (!reinterpret_cast<BYTE(__thiscall*)(void*, int, void*, void*, int)>(0x5015f0)
                    (info.popup, 2, widget, event, 0)) continue;
                info.acceptAfter = GetTickCount64() + 200;
                if (index == 26) { closePlanetInformation(); break; }
                if (index == 30) {stopPlanetUnits();info.directoryVisible=true;rebuildPlanetDirectory();break;}
                if (index <= 13 && static_cast<unsigned>(index - 10) < info.count && info.index != static_cast<unsigned>(index - 10)) {
                    info.index = index - 10; requestPlanetInformation();
                } else if ((index >= 20 && index <= 22) || index == 31 || index == 32) {
                    stopPlanetUnits();
                    planetWindowTab = index >= 31 ? index-28 : index - 20; info.page = 0; info.displayed = false;
                } else if (index == 23 && info.page) { --info.page; info.displayed = false; }
                else if (index == 24 && info.page + 1 < planetStockPages()) { ++info.page; info.displayed = false; }
                else if (index == 25 && info.count) requestPlanetInformation();
                else if (index >= 27 && index <= 29) {
                    unsigned target = index == 29 ? info.originGrid : info.systems.adjacent(info.grid, index == 28);
                    if (target != info.grid && selectPlanetSystem(target)) {
                        if (info.count) requestPlanetInformation();
                        else { stopPlanetUnits();info.displayed = false; info.conditionsReady = info.warehouseReady = false; }
                    }
                }
                break;
            }
            if (info.popup && (!info.displayed || (!info.timeoutShown && (!info.conditionsReady || !info.warehouseReady || !info.placesReady || (info.governorSent && !info.governorReady) || (info.garrisonSent && !info.garrisonReady)) &&
                GetTickCount64() - info.requestedAt >= 5000))) showPlanetInformation();
            return -1;
        }
    }
    int selected = reinterpret_cast<int(__cdecl*)(void*)>(0x54be80)(gui);
    // Categories 1/3/5 (ship, squadron, ground troops) have no CD window; 0054bb50 only deselects them.
    if ((selected == 1 || selected == 3 || selected == 5) && !*reinterpret_cast<void**>(0xc9e2f8)) {
        ownOutfitShown=openOwnOutfit(gui);
        return selected;
    }
    if (selected != 6 || planetInformationGrid(world)>=5000 ||
        *reinterpret_cast<void**>(0xc9e2f8)) return selected;
    auto character = at<void*>(world, 8);
    unsigned grid = planetInformationGrid(world);
    if (!character || grid >= 5000 || !createPlanetWindow(gui)) return selected;
    info = {}; info.world = world; info.character = at<unsigned>(character, 0x24); info.originGrid = grid;
    info.popup = planetWindowRoot; info.tactical=at<BYTE>(world,0x126718)!=0; planetWindowTab = 0;
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
    const unsigned addresses[] = {0x54bc01, 0x4bae89, 0x4babc2, 0x4baeb4, 0x4bb4bd};
    const BYTE expected[][5] = {{0xe8,0x7a,0x02,0,0}, {0xe9,0xa5,0x2e,0,0}, {0xe8,0x89,0x43,0,0}, {0xe9,0x7a,0x2e,0,0}, {0xe9,0x71,0x28,0,0}};
    const void* functions[] = {&planetInfoMenu, &planetConditionsCopied, &receivedPlanetWarehouse, &planetPlacesCopied, &planetGovernorCopied};
    const unsigned gridDecoder=0x41c5f0;
    if (memcmp(reinterpret_cast<void*>(0x66c4b4),&gridDecoder,4)) return false;
    for (unsigned i = 0; i < 5; ++i)
        if (memcmp(reinterpret_cast<void*>(addresses[i]), expected[i], 5)) return false;
    for (unsigned i = 0; i < 5; ++i) {
        auto target = reinterpret_cast<BYTE*>(addresses[i]);
        BYTE replacement[5]; replacement[0] = expected[i][0];
        *reinterpret_cast<LONG*>(replacement + 1) = static_cast<LONG>(static_cast<const BYTE*>(functions[i]) - target - 5);
        if (!patchBytes(target, expected[i], replacement, 5)) return false;
    }
    auto replacement=reinterpret_cast<unsigned>(&decodedPlanetUnitGrid);
    return patchBytes(reinterpret_cast<void*>(0x66c4b4),&gridDecoder,&replacement,4);
}
