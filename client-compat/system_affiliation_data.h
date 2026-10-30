// Current base ownership, independent of fleets and entry capacity. Author: 최병호.
#pragma once
namespace logh {
struct SystemAffiliation {
    struct Owner { unsigned id=0, grid=5000, power=0, camp=0; } owners[350];
    unsigned count=0;
    bool accept(unsigned id,unsigned grid,unsigned power,unsigned camp) {
        if (!id || grid>=5000 || power>4 || camp>1) return false;
        for (unsigned i=0;i<count;++i) if (owners[i].id==id) {
            owners[i]={id,grid,power,camp}; return true;
        }
        if (count==350) return false;
        owners[count++]={id,grid,power,camp}; return true;
    }
    // Missing knowledge is distinct from neutral and mixed sovereignty.
    unsigned affiliation(const unsigned* ids,unsigned n,unsigned grid) const {
        if (!n || n>350 || grid>=5000) return 10;
        unsigned affiliation=10;
        bool mixed=false;
        for (unsigned i=0;i<n;++i) {
            const Owner* owner=nullptr;
            for (unsigned j=0;j<count;++j) if (owners[j].id==ids[i] && owners[j].grid==grid) {owner=&owners[j];break;}
            if (!owner) return 10;
            unsigned value=owner->power*2+owner->camp;
            if (i && value!=affiliation) mixed=true;
            affiliation=value;
        }
        return mixed ? 11 : affiliation;
    }
};
inline unsigned systemAffiliationRgb(unsigned affiliation) {
    if (affiliation==10) return 0xffffff; // unknown: retain original white
    if (affiliation==11) return 0xd090ff; // mixed: purple
    if (affiliation==5) return 0x40c0ff; // Empire rebels: sky blue
    if (affiliation==7) return 0xffff40; // Alliance rebels: yellow
    if (affiliation==4) return 0x4080ff; // Empire: blue
    if (affiliation==6) return 0x00dc80; // Alliance: green
    if (!affiliation) return 0xb0b0b0; // neutral
    return 0xc8c8c8; // another power, without inventing its identity
}
}
