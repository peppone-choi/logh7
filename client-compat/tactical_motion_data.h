// Decoded native 0400/0402 origins, before interpolation. Author: 최병호.
#pragma once
#include <string.h>
#include <float.h>
namespace logh {
struct TacticalMoveOrigins {
    struct Origin { unsigned id; float direction,x,y; } units[32];
    unsigned count=0;
    bool stationary=false;
    bool read(const unsigned char* message) {
        count=0;stationary=false;
        unsigned n=message[12],points=message[0x298];
        float speed,direction;memcpy(&speed,message+0x290,4);memcpy(&direction,message+0x294,4);
        if (!n || n>32 || !points || points>32 || !_finite(speed) || speed<0 || speed>1 || !_finite(direction)) return false;
        for (unsigned i=0;i<n;++i) {
            auto row=message+16+i*20;float z;
            memcpy(&units[i].id,row,4);memcpy(&units[i].direction,row+4,4);
            memcpy(&units[i].x,row+8,4);memcpy(&units[i].y,row+12,4);memcpy(&z,row+16,4);
            if (!units[i].id || !_finite(units[i].direction) || !_finite(units[i].x) || !_finite(units[i].y) || z!=0 ||
                units[i].x < -100000 || units[i].x > 100000 || units[i].y < -100000 || units[i].y > 100000) return false;
            for (unsigned j=0;j<i;++j) if (units[i].id==units[j].id) return false;
        }
        for (unsigned i=0;i<points;++i) {
            float x,y,z;memcpy(&x,message+0x29c+i*12,4);memcpy(&y,message+0x2a0+i*12,4);memcpy(&z,message+0x2a4+i*12,4);
            if (!_finite(x) || !_finite(y) || z!=0 || x < -100000 || x > 100000 || y < -100000 || y > 100000) return false;
        }
        float x,y;memcpy(&x,message+0x29c,4);memcpy(&y,message+0x2a0,4);
        unsigned wait;memcpy(&wait,message+4,4);
        stationary=!wait && n==1 && points==1 && speed==0 && x==units[0].x && y==units[0].y && direction==units[0].direction;
        count=n;return true;
    }
};
}
