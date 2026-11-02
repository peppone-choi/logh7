// U-01 オフライン設定 window (command 179 on the personal card, user-facing help constmsg 0/179). The CD keeps the
// 0f18 writer 00464f70 (u32 time, u32 character, five setting bytes at +8..+12) but no screen sends it (ordinal 0x75 in
// 004b78a0 has no caller), so this window does. The five bytes follow the character record's +5f..+63 order:
// strategy, commanded, suggested, announcement, tactics; the server runs the offline tactical AI on the fifth.
// Author: Peppone Choi.
#pragma once
static constexpr unsigned offlineSettingsCommand=179;
static BYTE offlineFlags[5];
static unsigned offlineCursor;
static void sendOfflineSettings() {
    auto& state=taxWindow;
    BYTE record[16]={};
    at<unsigned>(record,0)=reinterpret_cast<unsigned(__thiscall*)(void*)>(0x4c53b0)(state.world);
    at<unsigned>(record,4)=state.character;
    for(unsigned i=0;i<5;++i)record[8+i]=offlineFlags[i] ? 1 : 0;
    reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(state.world,0,0x75,record);
    state.message=L"오프라인 설정을 서버에 보냈습니다.";
}
static void openOfflineSettings(void* own) {
    // The 0323 handler (004ba2b0 case 0x323) copies the parsed own record to world+0x36a5e0; its +5f..+63 are the five
    // switches. The record behind world+8 holds other data there (run-139..141 showed constant values).
    (void)own;auto world=*reinterpret_cast<BYTE**>(0x7ccffc);
    for(unsigned i=0;i<5;++i)offlineFlags[i]=world && at<BYTE>(world,0x36a5e0+0x5f+i) ? 1 : 0;
    offlineCursor=4;
    taxWindow.message=L"항목을 고르고 켬/끔을 바꾼 뒤 저장하세요.";
}
static bool updateOfflineSettings() {
    auto& state=taxWindow;
    static const wchar_t* names[]={L"전략 행동",L"지휘 명령 수행",L"제안 처리",L"통지 처리",L"전술 AI(임무 수행)"};
    taxText(0,L"오프라인 설정");
    wchar_t line[160];
    for(unsigned i=0;i<5;++i) {
        swprintf_s(line,L"%ls %ls: %ls",i==offlineCursor ? L"▶" : L"  ",names[i],offlineFlags[i] ? L"켬" : L"끔");
        taxText(1+i,line);
    }
    taxText(6,state.message);
    taxText(10,L"항목 이동");taxText(11,L"켬/끔");taxText(12,L"-");taxText(13,L"-");taxText(14,L"저장");taxText(15,L"닫기");
    taxEnabled(10,true);taxEnabled(11,true);taxEnabled(12,false);taxEnabled(13,false);taxEnabled(14,true);
    if(GetTickCount64()<state.acceptAfter)return true;
    BYTE event[52]={};
    for(int id=10;id<=15;++id)if(reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,void*,int)>(0x5015f0)(state.root,2,taxWidget(1,id),event,0)) {
        state.acceptAfter=GetTickCount64()+250;
        if(id==15)closeTaxWindow();
        else if(id==10)offlineCursor=(offlineCursor+1)%5;
        else if(id==11)offlineFlags[offlineCursor]^=1;
        else if(id==14)sendOfflineSettings();
        break;
    }
    return true;
}
