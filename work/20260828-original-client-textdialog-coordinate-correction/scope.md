# Scope: TextDialog coordinate-frame collector correction

## Question

Which exact original-client UI-context, parent-origin, widget, scaling, and client-rectangle fields produce the confirmation and cancellation hit rectangles, and can a fresh read-only snapshot yield replay-verified client pixels without using the disproved manager `+0x7C/+0x80` origin model?

## Target

- `G7MTClient.exe`, PE32 x86
- SHA-256 `BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16`

## Allowed

- existing hash-bound Ghidra export and read-only `-noanalysis` follow-up if needed
- offline fixtures, tests, corrected read-only collector, coordinate resolver, report, and independent review

## Forbidden

- live oracle or VM operations
- clicks, input synthesis, retries, process-memory writes, binary patches
- server, protocol, or database changes
- use of manager `+0x7C/+0x80` or old `rawRect` as activation coordinates

## Acceptance boundary

Pass only if the old model is absent, recursive logical origins and widget transform gates are captured twice, logical rectangles are converted to exact half-open client-pixel sets with forward replay, and live self-claims remain unbound pending independent run evidence.
