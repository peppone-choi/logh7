# Scope: destination grid world-to-client hit-region owner

## Question

Which exact original-client functions, runtime fields, and transforms map a client-area mouse position to the mode-`0x101` destination grid candidate, and what additional state is required to derive a safe client-area activation region for a chosen grid?

## Target

- `G7MTClient.exe`
- SHA-256 `BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16`
- PE32 x86

## Allowed

- read-only reuse of `work/ghidra-input-consumption/Unit10Input`, program `g7mtclient.exe`
- `-readOnly -noanalysis` headless exports
- offline scripts, fixtures, tests, reports, and independent read-only review

## Forbidden

- live oracle or VM operations
- game input, automatic click, or retry
- process-memory or binary writes and patches
- server, protocol, or database changes

## Acceptance boundary

The unit may pass only if it binds the complete static mouse-to-grid callflow, records every runtime field needed for client-area derivation, and implements a fail-closed read-only/offline binding contract. If a chosen grid's exact activation region still requires fresh runtime matrices or sampling, the result remains `PARTIAL` and names that first missing boundary.
