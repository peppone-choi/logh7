# Independent read-only review

- reviewer task: `/root/collector_review`
- disposition: `APPROVE`
- files modified by reviewer: none
- verifier command: `pwsh -NoProfile -File work/20260828-original-client-destination-confirm-collector/evidence/verify-destination-confirm-stage-collectors.ps1`
- verifier result: `PASS`
- final same-host and separate `pwsh -NoProfile -NonInteractive -File` executions both produced byte-identical canonical receipts: 1,702 bytes, no BOM, zero CR bytes, 42 LF bytes, final LF present, SHA-256 `17AF17ED2E6A8821F83AE76E213AF1A77867CD26C4159B4BAB52B849256ADFBA`.

The reviewer independently confirmed:

- destination 6 cases / 24 mechanically counted assertions;
- confirmation 5 cases / 25 mechanically counted assertions;
- nonzero destination result-state rejection;
- noncanonical expected-hash rejection before process lookup in both collectors;
- exact ledger hashes for both collectors, exporter, static export, both tests, and all three fixtures;
- canonical target hash, fresh PID/start/hash/HWND-owner/MainWindowHandle checks and read-only native capability surface;
- corrected separation of the heap `SelectGrid` flow object from global selection-controller state;
- fail-closed `UNBOUND`/`UNSEEN`, permit false, and live-operations zero claims.
