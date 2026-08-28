# Original terms-of-service TXT RESOURCE_LOADER closure

## Question

What exact original evidence closes `RESOURCE_LOADER` for
`RESOURCE:FILE:original-installshield-payload:doc/___p_`vii___p_k__.txt`?

## Unit

- Unit ID: `RECOVERY:D01:RESOURCE_LOADER:F9CBE1F4AEAE7D6B`
- Boundary: `RESOURCE_LOADER`
- Recovery disposition: `RECOVERABLE_STATIC`
- Live input count: 0
- Runtime mutation: false

## In scope

- Hash, byte-size, encoding, decoded title, and duplicate InstallShield support-file identity.
- Installer/source role and exact original-name evidence when available.
- Static reference checks over the hash-bound original executable and existing Ghidra exports.
- One fail-closed resource adjudication and deterministic foundation regeneration.

## Out of scope

- VM, debugger, process memory, physical input, or executable launch.
- Ghidra analysis or repository mutation.
- Original binary patching or server/protocol/database changes.
- Owner, runtime observation, player visibility, gameplay, authority, persistence,
  faction parity, or evidence-state promotion.
- Starting the next recovery unit.

## Acceptance

- Exact file facts and CP932 decode are reproducible from the original payload bytes.
- The adjudication distinguishes installer/license-document use from G7MTClient runtime resource loading.
- Tests reject content, encoding, title, duplicate-source, and unsupported-field drift.
- The loader boundary closes without changing any evidence-state boolean.
- Full generated artifacts reproduce and an independent reviewer approves the bounded result.
