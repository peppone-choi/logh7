# Timeline

- 2026-08-28: unit opened offline; goal, manual, mistakes, and movement-receipt handoff re-read.
- 2026-08-28: parallel audits assigned for official commands, formal schedule, and prelaunch v7 delta.
- 2026-08-28: official source inspection found four-slot enforcement, all-current-thread DR programming, and CREATE_THREAD propagation; no debugger or VM was run.
- 2026-08-28: formal audit selected a single rotating synchronous slot plus pre-armed MVB06/MVB08/MVB09, with MVB07 armed at the paused MVB05 boundary.
- 2026-08-28: audit also found receipt-v1 temporal/per-thread/rejected-hit/unique-queue-correlation fields missing; v7 must remain fail-closed on that schema gap.
- 2026-08-28: first GREEN simulator removed then appended the rotating anchor and lost DR-slot order; corrected to replace the removed anchor in place within the paused transaction.
- 2026-08-28: second GREEN exposed that `active` was specified as a canonical set, not a DR-slot map; simulator now canonicalizes membership, while exact per-thread DR0-DR3 mapping remains an explicit receipt-v2 gap.
- 2026-08-28: third GREEN found PowerShell flattened the final nested empty-set literal; final cleanup is now asserted directly as zero active anchors.
- 2026-08-28: fourth GREEN found the empty function result was assigned as null and then compared as `[null]`; call-site array capture now preserves the true empty final set.
- 2026-08-28: bound the exact installed x32dbg commit and three x32 binary hashes; retained the installed-source multi-thread TODO as runtime-unseen rather than promoting reference-source behavior.
- 2026-08-28: hardware plan suite initially passed 35 cases / 53 assertions / 34 mutations; trace has ten phases, nine transitions, and peak four active definitions.
- 2026-08-28: prelaunch v7 initially passed 19 cases / 28 assertions / 18 mutations; the rearm blocker is resolved and replaced one-for-one by the receipt-v2 temporal/thread/correlation schema gap.
- 2026-08-28: aggregate verification passed with ten hash-bound artifacts and zero live/debugger/input/memory/capture/permit operations.
- 2026-08-29: independent review returned REVISE because commithash.txt was not bound, runtime no-miss semantics were assumed, receipt-v2 lacked plan/binary/command-result bindings, and the stored dry-run trace was not compared to a fresh verifier result.
- 2026-08-29: bound commithash.txt, narrowed no-miss to MISSING with two external debugger semantics unbound, expanded receipt-v2 gaps from five to eight, bound the prior v6 sealed receipt/ledger, and mechanically compared fresh trace/phase/transition/peak.
- 2026-08-29: revised suites pass 39 cases / 59 assertions / 38 mutations and 21 cases / 33 assertions / 20 mutations; aggregate verification is GREEN pending re-review.
- 2026-08-29: independent read-only re-review returned APPROVE with validator writes 0; all four initial findings are closed within the offline claim boundary.
