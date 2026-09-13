# Timeline

- 2026-08-29: unit opened offline; goal, manual, mistakes, and hardware-rearm handoff re-read.
- 2026-08-29: parallel audits assigned for v2 field invariants, v1 migration/prelaunch delta, and authoritative debugger semantics.
- 2026-08-29: v1 additional-properties closure requires a parallel v2 envelope rather than in-place extension.
- 2026-08-29: selected a hash-bound v1 receipt composition model so v1 payload/hit evidence remains independently verifiable and immutable.
- 2026-08-29: first independent read-only review returned REVISE for EXIT_THREAD semantics, incomplete ordinal/thread census correlation, missing RF/suppression/trigger-state evidence, permissive DR7 handling, and an unlocated queue decrement.
- 2026-08-29: reproduced the findings with positive EXIT_THREAD and adversarial mutation cases, then added phase-bound snapshots, exact lifecycle deltas, accepted/rejected census links, a complete hardware-event transcript, EFLAGS/RF and predecessor-suppression evidence, pre-command DR/trigger-slot evidence, exact DR7 policy, and MVB09-bound queue decrement evidence.
- 2026-08-29: independent pressure testing found two additional fail-open boundaries: DR6 BD/BS/BT status bits and semantic verification without validating the actual receipt against the closed JSON Schema.
- 2026-08-29: added a positive reserved-DR6 case, negative BD/BS/BT cases, root/nested additional-property mutations, and Draft-2020-12 validation of every actual ReceiptPath.
- 2026-08-29: synchronized v8 and the artifact ledger; aggregate verification passed with receipt-v2 78 cases / 101 assertions / 74 mutations, prelaunch-v8 20 / 30 / 19, two schema documents, and twelve artifact hashes.
- 2026-08-29: final independent read-only re-review returned APPROVE; validator writes 0 and runtime/live claims remain MISSING/READY_FALSE.
