// Author: Peppone Choi. The wire damage counter includes destroyed boats.
#pragma once
namespace logh {
inline bool repairedNormal(unsigned maximum,unsigned alive,unsigned unavailable,unsigned& normal) {
    if (!maximum || maximum>300 || alive>maximum || unavailable>maximum || maximum-unavailable>alive) return false;
    normal=maximum-unavailable;return true;
}
inline bool definitionShipCounts(unsigned maximum,unsigned unavailable,unsigned destroyed,unsigned& normal,unsigned& alive) {
    if(!maximum || maximum>300 || unavailable>maximum || destroyed>unavailable)return false;
    normal=maximum-unavailable;alive=maximum-destroyed;return true;
}
}
