// Original scrolling-list control and selection event, with the surviving
// group-54 list fields. The layout is reconstructed, not a recovered screen.
// Author: 최병호.
#pragma once
static void* planetDirectoryList = nullptr;
static unsigned planetDirectoryStates[350];
static void planetDirectoryCell(unsigned row, unsigned col, const wchar_t* text) {
    reinterpret_cast<void(__thiscall*)(void*,void*,const wchar_t*,int,int)>(0x505b50)
        (planetWindowRoot,planetDirectoryList,text,row,col);
}
static void createPlanetDirectory() {
    auto root=planetWindowRoot;
    planetDirectoryList=reinterpret_cast<void*(__thiscall*)(void*,int,int,const void*)>(0x503a10)(root,4,0,nullptr);
    reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x5024e0)(root,planetDirectoryList,1);
    reinterpret_cast<void(__thiscall*)(void*,void*,const char*)>(0x505c50)(root,planetDirectoryList,"B0D0F0");
    reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x5055e0)(root,planetDirectoryList,24);
    static const char path[]="/../data/image/window/menu_parts.tga";
    for (int state=0;state<4;++state) {
        unsigned parts[15]={};
        const unsigned x[]={0,18,35},width[]={17,16,17};
        for (unsigned i=0;i<3;++i) {
            parts[i*5]=reinterpret_cast<unsigned>(path);parts[i*5+1]=x[i];
            parts[i*5+2]=state ? 22 : 0;parts[i*5+3]=width[i];parts[i*5+4]=21;
        }
        reinterpret_cast<void(__thiscall*)(void*,void*,const void*,int,int,int)>(0x503030)
            (root,planetDirectoryList,parts,3,1,state);
    }
    // The original sheet builder uses this helper to attach its scroll controls.
    // Slots 108..113 are separate from this window's existing labels (0..103).
    static_assert(104+9<120,"Native button-slot bound");
    int rect[]={182,164,0,636,300};
    reinterpret_cast<void(__thiscall*)(void*,void*,const int*)>(0x502940)(root,planetDirectoryList,rect);
    reinterpret_cast<void(__cdecl*)(void*,void*,int,int,int)>(0x519330)(root,planetDirectoryList,636,300,104);
    auto layout=reinterpret_cast<void(__thiscall*)(void*,void*,int,int,int,int,int,int,int,int,int,int,int)>(0x503680);
    // Offsets are top/bottom/left/right from the original 700x463 frame.
    // The thumb has its own travel region (00503760), not a fixed rectangle.
    layout(root,planetDirectoryList,0,0,1,1,1,1,90,73,20,44,1);
    layout(root,planetWidget(108),0,0,1,1,1,1,90,362,658,31,0);
    layout(root,planetWidget(110),0,0,1,1,1,1,379,73,658,31,0);
    reinterpret_cast<void(__thiscall*)(void*,void*,int,int,int,int,int,int,int,int,int,int,int)>(0x503760)
        (root,planetWidget(109),0,0,1,1,1,1,101,84,658,31,0);
}
static void planetDirectoryLayout(bool directory) {
    auto root=planetWindowRoot;
    const int ranges[][2]={{0,3},{10,13},{20,30},{40,71},{80,84},{100,103}};
    for (const auto& range:ranges) for (int i=range[0];i<=range[1];++i) {
        bool visible=!directory;
        if (i==0 || i==25 || i==26) visible=true;
        if (directory && (i==1 || i==23 || i==24 || i==27 || i==28)) visible=true;
        if (i==2 || (directory && i==30)) visible=false;
        reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502eb0)(root,planetWidget(i),visible ? 1 : 0);
        // 005025c0 checks +15 (input enabled), not +18 (visible). Hidden
        // detail labels otherwise intercept clicks on the directory below.
        planetEnabled(i,visible);
    }
    for (int i=0;i<planetDecorationCount;++i) {
        auto sprite=reinterpret_cast<void*(__thiscall*)(void*,int,int)>(0x502780)(root,2,i);
        reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502eb0)
            (root,sprite,(!directory || i==planetDecorationCount-1) ? 1 : 0);
    }
    reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502eb0)(root,planetDirectoryList,directory ? 1 : 0);
    reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x5024e0)(root,planetDirectoryList,directory ? 1 : 0);
    for (int i=0;i<6;++i) {
        reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502eb0)
            (root,planetWidget(108+i),directory && i<3 ? 1 : 0);
        planetEnabled(108+i,directory && i<3);
    }
    planetRect(1,directory ? 184 : 432,directory ? 126 : 170,directory ? 634 : 388,24);
    planetRect(27,182,directory ? 477 : 385,directory ? 100 : 92,directory ? 42 : 30);
    planetRect(28,directory ? 292 : 282,directory ? 477 : 385,directory ? 100 : 92,directory ? 42 : 30);
    planetRect(23,directory ? 402 : 390,477,100,42);planetRect(24,directory ? 512 : 500,477,100,42);
    planetRect(25,directory ? 622 : 610,477,directory ? 96 : 100,42);
    planetRect(26,directory ? 728 : 720,477,directory ? 96 : 100,42);
    planetCell(23,directory ? L"이전 목록" : L"이전 재고");planetCell(24,directory ? L"다음 목록" : L"다음 재고");
    planetCell(27,directory ? L"현재 성계" : L"< 성계");
    planetCell(28,directory ? L"전체 목록" : L"성계 >");
    reinterpret_cast<void(__thiscall*)(void*)>(0x503910)(root);
}
static void rebuildPlanetDirectory() {
    auto& info=planetInformation;
    info.directoryCount=info.directory.view(info.directoryRows,info.directoryLocal,info.originGrid);
    for (auto& state:planetDirectoryStates) state=~0u;
    reinterpret_cast<void(__thiscall*)(void*,void*)>(0x5061c0)(planetWindowRoot,planetDirectoryList);
    reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x505a70)(planetWindowRoot,planetDirectoryList,0);
    reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x505a00)(planetWindowRoot,planetDirectoryList,0);
    reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x5053d0)(planetWindowRoot,planetDirectoryList,6);
    const int widths[]={88,134,162,62,58,108};
    const wchar_t* headers[]={L"소속",L"성계",L"행성·요새",L"구분",L"거주",L"인구(만 명)"};
    for (unsigned i=0;i<6;++i) {
        reinterpret_cast<void(__thiscall*)(void*,void*,int,int)>(0x5054b0)(planetWindowRoot,planetDirectoryList,widths[i],i);
        planetDirectoryCell(0,i,headers[i]);
    }
    if (!info.directoryCount) planetDirectoryCell(1,0,L"거점 없음");
    info.displayed=false;
}
static void renderPlanetDirectory() {
    auto& info=planetInformation;
    planetCell(0,L"행성·요새 목록");
    wchar_t text[160];
    _snwprintf_s(text,_countof(text),_TRUNCATE,L"%ls %u곳 · %u / %u쪽 · 현황 수신 %u / %u · 행 선택으로 상세 조회",
        info.directoryLocal ? L"현재 성계" : L"전체",info.directoryCount,info.directoryPage+1,
        info.directoryCount ? (info.directoryCount+97)/98 : 1,info.directory.received(),info.directory.count);
    planetCell(1,text);planetEnabled(25,true);planetEnabled(26,true);
    planetEnabled(27,!info.directoryLocal);planetEnabled(28,info.directoryLocal);
    planetEnabled(23,info.directoryPage>0);
    planetEnabled(24,(info.directoryPage+1)*logh::PlanetDirectory::pageCapacity<info.directoryCount);
    for (unsigned i=0;i<logh::PlanetDirectory::pageCapacity;++i) {
        unsigned index=info.directoryPage*logh::PlanetDirectory::pageCapacity+i;
        if (index>=info.directoryCount) break;
        const auto& row=info.directory.rows[info.directoryRows[index]];
        bool first=planetDirectoryStates[i]==~0u;
        if (!first && planetDirectoryStates[i]==row.state) continue;
        planetDirectoryStates[i]=row.state;
        if (first) {
            wchar_t name[13]={};planetName(row.id,name);planetDirectoryCell(i+1,2,name[0] ? name : L"이름 미수신");
            auto grid=reinterpret_cast<const BYTE*(__stdcall*)(int,int)>(0x4c8b70)(row.grid%100,row.grid/100);
            auto label=grid ? reinterpret_cast<const char*(__stdcall*)(unsigned)>(0x4c8c90)(grid[0]) : nullptr;
            wchar_t system[64]={};if (label) MultiByteToWideChar(932,0,label,-1,system,_countof(system));
            planetDirectoryCell(i+1,1,system[0] ? system : L"성계명 미수신");
            auto base=planetBase(row.id);unsigned kind=base ? logh::infoShort(base,0x14) : ~0u;
            planetDirectoryCell(i+1,3,kind==0 ? L"행성" : kind==1 ? L"요새" : L"미확인");
        }
        bool ready=row.state==2;
        const wchar_t* queryStatus=row.state==3 ? L"응답 없음" : L"조회 중";
        const wchar_t* powers[]={L"중립",L"기타",L"은하제국",L"자유행성동맹",L"기타"};
        const wchar_t* power=ready && row.camp==1 && row.power==2 ? L"제국 반란군" :
            ready && row.camp==1 && row.power==3 ? L"동맹 반란군" : powers[row.power];
        planetDirectoryCell(i+1,0,ready ? power : queryStatus);
        planetDirectoryCell(i+1,4,ready ? row.habitable==0 ? L"불가" : row.habitable==1 ? L"가능" : L"미확인" : L"—");
        if (ready) _snwprintf_s(text,_countof(text),_TRUNCATE,L"%u.%04u",row.population/10000,row.population%10000);
        else wcscpy_s(text,L"—");
        planetDirectoryCell(i+1,5,text);
    }
}
