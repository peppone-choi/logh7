# Timeline

- phase: triage
- decision_delta: [target hash and PE32 x86 identity reused from hash-bound current evidence; live operations remain zero]
- carry_forward_refs: [docs/handoffs/2026-08-27-original-client-first-play-stage-gate.md, docs/handoffs/2026-08-27-original-client-manager65-readonly-collector.md, report/manual.md, report/mistakes.md]
- phase: static
- hypothesis: SelectGrid embeds stage-local target selectors whose vtable callbacks expose enough state to bind a fresh destination; confirm may be a later runtime dialog and must not be pre-bound.
- next: export vtable pointers, references, constructors, and callback bodies from the read-only Ghidra project.
- phase: implementation
- decision_delta: [bound SelectGrid destination state at 0x009D2A30 and TextDialog manager 4/index 3 at 0x00CA292C; implemented read-only fresh PID/HWND collectors with fixture-driven tests]
- result: STATIC_STAGE_STATE_COLLECTORS_PARTIAL
- verification: [destination 6 cases/24 mechanically counted assertions, confirm 5 cases/25 mechanically counted assertions, canonical target hash enforced internally, forbidden capability hits 0, live operations 0]
- first_missing_boundary: DESTINATION_GRID_WORLD_TO_CLIENT_HIT_REGION_OWNER
- independent_review: APPROVE after one REVISE cycle for assertion counting, mutation coverage, canonical hash enforcement, and factory/controller wording
- receipt_determinism_review: APPROVE after canonical BOM-free UTF-8 LF output produced identical 17AF17ED2E6A8821F83AE76E213AF1A77867CD26C4159B4BAB52B849256ADFBA receipts in two PowerShell invocation styles
- next: bind the destination grid world-to-client hit-region owner before any new permit or activation request; then bind the confirm widget coordinate frame.
