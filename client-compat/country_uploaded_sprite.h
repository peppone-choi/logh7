// UI-thread graphics only; the native queues own their frame geometry. Author: Peppone Choi.
#pragma once
struct UploadedCountryTexture {
    unsigned id=0;bool map=false;std::uint64_t epoch=0,revision=0;void* device=nullptr;
    BYTE resource[0x228]={},sprite[0x2c]={},quad[20]={};
};
static UploadedCountryTexture uploadedCountryTextures[700];
static unsigned uploadedCountryTextureCount=0;
static std::uint64_t uploadedCountryEpoch=0;
static void releaseUploadedCountryTexture(UploadedCountryTexture& entry) {
    if(auto texture=at<void*>(entry.resource,0x220)) {
        auto methods=*reinterpret_cast<void***>(texture);
        reinterpret_cast<ULONG(WINAPI*)(void*)>(methods[2])(texture);
    }
    entry={};
}
static bool uploadCountryTexture(UploadedCountryTexture& entry,const unsigned char* rgba,void* device) {
    BYTE tga[4114]={};tga[2]=2;tga[12]=32;tga[14]=32;tga[16]=32;tga[17]=0x28;
    for(unsigned p=0;p<1024;++p) {
        const unsigned gray=(54u*rgba[p*4]+183u*rgba[p*4+1]+19u*rgba[p*4+2]+128u)>>8;
        auto pixel=tga+18+p*4;
        pixel[0]=pixel[1]=pixel[2]=static_cast<BYTE>(gray);
        pixel[3]=rgba[p*4+3];
    }
    void* texture=nullptr;
    const HRESULT result=reinterpret_cast<HRESULT(__cdecl*)(void*,const void*,unsigned,void**,unsigned,unsigned,void*)>(0x5e2d60)
        (device,tga,sizeof(tga),&texture,21,1,nullptr);
    if(FAILED(result) || !texture)return false;
    releaseUploadedCountryTexture(entry);
    entry.device=device;
    at<void*>(entry.resource,0x220)=texture;
    // Type 6 queues a frame-owned copy of the descriptor.
    at<void*>(entry.quad,8)=entry.resource;entry.quad[0x10]=3;entry.quad[0x11]=6;entry.quad[0x12]=5;
    return true;
}
static UploadedCountryTexture* uploadedCountryTexture(unsigned country,bool map) {
    if(!country)return nullptr;
    unsigned char rgba[4096];std::uint64_t revision=0,epoch=0;bool found=false;
    AcquireSRWLockShared(&countryImagesLock);
    for(unsigned i=0;i<countryImagesCache.count;++i)if(countryImagesCache.rows[i].id==country) {
        const auto& row=countryImagesCache.rows[i];if(const auto pixels=row.pixels(map)) {
            revision=row.revision;epoch=countryImagesCache.epoch;memcpy(rgba,pixels,4096);found=true;
        }
        break;
    }
    ReleaseSRWLockShared(&countryImagesLock);if(!found)return nullptr;
    if(uploadedCountryEpoch!=epoch) {
        for(unsigned i=0;i<uploadedCountryTextureCount;++i)releaseUploadedCountryTexture(uploadedCountryTextures[i]);
        uploadedCountryTextureCount=0;uploadedCountryEpoch=epoch;
    }
    auto device=*reinterpret_cast<void**>(0x2229400);if(!device)return nullptr;
    auto& count=uploadedCountryTextureCount;unsigned i=0;
    for(;i<count;++i)if(uploadedCountryTextures[i].id==country && uploadedCountryTextures[i].map==map)break;
    if(i==700)return nullptr;
    auto& entry=uploadedCountryTextures[i];
    if(i<count && entry.device==device && entry.epoch==epoch && entry.revision==revision && at<void*>(entry.resource,0x220))return &entry;
    if(!uploadCountryTexture(entry,rgba,device))return nullptr;
    entry.id=country;entry.map=map;entry.epoch=epoch;entry.revision=revision;
    at<void*>(entry.sprite,0)=entry.resource;
    at<int>(entry.sprite,4)=at<int>(entry.sprite,8)=at<int>(entry.sprite,20)=at<int>(entry.sprite,24)=32;
    // 004d2030 only reads the resource's texture and computes this sprite's UVs.
    reinterpret_cast<void(__thiscall*)(void*)>(0x4d2030)(entry.sprite);
    if(i==count)++count;
    return &entry;
}
