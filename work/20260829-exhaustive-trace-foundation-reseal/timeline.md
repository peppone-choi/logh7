# Timeline

- 2026-08-29: Read current goal, operating manual, mistakes ledger, plan, generated artifacts, and live worktree state.
- 2026-08-29: Reproduced the source-manifest boundary: two repository database versions caused 2 failures and 15 errors in the 280-test suite.
- 2026-08-29: Added RED tests for exact safe relative program-database binding.
- 2026-08-29: Implemented manifest-selected database hashing without deleting either repository version.
- 2026-08-29: Source-manifest tests passed 18/18; full exhaustive-trace suite passed 283/283.
- 2026-08-29: Independent lineage audit identified `db.2.gbf` as the revision saved by a diagnostic `PrintAt.java` run that omitted `-readOnly`; `db.1.gbf` remains the exact manifest authority.
- 2026-08-29: Added missing-path and invalid-name negatives; source-manifest tests passed 20/20.
- 2026-08-29: First explicit aggregate receipt path was correctly rejected because it was not a direct child of the real temporary root.
- 2026-08-29: Traced source-manifest hash propagation through resource/function raw exports, evidence manifests, and resource adjudications. Two staging-only attempts stopped before publication at the first unpropagated binding.
- 2026-08-29: Staging-only complete regeneration passed with 15,999 rows, 35,686 nodes, 92,055 edges, 25,606 gaps, 153,598 missing-boundary occurrences, and the single expected feature-ledger fatal.
- 2026-08-29: Published only the validated staging artifacts to the checked baseline.
- 2026-08-29: A pre-commit EOL audit found the patched source manifest had mixed CRLF/LF bytes. Normalized it to required LF, regenerated and republished the complete provenance chain, and discarded the pre-normalization candidate receipt.
- 2026-08-29: Final aggregate verifier passed 285/285 tests and reproduced all 32 checked artifacts byte-identically from two fresh roots. Receipt SHA-256: `2ECF55C94C0D1FBF1D43BD9B9F021B9F76C6122CA7A72386BEDB715E539E9864`.
- 2026-08-29: Independent code/path-safety and metrics/document reviews returned `APPROVE`; a separate final-state full verifier execution passed once with receipt SHA-256 `A629DC9BB0758312B563C523A73660EC4E179CF979646A9FD20CE8B91BCB7FA1`. Review index SHA-256: `8312104E52CABF205C42DDB62D74E3F4AA1C7A820E220273EF6DB1517DF483A4`.
