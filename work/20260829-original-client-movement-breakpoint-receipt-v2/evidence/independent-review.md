# Independent review: movement breakpoint receipt v2

- reviewer: `prelaunch_v7_delta` (read-only validator)
- verdict: `APPROVE`
- validator workspace writes: `0`
- fresh aggregate exit: `0`
- receipt-v2 tests: `78 cases / 101 assertions / 74 mutations`
- prelaunch-v8 tests: `20 cases / 30 assertions / 19 mutations`
- JSON Schema: `Draft 2020-12`, `2/2 PASS`
- artifact hashes: `12/12`
- artifact-ledger SHA-256: `093108A8F8E50716A132AC1DE740E53DAC03AB26FEECBC2FE78059D8908D234E`
- authoritative external source hashes: `6/6`

## Findings closed

- Positive `EXIT_THREAD` semantics pass; CREATE requires absent-before/present-after and EXIT requires present-before/absent-after.
- Phase, snapshot, accepted/rejected hit, lifecycle, thread-census, observation-range, and complete hardware-event transcript ordinals are cross-bound.
- Accepted hits require EFLAGS.RF clear, suppression class `NONE`, a suppression audit, pre-command DR0-DR7, trigger slot/address, and DR6 trigger evidence.
- DR7 is local-only and execute/size-one; physical slot permutation is accepted. Reserved DR6 bits are tolerated while BD/BS/BT are rejected.
- Queue decrement is bound to phase 9, MVB09 event ordinal, and a dedicated evidence hash.
- The actual input receipt is validated against the closed Draft-2020-12 schema; extra root and nested properties fail.
- `DBG_REPLY_LATER` remains denied; ten phases, eighteen commands, nine SET and nine DELETE commands remain exact.
- v7 to v8 blocker conservation remains `12 -> 12`.

## Claim ceiling

- runtime no-miss: `MISSING`
- runtime receipt: `MISSING`
- prelaunch: `READY_FALSE`
- live operations, debugger commands, process-memory reads/writes, input, capture, permit issuance: `0`
- synthetic evidence was not promoted to live evidence.
