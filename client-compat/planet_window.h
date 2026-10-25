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
// 00503a70 has 150 sprite slots; exceeding it corrupts the native GUI array.
static_assert(14 * 6 + 4 * 9 + 8 < 150, "Planet decoration slot budget");

static void planetButtonArt(int x, int y, int width, int height) {
    // Reconstructed read-only browser: use the planet atlas's dark plate.
    // Native font colour is retained, so white labels need a dark background.
    // Keep the original bevel at each edge; crop only the flat centre.
    // Two horizontal bands crop only the flat middle of the 50-pixel plate.
    // Six pieces preserve all four bevels while leaving room for navigation.
    const int sourceX[] = {459, 467, 659}, sourceY[] = {264, 314 - (height - height / 2)};
    const int sizesX[] = {8, width - 16, 8}, sizesY[] = {height / 2, height - height / 2};
    int top = y;
    for (int row = 0; row < 2; ++row) {
        int left = x;
        for (int col = 0; col < 3; ++col) {
            unsigned sprites[40] = {};
            for (unsigned state = 0; state < 4; ++state) {
                sprites[state * 5] = reinterpret_cast<unsigned>(planetPartsPath);
                sprites[state * 5 + 1] = sourceX[col]; sprites[state * 5 + 2] = sourceY[row];
                sprites[state * 5 + 3] = sizesX[col]; sprites[state * 5 + 4] = sizesY[row];
            }
            auto piece = reinterpret_cast<void*(__thiscall*)(void*, int, int, const void*)>(0x503a10)
                (planetWindowRoot, 2, planetDecorationCount++, sprites);
            int rect[] = {left, top, 0, sizesX[col], sizesY[row]};
            reinterpret_cast<void(__thiscall*)(void*, void*, const int*)>(0x502940)(planetWindowRoot, piece, rect);
            reinterpret_cast<void(__thiscall*)(void*, void*, int)>(0x502eb0)(planetWindowRoot, piece, 1);
            left += sizesX[col];
        }
        top += sizesY[row];
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
static void planetRect(int index, int x, int y, int width, int height) {
    int rect[] = {x, y, 0, width, height};
    auto widget = planetWidget(index);
    if (widget) reinterpret_cast<void(__thiscall*)(void*, void*, const int*)>(0x502940)(planetWindowRoot, widget, rect);
}
static void planetControl(int index, int x, int y, int width, int height, const wchar_t* label, bool button) {
    if (button) planetButtonArt(x, y, width, height);
    auto root = planetWindowRoot;
    auto widget = reinterpret_cast<void*(__thiscall*)(void*, int, int, const void*)>(0x503a10)
        (root, 1, index, nullptr);
    planetRect(index, x, y, width, height);
    reinterpret_cast<void(__thiscall*)(void*, void*, int)>(0x5024e0)(root, widget, 1);
    reinterpret_cast<void(__thiscall*)(void*, void*, int)>(0x502eb0)(root, widget, 1);
    reinterpret_cast<void(__thiscall*)(void*, void*, int)>(0x5029e0)(root, widget, button ? 2 : 3);
    planetCell(index, label);
    if (button) {
        reinterpret_cast<void(__thiscall*)(void*, void*, int)>(0x502b00)(root, widget, 1);
        reinterpret_cast<void(__thiscall*)(void*, void*, int)>(0x502b30)(root, widget, 1);
    }
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
    planetPanelArt(178, 116, 160, 42);
    planetPanelArt(348, 162, 478, 38);
    planetPanelArt(348, 202, 478, 30);
    planetPanelArt(348, 442, 478, 32);
    for (int row = 0; row < 8; ++row) {
        unsigned lineSprites[40] = {};
        for (unsigned state = 0; state < 4; ++state) {
            lineSprites[state * 5] = reinterpret_cast<unsigned>(planetPartsPath);
            lineSprites[state * 5 + 1] = 51; lineSprites[state * 5 + 2] = 340;
            lineSprites[state * 5 + 3] = 472; lineSprites[state * 5 + 4] = 2;
        }
        auto line = reinterpret_cast<void*(__thiscall*)(void*, int, int, const void*)>(0x503a10)
            (planetWindowRoot, 2, planetDecorationCount++, lineSprites);
        int lineRect[] = {351, 258 + row * 26, 0, 472, 2};
        reinterpret_cast<void(__thiscall*)(void*, void*, const int*)>(0x502940)(planetWindowRoot, line, lineRect);
        reinterpret_cast<void(__thiscall*)(void*, void*, int)>(0x502eb0)(planetWindowRoot, line, 1);
    }
    planetControl(0, 184, 91, 610, 24, L"행성·요새", false);
    planetControl(1, 352, 170, 468, 24, L"", false);
    planetControl(2, 184, 460, 148, 45, L"", false);
    planetControl(3, 184, 126, 148, 24, L"성계 내 행성·요새", false);
    for (int i = 0; i < 4; ++i) planetControl(10 + i, 182, 168 + i * 54, 152, 48, L"", true);
    const wchar_t* tabs[] = {L"개요", L"환경·내정", L"창고"};
    for (int i = 0; i < 3; ++i) planetControl(20 + i, 350 + i * 158, 116, 150, 42, tabs[i], true);
    planetControl(23, 350, 477, 110, 42, L"이전 재고", true);
    planetControl(24, 468, 477, 110, 42, L"다음 재고", true);
    planetControl(25, 586, 477, 110, 42, L"새로고침", true);
    planetControl(26, 704, 477, 110, 42, L"닫기", true);
    planetControl(27, 182, 385, 72, 30, L"< 성계", true);
    planetControl(28, 262, 385, 72, 30, L"성계 >", true);
    planetControl(29, 182, 420, 152, 32, L"현재 위치", true);
    for (int row = 0; row < 8; ++row) for (int col = 0; col < 4; ++col)
        planetControl(40 + row * 4 + col, 352 + col * 110, 234 + row * 26, 105, 24, L"", false);
    for (int col = 0; col < 4; ++col) planetControl(80 + col, 352 + col * 110, 206, 105, 24, L"", false);
    planetControl(84, 352, 448, 468, 23, L"", false);
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
static unsigned planetStockPages() {
    auto& info = planetInformation;
    return (info.warehouse[12] + info.warehouse[0x260] + planetWindowRows - 1) / planetWindowRows;
}
static void planetValue(unsigned row, const wchar_t* label, unsigned value, const wchar_t* suffix = L"") {
    wchar_t text[48]; _snwprintf_s(text, _countof(text), _TRUNCATE, L"%u%ls", value, suffix);
    planetCell(40 + row * 4, label); planetCell(41 + row * 4, text);
}
static void renderPlanetWindow() {
    auto& info = planetInformation;
    bool stock = planetWindowTab == 2;
    const int xs[] = {352, 584, 650, 734}, widths[] = {226, 60, 78, 80};
    for (int row = 0; row < 8; ++row) for (int col = 0; col < 4; ++col) {
        int index = 40 + row * 4 + col;
        planetCell(index, L"");
        planetRect(index, stock ? xs[col] : (col ? 540 : 352), 234 + row * 26, stock ? widths[col] : 275, 24);
    }
    for (int col = 0; col < 4; ++col) {
        planetCell(80 + col, L""); planetRect(80 + col, xs[col], 206, widths[col], 24);
    }
    planetCell(84, L"");
    const wchar_t* tabs[] = {L"개요", L"환경·내정", L"창고"};
    for (unsigned tab = 0; tab < 3; ++tab) {
        wchar_t label[24];
        _snwprintf_s(label, _countof(label), _TRUNCATE, L"%ls%ls", tab == planetWindowTab ? L"> " : L"", tabs[tab]);
        planetCell(20 + tab, label);
    }
    wchar_t text[160], name[13] = {};
    for (unsigned i = 0; i < 4; ++i) {
        if (i < info.count) {
            planetName(info.ids[i], name);
            _snwprintf_s(text, _countof(text), _TRUNCATE, L"%ls%ls", i == info.index ? L"> " : L"", name);
            planetCell(10 + i, text);
        } else planetCell(10 + i, L"—");
        planetEnabled(10 + i, i < info.count);
    }
    unsigned system = info.systems.position(info.grid);
    _snwprintf_s(text, _countof(text), _TRUNCATE, L"성계 %u / %u\n궤도 순서 · %u곳",
        system < info.systems.count ? system + 1 : 0, info.systems.count, info.count); planetCell(2, text);
    planetEnabled(27, info.systems.count > 0);
    planetEnabled(28, info.systems.count > 0);
    planetEnabled(29, info.grid != info.originGrid);
    planetEnabled(23, stock && info.warehouseReady && info.page > 0);
    planetEnabled(24, stock && info.warehouseReady && info.page + 1 < planetStockPages());
    planetEnabled(25, info.count > 0);
    if (!info.count) { planetCell(0, L"행성·요새"); planetCell(1, L"이곳에는 행성이나 요새가 없습니다. 성계를 선택해 주세요."); return; }
    planetName(info.ids[info.index], name);
    _snwprintf_s(text, _countof(text), _TRUNCATE, L"%ls  ·  %ls", name, stock ? L"거점 창고" : planetWindowTab ? L"환경·내정" : L"개요");
    planetCell(0, text);
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
        planetCell(80, L"항목"); planetRect(80, 352, 206, 180, 24);
        planetCell(81, L"현재 상태"); planetRect(81, 540, 206, 275, 24);
        auto c = info.condition;
        unsigned power = c[4];
        planetCell(40, L"소속 세력"); planetCell(41, power == 2 ? L"은하제국" : power == 3 ? L"자유행성동맹" : power == 0 ? L"중립" : L"기타 세력");
        if (!planetWindowTab) {
            planetValue(1, L"인구", logh::infoWord(c, 0x120), L" 명");
            planetValue(2, L"성인 인구", logh::infoWord(c, 0x124), L" 명");
            planetCell(52, L"대기"); planetCell(53, c[0x174] ? L"있음" : L"없음");
            planetCell(56, L"거주 가능 여부"); planetCell(57, c[0x175] ? L"가능" : L"불가");
            planetValue(5, L"세율", logh::infoShort(c, 0x128), L"%");
            planetValue(6, L"민심", logh::infoShort(c, 0x150)); planetValue(7, L"치안", logh::infoShort(c, 0x152));
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
        planetCell(41 + row * 4, level < 4 ? ranks[level] : L"?");
        _snwprintf_s(text, _countof(text), _TRUNCATE, L"%u", quantity); planetCell(42 + row * 4, text);
        auto spec = static_cast<const BYTE*>(info.world) + 0x2c1a78 + kind * 0x2a8;
        if (ship && kind < 200 && spec[0] && logh::infoShort(spec, 0x218))
            _snwprintf_s(text, _countof(text), _TRUNCATE, L"%u", quantity * logh::infoShort(spec, 0x218));
        else wcscpy_s(text, L"—");
        planetCell(43 + row * 4, text);
    }
    _snwprintf_s(text, _countof(text), _TRUNCATE, L"물자 %u  ·  식량 %u  ·  광물 %u",
        logh::infoWord(info.warehouse, 0x2f4), logh::infoWord(info.warehouse, 0x2f8), logh::infoWord(info.warehouse, 0x2fc));
    planetCell(84, text);
}
