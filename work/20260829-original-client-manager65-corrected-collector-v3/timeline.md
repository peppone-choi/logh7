# Timeline

- Re-read the controlling goal, oracle operating manual, mistakes ledger, latest WARP prelaunch v10 handoff, superseded manager65 collector, and stage-gate contract.
- Parallel read-only audits identified two false gates: strategy-owner `+0/+4/+0xF4` state interpretation and engine-viewport/full-HWND equality.
- Implemented a new bounded collector rather than editing the sealed historical unit.
- Added UI-root builder/handler reads, inline owner/controller/registry joins, manager67 dormant-stage reconciliation, 150-read double fixture capture, timestamps, run ID, external identity-receipt hash, and zero-operation counters.
- Added an independent evaluator that validates exact root and nested shapes, externally supplied capture/collector hashes, recomputed action semantics, exact inverse client region, and an offline-only claim ceiling.
- The first independent review returned `REVISE`: PowerShell 5.1 rejected `ConvertFrom-Json -DateKind`, and the external identity-receipt digest was format-checked but not compared with an externally supplied expected value.
- Removed the incompatible switch, added an actual `powershell.exe` fixture test, added an external expected identity-receipt SHA argument and a valid alternate-SHA mutation.
- The first attempt to republish the fixture exposed that `Move-Item` without overwrite cannot replace an existing receipt. Replaced it with same-volume `File.Replace`/`File.Move` publication so the requested path is atomically replaced or created.
- Ran 7 tests with 62 semantic mutations and both `pwsh` and Windows PowerShell 5.1 aggregate verification.
- Requested a second independent read-only final review of the corrected files.
- Round-two independent review reran both shells and returned `APPROVE`; target mutation capability remained zero and the live claim ceiling remained unchanged.
- Removed only two failed-attempt `fixture-capture.json.tmp-*` files from this bounded evidence directory before sealing; the last complete receipt was never deleted or overwritten by an incomplete file.

No live, VM, debugger, input, process-memory, server, protocol, database, binary, resource, or permit operation occurred.
