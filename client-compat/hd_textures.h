// HD texture pack loader. Author: Peppone Choi (peppone-choi)
// When <install>\hd\ exists, the two main texture loads in 0x5e2887 read hd\<data-relative path>.png instead.
// HD textures report their original size from GetLevelDesc so every UV that the client derives from it
// (sprites: 0x4d2030) stays in original pixel space. Nothing changes when the folder is absent.
#pragma once
#include "hd_texture_data.h"

struct HdSurfaceDesc8 {unsigned format,type,usage,pool,size,multisample,width,height;}; // Direct3D 8 ABI
using HdLoadFile=HRESULT(__cdecl*)(void*,const char*,void**,unsigned,unsigned,void*);
using HdGetLevelDesc=HRESULT(WINAPI*)(void*,unsigned,HdSurfaceDesc8*);
using HdRelease=ULONG(WINAPI*)(void*);
static const HdLoadFile hdOriginalLoad=reinterpret_cast<HdLoadFile>(0x5e2d20); // D3DXCreateTextureFromFileExA wrapper
static BYTE* volatile hdResource=nullptr; // EBX at the patched call sites: the texture resource being loaded
static bool hdEnabled=false;
static char hdRoot[MAX_PATH];
static SRWLOCK hdLock=SRWLOCK_INIT;
struct HdEntry {void* texture;unsigned scale;};
static HdEntry hdEntries[8192]; // open addressing by texture pointer
static void* const hdTombstone=reinterpret_cast<void*>(1);
static void** hdVtable=nullptr;
static HdGetLevelDesc hdOriginalDesc=nullptr;
static HdRelease hdOriginalRelease=nullptr;
static LONG hdLoaded=0;
static LONG hdLogged=0;

static unsigned hdSlot(const void* texture) {
    return static_cast<unsigned>((reinterpret_cast<ULONG_PTR>(texture)>>4)*2654435761u)&(_countof(hdEntries)-1);
}
static unsigned hdScale(void* texture) {
    unsigned scale=0;
    AcquireSRWLockShared(&hdLock);
    for(unsigned i=hdSlot(texture),n=0;n<_countof(hdEntries);++n,i=(i+1)&(_countof(hdEntries)-1)) {
        if(!hdEntries[i].texture)break;
        if(hdEntries[i].texture==texture){scale=hdEntries[i].scale;break;}
    }
    ReleaseSRWLockShared(&hdLock);
    return scale;
}
static bool hdRemember(void* texture,unsigned scale) {
    bool stored=false;
    AcquireSRWLockExclusive(&hdLock);
    for(unsigned i=hdSlot(texture),n=0;n<_countof(hdEntries);++n,i=(i+1)&(_countof(hdEntries)-1)) {
        if(!hdEntries[i].texture || hdEntries[i].texture==hdTombstone || hdEntries[i].texture==texture) {
            hdEntries[i]={texture,scale};stored=true;break;
        }
    }
    ReleaseSRWLockExclusive(&hdLock);
    return stored;
}
static void hdForget(void* texture) {
    AcquireSRWLockExclusive(&hdLock);
    for(unsigned i=hdSlot(texture),n=0;n<_countof(hdEntries);++n,i=(i+1)&(_countof(hdEntries)-1)) {
        if(!hdEntries[i].texture)break;
        if(hdEntries[i].texture==texture){hdEntries[i].texture=hdTombstone;hdEntries[i].scale=0;break;}
    }
    ReleaseSRWLockExclusive(&hdLock);
}

static HRESULT WINAPI hdGetLevelDesc(void* texture,unsigned level,HdSurfaceDesc8* desc) {
    const HRESULT result=hdOriginalDesc(texture,level,desc);
    if(SUCCEEDED(result) && desc)if(const unsigned scale=hdScale(texture)) {
        desc->width=logh_hd::reportedSize(desc->width,scale);
        desc->height=logh_hd::reportedSize(desc->height,scale);
    }
    return result;
}
static ULONG WINAPI hdRelease(void* texture) {
    const ULONG count=hdOriginalRelease(texture);
    if(!count)hdForget(texture);
    return count;
}
// All textures share the wrapper's vtable; hook Release and GetLevelDesc once.
static bool hdHookVtable(void* texture) {
    void** table=*reinterpret_cast<void***>(texture);
    if(hdVtable)return table==hdVtable;
    DWORD previous=0;
    if(!VirtualProtect(table+2,13*sizeof(void*),PAGE_READWRITE,&previous))return false;
    hdOriginalRelease=reinterpret_cast<HdRelease>(table[2]);
    hdOriginalDesc=reinterpret_cast<HdGetLevelDesc>(table[14]);
    table[2]=reinterpret_cast<void*>(&hdRelease);
    table[14]=reinterpret_cast<void*>(&hdGetLevelDesc);
    DWORD ignored=0;VirtualProtect(table+2,13*sizeof(void*),previous,&ignored);
    hdVtable=table;
    return true;
}

static bool hdFileSize(const char* path,unsigned* width,unsigned* height) {
    HANDLE file=CreateFileA(path,GENERIC_READ,FILE_SHARE_READ,nullptr,OPEN_EXISTING,FILE_ATTRIBUTE_NORMAL,nullptr);
    if(file==INVALID_HANDLE_VALUE)return false;
    const DWORD capacity=256*1024; // JPEG frame headers can follow large metadata
    auto buffer=static_cast<unsigned char*>(HeapAlloc(GetProcessHeap(),0,capacity));
    DWORD read=0;
    const bool ok=buffer && ReadFile(file,buffer,capacity,&read,nullptr) &&
        logh_hd::imageSize(buffer,read,logh_hd::fileName(path),width,height);
    if(buffer)HeapFree(GetProcessHeap(),0,buffer);
    CloseHandle(file);
    return ok;
}
static void hdLog(const char* relative,unsigned scale) {
    if(InterlockedIncrement(&hdLogged)>4000)return;
    char path[MAX_PATH];
    if(strcpy_s(path,hdRoot) || strcat_s(path,"hd-textures.log"))return;
    HANDLE file=CreateFileA(path,FILE_APPEND_DATA,FILE_SHARE_READ,nullptr,OPEN_ALWAYS,FILE_ATTRIBUTE_NORMAL,nullptr);
    if(file==INVALID_HANDLE_VALUE)return;
    char line[MAX_PATH+16];
    const int length=wsprintfA(line,"x%u %s\r\n",scale,relative);
    DWORD written=0;
    if(length>0)WriteFile(file,line,static_cast<DWORD>(length),&written,nullptr);
    CloseHandle(file);
}

static HRESULT __cdecl hdLoad(void* device,const char* path,void** texture,unsigned format,unsigned mips,void* palette) {
    BYTE* resource=hdResource;hdResource=nullptr;
    // P8 (0x29) keeps a palette; a resource with an alpha companion (+0x108) is merged by locking.
    if(hdEnabled && resource && texture && path && format!=0x29 && !resource[0x108]) {
        const char* source=reinterpret_cast<const char*>(resource+8);
        char relative[MAX_PATH],hdPath[MAX_PATH];
        unsigned ow=0,oh=0,hw=0,hh=0;
        if(strnlen_s(source,0x100)<0x100 && logh_hd::relativeDataPath(source,relative,sizeof(relative)) &&
            !logh_hd::skipped(relative) && !strcpy_s(hdPath,hdRoot) && !strcat_s(hdPath,relative) && !strcat_s(hdPath,".png") &&
            hdFileSize(hdPath,&hw,&hh) && hdFileSize(path,&ow,&oh)) {
            if(const unsigned scale=logh_hd::scaleFor(ow,oh,hw,hh)) {
                void* created=nullptr;
                const HRESULT result=hdOriginalLoad(device,hdPath,&created,0x15,mips,palette); // A8R8G8B8
                if(SUCCEEDED(result) && created) {
                    if(hdHookVtable(created) && hdRemember(created,scale)) {
                        *texture=created;InterlockedIncrement(&hdLoaded);hdLog(relative,scale);
                        return result;
                    }
                    (*reinterpret_cast<HdRelease*>(*reinterpret_cast<void***>(created)+2))(created);
                }
            }
        }
    }
    return hdOriginalLoad(device,path,texture,format,mips,palette);
}

static __declspec(naked) void hdLoadStub() {
    __asm {
        mov dword ptr [hdResource], ebx
        jmp hdLoad
    }
}

static bool installHdTextures() {
    char module[MAX_PATH];
    const DWORD length=GetModuleFileNameA(nullptr,module,MAX_PATH);
    if(!length || length>=MAX_PATH)return true;
    char* slash=strrchr(module,'\\');
    if(!slash)return true;
    slash[1]=0;
    if(strcat_s(module,"..\\hd\\") || !GetFullPathNameA(module,MAX_PATH,hdRoot,nullptr))return true;
    const DWORD attributes=GetFileAttributesA(hdRoot);
    if(attributes==INVALID_FILE_ATTRIBUTES || !(attributes&FILE_ATTRIBUTE_DIRECTORY))return true; // no pack installed
    const unsigned sites[]={0x5e29eb,0x5e2a1f}; // main loads; 0x5e2c40/0x5e2c74 load alpha companions
    for(unsigned site:sites) {
        BYTE expected[5]={0xe8};
        *reinterpret_cast<LONG*>(expected+1)=static_cast<LONG>(0x5e2d20-(site+5));
        if(memcmp(reinterpret_cast<void*>(site),expected,5))return false;
    }
    for(unsigned site:sites) {
        BYTE expected[5]={0xe8},replacement[5]={0xe8};
        *reinterpret_cast<LONG*>(expected+1)=static_cast<LONG>(0x5e2d20-(site+5));
        *reinterpret_cast<LONG*>(replacement+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&hdLoadStub)-reinterpret_cast<BYTE*>(site+5));
        if(!patchBytes(reinterpret_cast<void*>(site),expected,replacement,5))return false;
    }
    hdEnabled=true;
    return true;
}
