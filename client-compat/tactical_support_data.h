// Author: 최병호. The wire damage counter includes destroyed boats.
#pragma once
namespace logh {
inline bool repairedNormal(unsigned maximum,unsigned alive,unsigned unavailable,unsigned& normal) {
    if (!maximum || maximum>300 || alive>maximum || unavailable>maximum || maximum-unavailable>alive) return false;
    normal=maximum-unavailable;return true;
}
}
