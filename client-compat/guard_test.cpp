// Negative test: loading the proxy in another x86 process must not install game hooks.
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include "hud_atlas.h"
#include "planet_info_data.h"
#include "spot_presence.h"
#include "unicode_text.h"
#include "input_text.h"
#include "mail_text.h"
#include "korean_windows.h"
#include "dialog_text.h"
#include "portrait_label.h"
#include "roster_text.h"
#include "spot_name.h"
#include "character_cards.h"
#include "character_info_data.h"
#include "outfit_info_data.h"
#include "ground_info_data.h"
#include "planet_unit_data.h"
#include "appointment_ui_data.h"
#include "planet_tax_data.h"
#include "tactical_support_data.h"
#include "tactical_motion_data.h"
#include "tactical_weapon_data.h"
#include "tactical_base_data.h"
#include "tactical_notice_data.h"
#include "session_ending_data.h"
#include "system_affiliation_data.h"
static bool mailTextTest() {
    std::wstring value=L"unchanged";
    // Use explicit CP949 bytes for portable source-code compilation.
    if (!logh::mailInputText("\xc7\xd1\xb1\xdb ABC",128,value,949) || value!=L"한글 ABC") return false;
    for (unsigned ch=0xac00;ch<=0xd7a3;++ch) {
        wchar_t source[]={static_cast<wchar_t>(ch),0};char bytes[8]={};
        if (!WideCharToMultiByte(949,WC_NO_BEST_FIT_CHARS,source,-1,bytes,sizeof(bytes),nullptr,nullptr) ||
            !logh::mailInputText(bytes,128,value,949) || value!=source) return false;
    }
    for (size_t capacity:{logh::mailTitleUnits,logh::mailBodyUnits}) {
        std::string maximum;
        for (size_t i=1;i<capacity;++i) maximum+="\xc6\x52"; // CP949 힣.
        if (!logh::mailInputText(maximum.c_str(),capacity,value,949) || value!=std::wstring(capacity-1,L'힣')) return false;
        const auto saved=value;maximum+="\xc6\x52";
        if (logh::mailInputText(maximum.c_str(),capacity,value,949) || value!=saved ||
            logh::mailInputText("\x81",capacity,value,949) || value!=saved ||
            logh::mailInputText(nullptr,capacity,value,949) || value!=saved) return false;
    }
    if (!logh::mailReplyText(L"한글日本",true,true,value) || value!=L"Re:한글日本" ||
        !logh::mailReplyText(L"한글\r\n日本\n",false,true,value) || value!=L">한글\r\n>日本\n" ||
        !logh::mailReplyText(L"\n\n",false,true,value) || value!=L">\n>\n" ||
        !logh::mailReplyText(L"",false,true,value) || !value.empty() ||
        !logh::mailReplyText(L"한글\n日本",false,false,value) || value!=L"한글\n日本") return false;
    std::wstring lines(511,L'\n');
    if (!logh::mailReplyText(lines.c_str(),false,true,value) || value.size()!=1022) return false;
    const auto saved=value;
    wchar_t unterminated[128];for (auto& ch:unterminated) ch=L'가';
    wchar_t broken[]={0xd800,L'A',0},pair[]={0xd83d,0xde80,0};
    return !logh::mailWideText(unterminated,128,value) && value==saved &&
        !logh::mailWideText(broken,128,value) && value==saved &&
        logh::mailWideText(pair,128,value) && value==pair;
}
static bool dialogTextTest() {
    const char* mouse="\x83\x7d\x83\x45\x83\x58\x83\x66\x83\x6f\x83\x43\x83\x58\x82\xcc\x90\xb6\x90\xac\x82\xc9\x8e\xb8\x94\x73\x82\xb5\x82\xdc\x82\xb5\x82\xbd";
    std::wstring text=L"unchanged";
    if (!logh::translatedDialogText(mouse,false,text) || text!=L"마우스 장치를 만들지 못했습니다.") return false;
    const auto translated=logh::koreanResourceEntry((static_cast<uint64_t>(65535)<<32)|0x788038);
    if (!translated || !logh::translatedDialogText(translated->text,false,text) || text!=L"마우스 장치를 만들지 못했습니다.") return false;
    std::string copied(translated->text);
    if (!logh::translatedDialogText(copied.c_str(),false,text) || text!=L"마우스 장치를 만들지 못했습니다.") return false;
    const char* error="\x83\x47\x83\x89\x81\x5b";
    if (!logh::translatedDialogText(error,true,text) || text!=L"오류") return false;
    if (logh::translatedDialogText(error,false,text) || text!=L"오류" ||
        logh::translatedDialogText("\x90\xed\x8a\xcd",false,text) || text!=L"오류" ||
        logh::translatedDialogText("unreviewed error",false,text) || text!=L"오류" ||
        logh::translatedDialogText(nullptr,false,text) || text!=L"오류") return false;
    std::string changed(mouse);changed+='!';
    if (logh::translatedDialogText(changed.c_str(),false,text) || text!=L"오류") return false;
    // The untranslated partner is Windows ACP, not CP932. No GUI is opened.
    if (!logh::ansiDialogText("\xc7\xd1\xb1\xdb ABC",text,949) || text!=L"한글 ABC" ||
        logh::ansiDialogText("\x81",text,949) || text!=L"한글 ABC" ||
        !logh::ansiDialogText(nullptr,text,949) || !text.empty()) return false;
    for (unsigned address:logh::dialogMessageBoxCalls)
        if (!logh::dialogMessageBoxCaller(address+6) || logh::dialogMessageBoxCaller(address)) return false;
    return !logh::dialogMessageBoxCaller(0x12345678);
}
static bool tacticalBaseDataTest() {
    BYTE stopRecord[16]={};unsigned stopCharacter=10000,stopBase=1000,wait=1;
    memcpy(stopRecord+8,&stopCharacter,4);memcpy(stopRecord+12,&stopBase,4);
    logh::TacticalFortressStop stop;
    if (!stop.read(stopRecord) || stop.base!=1000 || stop.read(nullptr)) return false;
    memcpy(stopRecord+4,&wait,4);
    if (stop.read(stopRecord) || stop.base!=1000) return false;
    wait=0;memcpy(stopRecord+4,&wait,4);
    for(unsigned bad : {0u,65536u}) {
        memcpy(stopRecord+12,&bad,4);
        if (stop.read(stopRecord)) return false;
    }
    memcpy(stopRecord+12,&stopBase,4);memcpy(stopRecord+8,&wait,4);
    if (stop.read(stopRecord)) return false;
    BYTE object[0x5c8];memset(object,0xa5,sizeof(object));
    logh::TacticalFortressStop::clearCharge(object);
    logh::TacticalFortressStop::clearCharge(object);logh::TacticalFortressStop::clearCharge(nullptr);
    for(unsigned i=0;i<sizeof(object);++i)if(object[i]!=(i>=0x5bc && i<0x5c4 ? 0 : 0xa5))return false;
    BYTE record[28]={};unsigned base=1000,target=11;unsigned short unavailable=10,destroyed=5,lost=20;
    memcpy(record+4,&base,4);memcpy(record+12,&target,4);
    record[9]=0x81;record[20]=255;record[24]=35;
    memcpy(record+16,&unavailable,2);memcpy(record+18,&destroyed,2);
    logh::TacticalBaseDamage hit;
    if (!hit.read(record,300,70) || hit.normal!=290 || hit.alive!=295 || hit.face!=255 || hit.morale!=35) return false;
    record[20]=2;memcpy(record+22,&lost,2);
    if (!hit.read(record,300,70) || hit.shield!=50 || hit.face!=2) return false;
    unavailable=4;memcpy(record+16,&unavailable,2);
    if (hit.read(record,300,70) || hit.normal!=290 || hit.alive!=295) return false;
    unavailable=10;memcpy(record+16,&unavailable,2);record[9]=1;
    if (hit.read(record,300,70)) return false;
    BYTE supply[16]={};unsigned available=321,zero=0;
    memcpy(supply,&base,4);memcpy(supply+8,&available,4);
    logh::TacticalEmergencySupply state;
    if (!state.read(supply) || state.base!=1000 || state.target || state.available!=321 || state.supplies) return false;
    memcpy(supply+4,&target,4);memcpy(supply+12,&available,4);
    if (!state.read(supply) || state.target!=11 || state.supplies!=321) return false;
    memcpy(supply+4,&zero,4);
    if (state.read(supply) || state.target!=11) return false;
    return true;
}
static bool tacticalWeaponSelectionTest() {
    logh::TacticalWeaponSelection selection;
    const unsigned ids[]={11,12},powers[]={110,20},mixed[]={110,0},changed[]={11,13},duplicate[]={11,11};
    unsigned context=1,otherContext=2;
    if(selection.targetKind(2,1)!=1 || !selection.canSelect(ids,powers,2) ||
        selection.canSelect(ids,mixed,2) || selection.canSelect(duplicate,powers,2) ||
        selection.canSelect(ids,powers,0) || selection.canSelect(ids,powers,33) || selection.canSelect(nullptr,powers,2))return false;
    for(unsigned menu=0x21;menu<=0x24;++menu) {
        selection.select(menu);
        if(selection.targetKind(2,1)!=1)return false; // An unbound menu is never a live AA request.
        const bool bound=selection.bind(&context,ids,powers,2);
        if(bound!=(menu==0x24) || selection.targetKind(2,1)!=(bound ? 0x81u : 1u) ||
            selection.targetKind(0,1)!=1 || selection.targetKind(1,1)!=1 || selection.targetKind(2,0)!=0 ||
            selection.targetKind(2,0x81)!=0x81)return false;
        if(bound && (!selection.matches(&context,ids,2) || selection.matches(&otherContext,ids,2) ||
            selection.matches(&context,changed,2) || selection.matches(&context,ids,1)))return false;
    }
    if(!selection.aiming(6,4,2) || selection.aiming(1,4,2) || selection.aiming(6,0,2) || selection.aiming(6,4,3))return false;
    selection.clear();if(selection.targetKind(2,1)!=1 || selection.count || selection.world || selection.units[0])return false;
    selection.select(0x24);if(!selection.bind(&context,ids,powers,2))return false;
    if(selection.bind(&context,ids,mixed,2) || selection.targetKind(2,1)!=1)return false;
    selection.select(0);if(selection.antiAir() || selection.matches(&context,ids,2))return false;
    unsigned char storage[logh::TacticalAntiAirPreview::SIZE+2];memset(storage,0xa5,sizeof(storage));
    auto preview=storage+1;
    // AA power is independent of a missing missile profile. Six native sectors stop at 8.
    if(!logh::TacticalAntiAirPreview::write(preview,20) || !logh::TacticalAntiAirPreview::owns(preview) ||
        preview[0]!=1 || preview[1]!=5 || storage[0]!=0xa5 || storage[sizeof(storage)-1]!=0xa5)return false;
    for(unsigned i=0;i<48;++i) {float value;memcpy(&value,preview+4+i*4,4);if(value!=100.f)return false;}
    for(unsigned i=0;i<6;++i) {float value;memcpy(&value,preview+0xc4+i*4,4);if(value!=8.f)return false;}
    logh::TacticalAntiAirPreview::clear(preview);
    for(unsigned i=0;i<logh::TacticalAntiAirPreview::SIZE;++i)if(preview[i])return false;
    if(logh::TacticalAntiAirPreview::write(preview,0) || preview[0] ||
        logh::TacticalAntiAirPreview::write(preview,65536) || logh::TacticalAntiAirPreview::write(nullptr,110))return false;
    if(!logh::TacticalAntiAirPreview::write(preview,20))return false;
    const float missile[]={60.f,80.f,100.f,100.f,80.f,60.f,40.f,20.f};
    for(unsigned i=0;i<48;++i)memcpy(preview+4+i*4,&missile[i%8],4);
    unsigned char saved[logh::TacticalAntiAirPreview::SIZE];memcpy(saved,preview,sizeof(saved));
    logh::TacticalAntiAirPreview::clear(preview);
    if(memcmp(preview,saved,sizeof(saved)))return false; // A real native missile profile is not AA-owned.
    memset(preview,0xa5,logh::TacticalAntiAirPreview::SIZE);logh::TacticalAntiAirPreview::clear(preview);
    for(unsigned i=0;i<sizeof(storage);++i)if(storage[i]!=0xa5)return false;
    return true;
}
static bool displayNameTest() {
    std::string stored="unchanged";
    std::wstring decoded;
    const wchar_t* sample=L"한글日本ABC %／※";
    if (!logh::storeDisplayName(sample,stored) || !logh::japaneseFragment(stored.c_str(),decoded) || decoded!=sample) return false;
    if (!logh::storeDisplayName(L"",stored) || !logh::japaneseFragment(stored.c_str(),decoded) || !decoded.empty()) return false;
    for (unsigned ch=0xac00;ch<=0xd7a3;++ch) {
        wchar_t name[]={static_cast<wchar_t>(ch),0};
        if (!logh::storeDisplayName(name,stored) || !logh::japaneseFragment(stored.c_str(),decoded) || decoded!=name) return false;
    }
    std::wstring maximum(27,L'힣'),tooLong(28,L'힣');
    if (!logh::storeDisplayName(maximum.c_str(),stored) || !logh::japaneseFragment(stored.c_str(),decoded) || decoded!=maximum) return false;
    std::string saved=stored;
    wchar_t bad[]={0xd800,L'A',0};
    if (logh::storeDisplayName(tooLong.c_str(),stored) || logh::storeDisplayName(bad,stored) ||
        logh::storeDisplayName(nullptr,stored) || stored!=saved) return false;
    std::wstring literal=L"\x1eL7U:AC00\x1f";
    if (!logh::storeDisplayName(literal.c_str(),stored) || !logh::japaneseFragment(stored.c_str(),decoded) || decoded!=literal) return false;
    if (!logh::storeDisplayName(L"주인 한글",stored)) return false;
    auto second=stored;
    if (!logh::storeDisplayName(L"손님日本",stored)) return false;
    std::string joined=second+" / "+stored;
    if (!logh::japaneseFragment(joined.c_str(),decoded) || decoded!=L"주인 한글 / 손님日本") return false;
    for (const auto* malformed:{L"\x1eL7U:0000\x1f",L"\x1eL7U:D800\x1f",L"\x1eL7U:AC0\x1f",L"\x1eL7U:ZZZZ\x1f",L"\x1eL7U:AC00"}) {
        std::wstring text=L"\x1eL7U:AC00\x1f";text+=malformed;auto before=text;
        if (logh::restoreDisplayNames(text) || text!=before) return false;
    }
    wchar_t buffer[64]={};wcscpy_s(buffer,L"\x1eL7U:AC00\x1f");buffer[63]=L'Z';
    if (!logh::restoreDisplayNames(buffer) || wcscmp(buffer,L"가") || buffer[63]!=L'Z') return false;
    if (logh::koreanFormatStringArguments(0x76ccf4,"%s%s")!=2 ||
        logh::koreanFormatStringArguments(0x771e08,"%s")!=1 ||
        logh::koreanFormatStringArguments(0x788a24,"%s %s")!=2 ||
        logh::koreanFormatStringArguments(0x76ccf4,"%d%s") ||
        logh::koreanFormatStringArguments(0x1234,"%s%s") ||
        logh::koreanFormatStringArguments(0x76ccf4,nullptr)) return false;
    return true;
}
static bool tacticalNoticesTest() {
    const char* missing = "NO DATA";
    const char* valid = "existing guidance";
    if (!logh::tacticalRetreatText(61, missing) || !logh::tacticalRetreatText(40, "NO TABLE") ||
        logh::tacticalRetreatText(61, valid) || logh::tacticalRetreatText(40, nullptr) ||
        logh::tacticalRetreatText(9, missing) || logh::missingTacticalText("prefix NO DATA")) return false;
    size_t count; const auto rows = logh::tacticalNotices(count);
    for (size_t i = 0; i < count; ++i) {
        if (logh::tacticalNoticeFallback(118, rows[i].index, missing, rows[i].motion) != rows[i].marker ||
            logh::tacticalNoticeFallback(118, rows[i].index, "NO TABLE", rows[i].motion) != rows[i].marker ||
            logh::tacticalNoticeFallback(118, rows[i].index, valid, rows[i].motion) != valid ||
            logh::tacticalNoticeFallback(117, rows[i].index, missing, rows[i].motion) != missing ||
            logh::tacticalNoticeFallback(118, rows[i].index, nullptr, rows[i].motion)) return false;
    }
    if (logh::tacticalNoticeFallback(118, 0, missing) != missing ||
        logh::tacticalNoticeFallback(118, 60, missing) != missing) return false;
    if (strcmp(logh::tacticalNoticeFallback(118, 10, missing, 3), "LOGH7_TACTICAL_TURN") ||
        strcmp(logh::tacticalNoticeFallback(118, 10, missing, 4), "LOGH7_TACTICAL_PARALLEL") ||
        strcmp(logh::tacticalNoticeFallback(118, 10, missing, 99), "LOGH7_TACTICAL_DESTINATION")) return false;
    std::wstring decoded = L"unchanged";
    if (logh::tacticalNoticeFragment(missing, decoded) || decoded != L"unchanged" ||
        logh::tacticalNoticeFragment(nullptr, decoded)) return false;
    const std::string mixed = std::string("[5]\x90\xed\x8a\xcd ") + rows[0].marker + rows[4].marker + "!";
    if (!logh::tacticalNoticeFragment(mixed.c_str(), decoded) ||
        decoded != std::wstring(L"[5]戦艦 ") + rows[0].text + rows[4].text + L"!") return false;
    const std::string invalid = std::string("\x81") + rows[0].marker;
    decoded = L"unchanged";
    return !logh::tacticalNoticeFragment(invalid.c_str(), decoded) && decoded == L"unchanged";
}
static bool tacticalMotionOriginsTest() {
    BYTE message[0x41c]={};message[12]=1;message[0x298]=1;
    unsigned id=3;float x=1,y=-8,angle=0.7f;
    memcpy(message+16,&id,4);memcpy(message+20,&angle,4);
    memcpy(message+24,&x,4);memcpy(message+28,&y,4);
    memcpy(message+0x294,&angle,4);memcpy(message+0x29c,&x,4);memcpy(message+0x2a0,&y,4);
    logh::TacticalMoveOrigins origins;
    if (!origins.read(message) || !origins.stationary || origins.count!=1 || origins.units[0].direction!=angle) return false;
    message[4]=1;if (!origins.read(message) || origins.stationary) return false;message[4]=0;
    float speed=0.5f;memcpy(message+0x290,&speed,4);
    if (!origins.read(message) || origins.stationary) return false;
    message[12]=33;if (origins.read(message) || origins.count) return false;
    message[12]=2;memcpy(message+36,message+16,20);if (origins.read(message)) return false;
    message[12]=1;message[0x298]=33;if (origins.read(message)) return false;
    message[0x298]=1;unsigned nan=0x7fc00000;memcpy(message+24,&nan,4);
    if (origins.read(message)) return false;
    memcpy(message+24,&x,4);memcpy(message+32,&x,4);if (origins.read(message)) return false;
    memset(message+32,0,4);x=100001;memcpy(message+24,&x,4);
    return !origins.read(message);
}
static bool planetUnitRowsTest() {
    unsigned char grid[4+300*12]={},party[logh::outfitPartyBytes]={};
    unsigned id=11,base=1049;grid[0]=1;
    memcpy(grid+4,&id,4);grid[8]=0;grid[9]=2;grid[11]=1;
    logh::PlanetUnitRows rows;
    if (!rows.grid(grid,2) || rows.candidates!=1 || rows.ids[0]!=id) return false;
    grid[0]=2;memcpy(grid+16,grid+4,12);
    if (rows.grid(grid,2) || rows.candidates!=1) return false;
    grid[0]=1;grid[9]=3;if (rows.grid(grid,2)) return false;grid[9]=2;
    memcpy(party,&id,4);memcpy(party+4,&base,4);party[9]=2;party[16]=1;
    party[0x180]=1;party[0x186]=2;party[0x188]=88;party[0x189]=2;party[0x18a]=2;
    unsigned unit=1;memcpy(party+0x18c,&unit,4);++unit;memcpy(party+0x190,&unit,4);
    party[0x4504]=1;party[0x4506]=0xe8;party[0x4507]=3;party[0x450a]=5;
    unsigned carried=123,remaining=333;
    memcpy(party+0x4598,&carried,4);memcpy(party+0x459c,&remaining,4);
    if (!logh::validOutfitParty(party,id) || logh::outfitSupplyCapacity(party)!=456) return false;
    remaining=0x7fffffffu;memcpy(party+0x459c,&remaining,4);
    if (logh::validOutfitParty(party,id)) return false;
    remaining=333;memcpy(party+0x459c,&remaining,4);
    if (!rows.party(party,id,1048,2) || rows.count) return false;
    if (!rows.party(party,id,base,2) || rows.count!=1 || rows.rows[0].ships!=2 || rows.rows[0].boats!=600 || rows.rows[0].troops!=5) return false;
    if (!logh::matchingOutfitParty(party,id,2,0) || !logh::matchingOutfitParty(party,id,2,0,base) ||
        logh::matchingOutfitParty(party,id,2,0,1048) || logh::matchingOutfitParty(party,id,3,0,base) ||
        logh::matchingOutfitParty(party,id,2,3,base) || logh::matchingOutfitParty(party,id+1,2,0,base)) return false;
    if (rows.party(party,id,base,2) || rows.count!=1) return false;
    party[8]=1;if (rows.party(party,id,base,2)) return false;party[8]=0;
    if (rows.party(party,id,base,3) || rows.party(party,id+1,base,2)) return false;
    grid[0]=0;
    return rows.grid(grid,2) && !rows.candidates && !rows.count;
}
static bool stationaryTacticalPoseTest() {
    BYTE unit[0x9ec],model[0xb4c];memset(unit,0xcc,sizeof(unit));memset(model,0xcc,sizeof(model));
    const logh::TacticalMoveOrigins::Origin origin={1010000,2.9f,1.5f,-8.5f};
    memcpy(unit+4,&origin.id,4);memcpy(model+4,&origin.id,4);
    if (!logh::applyStationaryTacticalPose(unit,model,origin)) return false;
    const float pose[]={1.5f,0,-8.5f,0,2.9f,0};
    if (memcmp(unit+0x14,pose,sizeof(pose)) || memcmp(model+0x85c,pose,sizeof(pose)) ||
        memcmp(model+0x88c,pose,sizeof(pose)) || unit[0x60] || unit[0x62] ||
        model[9] || model[10] || model[11]) return false;
    for(unsigned i=0x10;i<0x20;++i) if(model[i]) return false;
    // Preserve formation flags, other unit state, and neighboring render data.
    if (model[8]!=0xcc || model[12]!=0xcc || model[0x85b]!=0xcc || model[0x874]!=0xcc ||
        model[0x88b]!=0xcc || model[0x8a4]!=0xcc || unit[0x61]!=0xcc) return false;
    BYTE before[sizeof(model)];++model[4];memcpy(before,model,sizeof(model));
    if(logh::applyStationaryTacticalPose(unit,model,origin) || memcmp(before,model,sizeof(model))) return false;
    model[4]=unit[4];model[0]=0;
    return !logh::applyStationaryTacticalPose(unit,model,origin) &&
        !logh::applyStationaryTacticalPose(nullptr,model,origin) &&
        !logh::applyStationaryTacticalPose(unit,nullptr,origin);
}
static bool tacticalWaypointOrderTest() {
    BYTE unit[0x9ec]={},model[0xb4c]={};unit[0]=model[0]=1;
    auto put=[](BYTE* p,unsigned offset,unsigned value){memcpy(p+offset,&value,4);};
    put(unit,4,3);put(model,4,3);put(unit,0x10c,4);put(model,0x1c,7);
    put(model,0x14,4);put(model,0x18,3);put(unit,0x410,2);put(unit,0x6c,42);unit[0x414]=1;
    for(unsigned i=0;i<7;++i)put(model,0x20+i*4,i%2 ? 2 : 3);
    for(unsigned i=0;i<3;++i) {
        float x=i%2 ? -12.0f : 26.0f,z=i%2 ? -65.0f : 101.0f;
        memcpy(unit+0x128+i*24,&x,4);memcpy(unit+0x130+i*24,&z,4);
        memcpy(model+0x534+(i*2+1)*12,&x,4);memcpy(model+0x53c+(i*2+1)*12,&z,4);
    }
    if(!logh::applyTacticalWaypointOrder(unit,model) || unit[0x414] || model[0x18]!=5 || unit[0x6c] || model[0x14]!=4) return false;
    // The repeated A must select translation 3, and an older B must not rewind it.
    put(unit,0x410,1);unit[0x414]=1;
    if(!logh::applyTacticalWaypointOrder(unit,model) || model[0x18]!=5) return false;
    for(unsigned i=1;i<7;i+=2)put(model,0x20+i*4,4); // Parallel translation steps.
    put(unit,0x410,2);unit[0x414]=1;
    if(!logh::applyTacticalWaypointOrder(unit,model)) return false;
    BYTE before[sizeof(model)];memcpy(before,model,sizeof(model));
    put(unit,0x410,0xffffffffu);unit[0x414]=1;
    if(logh::applyTacticalWaypointOrder(unit,model) || !unit[0x414] || memcmp(before,model,sizeof(model))) return false;
    put(unit,0x410,2);put(unit,0x10c,5);
    if(logh::applyTacticalWaypointOrder(unit,model) || !unit[0x414]) return false;
    put(unit,0x10c,4);put(model,4,99);
    if(logh::applyTacticalWaypointOrder(unit,model)) return false;
    put(model,4,3);put(model,0x1c,66);
    if(logh::applyTacticalWaypointOrder(unit,model)) return false;
    put(model,0x1c,7);put(model,0x534+5*12,0); // Stale/different render plan.
    return !logh::applyTacticalWaypointOrder(unit,model) && !logh::applyTacticalWaypointOrder(nullptr,model) &&
        !logh::applyTacticalWaypointOrder(unit,nullptr);
}
static bool groundLocationPageTest() {
    unsigned char wire[4+70*88]={};wire[0]=2;
    auto set=[&](unsigned row,unsigned field,unsigned value){memcpy(wire+4+row*88+field,&value,4);};
    unsigned short kind=1000;
    for(unsigned i=0;i<2;++i){set(i,0,100+i);memcpy(wire+4+i*88+4,&kind,2);set(i,8,2487);set(i,12,1);}
    wire[4+6]=10;set(0,16,1);wire[4+88+6]=4;set(1,64,1049);
    logh::GroundPage page;
    if(!page.read(wire,1,99) || page.count!=2 || page.last()!=101 || !page.matches(wire) ||
        page.read(wire,2,99) || page.read(wire,1,100))return false;
    auto samePage=page;samePage.rows[7]=255;
    if(!page.same(samePage))return false; // Decoder padding cannot reset a native scroll position.
    samePage.rows[16]=2;if(page.same(samePage))return false;
    samePage=page;samePage.count=1;if(page.same(samePage))return false;
    set(1,0,100);if(page.read(wire,1,99) || page.last()!=101)return false;set(1,0,101);
    set(0,64,1049);if(page.matches(wire) || page.read(wire,1,99))return false;set(0,64,0);
    set(0,16,100);if(page.read(wire,1,99))return false;set(0,16,1);
    wire[0]=71;if(page.read(wire,1,99))return false;
    wire[0]=1;wire[4+6]=0;set(0,16,0);
    if(!page.read(wire,1,99) || page.count!=1)return false;
    wire[0]=0;return page.read(wire,1,999999) && !page.count && !page.last();
}
static bool sessionEndingDataTest() {
    BYTE record[0x434]={};unsigned id=10000;memcpy(record,&id,4);record[4]=2;record[0x2ea]=3;
    record[0x2eb]=2;record[0x2ec]='A';record[0x390]=3;record[0x3d8]=2;
    record[0x3a0]=1;record[0x3e8]=1;record[0x3c2]=3;record[0x40a]=0;
    record[0x3c3]=255;record[0x3c4]=1;record[0x40b]=0;record[0x40c]=1;
    record[0x3c8]=7;record[0x410]=9;record[0x420]=1;record[0x424]=16;
    logh::SessionEndingData data;
    if(!data.read(record) || data.character!=id || data.power!=2 || data.reason!=3 || data.name[0]!='A' ||
        data.sides[0].power!=2 || data.sides[0].grade!=0 || data.sides[0].statistics.planets!=9 ||
        data.sides[1].grade!=3 || data.sides[1].populationCondition!=255 || data.total.planets!=16)return false;
    for(unsigned offset : {0x2ebu,0x3a0u,0x3c2u,0x3c3u,0x420u}) {
        BYTE before=record[offset];record[offset]=offset==0x2eb ? 14 : 4;
        if(data.read(record) || data.character!=id || data.total.planets!=16)return false;
        record[offset]=before;
    }
    record[0x3d8]=3;if(data.read(record))return false;record[0x3d8]=2;
    if(data.read(nullptr) || data.read(record,0x433))return false;
    record[0x40a]=255;if(!data.read(record) || data.sides[0].grade!=255)return false;
    BYTE lobby[0x6d8]={};lobby[0]=1;
    memcpy(lobby+4,record+0x2e8,0x14c);memcpy(lobby+0x2a4,&id,4);lobby[0x2a8]=2;
    lobby[0x2a0]=2;lobby[0x2a1]=1;lobby[0x358]=1;
    if(!data.readLobby(lobby,sizeof(lobby),0) || data.character!=id || data.sides[0].grade!=255 || data.total.planets!=16)return false;
    if(data.readLobby(lobby,0x36b,0) || data.readLobby(lobby,sizeof(lobby),1) || data.readLobby(nullptr,0,0))return false;
    lobby[0]=2;memcpy(lobby+0x36c+4,lobby+4,0x368);
    lobby[0x36c+0x2a8]=3;
    if(!data.readLobby(lobby,sizeof(lobby),1) || data.power!=3)return false;
    for(unsigned offset:{0u,0x2a0u,0x2a1u,0x358u}) {
        BYTE before=lobby[offset];lobby[offset]=4;
        if(data.readLobby(lobby,sizeof(lobby),0))return false;lobby[offset]=before;
    }
    return !wcscmp(logh::endingGrade(2),L"결정적 승리") && !wcscmp(logh::endingGrade(255),L"승패 집계 중");
}
int wmain(int argc, wchar_t** argv) {
    if (!mailTextTest()) return 252;
    if (!dialogTextTest()) return 251;
    if (!displayNameTest()) return 250;
    const char* battleship="\x90\xed\x8a\xcd";
    const char* translated=logh::koreanResource(85,0,battleship);
    std::wstring korean;
    if (translated==battleship || !logh::japaneseFragment(translated,korean) || korean!=L"전함" ||
        strcmp(logh::koreanResource(85,0,"modified catalogue"),"modified catalogue") ||
        logh::koreanResource(999,0,battleship)!=battleship ||
        logh::koreanResource(85,0,nullptr)!=nullptr) return 82;
    const char* error="\x83\x47\x83\x89\x81\x5b";
    if (!logh::japaneseFragment(logh::koreanResource(65535,0x78,0x6ab4,error),korean) || korean!=L"오류" ||
        logh::koreanStaticResource(error)!=error ||
        !logh::japaneseFragment(logh::koreanResource(65534,0,0,"NO TABLE"),korean) || korean!=L"명령 패널") return 82;
    char windowsString[128];memset(windowsString,0x7f,sizeof(windowsString));
    if (logh::copyKoreanWindowsString(57344,windowsString,sizeof(windowsString),949)<=0 ||
        !logh::inputFragment(windowsString,korean) || korean!=L"은하영웅전설 VII" ||
        logh::copyKoreanWindowsString(57344,windowsString,2,949)!=0 || windowsString[0] ||
        logh::copyKoreanWindowsString(57344,windowsString,3,949)!=2 || windowsString[2] ||
        logh::copyKoreanWindowsString(999,windowsString,sizeof(windowsString),949)!=-1 ||
        logh::copyKoreanWindowsString(57344,windowsString,sizeof(windowsString),932)!=-1) return 82;
    // A genuine resource translation cannot leak its private codepoints into
    // Unicode render text, and malformed Japanese bytes still fail strictly.
    for (const auto& row:logh::koreanResources) {
        if (!logh::japaneseFragment(row.text,korean)) return 83;
        for (wchar_t ch:korean) if(ch>=0xe000 && ch<=0xf8ff) return 83;
    }
    logh::SystemAffiliation territory;
    unsigned territoryIds[]={1048,1049,1050};
    if (territory.affiliation(territoryIds,3,2487)!=10 ||
        !territory.accept(1048,2487,2,0) || !territory.accept(1049,2487,3,0) ||
        territory.affiliation(territoryIds,3,2487)!=10 || !territory.accept(1050,2487,2,0) ||
        territory.affiliation(territoryIds,3,2487)!=11 || !territory.accept(1049,2487,2,0) ||
        territory.affiliation(territoryIds,3,2487)!=4 || !territory.accept(1049,2487,2,1) ||
        territory.affiliation(territoryIds,3,2487)!=11 || territory.accept(1049,5000,2,0) ||
        territory.accept(1049,2487,5,0) || territory.accept(1049,2487,2,2)) return 80;
    if (!territory.accept(1048,2487,0,0) || !territory.accept(1049,2487,0,0) ||
        !territory.accept(1050,2487,0,0) || territory.affiliation(territoryIds,3,2487)!=0 ||
        logh::systemAffiliationRgb(10)==logh::systemAffiliationRgb(0) ||
        logh::systemAffiliationRgb(4)!=0x4080ff || logh::systemAffiliationRgb(6)!=0x00dc80 ||
        logh::systemAffiliationRgb(5)!=0x40c0ff || logh::systemAffiliationRgb(7)!=0xffff40 ||
        logh::systemAffiliationRgb(11)==logh::systemAffiliationRgb(7)) return 81;
    if(!sessionEndingDataTest())return 86;
    if (!groundLocationPageTest()) return 85;
    if (!tacticalMotionOriginsTest()) return 81;
    if (!tacticalNoticesTest()) return 82;
    if (!tacticalWeaponSelectionTest()) return 87;
    if (!tacticalBaseDataTest()) return 88;
    if (!stationaryTacticalPoseTest()) return 83;
    if (!tacticalWaypointOrderTest()) return 84;
    BYTE profile[logh::characterProfileBytes]={},snapshot[0x370]={};
    unsigned profileId=10001;memcpy(profile,&profileId,4);memcpy(snapshot+0x24,&profileId,4);
    profile[4]=2;profile[6]='A';profile[0x20]=3;profile[0x6a]=7;profile[0x60]=88;
    profile[0x69]=97;snapshot[0x1ae]=19;snapshot[0x270]=2;snapshot[0x274]=195;
    memset(profile+0x6e,0xcc,26); // Unwritten payload behind an empty title.
    if (!logh::validCharacterProfile(profile,sizeof(profile),profileId) ||
        !logh::applyCharacterProfile(profile,sizeof(profile),snapshot,sizeof(snapshot)) ||
        snapshot[0xde]!='A' || snapshot[0xfa]!=7 || snapshot[0x28]!=3 ||
        logh::infoShort(snapshot,0x1ac)!=88 || snapshot[0x1cd]!=97 ||
        snapshot[0x1ae]!=19 || snapshot[0x270]!=2 || snapshot[0x274]!=195 || snapshot[0xfe]) return 222;
    if (logh::validCharacterProfile(nullptr,sizeof(profile),profileId) ||
        logh::validCharacterProfile(profile,sizeof(profile)-1,profileId) ||
        logh::validCharacterProfile(profile,sizeof(profile),profileId+1) ||
        logh::applyCharacterProfile(profile,sizeof(profile),snapshot,sizeof(snapshot)-1)) return 223;
    profile[0x90]=17;
    if (logh::validCharacterProfile(profile,sizeof(profile),profileId)) return 224;
    profile[0x90]=16;profile[0xb2]=1;profile[0xb4]=1;profile[0xba]=1;profile[0xbc]=1;profile[0xe0]=5;
    for (unsigned i=0;i<5;++i) profile[0xe8+i*0x28]=1;
    if (!logh::validCharacterProfile(profile,sizeof(profile),profileId)) return 225;
    profile[0xe0]=6;
    if (logh::validCharacterProfile(profile,sizeof(profile),profileId)) return 226;
    profile[0xe0]=5;profile[0xb2]=2;
    if (logh::validCharacterProfile(profile,sizeof(profile),profileId)) return 227;
    profile[0xb2]=1;profile[4]=14;
    if (logh::validCharacterProfile(profile,sizeof(profile),profileId)) return 228;
    profile[4]=0;profile[0x20]=0;profile[0x6a]=255;
    if (!logh::validCharacterProfile(profile,sizeof(profile),profileId) ||
        logh::applyCharacterProfile(profile,sizeof(profile),snapshot,sizeof(snapshot))) return 229;
    // The CD decoder leaves absent optional outfit fields untouched on reused objects.
    profile[0xb2]=0;profile[0xba]=255;
    if (!logh::validCharacterProfile(profile,sizeof(profile),profileId)) return 231;
    profile[0xb2]=1;
    if (logh::validCharacterProfile(profile,sizeof(profile),profileId)) return 232;
    logh::OutfitReplyQueue profileQueue;unsigned oldToken=0;
    if (!profileQueue.add(profileId,0,1,false) || !profileQueue.add(profileId,0,2,false) ||
        profileQueue.take(profileId+1,0,false,oldToken) ||
        !profileQueue.take(profileId,0,false,oldToken) || oldToken!=1 ||
        !profileQueue.take(profileId,0,false,oldToken) || oldToken!=2) return 230;
    unsigned char cardRecord[0x370]={};logh::CharacterCards cardRows;
    if (!logh::CharacterCards::read(cardRecord,sizeof(cardRecord),cardRows) || cardRows.count) return 215;
    cardRecord[0x270]=2;cardRecord[0x27c]=190;
    unsigned cardBase=2015;memcpy(cardRecord+0x280,&cardBase,4);
    if (!logh::CharacterCards::read(cardRecord,sizeof(cardRecord),cardRows) || cardRows.count!=2 ||
        cardRows.rows[1].kind!=190 || cardRows.rows[1].target!=2015) return 216;
    auto savedCards=cardRows;
    cardRecord[0x270]=17;
    if (logh::CharacterCards::read(cardRecord,sizeof(cardRecord),cardRows) || !cardRows.same(savedCards)) return 217;
    cardRecord[0x270]=2;cardRecord[0x27c]=5;cardRecord[0x27d]=1;
    if (logh::CharacterCards::read(cardRecord,sizeof(cardRecord),cardRows) ||
        logh::CharacterCards::read(cardRecord,0x280,cardRows) ||
        logh::CharacterCards::read(nullptr,sizeof(cardRecord),cardRows)) return 218;
    cardRecord[0x27c]=0;cardRecord[0x27d]=0;memset(cardRecord+0x280,0,4);
    if (logh::CharacterCards::read(cardRecord,sizeof(cardRecord),cardRows)) return 219;
    cardRecord[0x274]=190;cardRecord[0x27c]=190;memcpy(cardRecord+0x280,&cardBase,4);
    if (!logh::CharacterCards::read(cardRecord,sizeof(cardRecord),cardRows) || cardRows.same(savedCards)) return 220;
    cardRecord[0x270]=16;
    for (unsigned i=0;i<16;++i) {unsigned short kind=static_cast<unsigned short>(i);memcpy(cardRecord+0x274+i*8,&kind,2);}
    if (!logh::CharacterCards::read(cardRecord,sizeof(cardRecord),cardRows) || cardRows.count!=16 ||
        !logh::characterCardBase(190) || logh::characterCardBase(195) || logh::characterCardBase(0)) return 221;
    if (logh::spotName(L"양",7)!=L"양준장" ||
        logh::spotName(L"트류니히트",1)!=L"정치가 트류니히트" ||
        logh::spotName(L"병사",20)!=L"병사이등병" ||
        logh::spotName(L"미상",255)!=L"미상" ||
        logh::spotName(L"미상",21)!=L"미상" ||
        logh::spotName(L"",7)!=L"준장") return 214;
    std::wstring roster=L"unchanged";
    auto stored=logh::storedRosterText(L"한글日本ABC손님／二等兵※");
    if (!logh::rosterText(stored.c_str(),roster) || roster!=L"한글日本ABC손님／二等兵※") return 212;
    if (logh::rosterText("Native list",roster) || roster!=L"한글日本ABC손님／二等兵※" ||
        logh::rosterText("LOGH7_SPOT_ROW:\xc0\xaf",roster) || roster!=L"한글日本ABC손님／二等兵※") return 213;
    if (logh::portraitLineBreak(L"가나다라마바사아")!=L"가나다라마바사아" ||
        logh::portraitLineBreak(L"가나다라마바사아／大将")!=L"가나다라마바사아\n／大将" ||
        logh::portraitLineBreak(L"123456789012345가")!=L"123456789012345\n가" ||
        logh::portraitLineBreak(L"日本 ABC 한글／少佐")!=L"日本 ABC 한글／\n少佐") return 211;
    std::wstring inputResult=L"unchanged";
    if (!logh::inputFragment("ABC 123",inputResult) || inputResult!=L"ABC 123") return 201;
    if (logh::inputFragment("\x81",inputResult,949) || inputResult!=L"ABC 123") return 202;
    if (!logh::inputFragment("\x93\xfa\x96\x7b",inputResult,932) || inputResult!=L"日本") return 203;
    for (unsigned value=0xac00;value<=0xd7a3;++value) {
        wchar_t syllable[]={static_cast<wchar_t>(value),0};
        char bytes[8]={};BOOL replaced=FALSE;
        int length=WideCharToMultiByte(949,WC_NO_BEST_FIT_CHARS,syllable,-1,bytes,sizeof(bytes),nullptr,&replaced);
        if (length!=3 || replaced || !logh::inputFragment(bytes,inputResult,949) || inputResult!=syllable) return 204;
    }
    if (logh::inputPrintableByte('A')!=1 || logh::inputPrintableByte('9')!=1 ||
        logh::inputPrintableByte('\n') || logh::inputPrintableByte(0)) return 205;
    if (GetACP()==949 && (logh::inputPrintableByte(0xb0) || logh::inputPrintableByte(0xa1))) return 206;
    if (!planetUnitRowsTest()) return 200;
    if (argc != 2) return 1;
    if (wcscmp(logh::planetAffiliation(2,0),L"은하제국") ||
        wcscmp(logh::planetAffiliation(2,1),L"제국 반란군") ||
        wcscmp(logh::planetAffiliation(3,0),L"자유행성동맹") ||
        wcscmp(logh::planetAffiliation(3,1),L"동맹 반란군") ||
        wcscmp(logh::planetAffiliation(5,0),L"미확인") ||
        wcscmp(logh::planetAffiliation(2,2),L"미확인")) return 75;
    if (wcscmp(logh::planetHabitability(0),L"불가") ||
        wcscmp(logh::planetHabitability(1),L"가능") ||
        wcscmp(logh::planetHabitability(2),L"미확인") ||
        wcscmp(logh::planetHabitability(3),L"미확인") ||
        wcscmp(logh::planetAtmosphere(2),L"미확인")) return 76;
    unsigned char taxCharacter[0x360]={};taxCharacter[0x270]=3;
    uint16_t taxCard=190;uint32_t taxFirst=1048,taxLocal=1049;
    memcpy(taxCharacter+0x274,&taxCard,2);memcpy(taxCharacter+0x278,&taxFirst,4);
    memcpy(taxCharacter+0x27c,&taxCard,2);memcpy(taxCharacter+0x280,&taxLocal,4);
    memcpy(taxCharacter+0x358,&taxLocal,4);
    if (logh::heldLocalTaxBase(taxCharacter,190)!=1049 || logh::heldLocalTaxBase(taxCharacter,192)) return 71;
    taxCard=192;memcpy(taxCharacter+0x284,&taxCard,2);memcpy(taxCharacter+0x288,&taxLocal,4);
    if (logh::heldLocalTaxBase(taxCharacter,192)!=1049 || logh::heldLocalTaxBase(taxCharacter,193)) return 72;
    memset(taxCharacter+0x358,0,4);
    if (logh::heldLocalTaxBase(taxCharacter,190)) return 73;
    memcpy(taxCharacter+0x358,&taxLocal,4);taxCharacter[0x270]=17;
    if (logh::heldLocalTaxBase(taxCharacter,192)) return 74;
    unsigned char capitalBase[0x250]={};
    const uint16_t odinName[]={0x30aa,0x30fc,0x30c7,0x30a3,0x30f3,0};
    const uint16_t heinessenName[]={0x30cf,0x30a4,0x30cd,0x30bb,0x30f3,0};
    uint16_t capitalGrid=2487;memcpy(capitalBase+0x10,&capitalGrid,2);
    memcpy(capitalBase+0x48,odinName,sizeof(odinName));
    if (logh::planetOfficeKinds(capitalBase,2,0).first!=56 || logh::planetOfficeKinds(capitalBase,2,0).second!=57 ||
        logh::planetOfficeKinds(capitalBase,3,0).first || logh::planetOfficeKinds(capitalBase,2,1).second) return 67;
    capitalGrid=2015;memcpy(capitalBase+0x10,&capitalGrid,2);
    memcpy(capitalBase+0x48,heinessenName,sizeof(heinessenName));
    if (logh::planetOfficeKinds(capitalBase,3,0).first!=192 || logh::planetOfficeKinds(capitalBase,3,0).second!=193 ||
        logh::planetOfficeKinds(capitalBase,2,0).second) return 68;
    const uint16_t odinKoName[]={0xc624,0xb518,0};
    const uint16_t heinessenKoName[]={0xd558,0xc774,0xb124,0xc13c,0};
    memcpy(capitalBase+0x48,odinKoName,sizeof(odinKoName));
    if(logh::planetCapitalPower(capitalBase)) return 212;
    capitalGrid=2487;memcpy(capitalBase+0x10,&capitalGrid,2);
    if(logh::planetOfficeKinds(capitalBase,2,0).first!=56) return 213;
    memcpy(capitalBase+0x48,heinessenKoName,sizeof(heinessenKoName));
    if(logh::planetCapitalPower(capitalBase)) return 214;
    capitalGrid=2015;memcpy(capitalBase+0x10,&capitalGrid,2);
    if(logh::planetOfficeKinds(capitalBase,3,0).first!=192 || logh::planetOfficeKinds(capitalBase,3,0).second!=193) return 215;
    capitalBase[0x48]='X';capitalBase[0x49]=0;
    if (logh::planetCapitalPower(capitalBase) || logh::planetOfficeKinds(capitalBase,3,0).first!=190) return 69;
    capitalBase[0x14]=1;
    if (logh::planetOfficeKinds(capitalBase,3,0).first || logh::planetOfficeKinds(capitalBase,3,0).second!=188 ||
        logh::planetOfficeKinds(capitalBase,0,0).second) return 70;
    if (logh::appointmentInformationCard(12,55,0)!=55 ||
        logh::appointmentInformationCard(14,1049,55)!=55 ||
        logh::appointmentInformationCard(13,999,52)!=52 ||
        logh::appointmentInformationCard(12,0,55) ||
        logh::appointmentInformationCard(12,261,55) ||
        logh::appointmentInformationCard(14,55,0) ||
        logh::appointmentInformationCard(14,55,261) ||
        logh::appointmentInformationCard(3,55,55)) return 26;
    if (!logh::appointmentAuthorityCard(135) || !logh::appointmentAuthorityCard(1) ||
        logh::appointmentAuthorityCard(0) || logh::appointmentAuthorityCard(195) ||
        logh::appointmentCapitalPower(169)!=3 || logh::appointmentCapitalPower(135)!=3 ||
        logh::appointmentCapitalPower(2)!=2 || logh::appointmentCapitalPower(56)!=2 ||
        logh::appointmentCapitalPower(193)!=3 || logh::appointmentCapitalPower(54) ||
        logh::appointmentCapitalPower(175) || logh::appointmentCapitalPower(231)) return 71;
    unsigned char outfitCache[100 * 32] = {};
    outfitCache[32] = 1; outfitCache[36] = 7;
    outfitCache[96] = 1; outfitCache[100] = 9;
    if (logh::outfitListRow(outfitCache, 0) || logh::outfitListRow(outfitCache, 101) ||
        logh::outfitListRow(outfitCache, 1) != outfitCache + 36 ||
        logh::outfitListRow(outfitCache, 2) != outfitCache + 100 || logh::outfitListRow(outfitCache, 3)) return 23;
    unsigned char party[logh::outfitPartyBytes] = {};
    party[0] = 7; party[4] = 1; party[9] = 2;
    if (!logh::validOutfitParty(party, 7) || logh::validOutfitParty(party, 9)) return 24;
    party[4]=0;
    if(!logh::validOutfitParty(party,7) || !logh::matchingOutfitParty(party,7,2,0) ||
        logh::matchingOutfitParty(party,7,2,0,1))return 87;
    party[4]=1;
    party[0x180] = 1; party[0x186] = 1; party[0x188] = 44; party[0x18a] = 1; party[0x18c] = 1;
    if (!logh::validOutfitParty(party, 7)) return 24;
    unsigned char sameParty[logh::outfitPartyBytes];memcpy(sameParty,party,sizeof(party));
    sameParty[11]=255;sameParty[0x183]=255;sameParty[0x187]=255;sameParty[0x1a0]=255;
    if(!logh::sameOutfitParty(party,sameParty))return 86;
    sameParty[0x18c]=2;if(logh::sameOutfitParty(party,sameParty))return 86;
    memcpy(sameParty,party,sizeof(party));sameParty[0x4598]=1;
    if(logh::sameOutfitParty(party,sameParty))return 86;
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
    unsigned char placeReply[4+4*0x2378]={};
    placeReply[0]=2;
    memcpy(placeReply+4,&other,4);
    auto placeBase=placeReply+4+0x2378;
    memcpy(placeBase,&id,4);placeBase[4]=1;
    auto facility=placeBase+8;
    unsigned facilityId=10490001,spotId=10490002;
    memcpy(facility+4,&facilityId,4);facility[8]=1;
    memcpy(facility+16,&spotId,4);facility[20]=7;
    logh::PlanetPlaces places;
    if (!places.accept(placeReply,id) || places.count!=1 || places.institutions!=1 ||
        places.rows[0].institution!=facilityId || places.rows[0].spot!=spotId || places.rows[0].image!=7 ||
        places.accept(placeReply,9999)) return 51;
    facility[8]=21;
    if (places.accept(placeReply,id) || places.count!=1 || places.rows[0].spot!=spotId) return 52;
    facility[8]=0;
    if (!places.accept(placeReply,id) || places.count!=1 || places.rows[0].spot) return 53;
    placeBase[4]=0;
    if (!places.accept(placeReply,id) || places.count || places.institutions) return 54;
    placeBase[4]=36;
    for (unsigned i=0;i<36;++i) {
        auto f=placeBase+8+i*0xfc;memcpy(f+4,&facilityId,4);f[8]=20;
        for (unsigned j=0;j<20;++j) {auto spot=f+12+j*12;memcpy(spot+4,&spotId,4);spot[8]=1;}
    }
    if (!places.accept(placeReply,id) || places.count!=720 || places.rows[719].spot!=spotId) return 55;
    placeBase[4]=37;
    if (places.accept(placeReply,id) || places.count!=720) return 56;
    placeReply[0]=5;
    if (places.accept(placeReply,id)) return 57;
    unsigned char governorReply[4+64*0x2d4]={};governorReply[0]=54;governorReply[2]=1;
    auto governor=governorReply+4;
    unsigned governorId=10000;memcpy(governor,&governorId,4);governor[0x7d]=1;
    governor[0x82]='A';governor[0x9c]='B';governor[0x24c]=1;governor[0x250]=54;
    memcpy(governor+0x254,&id,4);
    logh::PlanetOfficeHolder holder;
    if (!holder.accept(governorReply,54,id) || holder.id!=governorId || holder.surname[0]!='A' || holder.givenName[0]!='B') return 58;
    memcpy(governor+0x2d4,governor,0x2d4);
    unsigned secondGovernor=10001;memcpy(governor+0x2d4,&secondGovernor,4);governorReply[2]=2;
    if (holder.accept(governorReply,54,id) || holder.id!=governorId) return 64;
    governorReply[2]=1;
    if (holder.accept(governorReply,190,id) || holder.id!=governorId) return 59;
    governor[0x24c]=17;
    if (holder.accept(governorReply,54,id) || holder.id!=governorId) return 60;
    governor[0x24c]=1;
    if (!holder.accept(governorReply,54,other) || holder.id || holder.surname[0]) return 61;
    governorReply[2]=0;
    if (!holder.accept(governorReply,54,id) || holder.id) return 62;
    governorReply[2]=65;
    if (holder.accept(governorReply,54,id)) return 63;
    for (unsigned card : {52u,55u,56u,57u,188u,191u,192u,193u}) {
        memset(governorReply,0,sizeof(governorReply));governorReply[0]=static_cast<unsigned char>(card);
        if (!holder.accept(governorReply,card,id) || holder.id) return 65;
        if (holder.accept(governorReply,card==55?52:55,id)) return 66;
    }
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
    unsigned normal=999;
    if (!logh::repairedNormal(300,250,50,normal) || normal!=250 ||
        !logh::repairedNormal(300,250,78,normal) || normal!=222 ||
        !logh::repairedNormal(1,1,0,normal) || normal!=1 ||
        logh::repairedNormal(300,250,0,normal) || logh::repairedNormal(300,250,301,normal) ||
        logh::repairedNormal(0,0,0,normal)) return 80;
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
