// HD texture pack loader. Author: Peppone Choi (peppone-choi)
// When <install>\hd\ exists, the two main texture loads in 0x5e2887 read hd\<data-relative path>.<fnv1a32 of the
// original file>.png instead (logh_hd::hdFileName), so each original version gets only its own HD texture.
// The in-memory load of the same loader (face TGAs from .tcf) reads hd\mem	cf.<fnv1a32 of the bytes>.png.
// HD textures report their original size from GetLevelDesc so every UV that the client derives from it
// (sprites: 0x4d2030) stays in original pixel space. Nothing changes when the folder is absent.
#pragma once
#include "hd_texture_data.h"

struct HdSurfaceDesc8 {unsigned format,type,usage,pool,size,multisample,width,height;}; // Direct3D 8 ABI
using HdLoadFile=HRESULT(__cdecl*)(void*,const char*,void**,unsigned,unsigned,void*);
using HdGetLevelDesc=HRESULT(WINAPI*)(void*,unsigned,HdSurfaceDesc8*);
using HdRelease=ULONG(WINAPI*)(void*);
using HdLoadMemory=HRESULT(__cdecl*)(void*,const void*,unsigned,void**,unsigned,unsigned,void*);
static const HdLoadFile hdOriginalLoad=reinterpret_cast<HdLoadFile>(0x5e2d20); // D3DXCreateTextureFromFileExA wrapper
// D3DXCreateTextureFromFileInMemoryEx wrapper. The loader takes it when resource+0x20c holds bytes: face TGAs that
// 0x5924c0 reads from the .tcf files at the tcf.hed offsets (0x5e3650 makes the resource).
static const HdLoadMemory hdOriginalLoadMemory=reinterpret_cast<HdLoadMemory>(0x5e2d60);
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

// Image size from the file header; with hash set, the whole file is read and hashed (originals only).
static bool hdFileSize(const char* path,unsigned* width,unsigned* height,unsigned* hash=nullptr) {
    HANDLE file=CreateFileA(path,GENERIC_READ,FILE_SHARE_READ,nullptr,OPEN_EXISTING,FILE_ATTRIBUTE_NORMAL,nullptr);
    if(file==INVALID_HANDLE_VALUE)return false;
    LARGE_INTEGER size{};
    DWORD capacity=256*1024; // JPEG frame headers can follow large metadata
    if(hash) {
        if(!GetFileSizeEx(file,&size) || size.QuadPart<=0 || size.QuadPart>64*1024*1024){CloseHandle(file);return false;}
        capacity=static_cast<DWORD>(size.QuadPart);
    }
    auto buffer=static_cast<unsigned char*>(HeapAlloc(GetProcessHeap(),0,capacity));
    DWORD read=0;
    bool ok=buffer && ReadFile(file,buffer,capacity,&read,nullptr) && (!hash || read==capacity) &&
        logh_hd::imageSize(buffer,read,logh_hd::fileName(path),width,height);
    if(ok && hash)*hash=logh_hd::fnv1a32(buffer,read);
    if(buffer)HeapFree(GetProcessHeap(),0,buffer);
    CloseHandle(file);
    return ok;
}
static void hdLog(const char* relative,unsigned scale) { // scale 0: memory texture without an HD file
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

// Creates the HD texture from hdPath when it is exactly N times the original; false leaves *texture untouched.
static bool hdCreate(void* device,const char* hdPath,const char* relative,unsigned ow,unsigned oh,void** texture,unsigned mips,void* palette) {
    unsigned hw=0,hh=0;
    if(!hdFileSize(hdPath,&hw,&hh))return false;
    const unsigned scale=logh_hd::scaleFor(ow,oh,hw,hh);
    if(!scale)return false;
    void* created=nullptr;
    const HRESULT result=hdOriginalLoad(device,hdPath,&created,0x15,mips,palette); // A8R8G8B8
    if(FAILED(result) || !created)return false;
    if(hdHookVtable(created) && hdRemember(created,scale)) {
        *texture=created;InterlockedIncrement(&hdLoaded);hdLog(relative,scale);
        return true;
    }
    (*reinterpret_cast<HdRelease*>(*reinterpret_cast<void***>(created)+2))(created);
    return false;
}

static HRESULT __cdecl hdLoad(void* device,const char* path,void** texture,unsigned format,unsigned mips,void* palette) {
    BYTE* resource=hdResource;hdResource=nullptr;
    // P8 (0x29) keeps a palette; a resource with an alpha companion (+0x108) is merged by locking.
    if(hdEnabled && resource && texture && path && format!=0x29 && !resource[0x108]) {
        const char* source=reinterpret_cast<const char*>(resource+8);
        char relative[MAX_PATH],name[MAX_PATH],hdPath[MAX_PATH];
        unsigned ow=0,oh=0,hash=0;
        // The HD name holds the original's hash, so a changed original (e.g. the update) keeps its own texture.
        if(strnlen_s(source,0x100)<0x100 && logh_hd::relativeDataPath(source,relative,sizeof(relative)) &&
            !logh_hd::skipped(relative) && hdFileSize(path,&ow,&oh,&hash) &&
            logh_hd::hdFileName(relative,hash,name,sizeof(name)) && !strcpy_s(hdPath,hdRoot) && !strcat_s(hdPath,name) &&
            hdCreate(device,hdPath,relative,ow,oh,texture,mips,palette))return S_OK;
    }
    return hdOriginalLoad(device,path,texture,format,mips,palette);
}

// Memory textures (face TGAs) have no data path; the HD file is hd\mem\tcf.<fnv1a32 of the bytes>.png.
static HRESULT __cdecl hdLoadMemory(void* device,const void* data,unsigned size,void** texture,unsigned format,unsigned mips,void* palette) {
    BYTE* resource=hdResource;hdResource=nullptr;
    if(hdEnabled && resource && texture && data && size && size<=64*1024*1024 && format!=0x29 && !resource[0x108]) {
        const auto bytes=static_cast<const unsigned char*>(data);
        unsigned ow=0,oh=0;
        char name[MAX_PATH],hdPath[MAX_PATH];
        if(logh_hd::imageSize(bytes,size,"tcf.tga",&ow,&oh) &&
            logh_hd::hdFileName("mem\\tcf",logh_hd::fnv1a32(bytes,size),name,sizeof(name)) &&
            !strcpy_s(hdPath,hdRoot) && !strcat_s(hdPath,name)) {
            if(hdCreate(device,hdPath,name,ow,oh,texture,mips,palette))return S_OK;
            hdLog(name,0);
        }
    }
    return hdOriginalLoadMemory(device,data,size,texture,format,mips,palette);
}

static __declspec(naked) void hdLoadStub() {
    __asm {
        mov dword ptr [hdResource], ebx
        jmp hdLoad
    }
}
static __declspec(naked) void hdLoadMemoryStub() {
    __asm {
        mov dword ptr [hdResource], ebx
        jmp hdLoadMemory
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
    // Main loads from a file and the in-memory load; 0x5e2c40/0x5e2c74 load alpha companions and stay original.
    struct Site {unsigned at,original;void* hook;};
    const Site sites[]={{0x5e29eb,0x5e2d20,reinterpret_cast<void*>(&hdLoadStub)},{0x5e2a1f,0x5e2d20,reinterpret_cast<void*>(&hdLoadStub)},
        {0x5e296a,0x5e2d60,reinterpret_cast<void*>(&hdLoadMemoryStub)}};
    for(const Site& site:sites) {
        BYTE expected[5]={0xe8};
        *reinterpret_cast<LONG*>(expected+1)=static_cast<LONG>(site.original-(site.at+5));
        if(memcmp(reinterpret_cast<void*>(site.at),expected,5))return false;
    }
    for(const Site& site:sites) {
        BYTE expected[5]={0xe8},replacement[5]={0xe8};
        *reinterpret_cast<LONG*>(expected+1)=static_cast<LONG>(site.original-(site.at+5));
        *reinterpret_cast<LONG*>(replacement+1)=static_cast<LONG>(static_cast<BYTE*>(site.hook)-reinterpret_cast<BYTE*>(site.at+5));
        if(!patchBytes(reinterpret_cast<void*>(site.at),expected,replacement,5))return false;
    }
    hdEnabled=true;
    return true;
}
