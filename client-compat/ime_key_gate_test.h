// Event-order regression for ime_key_gate.h. No IME, window or client is involved. Author: Peppone Choi.
#pragma once
#include "ime_key_gate.h"

inline bool imeKeyGateTest() {
    using logh::ImeKeyAction;
    using logh::imeKeyFilter;
    const WORD enterScan=0x1c,escapeScan=0x01;
    auto bits=[](WORD scan,bool repeat=false) {
        return static_cast<LPARAM>(1u|(static_cast<DWORD>(scan)<<16)|(repeat?0x40000000u:0u));
    };
    auto down=[&](logh::ImeKeyGate& gate,WPARAM wParam,UINT key,WORD scan,DWORD time,bool composing,bool repeat=false) {
        return imeKeyFilter(gate,WM_KEYDOWN,wParam,bits(scan,repeat),time,key,composing);
    };
    auto up=[&](logh::ImeKeyGate& gate,WPARAM wParam,WORD scan,DWORD time) {
        return imeKeyFilter(gate,WM_KEYUP,wParam,bits(scan),time,0,false);
    };
    auto character=[&](logh::ImeKeyGate& gate,UINT message,WPARAM value,DWORD time) {
        return imeKeyFilter(gate,message,value,1,time,0,false);
    };
    const auto pass=ImeKeyAction::Pass,drop=ImeKeyAction::Drop;

    // Enter confirms: the IME passes the key on after committing, then a later Enter sends.
    logh::ImeKeyGate gate={};
    if(down(gate,VK_PROCESSKEY,VK_RETURN,enterScan,1000,true)!=pass)return false;
    if(gate.key!=VK_RETURN)return false;
    if(down(gate,VK_RETURN,VK_RETURN,enterScan,1001,false)!=drop)return false;
    if(character(gate,WM_CHAR,0x0d,1002)!=drop || character(gate,WM_IME_CHAR,0x0d,1002)!=drop)return false;
    if(down(gate,VK_RETURN,VK_RETURN,enterScan,1100,false,true)!=drop)return false; // Held key.
    if(up(gate,VK_RETURN,enterScan,1200)!=pass || gate.key)return false;
    if(down(gate,VK_RETURN,VK_RETURN,enterScan,1250,false)!=pass)return false;
    if(character(gate,WM_CHAR,0x0d,1251)!=pass || gate.key)return false;

    // The IME swallows the key completely, and its key-up is also translated.
    if(down(gate,VK_PROCESSKEY,VK_RETURN,enterScan,2000,true)!=pass)return false;
    if(up(gate,VK_PROCESSKEY,0x1e,2010)!=pass || gate.key!=VK_RETURN)return false; // Another key.
    if(up(gate,VK_PROCESSKEY,enterScan,2020)!=pass || gate.key)return false;
    if(down(gate,VK_RETURN,VK_RETURN,enterScan,2030,false)!=pass)return false;

    // Nothing was composing: this is a plain send, also when the IME is open.
    if(down(gate,VK_PROCESSKEY,VK_RETURN,enterScan,3000,false)!=pass || gate.key)return false;
    if(down(gate,VK_RETURN,VK_RETURN,enterScan,3001,false)!=pass || gate.key)return false;

    // A real Enter reaching a live composition completes it and is not sent.
    if(down(gate,VK_RETURN,VK_RETURN,enterScan,4000,true)!=ImeKeyAction::Complete)return false;
    if(up(gate,VK_RETURN,enterScan,4010)!=pass || gate.key)return false;
    if(down(gate,VK_RETURN,VK_RETURN,enterScan,4020,false)!=pass)return false;

    // Esc cancels the composition without acting on the draft; the next Esc is normal.
    if(down(gate,VK_PROCESSKEY,VK_ESCAPE,escapeScan,5000,true)!=pass)return false;
    if(down(gate,VK_ESCAPE,VK_ESCAPE,escapeScan,5001,false)!=drop)return false;
    if(character(gate,WM_CHAR,0x1b,5002)!=drop || character(gate,WM_CHAR,0x0d,5002)!=pass)return false;
    if(up(gate,VK_ESCAPE,escapeScan,5010)!=pass || gate.key)return false;
    if(down(gate,VK_ESCAPE,VK_ESCAPE,escapeScan,5020,false)!=pass)return false;
    if(down(gate,VK_ESCAPE,VK_ESCAPE,escapeScan,5030,true)!=ImeKeyAction::Cancel)return false;
    gate={};

    // Editing keys and committed text are untouched while a confirm is pending.
    if(down(gate,VK_PROCESSKEY,VK_RETURN,enterScan,6000,true)!=pass)return false;
    const UINT editingKeys[]={VK_LEFT,VK_RIGHT,VK_BACK,VK_DELETE,'A'};
    for(UINT key:editingKeys) {
        if(down(gate,key,key,0x20,6001,false)!=pass || gate.key!=VK_RETURN)return false;
        if(up(gate,key,0x20,6002)!=pass || gate.key!=VK_RETURN)return false;
    }
    if(character(gate,WM_CHAR,'A',6003)!=pass || character(gate,WM_IME_CHAR,0xd55c,6003)!=pass)return false;
    if(imeKeyFilter(gate,WM_IME_COMPOSITION,0,0x800,6004,0,false)!=pass)return false;
    gate={};

    // A lost key-up cannot swallow more than the one pass-on within its window.
    if(down(gate,VK_PROCESSKEY,VK_RETURN,enterScan,7000,true)!=pass)return false;
    if(down(gate,VK_RETURN,VK_RETURN,enterScan,7001,false)!=drop)return false;
    if(down(gate,VK_RETURN,VK_RETURN,enterScan,7002,false)!=pass || gate.key)return false;
    if(down(gate,VK_PROCESSKEY,VK_RETURN,enterScan,8000,true)!=pass)return false;
    if(down(gate,VK_RETURN,VK_RETURN,enterScan,8000+logh::imeKeyRepostWindow,false)!=pass || gate.key)return false;
    if(down(gate,VK_PROCESSKEY,VK_RETURN,enterScan,0xffffff00u,true)!=pass)return false; // Tick wrap.
    if(down(gate,VK_RETURN,VK_RETURN,enterScan,0x10,false)!=drop)return false;
    gate={};

    // A new composition's Enter re-arms even after a lost key-up.
    if(down(gate,VK_PROCESSKEY,VK_RETURN,enterScan,9000,true)!=pass)return false;
    if(down(gate,VK_PROCESSKEY,VK_RETURN,enterScan,9900,true)!=pass || gate.time!=9900 || gate.duplicate)return false;
    if(down(gate,VK_RETURN,VK_RETURN,enterScan,9901,false)!=drop)return false;
    gate={};

    // Only the bound editor under a Korean layout is filtered, and neither change keeps a key.
    using logh::imeKeyInScope;
    const HWND first=reinterpret_cast<HWND>(1),second=reinterpret_cast<HWND>(2);
    const LANGID korean=MAKELANGID(LANG_KOREAN,SUBLANG_KOREAN);
    const LANGID japanese=MAKELANGID(LANG_JAPANESE,SUBLANG_JAPANESE_JAPAN);
    HWND owner=nullptr;
    auto arm=[&]() {return down(gate,VK_PROCESSKEY,VK_RETURN,enterScan,10000,true)==pass && gate.key==VK_RETURN;};
    if(!imeKeyInScope(gate,owner,first,first,korean) || owner!=first || !arm())return false;
    if(imeKeyInScope(gate,owner,first,second,korean) || gate.key!=VK_RETURN)return false;
    if(imeKeyInScope(gate,owner,first,first,japanese) || gate.key || !arm())return false;
    if(!imeKeyInScope(gate,owner,second,second,korean) || gate.key || owner!=second || !arm())return false;
    if(imeKeyInScope(gate,owner,nullptr,second,korean) || gate.key || owner)return false;
    if(imeKeyInScope(gate,owner,nullptr,nullptr,korean))return false;

    // Cancel needs no fresh result and a composition that is really empty.
    using logh::imeCompositionCanceled;
    if(!imeCompositionCanceled(0,0) || !imeCompositionCanceled(GCS_COMPSTR,IMM_ERROR_NODATA))return false;
    if(imeCompositionCanceled(0,IMM_ERROR_GENERAL) || imeCompositionCanceled(0,2))return false;
    if(imeCompositionCanceled(GCS_RESULTSTR,0) || imeCompositionCanceled(GCS_RESULTSTR|GCS_COMPSTR,IMM_ERROR_NODATA))return false;
    return true;
}
