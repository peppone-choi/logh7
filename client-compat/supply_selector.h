// Supply is the first selection, so TARGET_SELECT_S_BASE has no preceding value.
// Keep the native filter vector and resolve its grid from the current unit. Author: Peppone Choi.
#pragma once
static void __fastcall supplySelectFilter(void* step, void*, unsigned kind, const char* variable) {
    auto command=*reinterpret_cast<BYTE**>(0xc9e2f8);
    unsigned authority=*reinterpret_cast<unsigned*>(0xc9eac0);
    if (step && command && at<unsigned>(command,0x28)==62 &&
        (authority==44 || authority==180) && at<unsigned>(step,0x28)==7 &&
        kind==3 && variable && !strcmp(variable,"TARGET_SELECT_S_BASE")) {
        auto world=*reinterpret_cast<BYTE**>(0x7ccffc);
        auto own=world ? at<BYTE*>(world,8) : nullptr;
        if (own && own[0] && at<void*>(command,0x1c)==own) variable=nullptr;
    }
    reinterpret_cast<void(__thiscall*)(void*,unsigned,const char*)>(0x5713b0)(step,kind,variable);
}
static bool installSupplySelector() {
    DWORD expected=0x5713b0;
    DWORD replacement=reinterpret_cast<DWORD>(&supplySelectFilter);
    return patchBytes(reinterpret_cast<void*>(0x67576c),&expected,&replacement,sizeof(expected));
}
