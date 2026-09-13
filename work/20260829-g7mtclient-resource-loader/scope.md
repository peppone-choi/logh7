# G7MTClient executable RESOURCE_LOADER closure

## Question

What exact original evidence closes `RESOURCE_LOADER` for
`RESOURCE:FILE:original-installshield-payload:exe/g7mtclient.exe`?

## Unit

- Unit ID: `RECOVERY:D01:RESOURCE_LOADER:E346F47C94A6E543`
- Boundary: `RESOURCE_LOADER`
- Recovery disposition: `RECOVERABLE_STATIC`
- Live input count: 0
- Runtime mutation: false

## In scope

- Hash-bound PE triage: type, architecture, sections, imports, version identity, entry point, and packing/readability quality.
- Original InstallShield filename/placement and exact executable role.
- Static process-launch relationships from hash-bound bootstrap, updater, launcher, or existing Ghidra evidence.
- Fail-closed distinction between OS process-image loading and G7MTClient runtime game-resource loading.
- One resource adjudication and deterministic foundation regeneration.

## Out of scope

- VM, debugger, process memory, physical input, or executable launch.
- Original binary patching or Ghidra repository mutation.
- Server, protocol, database, port, or VM lifecycle changes.
- Runtime observation, player visibility, original-client playability, gameplay, authority, persistence, faction parity, or evidence-state promotion.
- Starting the next recovery unit.

## Acceptance

- PE triage includes the mandatory imports anchor and is reproducible from the original bytes.
- Any launcher relation is tied to an exact hash, function/callsite/string, or remains explicitly unresolved.
- The adjudication explains why an executable process image is or is not applicable to the G7MT runtime-resource-loader boundary.
- Tests reject source, PE identity, role, process-launch, receipt, and unsupported-field drift.
- The loader boundary closes without changing any evidence-state boolean.
- Full generated artifacts reproduce and an independent read-only reviewer approves the bounded result.
