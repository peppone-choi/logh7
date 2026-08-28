# PRELAUNCH_V4_MANAGER65_INTEGRATION

## Question

Does the hardened manager65 action-0x2B collector retire only its static hardening blocker while preserving every fresh-run and independent-binding boundary required before one physical activation?

## Scope

- Layer a v4 offline prelaunch contract over the sealed v3 manager67 contract.
- Bind the manager65 hardening artifact ledger, static owner ledger, and fresh verifier output contract.
- Prove fail-closed blocker ordering and zero live/input operations.
- Do not launch the VM, attach a debugger, read live process memory, or issue input.

## Acceptance

- The old manager65 hardening blocker is absent.
- `FRESH_MANAGER65_SNAPSHOT_MISSING` and `MANAGER65_ACTION_0X2B_HIT_REGION_INDEPENDENT_BINDING_MISSING` are present separately.
- First policy boundary remains the activation-budget mismatch; first technical boundary becomes the WARP stage-owner pointer.
- All bound hashes and manager65 offline semantics are mechanically checked.
- Mutation tests reject self-promotion, stale hashes, reordered blockers, nonzero operations, and unsafe same-run composition.

