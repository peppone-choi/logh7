// Neutral RGB mask; preserve alpha and row padding. Author: Peppone Choi.
#pragma once
namespace logh {
inline bool neutralCountryPixels(unsigned char* pixels,int pitch,unsigned width,unsigned height) {
    if(!pixels || !width || !height || width>128 || height>128 || pitch<static_cast<int>(width*4) || pitch>4096)return false;
    for(unsigned y=0;y<height;++y)for(unsigned x=0;x<width;++x) {
        auto pixel=pixels+y*pitch+x*4;
        const unsigned gray=(54u*pixel[2]+183u*pixel[1]+19u*pixel[0]+128u)>>8;
        pixel[0]=pixel[1]=pixel[2]=static_cast<unsigned char>(gray);
    }
    return true;
}
}
