// Negative test: loading the proxy in another x86 process must not install game hooks.
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include "hud_atlas.h"
#include "planet_info_data.h"
#include "spot_presence.h"
#include "unicode_text.h"
#include "input_text.h"
#include "portrait_label.h"
#include "roster_text.h"
#include "spot_name.h"
#include "character_cards.h"
#include "character_info_data.h"
#include "outfit_info_data.h"
#include "planet_unit_data.h"
#include "appointment_ui_data.h"
#include "planet_tax_data.h"
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
int wmain(int argc, wchar_t** argv) {
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
    capitalBase[0x48]='X';
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
