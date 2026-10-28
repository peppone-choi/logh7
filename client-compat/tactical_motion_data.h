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
// A zero-length stationary snapshot must also retire the previous render plan.
// 004ca620 reads its baseline even with zero steps; 004ca2e0 may then rotate
// the unit back toward that old baseline unless its active flag is cleared.
inline bool applyStationaryTacticalPose(unsigned char* unit, unsigned char* model,
    const TacticalMoveOrigins::Origin& origin) {
    if (!unit || !model) return false;
    unsigned unitId, modelId;memcpy(&unitId,unit+4,4);memcpy(&modelId,model+4,4);
    if (!unit[0] || !model[0] || unitId!=origin.id || modelId!=origin.id) return false;
    const float pose[]={origin.x,0,origin.y,0,origin.direction,0};
    memcpy(unit+0x14,pose,sizeof(pose));unit[0x60]=0;unit[0x62]=0;
    memcpy(model+0x85c,pose,sizeof(pose));
    memcpy(model+0x88c,pose,sizeof(pose));
    model[9]=0;model[10]=0;model[11]=0;
    memset(model+0x10,0,16);
    return true;
}
}
