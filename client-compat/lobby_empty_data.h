// Native lobby cache: at most two 0x36c-byte charged-session records. Author: Peppone Choi.
#pragma once
#include <cstddef>
namespace logh {
inline bool emptyPlayableLobby(const unsigned char* p,std::size_t length) {
    if(!p || !length || !p[0] || p[0]>2 || length<p[0]*0x36cu)return false;
    for(unsigned i=0;i<p[0];++i) {
        const auto q=p+i*0x36c;
        if(!(q[4] || q[5]) || (q[6]!=1 && q[6]!=2) || q[0x2a0] || q[0x2a1])return false;
    }
    return true;
}
}
