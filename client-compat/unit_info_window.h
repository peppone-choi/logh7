// Reconstructed ship information (design-ship-squadron-info.md stage 2): in tactics the info menu's ship category
// shows the selected unit from the CD's own tactical unit table, in the shared command popup. Author: Peppone Choi.
#pragma once
static struct UnitInfoWindow { bool visible; unsigned index; ULONGLONG acceptAfter; } unitWindow={};
// 0050d230 hands 004b3b20 the selection as (count 02216664, unit ids 02215128).
static unsigned selectedUnitCount() {return (std::min)(*reinterpret_cast<unsigned*>(0x2216664),32u);}
static unsigned selectedUnitId(unsigned i) {return reinterpret_cast<unsigned*>(0x2215128)[i];}
// Tactical units: world+0x12671c, 0x9ec each (+4 id, +8 type 1 ship, +0x8bc kind, +0x8d4 normal, +0x8d8 alive,
// +0x950 supplies, +0x954 morale); kind specs at world+0x2c1a78, 0x2a8 each, UTF-16 name at +12.
static const BYTE* tacticalUnit(const BYTE* world,unsigned id) {
    for (unsigned i=0;i<600;++i) {
        auto unit=world+0x12671c+i*0x9ec;
        if (unit[0] && unit[8]==1 && logh::infoWord(unit,4)==id) return unit;
    }
    return nullptr;
}
static void unitWindowButtons(bool shown) {
    for (int i=12;i<=14;++i) if (auto widget=taxWidget(1,i))
        reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502eb0)(taxWindow.root,widget,shown ? 1 : 0);
}
static void closeUnitWindow() {
    if (unitWindow.visible && taxWindow.root) {
        unitWindowButtons(true);
        reinterpret_cast<void(__thiscall*)(void*,int)>(0x502ea0)(taxWindow.root,0);
    }
    unitWindow.visible=false;
}
static bool openUnitWindow(void* gui) {
    auto world=*reinterpret_cast<const BYTE**>(0x7ccffc);
    if (!world || !world[0x126718] || !selectedUnitCount() || !tacticalUnit(world,selectedUnitId(0)) || taxWindow.visible || groundWindow.visible) return false;
    taxWindow.manager=gui;
    if (!createTaxWindow()) return false;
    hideGroundArt();
    unitWindow={};unitWindow.visible=true;unitWindow.acceptAfter=GetTickCount64()+250;
    unitWindowButtons(false);
    reinterpret_cast<void(__thiscall*)(void*,int)>(0x506280)(taxWindow.root,12);
    reinterpret_cast<void(__thiscall*)(void*,void*)>(0x50beb0)(gui,taxWindow.root);
    return true;
}
static bool updateUnitWindow() {
    auto& w=unitWindow;
    if (!w.visible) return false;
    auto world=*reinterpret_cast<const BYTE**>(0x7ccffc);
    unsigned count=selectedUnitCount();
    if (!world || !world[0x126718] || !count || !taxWindow.root || !at<BYTE>(taxWindow.root,4)) {closeUnitWindow();return false;}
    if (w.index>=count) w.index=0;
    auto unit=tacticalUnit(world,selectedUnitId(w.index));
    wchar_t text[160],name[33]={};
    taxText(0,L"함정 정보");
    if (unit) {
        unsigned kind=logh::infoShort(unit,0x8bc);
        if (kind<200) wcsncpy_s(name,reinterpret_cast<const wchar_t*>(world+0x2c1a78+kind*0x2a8+12),_TRUNCATE);
        unsigned alive=logh::infoWord(unit,0x8d8),normal=logh::infoWord(unit,0x8d4),morale=unit[0x954];
        _snwprintf_s(text,_countof(text),_TRUNCATE,L"%ls #%u",name[0] ? name : L"함정",logh::infoWord(unit,4));taxText(1,text);
        _snwprintf_s(text,_countof(text),_TRUNCATE,L"척수 %u (정상 %u · 손상 %u)",alive,normal,alive>normal ? alive-normal : 0);taxText(2,text);
        // Morale 20 or below is the confused band (TacticalCommandState.confused).
        _snwprintf_s(text,_countof(text),_TRUNCATE,L"사기 %u%ls",morale,morale<=20 ? L" (혼란)" : L"");taxText(3,text);
        // Effective stance (004b5d50, as the capture tool reads it): wire 4 주류, 5 정박, 6 항행, 7 전투 (TacticalShipMode).
        static const wchar_t* const stances[]={L"주류",L"정박",L"항행",L"전투"};
        const BYTE stance=reinterpret_cast<BYTE(__thiscall*)(const void*)>(0x4b5d50)(unit);
        _snwprintf_s(text,_countof(text),_TRUNCATE,L"군수물자 %u%ls%ls",logh::infoWord(unit,0x950),
            stance>=4 && stance<=7 ? L" · 태세 " : L"",stance>=4 && stance<=7 ? stances[stance-4] : L"");taxText(4,text);
    } else {
        taxText(1,L"선택한 유닛 정보를 찾을 수 없습니다.");taxText(2,L"");taxText(3,L"");taxText(4,L"");
    }
    _snwprintf_s(text,_countof(text),_TRUNCATE,L"선택 %u개 중 %u번째",count,w.index+1);taxText(5,text);
    taxText(6,L"◀▶로 선택한 유닛을 넘깁니다.");
    taxText(10,L"◀");taxText(11,L"▶");taxText(15,L"닫기");
    taxText(12,L"");taxText(13,L"");taxText(14,L"");
    taxEnabled(10,count>1);taxEnabled(11,count>1);for (int i=12;i<=14;++i) taxEnabled(i,false);
    if (GetTickCount64()<w.acceptAfter) return true;
    BYTE event[52]={};
    for (int i : {10,11,15}) if (reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,void*,int)>(0x5015f0)(taxWindow.root,2,taxWidget(1,i),event,0)) {
        w.acceptAfter=GetTickCount64()+200;
        if (i==15) closeUnitWindow();
        else w.index=(w.index+count+(i==10 ? count-1 : 1))%count;
        break;
    }
    return true;
}
