// Narrow shader-source compatibility; the original assembler owns its buffers.
#pragma once
#include "environment_shader_data.h"
using EnvironmentAssembler=HRESULT(WINAPI*)(const char*,unsigned,DWORD,void**,void**,void**);
static HRESULT WINAPI assembleEnvironmentShader(const char* source,unsigned size,DWORD flags,
    void** constants,void** shader,void** errors) {
    const auto original=reinterpret_cast<EnvironmentAssembler>(0x5a8dc7);
    if(source==reinterpret_cast<const char*>(0x7ac798) || source==reinterpret_cast<const char*>(0x7ad798)) {
        char expanded[4160];
        unsigned length=logh::expandEnvironmentMatrix(source,size,expanded,sizeof(expanded));
        if(length)return original(expanded,length,flags,constants,shader,errors);
    }
    return original(source,size,flags,constants,shader,errors);
}
static bool installEnvironmentShader() {
    const BYTE expected[]={0xe8,0x69,0x0b,0xfc,0xff};BYTE call[5]={0xe8};
    auto site=reinterpret_cast<BYTE*>(0x5e8259);
    *reinterpret_cast<LONG*>(call+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&assembleEnvironmentShader)-site-5);
    return patchBytes(site,expected,call,5);
}
