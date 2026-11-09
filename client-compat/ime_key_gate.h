// Keep an Enter/Esc consumed by an active IME away from the native editor. Author: Peppone Choi.
#pragma once
#include <windows.h>
#include <imm.h>

namespace logh {
struct ImeKeyGate {UINT key;UINT scan;DWORD time;bool duplicate;};
enum class ImeKeyAction {Pass,Drop,Complete,Cancel};
// Bounds only a lost key-up; a normal second press is released by its key-up first.
constexpr DWORD imeKeyRepostWindow=500;

// The filter acts only on the bound editor under a Korean layout. A pending key never
// outlives the editor or the layout it was armed for; other windows just leave it pending.
inline bool imeKeyInScope(ImeKeyGate& gate,HWND& owner,HWND bound,HWND target,LANGID layout) {
    if(owner!=bound) {
        gate={};
        owner=bound;
    }
    if(!bound || PRIMARYLANGID(layout)!=LANG_KOREAN) {
        gate={};
        return false;
    }
    return target==bound;
}

// IMM keeps an earlier result readable, so only the last composition event's flags prove a
// fresh one. IMM_ERROR_GENERAL is a failed query, not an empty composition.
inline bool imeCompositionCanceled(DWORD lastFlags,LONG composition) {
    return !(lastFlags&GCS_RESULTSTR) && (composition==0 || composition==IMM_ERROR_NODATA);
}

// `key` is the resolved virtual key of a WM_KEYDOWN (the IME's original key for
// VK_PROCESSKEY). `composing` is whether a composition string exists at retrieval.
inline ImeKeyAction imeKeyFilter(ImeKeyGate& gate,UINT message,WPARAM wParam,LPARAM lParam,
    DWORD time,UINT key,bool composing) {
    const DWORD bits=static_cast<DWORD>(lParam);
    const UINT scan=(bits>>16)&0xff;
    if(message==WM_KEYUP) {
        if(gate.key && (wParam==gate.key || (wParam==VK_PROCESSKEY && scan==gate.scan)))gate={};
        return ImeKeyAction::Pass;
    }
    if(message==WM_CHAR || message==WM_IME_CHAR) {
        const WPARAM echo=gate.key==VK_RETURN?0x0d:gate.key==VK_ESCAPE?0x1b:0;
        return echo && wParam==echo?ImeKeyAction::Drop:ImeKeyAction::Pass;
    }
    if(message!=WM_KEYDOWN || (key!=VK_RETURN && key!=VK_ESCAPE))return ImeKeyAction::Pass;
    const bool real=wParam!=VK_PROCESSKEY;
    if(real && gate.key==key) {
        if(bits&0x40000000)return ImeKeyAction::Drop; // Auto-repeat of the consumed press.
        // The IME may pass the same press on once after committing. Time wraps safely.
        if(!gate.duplicate && time-gate.time<imeKeyRepostWindow) {
            gate.duplicate=true;
            return ImeKeyAction::Drop;
        }
        gate={}; // A new press whose predecessor's key-up never reached this thread.
    }
    if(!composing)return ImeKeyAction::Pass;
    gate={key,scan,time,real};
    // VK_PROCESSKEY must still reach TranslateMessage, which runs the IME. A real key
    // bypassed the IME, so it has to be told to finish the composition itself.
    if(!real)return ImeKeyAction::Pass;
    return key==VK_RETURN?ImeKeyAction::Complete:ImeKeyAction::Cancel;
}
}
