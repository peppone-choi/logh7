# ORIGINAL_CLIENT_WARP_STAGE_OWNER_POINTER

## Question

Which exact runtime pointer owns the active WARP flow, and how can a fresh read-only collector prove that its child list contains the `TextDialog` object constructed by `FUN_00581C80`?

## Boundaries

- Canonical target: `G7MTClient.exe` SHA-256 `BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16`.
- Offline Ghidra analysis and local evidence only.
- No VM lifecycle, guest operation, debugger attach, process-memory access, input, binary patch, server/protocol/DB change, or permit consumption.

## Acceptance

- Reproduce manager65 command `0x2B` dispatch to factory `FUN_00581C80`.
- Identify the active flow-owner pointer field and its module-relative address.
- Identify the owner-to-child-container and child-object traversal needed to distinguish the WARP `TextDialog` from unrelated dialogs.
- Implement a fail-closed offline-verifiable binding contract or document the exact remaining static edge.
- Obtain independent review and preserve runtime/player-visible status as `UNSEEN`.
