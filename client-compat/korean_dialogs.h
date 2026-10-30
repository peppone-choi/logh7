// Keep native MessageBox ownership/buttons/return values. Author: Peppone Choi.
#pragma once
#include "dialog_text.h"
#include <intrin.h>
#pragma intrinsic(_ReturnAddress)

static int (WINAPI* originalKoreanMessageBox)(HWND,LPCSTR,LPCSTR,UINT)=nullptr;
static int WINAPI showKoreanMessageBox(HWND owner,LPCSTR text,LPCSTR caption,UINT flags) {
    if (!logh::dialogMessageBoxCaller(reinterpret_cast<uintptr_t>(_ReturnAddress())))
        return originalKoreanMessageBox(owner,text,caption,flags);
    try {
        std::wstring body,title;
        bool translatedBody=logh::translatedDialogText(text,false,body);
        bool translatedTitle=logh::translatedDialogText(caption,true,title);
        if ((!translatedBody && !translatedTitle) ||
            (!translatedBody && !logh::ansiDialogText(text,body)) ||
            (!translatedTitle && !logh::ansiDialogText(caption,title)))
            return originalKoreanMessageBox(owner,text,caption,flags);
        // An untranslated partner retains Windows ACP semantics. In particular,
        // do not decode an already-Korean LoadStringA result as Japanese.
        // Preserve NULL caption (Windows default title), rather than L"".
        return MessageBoxW(owner,text ? body.c_str() : nullptr,caption ? title.c_str() : nullptr,flags);
    } catch (...) {
        // Error reporting must still work when allocating a translation fails.
        return originalKoreanMessageBox(owner,text,caption,flags);
    }
}
static bool installKoreanDialogs() {
    auto slot=reinterpret_cast<void**>(0x66b5dc);
    const auto expected=GetProcAddress(GetModuleHandleW(L"user32.dll"),"MessageBoxA");
    if (!expected || *slot!=reinterpret_cast<void*>(expected)) return false;
    const BYTE call[]={0xff,0x15,0xdc,0xb5,0x66,0x00};
    for (unsigned address:logh::dialogMessageBoxCalls)
        if (memcmp(reinterpret_cast<void*>(address),call,sizeof(call))) return false;
    originalKoreanMessageBox=reinterpret_cast<decltype(originalKoreanMessageBox)>(expected);
    const auto replacement=reinterpret_cast<void*>(&showKoreanMessageBox);
    return patchBytes(slot,&expected,&replacement,sizeof(void*));
}
