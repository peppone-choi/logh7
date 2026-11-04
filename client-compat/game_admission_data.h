// SSLoginNG rejects entry; the CD's empty SSGameLoginOK has no status field. Author: 최병호.
#pragma once
namespace logh {
inline bool gameAdmissionRejected(unsigned opcode,bool initializing) {
    return initializing && (opcode&65535)==0x0202;
}
}
