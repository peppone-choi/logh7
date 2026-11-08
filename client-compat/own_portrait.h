// Substitute the exact native strategy HUD portrait quad. Author: Peppone Choi.
#pragma once
static void* ownPortraitWorld=nullptr;
static unsigned ownPortraitCharacter=0;
static void* ownPortraitQuads[4]={};

static BYTE __fastcall loadOwnPortraitSlot(void* manager,void*,void* widget,int face,int age,unsigned slot) {
    auto world=*reinterpret_cast<void**>(0x7ccffc);
    auto own=world ? at<BYTE*>(world,8) : nullptr;
    const unsigned character=own && at<BYTE>(own,0) ? at<unsigned>(own,0x24) : 0;
    const BYTE result=reinterpret_cast<BYTE(__thiscall*)(void*,void*,int,int,unsigned)>(0x5933e0)
        (manager,widget,face,age,slot);
    if(ownPortraitWorld!=world || ownPortraitCharacter!=character) {
        ownPortraitWorld=world;ownPortraitCharacter=character;
        memset(ownPortraitQuads,0,sizeof(ownPortraitQuads));
    }
    if(slot<4)ownPortraitQuads[slot]=result && widget && character ? at<void*>(widget,0xcf4+slot*4) : nullptr;
    return result;
}

static UploadedCountryTexture* ownPortraitTexture(void* world,unsigned character) {
    static UploadedCountryTexture texture;
    static unsigned loaded=0;
    const auto device=*reinterpret_cast<void**>(0x2229400);
    if(!device)return nullptr;
    unsigned serial=0;
    BYTE rgba[4096];
    AcquireSRWLockShared(&countryPaintLock);
    const bool available=ownPortraitPresent && countryPaintInbox.world==world &&
        countryPaintInbox.character==character;
    const bool upload=available && (loaded!=ownPortraitSerial || texture.device!=device);
    if(upload){serial=ownPortraitSerial;memcpy(rgba,ownPortraitPixels,sizeof(rgba));}
    ReleaseSRWLockShared(&countryPaintLock);
    if(!available)return nullptr;
    if(upload) {
        // Letterbox the square display copy and composite transparency over the native black backing.
        BYTE tga[18+32*40*4]={};tga[2]=2;tga[12]=32;tga[14]=40;tga[16]=32;tga[17]=0x28;
        for(unsigned p=0;p<32*40;++p)tga[18+p*4+3]=255;
        for(unsigned p=0;p<1024;++p) {
            auto pixel=tga+18+(4*32+p)*4;
            for(unsigned c=0;c<3;++c)pixel[c]=static_cast<BYTE>((rgba[p*4+2-c]*rgba[p*4+3]+127)/255);
        }
        if(!createUploadedTexture(texture,tga,sizeof(tga),device))return nullptr;
        loaded=serial;
    }
    return &texture;
}

static unsigned __cdecl drawOwnPortraitSlot(void* quad,const float* point,const float* rect,const float* color,float width,float height) {
    const auto draw=reinterpret_cast<unsigned(__cdecl*)(void*,const float*,const float*,const float*,float,float)>(0x4ddc00);
    auto world=*reinterpret_cast<void**>(0x7ccffc);
    auto own=world ? at<BYTE*>(world,8) : nullptr;
    if(quad && world==ownPortraitWorld && own && at<BYTE>(own,0) && at<unsigned>(own,0x24)==ownPortraitCharacter &&
        at<BYTE>(world,0x2a58f8) && !at<BYTE>(world,0x126718)) {
        for(auto slot:ownPortraitQuads)if(quad==slot) {
            if(auto texture=ownPortraitTexture(world,ownPortraitCharacter)) {
                const float source[]={0,0,32,40};
                return draw(texture->quad,point,source,color,width,height);
            }
            break;
        }
    }
    return draw(quad,point,rect,color,width,height);
}

static bool installOwnPortrait() {
    struct Site {unsigned address;BYTE bytes[5];const void* hook;};
    const Site sites[]={{0x58d293,{0xe8,0x48,0x61,0,0},&loadOwnPortraitSlot},
                        {0x50cef5,{0xe8,0x06,0x0d,0xfd,0xff},&drawOwnPortraitSlot}};
    for(const auto& site:sites)if(memcmp(reinterpret_cast<void*>(site.address),site.bytes,5))return false;
    for(const auto& site:sites) {
        auto target=reinterpret_cast<BYTE*>(site.address);
        BYTE call[5]={0xe8};
        *reinterpret_cast<LONG*>(call+1)=static_cast<LONG>(reinterpret_cast<const BYTE*>(site.hook)-target-5);
        if(!patchBytes(target,site.bytes,call,5))return false;
    }
    return true;
}
