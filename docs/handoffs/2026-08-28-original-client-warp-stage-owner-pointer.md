# Handoff: original-client WARP stage-owner pointer

## Goal

Resolve the static pointer chain that proves the fixed TextDialog manager belongs to the currently active WARP flow, then integrate it fail-closed into the first-play prelaunch contract.

## Scope and actual work

- Re-read the oracle manual and mistakes ledger.
- Reproduced the dispatcher, factory, active-flow publication, child vector, TextDialog callback, and manager-cache chain in Ghidra read-only mode.
- Implemented a fresh identity/HWND/module-bound double-capture read-only collector.
- Added negative fixtures for null owner, wrong command/card, malformed vector, child-sequence drift, TextDialog tuple/opcode/manager drift, layout/terminal state, torn capture, and executable hash.
- Added v5 prelaunch contract and verifier.
- Performed no VM, debugger, live process-memory, capture, input, server/protocol/DB, or permit operation.

## Changed files

- `work/20260828-original-client-warp-stage-owner-pointer/**`
- `docs/handoffs/2026-08-28-original-client-warp-stage-owner-pointer.md`
- operational lessons appended to untracked shared `report/manual.md` and `report/mistakes.md` (not included in the bounded commit)

## Commands

```powershell
& 'C:\Users\user\AppData\Local\Programs\Ghidra\ghidra_12.1.2_PUBLIC\support\analyzeHeadless.bat' 'E:\logh7-greenfield\work\ghidra-input-consumption' 'Unit10Input' -process 'g7mtclient.exe' -readOnly -noanalysis -scriptPath 'E:\logh7-greenfield\work\20260828-original-client-warp-stage-owner-pointer' -postScript 'ExportWarpStageOwnerPointer.java' 'E:\logh7-greenfield\work\20260828-original-client-warp-stage-owner-pointer\evidence\warp-stage-owner-pointer.txt'
& 'work\20260828-original-client-warp-stage-owner-pointer\verify.ps1'
```

## Evidence

- `evidence/warp-stage-owner-pointer.txt`
- `evidence/warp-stage-owner-static-ledger.json`
- `evidence/fixture-snapshot.json`
- `evidence/prelaunch-v5-warp-owner.json`
- `evidence/artifact-ledger.json`
- `evidence/final-verification.json`
- `evidence/independent-review.md`

## Confirmed facts

- `moduleBase+0x89E2F8` is the current strategy flow pointer field.
- Dispatcher publication records command at flow `+0x28` and authority-card ID at `+0x20`.
- WARP factory `FUN_00581C80` creates an exact six-child flow; child 2 is the scoped TextDialog.
- TextDialog callback resolves and caches manager `moduleBase+0x8A292C` at TextDialog `+0x58`.
- `WARP_STAGE_OWNER_POINTER_UNBOUND` is statically resolved; runtime remains `UNSEEN`.

## Unknown / unverified

- Fresh owner, vector, TextDialog and manager values in one authorized run.
- Fresh destination/TextDialog hit regions and player-visible transition.
- Movement breakpoint receipts and outbound/inbound opcode correlation.
- Original-client end-to-end playability, authority, persistence, both factions, and every full-game completion condition.

## Execution state

- `STATIC_WARP_STAGE_MANAGER_OWNER_PASS_LIVE_SNAPSHOT_UNSEEN`
- Prelaunch: `OFFLINE_PRELAUNCH_WARP_OWNER_INTEGRATED_READY_FALSE`
- Live operations: 0; process-memory reads: 0; game inputs: 0; permit issued: false.

## Next start

Build the movement-specific breakpoint/receipt schema around `0x005737D0`, `0x004B490E`, `0x004B7EDE`, `0x004B7EE3`, `0x004B85B6`, `0x004BCF4F`, and `0x004BCF7E`, without installing breakpoints or launching the VM. Separately reconcile the one-activation authority with the three-stage UI sequence before any live launch.

## Failed approaches and manual assessment

- The first exporter lacked its output directory; a second attempt met the parallel project lock. Neither touched the target. The manual now explicitly requires output-parent creation, artifact-marker checking, and serialized Ghidra project access.

## Forbidden retries

- Do not delete Ghidra lock files or terminate an owned analysis process.
- Do not reuse prior PID/HWND/pointer/coordinate/permit evidence.
- Do not click, retry, write memory, patch binary/resources, or alter VM/server/protocol/DB state.
- Do not promote fixture or self-claimed live evidence to runtime/player-visible/authority/persistence/Gate status.
