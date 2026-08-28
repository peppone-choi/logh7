# Timeline

- 2026-08-29: unit opened from WARP prelaunch v10 handoff.
- 2026-08-29: local Orca computer-use status/capabilities passed; VMware Workstation app PID 11704 and window title `LOGH7 Oracle Win11 HD RE - VMware Workstation` were enumerated without focus or input.
- 2026-08-29: VMware window was found at the minimized sentinel coordinates `-32000,-32000`; no guest pixels or actions were used.
- 2026-08-29: authenticated `vmrun captureScreen` and passive VNC capture both produced 1024x768 all-black frames; no wake, focus, keyboard, or pointer input was sent.
- 2026-08-29: a session diagnostic proved active console session 1 with `explorer`, canonical G7MTClient PID 3448, and x32dbg PID 6548 while the noninteractive helper ran in session 0.
- 2026-08-29: broker inventory proved same-user `vmtoolsd` agents in sessions 0 and 1 and no pre-existing interactive-token task/service broker.
- 2026-08-29: TDD RED was observed for the missing evaluator and collector. The first contract RED also exposed a missing whitespace after `throw`; the test was corrected before implementation.
- 2026-08-29: initial evaluator/collector GREEN passed 20 cases / 63 assertions and 24 capability assertions.
- 2026-08-29: independent pre-implementation audit returned `REVISE`: the receipt self-asserted snapshot stability, a provenance string could self-claim live status, and operation/identity mutation coverage was incomplete.
- 2026-08-29: contract revision stored complete A/B snapshots, required a separate host launch binding, expanded identity and all forbidden-operation mutations, and added AST plus capability deny checks. Fresh GREEN passed 47 cases / 139 assertions and 50 capability assertions.
- 2026-08-29: one unique collector source was copied to `C:\LOGH7_ORACLE\interactive-canary-20260829T183100Z-v1.ps1` and one `runProgramInGuest -interactive` call was issued without `-activeWindow` or input.
- 2026-08-29: the one helper launch attempt returned exit `-1`, output `Error: A file was not found`, and produced no started, raw, or diagnostic receipt. All three one-time copy-back calls confirmed the files absent.
- 2026-08-29: one read-only `fileExistsInGuest` check returned exit 0 / `The file exists.` for the copied collector. Static comparison with the earlier successful route showed the material difference: this attempt used bare `powershell.exe`; successful routes used `C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe`.
- 2026-08-29: no helper retry was issued. No game input, foreground change, debugger action, process-memory access, permit issuance, VM lifecycle change, or server/protocol/database change occurred. The physical WARP activation remains unconsumed.
- 2026-08-29: final independent re-review returned `APPROVE` for fail-closed bounded closure only. It explicitly left helper creation, interactive session/desktop, fresh HWND, promotion, prelaunch eligibility, permit, and physical activation `UNKNOWN/UNSEEN` or unconsumed.
