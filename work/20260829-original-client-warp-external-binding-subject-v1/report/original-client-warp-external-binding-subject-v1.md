# WARP external live binding subject v1

## Result

The offline contract and geometry test vector pass. Independent review round 2 returned `APPROVE`. No live binding subject exists.

## Current state

- H1–H6: `NOT_CREATED`;
- live oracle run ID, receipts, paths, source/expected hashes, timestamps: null;
- live full client rect, manual point, activation cell, binding digest: null;
- authority maximum/consumed/remaining: `1/0/1`;
- live/activation/permit eligibility: false;
- permit: null;
- first readiness blocker: fresh interactive owned HWND unavailable.

## Test-only geometry

The manager65 fixture geometry is isolated under `TEST_ONLY_NONPROMOTABLE`. Its decimal scales are represented by authored test-only binary32 bits `3FA00000` and `3FC00000`; these are not claimed as captured live raw bytes.

The evaluator decodes those bits exactly, verifies the complete inverse client set, recomputes the prescribed midpoint, checks all nine 3x3 pixels, verifies forward logical replay, and verifies the exact 1x1 cell. None of these coordinates is emitted in the evaluation result.

The evaluator also parses the hash-bound manager65 capture and evaluation. It cross-checks the full client dimensions, identical A/B snapshots, unique command `0x2B` logical rectangle, captured scales, raw-bit decoding, candidate rectangle, safe point, forward logical point, run/status/source-capture chain, and externally supplied expected hashes. This closes the coordinated alternate-geometry weakness found in independent review round 1.

## Verification

```powershell
pwsh -NoProfile -File work/20260829-original-client-warp-external-binding-subject-v1/verify.ps1
```

Expected: 4 tests, 115 mutations, Draft 2020-12 PASS, six chain steps not created, test-only vector valid, no geometry escape, live subject false, point/digest/permit null, state-changing operations zero.

## Independent review

Round 1 returned `REVISE` because a coordinated alternate geometry could pass while retaining the real source paths and hashes. Round 2 reran that exact proof after semantic source binding was added. The contract was rejected with `TEST_ONLY_VECTOR_INVALID:ValueError:logical rect not bound to unique capture command 0x2B`, and the final verdict was `APPROVE` with no blocking findings.

This approval covers only the offline contract and test-only geometry verifier. It does not approve a live subject, WARP activation, packet, authority, persistence, pixels, both factions, Gate-A, Gate-B, or playability.
