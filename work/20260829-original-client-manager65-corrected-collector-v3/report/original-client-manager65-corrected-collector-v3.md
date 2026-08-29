# Original-client manager65 corrected collector v3

## Result

The bounded offline collector/evaluator unit passes. This is not a live WARP receipt.

## Corrections

1. `U32(moduleBase+0x1E15E2C)` is the UI mode/registry host. Its `+0/+4` fields own the builder/handler checks.
2. `moduleBase+0x89E638` is the inline strategy-manager owner. Its first DWORDs and `+0xF4` are not used as mode gates.
3. manager65 is resolved through owner `+0x130` and registry slot `+0x198`; manager67 is separately resolved through owner `+0x48C` and registry slot `+0x1A0` and must be dormant at this stage.
4. The engine viewport is a child surface inside the owned HWND, not necessarily the complete HWND. The action client region is resolved from the logical widget rectangle and scale and checked against the owned HWND.

## Fixture result

- manager65 command `0x2B`: unique and eligible;
- manager67: structurally joined and dormant;
- engine viewport: `800x576` inside an `800x600` owned-HWND fixture;
- resolved offline client region: `[560,420,656,436)`;
- fixture-only safe point: `(607,427)`;
- double-capture reads: `150`;
- target writes, input, debugger, VM, server/protocol/DB, permit: `0`.

The evaluator returns the region only as `offlineCandidateRegion`. `automaticActivationPoint` remains null and all live, WARP prelaunch, launch, and permit eligibility fields remain false.

## Verification

```powershell
pwsh -NoProfile -File work/20260829-original-client-manager65-corrected-collector-v3/verify.ps1
```

Expected result: 7 tests, 62 mutation subtests, Windows PowerShell 5.1 fixture compatibility, 4 bound static sources, 7 read/query native APIs, forbidden native capabilities 0.

Independent round-two review reran both `pwsh` and Windows PowerShell 5.1 and returned `APPROVE`. The reviewer made no file or target changes.

## Remaining boundary

The current live boundary remains `FRESH_OWNED_HWND_NOT_OBSERVABLE_FROM_AVAILABLE_GUEST_OPERATION_SESSION`. A future live capture also requires fresh run/client/listener/heartbeat/foreground identity, external capture and hit-region hash binding, and independent live review. This unit does not resolve the stage-gate v1 activation-authority mismatch or create a WARP receipt.
