// Negative test: loading the proxy in another x86 process must not install game hooks.
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <cstdio>
#include "hud_atlas.h"
#include "navigation_hud_data.h"
#include "country_colors_data.h"
#include "country_metadata.h"
#include "country_image_test.h"
#include "base_affiliation.h"
#include "outfit_affiliation_data.h"
#include "tactical_camp_data.h"
#include "country_ending_data.h"
#include "country_icon_data.h"
#include "creation_menu_data.h"
#include "lobby_empty_data.h"
#include "planet_info_data.h"
#include "facility_guide_data.h"
#include "facility_label_data.h"
#include "lobby_country_data.h"
#include "spot_presence.h"
#include "unicode_text.h"
#include "input_text.h"
#include "ime_status.h"
#include "ime_key_gate_test.h"
#include "mail_text.h"
#include "mail_caption_text.h"
#include "office_text.h"
#include "mail_peer_text.h"
#include "return_base_data.h"
#include "mail_delivery.h"
#include "legacy_korean_glyph.h"
#include "namecard_text.h"
#include "messenger_text.h"
#include <vector>
#pragma comment(lib,"gdi32.lib")
#include "mail_refresh.h"
#include "proposal_approval_state.h"
#include "proposal_detail_caption.h"
#include "korean_windows.h"
#include "dialog_text.h"
#include "portrait_label.h"
#include "roster_text.h"
#include "spot_name.h"
#include "character_cards.h"
#include "character_info_data.h"
#include "outfit_info_data.h"
#include "ground_info_data.h"
#include "ground_panel_layout.h"
#include "planet_unit_data.h"
#include "appointment_ui_data.h"
#include "planet_tax_data.h"
#include "tactical_support_data.h"
#include "tactical_motion_data.h"
#include "tactical_weapon_data.h"
#include "tactical_charge_data.h"
#include "tactical_energy_data.h"
#include "tactical_base_data.h"
#include "tactical_notice_data.h"
#include "flagship_weapon_data.h"
#include "session_ending_data.h"
#include "game_admission_data.h"
#include "system_affiliation_data.h"
#include "environment_shader_data.h"
#include "tactical_fortress_menu_data.h"
#include "tactical_detection_data.h"
#include "tactical_inspect_data.h"
#include "hd_texture_data.h"
// HD texture pack rules: data-relative path, skipped files, header sizes and the reported size (codes 901+).
static int checkHdTextureRules() {
    char rel[MAX_PATH];
    if(!logh_hd::relativeDataPath("/../data/image/icon/system_icon_parts.tga",rel,sizeof(rel)) ||
        strcmp(rel,"image\\icon\\system_icon_parts.tga"))return 901;
    if(!logh_hd::relativeDataPath("C:\\G\\exe/../data/model/images/Hi/a.bmp",rel,sizeof(rel)) ||
        strcmp(rel,"model\\images\\Hi\\a.bmp"))return 902;
    if(logh_hd::relativeDataPath("/../metadata/a.tga",rel,sizeof(rel)) || logh_hd::relativeDataPath("/x/a.tga",rel,sizeof(rel)) ||
        logh_hd::relativeDataPath("/data/../a.tga",rel,sizeof(rel)) || logh_hd::relativeDataPath("/data/",rel,sizeof(rel)) ||
        logh_hd::relativeDataPath("/data/abcdef",rel,4))return 903;
    if(!logh_hd::skipped("image\\a_col_p.tga") || !logh_hd::skipped("model\\images\\Hi\\b_NORMALMAP.bmp") ||
        !logh_hd::skipped("image\\icon\\ICON_COUNTRY32.TGA") || !logh_hd::skipped("x\\c_alpha_s.bmp") ||
        logh_hd::skipped("image\\a_alpha_x.bmp") || logh_hd::skipped("image\\icon_country32.tga.bak") ||
        logh_hd::skipped("_col_p\\plain.tga"))return 904;
    unsigned w=0,h=0;
    const unsigned char png[24]={0x89,'P','N','G','\r','\n',0x1a,'\n',0,0,0,13,'I','H','D','R',0,0,1,0,0,0,1,64};
    if(!logh_hd::imageSize(png,sizeof(png),"a.png",&w,&h) || w!=256 || h!=320)return 905;
    unsigned char tga[18]={};tga[12]=64;tga[14]=80;
    if(!logh_hd::imageSize(tga,sizeof(tga),"a.TGA",&w,&h) || w!=64 || h!=80 ||
        logh_hd::imageSize(tga,sizeof(tga),"a.raw",&w,&h))return 906;
    unsigned char bmp[26]={'B','M'};bmp[18]=32;bmp[22]=0xf0;bmp[23]=0xff;bmp[24]=0xff;bmp[25]=0xff; // height -16
    if(!logh_hd::imageSize(bmp,sizeof(bmp),"a.bmp",&w,&h) || w!=32 || h!=16)return 907;
    const unsigned char jpg[]={0xff,0xd8,0xff,0xe0,0,4,0,0,0xff,0xc0,0,11,8,1,0x2c,2,0x58,1,1,0x11,0,0,0};
    if(!logh_hd::imageSize(jpg,sizeof(jpg),"a.jpg",&w,&h) || w!=600 || h!=300)return 908;
    unsigned char dds[20]={'D','D','S',' '};dds[12]=128;dds[16]=64;
    if(!logh_hd::imageSize(dds,sizeof(dds),"a.dds",&w,&h) || w!=64 || h!=128)return 909;
    if(logh_hd::scaleFor(64,80,256,320)!=4 || logh_hd::scaleFor(300,10,600,20)!=2 || logh_hd::scaleFor(64,80,256,321) ||
        logh_hd::scaleFor(64,80,64,80) || logh_hd::scaleFor(10,10,100,100) || logh_hd::scaleFor(0,1,2,2) ||
        logh_hd::scaleFor(64,80,256,160))return 910;
    if(logh_hd::reportedSize(512,4)!=128 || logh_hd::reportedSize(2,4)!=1 || logh_hd::reportedSize(64,0)!=64)return 911;
    const unsigned char a[]={'a'};
    if(logh_hd::fnv1a32(nullptr,0)!=0x811c9dc5u || logh_hd::fnv1a32(a,1)!=0xe40c292cu)return 912;
    char name[24];
    if(!logh_hd::hdFileName("x\\a.tga",0x0123abcdu,name,sizeof(name)) || strcmp(name,"x\\a.tga.0123abcd.png"))return 913;
    if(!logh_hd::hdFileName("ab",1,name,16) || strcmp(name,"ab.00000001.png") || logh_hd::hdFileName("abc",1,name,16))return 914;
    return 0;
}
static bool legacyKoreanGlyphTest() {
    wchar_t value=0;
    if (logh::legacyKoreanGlyph('A',value) || logh::legacyKoreanGlyph(0x82a0,value) ||
        logh::legacyKoreanGlyph(0xf07f,value) || logh::legacyKoreanGlyph(0xf9fc,value)) return false;
    UINT firstHangul=0;
    for (unsigned i=0;i<logh::koreanGlyphCount;++i) {
        const wchar_t privateGlyph=static_cast<wchar_t>(0xe000+i);
        char bytes[2]={};
        if (WideCharToMultiByte(932,WC_NO_BEST_FIT_CHARS,&privateGlyph,1,bytes,2,nullptr,nullptr)!=2) return false;
        const UINT code=(static_cast<unsigned char>(bytes[0])<<8)|static_cast<unsigned char>(bytes[1]);
        if (!logh::legacyKoreanGlyph(code,value) || value!=logh::koreanGlyphs()[i]) return false;
        if (!firstHangul && value>=0xac00 && value<=0xd7a3) firstHangul=code;
    }
    const auto dc=CreateCompatibleDC(nullptr);
    if (!dc || !firstHangul) { if(dc)DeleteDC(dc);return false; }
    const auto original=GetCurrentObject(dc,OBJ_FONT);
    MAT2 matrix={};matrix.eM11.value=matrix.eM22.value=1;
    GLYPHMETRICS metrics={};
    const auto size=logh::legacyKoreanOutline(dc,firstHangul,GGO_GRAY8_BITMAP,&metrics,0,nullptr,&matrix);
    bool ok=size!=GDI_ERROR && size>0 && GetCurrentObject(dc,OBJ_FONT)==original;
    if (ok) {
        std::vector<BYTE> bitmap(size);
        ok=logh::legacyKoreanOutline(dc,firstHangul,GGO_GRAY8_BITMAP,&metrics,size,bitmap.data(),&matrix)==size &&
            GetCurrentObject(dc,OBJ_FONT)==original;
    }
    GLYPHMETRICS nativeMetrics={},bridgeMetrics={};
    const auto native=GetGlyphOutlineA(dc,'A',GGO_METRICS,&nativeMetrics,0,nullptr,&matrix);
    const auto bridge=logh::legacyKoreanOutline(dc,'A',GGO_METRICS,&bridgeMetrics,0,nullptr,&matrix);
    ok=ok && native==bridge && !memcmp(&nativeMetrics,&bridgeMetrics,sizeof(nativeMetrics));
    DeleteDC(dc);
    return ok;
}
static bool nameCardDisplayTest() {
    std::string text="old";
    if (!logh::nameCardDisplayText(L"한글ABC"," suffix",text)) return false;
    std::wstring wide;
    if (!logh::japaneseFragment(text.c_str(),wide) || wide!=L"한글ABC suffix") return false;
    const std::wstring maximum(27,L'한');
    const std::string suffix(64,'x');
    if (!logh::nameCardDisplayText(maximum.c_str(),suffix.c_str(),text) || text.size()<=128 ||
        !logh::japaneseFragment(text.c_str(),wide) || wide!=maximum+std::wstring(64,L'x')) return false;
    const auto before=text;
    const std::wstring tooLong(28,L'한');
    const wchar_t broken[]={0xd800,0};
    return !logh::nameCardDisplayText(tooLong.c_str(),"suffix",text) && text==before &&
        !logh::nameCardDisplayText(broken,"suffix",text) && text==before &&
        !logh::nameCardDisplayText(nullptr,"suffix",text) && text==before;
}
static bool messengerStatusTextTest() {
    return logh::messengerStatusKind(0x5433a5,0x16,0x77)==0 &&
        logh::messengerStatusKind(0x5433a5,0x16,0x7b)==1 &&
        logh::messengerStatusKind(0x5433a5,0x16,0x78)==2 &&
        logh::messengerStatusKind(0x54501d,0x16,0x78)==2 &&
        logh::messengerStatusKind(0x545044,0x16,0x66)==1 &&
        logh::messengerStatusKind(0x54506b,0x16,0x77)==0 &&
        logh::messengerStatusKind(0x54506b,0x16,0x87)==3 &&
        logh::messengerStatusKind(0x5433a5,0x15,0x77)==3 &&
        logh::messengerStatusKind(0x544f05,0x16,0x77)==3;
}
static bool commanderMenuTest() {
    BYTE decoded[4+3*0x3c]={};*reinterpret_cast<unsigned short*>(decoded)=2;
    *reinterpret_cast<unsigned*>(decoded+4)=10001;*reinterpret_cast<unsigned*>(decoded+4+0x3c)=10000;decoded[4+0x3c+0x10]=1;
    if(logh::corpsChief(decoded,10000)!=1 || logh::corpsChief(decoded,10001)!=0 || logh::corpsChief(decoded,10002)!=-1)return false;
    *reinterpret_cast<unsigned*>(decoded+4+2*0x3c)=10002;decoded[4+2*0x3c+0x10]=1;
    if(logh::corpsChief(decoded,10002)!=-1 || logh::corpsChief(decoded,0)!=-1)return false;
    *reinterpret_cast<unsigned short*>(decoded)=601;if(logh::corpsChief(decoded,10000)!=-1)return false;
    BYTE enabled[24];logh::commanderMenu(true,enabled);
    for(unsigned i=0;i<24;++i)if(enabled[i]!=(i==20 || i==21 || i==23))return false;
    logh::commanderMenu(false,enabled);for(unsigned i=0;i<24;++i)if(enabled[i]!=(i==21))return false;
    BYTE strike[36];logh::orbitalStrikeRecord(strike,10000,7,true);
    if(*reinterpret_cast<unsigned*>(strike)!=10000 || *reinterpret_cast<unsigned short*>(strike+4)!=0x7f00 ||
        *reinterpret_cast<unsigned short*>(strike+6)!=230 || *reinterpret_cast<unsigned short*>(strike+32)!=7 || strike[16]!=1 || strike[8])return false;
    logh::orbitalStrikeRecord(strike,10000,8,false,1177,3,100);
    if(strike[16] || *reinterpret_cast<unsigned short*>(strike+10)!=100 ||
        *reinterpret_cast<unsigned*>(strike+20)!=3 || *reinterpret_cast<unsigned*>(strike+28)!=1177)return false;
    return true;
}
static bool fortressMenuAuthorityTest() {
    BYTE own[0x370]={};own[0x270]=1;unsigned base=1040;unsigned short kind=188;
    memcpy(own+0x274,&kind,2);memcpy(own+0x278,&base,4);
    if(logh::commandsFortress(own,sizeof(own),base))return false;
    kind=187;memcpy(own+0x274,&kind,2);
    if(!logh::commandsFortress(own,sizeof(own),base) || logh::commandsFortress(own,sizeof(own),1041) ||
       logh::commandsFortress(own,sizeof(own),0) || logh::commandsFortress(own,0x274,base))return false;
    kind=51;memcpy(own+0x274,&kind,2);
    if(!logh::commandsFortress(own,sizeof(own),base))return false;
    own[0x270]=0;return !logh::commandsFortress(own,sizeof(own),base);
}
static bool environmentShaderTest() {
    const char source[]="prefix\r\nm4x4 oT0.xy, r0, c12\r\nsuffix";char output[128]={};
    const char expected[]="prefix\r\ndp4 oT0.x, r0, c12\r\ndp4 oT0.y, r0, c13\r\nsuffix";
    unsigned n=logh::expandEnvironmentMatrix(source,sizeof(source)-1,output,sizeof(output));
    if(n!=sizeof(expected)-1 || strcmp(output,expected))return false;
    if(logh::expandEnvironmentMatrix(source,sizeof(source)-1,output,n) ||
       logh::expandEnvironmentMatrix(nullptr,20,output,sizeof(output)) ||
       logh::expandEnvironmentMatrix(source,sizeof(source)-1,nullptr,sizeof(output)) ||
       logh::expandEnvironmentMatrix("m4x4 oT0.xyzw, r0, c12",22,output,sizeof(output)))return false;
    const char duplicate[]="m4x4 oT0.xy, r0, c12\nm4x4 oT0.xy, r0, c12";
    if(logh::expandEnvironmentMatrix(duplicate,sizeof(duplicate)-1,output,sizeof(output)))return false;
    return logh::expandEnvironmentMatrix(source,sizeof(source)-1,output,n+1)==n && output[n]==0;
}
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
    if (logh::mailInputText("bad\x1b",128,value,949) ||
        !logh::mailInputText("\r\n\t",512,value,949) || value!=L"\r\n\t") return false;
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
static bool wideResourceTest() {
    const wchar_t original[]=L"銀河帝国";
    const auto row=logh::koreanWideResourceAt(0x78d9b8,original);
    if (!row || wcscmp(row->text,L"은하제국") || logh::koreanWideResource(original) ||
        logh::koreanWideText(original)!=original || logh::koreanWideEntry(0) ||
        logh::koreanWideResourceAt(0x78d9b8,nullptr) ||
        logh::koreanWideResourceAt(0x78d9b8,L"銀河帝国!") ||
        logh::koreanWideResourceAt(0x78d9b8,L"銀河帝") ||
        logh::koreanWideResourceAt(0x78d9b8,L"銀河帝國")) return false;
    unsigned count=0;
    for (const auto& entry:logh::koreanWideResources) {
        if (!entry.address) continue;
        std::wstring decoded;
        if (!logh::japaneseFragment(entry.narrow,decoded) || decoded!=entry.text) return false;
        ++count;
    }
    return count==121;
}
static bool mailDeliveryTest() {
    logh::MailDelivery delivery;
    int editor=0,session=0,other=0;
    if (!delivery.begin(&editor,&session,10000,10001,0x81234567,0x76543210,L"한글 제목",L"초안\n본문")) return false;
    if (delivery.begin(&editor,&session,10000,10001,0x81234568,0,L"중복",L"송신")) return false;
    delivery.accepted(&other,10000,10001,0x76543210);
    delivery.accepted(&session,10001,10000,0x76543210);
    delivery.accepted(&session,10000,10001,1);
    delivery.rejected(&session,0x0f11);
    if (delivery.response || delivery.body!=L"초안\n본문") return false;
    delivery.rejected(&session,0x0f10);
    if (delivery.response!=2 || !delivery.draftOpen || delivery.title!=L"한글 제목") return false;
    delivery.reset();
    if (!delivery.begin(&editor,&session,10000,10001,0x81234568,7,L"수정",L"재편집")) return false;
    delivery.accepted(&session,10000,10001,7);
    if (delivery.response!=1) return false;
    delivery.reset();
    return !delivery.pending && !delivery.context && delivery.title.empty() &&
        !delivery.begin(&editor,&session,10000,10001,0xffffffff,7,L"",L"");
}
static bool proposalApprovalTest() {
    unsigned char record[36];memset(record,0xcc,sizeof(record));
    logh::proposalApprovalRecord(record,10001,7,17);
    const unsigned char expected[36]={0x11,0x27,0,0, 0,0x7f, 99,0, 0,0, 3,0, 7,0,0,0, 0,0,0,0, 0,0,0,0, 0,0,0,0, 0,0,0,0, 17,0, 0,0};
    if(memcmp(record,expected,sizeof(record)))return false;
    using State=logh::ProposalApproval;
    State state;int world=0,other=0;
    if(state.begin(nullptr,10001,7,17,0) || state.begin(&world,0,7,17,0) || state.begin(&world,10001,0,17,0) ||
        state.begin(&world,10001,7,0,0) || state.outstanding)return false;
    if(!state.begin(&world,10001,7,17,1000))return false;
    if(state.begin(&world,10001,8,18,1000) || state.mail!=7 || state.token!=17)return false; // no duplicate while open
    // Another character, world, mail or token and a preview-like flag are all stale.
    if(state.receive(&world,10002,7,17,0,2) || state.receive(&other,10001,7,17,0,2) ||
        state.receive(&world,10001,8,17,0,2) || state.receive(&world,10001,7,18,0,2) ||
        state.receive(&world,10001,7,17,0,1) || !state.outstanding || state.outcome!=State::Waiting)return false;
    state.observe(&world,10001);
    if(!state.outstanding || state.expire(1000+logh::proposalApprovalWait-1))return false;
    if(!state.receive(&world,10001,7,17,0,2) || state.outcome!=State::Accepted || !state.settled())return false;
    if(state.begin(&world,10001,7,18,1000))return false; // a result not yet shown still blocks
    if(state.receive(&world,10001,7,17,0,2))return false; // repeated answer
    state.clear();
    const unsigned refusals[][2]={{10,3},{0,3},{8,2}}; // only status 0 flag 2 is accepted
    for(const auto& answer:refusals) {
        if(!state.begin(&world,10001,7,18,0) || !state.receive(&world,10001,7,18,answer[0],answer[1]) ||
            state.outcome!=State::Rejected || state.status!=answer[0])return false;
        state.clear();
    }
    // A late answer after the wait is stale; a logout drops the request.
    if(!state.begin(&world,10001,7,19,0) || !state.expire(logh::proposalApprovalWait) || state.outcome!=State::Unconfirmed ||
        state.outstanding || state.receive(&world,10001,7,19,0,2) || state.outcome!=State::Unconfirmed)return false;
    state.observe(&world,10002);
    if(state.settled() || state.mail)return false;
    if(!state.begin(&world,10001,7,20,0))return false;
    state.observe(&other,10001);
    if(state.outstanding || state.receive(&world,10001,7,20,0,2))return false;
    return wcscmp(logh::proposalApprovalFailure(10),logh::proposalApprovalFailure(8)) &&
        wcscmp(logh::proposalApprovalFailure(99),logh::proposalApprovalFailure(0));
}
static void* detailCaptionTable=nullptr;
static unsigned detailCaptionGroup=0,detailCaptionIndex=0;
static const char* __fastcall detailCaptionProbe(void* table,void*,unsigned group,unsigned index) {
    detailCaptionTable=table;detailCaptionGroup=group;detailCaptionIndex=index;
    return "caption";
}
/** Runs the wrapper on the stack 0053b588 has: row kind, parent's return, four saved registers, -1, index, group. */
static bool detailCaptionCall(int rowKind,unsigned rowGroup,unsigned rowIndex,unsigned expected) {
    unsigned catalogue=0x2217400,stackBefore=0,stackAfter=0,remaining=0,kindSlot=0;
    const char* caption=nullptr;
    detailCaptionTable=nullptr;detailCaptionGroup=detailCaptionIndex=0;
    __asm {
        mov stackBefore,esp
        push rowKind
        push 0x11111111
        push 0x22222222
        push 0x33333333
        push 0x44444444
        push 0x55555555
        push -1
        push rowIndex
        push rowGroup
        mov ecx,catalogue
        call proposalDetailCaption
        mov caption,eax
        mov eax,dword ptr [esp]
        mov remaining,eax
        mov eax,dword ptr [esp+0x18]
        mov kindSlot,eax
        add esp,0x1c
        mov stackAfter,esp
    }
    return stackBefore==stackAfter && remaining==0xffffffff && kindSlot==static_cast<unsigned>(rowKind) && caption && !strcmp(caption,"caption") &&
        detailCaptionTable==reinterpret_cast<void*>(0x2217400) && detailCaptionGroup==rowGroup && detailCaptionIndex==expected;
}
static bool proposalDetailCaptionTest() {
    if(proposalDetailCaptionCall+5+*reinterpret_cast<const unsigned*>(proposalDetailCaptionExpected+1)!=0x522010)return false;
    const unsigned lookup=proposalDetailLookup;
    proposalDetailLookup=reinterpret_cast<unsigned>(&detailCaptionProbe);
    bool passed=true;
    for(int kind:{0x0a,0x14})passed=passed && detailCaptionCall(kind,0x16,0x4d,0x4c); // unreplied order and proposal
    for(int base:{0x0a,0x14})for(int digit:{1,2,3,4,5,6,9})
        passed=passed && detailCaptionCall(base+digit,0x16,0x4d,0x4d); // replied and approved keep the original text
    // Only 22/77 changes, even on a pending row.
    passed=passed && detailCaptionCall(0x14,0x15,0x4d,0x4d) && detailCaptionCall(0x14,0x16,0x4e,0x4e) && detailCaptionCall(0x14,0x16,0x4c,0x4c);
    proposalDetailLookup=lookup;
    return passed;
}
static bool mailRefreshTest() {
    unsigned char peer[0x124]={};
    const unsigned face=1000001,age=35u*31536000u;
    memcpy(peer+0x118,&face,4);memcpy(peer+0x11c,&age,4);
    if(!logh::personalMailAddress(peer) || logh::personalMailAddress(nullptr))return false;
    for(unsigned offset=0x120;offset<0x124;++offset) {
        peer[offset]=1;if(logh::personalMailAddress(peer))return false;peer[offset]=0;
    }
    logh::MailRefresh refresh;int world=0,other=0;
    refresh.observe(&world,10000,true);
    refresh.begin(&world,10001,true,true);refresh.end(&world);
    if(refresh.take(&world,10000,true))return false;
    refresh.begin(&world,10000,false,true);refresh.end(&world);
    refresh.begin(&world,10000,true,false);refresh.end(&world);
    if(refresh.take(&world,10000,true))return false;
    refresh.begin(&world,10000,true,true);
    if(refresh.take(&world,10000,true))return false; // Wait for transaction end.
    refresh.end(&world);
    refresh.observe(&world,10000,false); // A draft opened before the transaction ended.
    if(refresh.take(&world,10000,false) || !refresh.pending)return false;
    if(!refresh.take(&world,10000,true) || refresh.take(&world,10000,true))return false;
    refresh.observe(&world,10000,false); // Native refresh's own query must not recurse.
    refresh.begin(&world,10000,true,true);refresh.end(&world);
    if(refresh.pending)return false;
    refresh.observe(&world,10000,true); // Idle native queue while editing.
    refresh.begin(&world,10000,true,true);refresh.end(&world);
    if(refresh.take(&world,10000,false) || !refresh.pending)return false;
    if(!refresh.take(&world,10000,true) || refresh.take(&world,10000,true))return false;
    refresh.begin(&world,10000,true,true);refresh.end(&world);
    refresh.observe(&other,10000,true);
    if(refresh.pending || refresh.take(&world,10000,true))return false;
    refresh.begin(&other,10000,true,true);refresh.end(&other);
    refresh.observe(nullptr,0,false);
    return !refresh.pending && !refresh.world && !refresh.owner;
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
static bool tacticalChargeDataTest() {
    BYTE unit[0x9ec]={},row[60]={},before[sizeof(unit)];
    unsigned character=10000,other=10001;
    unit[0]=unit[8]=1;memcpy(unit+0x984,&character,4);memcpy(row,&character,4);
    unsigned short beam=100,gun=0;
    memcpy(row+30,&beam,2);memcpy(row+32,&gun,2);
    if (!logh::applyTacticalFlagshipCharge(unit,row) || memcmp(unit+0x9ce,row+30,4)) return false;
    beam=0;gun=100;memcpy(row+30,&beam,2);memcpy(row+32,&gun,2);
    if (!logh::applyTacticalFlagshipCharge(unit,row) || memcmp(unit+0x9ce,row+30,4)) return false;
    memcpy(before,unit,sizeof(unit));
    for (unsigned offset : {30u,32u}) {
        row[offset]=101;
        if (logh::applyTacticalFlagshipCharge(unit,row) || memcmp(unit,before,sizeof(unit))) return false;
        memcpy(row+30,&beam,2);memcpy(row+32,&gun,2);
    }
    memcpy(row,&other,4);
    if (logh::applyTacticalFlagshipCharge(unit,row) || memcmp(unit,before,sizeof(unit))) return false;
    memcpy(row,&character,4);
    for (unsigned offset : {0u,8u,9u}) {
        unit[offset]=offset==9 ? 1 : 0;
        memcpy(before,unit,sizeof(unit));
        if (logh::applyTacticalFlagshipCharge(unit,row) || memcmp(unit,before,sizeof(unit))) return false;
        unit[offset]=offset==9 ? 0 : 1;
    }
    return !logh::applyTacticalFlagshipCharge(nullptr,row) && !logh::applyTacticalFlagshipCharge(unit,nullptr);
}
// Observed refusal: BEAM 20 dragged to 95, the detained 040c reply keeps 20.
static bool tacticalEnergyDataTest() {
    if (logh::tacticalEnergyRecord+logh::tacticalEnergyField!=0x30a ||
        logh::tacticalEnergyRecord+logh::tacticalEnergyField+logh::tacticalEnergyCount-1!=0x314) return false;
    BYTE before[0x20]={},after[0x20]={},reply[0x20]={};
    unsigned character=10000,flagship=1010000,seconds=500,other=10001;
    memcpy(before+8,&character,4);memcpy(before+12,&flagship,4);
    before[0x12]=20;before[0x1a]=40; // beam, engine
    memcpy(reply,before,sizeof(reply));memcpy(reply,&seconds,4);
    memcpy(after,reply,sizeof(after)); // 004c1700 copied this reply to the own corps.
    if (!logh::tacticalEnergyKept(before,after,reply)) return false;
    int hold=0x78;
    if (logh::releaseTacticalEnergyHold(hold,1) || hold!=0x78) return false; // Newer slider change queued.
    if (!logh::releaseTacticalEnergyHold(hold,0) || hold) return false;
    reply[0x12]=after[0x12]=95; // Accepted: the native hold stays untouched.
    if (logh::tacticalEnergyKept(before,after,reply)) return false;
    reply[0x12]=after[0x12]=20;
    for (unsigned field=0x12;field<0x1d;++field) { // Every slider byte counts.
        reply[field]^=1;after[field]^=1;
        if (logh::tacticalEnergyKept(before,after,reply)) return false;
        reply[field]^=1;after[field]^=1;
    }
    memcpy(after+8,&other,4); // Another flagship's reply leaves the own copy alone.
    if (logh::tacticalEnergyKept(before,after,reply)) return false;
    memcpy(after+8,&character,4);
    return logh::tacticalEnergyKept(before,after,reply) && !logh::tacticalEnergyKept(nullptr,after,reply) &&
        !logh::tacticalEnergyKept(before,nullptr,reply) && !logh::tacticalEnergyKept(before,after,nullptr);
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
    int32_t expired=-118;unsigned positive=840;
    memcpy(object+0x5bc,&expired,4);memcpy(object+0x5c0,&positive,4);
    logh::clampFortressCharge(object);logh::clampFortressCharge(nullptr);
    if(logh::baseMessageWord(object,0x5bc)!=0 || logh::baseMessageWord(object,0x5c0)!=840)return false;
    memcpy(object+0x5c0,&expired,4);logh::clampFortressCharge(object);
    if(logh::baseMessageWord(object,0x5c0)!=0 || object[0x5bb]!=0xa5 || object[0x5c4]!=0xa5)return false;
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
    if (!logh::storeDisplayName(L"전함Ⅰ",stored,13) ||
        !logh::japaneseFragment(stored.c_str(),decoded) || decoded!=L"전함Ⅰ") return false;
    std::wstring shipMaximum(12,L'힣'),shipTooLong(13,L'힣');
    if (!logh::storeDisplayName(shipMaximum.c_str(),stored,13) ||
        !logh::japaneseFragment(stored.c_str(),decoded) || decoded!=shipMaximum) return false;
    auto shipSaved=stored;
    wchar_t unterminatedShip[13];for (auto& ch:unterminatedShip)ch=L'가';
    if (logh::storeDisplayName(shipTooLong.c_str(),stored,13) ||
        logh::storeDisplayName(unterminatedShip,stored,13) ||
        logh::storeDisplayName(L"",stored,0) ||
        logh::storeDisplayName(L"",stored,29) || stored!=shipSaved) return false;
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
// Call each rate-row thunk with the native registers of its slot (world, kind*9) and return its output register.
static __declspec(naked) const BYTE* __stdcall flagshipBeamRowAt(BYTE*,unsigned) {
    __asm { push ebp
            mov ecx,[esp+8]
            mov eax,[esp+12]
            call flagshipBeamRateRow
            mov eax,ebp
            pop ebp
            ret 8 }
}
static __declspec(naked) const BYTE* __stdcall flagshipGunRowAt(BYTE*,unsigned) {
    __asm { mov ecx,[esp+4]
            mov eax,[esp+8]
            call flagshipGunRateRow
            ret 8 }
}
static __declspec(naked) const BYTE* __stdcall flagshipMissileRowAt(BYTE*,unsigned) {
    __asm { mov eax,[esp+4]
            mov edx,[esp+8]
            call flagshipMissileRateRow
            ret 8 }
}
static __declspec(naked) const BYTE* __stdcall flagshipAntiAirRowAt(BYTE*,unsigned) {
    __asm { mov edx,[esp+4]
            mov ecx,[esp+8]
            call flagshipAntiAirRateRow
            ret 8 }
}
static bool flagshipRateRowTest() {
    const SIZE_T size=0x342000; // Past row 255, where the native address calculation lands.
    auto world=static_cast<BYTE*>(VirtualAlloc(nullptr,size,MEM_RESERVE|MEM_COMMIT,PAGE_READWRITE));
    if (!world) return false;
    auto nativeRow=[&](unsigned kind) { return world+0x340828+kind*0x12; };
    *nativeRow(255)=1;nativeRow(255)[2]=101; // The bogus data seen beyond the table must not be selected.
    const BYTE* (__stdcall* const slots[])(BYTE*,unsigned)={flagshipBeamRowAt,flagshipGunRowAt,flagshipMissileRowAt,flagshipAntiAirRowAt};
    bool passed=!flagshipAbsentWeaponRow[0];
    for (auto slot : slots) {
        // Installed kinds, the last table row and a genuine unknown kind keep the native row.
        for (unsigned kind : {0u,1u,16u,26u,199u,200u,254u}) passed=passed && slot(world,kind*9)==nativeRow(kind);
        passed=passed && slot(world,logh::flagshipWeaponNone*9)==flagshipAbsentWeaponRow;
    }
    VirtualFree(world,0,MEM_RELEASE);
    return passed;
}
static bool flagshipWeaponLabelTest() {
    const unsigned group=logh::flagshipWeaponGroup,none=logh::flagshipWeaponNone;
    if (!logh::flagshipWeaponAbsent(group,none,"NO DATA")) return false;
    // A genuine unknown kind, a missing table, a named kind 255, another group and a null stay native.
    if (logh::flagshipWeaponAbsent(group,254,"NO DATA") || logh::flagshipWeaponAbsent(group,0,"NO DATA") ||
        logh::flagshipWeaponAbsent(group,none,"NO TABLE") || logh::flagshipWeaponAbsent(group,none,"weapon") ||
        logh::flagshipWeaponAbsent(group+1,none,"NO DATA") || logh::flagshipWeaponAbsent(group,none,nullptr)) return false;
    std::string encoded;std::wstring decoded;
    return logh::encodeKoreanResource(L"미장착",encoded) && encoded.size()==6 &&
        logh::japaneseFragment(encoded.c_str(),decoded) && decoded==L"미장착";
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
    if (strcmp(logh::tacticalMissionNotice("LOGH7_TACTICAL_MISSION_RETREAT", false), "LOGH7_TACTICAL_MISSION_PROPOSED") ||
        strcmp(logh::tacticalMissionNotice("LOGH7_TACTICAL_MISSION_RETREAT", true), "LOGH7_TACTICAL_MISSION_RETREAT") ||
        logh::tacticalMissionNotice(missing, false) != missing) return false;
    {
        std::string encoded;std::wstring back;
        if (!logh::encodeKoreanResource(L"[ \ub300\uc9c0 \uacf5\uaca9 ]\n\uc801 \ud589\uc131\uc758 \uc218\ube44\ub300\ub97c \uacf5\uaca9\ud569\ub2c8\ub2e4.\n\uae30\ud568 \ubb3c\uc790 100",encoded))return false;
        if (logh::encodeKoreanResource(L"\u3042",encoded))return false;
        if (!logh::hasHangul(L"A\uc591") || logh::hasHangul(L"\u30e4\u30f3") || logh::hasHangul(nullptr))return false;
        logh::encodeKoreanResource(L"A\uac00",encoded);
        if (encoded.size()!=3 || encoded[0]!='A' || static_cast<unsigned char>(encoded[1])!=0xf0 || static_cast<unsigned char>(encoded[2])!=0x41)return false;
    }
    if (logh::tacticalNoticeFallback(118, 0, missing) != missing ||
        logh::tacticalNoticeFallback(118, 63, missing) != missing) return false;
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
static bool tacticalClockScaleTest() {
    const unsigned ticks[]={0,12561,25087,37631};
    const unsigned games[]={0,301,602,903};
    double integrated=0;
    for (unsigned i=1;i<4;++i) integrated+=logh::tacticalClockScale(
        games[i-1],games[i],ticks[i-1],ticks[i],1.0f)*(ticks[i]-ticks[i-1])*24.0/1000.0;
    if (fabs(integrated-903.0)>0.001) return false;
    if (logh::tacticalClockScale(0,600,0,12500,1)!=2.0f ||
        fabs(logh::tacticalClockScale(0,12,0,12000,1)-1.0f/24.0f)>0.000001f) return false;
    return logh::tacticalClockScale(20,20,10,20,0.5f)==0.5f &&
        logh::tacticalClockScale(20,19,10,20,0.5f)==0.5f &&
        logh::tacticalClockScale(20,21,20,20,0.5f)==0.5f &&
        logh::tacticalClockScale(20,21,0xfffffff0,10,0.5f)==0.5f;
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
    // The empty roster reply keeps the queried base at +4; only number +16 and rows +20 decide.
    unsigned char emptyRoster[24]={},numbered[24]={},withRows[24]={};
    const unsigned queriedBase=1049,fleetNumber=1;
    memcpy(emptyRoster+4,&queriedBase,4);memcpy(numbered+4,&queriedBase,4);memcpy(withRows+4,&queriedBase,4);
    memcpy(numbered+16,&fleetNumber,4);withRows[20]=1;
    if (!logh::reorganizationWithoutOutfit(63,0,emptyRoster) || logh::reorganizationWithoutOutfit(25,0,emptyRoster) ||
        logh::reorganizationWithoutOutfit(64,0,emptyRoster) || logh::reorganizationWithoutOutfit(63,133,emptyRoster) ||
        logh::reorganizationWithoutOutfit(63,0,numbered) || logh::reorganizationWithoutOutfit(63,0,withRows)) return false;
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
static bool groundTransferReasonTest() {
    unsigned char record[36]={};unsigned reason=99;
    auto value=[&](unsigned word){memcpy(record+24,&word,4);};
    if(logh::groundTransferReason(record,reason) || reason!=99)return false; // Plain success.
    record[20]=1;value(0x4c470008);
    if(!logh::groundTransferReason(record,reason) || reason!=8)return false; // Observed refusal.
    value(0x4c470040);if(!logh::groundTransferReason(record,reason) || reason!=0x40)return false;
    reason=99;value(8);if(logh::groundTransferReason(record,reason) || reason!=99)return false; // Native value.
    value(0x4c480008);if(logh::groundTransferReason(record,reason))return false;
    record[20]=2;value(0x4c470008);return !logh::groundTransferReason(record,reason) && reason==99;
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
    const unsigned awards[]={250,933,12,21};memcpy(record+0x2d4,awards,sizeof(awards));
    logh::SessionEndingData data;
    if(!data.read(record) || data.character!=id || data.power!=2 || data.reason!=3 || data.name[0]!='A' ||
        data.sides[0].power!=2 || data.sides[0].grade!=0 || data.sides[0].statistics.planets!=9 ||
        data.sides[1].grade!=3 || data.sides[1].populationCondition!=255 || data.total.planets!=16 ||
        !data.evaluationPresent || data.previousEvaluation!=250 || data.finalEvaluation!=933 ||
        data.previousFame!=12 || data.finalFame!=21)return false;
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
    memcpy(lobby+0x35c,awards,sizeof(awards));
    if(!data.readLobby(lobby,sizeof(lobby),0) || data.character!=id || data.sides[0].grade!=255 || data.total.planets!=16 ||
        !data.evaluationPresent || data.previousEvaluation!=250 || data.finalEvaluation!=933 ||
        data.previousFame!=12 || data.finalFame!=21)return false;
    lobby[0x358]=0;if(!data.readLobby(lobby,sizeof(lobby),0) || data.evaluationPresent)return false;lobby[0x358]=1;
    if(data.readLobby(lobby,0x36b,0) || data.readLobby(lobby,sizeof(lobby),1) || data.readLobby(nullptr,0,0))return false;
    lobby[0]=2;memcpy(lobby+0x36c+4,lobby+4,0x368);
    lobby[0x36c+0x2a8]=3;
    if(!data.readLobby(lobby,sizeof(lobby),1) || data.power!=3 || data.finalFame!=21)return false;
    for(unsigned offset:{0u,0x2a0u,0x2a1u,0x358u}) {
        BYTE before=lobby[offset];lobby[offset]=4;
        if(data.readLobby(lobby,sizeof(lobby),0))return false;lobby[offset]=before;
    }
    const unsigned maximum=0xffffffffu;memcpy(record+0x2e0,&maximum,4);
    if(!data.read(record) || data.finalFame!=maximum)return false;
    return !wcscmp(logh::endingGrade(2),L"결정적 승리") && !wcscmp(logh::endingGrade(255),L"승패 집계 중");
}
// An uncatalogued Hangul syllable (a player's name) takes a runtime EUDC slot and comes back unchanged.
static bool runtimeKoreanGlyphTest() {
    std::string narrow;std::wstring wide;
    if (!logh::encodeKoreanResource(L"김하나 이",narrow) || narrow.size()!=9) return false;
    if (!logh::japaneseFragment(narrow.c_str(),wide) || wide!=L"김하나 이") return false;
    size_t first=0,again=0;
    if (!logh::koreanGlyphIndex(0xae40,first) || !logh::koreanGlyphIndex(0xae40,again) || first!=again || first<logh::koreanGlyphCount)
        return false;
    wchar_t value=0;char bytes[2]={};const wchar_t slot=static_cast<wchar_t>(0xe000+first);
    if (WideCharToMultiByte(932,WC_NO_BEST_FIT_CHARS,&slot,1,bytes,2,nullptr,nullptr)!=2) return false;
    if (!logh::legacyKoreanGlyph((static_cast<unsigned char>(bytes[0])<<8)|static_cast<unsigned char>(bytes[1]),value) || value!=0xae40)
        return false;
    size_t none=0;
    return !logh::koreanGlyphIndex(0x3042,none) && !logh::koreanGlyphIndex(0xae41,none,false);
}
static int facilityGuideTest() {
    std::string label;
    if(!logh::facilityLabel(104901,0,L"오딘 민정청",label))return 620;
    std::wstring shown(label.begin(),label.end());
    if(!logh::restoreDisplayNames(shown) || shown!=L"오딘 민정청")return 621;
    if(!logh::facilityLabel(104901,0,std::wstring(24,L'가').c_str(),label))return 622;
    shown.assign(label.begin(),label.end());
    if(!logh::restoreDisplayNames(shown) || shown!=std::wstring(11,L'가')+L"…")return 623;
    const auto saved=label;
    if(logh::facilityLabel(104902,0,L"시설",label) || logh::facilityLabel(104901,39,L"시설",label) ||
        logh::facilityLabel(0,0,L"시설",label) || logh::facilityLabel(104901,0,std::wstring(25,L'가').c_str(),label) ||
        logh::facilityLabel(104901,0,L"줄\n바꿈",label) || label!=saved)return 624;
    if(logh::facilityMoveName(L"은하제국오딘행성중앙행정민원통합지원센터본관")!=L"은하제국…터본관" ||
        logh::facilityMoveName(L"에너지 플랜트")!=L"에너지 플랜트" ||
        logh::facilityMoveName(L"Regional Office 20")!=L"Regional…ice 20")return 653;
    logh::FacilityNameCache names;logh::FacilityGuide value;value.id=101;value.revision=2;wcscpy_s(value.name,L"첫 시설");
    if(!names.publish(value,1))return 625;
    value.revision=1;if(names.publish(value,2) || names.find(101)->revision!=2)return 626;
    for(unsigned i=2;i<=351;++i){value.id=i*100+1;if(!names.publish(value,i))return 627;}
    if(names.count!=350 || names.find(101) || !names.find(201) || !names.find(35101))return 628;
    value.id=201;names.publish(value,400);value.id=35201;names.publish(value,401);
    if(!names.find(201) || names.find(301) || !names.find(35201))return 629;
    BYTE record[24+10*36]={};
    auto make=[&](unsigned page,const std::wstring& text,const wchar_t* revision=L"17") {
        memset(record,0,sizeof(record));unsigned id=105038,token=9*16+page;
        memcpy(record,&id,4);memcpy(record+4,&token,4);record[8]=6;record[20]=10;
        for(unsigned i=0;i<10;++i) {
            auto row=record+24+i*36;unsigned number=i+1;memcpy(row,&number,4);row[4]=static_cast<BYTE>(i);
            std::wstring part=i==0 ? revision : i==1 ? L"" : text.substr((i-2)*12<text.size() ? (i-2)*12 : text.size(),12);
            row[6]=static_cast<BYTE>(part.empty() ? 0 : part.size()+1);
            if(!part.empty())memcpy(row+8,part.c_str(),(part.size()+1)*2);
        }
    };
    logh::FacilityGuideDraft draft;draft.begin(105038,9);
    make(0,L"새 시설");if(draft.accept(record,sizeof(record))!=1)return 610;
    make(1,L"첫 용도",L"18");if(draft.accept(record,sizeof(record))!=-1 || draft.next!=1)return 611;
    make(0,L"stale");if(draft.accept(record,sizeof(record)) || draft.next!=1)return 612;
    const std::wstring purpose(200,L'용'),usage(200,L'조'),commands(192,L'명');
    for(unsigned page=1;page<9;++page) {
        const auto& full=page<=3 ? purpose : page<=6 ? usage : commands;
        unsigned offset=(page<=3 ? page-1 : page<=6 ? page-4 : page-7)*96;
        make(page,full.substr(offset,96));
        if(draft.accept(record,sizeof(record))!=(page==8 ? 2 : 1))return 613;
    }
    if(wcscmp(draft.value.name,L"새 시설") || draft.value.purpose!=purpose || draft.value.usage!=usage || draft.value.commands!=commands)return 614;
    std::wstring wrapped;
    for(unsigned line=0;line<8;++line){wchar_t text[97];logh::facilityGuideLine(draft.value.purpose,line,text);wrapped+=text;}
    if(wrapped!=purpose)return 615;
    std::wstring spaced;
    while(spaced.size()<200)spaced+=std::wstring(18,L'용')+L" ";
    spaced.resize(200);wrapped.clear();
    for(unsigned line=0;line<8;++line){wchar_t text[97];logh::facilityGuideLine(spaced.c_str(),line,text);wrapped+=text;}
    if(wrapped!=spaced)return 619;
    draft.begin(105038,9);make(0,std::wstring(25,L'가'));if(draft.accept(record,sizeof(record)))return 616;
    make(0,L"잘못된 이름");record[24+2*36+8]=0;record[24+2*36+9]=0xd8;
    if(draft.accept(record,sizeof(record)) || draft.next)return 617;
    make(0,L"이름");if(draft.accept(record,sizeof(record)-1) || draft.next)return 618;
    return 0;
}

static int lobbyCountryDataTest() {
    BYTE record[24+10*36]={};
    auto packet=[&](unsigned kind,unsigned number,const wchar_t* const (&fields)[10]) {
        memset(record,0,sizeof(record));*reinterpret_cast<unsigned*>(record)=12;
        *reinterpret_cast<unsigned*>(record+4)=7*512+348;record[8]=7;record[9]=2;record[20]=10;
        *reinterpret_cast<unsigned*>(record+12)=kind;*reinterpret_cast<unsigned*>(record+16)=number;
        for(unsigned i=0;i<10;++i) {
            auto p=record+24+i*36;*reinterpret_cast<unsigned*>(p)=i+1;p[4]=static_cast<BYTE>(i);
            unsigned n=static_cast<unsigned>(wcslen(fields[i]));if(n){p[6]=static_cast<BYTE>(n+1);memcpy(p+8,fields[i],n*2);}
        }
    };
    const wchar_t* header[]={L"NC1",L"350",L"348",L"2",L"900719925474",L"0993",L"172800",L"1",L"",L"283"};
    const wchar_t* row[]={L"42",L"새 국가 이름",L"",L"A1B2C3",L"123",L"14",L"2",L"0",L"",L"0"};
    logh::LobbyCountryDraft draft;
    if(!draft.begin(12,7,348) || draft.begin(12,128,0) || draft.begin(12,1,351))return 1001;
    packet(1,0,row);if(draft.accept(record,sizeof(record)))return 1002;
    packet(0,0,header);if(draft.accept(record,sizeof(record)-1) || draft.accept(record,sizeof(record))!=1)return 1003;
    packet(1,0,row);record[4]^=1;if(draft.accept(record,sizeof(record)))return 1004;record[4]^=1;
    auto malformed=*reinterpret_cast<unsigned short*>(record+24+36+8);
    *reinterpret_cast<unsigned short*>(record+24+36+8)=10;
    if(draft.accept(record,sizeof(record)))return 1005;
    *reinterpret_cast<unsigned short*>(record+24+36+8)=malformed;
    if(draft.accept(record,sizeof(record))!=1 || draft.complete)return 1006;
    packet(1,1,row);if(draft.accept(record,sizeof(record)))return 1007;
    row[0]=L"2147483647";row[3]=L"inherit";row[9]=L"3";packet(1,1,row);
    if(draft.accept(record,sizeof(record))!=2 || !draft.complete || draft.page.total!=350 || draft.page.offset!=348 ||
        draft.page.revision!=9007199254740993ULL || draft.page.generation!=1 || draft.page.seconds!=172800 ||
        draft.page.totalBases!=283 || draft.page.rows[0].id!=42 || draft.page.rows[0].rgb!=0xA1B2C3 ||
        !draft.page.rows[0].hasRgb || draft.page.rows[0].characters!=123 || draft.page.rows[0].bases!=14 ||
        wcscmp(draft.page.rows[0].name,L"새 국가 이름") || draft.page.rows[1].hasRgb || draft.accept(record,sizeof(record)))return 1008;
    draft.begin(12,7,350);header[2]=L"350";header[3]=L"0";packet(0,0,header);
    *reinterpret_cast<unsigned*>(record+4)=7*512+350;
    if(draft.accept(record,sizeof(record))!=2 || draft.page.count)return 1009;
    draft.begin(12,7,350);header[1]=L"0";header[4]=L"0";header[5]=L"";header[6]=L"0";header[7]=L"0";header[9]=L"0";
    packet(0,0,header);*reinterpret_cast<unsigned*>(record+4)=7*512+350;
    if(draft.accept(record,sizeof(record))!=2 || draft.page.generation || draft.page.total)return 1014;
    BYTE sessions[4+64*0x14c]={};sessions[1]=64;
    for(unsigned i=0;i<64;++i) {
        auto q=sessions+4+i*0x14c;q[0]=static_cast<BYTE>(i+1);q[2]=1;q[3]=2;q[4]='S';
        *reinterpret_cast<unsigned*>(q+0xa4)=86400+i;
    }
    logh::LobbySessionList list;
    if(!logh::readLobbySessionList(sessions,sizeof(sessions),list) || list.count!=64 ||
        list.rows[63].id!=64 || list.rows[63].seconds!=86463 || wcscmp(list.rows[63].name,L"S"))return 1010;
    if(logh::readLobbySessionList(sessions,sizeof(sessions)-1,list))return 1011;
    sessions[4+0x14c]=1;if(logh::readLobbySessionList(sessions,sizeof(sessions),list))return 1012;
    sessions[1]=65;if(logh::readLobbySessionList(sessions,sizeof(sessions),list))return 1013;
    logh::LobbyCountrySelection choice;
    if(!choice.begin(65535,0x7fffffff,8,32767) || choice.queryBase()!=0x7fffffff ||
        choice.begin(0,42,8,1) || choice.begin(1,42,8,0) || choice.begin(1,0,9,1) || choice.begin(1,42,10,1))return 1015;
    if(!logh::LobbyCountrySelection{}.begin(1,0,8,1))return 1015;
    const wchar_t* ack[]={L"NS1",L"1",L"3",L"empire",L"",L"",L"",L"",L"",L""};
    auto selectionPacket=[&]() {
        packet(0,0,ack);*reinterpret_cast<unsigned*>(record)=0x7fffffff;
        *reinterpret_cast<unsigned*>(record+4)=0x7fffffff;record[8]=8;
    };
    selectionPacket();record[4]^=1;if(choice.accept(record,sizeof(record)))return 1016;record[4]^=1;
    record[8]=9;if(choice.accept(record,sizeof(record)))return 1017;record[8]=8;
    if(choice.accept(record,sizeof(record)-1) || !choice.accept(record,sizeof(record)) || !choice.complete ||
        !choice.accepted || choice.power!=3 || choice.accept(record,sizeof(record)))return 1018;
    choice.begin(65535,0x7fffffff,8,32767);ack[2]=L"42";selectionPacket();
    if(choice.accept(record,sizeof(record)))return 1019;
    ack[1]=L"0";ack[2]=L"0";ack[3]=L"";selectionPacket();
    if(!choice.accept(record,sizeof(record)) || choice.accepted || choice.power)return 1020;
    choice.begin(1,0,10,1);ack[1]=L"1";packet(0,0,ack);
    *reinterpret_cast<unsigned*>(record)=0;*reinterpret_cast<unsigned*>(record+4)=65537;record[8]=10;
    if(!choice.accept(record,sizeof(record)) || !choice.accepted || choice.power)return 1021;
    const wchar_t* identityFields[]={L"NI1",L"42",L"새 제국",L"",L"신제국력",L"-798",L"0",L"",L"3",L"1"};
    packet(0,0,identityFields);*reinterpret_cast<unsigned*>(record)=10000;
    *reinterpret_cast<unsigned*>(record+4)=43;record[8]=11;
    logh::CountryIdentity identity;
    if(logh::readCountryIdentity(record,sizeof(record),10001,43,identity) ||
        logh::readCountryIdentity(record,sizeof(record),10000,44,identity) ||
        !logh::readCountryIdentity(record,sizeof(record),10000,43,identity) || identity.country.id!=42 ||
        identity.power!=3 || !identity.registered || identity.country.yearOffset!=-798)return 1022;
    // Persistent wounded bit 0x20 is read from the same authenticated record; older servers leave it clear.
    record[10]=logh::ownWoundedFlag;
    if(!logh::readCountryIdentity(record,sizeof(record),10000,43,identity) || !identity.wounded)return 1025;
    record[10]=0x1f;
    if(!logh::readCountryIdentity(record,sizeof(record),10000,43,identity) || identity.wounded)return 1026;
    record[10]=0;
    auto identityWorld=reinterpret_cast<void*>(0x1234);
    publishCountryMetadata(identity.country);publishOwnCountryIdentity(identityWorld,identity);
    wchar_t identityCp[32],identityDate[96];
    if(runtimeOwnCountry(identityWorld,10000,3)!=42 || runtimeOwnCountry(identityWorld,10001,3)!=3 ||
        runtimeOwnCountry(identityWorld,10000,2)!=2 ||
        !runtimeNavigationHud(0,1000,675,runtimeOwnCountry(identityWorld,10000,3),identityCp,identityDate) ||
        !wcsstr(identityDate,L"신제국력"))return 1023;
    resetCountryMetadata();if(runtimeOwnCountry(identityWorld,10000,3)!=3)return 1024;
    return 0;
}

static int baseAffiliationTest() {
    BYTE record[24+10*36]={};
    auto frame=[&](unsigned kind,unsigned number,unsigned count) {
        memset(record,0,sizeof(record));*reinterpret_cast<unsigned*>(record+4)=65535;
        record[8]=12;record[9]=2;record[20]=static_cast<BYTE>(count);
        *reinterpret_cast<unsigned*>(record+12)=kind;*reinterpret_cast<unsigned*>(record+16)=number;
    };
    auto member=[&](unsigned slot,unsigned id,unsigned camp,unsigned power,const wchar_t* text) {
        auto p=record+24+slot*36;*reinterpret_cast<unsigned*>(p)=id;p[4]=static_cast<BYTE>(camp);p[5]=static_cast<BYTE>(power);
        p[6]=static_cast<BYTE>(wcslen(text)+1);memcpy(p+8,text,(wcslen(text)+1)*2);
    };
    auto header=[&](const wchar_t* total) {
        frame(0,0,10);for(unsigned i=0;i<10;++i)member(i,i+1,i,0,i==0 ? L"BA1" : i==1 ? total : L"");
    };
    logh::BaseAffiliationDraft draft;draft.begin(65535);
    frame(1,0,3);if(draft.accept(record,sizeof(record)))return 1030;
    header(L"3");record[4]^=1;if(draft.accept(record,sizeof(record)))return 1031;record[4]^=1;
    if(draft.accept(record,sizeof(record)-1) || draft.accept(record,sizeof(record))!=1 || draft.accept(record,sizeof(record)))return 1032;
    frame(1,0,3);member(0,1,0,3,L"42");member(1,2,0,3,L"43");member(2,3,0,0,L"0");
    record[24+36+4]=2;if(draft.accept(record,sizeof(record)) || draft.value.count)return 1033;record[24+36+4]=0;
    record[24+36]=1;if(draft.accept(record,sizeof(record)) || draft.value.count)return 1034;record[24+36]=2;
    if(draft.accept(record,sizeof(record))!=2 || draft.value.count!=3 || draft.value.find(2)->country!=43 || draft.accept(record,sizeof(record)))return 1035;
    unsigned ids[]={1,2},country=99;
    if(draft.value.affiliation(ids,2,country)!=11 || country || draft.value.affiliation(ids,1,country)!=6 || country!=42)return 1036;
    ids[0]=3;if(draft.value.affiliation(ids,1,country)!=0 || country)return 1037;
    ids[0]=4;if(draft.value.affiliation(ids,1,country)!=10 || country)return 1038;
    auto world=reinterpret_cast<void*>(0x1234);publishBaseAffiliations(world,draft.value);
    logh::BaseAffiliation::Owner owner;
    if(!runtimeBaseOwner(world,2,owner) || owner.country!=43 || runtimeBaseOwner(reinterpret_cast<void*>(0x1235),2,owner))return 1039;
    logh::CountryMetadata metadata;metadata.id=43;wcscpy_s(metadata.name,L"새 국가");publishCountryMetadata(metadata);
    if(runtimeBaseAffiliation(world,2)!=L"새 국가")return 1040;
    resetBaseAffiliations();resetCountryMetadata();if(runtimeBaseOwner(world,2,owner))return 1041;
    draft.begin(65535);header(L"0");if(draft.accept(record,sizeof(record))!=2 || draft.value.count)return 1042;
    draft.begin(65535);header(L"351");if(draft.accept(record,sizeof(record)))return 1043;
    draft.begin(65535);header(L"350");if(draft.accept(record,sizeof(record))!=1)return 1044;
    for(unsigned batch=0;batch<35;++batch) {
        frame(1,batch,10);for(unsigned i=0;i<10;++i)member(i,batch*10+i+1,0,3,L"2147483647");
        if(draft.accept(record,sizeof(record))!=(batch==34 ? 2 : 1))return 1045;
    }
    if(draft.value.count!=350 || draft.value.find(350)->country!=0x7fffffff)return 1046;
    // Warehouse access follows the actual country and regular camp; an unknown owner stays server-decided.
    const logh::BaseAffiliation::Owner regular{1,42,2,0},rebel{1,42,2,1},neutral{1,0,0,0};
    if(!logh::outfitWarehouseBaseAllowed(nullptr,42) || !logh::outfitWarehouseBaseAllowed(&regular,42) ||
        logh::outfitWarehouseBaseAllowed(&regular,43) || logh::outfitWarehouseBaseAllowed(&regular,2) ||
        logh::outfitWarehouseBaseAllowed(&rebel,42) || logh::outfitWarehouseBaseAllowed(&neutral,0))return 1047;
    // The earlier grant answers its own token, which an access change has already abandoned.
    logh::OutfitReplyQueue warehouseReplies;unsigned warehouseReply=0;
    if(!warehouseReplies.add(7,1,1,true) || !warehouseReplies.add(7,1,2,true) ||
        !warehouseReplies.take(7,1,true,warehouseReply) || warehouseReply!=1 ||
        !warehouseReplies.take(7,1,true,warehouseReply) || warehouseReply!=2)return 1048;
    return 0;
}
static int outfitAffiliationTest() {
    BYTE record[24+10*36]={};
    auto frame=[&](unsigned kind,unsigned number,unsigned count,unsigned cursor=800000) {
        memset(record,0,sizeof(record));*reinterpret_cast<unsigned*>(record)=65535;*reinterpret_cast<unsigned*>(record+4)=cursor;
        record[8]=13;record[9]=2;record[20]=static_cast<BYTE>(count);
        *reinterpret_cast<unsigned*>(record+12)=kind;*reinterpret_cast<unsigned*>(record+16)=number;
    };
    auto member=[&](unsigned slot,unsigned id,unsigned camp,unsigned power,const wchar_t* text) {
        auto p=record+24+slot*36;*reinterpret_cast<unsigned*>(p)=id;p[4]=static_cast<BYTE>(camp);p[5]=static_cast<BYTE>(power);
        p[6]=static_cast<BYTE>(wcslen(text)+1);memcpy(p+8,text,(wcslen(text)+1)*2);
    };
    const wchar_t* fields[]={L"FA1",L"60",L"800060",L"1",L"2487",L"922337203685",L"4775807",L"",L"",L""};
    auto header=[&]() {frame(0,0,10);for(unsigned i=0;i<10;++i)member(i,i+1,i,0,fields[i]);};
    logh::OutfitAffiliationDraft draft;draft.begin(65535,800000,2487);
    header();record[0]^=1;if(draft.accept(record,sizeof(record)))return 1050;record[0]^=1;
    fields[4]=L"2488";header();if(draft.accept(record,sizeof(record)))return 1051;fields[4]=L"2487";
    header();if(draft.accept(record,sizeof(record)-1) || draft.accept(record,sizeof(record))!=1)return 1052;
    for(unsigned batch=0;batch<6;++batch) {
        frame(1,batch,10);
        for(unsigned i=0;i<10;++i)member(i,800001+batch*10+i,i%2,3,i%2 ? L"43" : L"42");
        if(!batch) {record[24+36]=record[24];if(draft.accept(record,sizeof(record)))return 1053;record[24+36]++;}
        if(draft.accept(record,sizeof(record))!=(batch==5 ? 2 : 1))return 1054;
    }
    if(draft.page.count!=60 || !draft.page.more || draft.page.next!=800060 || draft.page.generation!=0x7fffffffffffffffULL ||
        draft.page.rows[59].country!=43 || draft.accept(record,sizeof(record)))return 1055;
    draft.begin(65535,800000,2487);fields[1]=L"1";fields[3]=L"0";header();if(draft.accept(record,sizeof(record))!=1)return 1056;
    frame(1,0,1);member(0,800001,0,4,L"0");if(draft.accept(record,sizeof(record)) || draft.received)return 1057;
    member(0,800060,0,4,L"0");if(draft.accept(record,sizeof(record))!=2 || draft.page.rows[0].power!=4)return 1058;
    draft.begin(65535,800000,2487);fields[1]=L"0";fields[2]=L"800000";header();
    if(draft.accept(record,sizeof(record))!=2 || draft.page.count)return 1059;
    draft.begin(65535,800000,2487);fields[3]=L"1";header();if(draft.accept(record,sizeof(record)))return 1060;
    draft.begin(65535,800000,2487);fields[1]=L"1";fields[2]=L"2147483647";fields[3]=L"0";header();
    if(draft.accept(record,sizeof(record))!=1)return 1061;
    frame(1,0,1);member(0,0x7fffffff,0,3,L"42");
    if(draft.accept(record,sizeof(record))!=2 || draft.page.rows[0].id!=0x7fffffff)return 1062;
    return 0;
}
static int tacticalCampTest() {
    BYTE record[384]={};
    auto frame=[&](unsigned kind,unsigned number,unsigned count,unsigned cursor=0) {
        memset(record,0,sizeof(record));*reinterpret_cast<unsigned*>(record)=65535;*reinterpret_cast<unsigned*>(record+4)=cursor;
        record[8]=15;record[9]=2;record[20]=static_cast<BYTE>(count);
        *reinterpret_cast<unsigned*>(record+12)=kind;*reinterpret_cast<unsigned*>(record+16)=number;
    };
    auto member=[&](unsigned slot,unsigned id,unsigned rebel,unsigned kind,const wchar_t* text) {
        auto p=record+24+slot*36;*reinterpret_cast<unsigned*>(p)=id;p[4]=static_cast<BYTE>(kind);p[5]=static_cast<BYTE>(rebel);
        p[6]=static_cast<BYTE>(wcslen(text)+1);memcpy(p+8,text,(wcslen(text)+1)*2);
    };
    const wchar_t* fields[]={L"UC1",L"60",L"60",L"1",L"3758",L"922337203685",L"4775807",L"123",L"",L"336"};
    auto header=[&](unsigned cursor=0) {frame(0,0,10,cursor);for(unsigned i=0;i<10;++i)member(i,i+1,0,i,fields[i]);};
    logh::TacticalCampDraft draft;draft.begin(65535,0,3758);header();
    record[0]^=1;if(draft.accept(record,sizeof(record)))return 1100;record[0]^=1;
    record[8]=13;if(draft.accept(record,sizeof(record)))return 1101;record[8]=15;
    fields[9]=L"5";header();if(draft.accept(record,sizeof(record)))return 1102;fields[9]=L"336";
    fields[4]=L"3757";header();if(draft.accept(record,sizeof(record)))return 1103;fields[4]=L"3758";
    header();if(draft.accept(record,sizeof(record)-1) || draft.accept(record,sizeof(record))!=1)return 1104;
    for(unsigned batch=0;batch<6;++batch) {
        frame(1,batch,10);for(unsigned i=0;i<10;++i)member(i,batch*10+i+1,0,0,i%2 ? L"43" : L"42");
        if(!batch) {
            record[24+36]=record[24];if(draft.accept(record,sizeof(record)) || draft.received)return 1105;record[24+36]++;
            record[24+4]=2;if(draft.accept(record,sizeof(record)) || draft.received)return 1106;record[24+4]=0;
        }
        if(draft.accept(record,sizeof(record))!=(batch==5 ? 2 : 1))return 1107;
    }
    logh::TacticalCampRoster roster;
    if(!roster.append(draft.page) || roster.complete || roster.count!=60 || roster.viewer.owner!=42)return 1108;
    draft.begin(65535,60,3758);fields[1]=L"1";fields[2]=L"61";fields[3]=L"0";header(60);
    if(draft.accept(record,sizeof(record))!=1)return 1109;
    frame(1,0,1,60);member(0,61,0,2,L"0");if(draft.accept(record,sizeof(record))!=2)return 1110;
    auto changed=draft.page;changed.stamp++;if(roster.append(changed) || roster.count!=60)return 1111;
    changed=draft.page;changed.generation--;if(roster.append(changed))return 1112;
    changed=draft.page;changed.viewer.owner=43;if(roster.append(changed))return 1113;
    if(!roster.append(draft.page) || !roster.complete || roster.count!=61 || roster.append(draft.page) || !roster.find(61) || roster.find(62))return 1114;
    const logh::TacticalSide same={0,42,0},foreign={0,43,0},rebel={0,42,1},independent={1,42,0},otherPerson={1,43,0};
    if(logh::tacticalRelation(1,1,same,foreign)!=0x400 || logh::tacticalRelation(2,1,same,same)!=0x800 ||
        logh::tacticalRelation(2,1,same,foreign)!=0x1000 || logh::tacticalRelation(2,1,same,rebel)!=0x1000 ||
        logh::tacticalRelation(2,1,independent,otherPerson)!=0x1000 || logh::tacticalRelation(2,1,same,independent)!=0x1000)return 1115;
    if(logh::tacticalRelation(2,1,same,same,0x400)!=0x400 ||
        logh::tacticalRelation(2,1,same,foreign,0x400)!=0x1000 ||
        logh::tacticalRelation(2,1,same,rebel,0x400)!=0x1000)return 1124;
    logh::TacticalSide side;
    if(!logh::TacticalSide::decode(0x3fffffff9ULL,side) || side.owner!=0x7fffffff || !side.insurgent ||
        logh::TacticalSide::decode(0x400000000ULL,side) || logh::TacticalSide::decode(0,side) || logh::TacticalSide::decode(7,side))return 1116;
    logh::TacticalCampRoster full;
    for(unsigned page=0;page<10;++page) {
        logh::TacticalCampPage p;p.count=60;p.generation=1;p.stamp=1;p.viewer=same;p.more=page!=9;p.next=(page+1)*60;
        for(unsigned i=0;i<60;++i)p.rows[i]={page*60+i+1,same};
        if(!full.append(p))return 1117;
    }
    if(full.count!=600 || !full.complete)return 1118;
    draft.begin(65535,60,3758);fields[1]=L"0";fields[2]=L"60";header(60);
    if(draft.accept(record,sizeof(record))!=2 || draft.page.count)return 1119;
    return 0;
}
static int tacticalDetectionTest() {
    // Same row layout as the 47-byte server record after 00421f80 pads it to 0x34 bytes.
    static BYTE decoded[4+600*0x34];
    static logh::TacticalDetectionInbox inbox;
    static BYTE units[600*0x9ec];
    static BYTE renderer[0xa45c];
    memset(decoded,0,sizeof(decoded));memset(units,0,sizeof(units));memset(renderer,0,sizeof(renderer));
    auto row=[&](unsigned i,unsigned id,BYTE search) {
        auto p=decoded+4+i*0x34;memcpy(p,&id,4);p[0x30]=search;
    };
    auto total=[&](unsigned short count) {memcpy(decoded,&count,2);};
    total(2);row(0,11,1);row(1,12,0);
    inbox.clear();
    if(!inbox.merge(decoded) || inbox.count!=2 || inbox.rows[0].search!=1 || inbox.rows[1].search!=0)return 1130;
    total(1);row(0,11,0); // A later delta replaces the row without widening the set.
    if(!inbox.merge(decoded) || inbox.count!=2 || inbox.rows[0].search!=0 || inbox.rows[1].id!=12)return 1131;
    total(601);if(inbox.merge(decoded) || inbox.count!=2)return 1132;
    total(1);row(0,0,1);if(!inbox.merge(decoded) || inbox.count!=2)return 1133;
    auto unit=[&](unsigned i,unsigned id,BYTE kind,BYTE search)->BYTE* {
        auto p=units+i*0x9ec;p[0]=1;memcpy(p+4,&id,4);p[8]=kind;p[0x9ac]=search;p[0x95c]=99;p[0x95e]=1;return p;
    };
    const auto first=unit(0,11,1,1),second=unit(1,12,1,1),own=unit(2,13,1,0),base=unit(3,14,0,0),visible=unit(4,15,1,1);
    logh::applyTacticalDetection(units,inbox);
    if(first[0x9ac] || second[0x9ac] || !visible[0x9ac])return 1134;
    if(!logh::tacticalUnitHidden(first,13) || logh::tacticalUnitHidden(own,13) || logh::tacticalUnitHidden(base,13) ||
        logh::tacticalUnitHidden(visible,13))return 1135;
    logh::forceTacticalUndetected(units,13);
    if(first[0x95c] || first[0x95e]!=4 || own[0x95c]!=99 || own[0x95e]!=1 || base[0x95e]!=1 || visible[0x95e]!=1)return 1136;
    auto node=[&](unsigned i,unsigned flags,const BYTE* target,BYTE level,BYTE* next)->BYTE* {
        auto p=renderer+0x48+i*0x3c;memcpy(p+0x24,&flags,4);memcpy(p+0x28,&target,4);p[0x2e]=level;memcpy(p+0x38,&next,4);return p;
    };
    const auto head=renderer+8;
    const auto shipBase=node(3,4,second,3,head),foreign=node(2,1,decoded,2,shipBase),shown=node(1,1,visible,2,foreign),hidden=node(0,1|0x1000,second,1,shown);
    const unsigned nodes=4;memcpy(renderer+0x44,&nodes,4);memcpy(renderer+0x40,&hidden,4);
    if(logh::tacticalMarkerHidden(foreign,units,13) || logh::tacticalMarkerHidden(shipBase,units,13) ||
        !logh::tacticalMarkerHidden(hidden,units,13) || logh::tacticalMarkerHidden(hidden,nullptr,13))return 1137;
    for(int pool=0;pool<2;++pool) {
        {
            logh::TacticalHiddenMarkers mask(renderer,units,13,pool!=0);
            if(hidden[0x2e]!=4 || shown[0x2e]!=2 || foreign[0x2e]!=2 || shipBase[0x2e]!=3)return 1138;
        }
        if(hidden[0x2e]!=1)return 1139;
    }
    { logh::TacticalHiddenMarkers mask(nullptr,units,13,true);logh::TacticalHiddenMarkers none(renderer,nullptr,13,false);
      if(hidden[0x2e]!=1)return 1140; }
    // A real level-4 write from the native loop must survive the scope as well.
    hidden[0x2e]=4;{logh::TacticalHiddenMarkers mask(renderer,units,13,false);}if(hidden[0x2e]!=4)return 1141;
    hidden[0x2e]=1;
    BYTE code[32];memset(code,0x90,sizeof(code));
    const auto target=code+24;
    auto call=[&](unsigned offset) {code[offset]=0xe8;const auto rel=static_cast<unsigned>(target-(code+offset+5));memcpy(code+offset+1,&rel,4);};
    call(3);BYTE* sites[2]={};
    if(logh::findTacticalCalls(code,sizeof(code),target,sites,2)!=1 || sites[0]!=code+3)return 1142;
    if(logh::findTacticalCalls(code,3+4,target,sites,2) || logh::findTacticalCalls(code,sizeof(code),code,sites,2))return 1143;
    call(10);if(logh::findTacticalCalls(code,sizeof(code),target,sites,1)!=2 || sites[0]!=code+3)return 1144;
    return 0;
}
static int tacticalInspectTest() {
    // 02216680 is a lookup only in state 2; state 1 and the reset value must not open a panel.
    if(logh::tacticalInspectShip(2,7)!=7 || logh::tacticalInspectShip(1,7) || logh::tacticalInspectShip(9,7) || logh::tacticalInspectShip(2,0))return 1150;
    logh::TacticalInspectLatch latch;
    if(latch.next(7)!=7)return 1151;
    latch.dismiss(7);
    if(latch.next(7) || latch.next(8)!=8)return 1152;
    latch.dismiss(8);
    if(latch.next(0) || latch.next(8)!=8)return 1153;
    using View=logh::TacticalInspectView;
    if(logh::tacticalInspectView(0x400,true)!=View::full || logh::tacticalInspectView(0x800,true)!=View::full ||
        logh::tacticalInspectView(0x1000,false)!=View::basic || logh::tacticalInspectView(0x1000,true)!=View::hidden ||
        logh::tacticalInspectView(0,false)!=View::basic)return 1154;
    static BYTE unit[0x9ec];memset(unit,0,sizeof(unit));
    const unsigned id=11;unit[0]=1;unit[8]=1;memcpy(unit+4,&id,4);unit[0x9ac]=1;unit[0x95e]=0;
    if(logh::tacticalInspectUndetected(unit,13))return 1155;
    unit[0x95e]=4;if(!logh::tacticalInspectUndetected(unit,13))return 1156;
    unit[0x95e]=0;unit[0x9ac]=0;if(!logh::tacticalInspectUndetected(unit,13) || logh::tacticalInspectUndetected(unit,11))return 1157;
    BYTE spec[0x2a8]={};float rated=2.5f,read=0;memcpy(spec+0x258,&rated,4);
    if(!logh::tacticalInspectRated(spec,read) || read!=2.5f)return 1158;
    const unsigned nan=0x7fc00000;memcpy(spec+0x258,&nan,4);
    if(logh::tacticalInspectRated(spec,read))return 1159;
    // Enemy paging: wraps both ways, keeps a sole target, and reports an empty table.
    auto oneOrThree=[](unsigned row) {return row==1 || row==3;};
    if(logh::nextInspectRow(5,1,true,oneOrThree)!=3 || logh::nextInspectRow(5,3,true,oneOrThree)!=1 ||
        logh::nextInspectRow(5,1,false,oneOrThree)!=3 || logh::nextInspectRow(5,3,false,oneOrThree)!=1)return 1160;
    if(logh::nextInspectRow(5,4,true,oneOrThree)!=1 || logh::nextInspectRow(5,0,false,oneOrThree)!=3)return 1161;
    if(logh::nextInspectRow(5,2,true,[](unsigned row) {return row==2;})!=2 ||
        logh::nextInspectRow(5,2,false,[](unsigned row) {return row==2;})!=2)return 1162;
    if(logh::nextInspectRow(5,2,true,[](unsigned) {return false;})!=5 ||
        logh::nextInspectRow(0,0,true,[](unsigned) {return true;})!=0)return 1163;
    return 0;
}
static int countryEndingTest() {
    BYTE p[384]={};
    auto frame=[&](unsigned kind,unsigned number,const wchar_t* const (&fields)[10]) {
        memset(p,0,sizeof(p));*reinterpret_cast<unsigned*>(p)=65535;*reinterpret_cast<unsigned*>(p+4)=127*512;
        p[8]=14;p[9]=2;p[20]=10;*reinterpret_cast<unsigned*>(p+12)=kind;*reinterpret_cast<unsigned*>(p+16)=number;
        for(unsigned i=0;i<10;++i) {auto row=p+24+i*36;*reinterpret_cast<unsigned*>(row)=i+1;row[4]=static_cast<BYTE>(i);
            row[6]=static_cast<BYTE>(wcslen(fields[i])+1);memcpy(row+8,fields[i],(wcslen(fields[i])+1)*2);}
    };
    const wchar_t* header[]={L"CE1",L"3",L"0",L"3",L"922337203685",L"4775807",L"922337203685",L"4775807",L"3",L"42"};
    const wchar_t* identity[]={L"2",L"추가 국가",L"",L"3",L"2147483647",L"1",L"0",L"FF40E0",L"1",L""};
    const wchar_t* values[]={L"65535",L"65535",L"922337203685",L"4775807",L"922337203685",L"4775807",L"922337203685",L"4775807",L"",L""};
    logh::CountryEndingDraft draft;if(!draft.begin(65535,127,0))return 1070;
    frame(0,0,header);p[0]^=1;if(draft.accept(p,sizeof(p)))return 1071;p[0]^=1;
    if(draft.accept(p,sizeof(p)-1) || draft.accept(p,sizeof(p))!=1)return 1072;
    for(unsigned i=0;i<3;++i) {
        identity[0]=i==0 ? L"2" : i==1 ? L"42" : L"43";
        frame(2,i,values);if(draft.accept(p,sizeof(p)))return 1073;
        frame(1,i,identity);if(draft.accept(p,sizeof(p))!=1 || draft.accept(p,sizeof(p)))return 1074;
        frame(2,i,values);if(!i) {p[24+3*36+8]=L'8';if(draft.accept(p,sizeof(p)))return 1075;frame(2,i,values);}
        if(draft.accept(p,sizeof(p))!=(i==2 ? 2 : 1))return 1076;
    }
    if(draft.page.viewer!=42 || draft.page.rows[1].id!=42 || draft.page.rows[2].id!=43 ||
        draft.page.rows[2].ships!=0x7fffffffffffffffULL || draft.page.rows[0].rgb!=0xff40e0)return 1077;
    if(draft.accept(p,sizeof(p)) || draft.begin(1,128,0) || draft.begin(1,1,351))return 1078;
    draft.begin(65535,127,0);header[1]=L"0";header[3]=L"0";frame(0,0,header);
    if(draft.accept(p,sizeof(p))!=2 || draft.page.count)return 1079;
    return 0;
}
static int runGuard(int argc, wchar_t** argv) {
    if(!countryImageDataTest())return 812;
    if(logh::countryAtlasScale(128,128,65536,1024)!=1 || logh::countryAtlasScale(128,128,262144,1024)!=2 ||
        logh::countryAtlasScale(128,128,262144,512)!=0)return 1123;
    if(int result=tacticalCampTest())return result;
    if(int result=tacticalDetectionTest())return result;
    if(int result=tacticalInspectTest())return result;
    if(int result=countryEndingTest())return result;
    if(int result=outfitAffiliationTest())return result;
    if(int result=baseAffiliationTest())return result;
    if(int result=facilityGuideTest())return result;
    if(int result=lobbyCountryDataTest())return result;
    if(logh::groundMeter(0,1000,70)!=0 || logh::groundMeter(500,1000,70)!=35 ||
        logh::groundMeter(1000,1000,70)!=70 || logh::groundMeter(1001,1000,70)!=70 ||
        logh::groundMeter(~0u,1000,70)!=70 || logh::groundMeter(500,0,70)!=0 ||
        logh::groundPanelTop+logh::groundPanelHeight>600) return 270;
    if (!logh::groundPanelFriendly(1,false,true) || logh::groundPanelFriendly(2,true,true) ||
        !logh::groundPanelFriendly(0,true,true) || logh::groundPanelFriendly(0,true,false)) return 270;
    if(!runtimeKoreanGlyphTest())return 95;
    if(!environmentShaderTest())return 96;
    if(!fortressMenuAuthorityTest())return 97;
    if(!commanderMenuTest())return 196;
    if (!mailTextTest() || !mailDeliveryTest() || !mailRefreshTest() || !proposalApprovalTest() || !proposalDetailCaptionTest()) return 252;
    if (!legacyKoreanGlyphTest()) return 253;
    if (!nameCardDisplayTest()) return 254;
    if (!messengerStatusTextTest()) return 255;
    std::string officeCaption;
    std::wstring officeDecoded;
    for(unsigned kind=0;kind<2;++kind) {
        if(!logh::officeMailKindText(kind,officeCaption) || officeCaption.size()!=4 ||
            !logh::japaneseFragment(officeCaption.c_str(),officeDecoded) ||
            officeDecoded!=(kind==0?L"제안":L"명령"))return 263;
    }
    if(logh::officeMailKindText(2,officeCaption) ||
        logh::officeMailKind(0x53c80e,0x16,0x54)!=0 ||
        logh::officeMailKind(0x53c80e,0x16,0x55)!=1 ||
        logh::officeMailKind(0x53c961,0x16,0x54)!=0 ||
        logh::officeMailKind(0x53c961,0x16,0x55)!=1 ||
        logh::officeMailKind(0x53cc65,0x16,0x56)!=2 ||
        logh::officeMailKind(0x53c80e,0x15,0x54)!=2)return 264;
    logh::OfficeFriendDisplay friendDisplay;
    char originalCString[]="native",differentCString[]="native";
    if(!friendDisplay.remember(L"한글日本ABC",originalCString) ||
        friendDisplay.take(differentCString,officeDecoded) || friendDisplay.buffer ||
        !friendDisplay.remember(L"가나다라마바사아자차카타",originalCString) ||
        !friendDisplay.take(originalCString,officeDecoded) || officeDecoded!=L"가나다라마바사아자차카타" ||
        friendDisplay.take(originalCString,officeDecoded) || strcmp(originalCString,"native"))return 265;
    const wchar_t badFriend[]={0xdc00,0};
    if(friendDisplay.remember(L"가나다라마바사아자차카타파",originalCString) ||
        friendDisplay.remember(badFriend,originalCString) ||
        friendDisplay.remember(nullptr,originalCString) || friendDisplay.remember(L"한글",nullptr) ||
        friendDisplay.buffer)return 266;
    logh::OfficeGroupDisplay officeGroups;
    const uintptr_t pickerContext=0x10000000;
    char groupNarrow[]="    narrowed",groupCopy[]="    narrowed";
    const std::string groupOriginal=groupNarrow;
    if(!officeGroups.remember(pickerContext,299,L"가나다라마바사아자차카타") ||
        !officeGroups.bind(pickerContext+0x5938,511,1,0,2,299,groupNarrow) ||
        !officeGroups.restore(pickerContext+0x1038,2,299,groupCopy,officeDecoded) ||
        officeDecoded!=L"    가나다라마바사아자차카타" || groupOriginal!=groupNarrow)return 267;
    const auto groupSaved=officeDecoded;
    if(officeGroups.restore(pickerContext+0x738,2,299,groupCopy,officeDecoded) ||
        officeGroups.restore(pickerContext+0x1038,1,299,groupCopy,officeDecoded) ||
        officeGroups.restore(pickerContext+0x1038,2,298,groupCopy,officeDecoded) ||
        officeGroups.restore(pickerContext+0x1038,2,299,"    changed",officeDecoded) ||
        officeDecoded!=groupSaved)return 268;
    if(officeGroups.bind(pickerContext+0x5938,512,1,0,2,299,groupCopy) ||
        officeGroups.bind(pickerContext+0x5938,-1,1,0,2,299,groupCopy) ||
        officeGroups.bind(pickerContext+0x5938,0,1,1,2,299,groupCopy) ||
        officeGroups.bind(pickerContext+0x5938,0,1,0,1,299,groupCopy) ||
        officeGroups.remember(pickerContext,300,L"다른 이름"))return 269;
    if(officeGroups.remember(pickerContext,299,L"가나다라마바사아자차카타파") ||
        officeGroups.restore(pickerContext+0x1038,2,299,groupCopy,officeDecoded) ||
        officeGroups.remember(pickerContext,0,badFriend))return 270;
    if(!officeGroups.remember(pickerContext,0,L"한글日本ABC") ||
        !officeGroups.bind(pickerContext+0x5938,0,1,0,2,0,groupCopy) ||
        !officeGroups.restore(pickerContext+0x1038,2,0,groupCopy,officeDecoded) ||
        officeDecoded!=L"    한글日本ABC" ||
        !officeGroups.remember(pickerContext+4,1,L"새 그룹") ||
        officeGroups.restore(pickerContext+0x1038,2,0,groupCopy,officeDecoded))return 271;
    officeGroups.clear();
    if(officeGroups.owner || officeGroups.restore(pickerContext+4+0x1038,2,1,groupCopy,officeDecoded))return 272;
    std::vector<BYTE> friendCache(4+100*0x124,0);
    friendCache[0]=100;
    const unsigned lastFriendId=12345;
    memcpy(friendCache.data()+4+99*0x124,&lastFriendId,4);
    memcpy(friendCache.data()+10+99*0x124,L"이 둘",4*sizeof(wchar_t));
    const auto friendCacheOriginal=friendCache;
    if(!logh::officeFriendCacheName(friendCache.data(),lastFriendId,officeDecoded) ||
        officeDecoded!=L"이 둘" || friendCache!=friendCacheOriginal)return 273;
    if(logh::officeFriendCacheName(nullptr,lastFriendId,officeDecoded) ||
        logh::officeFriendCacheName(friendCache.data(),54321,officeDecoded) || officeDecoded!=L"이 둘")return 274;
    friendCache[0]=101;
    if(logh::officeFriendCacheName(friendCache.data(),lastFriendId,officeDecoded))return 275;
    friendCache[0]=100;
    memcpy(friendCache.data()+10+99*0x124,L"가나다라마바사아자차카타파",14*sizeof(wchar_t));
    if(logh::officeFriendCacheName(friendCache.data(),lastFriendId,officeDecoded) || officeDecoded!=L"이 둘")return 276;
    logh::OfficeFriendRows friendRows;
    const uintptr_t friendListContext=0x20000000;
    const char friendOriginal[]="\xc0\xcc \xb5\xd1",friendCopy[]="\xc0\xcc \xb5\xd1";
    friendRows.begin(friendListContext,1,2);
    if(friendRows.root!=1 || friendRows.table!=2)return 277;
    if(!friendRows.bind(friendListContext,12345,friendOriginal) ||
        !friendRows.matches(friendListContext,12345,friendCopy) ||
        friendRows.matches(friendListContext+4,12345,friendCopy) ||
        friendRows.matches(friendListContext,12346,friendCopy) ||
        friendRows.matches(friendListContext,12345,"different") ||
        friendRows.matches(friendListContext,12345,nullptr))return 277;
    friendRows.begin(friendListContext);
    if(friendRows.root || friendRows.table || friendRows.matches(friendListContext,12345,friendCopy))return 278;
    for(unsigned i=0;i<100;++i)if(!friendRows.bind(friendListContext,i,friendOriginal))return 279;
    if(friendRows.bind(friendListContext,100,friendOriginal) ||
        !friendRows.bind(friendListContext,99,"new") ||
        friendRows.matches(friendListContext,99,friendCopy) ||
        !friendRows.matches(friendListContext,99,"new"))return 280;
    const std::string tooLongFriend(64,'A');
    if(friendRows.bind(friendListContext,99,tooLongFriend.c_str()) ||
        !friendRows.bind(friendListContext+4,100,friendOriginal) ||
        friendRows.matches(friendListContext,0,friendOriginal))return 281;
    logh::MailPeerRows mailPeers;
    if(!mailPeers.bind(0,0x30000000,1,2,0,10,12345,friendOriginal,L"김 하나") ||
       !mailPeers.restore(0,0x30000000,1,2,0,2,-1,10,12345,friendCopy,officeDecoded) ||
       officeDecoded!=L"김 하나")return 282;
    for(unsigned which=0;which<8;++which) {
        if(mailPeers.restore(which==0?1:0,which==1?0x30000004:0x30000000,which==2?3:1,which==3?3:2,
            which==4?1:0,2,-1,which==5?11:10,which==6?12346:12345,which==7?"changed":friendCopy,officeDecoded))return 283;
    }
    if(mailPeers.restore(0,0x30000000,1,2,0,1,-1,10,12345,friendCopy,officeDecoded) ||
       mailPeers.restore(0,0x30000000,1,2,0,2,0,10,12345,friendCopy,officeDecoded) || officeDecoded!=L"김 하나")return 284;
    if(!mailPeers.bind(1,0x30000004,3,4,99,11,12346,"plain",L"한글日本ABC") ||
       !mailPeers.restore(1,0x30000004,3,4,99,2,-1,11,12346,"plain",officeDecoded) || officeDecoded!=L"한글日本ABC" ||
       mailPeers.bind(1,0x30000004,3,4,100,11,12346,"plain",L"이 둘"))return 285;
    if(mailPeers.bind(0,0x30000000,1,2,0,10,12345,friendOriginal,L"가나다라마바사아자차카타파") ||
       mailPeers.restore(0,0x30000000,1,2,0,2,-1,10,12345,friendCopy,officeDecoded))return 286;
    std::wstring hudName;
    if(!logh::hudPersonalName(L"이 둘",0,L"계급",hudName) || hudName!=L"계급이 둘\n" ||
       !logh::hudPersonalName(L"이 둘",1,L"계급",hudName) || hudName!=L"계급이 둘\n" ||
       !logh::hudPersonalName(L"김 하나",2,L"계급",hudName) || hudName!=L"김 하나계급\n" ||
       logh::hudPersonalName(L"가나다라마바사아자차카타파",2,L"계급",hudName))return 287;
    {
        logh::HudNameRefresh refresh;
        const auto hudRoot=reinterpret_cast<void*>(0x1000),nextRoot=reinterpret_cast<void*>(0x2000),label=reinterpret_cast<void*>(0x1004);
        refresh.rankChanged(1,10); // A rank before the HUD exists is read fresh at creation.
        if(refresh.pending)return 288;
        refresh.bind(hudRoot,label);refresh.rankChanged(10,10);
        if(refresh.pending)return 289;
        refresh.rankChanged(1,10);
        if(!refresh.due(hudRoot,true) || refresh.due(hudRoot,false) ||
           refresh.due(nextRoot,true) || refresh.due(nullptr,true))return 290;
        refresh.bind(nextRoot,label); // Recreating the HUD already rendered the current rank.
        if(refresh.due(nextRoot,true))return 291;
    }
    {
        unsigned excluded=0;
        logh::ReturnBaseRefresh refresh={};
        refresh.updated(0,0,false,true); // Another character's 0356 leaves the own value unchanged.
        refresh.updated(1012,0,true,true); // A pending request's reply decides the result.
        refresh.updated(1012,0,false,false); // Reopening the tab reads the current value.
        if(refresh.take(excluded))return 292;
        refresh.updated(1012,0,false,true);
        if(!refresh.take(excluded) || excluded!=1012 || refresh.take(excluded))return 293;
        refresh.replied(true,true);
        refresh.updated(1012,0,false,true); // The tab's own accepted clear keeps its result text.
        if(refresh.take(excluded))return 1146;
        refresh.updated(0,1048,false,true);
        if(!refresh.take(excluded) || excluded!=0)return 1147;
        refresh.replied(true,true);
        refresh.updated(1048,1049,false,false); // A closed tab still consumes its echo.
        refresh.updated(1049,0,false,true);
        if(!refresh.take(excluded) || excluded!=1049)return 1148;
        for(bool accepted:{false,true}) { // A rejection or an unchanged save produces no echo.
            refresh.replied(accepted,!accepted);
            refresh.updated(1012,0,false,true);
            if(!refresh.take(excluded))return 1149;
        }
    }
    std::string calendarPrefix;
    std::wstring calendarDecoded;
    for(bool imperial:{true,false}) {
        if(!logh::mailCalendarText(imperial,calendarPrefix) || calendarPrefix.size()!=6 ||
            calendarPrefix.size()+1>16 || !logh::japaneseFragment(calendarPrefix.c_str(),calendarDecoded) ||
            calendarDecoded!=(imperial?L"제국력":L"우주력"))return 257;
        // Simulate the native adjacent prefix[16] / numeric[128] buffers.
        // Formatting numbers must not overwrite the prefix's terminator.
        char dateBuffers[144]={};
        memcpy(dateBuffers,calendarPrefix.c_str(),calendarPrefix.size()+1);
        strcpy_s(dateBuffers+16,sizeof(dateBuffers)-16,"795/ 1/12 06:26");
        if(strcmp(dateBuffers,calendarPrefix.c_str()) ||
            !logh::japaneseFragment(dateBuffers,calendarDecoded) ||
            calendarDecoded!=(imperial?L"제국력":L"우주력"))return 258;
    }
    if(logh::mailCalendarKind(0x53cc65,0x16,0x56)!=0 ||
        logh::mailCalendarKind(0x53cc9d,0x16,0x57)!=1 ||
        logh::mailCalendarKind(0x53c80e,0x16,0x54)!=2 ||
        logh::mailCalendarKind(0x53c961,0x16,0x55)!=2 ||
        logh::mailCalendarKind(0x53cc65,0x16,0x57)!=2 ||
        logh::mailCalendarKind(0x53cc9d,0x15,0x57)!=2)return 259;
    if(logh::mailFooterKind(0x537eb7,0x16,4)!=0 ||
        logh::mailFooterKind(0x53c2d9,0x16,4)!=0 ||
        logh::mailFooterKind(0x537f53,0x16,4)!=1)return 260;
    for(auto caller:{0x52a0ffu,0x52d30eu,0x52d580u,0x52d8eeu})
        if(logh::mailFooterKind(caller,0x16,4)!=2)return 261;
    if(logh::mailFooterKind(0x537f53,0x16,5)!=3 ||
        logh::mailFooterKind(0x52a0ff,0x15,4)!=3 ||
        logh::mailFooterKind(0x52d203,0x16,7)!=3)return 262;
    std::wstring imeStatus=L"unchanged";
    const LANGID koreanKeyboard=MAKELANGID(LANG_KOREAN,SUBLANG_DEFAULT);
    const LANGID japaneseKeyboard=MAKELANGID(LANG_JAPANESE,SUBLANG_DEFAULT);
    if(!logh::imeStatusFragment("\xe7\xc8",imeStatus,koreanKeyboard,949) || imeStatus!=L"A" ||
        !logh::imeStatusFragment("\xaa\xa2",imeStatus,koreanKeyboard,949) || imeStatus!=L"한" ||
        !logh::imeStatusFragment("\xab\xa2",imeStatus,koreanKeyboard,949) || imeStatus!=L"한" ||
        !logh::imeStatusFragment("A",imeStatus,koreanKeyboard,949) || imeStatus!=L"A" ||
        !logh::imeStatusFragment("\x82\xa0",imeStatus,japaneseKeyboard,932) || imeStatus!=L"あ" ||
        !logh::imeStatusFragment("\x83\x41",imeStatus,japaneseKeyboard,932) || imeStatus!=L"ア" ||
        !logh::imeStatusFragment("\x89\x70",imeStatus,japaneseKeyboard,932) || imeStatus!=L"A" ||
        logh::imeStatusFragment("\x81",imeStatus,koreanKeyboard,949) || imeStatus!=L"A" ||
        logh::imeStatusFragment(nullptr,imeStatus,koreanKeyboard,949) || imeStatus!=L"A")return 256;
    if (!imeKeyGateTest()) return 939;
    if (!wideResourceTest()) return 253;
    if (!dialogTextTest()) return 251;
    if (!displayNameTest()) return 250;
    const char* battleship="\x90\xed\x8a\xcd";
    const char* translated=logh::koreanResource(85,0,battleship);
    std::wstring korean;
    if (translated==battleship || !logh::japaneseFragment(translated,korean) || korean!=L"전함" ||
        strcmp(logh::koreanResource(85,0,"modified catalogue"),"modified catalogue") ||
        logh::koreanResource(999,0,battleship)!=battleship ||
        logh::koreanResource(85,0,nullptr)!=nullptr) return 82;
    wchar_t catalogue[16];logh::catalogueText(translated,catalogue);
    if (wcscmp(catalogue,L"전함")) return 82;
    logh::catalogueText(nullptr,catalogue);
    if (catalogue[0]) return 82;
    const char* error="\x83\x47\x83\x89\x81\x5b";
    if (!logh::japaneseFragment(logh::koreanResource(65535,0x78,0x6ab4,error),korean) || korean!=L"오류" ||
        logh::koreanStaticResource(error)!=error ||
        !logh::japaneseFragment(logh::koreanResource(65534,0,0,"NO TABLE"),korean) || korean!=L"명령 패널") return 82;
    char windowsString[128];memset(windowsString,0x7f,sizeof(windowsString));
    // This fixture is encoded as CP949 even on the English CI runner (ACP1252).
    if (logh::copyKoreanWindowsString(57344,windowsString,sizeof(windowsString),949)<=0 ||
        !logh::inputFragment(windowsString,korean,949) || korean!=L"은하영웅전설 VII" ||
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
    // Additional countries can share native power 3 while using Empire assets.
    if(logh::systemEmblemAffiliation(6,2)!=4 || logh::systemEmblemAffiliation(7,2)!=5 ||
        logh::systemEmblemAffiliation(4,3)!=6 || logh::systemEmblemAffiliation(5,3)!=7 ||
        logh::systemEmblemAffiliation(6,0)!=6 || logh::systemEmblemAffiliation(4,42)!=4 ||
        logh::systemEmblemAffiliation(0,2)!=0 || logh::systemEmblemAffiliation(8,3)!=8 ||
        logh::systemEmblemAffiliation(10,2)!=10 || logh::systemEmblemAffiliation(11,3)!=11)return 81;
    if(!sessionEndingDataTest())return 86;
    if (!groundLocationPageTest()) return 85;
    if (!groundTransferReasonTest()) return 915;
    if (!tacticalMotionOriginsTest()) return 81;
    if (!tacticalClockScaleTest()) return 81;
    if (!tacticalNoticesTest()) return 82;
    if (!flagshipWeaponLabelTest()) return 813;
    if (!flagshipRateRowTest()) return 814;
    if (!tacticalWeaponSelectionTest()) return 87;
    if (!tacticalBaseDataTest()) return 88;
    if (!tacticalChargeDataTest()) return 89;
    if (!tacticalEnergyDataTest()) return 816;
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
    const auto adjacentWarp=logh::normalWarpFare(2487,2488), diagonalWarp=logh::normalWarpFare(2487,2588);
    const auto longWarp=logh::normalWarpFare(2487,2492);
    if (adjacentWarp.lightYears!=100 || adjacentWarp.mcp!=40 || adjacentWarp.waitHours ||
        diagonalWarp.lightYears!=142 || diagonalWarp.mcp!=80 || diagonalWarp.waitHours!=1 ||
        longWarp.lightYears!=500 || longWarp.mcp!=160 || longWarp.waitHours!=4 ||
        logh::normalWarpFare(2487,2487).mcp || logh::normalWarpFare(2487,5000).mcp) return 1123;
    if (logh::fuelServiceHours(1000)!=0 || logh::fuelServiceHours(999)!=48 ||
        logh::fuelServiceHours(899)!=97 || logh::fuelServiceHours(900)!=96 ||
        logh::fuelServiceHours(0)!=960) return 1125;
    if (!logh::appointmentAuthorityCard(135) || !logh::appointmentAuthorityCard(1) ||
        logh::appointmentAuthorityCard(0) || logh::appointmentAuthorityCard(195) ||
        logh::appointmentCapitalPower(169)!=3 || logh::appointmentCapitalPower(135)!=3 ||
        logh::appointmentCapitalPower(2)!=2 || logh::appointmentCapitalPower(56)!=2 ||
        logh::appointmentCapitalPower(193)!=3 || logh::appointmentCapitalPower(54) ||
        logh::appointmentCapitalPower(175) || logh::appointmentCapitalPower(231)) return 71;
    if (logh::concurrentOfficePenaltyPercent(0)!=0 || logh::concurrentOfficePenaltyPercent(3)!=0 ||
        logh::concurrentOfficePenaltyPercent(4)!=5 || logh::concurrentOfficePenaltyPercent(5)!=10 ||
        logh::concurrentOfficePenaltyPercent(6)!=15 || logh::concurrentOfficePenaltyPercent(7)!=20 ||
        logh::concurrentOfficePenaltyPercent(15)!=20 || logh::concurrentOfficePenaltyPercent(0xffffffffu)!=20) return 1126;
    {
        unsigned offices=99;
        if (!logh::concurrentOfficeMarker(0x4c00,offices) || offices!=0 ||
            !logh::concurrentOfficeMarker(0x4c0f,offices) || offices!=15 ||
            logh::concurrentOfficeMarker(0x4c10,offices) || logh::concurrentOfficeMarker(0x4b05,offices) ||
            logh::concurrentOfficeMarker(0x5c03,offices) || logh::concurrentOfficeMarker(0,offices)) return 1127;
    }
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
    // A stale unanswered head (closed window's warehouse query) must not block a later reply.
    logh::OutfitReplyQueue stale;unsigned staleToken=0;
    if (!stale.add(7,1,1,true) || !stale.add(7,0,2,false) || !stale.take(7,1,false,staleToken) ||
        staleToken!=2 || stale.count || stale.take(7,1,true,staleToken)) return 25;
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
    using OfficeLookup=logh::PlanetOfficeLookup;
    if (logh::planetOfficeQuery(54,0)!=0x8036 || logh::planetOfficeQuery(54,1)!=0x8136 ||
        logh::planetOfficeQuery(190,127)!=0xffbe) return 920;
    memset(governorReply,0,sizeof(governorReply));governorReply[0]=54;governorReply[2]=64;
    for (unsigned i=0;i<64;++i) {unsigned person=20000+i;memcpy(governorReply+4+i*0x2d4,&person,4);}
    OfficeLookup office;office.start(54);office.state=OfficeLookup::waiting;
    office.receive(governorReply,id); // A full page without the holder proves nothing yet.
    if (office.state!=OfficeLookup::sending || office.page!=1 || office.holder.id) return 921;
    office.receive(governorReply,id); // Not waiting: a repeated reply cannot advance the page.
    if (office.state!=OfficeLookup::sending || office.page!=1) return 922;
    auto holderRow=governorReply+4+63*0x2d4;
    holderRow[0x7d]=1;holderRow[0x82]='A';holderRow[0x24c]=1;holderRow[0x250]=54;memcpy(holderRow+0x254,&id,4);
    office.state=OfficeLookup::waiting;office.receive(governorReply,id);
    if (office.state!=OfficeLookup::ready || office.holder.id!=20063 || office.holder.surname[0]!='A') return 923;
    office.start(54);office.state=OfficeLookup::waiting;office.receive(governorReply,other);
    if (office.state!=OfficeLookup::sending) return 924; // Another base's holder: keep looking.
    governorReply[2]=0;office.state=OfficeLookup::waiting;office.receive(governorReply,other);
    if (office.state!=OfficeLookup::ready || office.holder.id) return 925; // Exact multiple of 64: empty last page.
    office.start(54);office.state=OfficeLookup::waiting;governorReply[2]=65;office.receive(governorReply,id);
    if (office.state!=OfficeLookup::unconfirmed) return 926;
    governorReply[0]=55;governorReply[2]=1;office.start(54);office.state=OfficeLookup::waiting;office.receive(governorReply,id);
    if (office.state!=OfficeLookup::unconfirmed) return 927;
    governorReply[0]=54;governorReply[2]=64;
    office.start(54);office.page=OfficeLookup::lastPage;office.state=OfficeLookup::waiting;office.receive(governorReply,other);
    if (office.state!=OfficeLookup::unconfirmed) return 928; // No next page to ask: not unappointed.
    office.start(0);
    if (office.state!=OfficeLookup::ready || office.holder.id) return 929;
    logh::PlanetOfficeWire officeWire;
    if (officeWire.settle(54) || !officeWire.available(0)) return 930; // Nothing sent: not ours.
    officeWire.sent(54,1000);
    if (officeWire.available(1000) || officeWire.settle(190) || !officeWire.busy) return 931; // Another card keeps the slot.
    if (!officeWire.settle(54) || officeWire.busy || officeWire.settle(54)) return 932;
    officeWire.sent(54,1000);officeWire.abandon(); // Planet switch or close with a page on the wire.
    if (officeWire.available(1000) || officeWire.overdue(9000)) return 933;
    if (officeWire.settle(54) || officeWire.busy) return 934; // Released but not read.
    officeWire.abandon();
    officeWire.sent(54,2000);
    if (officeWire.abandoned) return 935; // An earlier abandon does not poison the next query.
    if (officeWire.overdue(6999) || !officeWire.overdue(7000) || officeWire.overdue(7001)) return 936;
    if (officeWire.available(7000) || officeWire.settle(54)) return 937; // A reply after the timeout is not read.
    officeWire.sent(54,2000);officeWire.abandon();
    if (officeWire.available(31999) || !officeWire.available(32000)) return 938;
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
    unsigned alive=999;
    if(!logh::definitionShipCounts(300,50,0,normal,alive) || normal!=250 || alive!=300 ||
        !logh::definitionShipCounts(300,0,0,normal,alive) || normal!=300 || alive!=300 ||
        !logh::definitionShipCounts(300,222,148,normal,alive) || normal!=78 || alive!=152 ||
        !logh::definitionShipCounts(1,1,0,normal,alive) || normal!=0 || alive!=1 ||
        logh::definitionShipCounts(300,10,20,normal,alive) || logh::definitionShipCounts(300,301,0,normal,alive) ||
        logh::definitionShipCounts(0,0,0,normal,alive) || logh::definitionShipCounts(301,0,0,normal,alive))return 80;
    using AdmissionRejection=logh::GameAdmissionRejection;
    if(logh::gameAdmissionRejection(0x0206,true,2)!=AdmissionRejection::None ||
        logh::gameAdmissionRejection(0x0201,true,2)!=AdmissionRejection::None ||
        logh::gameAdmissionRejection(0x0202,false,2)!=AdmissionRejection::None ||
        logh::gameAdmissionRejection(0x0202,true,1)!=AdmissionRejection::Generic ||
        logh::gameAdmissionRejection(0x0202,true,255)!=AdmissionRejection::Generic ||
        logh::gameAdmissionRejection(0x0202,true,3)!=AdmissionRejection::Maintenance ||
        logh::gameAdmissionRejection(0x10000202,true,2)!=AdmissionRejection::AlreadyOnline)return 81;
    unsigned char creation[sizeof(logh::creationMenuDisabled)];
    memcpy(creation,logh::creationMenuDisabled,sizeof(creation));
    if(logh::creationMenuState(creation,sizeof(creation))!=logh::CreationMenuState::Disabled)return 82;
    memcpy(creation,logh::creationMenuEnabled,sizeof(creation));
    if(logh::creationMenuState(creation,sizeof(creation))!=logh::CreationMenuState::Enabled)return 82;
    creation[4]=0; // A partially patched pair is not a supported lobby initializer.
    if(logh::creationMenuState(creation,sizeof(creation))!=logh::CreationMenuState::Unsupported)return 82;
    memcpy(creation,logh::creationMenuDisabled,sizeof(creation));creation[0]=0x90;
    if(logh::creationMenuState(creation,sizeof(creation))!=logh::CreationMenuState::Unsupported ||
        logh::creationMenuState(nullptr,sizeof(creation))!=logh::CreationMenuState::Unsupported ||
        logh::creationMenuState(creation,sizeof(creation)-1)!=logh::CreationMenuState::Unsupported)return 82;
    BYTE emptySessions[0x6d8]={};
    if(logh::emptyPlayableLobby(nullptr,sizeof(emptySessions)) || logh::emptyPlayableLobby(emptySessions,sizeof(emptySessions)))return 94;
    emptySessions[0]=1;emptySessions[4]=1;emptySessions[6]=1;
    if(!logh::emptyPlayableLobby(emptySessions,sizeof(emptySessions)) || logh::emptyPlayableLobby(emptySessions,0x36b))return 94;
    emptySessions[0]=3;if(logh::emptyPlayableLobby(emptySessions,sizeof(emptySessions)))return 94;
    emptySessions[0]=1;emptySessions[6]=3;if(logh::emptyPlayableLobby(emptySessions,sizeof(emptySessions)))return 94;
    emptySessions[6]=1;emptySessions[0x2a1]=1;if(logh::emptyPlayableLobby(emptySessions,sizeof(emptySessions)))return 94;
    emptySessions[0x2a1]=0;emptySessions[0x2a0]=2;if(logh::emptyPlayableLobby(emptySessions,sizeof(emptySessions)))return 94;
    emptySessions[0x2a0]=0;emptySessions[0]=2;emptySessions[0x36c+4]=2;emptySessions[0x36c+6]=2;
    if(!logh::emptyPlayableLobby(emptySessions,sizeof(emptySessions)))return 94;
    emptySessions[0x36c+0x2a1]=1;if(logh::emptyPlayableLobby(emptySessions,sizeof(emptySessions)))return 94;
    HMODULE module = LoadLibraryW(argv[1]);
    if (!module) return 2;
    auto status = reinterpret_cast<LONG(WINAPI*)()>(GetProcAddress(module, "LoghPlanetLayoutStatus"));
    auto create = reinterpret_cast<HRESULT(WINAPI*)(HINSTANCE, DWORD, const GUID&, void**, void*)>(GetProcAddress(module, "DirectInput8Create"));
    if (!status || !create || status() != 0) return 3;
    GUID iid = {}; void* input = nullptr;
    HRESULT result = create(GetModuleHandleW(nullptr), 0, iid, &input, nullptr);
    if (status() != -1 || SUCCEEDED(result) || input) return 4;
    auto detectionCoverage = reinterpret_cast<LONG(WINAPI*)()>(GetProcAddress(module, "LoghTacticalDetectionCoverage"));
    if (!detectionCoverage || detectionCoverage() != 0) return 1145;
    FreeLibrary(module);
    wchar_t navigationCp[32],navigationDate[96];
    if(!logh::navigationHudText(0,1000,675,3,navigationCp,navigationDate) ||
        wcscmp(navigationCp,L"1000             675 ") ||
        wcscmp(navigationDate,L"우주력 795년  1월  1일  0시  0분") ||
        !logh::navigationHudText(31104000,0,1000,3,navigationCp,navigationDate) ||
        wcscmp(navigationDate,L"우주력 796년  1월  1일  0시  0분") ||
        logh::navigationHudText(0,1001,0,3,navigationCp,navigationDate) ||
        !logh::navigationHudText(0,1000,1000,2,navigationCp,navigationDate) ||
        wcscmp(navigationDate,L"제국력 486년  1월  1일  0시  0분") ||
        !logh::navigationHudText((4*360+5*30+20)*86400u,1000,1000,2,navigationCp,navigationDate) ||
        wcscmp(navigationDate,L"제국력 490년  6월 21일  0시  0분") ||
        !logh::navigationHudText((4*360+5*30+21)*86400u,1000,1000,2,navigationCp,navigationDate) ||
        wcscmp(navigationDate,L"제국력 490년  6월 22일  0시  0분") ||
        !logh::navigationHudText(5*31104000u,1000,1000,2,navigationCp,navigationDate) ||
        wcscmp(navigationDate,L"제국력 491년  1월  1일  0시  0분") ||
        !logh::formatNavigationHud(4*31104000u,1000,1000,logh::countryCalendars[2],navigationCp,navigationDate) ||
        wcscmp(navigationDate,L"신제국력 1년  1월  1일  0시  0분") ||
        logh::navigationHudText(0,1000,1000,100,navigationCp,navigationDate))return 294;
    BYTE paintPacket[24+6*36]={};
    *reinterpret_cast<unsigned*>(paintPacket)=2;*reinterpret_cast<unsigned*>(paintPacket+4)=17;
    paintPacket[8]=4;paintPacket[20]=6;
    auto paintField=[&](unsigned index,const wchar_t* value) {
        auto row=paintPacket+24+index*36;*reinterpret_cast<unsigned*>(row)=index+1;
        row[4]=static_cast<BYTE>(index);row[6]=static_cast<BYTE>(wcslen(value)+1);
        wcscpy_s(reinterpret_cast<wchar_t*>(row+8),13,value);
    };
    paintField(0,L"123456");paintField(1,L"inherit");paintField(2,L"000000");paintField(3,L"FFFFFF");
    paintField(4,L"900719925474");paintField(5,L"0993");logh::CountryPaint paint;
    if(!logh::readCountryPaint(paintPacket,sizeof(paintPacket),2,17,paint) || paint.id!=2 || paint.mask!=13 ||
        paint.rgb[0]!=0x123456 || paint.rgb[2] || paint.rgb[3]!=0xffffff || paint.revision!=9007199254740993ULL ||
        logh::readCountryPaint(paintPacket,sizeof(paintPacket),2,18,paint) ||
        logh::readCountryPaint(paintPacket,sizeof(paintPacket)-1,2,17,paint))return 295;
    paintField(4,L"922337203685");paintField(5,L"4775808");
    if(logh::readCountryPaint(paintPacket,sizeof(paintPacket),2,17,paint))return 296;
    paintField(4,L"0");paintField(5,L"");paintField(0,L"url(x)");
    if(logh::readCountryPaint(paintPacket,sizeof(paintPacket),2,17,paint))return 297;
    paintPacket[8]=5;
    paintField(0,L"제국 시험");paintField(1,L"");paintField(2,L"시험력");paintField(3,L"-310");
    paintField(4,L"900719925474");paintField(5,L"0993");logh::CountryMetadata metadata;
    if(!logh::readCountryMetadata(paintPacket,sizeof(paintPacket),2,17,metadata) ||
        wcscmp(metadata.name,L"제국 시험") || wcscmp(metadata.calendar,L"시험력") || metadata.yearOffset!=-310 ||
        metadata.revision!=9007199254740993ULL || logh::readCountryMetadata(paintPacket,sizeof(paintPacket),2,18,metadata) ||
        logh::readCountryMetadata(paintPacket,sizeof(paintPacket)-1,2,17,metadata))return 505;
    publishCountryMetadata(metadata);
    BYTE emblemPacket[24+7*36]={};memcpy(emblemPacket,paintPacket,sizeof(paintPacket));emblemPacket[8]=16;emblemPacket[20]=7;
    auto emblemRow=emblemPacket+24+6*36;*reinterpret_cast<unsigned*>(emblemRow)=7;emblemRow[4]=6;emblemRow[6]=2;emblemRow[8]=L'2';
    logh::CountryMetadata emblemMetadata;
    if(!logh::readCountryMetadata(emblemPacket,sizeof(emblemPacket),2,17,emblemMetadata) || emblemMetadata.emblem!=2 || !emblemMetadata.emblemKnown ||
        logh::readCountryMetadata(emblemPacket,sizeof(emblemPacket)-1,2,17,emblemMetadata))return 1120;
    publishCountryMetadata(emblemMetadata);publishCountryMetadata(metadata);
    if(runtimeCountryEmblem(2)!=2 || runtimeCountryEmblem(42))return 1121;
    emblemRow[8]=L'4';if(logh::readCountryMetadata(emblemPacket,sizeof(emblemPacket),2,17,emblemMetadata))return 1122;
    if(runtimeCountryAffiliation(2)!=L"제국 시험" || runtimeCountryAffiliation(2,1)!=L"제국 시험 반란군" ||
        runtimeCountryAffiliation(3)!=L"자유행성동맹" || runtimeCountryAffiliation(2,2)!=L"미확인")return 514;
    unsigned nameSequence=countryNameSequence.load();publishCountryMetadata(metadata);
    if(countryNameSequence.load()!=nameSequence || logh::fitCountryLabel(L"자유행성동맹",12)!=L"자유행성동맹" ||
        logh::fitCountryLabel(L"긴국가이름이반복되는국가이름",12)!=L"긴국가이..." ||
        logh::fitCountryLabel(L"abcdefghijklmnop",12)!=L"abcdefghi..." ||
        logh::fitCountryLabel(L"abc",2)!=L"" || logh::fitCountryLabel(L"abc",3)!=L"abc")return 515;
    if(!runtimeNavigationHud(0,1000,675,2,navigationCp,navigationDate) || !wcsstr(navigationDate,L"시험력 485년"))return 506;
    auto staleCountryMetadata=metadata;staleCountryMetadata.yearOffset=-309;--staleCountryMetadata.revision;publishCountryMetadata(staleCountryMetadata);
    wcscpy_s(staleCountryMetadata.name,L"옛 국가 이름");publishCountryMetadata(staleCountryMetadata);
    if(runtimeCountryAffiliation(2)!=L"제국 시험" || countryNameSequence.load()!=nameSequence)return 516;
    auto renamedCountry=metadata;wcscpy_s(renamedCountry.name,L"새 국가 이름");++renamedCountry.revision;publishCountryMetadata(renamedCountry);
    if(runtimeCountryAffiliation(2)!=L"새 국가 이름" || countryNameSequence.load()!=nameSequence+1)return 517;
    auto otherCountry=renamedCountry;otherCountry.id=42;wcscpy_s(otherCountry.name,L"같은 외형의 다른 국가");publishCountryMetadata(otherCountry);
    if(runtimeCountryAffiliation(42)!=L"같은 외형의 다른 국가" || runtimeCountryAffiliation(2)!=L"새 국가 이름")return 518;
    if(!runtimeNavigationHud(0,1000,675,2,navigationCp,navigationDate) || !wcsstr(navigationDate,L"시험력 485년"))return 507;
    paintField(3,L"-0");if(logh::readCountryMetadata(paintPacket,sizeof(paintPacket),2,17,metadata))return 508;
    paintField(3,L"-310");paintField(2,L"시험\n력");if(logh::readCountryMetadata(paintPacket,sizeof(paintPacket),2,17,metadata))return 509;
    paintField(2,L"시험력");paintField(4,L"922337203685");paintField(5,L"4775808");
    if(logh::readCountryMetadata(paintPacket,sizeof(paintPacket),2,17,metadata))return 510;
    resetCountryMetadata();
    if(runtimeCountryAffiliation(2)!=L"은하제국" || runtimeCountryAffiliation(42)!=L"미확인")return 519;
    if(!runtimeNavigationHud(0,1000,675,2,navigationCp,navigationDate) || !wcsstr(navigationDate,L"제국력 486년"))return 511;
    const logh::CountryCalendar beforeNewEmpire={L"new_empire",L"신제국력",-798};
    if(!logh::formatNavigationHud(0,1000,675,beforeNewEmpire,navigationCp,navigationDate) || !wcsstr(navigationDate,L"신제국력 개원전 4년"))return 512;
    if(!logh::formatNavigationHud(4*31104000u,1000,675,beforeNewEmpire,navigationCp,navigationDate) || !wcsstr(navigationDate,L"신제국력 1년"))return 513;
    BYTE iconPixels[]={255,128,64,73,7,7,7,255,91,92,93,94,0,0,255,0,100,100,100,9,81,82,83,84};
    if(!logh::neutralCountryPixels(iconPixels,12,2,2) || iconPixels[0]!=iconPixels[1] || iconPixels[1]!=iconPixels[2] ||
        iconPixels[3]!=73 || iconPixels[4]!=7 || iconPixels[7]!=255 || iconPixels[8]!=91 || iconPixels[11]!=94 ||
        iconPixels[12]!=iconPixels[13] || iconPixels[13]!=iconPixels[14] || iconPixels[15]!=0 || iconPixels[19]!=9 || iconPixels[23]!=84)return 298;
    BYTE unchanged[sizeof(iconPixels)];memcpy(unchanged,iconPixels,sizeof(iconPixels));
    if(!logh::neutralCountryPixels(iconPixels,12,2,2) || memcmp(unchanged,iconPixels,sizeof(iconPixels)))return 299;
    if(logh::neutralCountryPixels(nullptr,12,2,2) || logh::neutralCountryPixels(iconPixels,-12,2,2) ||
        logh::neutralCountryPixels(iconPixels,7,2,2) || logh::neutralCountryPixels(iconPixels,4097,2,2) ||
        logh::neutralCountryPixels(iconPixels,12,129,2) || logh::neutralCountryPixels(iconPixels,12,2,129) ||
        logh::neutralCountryPixels(iconPixels,12,0,2) || memcmp(unchanged,iconPixels,sizeof(iconPixels)))return 300;
    if(const int hd=checkHdTextureRules())return hd;
    return 0;
}

int wmain(int argc, wchar_t** argv) {
    const int result=runGuard(argc,argv);
    if(result)std::fprintf(stderr,"Compatibility guard failed: %d (ACP %u)\n",result,GetACP());
    return result;
}
