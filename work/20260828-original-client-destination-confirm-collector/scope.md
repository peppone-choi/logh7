# Scope: original-client destination and confirm stage binding collectors

## Question

After the fresh `WARP` activation creates the `SelectGrid` flow, which exact
original-client objects, fields, gates, and rectangles can bind the
`DESTINATION` stage, and after that selection which object can bind the
`CONFIRM` stage without stale-pointer reuse?

## Target and authority

- Hash-bound target: PE32 x86 `G7MTClient.exe`, SHA-256
  `BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16`.
- Analysis project: `work/ghidra-input-consumption/Unit10Input`.
- Authorized operations: offline, read-only Ghidra analysis and local fixture tests.
- Live VM, debugger attach, input, process-memory write, binary/resource patch,
  VM lifecycle, server, protocol, and DB changes are out of scope.

## Acceptance boundary

1. Reproduce the `SelectGrid` object and callback layout from the hash-bound PE.
2. Identify a deterministic read-only destination binding or preserve the exact
   first missing field/owner as `UNKNOWN`.
3. Identify a deterministic read-only confirm binding or preserve the exact
   first missing field/owner as `UNKNOWN`.
4. Implement only collectors whose complete source path is statically proven;
   fixture success cannot become live or player-visible evidence.
5. Record evidence, verification, residual Unknowns, next start, and forbidden retries.
