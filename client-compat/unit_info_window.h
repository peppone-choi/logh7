// Reconstructed ship information (design-ship-squadron-info.md stage 2): in tactics the info menu's ship category
// shows the selected unit from the CD's own tactical unit table, in the shared command popup. Author: Peppone Choi.
#pragma once
#include "tactical_inspect_data.h"
// inspect is the ship shown instead of the allied command selection: the native state 2 pick, or with browsing
// a detected other-camp ship paged from this panel (button 12). No native state or selection is written for either.
static struct UnitInfoWindow { bool visible; unsigned index; ULONGLONG acceptAfter; unsigned inspect; bool browsing; } unitWindow={};
static logh::TacticalInspectLatch inspectLatch;
// 0050d230 hands 004b3b20 the selection as (count 02216664, unit ids 02215128).
static unsigned selectedUnitCount() {return (std::min)(*reinterpret_cast<unsigned*>(0x2216664),32u);}
static unsigned selectedUnitId(unsigned i) {return reinterpret_cast<unsigned*>(0x2215128)[i];}
// Tactical units: world+0x12671c, 0x9ec each (+4 id, +8 type 1 ship, +0x8bc kind, +0x8d4 normal, +0x8d8 alive,
// +0x950 supplies, +0x954 morale); kind specs at world+0x2c1a78, 0x2a8 each, UTF-16 name at +12.
static const BYTE* tacticalShipRow(const BYTE* world,unsigned row) {
    auto unit=world+0x12671c+row*0x9ec;
    return unit[0] && unit[8]==1 ? unit : nullptr;
}
static const BYTE* tacticalUnit(const BYTE* world,unsigned id) {
    for (unsigned i=0;i<600;++i) {
        auto unit=tacticalShipRow(world,i);
        if (unit && logh::infoWord(unit,4)==id) return unit;
    }
    return nullptr;
}
// taxControl draws each button's bevel as separate category 2 art, so hide it with the control (taxButtonVisible);
// closeUnitWindow restores both for the tax and ground windows that share these buttons.
static void unitWindowButtons(bool shown) {
    for (int i=12;i<=14;++i) taxButtonVisible(i,shown);
}
static void closeUnitWindow() {
    if (unitWindow.visible && taxWindow.root) {
        if (unitWindow.inspect) {taxButtonVisible(10,true);taxButtonVisible(11,true);}
        unitWindowButtons(true);
        reinterpret_cast<void(__thiscall*)(void*,int)>(0x502ea0)(taxWindow.root,0);
    }
    unitWindow.visible=false;
}
static bool showUnitWindow(void* gui,unsigned inspect) {
    taxWindow.manager=gui;
    if (!createTaxWindow()) return false;
    hideGroundArt();
    unitWindow={};unitWindow.visible=true;unitWindow.acceptAfter=GetTickCount64()+250;unitWindow.inspect=inspect;
    unitWindowButtons(false);
    if (inspect) {taxButtonVisible(10,false);taxButtonVisible(11,false);}
    // The allied view keeps button 12 for the enemy lookup. A native pick has nothing to switch to.
    else taxButtonVisible(12,true);
    reinterpret_cast<void(__thiscall*)(void*,int)>(0x506280)(taxWindow.root,12);
    reinterpret_cast<void(__thiscall*)(void*,void*)>(0x50beb0)(gui,taxWindow.root);
    return true;
}
static bool openUnitWindow(void* gui) {
    auto world=*reinterpret_cast<const BYTE**>(0x7ccffc);
    if (!world || !world[0x126718] || !selectedUnitCount() || !tacticalUnit(world,selectedUnitId(0)) || taxWindow.visible || groundWindow.visible) return false;
    return showUnitWindow(gui,0);
}
// Follows the native state 2 pick only. No menu or input path enters state 2 from here (README).
static void syncInspectWindow(void* gui) {
    auto controller=*reinterpret_cast<BYTE**>(0x2215e2c);
    const unsigned id=inspectLatch.next(logh::tacticalInspectShip(controller ? at<unsigned>(controller,4) : 0,
        *reinterpret_cast<const unsigned*>(0x2216680)));
    if (unitWindow.visible && unitWindow.inspect && !unitWindow.browsing && unitWindow.inspect!=id) closeUnitWindow();
    auto world=*reinterpret_cast<const BYTE**>(0x7ccffc);
    if (id && !unitWindow.visible && !taxWindow.visible && !groundWindow.visible && world && world[0x126718] && tacticalUnit(world,id))
        showUnitWindow(gui,id);
}
static logh::TacticalInspectView inspectView(BYTE* world,const BYTE* unit) {
    const auto own=at<BYTE*>(world,8);
    auto ownRecord=own ? ownTacticalRecord(world) : nullptr;
    // Without the viewer's own record the side is unknown: reveal no more than the basic rows.
    const unsigned relation=ownRecord ? liveTacticalRelation(world,ownRecord,logh::infoWord(unit,4),0x1000) : 0x1000;
    return logh::tacticalInspectView(relation,logh::tacticalInspectUndetected(unit,own ? at<unsigned>(own,0x48) : 0));
}
constexpr unsigned tacticalShipRows=600;
static unsigned tacticalShipRowOf(const BYTE* world,unsigned id) {
    if (id) for (unsigned i=0;i<tacticalShipRows;++i) {
        auto unit=tacticalShipRow(world,i);
        if (unit && logh::infoWord(unit,4)==id) return i;
    }
    return tacticalShipRows;
}
// The approved basic view is exactly a detected ship of another camp, so own, allied and undetected ships never page in.
static bool detectedEnemyRow(BYTE* world,unsigned i) {
    auto unit=tacticalShipRow(world,i);
    return unit && inspectView(world,unit)==logh::TacticalInspectView::basic;
}
static unsigned nextDetectedEnemy(BYTE* world,unsigned current,bool forward) {
    if (!at<BYTE*>(world,8) || !ownTacticalRecord(world)) return 0;
    const unsigned found=tacticalShipRowOf(world,current);
    const unsigned start=found<tacticalShipRows ? found : forward ? tacticalShipRows-1 : 0;
    const unsigned row=logh::nextInspectRow(tacticalShipRows,start,forward,[&](unsigned i) {return detectedEnemyRow(world,i);});
    return row<tacticalShipRows ? logh::infoWord(tacticalShipRow(world,row),4) : 0;
}
// An open lookup must not hold a ship that left detection or the table: keep it while detected, else move to the
// next detected enemy, and with none left show the empty lookup text.
static unsigned keepDetectedEnemy(BYTE* world,unsigned current) {
    if (!at<BYTE*>(world,8) || !ownTacticalRecord(world)) return 0;
    const unsigned row=logh::keepInspectRow(tacticalShipRows,tacticalShipRowOf(world,current),[&](unsigned i) {return detectedEnemyRow(world,i);});
    return row<tacticalShipRows ? logh::infoWord(tacticalShipRow(world,row),4) : 0;
}
static bool updateUnitWindow() {
    auto& w=unitWindow;
    if (!w.visible) return false;
    auto world=*reinterpret_cast<BYTE**>(0x7ccffc);
    unsigned count=selectedUnitCount();
    const bool inspecting=w.inspect || w.browsing;
    // A native pick shows one ship; the allied view and the enemy lookup both page.
    const bool pages=!w.inspect || w.browsing;
    if (!world || !world[0x126718] || (!inspecting && !count) || !taxWindow.root || !at<BYTE>(taxWindow.root,4)) {
        // The latch belongs to the native pick; a paged enemy must not suppress a later pick of the same ship.
        if (w.inspect && !w.browsing) inspectLatch.dismiss(w.inspect);
        closeUnitWindow();return false;
    }
    if (w.index>=count) w.index=0;
    if (w.browsing && w.inspect) w.inspect=keepDetectedEnemy(world,w.inspect);
    const unsigned shown=inspecting ? w.inspect : selectedUnitId(w.index);
    auto unit=shown ? tacticalUnit(world,shown) : nullptr;
    const auto view=unit && inspecting ? inspectView(world,unit) : logh::TacticalInspectView::full;
    wchar_t text[160],name[33]={};
    taxText(0,L"함정 정보");
    if (unit && view!=logh::TacticalInspectView::hidden) {
        unsigned kind=logh::infoShort(unit,0x8bc);
        if (kind<200) wcsncpy_s(name,reinterpret_cast<const wchar_t*>(world+0x2c1a78+kind*0x2a8+12),_TRUNCATE);
        unsigned alive=logh::infoWord(unit,0x8d8),normal=logh::infoWord(unit,0x8d4),morale=unit[0x954];
        _snwprintf_s(text,_countof(text),_TRUNCATE,L"%ls #%u",name[0] ? name : L"함정",logh::infoWord(unit,4));taxText(1,text);
        _snwprintf_s(text,_countof(text),_TRUNCATE,L"척수 %u (정상 %u · 손상 %u)",alive,normal,alive>normal ? alive-normal : 0);taxText(2,text);
        if (view==logh::TacticalInspectView::full) {
            // Morale 20 or below is the confused band (TacticalCommandState.confused).
            _snwprintf_s(text,_countof(text),_TRUNCATE,L"사기 %u%ls",morale,morale<=20 ? L" (혼란)" : L"");taxText(3,text);
            // Effective stance (004b5d50, as the capture tool reads it): wire 4 주류, 5 정박, 6 항행, 7 전투 (TacticalShipMode).
            static const wchar_t* const stances[]={L"주류",L"정박",L"항행",L"전투"};
            const BYTE stance=reinterpret_cast<BYTE(__thiscall*)(const void*)>(0x4b5d50)(unit);
            _snwprintf_s(text,_countof(text),_TRUNCATE,L"군수물자 %u%ls%ls",logh::infoWord(unit,0x950),
                stance>=4 && stance<=7 ? L" · 태세 " : L"",stance>=4 && stance<=7 ? stances[stance-4] : L"");taxText(4,text);
        } else {
            taxText(3,L"");taxText(4,L"");
        }
    } else {
        taxText(1,w.browsing && !w.inspect ? L"색적 범위 안의 적 함정이 없습니다." :
            unit ? L"색적 범위 밖의 함정입니다." : L"선택한 유닛 정보를 찾을 수 없습니다.");
        taxText(2,L"");taxText(3,L"");taxText(4,L"");
    }
    if (inspecting) {
        // The two values' stat name is unproven (README), so the label stays as neutral as the evidence.
        float rated=0;unsigned kind=unit ? logh::infoShort(unit,0x8bc) : 200;text[0]=0;
        if (view!=logh::TacticalInspectView::hidden && kind<179 && logh::tacticalInspectRated(world+0x2c1a78+kind*0x2a8,rated))
            _snwprintf_s(text,_countof(text),_TRUNCATE,L"정격 %.1f · 유효 %u",rated,logh::infoWord(unit,0x958));
        taxText(5,text);taxText(6,w.browsing ? L"◀▶로 색적 중인 적 함정을 넘깁니다." : L"");
    } else {
        _snwprintf_s(text,_countof(text),_TRUNCATE,L"선택 %u개 중 %u번째",count,w.index+1);taxText(5,text);
        taxText(6,L"◀▶로 선택한 유닛을 넘깁니다.");
    }
    if (pages) {
        taxText(10,L"◀");taxText(11,L"▶");
        taxEnabled(10,w.browsing || count>1);taxEnabled(11,w.browsing || count>1);
        taxText(12,w.browsing ? L"아군 함정" : L"적 함정");
        // Going back needs a selection to show; losing it mid-lookup does not close the lookup.
        taxEnabled(12,!w.browsing || count>0);
    } else {
        taxText(12,L"");taxEnabled(12,false);
    }
    taxText(15,L"닫기");
    taxText(13,L"");taxText(14,L"");
    for (int i=13;i<=14;++i) taxEnabled(i,false);
    if (GetTickCount64()<w.acceptAfter) return true;
    BYTE event[52]={};
    for (int i : {10,11,12,15}) {
        // A native pick pages nothing, and count may be 0 there.
        if (i!=15 && !pages) continue;
        if (!reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,void*,int)>(0x5015f0)(taxWindow.root,2,taxWidget(1,i),event,0)) continue;
        w.acceptAfter=GetTickCount64()+200;
        if (i==15) {
            if (w.inspect && !w.browsing) inspectLatch.dismiss(w.inspect);
            closeUnitWindow();
        }
        else if (i==12) {
            w.browsing=!w.browsing;w.index=0;
            w.inspect=w.browsing ? nextDetectedEnemy(world,0,true) : 0;
        }
        else if (w.browsing) w.inspect=nextDetectedEnemy(world,w.inspect,i==11);
        else w.index=(w.index+count+(i==10 ? count-1 : 1))%count;
        break;
    }
    return true;
}
