// Restore the native editor's initial tab and unit labels. Author: 최병호.
#pragma once
static void __fastcall outfitEditorReady(void* editor, void*) {
    reinterpret_cast<void(__thiscall*)(void*)>(0x530e70)(editor);
    int mode=at<int>(editor,0x2c4);
    if ((mode!=2 && mode!=3) || !at<BYTE>(editor,0xc88)) return;
    if (at<int>(editor,0x2d4)==-1) {
        for (int tab=0;tab<4;++tab) if (at<BYTE>(editor,0x2dc+tab)) {
            // The original tab action sets selection/history and rebuilds its
            // layout and rows. It takes the already-loaded path, without queries.
            reinterpret_cast<void(__thiscall*)(void*,int)>(0x531b60)(editor,tab);
            return;
        }
    } else {
        reinterpret_cast<void(__thiscall*)(void*)>(0x531eb0)(editor);
    }
}
static bool installOutfitEditor() {
    const BYTE ready[]={0xe8,0xf1,0x2f,0,0};
    const unsigned labels[]={0x531c5b,0x531c70};
    const BYTE oldLabel[]={0x6a,0x22},unitLabel[]={0x6a,0x21};
    if (memcmp(reinterpret_cast<void*>(0x52de7a),ready,sizeof(ready))) return false;
    for (unsigned address:labels)
        if (memcmp(reinterpret_cast<void*>(address),oldLabel,sizeof(oldLabel))) return false;
    for (unsigned address:labels)
        if (!patchBytes(reinterpret_cast<void*>(address),oldLabel,unitLabel,sizeof(oldLabel))) return false;
    BYTE call[5]={0xe8};
    *reinterpret_cast<LONG*>(call+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&outfitEditorReady)-
        reinterpret_cast<BYTE*>(0x52de7a)-5);
    return patchBytes(reinterpret_cast<void*>(0x52de7a),ready,call,sizeof(call));
}
