// The CD lobby disables both creation entry points. Author: Peppone Choi (peppone-choi).
#pragma once
#include <cstddef>
#include <cstring>
namespace logh {
constexpr unsigned char creationMenuDisabled[]={0xc6,0x44,0x24,0x15,0,0xc6,0x44,0x24,0x16,0};
constexpr unsigned char creationMenuEnabled[]={0xc6,0x44,0x24,0x15,1,0xc6,0x44,0x24,0x16,1};
enum class CreationMenuState { Unsupported,Disabled,Enabled };
inline CreationMenuState creationMenuState(const unsigned char* code,std::size_t size) {
    if(!code || size!=sizeof(creationMenuDisabled))return CreationMenuState::Unsupported;
    if(!std::memcmp(code,creationMenuDisabled,size))return CreationMenuState::Disabled;
    if(!std::memcmp(code,creationMenuEnabled,size))return CreationMenuState::Enabled;
    return CreationMenuState::Unsupported;
}
}
