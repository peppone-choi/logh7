// Native base picker: preserve its IDs/actions and render authoritative Unicode data.
// Author: 최병호.
#pragma once
#include <stdlib.h>
#include "roster_text.h"

static const BYTE* pickerBase(unsigned id) {
    auto world = *reinterpret_cast<BYTE**>(0x7ccffc);
    if (!world || !id) return nullptr;
    for (unsigned slot = 0; slot < 350; ++slot) {
        auto base = world + 0x2eb800 + slot * 0x250;
        if (at<unsigned>(world, 0x2eb288 + slot * 4) == id && at<BYTE>(base, 0) && at<int>(base, 8) == 2) return base;
    }
    return nullptr;
}
static void pickerName(unsigned id, wchar_t (&name)[32]) {
    name[0] = 0;
    auto base = pickerBase(id);
    if (!base) return;
    memcpy(name, base + 0x48, 12 * sizeof(wchar_t));
    name[12] = 0;
}
// Only the three native base-list name calls receive this temporary ASCII token.
// Their CString copies stay valid; the list's conversion consumes the token as UTF-16.
static constexpr char baseLabelPrefix[] = "LOGH7_INTERNAL_BASE_LABEL:";
static const char* __fastcall pickerNameToken(void*, void*, unsigned id) {
    static thread_local char token[48];
    if (!pickerBase(id) || id > 65535) return "-";
    _snprintf_s(token, sizeof(token), _TRUNCATE, "%s%u", baseLabelPrefix, id);
    return token;
}
static const wchar_t* __cdecl pickerListText(const char* text) {
    static thread_local wchar_t name[32];
    if (logh::markedRosterText(text)) {
        static thread_local std::wstring row;
        if (!logh::rosterText(text,row)) row.clear();
        return row.c_str();
    }
    if (text && !strncmp(text, baseLabelPrefix, sizeof(baseLabelPrefix) - 1)) {
        const char* digits = text + sizeof(baseLabelPrefix) - 1;
        unsigned id = 0, count = 0;
        while (*digits >= '0' && *digits <= '9' && count < 5) { id = id * 10 + *digits++ - '0'; ++count; }
        if (count && !*digits && id <= 65535) {
            pickerName(id, name);
            return name[0] ? name : L"자료를 확인할 수 없는 거점";
        }
    }
    return reinterpret_cast<const wchar_t*(__cdecl*)(const char*)>(0x4eac60)(text);
}
static void pickerText(void* selector, unsigned index, const wchar_t* text) {
    auto element = at<BYTE*>(selector, 8 + index * 4);
    if (element && at<BYTE>(element, 8))
        reinterpret_cast<void(__cdecl*)(void*, const wchar_t*, int)>(0x4ea8b0)(element + 0xb04, text, -1);
}
static int __fastcall pickerDetails(void* selector, void*, const BYTE* row) {
    auto world = *reinterpret_cast<BYTE**>(0x7ccffc);
    unsigned id = row ? at<unsigned>(const_cast<BYTE*>(row), 8) : 0;
    auto base = pickerBase(id);
    if (!world || !base) {
        for (unsigned index = 2; index <= 4; ++index) pickerText(selector, index, L"");
        return 0;
    }
    unsigned grid = at<unsigned short>(const_cast<BYTE*>(base), 0x10);
    auto condition = logh::findPlanetCondition(world + 0x3facf4, id, grid);
    auto warehouse = world + 0x3e098c;
    wchar_t name[32] = {}, text[512];
    pickerName(id, name);
    if (!condition) {
        pickerText(selector, 2, name);
        pickerText(selector, 3, L"선택한 거점의 정보를 조회 중입니다.");
        pickerText(selector, 4, L"");
        return 0;
    }
    unsigned power = condition[4];
    const wchar_t* powerName = logh::planetAffiliation(power,condition[5]);
    _snwprintf_s(text, _countof(text), _TRUNCATE, L"%ls\n소속: %ls\n대기: %ls / 거주: %ls", name, powerName,
        logh::planetAtmosphere(condition[0x174]), logh::planetHabitability(condition[0x175]));
    pickerText(selector, 2, text);
    _snwprintf_s(text, _countof(text), _TRUNCATE, L"인구: %u\n성인 인구: %u\n세율: %u%%\n민심: %u / 치안: %u",
        logh::infoWord(condition, 0x120), logh::infoWord(condition, 0x124), logh::infoShort(condition, 0x128),
        logh::infoShort(condition, 0x150), logh::infoShort(condition, 0x152));
    pickerText(selector, 3, text);
    if (logh::matchingPlanetWarehouse(warehouse, id)) {
        _snwprintf_s(text, _countof(text), _TRUNCATE, L"군수물자: %u\n식량: %u\n광물: %u",
            logh::infoWord(warehouse, 0x2f4), logh::infoWord(warehouse, 0x2f8), logh::infoWord(warehouse, 0x2fc));
        pickerText(selector, 4, text);
    } else pickerText(selector, 4, L"선택한 거점의 창고를 조회 중입니다.");
    return 1;
}
// The original selector has 23 shared controls. Base modes reuse card/rank
// captions; appointment person mode also keeps the card-count heading.
// Preserve the native controls and each mode's own actions.
static bool basePicker(void* selector) {
    unsigned kind=at<unsigned>(selector,0x234);
    return kind==5 || kind==17;
}
// The person selector is shared by many commands. Rename captions only for
// the active appointment target step, never for an arbitrary character list.
static bool appointmentPersonPicker(void* selector) {
    if (at<unsigned>(selector,0x234)!=3) return false;
    auto command=*reinterpret_cast<BYTE**>(0xc9e2f8);
    unsigned authority=*reinterpret_cast<unsigned*>(0xc9eac0);
    if (!command || (authority!=54 && authority!=2 && authority!=17 && authority!=134 && authority!=161 && authority!=39 && authority!=175 && authority!=3 && authority!=169 && authority!=171)) return false;
    auto steps=at<BYTE**>(command,0x10),end=at<BYTE**>(command,0x14);
    unsigned active=at<unsigned>(command,4);
    if (!steps || end<steps || end-steps>64 || active>=static_cast<unsigned>(end-steps)) return false;
    auto step=steps[active];
    if (!step || at<unsigned>(step,0)!=0x675738 || at<BYTE>(step,0x18)) return false;
    auto vars=at<BYTE**>(step,0xc),varsEnd=at<BYTE**>(step,0x10);
    if (!vars || varsEnd<vars || varsEnd-vars>16) return false;
    for (auto var=vars;var!=varsEnd;++var) {
        auto name=*var ? at<const char*>(*var,8) : nullptr;
        if (name && !strcmp(name,"TARGET_SELECT_S_CHARACTER")) return true;
    }
    return false;
}
static unsigned pickerCaptionContext(void* selector) {
    return basePicker(selector) ? 1u : appointmentPersonPicker(selector) ? 2u : 0u;
}
static bool pickerLabel(void* selector,unsigned index,wchar_t (&text)[128]) {
    auto element=at<BYTE*>(selector,8+index*4);
    if (!element || !at<BYTE>(element,8)) return false;
    unsigned handle=at<unsigned>(element,0xb04);
    if (handle>=4000) return false;
    if (!handle) {text[0]=0;return true;}
    // 004ea8b0's native UTF-16 pool: handle -> entry -> offset/length.
    int entry=at<int>(reinterpret_cast<void*>(0xbc03a0),handle*4);
    if (entry<0 || entry>=4000) return false;
    unsigned start=at<unsigned>(reinterpret_cast<void*>(0xbc51c8),entry*12);
    unsigned length=at<unsigned>(reinterpret_cast<void*>(0xbc51cc),entry*12);
    if (length>=_countof(text) || start>=30000 || length>=30000-start) return false;
    memcpy(text,reinterpret_cast<void*>(0xbd0d48+start*2),length*sizeof(wchar_t));
    text[length]=0;return true;
}
static void __fastcall updateCommonPicker(void* selector,void*) {
    static void* previousRoot=nullptr;
    static bool applied=false;
    static unsigned previousContext=0;
    static unsigned savedCount=0;
    static wchar_t saved[4][128]={};
    static constexpr unsigned indices[]={12,13,16,21};
    auto root=at<BYTE*>(selector,4);
    if (root!=previousRoot) {previousRoot=root;applied=false;}
    unsigned context=root && at<BYTE>(root,4) ? pickerCaptionContext(selector) : 0;
    bool active=context!=0;
    if (applied && (!active || context!=previousContext)) {
        for (unsigned i=0;i<savedCount;++i) pickerText(selector,indices[i],saved[i]);
        applied=false;
    }
    if (active && applied && context==1 && at<unsigned>(selector,0x238)==4) {
        BYTE event[52]={};
        auto refresh=at<void*>(selector,8+21*4);
        if (reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,void*,int)>(0x5015f0)(root,2,refresh,event,0)) {
            auto row=reinterpret_cast<const BYTE*(__thiscall*)(void*,int)>(0x576ec0)
                (static_cast<BYTE*>(selector)+0x244,at<int>(selector,0xb2c));
            if (row && pickerBase(logh::infoWord(row,8))) {
                // 00579d60 starts these two fields on a row change; 00579fd0
                // then queries 031e followed by 0326. No command/CP request.
                at<unsigned>(selector,0x238)=2;
                at<unsigned>(selector,0x1584)=0;
                pickerText(selector,2,L"선택한 거점의 정보를 다시 조회 중입니다.");
                pickerText(selector,3,L"");pickerText(selector,4,L"");
            }
        }
    }
    reinterpret_cast<void(__thiscall*)(void*)>(0x5794d0)(selector);
    root=at<BYTE*>(selector,4);
    context=root && at<BYTE>(root,4) ? pickerCaptionContext(selector) : 0;
    if (!context) return;
    if (!applied) {
        unsigned count=context==1 ? 4u : 3u;
        for (unsigned i=0;i<count;++i) if (!pickerLabel(selector,indices[i],saved[i])) return;
        const wchar_t* baseLabels[]={L"행성·요새 선택",L"행성·요새",L"선택 조건",L"다시 조회"};
        const wchar_t* personLabels[]={L"임명 대상 선택",L"임명 후보",L"임명 조건"};
        auto labels=context==1 ? baseLabels : personLabels;
        for (unsigned i=0;i<count;++i) pickerText(selector,indices[i],labels[i]);
        applied=true;previousContext=context;savedCount=count;
    }
    // Index 21 is a native sort dropdown in person mode, not a refresh action.
    if (context!=1) return;
    auto refresh=at<void*>(selector,8+21*4);
    bool ready=false;
    if (at<unsigned>(selector,0x238)==4) {
        auto row=reinterpret_cast<const BYTE*(__thiscall*)(void*,int)>(0x576ec0)
            (static_cast<BYTE*>(selector)+0x244,at<int>(selector,0xb2c));
        ready=row && pickerBase(logh::infoWord(row,8));
    }
    reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x5024e0)
        (root,refresh,ready ? 1 : 0);
}
static bool installDockingInformation() {
    const unsigned calls[] = {0x57bc3c, 0x57beae, 0x57d04d, 0x576690, 0x576ab1, 0x576af8, 0x57769c};
    const BYTE expected[][5] = {{0xe8,0x9f,0xd1,0xf4,0xff}, {0xe8,0x2d,0xcf,0xf4,0xff},
        {0xe8,0x8e,0xbd,0xf4,0xff}, {0xe8,0xcb,0x45,0xf7,0xff}, {0xe8,0xaa,0x41,0xf7,0xff},
        {0xe8,0x63,0x41,0xf7,0xff}, {0xe8,0xbf,0x35,0xf7,0xff}};
    const BYTE detail[] = {0x6a,0xff,0x68,0x00,0x6c};
    const BYTE update[]={0xe8,0xb9,0xc1,0x07,0};
    for (unsigned i = 0; i < _countof(calls); ++i)
        if (memcmp(reinterpret_cast<void*>(calls[i]), expected[i], 5)) return false;
    if (memcmp(reinterpret_cast<void*>(0x57aa90), detail, 5)) return false;
    if (memcmp(reinterpret_cast<void*>(0x4fd312),update,5)) return false;
    for (unsigned i = 0; i < _countof(calls); ++i) {
        BYTE replacement[5] = {0xe8};
        auto function = i < 3 ? reinterpret_cast<BYTE*>(&pickerNameToken) : reinterpret_cast<BYTE*>(&pickerListText);
        *reinterpret_cast<LONG*>(replacement + 1) = static_cast<LONG>(function - reinterpret_cast<BYTE*>(calls[i]) - 5);
        if (!patchBytes(reinterpret_cast<void*>(calls[i]), expected[i], replacement, 5)) return false;
    }
    BYTE jump[5] = {0xe9};
    *reinterpret_cast<LONG*>(jump + 1) = static_cast<LONG>(reinterpret_cast<BYTE*>(&pickerDetails) - reinterpret_cast<BYTE*>(0x57aa90) - 5);
    if (!patchBytes(reinterpret_cast<void*>(0x57aa90), detail, jump, 5)) return false;
    BYTE call[5]={0xe8};
    *reinterpret_cast<LONG*>(call+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&updateCommonPicker)-reinterpret_cast<BYTE*>(0x4fd312)-5);
    return patchBytes(reinterpret_cast<void*>(0x4fd312),update,call,5);
}
