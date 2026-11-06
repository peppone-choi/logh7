// HD texture pack: pure path, header and scale rules (no game state). Author: Peppone Choi (peppone-choi)
// The loader at 0x5e2887 passes resource+8 ("/../data/image/...") to D3DXCreateTextureFromFileExA.
// Sprites compute UV = pixel / GetLevelDesc width (0x4d2030), so an HD texture must report its original size.
#pragma once
#include <string.h>

namespace logh_hd {

// Files the client edits after loading (0x5e2a51.. suffix checks) or a DLL hook locks by fixed size.
// tools/assets/build_hd_pack.py reads this list; keep it as plain string literals.
static const char* const kSkipMarkers[]={"_col_p","_col_b","_alpha_s","_normalmap"};
static const char* const kSkipNames[]={"icon_country32.tga"};

static char lowerAscii(char c){return c>='A'&&c<='Z'?static_cast<char>(c-'A'+'a'):c;}

static bool containsNoCase(const char* text,const char* needle) {
    const size_t n=strlen(needle);
    for(const char* p=text;*p;++p) {
        size_t i=0;
        while(i<n && p[i] && lowerAscii(p[i])==lowerAscii(needle[i]))++i;
        if(i==n)return true;
    }
    return false;
}

static bool equalNoCase(const char* a,const char* b) {
    for(;*a && *b;++a,++b)if(lowerAscii(*a)!=lowerAscii(*b))return false;
    return !*a && !*b;
}

static const char* fileName(const char* path) {
    const char* name=path;
    for(const char* p=path;*p;++p)if(*p=='/' || *p=='\\')name=p+1;
    return name;
}

// "/../data/image/icon/a.tga" -> "image\icon\a.tga". The segment must be exactly "data".
static bool relativeDataPath(const char* path,char* out,size_t capacity) {
    if(!path || !out || !capacity)return false;
    const char* start=nullptr;
    for(const char* p=path;*p;++p) {
        const bool boundary=p==path || p[-1]=='/' || p[-1]=='\\';
        if(boundary && lowerAscii(p[0])=='d' && lowerAscii(p[1])=='a' && lowerAscii(p[2])=='t' && lowerAscii(p[3])=='a' &&
            (p[4]=='/' || p[4]=='\\'))start=p+5;
    }
    if(!start || !*start)return false;
    size_t n=0;
    for(const char* p=start;*p;++p) {
        if(n+1>=capacity)return false;
        if(p[0]=='.' && p[1]=='.')return false; // no escaping the pack root
        out[n++]=*p=='/'?'\\':*p;
    }
    out[n]=0;return true;
}

static bool skipped(const char* relative) {
    const char* name=fileName(relative);
    for(const char* marker:kSkipMarkers)if(containsNoCase(name,marker))return true;
    for(const char* excluded:kSkipNames)if(equalNoCase(name,excluded))return true;
    return false;
}

static unsigned le16(const unsigned char* p){return p[0]|(p[1]<<8);}
static unsigned le32(const unsigned char* p){return p[0]|(p[1]<<8)|(p[2]<<16)|(static_cast<unsigned>(p[3])<<24);}
static unsigned be16(const unsigned char* p){return (p[0]<<8)|p[1];}
static unsigned be32(const unsigned char* p){return (static_cast<unsigned>(p[0])<<24)|(p[1]<<16)|(p[2]<<8)|p[3];}

// Image size from the first bytes of a PNG, DDS, BMP, JPEG or (by extension) TGA file.
static bool imageSize(const unsigned char* d,size_t n,const char* name,unsigned* w,unsigned* h) {
    if(!d || !w || !h)return false;
    *w=*h=0;
    if(n>=24 && !memcmp(d,"\x89PNG\r\n\x1a\n",8) && !memcmp(d+12,"IHDR",4)){*w=be32(d+16);*h=be32(d+20);}
    else if(n>=20 && !memcmp(d,"DDS ",4)){*h=le32(d+12);*w=le32(d+16);}
    else if(n>=26 && d[0]=='B' && d[1]=='M') {
        *w=le32(d+18);const int height=static_cast<int>(le32(d+22));*h=static_cast<unsigned>(height<0?-height:height);
    } else if(n>=4 && d[0]==0xff && d[1]==0xd8) {
        size_t i=2;
        while(i+9<n) {
            if(d[i]!=0xff){++i;continue;}
            const unsigned char m=d[i+1];
            if(m==0xd8 || m==0x01 || (m>=0xd0 && m<=0xd7)){i+=2;continue;}
            const unsigned length=be16(d+i+2);
            if(m>=0xc0 && m<=0xcf && m!=0xc4 && m!=0xc8 && m!=0xcc){*h=be16(d+i+5);*w=be16(d+i+7);break;}
            if(length<2)return false;
            i+=2+length;
        }
    } else {
        const size_t len=strlen(name?name:"");
        if(len<4 || !equalNoCase(name+len-4,".tga") || n<18)return false;
        *w=le16(d+12);*h=le16(d+14);
    }
    return *w>0 && *h>0 && *w<=16384 && *h<=16384;
}

// Uniform integer scale between the original and HD files, or 0 when they do not match.
static unsigned scaleFor(unsigned ow,unsigned oh,unsigned hw,unsigned hh) {
    if(!ow || !oh || hw%ow || hh%oh)return 0;
    const unsigned k=hw/ow;
    return k>=2 && k<=8 && hh/oh==k ? k : 0;
}

// The size reported to the game: the created HD level divided by the pack scale.
static unsigned reportedSize(unsigned actual,unsigned k){return k? (actual/k? actual/k:1) : actual;}

// FNV-1a 32-bit over the original file bytes. The HD file name carries it so a pack made from the
// base install never replaces a texture the 2004-05-14 update changed under the same path.
static unsigned fnv1a32(const unsigned char* d,size_t n) {
    unsigned h=2166136261u;
    for(size_t i=0;i<n;++i){h^=d[i];h*=16777619u;}
    return h;
}

// "image\icon\a.tga" + 0x1234abcd -> "image\icon\a.tga.1234abcd.png" (lower-case hex).
static bool hdFileName(const char* relative,unsigned hash,char* out,size_t capacity) {
    const size_t n=strlen(relative);
    if(n+14>capacity)return false; // ".xxxxxxxx.png" plus the terminator
    memcpy(out,relative,n);
    out[n]='.';
    for(int i=0;i<8;++i)out[n+1+i]="0123456789abcdef"[(hash>>(28-4*i))&15];
    memcpy(out+n+9,".png",5);
    return true;
}

} // namespace logh_hd
