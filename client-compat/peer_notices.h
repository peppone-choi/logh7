// Rebuild the missing CD presence notices without changing world membership.
// Author: 최병호.
#pragma once

static const char peerNoticeMarker[] = "LOGH7_PEER_NOTICE";
static thread_local std::wstring pendingPeerNotice;

static void preparePeerNotice(void* character, bool entering) {
    std::wstring name;
    if (character) {
        // 004c7290 returns character+0xa4 only while the active flag is set.
        // Read that embedded name directly so removal notices retain the name.
        auto text = reinterpret_cast<const wchar_t*>(static_cast<BYTE*>(character) + 0xde);
        for (unsigned i = 0; i < 28 && text[i]; ++i) name += text[i];
    }
    pendingPeerNotice = name.empty() ? L"인물이" : name + L" 님이";
    pendingPeerNotice += entering ? L" 주변에 나타났습니다.\n" : L" 시야에서 벗어났습니다.\n";
}

static const char* __cdecl peerEntered(void* information) {
    auto world = *reinterpret_cast<BYTE**>(0x7ccffc);
    void* character = nullptr;
    if (world && information) for (unsigned i = 0; i < 600; ++i) {
        auto candidate = world + 0xc + i * 0x370;
        if (at<BYTE>(candidate, 0) && at<unsigned>(candidate, 0x24) == at<unsigned>(information, 0)) {
            character = candidate;
            break;
        }
    }
    preparePeerNotice(character, true);
    return peerNoticeMarker;
}
static const char* __cdecl peerLeft(void* character) {
    // The native remover has cleared only the active flag; its ID is still valid.
    preparePeerNotice(character, false);
    return peerNoticeMarker;
}
static void __declspec(naked) lookupPeerEntry() {
    __asm {
        push ebp
        call peerEntered
        add esp, 4
        ret 8
    }
}
static void __declspec(naked) lookupPeerExit() {
    __asm {
        push esi
        call peerLeft
        add esp, 4
        ret 8
    }
}
