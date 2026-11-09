// Pending 命令/提案 detail caption (design-proposals-orders.md). Author: Peppone Choi.
// 0053b4b0 fetches 22/77 承諾 at 0053b588 for an unreplied row as well as an approved one. Its row kind argument
// decides: remainder 0 is still pending (0053b65b enables the reply buttons on the same test), so only this call shows
// 22/76 instead. Remainder 5 (approved) and 1..4 keep their text, and no other 22/77 caller passes through here.
#pragma once
static constexpr unsigned proposalDetailCaptionCall=0x53b588;
static constexpr BYTE proposalDetailCaptionExpected[5]={0xe8,0x83,0x6a,0xfe,0xff}; // call 00522010
static unsigned proposalDetailLookup=0x522010;
static int proposalDetailKindBase=10; // Not const: __asm must see storage, not a folded constant.
// Entry stack: +0 return, +4 group, +8 index, +0xc -1, +0x10..+0x1c four saved registers, +0x20 parent's return, +0x24 row kind.
// EAX and EDX are volatile at this call; ECX stays the catalogue, and the lookup's own RET 8 pops group and index.
static __declspec(naked) void proposalDetailCaption() {
    __asm {
        mov eax,dword ptr [esp+0x24]
        cdq
        idiv dword ptr [proposalDetailKindBase]
        test edx,edx
        jnz keep
        cmp dword ptr [esp+4],0x16
        jne keep
        cmp dword ptr [esp+8],0x4d
        jne keep
        mov dword ptr [esp+8],0x4c
    keep:
        jmp dword ptr [proposalDetailLookup]
    }
}
