// Preserve the two texture-coordinate dot products of the CD extra pass.
// DX9 assembly rejects a masked m4x4 destination. Author: 최병호.
#pragma once
#include <string.h>
namespace logh {
inline unsigned expandEnvironmentMatrix(const char* source,unsigned size,char* output,unsigned capacity) {
    const char needle[]="m4x4 oT0.xy, r0, c12";
    const char replacement[]="dp4 oT0.x, r0, c12\r\ndp4 oT0.y, r0, c13";
    const unsigned n=sizeof(needle)-1,r=sizeof(replacement)-1;
    if(!source || !output || size<n || size>4096 || capacity<=size+r-n)return 0;
    unsigned found=0,count=0;
    for(unsigned i=0;i+n<=size;++i)if(!memcmp(source+i,needle,n)){found=i;++count;}
    if(count!=1)return 0;
    memcpy(output,source,found);memcpy(output+found,replacement,r);
    memcpy(output+found+r,source+found+n,size-found-n);
    output[size+r-n]=0;return size+r-n;
}
}
