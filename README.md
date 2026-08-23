# LOGH7 Greenfield

`LOGH7 Greenfield` is a clean reimplementation of the discontinued 2004 online strategy and tactical game client and its missing server. The repository does not reuse any prior LOGH7 implementation. It treats the archived CD and official manual as primary evidence, imports user-supplied legacy data at runtime, and keeps authored Korean localization and newly implemented systems separate from observed legacy behavior.

## Repository boundaries

- `apps/client`: C++23 client and engine.
- `apps/server`: authoritative C# server.
- `apps/admin`: authenticated Windows server administration tool.
- `contracts`: versioned network and domain contracts.
- `libs/legacy`: legacy resource decoders and immutable source manifests.
- `db`: PostgreSQL migrations and editable seed data.
- `tools`: import, reverse-engineering, and evidence tooling.
- `infra`: Docker Compose and local operations.
- `qa`: VMware multi-client scenarios and manual QA receipts.
- `docs`: architecture, provenance, goals, and evidence.

Legacy game files, database volumes, VM disks, packet captures, secrets, and generated builds are intentionally excluded from Git.
