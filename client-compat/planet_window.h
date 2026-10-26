// Dedicated planet browser using the native controls and original frame asset.
// The layout is reconstructed, not an original CD screen implementation.
// Author: 최병호.
#pragma once
static void* planetWindowManager = nullptr;
static void* planetWindowRoot = nullptr;
static unsigned planetWindowTab = 0;
static constexpr int planetWindowId = 0x71;
static constexpr unsigned planetWindowRows = 8;
static int planetDecorationCount = 0;
static const char planetPartsPath[] = "/../data/image/window/wakusei_parts.tga";
static const char planetIconPath[] = "/../data/image/senryaku_panel/senryaku_mainpanel.tga";
static void* planetIcons[1] = {};
static void createPlanetDirectory();
static void renderPlanetDirectory();
// 00503a70 has 150 sprite slots; exceeding it corrupts the native GUI array.
static_assert(1 + 4 * 9 + 8 + 1 < 150, "Planet decoration slot budget");

static void planetButtonArt(void* widget, int width, int height) {
    // 00502fe0 binds a native sliced background to the control itself.
    // It follows native hover/press/disabled state, unlike detached sprites.
    const int sourceX[] = {459, 467, 659};
    const int sizesX[] = {8, width - 16, 8}, sizesY[] = {height / 2, height - height / 2};
    for (int state = 0; state < 4; ++state) {
        unsigned sprites[30] = {};
        // 00506960: state 0 idle, 1 pressed, 2 disabled, 3 pointer-over.
        int sourceY = state == 3 ? 213 : 264;
        for (int row = 0; row < 2; ++row) for (int col = 0; col < 3; ++col) {
            auto part = sprites + (row * 3 + col) * 5;
            part[0] = reinterpret_cast<unsigned>(planetPartsPath);
            part[1] = sourceX[col]; part[2] = row ? sourceY + 50 - sizesY[row] : sourceY;
            part[3] = sizesX[col]; part[4] = sizesY[row];
        }
        reinterpret_cast<void(__thiscall*)(void*, void*, const void*, int, int, int)>(0x502fe0)
            (planetWindowRoot, widget, sprites, 3, 2, state);
    }
}

static void planetPanelArt(int x, int y, int width, int height) {
    // The original planet atlas contains this bordered information field.
    const int sx[] = {48, 51, 560}, sy[] = {340, 343, 380};
    const int widths[] = {3, width - 6, 3}, heights[] = {3, height - 6, 3};
    int top = y;
    for (int row = 0; row < 3; ++row) {
        int left = x;
        for (int col = 0; col < 3; ++col) {
            unsigned sprites[40] = {};
            for (unsigned state = 0; state < 4; ++state) {
                sprites[state * 5] = reinterpret_cast<unsigned>(planetPartsPath);
                sprites[state * 5 + 1] = sx[col]; sprites[state * 5 + 2] = sy[row];
                sprites[state * 5 + 3] = widths[col]; sprites[state * 5 + 4] = heights[row];
            }
            auto piece = reinterpret_cast<void*(__thiscall*)(void*, int, int, const void*)>(0x503a10)
                (planetWindowRoot, 2, planetDecorationCount++, sprites);
            int rect[] = {left, top, 0, widths[col], heights[row]};
            reinterpret_cast<void(__thiscall*)(void*, void*, const int*)>(0x502940)(planetWindowRoot, piece, rect);
            reinterpret_cast<void(__thiscall*)(void*, void*, int)>(0x502eb0)(planetWindowRoot, piece, 1);
            left += widths[col];
        }
        top += heights[row];
    }
}

static void* planetWidget(int index) {
    return reinterpret_cast<void*(__thiscall*)(void*, int, int)>(0x502780)(planetWindowRoot, 1, index);
}
static void planetCell(int index, const wchar_t* text) {
    auto widget = planetWidget(index);
    if (widget) reinterpret_cast<void(__thiscall*)(void*, void*, const wchar_t*)>(0x503560)(planetWindowRoot, widget, text);
}
static void planetEnabled(int index, bool enabled) {
    auto widget = planetWidget(index);
    if (widget) reinterpret_cast<void(__thiscall*)(void*, void*, int)>(0x5024e0)(planetWindowRoot, widget, enabled ? 1 : 0);
}
static void planetSelected(int index, bool selected) {
    // Selection changes colour only. Rebinding sliced art here would allocate
    // resource wrappers repeatedly on every asynchronous response/refresh.
    auto widget = planetWidget(index);
    reinterpret_cast<void(__thiscall*)(void*, void*, float, float, float, float)>(0x502f40)
        (planetWindowRoot, widget, selected ? 0.45f : 1.f, selected ? 0.7f : 1.f, 1.f, 1.f);
}
static void planetRect(int index, int x, int y, int width, int height) {
    int rect[] = {x, y, 0, width, height};
    auto widget = planetWidget(index);
    if (widget) reinterpret_cast<void(__thiscall*)(void*, void*, const int*)>(0x502940)(planetWindowRoot, widget, rect);
}
static void planetControl(int index, int x, int y, int width, int height, const wchar_t* label, bool button) {
    auto root = planetWindowRoot;
    auto widget = reinterpret_cast<void*(__thiscall*)(void*, int, int, const void*)>(0x503a10)
        (root, 1, index, nullptr);
    planetRect(index, x, y, width, height);
    reinterpret_cast<void(__thiscall*)(void*, void*, int)>(0x5024e0)(root, widget, 1);
    reinterpret_cast<void(__thiscall*)(void*, void*, int)>(0x502eb0)(root, widget, 1);
    reinterpret_cast<void(__thiscall*)(void*, void*, int)>(0x5029e0)(root, widget, button ? 2 : 3);
    planetCell(index, label);
    if (button) {
        planetButtonArt(widget, width, height);
        reinterpret_cast<void(__thiscall*)(void*, void*, int)>(0x502b00)(root, widget, 1);
        reinterpret_cast<void(__thiscall*)(void*, void*, int)>(0x502b30)(root, widget, 1);
    }
}
static void planetIcon(unsigned slot, const BYTE* base) {
    if (slot >= _countof(planetIcons) || !planetIcons[slot]) return;
    auto icon = planetIcons[slot];
    reinterpret_cast<void(__thiscall*)(void*, void*, int)>(0x502eb0)(planetWindowRoot, icon, base ? 1 : 0);
    if (!base) return;
    int x = 758, y = 0;
    logh::hudIconRect(logh::infoShort(base, 0x12), x, y);
    unsigned art[] = {reinterpret_cast<unsigned>(planetIconPath), static_cast<unsigned>(x), static_cast<unsigned>(y), 33, 33};
    for (int state = 0; state < 4; ++state)
        reinterpret_cast<void(__thiscall*)(void*, void*, const void*, int)>(0x503920)(planetWindowRoot, icon, art, state);
}
static bool createPlanetWindow(void* manager) {
    if (planetWindowManager == manager && planetWindowRoot &&
        at<void*>(manager, 4 + planetWindowId * 4) == planetWindowRoot) return true;
    if (at<void*>(manager, 4 + planetWindowId * 4)) return false;
    planetWindowManager = manager;
    planetDecorationCount = 0;
    planetWindowRoot = reinterpret_cast<void*(__thiscall*)(void*, int)>(0x50bb40)(manager, planetWindowId);
    if (!planetWindowRoot) return false;
    static const char framePath[] = "/../data/image/window/wakusei_shousai_window.tga";
    unsigned sprites[40] = {};
    for (unsigned i = 0; i < 4; ++i) {
        sprites[i * 5] = reinterpret_cast<unsigned>(framePath);
        sprites[i * 5 + 3] = 700; sprites[i * 5 + 4] = 463;
    }
    auto frame = reinterpret_cast<void*(__thiscall*)(void*, int, int, const void*)>(0x503a10)(planetWindowRoot, 0, 0, sprites);
    int rect[] = {162, 74, 0, 700, 463};
    reinterpret_cast<void(__thiscall*)(void*, void*, const int*)>(0x502940)(planetWindowRoot, frame, rect);
    reinterpret_cast<void(__thiscall*)(void*, void*, int)>(0x5024e0)(planetWindowRoot, frame, 1);
    reinterpret_cast<void(__thiscall*)(void*, void*, int)>(0x502eb0)(planetWindowRoot, frame, 1);
    planetPanelArt(178, 116, 200, 42);
    planetPanelArt(388, 162, 438, 38);
    planetPanelArt(388, 202, 438, 30);
    planetPanelArt(388, 442, 438, 32);
    for (int row = 0; row < 8; ++row) {
        unsigned lineSprites[40] = {};
        for (unsigned state = 0; state < 4; ++state) {
            lineSprites[state * 5] = reinterpret_cast<unsigned>(planetPartsPath);
            lineSprites[state * 5 + 1] = 51; lineSprites[state * 5 + 2] = 340;
            lineSprites[state * 5 + 3] = 432; lineSprites[state * 5 + 4] = 2;
        }
        auto line = reinterpret_cast<void*(__thiscall*)(void*, int, int, const void*)>(0x503a10)
            (planetWindowRoot, 2, planetDecorationCount++, lineSprites);
        int lineRect[] = {391, 258 + row * 26, 0, 432, 2};
        reinterpret_cast<void(__thiscall*)(void*, void*, const int*)>(0x502940)(planetWindowRoot, line, lineRect);
        reinterpret_cast<void(__thiscall*)(void*, void*, int)>(0x502eb0)(planetWindowRoot, line, 1);
    }
    planetControl(0, 184, 91, 610, 24, L"행성·요새", false);
    planetControl(1, 432, 170, 388, 24, L"", false);
    planetControl(2, 184, 460, 188, 45, L"", false);
    planetControl(3, 184, 126, 188, 24, L"성계 내 행성·요새", false);
    for (int i = 0; i < 4; ++i) {
        planetControl(10 + i, 182, 168 + i * 54, 192, 48, L"", true);
        planetControl(100 + i, 194, 180 + i * 54, 174, 24, L"", false);
    }
    const wchar_t* tabs[] = {L"개요", L"환경·내정", L"창고", L"시설·스폿"};
    for (int i = 0; i < 4; ++i) planetControl(i==3 ? 31 : 20+i, 390+i*110, 116, 102, 42, tabs[i], true);
    planetControl(23, 390, 477, 100, 42, L"이전", true);
    planetControl(24, 500, 477, 100, 42, L"다음", true);
    planetControl(25, 610, 477, 100, 42, L"새로고침", true);
    planetControl(26, 720, 477, 100, 42, L"닫기", true);
    planetControl(27, 182, 385, 92, 30, L"< 성계", true);
    planetControl(28, 282, 385, 92, 30, L"성계 >", true);
    planetControl(29, 182, 420, 192, 32, L"현재 위치", true);
    planetControl(30, 182, 460, 192, 42, L"행성 목록", true);
    for (int row = 0; row < 8; ++row) for (int col = 0; col < 4; ++col)
        planetControl(40 + row * 4 + col, 352 + col * 110, 234 + row * 26, 105, 24, L"", false);
    for (int col = 0; col < 4; ++col) planetControl(80 + col, 352 + col * 110, 206, 105, 24, L"", false);
    planetControl(84, 392, 448, 428, 23, L"", false);
    for (unsigned i = 0; i < _countof(planetIcons); ++i) {
        unsigned art[20] = {};
        for (unsigned state = 0; state < 4; ++state) {
            art[state * 5] = reinterpret_cast<unsigned>(planetIconPath);
            art[state * 5 + 1] = 758; art[state * 5 + 3] = art[state * 5 + 4] = 33;
        }
        auto icon = reinterpret_cast<void*(__thiscall*)(void*, int, int, const void*)>(0x503a10)
            (planetWindowRoot, 2, planetDecorationCount++, art);
        int iconRect[] = {392, 164, 0, 33, 33};
        reinterpret_cast<void(__thiscall*)(void*, void*, const int*)>(0x502940)(planetWindowRoot, icon, iconRect);
        planetIcons[i] = icon;
    }
    // Native decoration slots are painted from high to low: allocate the
    // backing surface last so it stays behind borders and the model icon.
    // The original frame's centre is transparent. Give the information surface
    // its own native colour fill so map labels/orbits cannot run through rows.
    auto surface = reinterpret_cast<void*(__thiscall*)(void*, int, int, const void*)>(0x503a10)
        (planetWindowRoot, 2, planetDecorationCount++, nullptr);
    int surfaceRect[] = {178, 106, 0, 650, 421};
    reinterpret_cast<void(__thiscall*)(void*, void*, const int*)>(0x502940)(planetWindowRoot, surface, surfaceRect);
    reinterpret_cast<void(__thiscall*)(void*, void*, int)>(0x502eb0)(planetWindowRoot, surface, 1);
    unsigned fill[45] = {};
    for (int tile = 0; tile < 9; ++tile) {
        fill[tile * 5] = reinterpret_cast<unsigned>(planetPartsPath);
        fill[tile * 5 + 1] = 470; fill[tile * 5 + 2] = 286;
        fill[tile * 5 + 3] = fill[tile * 5 + 4] = 1;
    }
    for (int state = 0; state < 4; ++state)
        reinterpret_cast<void(__thiscall*)(void*, void*, const void*, int, int, int)>(0x502fe0)
            (planetWindowRoot, surface, fill, 3, 3, state);
    reinterpret_cast<void(__thiscall*)(void*, void*, float, float, float, float)>(0x502f40)
        (planetWindowRoot, surface, 0.015f, 0.035f, 0.065f, 0.97f);
    createPlanetDirectory();
    reinterpret_cast<void(__thiscall*)(void*)>(0x503910)(planetWindowRoot);
    reinterpret_cast<void(__thiscall*)(void*, int)>(0x502ea0)(planetWindowRoot, 0);
    return true;
}
static const BYTE* planetBase(unsigned id) {
    auto world = static_cast<BYTE*>(planetInformation.world);
    for (unsigned slot = 0; slot < 350; ++slot)
        if (logh::infoWord(world, 0x2eb288 + slot * 4) == id) return world + 0x2eb800 + slot * 0x250;
    return nullptr;
}
static void planetName(unsigned id, wchar_t (&name)[13]) {
    name[0] = 0;
    auto base = planetBase(id);
    if (base) memcpy(name, base + 0x48, 12 * sizeof(wchar_t));
    name[12] = 0;
}
#include "planet_directory.h"
static unsigned planetStockPages() {
    auto& info = planetInformation;
    if (planetWindowTab==3) return (info.places.count+planetWindowRows-1)/planetWindowRows;
    return (info.warehouse[12] + info.warehouse[0x260] + planetWindowRows - 1) / planetWindowRows;
}
static void planetValue(unsigned row, const wchar_t* label, unsigned value, const wchar_t* suffix = L"") {
    wchar_t text[48]; _snwprintf_s(text, _countof(text), _TRUNCATE, L"%u%ls", value, suffix);
    planetCell(40 + row * 4, label); planetCell(41 + row * 4, text);
}
static void planetPlaceName(unsigned kind, bool spot, wchar_t (&name)[64]) {
    name[0]=0;
    if (kind<(spot?105u:39u)) {
        auto original=reinterpret_cast<const char*(__thiscall*)(void*,int,int)>(0x522010)
            (reinterpret_cast<void*>(0x2217400),spot?74:73,kind);
        if (original) MultiByteToWideChar(932,0,original,-1,name,_countof(name));
    }
    if (!name[0]) wcscpy_s(name,L"이름 미확인");
}
static void renderPlanetPlaces() {
    auto& info=planetInformation;
    const int x[]={392,606},width[]={208,208};
    const wchar_t* headers[]={L"시설",L"스폿"};
    for (int col=0;col<4;++col) {
        planetCell(80+col,col<2?headers[col]:L"");
        if (col<2) planetRect(80+col,x[col],206,width[col],24);
    }
    for (unsigned row=0;row<planetWindowRows;++row) for (unsigned col=0;col<4;++col) {
        int index=40+row*4+col;
        planetCell(index,L"");
        if (col<2) planetRect(index,x[col],234+row*26,width[col],24);
    }
    if (!info.placesReady) {
        planetCell(1,GetTickCount64()-info.requestedAt>=5000 ? L"장소 응답을 확인하지 못했습니다." : L"서버에서 장소 조회 중입니다.");
        return;
    }
    wchar_t text[128];
    unsigned pages=planetStockPages();
    _snwprintf_s(text,_countof(text),_TRUNCATE,L"시설 %u곳 · 장소 %u행 · %u / %u",
        info.places.institutions,info.places.count,pages?info.page+1:0,pages);
    planetCell(1,text);
    if (!info.places.count) planetCell(40,L"등록된 시설이 없습니다.");
    for (unsigned row=0;row<planetWindowRows && info.page*planetWindowRows+row<info.places.count;++row) {
        const auto& entry=info.places.rows[info.page*planetWindowRows+row];
        wchar_t name[64];planetPlaceName(entry.institutionKind,false,name);planetCell(40+row*4,name);
        if (entry.spot) planetPlaceName(entry.spotKind,true,name);else wcscpy_s(name,L"등록된 스폿 없음");
        planetCell(41+row*4,name);
    }
    planetCell(84,L"장소 조회 · 이동은 기항 후 장소 메뉴에서 선택");
}
static void planetOffice(unsigned row,const wchar_t* label,const logh::PlanetOfficeHolder& holder,bool ready) {
    auto& info=planetInformation;
    unsigned power=info.condition[4];
    planetCell(40+row*4,label);
    const wchar_t* value=nullptr;
    if (info.condition[5] || (power!=2 && power!=3)) value=L"해당 없음";
    else if (!ready) value=GetTickCount64()-info.requestedAt>=5000 ? L"응답 미확인" : L"조회 중";
    else if (!holder.id) value=L"미임명";
    wchar_t name[48];
    if (!value) {
        _snwprintf_s(name,_countof(name),_TRUNCATE,L"%ls %ls",
            reinterpret_cast<const wchar_t*>(holder.surname),reinterpret_cast<const wchar_t*>(holder.givenName));
        value=holder.surname[0] || holder.givenName[0] ? name : L"이름 미확인";
    }
    planetCell(41+row*4,value);
}
static void renderPlanetWindow() {
    auto& info = planetInformation;
    planetDirectoryLayout(info.directoryVisible);
    if (info.directoryVisible) { renderPlanetDirectory(); return; }
    bool stock = planetWindowTab == 2;
    bool places = planetWindowTab == 3;
    const int xs[] = {392, 578, 640, 724}, widths[] = {180, 58, 78, 80};
    for (int row = 0; row < 8; ++row) for (int col = 0; col < 4; ++col) {
        int index = 40 + row * 4 + col;
        planetCell(index, L"");
        planetRect(index, stock ? xs[col] : (col ? 594 : 392), 234 + row * 26, stock ? widths[col] : 216, 24);
    }
    for (int col = 0; col < 4; ++col) {
        planetCell(80 + col, L""); planetRect(80 + col, xs[col], 206, widths[col], 24);
    }
    planetCell(84, L"");
    const wchar_t* tabs[] = {L"개요", L"환경·내정", L"창고", L"시설·스폿"};
    for (unsigned tab = 0; tab < 4; ++tab) {
        wchar_t label[24];
        _snwprintf_s(label, _countof(label), _TRUNCATE, L"%ls", tabs[tab]);
        planetCell(tab==3 ? 31 : 20+tab, label);
        planetSelected(tab==3 ? 31 : 20+tab, tab == planetWindowTab);
    }
    wchar_t text[160], name[13] = {};
    for (unsigned i = 0; i < 4; ++i) {
        if (i < info.count) {
            planetName(info.ids[i], name);
            planetCell(100 + i, name);
        } else planetCell(100 + i, L"—");
        planetSelected(10 + i, i < info.count && i == info.index);
        planetEnabled(10 + i, i < info.count);
    }
    unsigned system = info.systems.position(info.grid);
    _snwprintf_s(text, _countof(text), _TRUNCATE, L"성계 %u / %u\n궤도 순서 · %u곳",
        system < info.systems.count ? system + 1 : 0, info.systems.count, info.count); planetCell(2, text);
    planetEnabled(27, info.systems.count > 0);
    planetEnabled(28, info.systems.count > 0);
    planetEnabled(29, info.grid != info.originGrid);
    planetEnabled(23, (stock ? info.warehouseReady : places && info.placesReady) && info.page > 0);
    planetEnabled(24, (stock ? info.warehouseReady : places && info.placesReady) && info.page + 1 < planetStockPages());
    planetEnabled(25, info.count > 0);
    planetIcon(0, info.count ? planetBase(info.ids[info.index]) : nullptr);
    if (!info.count) { planetCell(0, L"행성·요새"); planetCell(1, L"거점 없음 · 다른 성계를 선택해 주세요."); return; }
    planetName(info.ids[info.index], name);
    _snwprintf_s(text, _countof(text), _TRUNCATE, L"%ls  ·  %ls", name, places ? L"시설·스폿" : stock ? L"거점 창고" : planetWindowTab ? L"환경·내정" : L"개요");
    planetCell(0, text);
    if (places) { renderPlanetPlaces(); return; }
    if (!(stock ? info.warehouseReady : info.conditionsReady)) {
        planetCell(1, GetTickCount64() - info.requestedAt >= 5000 ? L"응답을 확인하지 못했습니다. 새로고침해 주세요." : L"서버에서 조회 중입니다.");
        return;
    }
    if (stock) {
        unsigned pages = planetStockPages();
        _snwprintf_s(text, _countof(text), _TRUNCATE, L"재고 조회  ·  %u / %u", pages ? info.page + 1 : 0, pages);
        planetCell(1, text);
    } else planetCell(1, planetWindowTab ? L"통치·자원 현황" : L"소속·거주 환경");
    if (!stock) {
        planetCell(80, L"항목"); planetRect(80, 392, 206, 180, 24);
        planetCell(81, L"현재 상태"); planetRect(81, 594, 206, 216, 24);
        auto c = info.condition;
        unsigned power = c[4];
        planetCell(40, L"소속 진영"); planetCell(41, logh::planetAffiliation(power,c[5]));
        if (!planetWindowTab) {
            planetValue(1, L"인구", logh::infoWord(c, 0x120), L" 명");
            planetValue(2, L"성인 인구", logh::infoWord(c, 0x124), L" 명");
            planetCell(52, L"대기"); planetCell(53, logh::planetAtmosphere(c[0x174]));
            planetCell(56, L"거주 가능 여부"); planetCell(57, logh::planetHabitability(c[0x175]));
            auto base=planetBase(info.ids[info.index]);
            unsigned kind=base ? logh::infoShort(base,0x14) : ~0u;
            auto offices=logh::planetOfficeKinds(base,power,c[5]);
            const wchar_t* first=offices.first==56 ? L"제도방위사령관" : offices.first==192 ? L"수도시정관" : L"통치자";
            const wchar_t* second=offices.second==57 ? L"근위병총감" : offices.second==193 ? L"수도방위지휘관" : L"수비대장";
            if (!offices.first) { planetCell(60,first);planetCell(61,L"해당 없음"); }
            else planetOffice(5,first,info.governor,info.governorReady);
            if (!offices.second) { planetCell(64,second);planetCell(65,L"해당 없음"); }
            else planetOffice(6,second,info.garrison,info.garrisonReady);
            planetCell(68,L"거점 구분");planetCell(69,kind==1 ? L"요새" : logh::planetCapitalPower(base) ? L"수도 행성" : kind==0 ? L"행성" : L"미확인");
            planetCell(84,L"민심·치안·세율은 환경·내정에서 확인");
        } else {
            planetValue(1, L"세율", logh::infoShort(c, 0x128), L"%");
            planetValue(2, L"민심", logh::infoShort(c, 0x150)); planetValue(3, L"치안", logh::infoShort(c, 0x152));
            if (info.warehouseReady) {
                planetValue(4, L"군수물자", logh::infoWord(info.warehouse, 0x2f4));
                planetValue(5, L"식량", logh::infoWord(info.warehouse, 0x2f8)); planetValue(6, L"광물", logh::infoWord(info.warehouse, 0x2fc));
            }
            planetCell(84, L"내정 명령은 해당 직무 카드에서 실행합니다.");
        }
        return;
    }
    const wchar_t* headers[] = {L"품목", L"등급", L"유닛", L"척수"};
    for (int i = 0; i < 4; ++i) planetCell(80 + i, headers[i]);
    unsigned ships = info.warehouse[12], troops = info.warehouse[0x260];
    if (!ships && !troops) planetCell(40, L"함선·병력 재고가 없습니다.");
    for (unsigned row = 0; row < planetWindowRows && info.page * planetWindowRows + row < ships + troops; ++row) {
        unsigned item = info.page * planetWindowRows + row;
        bool ship = item < ships;
        auto record = info.warehouse + (ship ? 14 + item * 6 : 0x262 + (item - ships) * 6);
        unsigned kind = logh::infoShort(record, 0), level = record[2], quantity = logh::infoShort(record, 4);
        if (!ship && kind >= 1000 && kind < 1016) kind -= 1000;
        wchar_t label[64] = {};
        if (kind < (ship ? 179u : 11u)) {
            auto original = reinterpret_cast<const char*(__thiscall*)(void*, int, int)>(0x522010)
                (reinterpret_cast<void*>(0x2217400), ship ? 85 : 86, kind);
            if (original) MultiByteToWideChar(932, 0, original, -1, label, _countof(label));
        }
        if (!label[0]) _snwprintf_s(label, _countof(label), _TRUNCATE, L"미등록 품목 %u", kind);
        planetCell(40 + row * 4, label);
        const wchar_t* ranks[] = {L"그린", L"노멀", L"베테랑", L"엘리트"};
        planetCell(41 + row * 4, ship ? L"—" : level < 4 ? ranks[level] : L"?");
        _snwprintf_s(text, _countof(text), _TRUNCATE, L"%u", ship ? level : quantity); planetCell(42 + row * 4, text);
        if (ship) _snwprintf_s(text, _countof(text), _TRUNCATE, L"%u", quantity);
        else wcscpy_s(text, L"—");
        planetCell(43 + row * 4, text);
    }
    _snwprintf_s(text, _countof(text), _TRUNCATE, L"물자 %u  ·  식량 %u  ·  광물 %u",
        logh::infoWord(info.warehouse, 0x2f4), logh::infoWord(info.warehouse, 0x2f8), logh::infoWord(info.warehouse, 0x2fc));
    planetCell(84, text);
}
