# Timeline

## 2026-08-29 - unit start

- `decision_delta`: selected mechanical first recovery unit `RECOVERY:D01:RESOURCE_LOADER:CF0262816AEED6F7`.
- Confirmed the target at 124 bytes and SHA-256 `EBB093A34852454DD8D15CA14E95804D9200416B8724CD4F445770B07C17EF7C`.
- Exact ASCII/CP932/UTF-8 bytes contain one `[UPDATE]` section, seven CRLF records, `VERSION=131`, `BASE_DIR=.\\`, empty TEMP/STARTUP/WORK fields, and `LAST_ERROR=0x00000003`.
- The updater has RT_STRING id 3 `%supdate.ini`; exact loader/path construction remains under static callflow analysis.
- Historical installed-tree evidence records the same path at a different SHA-256 `F89660546D6D0C7D4A00EFDCAA73E5120916C730E07E6CCFE7D8FF111FD71A88` after updater execution. Do not substitute it for the original extracted target.
- `carry_forward_refs`: `scope.md`, `docs/handoffs/2026-08-29-gin7updateclient-resource-loader.md`, `docs/handoffs/2026-08-24-original-client-install-handoff.md`, `report/manual.md`, `report/mistakes.md`.

## 2026-08-29 - static closure and publication

- Exact path: `GetModuleFileNameA` module directory -> RT_STRING ID 3 `%supdate.ini` -> `FUN_00433E1C` at `0x00404BA9` -> app `+0xD8` -> reader initialization at `0x00404BB7`.
- Reads: `GetPrivateProfileIntA` at `0x00404E13`; shared `GetPrivateProfileStringA` helper API call at `0x00404FF7` for server, proxy, base/temp, startup, and work keys.
- Writes: `WritePrivateProfileStringA` at `0x0040508F` for VERSION and at `0x00406F24` for LAST_ERROR.
- Added closed `EXTERNAL_PE_INI_CONFIGURATION` adjudication and receipt. No `loader.functions` or G7 function node was introduced.
- Graph: updater -> INI has one `READS` and one `WRITES` semantic edge, zero `LOADS` edges.
- Normalized row: loader `PROVEN`; usage `ORPHAN`; first missing `RUNTIME_OWNER`; only `ENUMERATED=true`.
- Full verifier: 299/299 tests, 32 deterministic artifacts, 109 protected inputs, two fresh roots, unchanged server/contracts/database trees.
- Aggregate: rows 15,999; nodes 35,686; edges 92,058; gaps 25,602; missing occurrences 153,594; all 15,999 verdicts `UNKNOWN`; closed traces 0; fatal exactly `FEATURE_REACHABILITY_LEDGER_ABSENT`.
- Author receipt SHA-256: `EF3858AF14BDBDC13B02C58B3C52D84A5C366F58BD278EE9E005578442012AFE`.
- Next mechanical unit: `RECOVERY:D01:RESOURCE_OWNER:9D646F35949A6258` for the official-site Internet Shortcut row.
