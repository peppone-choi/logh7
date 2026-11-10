// Fortress command UI follows the server-issued office and its exact base seat.
// Ground garrison cards do not grant command of the fortress cannon. Author: Peppone Choi.
#pragma once
#include "character_cards.h"
namespace logh {
inline bool commandsFortress(const void* own,size_t bytes,unsigned base) {
    if(!base || base>65535)return false;
    CharacterCards cards;
    if(!CharacterCards::read(own,bytes,cards))return false;
    for(unsigned i=0;i<cards.count;++i)if((cards.rows[i].kind==51 || cards.rows[i].kind==187) && cards.rows[i].target==base)return true;
    return false;
}
// 00422d80 decodes 033f into u16 count + pad, then 60-byte corps rows:
// +0 character, +0x10 supreme-commander flag (server CommandCorps.chief).
// Returns -1 when this character's row is absent, so partial updates keep the old state.
inline int corpsChief(const BYTE* decoded,unsigned character) {
    if(!decoded || !character)return -1;
    unsigned short count=*reinterpret_cast<const unsigned short*>(decoded);
    if(count>600)return -1;
    for(unsigned i=0;i<count;++i) {
        const BYTE* row=decoded+4+i*0x3c;
        if(*reinterpret_cast<const unsigned*>(row)==character)return row[0x10]?1:0;
    }
    return -1;
}
// update04 added the supreme commander's mission (index 20) and emergency supply (23)
// to the commander tab, whose CD table at 00785bc8 is all zero.
// U-31 (user decision 2026-11-02): the empty slot 21 (widget 0x1b, no commander-tab branch in 0050d230)
// becomes the orbital ground attack button for everyone in tactics; the server checks range and supplies.
constexpr unsigned orbitalStrikeSlot=21,orbitalStrikeCommand=230;
inline void commanderMenu(bool chief,BYTE enabled[24]) {
    for(unsigned i=0;i<24;++i)enabled[i]=0;
    if(chief)enabled[20]=enabled[23]=1;
    enabled[orbitalStrikeSlot]=1;
}
// Mission-kind switch (0050f074, state 0x1a). Kind 0 (skirmish) loads 118/14 at 0050f096,
// prepares ECX/stack at 0050f09b..0050f09e like kind 5 (0050f15c..0050f15f), then 0050f0a2
// jumps to the shared recon target selection at 0050f134 (mask 0x1181), so no target ever
// passes and no 0421 is sent. Skirmish needs no target: take kind 5's native no-target tail at
// 0050f163, which calls 004b4850 with the stored kind [esp+0x24], zero target and the selection.
constexpr unsigned skirmishMissionJump=0x50f0a2,skirmishMissionNoTarget=0x50f163;
constexpr BYTE skirmishMissionReconJump[5]={0xe9,0x8d,0,0,0};     // -> 0050f134
constexpr BYTE skirmishMissionNoTargetJump[5]={0xe9,0xbc,0,0,0};  // -> 0050f163
constexpr BYTE skirmishMissionNoTargetTail[11]={0x50,0xe8,0xdb,0xcf,0x13,0,0xe8,0x82,0x8a,0,0};
// The 0x7f00 strategy record (ordinal 0x46) that asks the server for one orbital ground attack.
inline void orbitalStrikeRecord(BYTE record[36],unsigned character,unsigned short token,bool preview,
    unsigned base=0,unsigned attackers=0,unsigned short supplies=0) {
    for(unsigned i=0;i<36;++i)record[i]=0;
    *reinterpret_cast<unsigned*>(record)=character;
    *reinterpret_cast<unsigned short*>(record+4)=0x7f00;
    *reinterpret_cast<unsigned short*>(record+6)=static_cast<unsigned short>(orbitalStrikeCommand);
    *reinterpret_cast<unsigned short*>(record+10)=supplies;
    record[16]=preview ? 1 : 0;
    *reinterpret_cast<unsigned*>(record+20)=attackers;
    *reinterpret_cast<unsigned*>(record+28)=base;
    *reinterpret_cast<unsigned short*>(record+32)=token;
}
}
