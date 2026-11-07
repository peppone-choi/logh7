// Neutralize the military atlas on the render thread, including already-loaded textures.
#pragma once
#include "country_icon_data.h"
#include <intrin.h>
// Direct3D 8 ABI, not Direct3D 9: the surface description includes Size.
struct CountrySurfaceDesc8 {unsigned format,type,usage,pool,size,multisample,width,height;};
struct CountryLockedRect8 {int pitch;BYTE* bits;};
static struct {void* texture;ULONGLONG until;} neutralCountryCache[8]={};
static unsigned neutralCountryCursor=0;
static bool neutralizeCountryResource(BYTE* resource,const char* asset="icon_country32.tga",bool exactSize=true) {
    if(!resource)return false;
    // Restrict this transform to the verified atlas and its four military tiles.
    const char* path=reinterpret_cast<const char*>(resource+8);
    if(strnlen_s(path,512)==512)return false;
    const char* name=path;for(auto p=path;*p;++p)if(*p=='/' || *p=='\\')name=p+1;
    if(_stricmp(name,asset))return false;
    auto texture=at<void*>(resource,0x220);if(!texture)return false;
    const auto now=GetTickCount64();
    for(const auto& cached:neutralCountryCache)if(cached.texture==texture && now<cached.until)return true;
    auto methods=*reinterpret_cast<void***>(texture);
    if(reinterpret_cast<unsigned(WINAPI*)(void*)>(methods[13])(texture)!=1)return false;
    CountrySurfaceDesc8 desc={};
    if(FAILED(reinterpret_cast<HRESULT(WINAPI*)(void*,unsigned,CountrySurfaceDesc8*)>(methods[14])(texture,0,&desc)) ||
        desc.format!=21 || desc.type!=1 || desc.width<64 || desc.height<64 ||
        (exactSize && (desc.width!=128 || desc.height!=128)))return false; // A8R8G8B8
    CountryLockedRect8 locked={};
    if(FAILED(reinterpret_cast<HRESULT(WINAPI*)(void*,unsigned,CountryLockedRect8*,const RECT*,DWORD)>(methods[16])
        (texture,0,&locked,nullptr,0)))return false;
    const unsigned scale=logh::countryAtlasScale(desc.width,desc.height,desc.size,locked.pitch);
    const bool changed=scale && logh::neutralCountryPixels(locked.bits,locked.pitch,64*scale,64*scale);
    const HRESULT unlocked=reinterpret_cast<HRESULT(WINAPI*)(void*,unsigned)>(methods[17])(texture,0);
    if(!changed || FAILED(unlocked))return false;
    // Recheck periodically: a renderer reset may restore pixels using the same COM wrapper.
    auto& cached=neutralCountryCache[neutralCountryCursor++%8];cached.texture=texture;cached.until=now+1000;return true;
}
static bool neutralizeCountryIcons() {
    auto sprites=reinterpret_cast<BYTE**>(0x9d12b8);
    if(!sprites[4])return false;
    auto resource=at<BYTE*>(sprites[4],0);
    for(unsigned i=4;i<=7;++i)if(!sprites[i] || at<BYTE*>(sprites[i],0)!=resource ||
        at<int>(sprites[i],4)!=128 || at<int>(sprites[i],8)!=128 ||
        at<int>(sprites[i],12)!=static_cast<int>((i/2-2)*32) || at<int>(sprites[i],16)!=static_cast<int>((i%2)*32) ||
        at<int>(sprites[i],20)!=32 || at<int>(sprites[i],24)!=32)return false;
    return neutralizeCountryResource(resource);
}
static BYTE countrySpriteTrampoline[10];
using CountrySpriteQueue=int(__thiscall*)(void*,const float*,float,float,float,float,void*,int,unsigned,int,int);
static CountrySpriteQueue originalCountrySpriteQueue=reinterpret_cast<CountrySpriteQueue>(&countrySpriteTrampoline[0]);
static int __fastcall queueCountrySprite(void* manager,void*,const float* scale,float x,float y,float width,float height,
    void* sprite,int mode,unsigned color,int first,int second) {
    auto sprites=reinterpret_cast<void**>(0x9d12b8);
    for(unsigned i=4;i<=7;++i)if(sprite && sprite==sprites[i]) {
        unsigned rgb=0xffffff;
        if(neutralizeCountryIcons()) {
            auto palette=reinterpret_cast<const BYTE*>(0x771f68)+i*3;
            rgb=(palette[0]<<16)|(palette[1]<<8)|palette[2];
            if(i==4 || i==6) {
                const auto caller=reinterpret_cast<unsigned>(_ReturnAddress());
                const unsigned field=caller>=0x4d8800 && caller<0x4d89c0 ? 3u : 0u;
                rgb=countryPaintRgb(i/2,field,rgb);
            }
        }
        color=(color&0xff000000)|rgb;break;
    }
    return originalCountrySpriteQueue(manager,scale,x,y,width,height,sprite,mode,color,first,second);
}
static bool installCountryIcon() {
    const BYTE expected[]={0x51,0x8b,0x44,0x24,0x08};auto entry=reinterpret_cast<BYTE*>(0x4cb2b0);
    if(memcmp(entry,expected,5))return false;
    DWORD previous=0;if(!VirtualProtect(countrySpriteTrampoline,sizeof(countrySpriteTrampoline),PAGE_EXECUTE_READWRITE,&previous))return false;
    memcpy(countrySpriteTrampoline,expected,5);countrySpriteTrampoline[5]=0xe9;
    *reinterpret_cast<LONG*>(countrySpriteTrampoline+6)=static_cast<LONG>(entry+5-countrySpriteTrampoline-10);
    FlushInstructionCache(GetCurrentProcess(),countrySpriteTrampoline,sizeof(countrySpriteTrampoline));
    BYTE jump[]={0xe9,0,0,0,0};*reinterpret_cast<LONG*>(jump+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&queueCountrySprite)-entry-5);
    return patchBytes(entry,expected,jump,5);
}
