# Handoff: original-client interactive desktop HWND route v2 offline prelaunch

## Goal and result

Prepare and mechanically seal the corrected interactive-canary route without repeating the failed live helper attempt or performing any VM/guest operation.

Result: `PASS` for `CORRECTED_ROUTE_V2_OFFLINE_PREPARED_NO_EXECUTION_AUTHORITY`.

This unit fixes the technical prelaunch contract but does not grant or consume execution authority. `routeLaunchCandidateEligible=false`, `executionAuthorized=false`, `prelaunchEligible=false`, and the previous physical WARP activation remains unconsumed.

## Scope actually performed

- TDD for a future noninteractive preflight collector and fail-closed evaluator.
- Direct owner-name/SID and module identity schema for client, debugger, and session-1 vmtoolsd.
- Absolute PowerShell program path/hash/length/time plus host-copy binding.
- Current/sealed/guest-round-trip interactive collector hash equality.
- Freshness ceiling, exact ordered arguments, safe run ID, unique guest/host paths, previous-run/path collision rejection, and no-retry route fields.
- External binding mutation coverage, aggregate verification, and independent read-only review.

Not performed:

- vmrun, VNC, VMware UI, guest helper, guest file, process, debugger, memory, input, capture, or lifecycle operations;
- permit issuance or physical activation;
- client binary, server, protocol, or database changes.

## Corrected preflight receipt

The future read-only collector records:

- session-0 helper PID, full owner name and SID;
- active console session;
- exact `C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe` path, existence, SHA-256, length, and observation time;
- exactly one G7MTClient, x32dbg, and active-session vmtoolsd tuple;
- for each tuple: PID, session, start time, path, SHA-256, owner name/SID, module base, and module size;
- direct `GetOwner` and `GetOwnerSid` success for vmtoolsd;
- explicit zero target/game mutation counters.

The collector contains no input, foreground, debugger, process-memory, process-launch, process-kill, vmrun, VNC, service, task, server, protocol, or database mutation capability.

## Host binding and route disposition

An externally bound live preflight must additionally prove:

- raw receipt, preflight collector, VMX, and program host-copy hashes;
- a separately hash-bound program-copy receipt with the same run ID, exact guest PowerShell source path, host-copy path/hash/length, copy exit 0, and ordered copy time;
- program host-copy hash and length equal the guest-observed hash and length;
- current interactive collector source, immutable seal, and guest round-trip hashes are identical;
- preflight observation is no more than 120 seconds old and not from the future;
- manifest creation follows observation and precedes evaluation;
- exact preflight executable and ordinal argument vector;
- exact interactive program, ordinal argument vector, normalized guest/host paths, and same run ID;
- a hash-bound historical attempt ledger containing the failed v1 run but not the new run or paths;
- the historical ledger contains exactly one exact-key v1 entry and all four known guest source/started/raw/diagnostic paths; empty, partial, extra-key, or current-run substitutions are rejected;
- a hash-bound path-absence receipt proving every new guest and host output path absent before manifest creation;
- one source copy, one preflight helper call, one copy-back, and host exit 0;
- the new run ID and guest paths do not reuse the failed v1 run.

Even when all structural checks pass, the evaluator adds `EXECUTION_AUTHORITY_NOT_BOUND` and returns:

- status `CORRECTED_ROUTE_LIVE_PREFLIGHT_STRUCTURALLY_READY_AUTHORITY_MISSING`;
- `routePreparedCandidateEligible=true`;
- `routeLaunchCandidateEligible=false`;
- `executionAuthorized=false`;
- `prelaunchEligible=false`.

It cannot manufacture a fresh attempt budget from a new run ID.

## Tests and verification

Fresh results:

- evaluator: 57 cases / 123 assertions / 27 receipt mutations / 17 binding mutations / 10 support-receipt mutations;
- collector capability contract: 30 assertions;
- parsed PowerShell files: 5;
- aggregate verdict: `CORRECTED_ROUTE_V2_OFFLINE_PREPARED_NO_EXECUTION_AUTHORITY`;
- VM/guest operations: 0;
- helper launches: 0;
- game inputs / physical activations: 0 / 0;
- execution authorized: false.

Artifacts:

- `work/20260829-original-client-interactive-desktop-hwnd-route-v2-prelaunch/evidence/synthetic-preflight-evaluation.json`
- SHA-256: `F2C977D6C777418268A3C4F40E8CEAC314BF8F3F6905354F82429EEB241B6028`
- `work/20260829-original-client-interactive-desktop-hwnd-route-v2-prelaunch/evidence/final-verification.json`
- SHA-256: `21CBAD628A97627559B6BC30F2E23C5C48DC14BF355932577CE5EC4F35B18494`

`PASS` is limited to offline contract preparation. It proves no live session, desktop, HWND, WARP, authority, pixels, or persistence.

Independent final review: `APPROVE` for this bounded offline unit only. The reviewer reran the verifier and confirmed every previous `REVISE` item closed. The approval explicitly does not authorize VM/guest execution; `routeLaunchCandidateEligible`, `executionAuthorized`, and `prelaunchEligible` remain false and `EXECUTION_AUTHORITY_NOT_BOUND` remains mandatory for a structurally ready live preflight.

## Proven, inferred, and unknown

Proven:

- the corrected contract cannot use bare PowerShell lookup;
- synthetic and unbound-live receipts cannot become route launch candidates;
- valid externally bound structure still cannot authorize execution;
- no VM/guest or target operation occurred in this unit.

Inferred/NEW DESIGN:

- the 120-second freshness ceiling is an operational `NEW_DESIGN` prelaunch threshold, not original-client behavior.

Unknown:

- current guest program bytes, live owner SID, current client/debugger tuples, and session-1 vmtoolsd tuple until the future collector is actually run;
- a distinct post-v1 execution authority record;
- interactive helper start, session/desktop, fresh HWND, foreground, manager gates, debugger/MVB state, permit, WARP, authority, pixels, and persistence.

## Changed artifacts

- `work/20260829-original-client-interactive-desktop-hwnd-route-v2-prelaunch/**`
- this handoff.

Shared working manuals were updated but remain excluded from the bounded commit:

- `report/manual.md`
- `report/mistakes.md`

## Exact next start

Only after a distinct execution authority record exists:

1. create a new unique run ID and immutable collector seal;
2. run the read-only preflight collector once using the absolute PowerShell path;
3. copy back and externally bind the program, VMX, collector, process, owner, freshness, and route evidence;
4. independently review the bound preflight;
5. only then decide whether one corrected interactive helper call is authorized.

## Forbidden retries

- Do not reuse `20260829T183100Z-v1`, its guest paths, or bare `powershell.exe`.
- Do not treat the synthetic evaluation as live evidence.
- Do not interpret `routePreparedCandidateEligible` as launch authorization.
- Do not create a new attempt budget from a run ID or manifest.
- Do not execute vmrun, attach, install breakpoints, access process memory, foreground a window, or send input from this unit.
- Do not patch the client or change VM lifecycle, server, protocol, or database state.
