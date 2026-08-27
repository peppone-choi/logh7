# Task 10 orphan and vertical-trace coverage report

## Outcome

The bounded coverage-gate implementation is `PASS`. The current game coverage result is intentionally `FAIL`: all 15,999 source rows are audited, but the complete feature/reachability ledger does not exist. This unit does not claim playable original-client behavior, complete reverse engineering, authority, persistence, both factions, Gate-A, or Gate-B.

## Implemented contract

- Preserved all full normalized source rows as deep immutable, bundle-bound `TraceGraph.source_rows` for reproducible nested audits without changing graph serialization hashes.
- Added deterministic `audit_graph`, canonical coverage serialization, strict live graph/bundle reload, component hashes, conservation, and atomic CLI publication.
- Separated structural fatals from evidence gaps. Explicit unknown/candidate/missing-source rows remain work items and never promote an evidence state.
- Froze feature, entity, and content vertical boundary sequences plus global missing-boundary order. Stored `firstMissingBoundary` is not trusted; current predicates derive `allMissingBoundaries` and its first member.
- Required evidence-linked proof, reason-bound N/A, exact writer-source graph identity, all eight implementation targets, and nested recovery dispositions.
- Added explicit LF checkout rules for the protocol and Task 10 artifact paths.

## Structural remediation

- Protocol: 547/547 rows now have exactly eight explicit implementation targets.
- UI: 422/422 rows now have exactly eight explicit implementation targets.
- Entity: 230/230 layout fields and 37/37 population claims now have their own closed recovery disposition.
- No original fact was invented by these mappings: implementation targets are requirements, and candidate/unknown entity material remains recoverable or authoring-required according to its provenance.

## Current audited population

- Rows: 15,999 = protocol 547 + UI 422 + entity 237 + resource 2,194 + function 11,497 + authority 1,102.
- Missing/extra/duplicate rows: 0/0/0.
- Row verdicts: 15,999 `UNKNOWN`; closed vertical traces: 0.
- Evidence gaps: 25,609; ordered missing-boundary references: 153,601.
- Structural fatals: exactly one, `FEATURE_REACHABILITY_LEDGER_ABSENT`.
- Audit command publishes the verified report and returns exit 1, because current coverage is honestly incomplete.

## Verification

- Focused coverage tests: 16/16 PASS.
- Complete exhaustive-trace suite: 214/214 PASS.
- Two full graph builds are byte-identical to the checked artifact: `6B520907EB7C22CB65FE4DA305D59F9C07F6357FDF5E0A249C8572CFE795F3C9`.
- Checked plus two independent coverage builds are byte-identical: `EAEECB3D6261AE1EBC9EAF9BF002527DE30BC0B3901083E3F75776A0600F24A4`.
- Coverage surface: `665FF9C360D48073A1907B2C8371F2535194FB270FB845CB610DAB89836B33D0`.
- Canonical UTF-8 LF, component hashes, strict graph/bundle/coverage loads, row conservation, and LF attributes pass.

## Independent review

The first two reviews rejected false-closure variants: empty feature paths, stale declared boundaries, reasonless N/A, signature-only writer proof, evidence-free `PROVEN`, dangling writer source IDs, and missing LF enforcement. Each finding was reproduced in a RED test and corrected. The final independent read-only review is `APPROVE`, with validator writes 0.

## Next start

Foundation Task 11 routes every audited row into the sixteen domain work packages. The feature ledger must be created as an explicit bounded unit; it must not be fabricated merely to make Task 10 exit zero.
