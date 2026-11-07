// SSLoginNG rejects entry; the CD's empty SSGameLoginOK has no status field. Author: Peppone Choi (peppone-choi).
#pragma once
namespace logh {
enum class GameAdmissionRejection : long { None, Generic, AlreadyOnline, Maintenance };
inline GameAdmissionRejection gameAdmissionRejection(unsigned opcode,bool initializing,unsigned char reason) {
    if(!initializing || (opcode&65535)!=0x0202)return GameAdmissionRejection::None;
    if(reason==2)return GameAdmissionRejection::AlreadyOnline;
    if(reason==3)return GameAdmissionRejection::Maintenance;
    return GameAdmissionRejection::Generic;
}
}
