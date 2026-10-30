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
// 004ca620 searches goals by coordinates, so A->B->A selects the first A again.
// Match the acknowledged path ordinal to the ordered translation steps instead.
inline bool applyTacticalWaypointOrder(unsigned char* unit,unsigned char* model) {
    if (!unit || !model || !unit[0] || !model[0] || !unit[0x414]) return false;
    unsigned id,modelId,index,points,count,phase,desired;
    memcpy(&id,unit+4,4);memcpy(&modelId,model+4,4);memcpy(&index,unit+0x410,4);
    memcpy(&points,unit+0x10c,4);memcpy(&count,model+0x1c,4);
    memcpy(&phase,model+0x14,4);memcpy(&desired,model+0x18,4);
    if (!id || id!=modelId || points<2 || points>33 || index>=points-1 || !count || count>65 ||
        phase>count || desired>=count) return false;
    unsigned translations=0,target=count;
    for (unsigned step=0;step<count;++step) {
        unsigned kind;memcpy(&kind,model+0x20+step*4,4);
        if (kind<1 || kind>4) return false;
        if (kind!=2 && kind!=4) continue;
        if (translations>=points-1) return false;
        float x,z,gx,gz;
        memcpy(&x,unit+0x128+translations*24,4);memcpy(&z,unit+0x130+translations*24,4);
        memcpy(&gx,model+0x534+step*12,4);memcpy(&gz,model+0x53c+step*12,4);
        if (!_finite(x) || !_finite(z) || x!=gx || z!=gz) return false;
        if (translations==index) target=step;
        ++translations;
    }
    if (translations!=points-1 || target==count) return false;
    // A delayed/duplicate acknowledgement cannot revoke progress already granted.
    if (target<desired) target=desired;
    memcpy(model+0x18,&target,4);unit[0x414]=0;
    if (phase<=target) memset(unit+0x6c,0,4);
    return true;
}
}
