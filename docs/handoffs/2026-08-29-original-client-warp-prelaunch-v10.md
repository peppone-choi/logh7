# Handoff: original-client WARP prelaunch v10

## Goal and result

Determine whether the current approved original-client run could reach a same-run, input-free WARP prelaunch state with fresh identity, heartbeat, manager65/manager67, owned HWND, foreground, and initial movement-breakpoint readiness before issuing a permit or consuming the one authorized physical activation.

Result: `BLOCKED` at `FRESH_OWNED_HWND_NOT_OBSERVABLE_FROM_AVAILABLE_GUEST_OPERATION_SESSION`. The unit stopped before debugger attach, breakpoint installation, permit issuance, owned-HWND capture, or physical input. The one authorized WARP activation remains unconsumed.

The unit also resolved a material source conflict: `U32(moduleBase+0x1E15E2C)` and `moduleBase+0x89E638` are complementary objects, not replacement roots.

## Scope actually performed

- Read-only VM/guest/server/process identity and port-47900 observations.
- Fresh application heartbeat binding from the current server trace.
- One manager67 and one manager65 read-only capture, totaling 292 `ReadProcessMemory` calls.
- Static and historical-live adjudication of the UI root and inline strategy-manager owner.
- TDD for a closed, nonpromotable root-role evaluator and a future read-only live collector.
- Guest-session diagnostics for fresh HWND availability.
- Independent read-only review and aggregate verification.

Not performed:

- physical input or automatic input;
- x32dbg attach or debugger command;
- breakpoint installation;
- process-memory or binary writes;
- owned-HWND visual capture;
- VM lifecycle, server, protocol, or database changes;
- WARP, DESTINATION, CONFIRM, movement wire, authority, persistence, or pixel claims.

## Corrected root model

`ORIGINAL_STATIC` plus partial runtime structural corroboration:

- UI/game host: `U32(moduleBase+0x1E15E2C)`.
- Manager registry: `U32(uiRoot+0x0C)`.
- Inline strategy manager owner: `moduleBase+0x89E638` without dereference.
- Manager65 controller: `strategyOwner+0x130`.
- Manager67 controller: `strategyOwner+0x48C`.
- Runtime joins observed in the preserved captures:
  - `U32(strategyOwner+0x130) == U32(registry+0x198)`;
  - `U32(strategyOwner+0x48C) == U32(registry+0x1A0)`.

Rejected interpretations:

- the dynamic UI host is not the inline strategy owner;
- the inline strategy owner does not replace the UI host;
- owner `+0/+4` are not builder/handler state: the current values are manager pointers and `+0` matches registry slot 106;
- owner `+0xF4 == 2` is not a proven WARP gate.

The old hardened manager collectors therefore contain false root-state blockers. Their controller/registry joins remain useful, but their root `builderMode`, `handlerState`, and `strategyMode` labels must not gate launch.

## Current runtime evidence

Last bound evidence in this unit:

- G7MTClient PID `3448`, start `2026-08-25T15:47:31.9489446Z`, canonical SHA-256 `BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16`, module base `0x00400000`.
- x32dbg PID `6548`, old XP build SHA-256 `42CF419B3549332AF44A8500E99085A0C590547CAE6950623FE592EA885711C6`.
- Active guest server PID `8668` accepted the client's `202.8.80.179:49722 -> 202.8.80.179:47900` connection.
- Application heartbeat candidate: connection 3, decoded request `0x0F08`, response `0x0F09`, trace timestamp `2026-08-28T17:09:30.106Z`.
- The later guest helpers ran in session 0. They could still see PID and TCP state but no interactive desktop windows. `-activeWindow -interactive` failed before helper launch; `-activeWindow` alone remained session 0.
- The previously observed HWND `0x001A0490` was not accepted as fresh in that session. No root-role memory capture ran after the HWND gate.

Do not interpret session-0 `EnumWindows=[]` as proof that the original client window was destroyed. It proves only that the available guest-operation session could not observe the interactive desktop.

## Manager capture disposition

- manager67 receipt SHA-256 `0D20CBC4CC251B2421430949A8E727539624E34476F4FA8127EF35E9816726DD`, 236 read-only reads.
- manager65 receipt SHA-256 `FC766FE64B16DC27693D9003C43D78CB79E5D103649954100256D56851D533D9`, 56 read-only reads.
- Both are `LIVE_READONLY` but remain ineligible and nonpromotable.
- They lack authoritative collector timestamps and include the superseded root-state gates.
- Current WARP stage semantics require active manager65 action `0x2B`; manager67 is dormant structural/card data at this stage. A manager67 active hit region belongs to a separately bound prior-stage receipt. Requiring both managers active simultaneously is invalid.

## Changed artifacts

Primary bounded unit:

- `work/20260829-original-client-warp-prelaunch-v10/scope.md`
- `work/20260829-original-client-warp-prelaunch-v10/timeline.md`
- `work/20260829-original-client-warp-prelaunch-v10/src/**`
- `work/20260829-original-client-warp-prelaunch-v10/tests/**`
- `work/20260829-original-client-warp-prelaunch-v10/evidence/**`
- `work/20260829-original-client-warp-prelaunch-v10/verify.ps1`
- this handoff.

Shared append-only working manuals were updated but deliberately excluded from the bounded commit because they pre-existed as untracked user/shared work:

- `report/manual.md`
- `report/mistakes.md`

## Verification

Command:

```powershell
pwsh -NoProfile -File work/20260829-original-client-warp-prelaunch-v10/verify.ps1
```

Fresh read-only result:

- status: `PASS`;
- verdict: `PRELAUNCH_V10_BLOCKED_BEFORE_ATTACH_OR_INPUT`;
- repository write count: `0`;
- artifact hashes: `17/17`;
- aggregate assertions: `92`;
- artifact-ledger SHA-256: `B8BC60F3B16F3558D15AF92681B368493244CCD571F5A88958515A5C9B0D188D`;
- stored final-verification SHA-256: `41B6845EAE91E5F9103DDF1D3D1EB751AA48C428274D4C09FE408D218923F4AC`.

Test receipts:

- identity: 20 cases / 30 assertions / 19 mutations;
- netstat: 1 / 13;
- heartbeat: 10 / 20 / 9;
- PS5.1 compatibility: 2 / 5;
- root-role evaluator: 27 / 87 / 23;
- collector capability contract: 23 assertions.

Independent review: `APPROVE`. The reviewer executed the verifier twice without `OutputPath`, confirmed repository write count 0, recomputed all seven nested static source hashes, verified synthetic/live-unreviewed self-promotion is impossible, and performed no file, VM, or process changes.

## Operation accounting

The semantic attempt ledger contains 13 ordered guest workload attempts. Earlier exact VMware transport copy/call cardinality was not instrumented and is preserved as `NOT_INSTRUMENTED_BEFORE_THIS_LEDGER`; it is not guessed.

- host observations: 2;
- guest workload attempts: 13;
- read-only process-memory reads: 292;
- process-memory writes: 0;
- debugger attach: 0;
- debugger commands: 0;
- breakpoints installed: 0;
- owned-HWND captures: 0;
- game/automatic inputs: 0/0;
- physical activations: 0;
- permit issuance: 0;
- VM lifecycle/server/protocol/database changes: 0/0/0/0.

## Proven, inferred, and unknown

Proven:

- `STATIC_MAPPED`: the two root roles and inline manager controller offsets.
- `PARTIAL_RUNTIME_STRUCTURAL_CORROBORATION`: manager65/67 controller pointers equal their dynamic registry slots in the preserved current process.
- client/server TCP connection and one application heartbeat response were observed.
- all state-changing operation counters are zero.

Not proven:

- fresh interactive owned HWND;
- active manager65 WARP action and independently bound hit region;
- manager67 prior-stage active hit-region receipt;
- x32dbg foreground;
- guest debugger build compatibility with the MVB plan;
- attach, initial MVB installation, per-thread DR state, permit eligibility, or physical WARP activation;
- outbound/inbound movement, authority, persistence, or player-visible movement.

## Exact next start

Open a separate bounded unit `ORIGINAL_CLIENT_INTERACTIVE_DESKTOP_HWND_ROUTE`. Establish one reproducible, no-input method that executes a fresh identity/HWND helper in the actual logged-on interactive desktop and records session ID, desktop name, PID, canonical hash, module, HWND existence, owner PID, visibility, and client rectangle twice.

Candidate investigation order:

1. audit VMware Tools interactive-launch prerequisites and the reason `-activeWindow -interactive` fails before helper start;
2. inspect an already-running helper or task in the logged-on session without changing VM lifecycle;
3. if guest operations cannot enter the interactive desktop, use an approved direct desktop/VNC read-only HWND-observation route and bind it to PID ownership;
4. only after a fresh owned HWND passes, run the corrected root-role collector once and stop for review.

After that, a separate prelaunch continuation must still resolve manager65 action `0x2B`, the prior manager67 hit-region receipt, foreground, debugger-build reconciliation, MVB installation, per-thread DR state, and independent approval before issuing any permit.

## Forbidden retries

- Do not reuse HWND `0x001A0490` without a fresh Win32 existence/ownership/client-rect receipt.
- Do not repeat session-0 `EnumWindows` and interpret emptiness as window destruction.
- Do not repeat `-activeWindow -interactive` blindly; diagnose its launch prerequisite first.
- Do not use the old manager collectors' root `+0/+4/+0xF4` state gates.
- Do not require manager65 and manager67 active simultaneously.
- Do not attach, install MVBs, foreground x32dbg, issue a permit, or consume the activation before every earlier gate passes.
- Do not patch the client, write process memory, automate input, change VM lifecycle, or change server/protocol/database state.
