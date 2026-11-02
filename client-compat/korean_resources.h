// Native lookup -> native ANSI formatting -> native UTF-16 pool.
// Author: Peppone Choi. Original EXE hash and complete instructions are checked first.
#pragma once
#include "korean_text.h"
#include "korean_windows.h"
#include "display_text.h"

static int (WINAPI* originalKoreanLoadString)(HINSTANCE,UINT,LPSTR,int)=nullptr;
static int WINAPI loadKoreanWindowsString(HINSTANCE instance,UINT id,LPSTR buffer,int capacity) {
    if (instance==GetModuleHandleW(nullptr)) {
        const int copied=logh::copyKoreanWindowsString(id,buffer,capacity,GetACP());
        if (copied>=0) return copied;
    }
    return originalKoreanLoadString(instance,id,buffer,capacity);
}

static BYTE koreanLookupTrampoline[11];
static BYTE koreanDecodeTrampoline[8+5];
static BYTE koreanLoadTrampoline[10+5];
static BYTE koreanFormatTrampoline[8+5];
static BYTE koreanConstructTrampoline[5+5];
static BYTE koreanAssignTrampoline[6+5];
static BYTE koreanAppendTrampoline[6+5];
static BYTE koreanWideConstructTrampoline[5+5];
static BYTE koreanWideAssignTrampoline[5+5];
static BYTE koreanWidePoolTrampoline[5+5];
static BYTE koreanWideAppendTrampoline[6+5];
static BYTE koreanWideFormatTrampoline[5+5];
static const void* koreanWideFormatResume=koreanWideFormatTrampoline;
// A static UI literal can reach the Japanese decoder only after CString has
// copied it to the heap. Translate before the address identity is lost. These
// three overloads accept NUL-terminated char*, not wide strings, byte counts,
// CString objects or varargs. Resource IDs/NULL and heap/player text pass
// through koreanStaticResource unchanged. The native allocator owns the copy.
static void* __fastcall constructKoreanCString(void* string,void*,const char* text) {
    return reinterpret_cast<void*(__thiscall*)(void*,const char*)>(koreanConstructTrampoline+0)
        (string,logh::koreanStaticResource(text));
}
static void* __fastcall assignKoreanCString(void* string,void*,const char* text) {
    return reinterpret_cast<void*(__thiscall*)(void*,const char*)>(koreanAssignTrampoline+0)
        (string,logh::koreanStaticResource(text));
}
static void* __fastcall appendKoreanCString(void* string,void*,const char* text) {
    return reinterpret_cast<void*(__thiscall*)(void*,const char*)>(koreanAppendTrampoline+0)
        (string,logh::koreanStaticResource(text));
}
static void* __fastcall constructKoreanWideCString(void* string,void*,const wchar_t* text) {
    const auto row=logh::koreanWideResource(text);
    // Preserve our resource-only CP932 representation through ANSI CString.
    // The original wide overload narrows through ACP and would lose Hangul.
    if (row) return reinterpret_cast<void*(__thiscall*)(void*,const char*)>(koreanConstructTrampoline+0)
        (string,row->narrow);
    return reinterpret_cast<void*(__thiscall*)(void*,const wchar_t*)>(koreanWideConstructTrampoline+0)(string,text);
}
static void* __fastcall assignKoreanWideCString(void* string,void*,const wchar_t* text) {
    const auto row=logh::koreanWideResource(text);
    if (row) return reinterpret_cast<void*(__thiscall*)(void*,const char*)>(koreanAssignTrampoline+0)
        (string,row->narrow);
    return reinterpret_cast<void*(__thiscall*)(void*,const wchar_t*)>(koreanWideAssignTrampoline+0)(string,text);
}
static void __cdecl setKoreanWidePool(void* pool,const wchar_t* text,int count) {
    const auto row=logh::koreanWideResource(text);
    // Translate only a complete literal, keeping substring requests intact.
    if (row && (count==-1 || (count>=0 && static_cast<unsigned>(count)>=row->units))) {text=row->text;count=-1;}
    reinterpret_cast<void(__cdecl*)(void*,const wchar_t*,int)>(koreanWidePoolTrampoline+0)(pool,text,count);
}
static void __cdecl appendKoreanWidePool(void* pool,const wchar_t* text) {
    reinterpret_cast<void(__cdecl*)(void*,const wchar_t*)>(koreanWideAppendTrampoline+0)(pool,logh::koreanWideText(text));
}
static const wchar_t* __cdecl translateKoreanWideFormat(const wchar_t* text) {
    return logh::koreanWideText(text);
}
static void __declspec(naked) formatKoreanWideText() {
    // 4ebe20 is cdecl (pool*, wchar_t* format, ...). Keep every vararg and
    // the native bounded 2048-unit formatting buffer and its error handling.
    __asm {
        pushfd
        pushad
        mov eax, dword ptr [esp+44]
        push eax
        call translateKoreanWideFormat
        add esp, 4
        mov dword ptr [esp+44], eax
        popad
        popfd
        jmp dword ptr [koreanWideFormatResume]
    }
}
static const void* koreanFormatResume=koreanFormatTrampoline;
static const char* __cdecl translateStaticKoreanText(const char* text,const char** arguments) {
    const unsigned count=logh::koreanFormatStringArguments(reinterpret_cast<uintptr_t>(text),text);
    for (unsigned i=0;i<count;++i) arguments[i]=logh::koreanStaticResource(arguments[i]);
    return logh::koreanStaticResource(text);
}
static void __declspec(naked) formatKoreanStaticText() {
    // 646616 is cdecl (CString*, format, ...). Change only the format pointer,
    // The five verified all-string formats may also localize exact static
    // argument pointers; heap/player strings and other varargs stay unchanged.
    __asm {
        pushfd
        pushad
        mov eax, dword ptr [esp+44]
        lea edx, dword ptr [esp+48]
        push edx
        push eax
        call translateStaticKoreanText
        add esp, 8
        mov dword ptr [esp+44], eax
        popad
        popfd
        jmp dword ptr [koreanFormatResume]
    }
}
static void translateMessagePointers(void* table, unsigned kind, unsigned variant) {
    if (table != reinterpret_cast<void*>(0x2217400)) return;
    for (const auto& metadata : logh::koreanMessageTables) {
        if (!metadata.id || metadata.kind!=kind || metadata.variant!=variant) continue;
        // 522310 stores one native HFWR table per kind, with separate index
        // and string-pointer arrays for the nine speech variants. The original
        // backing allocation stays owned by the client and is never rewritten.
        const auto block=reinterpret_cast<const BYTE*>(table)+0x298c+kind*152;
        if (*reinterpret_cast<const unsigned*>(block)!=metadata.groups ||
            *reinterpret_cast<const unsigned*>(block+4)!=metadata.strings) return;
        const auto indices=*reinterpret_cast<const unsigned*const*>(block+8+variant*4);
        auto pointers=*reinterpret_cast<const char***>(const_cast<BYTE*>(block)+0x2c+variant*4);
        if (!indices || !pointers || indices[0] || indices[metadata.groups-1]!=metadata.strings) return;
        for (unsigned g=0;g+1<metadata.groups;++g) {
            if (indices[g]>indices[g+1] || indices[g+1]>metadata.strings) return;
        }
        for (unsigned g=0;g+1<metadata.groups;++g) {
            for (unsigned i=0;i<indices[g+1]-indices[g];++i) {
                auto& original=pointers[indices[g]+i];
                original=logh::koreanResource(metadata.id,g,i,original);
            }
        }
        return;
    }
}
static int __fastcall loadKoreanMessages(void* table, void*, const char* filename, unsigned variant, unsigned kind) {
    const auto result=reinterpret_cast<int(__thiscall*)(void*,const char*,unsigned,unsigned)>(koreanLoadTrampoline+0)
        (table,filename,variant,kind);
    if (result>0) translateMessagePointers(table,kind,variant);
    return result;
}
static const char* __fastcall lookupKoreanResource(void* table, void*, unsigned group, unsigned index) {
    const auto original = reinterpret_cast<const char*(__thiscall*)(void*,unsigned,unsigned)>(koreanLookupTrampoline+0)
        (table,group,index);
    // Verified CD constmsg instance; other message tables keep their own
    // indices. Identical Japanese words may mean a name or a count unit.
    return table == reinterpret_cast<void*>(0x2217400) ? logh::koreanResource(group,index,original) : original;
}
static wchar_t* __cdecl decodeKoreanResource(const char* text) {
    // Keep the original allocator/lifetime, error handling, and Japanese CRT
    // locale. Only our private replacement glyphs are remapped after decoding.
    auto wide = reinterpret_cast<wchar_t*(__cdecl*)(const char*)>(koreanDecodeTrampoline+0)(logh::koreanStaticResource(text));
    logh::restoreKoreanGlyphs(wide);
    logh::restoreDisplayNames(wide);
    return wide;
}
static bool installKoreanResources() {
    // Verified CD LoadStringA IAT slot. Other modules retain their resources.
    auto stringImport=reinterpret_cast<void**>(0x66b4fc);
    const auto expectedLoadString=GetProcAddress(GetModuleHandleW(L"user32.dll"),"LoadStringA");
    if (!expectedLoadString || *stringImport!=reinterpret_cast<void*>(expectedLoadString)) return false;
    originalKoreanLoadString=reinterpret_cast<decltype(originalKoreanLoadString)>(expectedLoadString);
    const BYTE lookup[] = {0x8b,0x81,0x74,0x29,0,0}; // mov eax,[ecx+2974]
    const BYTE decode[] = {0x53,0x56,0x57,0x68,0xfc,0xe3,0x76,0}; // pushes, Japanese address
    const BYTE load[] = {0x55,0x8b,0xec,0x6a,0xff,0x68,0xf8,0x48,0x66,0}; // complete prologue instructions
    const BYTE format[] = {0x8b,0x4c,0x24,0x04,0x8d,0x44,0x24,0x0c}; // mov ecx,[esp+4]; lea eax,[esp+c]
    const BYTE construct[] = {0xa1,0x08,0x36,0x7b,0}; // mov eax,[7b3608], empty CString
    const BYTE assign[] = {0x56,0x57,0x8b,0x7c,0x24,0x0c}; // push esi/edi; mov edi,[esp+c]
    const BYTE append[] = {0x56,0x57,0x8b,0x7c,0x24,0x0c};
    const BYTE wideConstruct[] = {0xa1,0x08,0x36,0x7b,0};
    const BYTE wideAssign[] = {0x53,0x8b,0x5c,0x24,0x08};
    const BYTE widePool[] = {0x8b,0x44,0x24,0x08,0x53};
    const BYTE wideAppend[] = {0x51,0x53,0x8b,0x5c,0x24,0x10};
    const BYTE wideFormat[] = {0xb8,0x00,0x10,0x00,0x00};
    if (memcmp(reinterpret_cast<void*>(0x522010),lookup,sizeof(lookup)) ||
        memcmp(reinterpret_cast<void*>(0x4eac60),decode,sizeof(decode)) ||
        memcmp(reinterpret_cast<void*>(0x522310),load,sizeof(load)) ||
        memcmp(reinterpret_cast<void*>(0x646616),format,sizeof(format)) ||
        memcmp(reinterpret_cast<void*>(0x64c144),construct,sizeof(construct)) ||
        memcmp(reinterpret_cast<void*>(0x64c25f),assign,sizeof(assign)) ||
        memcmp(reinterpret_cast<void*>(0x64c43e),append,sizeof(append)) ||
        memcmp(reinterpret_cast<void*>(0x64c196),wideConstruct,sizeof(wideConstruct)) ||
        memcmp(reinterpret_cast<void*>(0x64c286),wideAssign,sizeof(wideAssign)) ||
        memcmp(reinterpret_cast<void*>(0x4ea8b0),widePool,sizeof(widePool)) ||
        memcmp(reinterpret_cast<void*>(0x4eabb0),wideAppend,sizeof(wideAppend)) ||
        memcmp(reinterpret_cast<void*>(0x4ebe20),wideFormat,sizeof(wideFormat))) return false;
    struct Entry { unsigned address; const BYTE* expected; size_t bytes; BYTE* trampoline; const void* replacement; };
    const Entry entries[] = {
        {0x522010,lookup,sizeof(lookup),koreanLookupTrampoline,reinterpret_cast<const void*>(&lookupKoreanResource)},
        {0x4eac60,decode,sizeof(decode),koreanDecodeTrampoline,reinterpret_cast<const void*>(&decodeKoreanResource)},
        {0x522310,load,sizeof(load),koreanLoadTrampoline,reinterpret_cast<const void*>(&loadKoreanMessages)},
        {0x646616,format,sizeof(format),koreanFormatTrampoline,reinterpret_cast<const void*>(&formatKoreanStaticText)},
        {0x64c144,construct,sizeof(construct),koreanConstructTrampoline,reinterpret_cast<const void*>(&constructKoreanCString)},
        {0x64c25f,assign,sizeof(assign),koreanAssignTrampoline,reinterpret_cast<const void*>(&assignKoreanCString)},
        {0x64c43e,append,sizeof(append),koreanAppendTrampoline,reinterpret_cast<const void*>(&appendKoreanCString)},
        {0x64c196,wideConstruct,sizeof(wideConstruct),koreanWideConstructTrampoline,reinterpret_cast<const void*>(&constructKoreanWideCString)},
        {0x64c286,wideAssign,sizeof(wideAssign),koreanWideAssignTrampoline,reinterpret_cast<const void*>(&assignKoreanWideCString)},
        {0x4ea8b0,widePool,sizeof(widePool),koreanWidePoolTrampoline,reinterpret_cast<const void*>(&setKoreanWidePool)},
        {0x4eabb0,wideAppend,sizeof(wideAppend),koreanWideAppendTrampoline,reinterpret_cast<const void*>(&appendKoreanWidePool)},
        {0x4ebe20,wideFormat,sizeof(wideFormat),koreanWideFormatTrampoline,reinterpret_cast<const void*>(&formatKoreanWideText)}
    };
    // Trampolines contain only whole, non-relative instructions followed by a
    // relative jump. Make their storage executable before redirecting callers.
    for (const auto& row : entries) {
        DWORD previous = 0;
        if (!VirtualProtect(row.trampoline,row.bytes+5,PAGE_EXECUTE_READWRITE,&previous)) return false;
        memcpy(row.trampoline,row.expected,row.bytes);
        row.trampoline[row.bytes] = 0xe9;
        const LONG back = static_cast<LONG>(reinterpret_cast<BYTE*>(row.address+row.bytes)-row.trampoline-row.bytes-5);
        memcpy(row.trampoline+row.bytes+1,&back,4);
        FlushInstructionCache(GetCurrentProcess(),row.trampoline,row.bytes+5);
    }
    for (const auto& row : entries) {
        BYTE jump[10]; memset(jump,0x90,sizeof(jump)); jump[0]=0xe9;
        const LONG offset = static_cast<LONG>(reinterpret_cast<const BYTE*>(row.replacement)-reinterpret_cast<BYTE*>(row.address)-5);
        memcpy(jump+1,&offset,4);
        if (!patchBytes(reinterpret_cast<void*>(row.address),row.expected,jump,row.bytes)) return false;
    }
    // DirectInput initialization can follow resource initialization. Cover
    // already-loaded tables as well as subsequent native reloads.
    for (const auto& row : logh::koreanMessageTables) {
        if (row.id) translateMessagePointers(reinterpret_cast<void*>(0x2217400),row.kind,row.variant);
    }
    const auto replacementLoadString=reinterpret_cast<void*>(&loadKoreanWindowsString);
    if (!patchBytes(stringImport,&expectedLoadString,&replacementLoadString,sizeof(void*))) return false;
    return true;
}
