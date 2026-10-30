// Verified CD name-card literal copies and paired display names. Author: 최병호.
#pragma once
#include "namecard_text.h"
#include "korean_text.h"
struct NameCardLiteral {unsigned address,source;};
static constexpr NameCardLiteral nameCardLiterals[]={
    {0x5496ef,0x788ae0}, // outgoing confirmation, copied after the name
    {0x54a4be,0x788b70}, // incoming confirmation, copied after the name
    {0x54a5ca,0x788b58}, // server error
    {0x54a5d1,0x788b30}, // exchange rejected/failed
    {0x54a5dc,0x788b08}, // exchange accepted
    {0x549d63,0x7881d4}, // unknown character name
    {0x54acd7,0x7881d4}
};
struct NameCardNameCall {unsigned address,delta;};
static constexpr NameCardNameCall nameCardNameCalls[]={
    {0x54966a,0x200}, // native CString at stack+14, scratch at stack+214
    {0x54a47d,4}      // native CString at stack+10, scratch at stack+14
};
static thread_local std::wstring nameCardPendingName;
static thread_local const char* nameCardPendingBuffer=nullptr;
static void* __cdecl captureNameCardName(void* output,const wchar_t* name,unsigned delta) {
    nameCardPendingName.clear();nameCardPendingBuffer=nullptr;
    // Preserve the native temporary CString, allocator and destructor. Its
    // lossy scratch copy is ignored only by the paired dialog setter below.
    const auto result=reinterpret_cast<void*(__cdecl*)(void*,const wchar_t*)>(0x4eacf0)(output,name);
    try {
        std::string checked;
        if (logh::storeDisplayName(name,checked)) {
            nameCardPendingName=name;
            nameCardPendingBuffer=static_cast<const char*>(output)+delta;
        }
    } catch (...) {nameCardPendingName.clear();nameCardPendingBuffer=nullptr;}
    return result;
}
static void* __cdecl outgoingNameCardName(void* output,const wchar_t* name) {return captureNameCardName(output,name,0x200);}
static void* __cdecl incomingNameCardName(void* output,const wchar_t* name) {return captureNameCardName(output,name,4);}
static void showNameCardQuestion(void* context,const char* text,unsigned suffix) {
    std::wstring name;name.swap(nameCardPendingName);
    const bool paired=nameCardPendingBuffer && text==nameCardPendingBuffer;
    nameCardPendingBuffer=nullptr;
    std::string composed;
    try {
        const auto literal=reinterpret_cast<const char*>(suffix);
        const auto translated=logh::koreanStaticResource(literal);
        if (paired && translated!=literal && logh::nameCardDisplayText(name.c_str(),translated,composed)) text=composed.c_str();
    } catch (...) { /* Keep the original dialog if display allocation fails. */ }
    reinterpret_cast<void(__thiscall*)(void*,const char*)>(0x5706e0)(context,text);
}
static void __fastcall outgoingNameCardQuestion(void* context,void*,const char* text) {showNameCardQuestion(context,text,0x788ae0);}
static void __fastcall incomingNameCardQuestion(void* context,void*,const char* text) {showNameCardQuestion(context,text,0x788b70);}
static bool installNameCardLabels() {
    struct Call {unsigned address,target;const void* replacement;};
    const Call calls[]={
        {0x54966a,0x4eacf0,reinterpret_cast<const void*>(&outgoingNameCardName)},
        {0x54a47d,0x4eacf0,reinterpret_cast<const void*>(&incomingNameCardName)},
        {0x549729,0x5706e0,reinterpret_cast<const void*>(&outgoingNameCardQuestion)},
        {0x54a4f2,0x5706e0,reinterpret_cast<const void*>(&incomingNameCardQuestion)}
    };
    for (const auto& row:nameCardLiterals) {
        BYTE expected[5]={0xbf};memcpy(expected+1,&row.source,4);
        const auto source=reinterpret_cast<const char*>(row.source);
        const auto translated=logh::koreanStaticResource(source);
        // Incoming scratch is 128 bytes. Keep its original non-escaped name
        // copy <=54 bytes and this translated suffix <=64, including room for NUL.
        if (memcmp(reinterpret_cast<void*>(row.address),expected,5) || translated==source || strlen(translated)>64) return false;
    }
    for (const auto& row:calls) {
        BYTE expected[5]={0xe8};*reinterpret_cast<LONG*>(expected+1)=static_cast<LONG>(row.target-row.address-5);
        if (memcmp(reinterpret_cast<void*>(row.address),expected,5)) return false;
    }
    for (const auto& row:nameCardLiterals) {
        BYTE expected[5]={0xbf},replacement[5]={0xbf};memcpy(expected+1,&row.source,4);
        const auto translated=logh::koreanStaticResource(reinterpret_cast<const char*>(row.source));
        memcpy(replacement+1,&translated,4);
        if (!patchBytes(reinterpret_cast<void*>(row.address),expected,replacement,5)) return false;
    }
    for (const auto& row:calls) {
        BYTE expected[5]={0xe8},replacement[5]={0xe8};
        *reinterpret_cast<LONG*>(expected+1)=static_cast<LONG>(row.target-row.address-5);
        *reinterpret_cast<LONG*>(replacement+1)=static_cast<LONG>(static_cast<const BYTE*>(row.replacement)-reinterpret_cast<BYTE*>(row.address)-5);
        if (!patchBytes(reinterpret_cast<void*>(row.address),expected,replacement,5)) return false;
    }
    return true;
}
