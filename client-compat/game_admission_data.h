// SSLoginNG rejects entry; the CD's empty SSGameLoginOK has no status field. Author: Peppone Choi (peppone-choi).
#pragma once
namespace logh {
enum class GameAdmissionRejection : long { None, Generic, AlreadyOnline };
inline GameAdmissionRejection gameAdmissionRejection(unsigned opcode,bool initializing,unsigned char reason) {
    if(!initializing || (opcode&65535)!=0x0202)return GameAdmissionRejection::None;
    return reason==2 ? GameAdmissionRejection::AlreadyOnline : GameAdmissionRejection::Generic;
}
}
