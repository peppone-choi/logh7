# Original character roster recovery and authoring boundary

<!-- CHARACTER_ROSTER_BOUNDARY_JSON
{"candidateStatisticRows":97,"datasets":{"authoredPlayableCharacters":"AUTHORING_REQUIRED","canonCandidateCharacters":"RECOVERABLE_STATIC","originalConfirmedCharacters":"RECOVERABLE_STATIC"},"decodedOGroupSlots":513,"legacyNamedRows":99,"officialNameFaceFacts":12,"portraitConflictDisposition":"SOURCE_CONFLICT","recordType":"CHARACTER_ROSTER_BOUNDARY","researchHistory":[{"evidence":["https://www.4gamer.net/games/010/G001079/20040227203229/"],"ordinal":1,"outcome":"PARTIAL_ORIGINAL_CHARACTER_EVIDENCE","performedAt":"2026-08-28","query":"Legend of Galactic Heroes VII character roster server source code","reason":"contemporary article confirms four special-slot original characters but not a complete roster or server source","scope":"original VII character roster and server-source search","stage":"GENERAL_WEB","status":"EVIDENCE_FOUND"},{"evidence":["https://www.4gamer.net/games/010/G001079/20040227203229/"],"ordinal":2,"outcome":"PARTIAL_ORIGINAL_CHARACTER_EVIDENCE","performedAt":"2026-08-28","query":"銀河英雄伝説VII キャラクター 一覧; 銀河英雄伝説VII サーバー ソース コード","reason":"Japanese contemporary source names four characters; no authenticated complete roster or server source was found in this search","scope":"銀河英雄伝説VII character and server-source search","stage":"JAPANESE_WEB","status":"EVIDENCE_FOUND"},{"evidence":[],"ordinal":3,"outcome":"ROSTER_EVIDENCE_MANIFEST_ABSENT","performedAt":"2026-08-28","query":null,"reason":"external candidate hashes are documented but are not bound by the greenfield source manifest","scope":"import official roster and strict portrait receipts into greenfield manifest","stage":"ORIGINAL_OFFICIAL_MANUAL_RUNTIME","status":"BLOCKED"},{"evidence":[],"ordinal":4,"outcome":"PENDING","performedAt":null,"query":null,"reason":"no user adjudication receipt exists for the roster fields","scope":"approve field-level original/candidate/authored roster decisions","stage":"USER_ADJUDICATION","status":"NOT_ATTEMPTED"},{"evidence":[],"ordinal":5,"outcome":"PENDING","performedAt":null,"query":null,"reason":"research and approval remain incomplete","scope":"author complete playable roster after research and user adjudication","stage":"AUTHORED_REPLACEMENT","status":"NOT_ATTEMPTED"}],"schemaVersion":1,"stalePlanConfirmedPortraitMappings":2,"strictConfirmedPortraitMappings":1,"survivingOfficialPortraitReferences":2,"usableOGroupSlots":397}
-->

## Status and claim limit

This document defines the Task 13 boundary. It does not publish a recovered original roster. The current greenfield source manifest contains no character-roster dataset, so the three required datasets remain separate goal-required recovery subjects.

- `originalConfirmedCharacters`: `RECOVERABLE_STATIC`, current dataset population `0` in this monorepo.
- `canonCandidateCharacters`: `RECOVERABLE_STATIC`, current dataset population `0` in this monorepo.
- `authoredPlayableCharacters`: `AUTHORING_REQUIRED`, current approved population `0`.
- All numeric legacy observations below are external candidate-source observations. They are not original-VII facts until imported through a new hash-bound roster evidence manifest and independently reproduced.

## Current local candidate-source observations

The following files live in `E:\logh7-revival`, outside the authoritative greenfield monorepo. They may be used only as `LEGACY_CANDIDATE` inputs.

| Observation | External candidate path | SHA-256 | Result and limitation |
|---|---|---|---|
| Mixed roster | `E:\logh7-revival\server\content\character-roster.json` | `75E2D437D9E2AB7BB76F97B9B3E8DB211FAEEF6256FA27AC0D003469D116CE6C` | 99 named rows; metadata reports 97 with candidate statistics and 2 without. This mixes manual, IV EX, partial official-site and community inputs. |
| Candidate statistics | `E:\logh7-revival\server\content\roster\characters.json` | `BF8FAD5608FA1F8DE72D2CA5DB0A9D1336F686F178E840E9D7BFA43C1CECCCC3` | 97 rows: manual 51, `ivex-real` 38, `ivex` 7, canon 1. These are not VII-observed individual statistics. |
| Official manual candidates | `E:\logh7-revival\server\content\roster\manual-roster.json` | `636CBED254B8BE45620B254193C25D70E9CABABC9D1EB8B5B6A3AB2EB7E71DFE` | 36 Empire and 39 Alliance duty-card holders. Names/posts/ranks may be field-level manual facts; numeric statistics and portraits are not implied. |
| IV EX statistics | `E:\logh7-revival\server\content\roster\ivex-stats.json` | `92714B23F7EF223E0D5B11FDAEC6087D1967A00FDD6BA3EE055E87E00D9997B9` | 181 adjacent-game candidates only. |
| IV EX reference | `E:\logh7-revival\server\content\roster\ivex-reference.json` | `F50E5134ABA09651FA42A8B3FA959454D307ACFEFF9B24A4BEEB6241D50D5BF9` | 181 adjacent-game candidates only. |
| Community sweep | `E:\logh7-revival\server\content\roster\community-roster.json` | `B28BEEDE0047A9202D2D4843179BF8CF3229CA962210B48AE4A66E4CA2FBAC83` | No new official face/name pair and no new VII name; community process evidence only. |

The 99/97 figures are therefore retained as `LEGACY_CANDIDATE_COUNT`, not as recovered roster cardinality or original statistics.

## Official VII name-to-face-number facts

The archived official page candidate `E:\logh7-revival\artifacts\gineiden-archive\files\www.gineiden.com\st_char.html` has SHA-256 `76BADEF1A14F981089F30B7DFC99DD84A148839CB496BC1972AEDC42BC88AB4C`. It directly references twelve face numbers: `041`, `048`, `069`, `085`, `125`, `195`, `206`, `209`, `268`, `270`, `285`, `286`.

The normalized external candidate `official-roster.json`, SHA-256 `8E8DE33CAC1B02102C898F76A3A98E36E123777D148A12F753DAB694C2A7838F`, records twelve partial official-page facts: Reinhard 209, Mittermeyer 195, Kessler 069, Friedrich IV 270, Ovlesser 041, Remscheid 286, Yang 206, Cazerne 048, Schenkopp 085, Trunicht 125, Negroponty 268 and Lebello 285.

These facts establish only fields directly present on the archived official page. A name-to-face-number fact does not prove numeric statistics, full roster membership, atlas slot, portrait pixels, initial office, unit, location or authority state. The historical `face-name-map.json` SHA-256 `D16DE6F6A8F2D5C8458778B61C94C0FFB9A33FA400F3C1D6380F79BE05C3A17B` is deprecated for atlas identity because an official face number is not a decoded TCF slot.

## Portrait evidence correction

The foundation plan says “2 pixel-confirmed official portrait mappings.” Current stricter evidence contradicts that assertion.

- Two official portrait references survive: `085.jpg` SHA-256 `2DA727658E07205C4C5424C687F39AE34749DBE647D6C310054CE108191E2244` and `206.jpg` SHA-256 `6CBF6B1889A5CD8EB965B9BA298B5EE45CA9B2038A8F0ED2171A633CD9444A72`.
- The current strict result `E:\logh7-revival\content\verified\portrait-identities-verified.json`, SHA-256 `4FAC1C0B39700716BEB3522EEE6E6E05938AC2E6BFA1985B6A72DBC2BC2E8C2D`, confirms only Yang official face 206 to `oam/0274`: NCC `0.9175`, runner-up `0.7278`, gap `0.1898` under threshold `>=0.85` and minimum gap `>=0.1`.
- Schenkopp face 085 remains `UNCONFIRMED`: best `0.5995`, gap `0.0011`.
- The older file `portrait-identities.json`, SHA-256 `9B316399F2771E95DBF21C276B6CF8C4EAD7CFB6D5C2494C62800207B4E8F475`, claimed two mappings under a weaker process and is not promoted over the strict result.

Task 13 therefore records `survivingOfficialPortraitReferences=2`, `strictConfirmedPortraitMappings=1`, and a stale-plan source conflict. It does not fabricate a second confirmed mapping.

## O-group portrait boundary

- External decoded catalog `E:\\logh7-revival\\server\\content\\generated\\logh7-face-portrait-catalog.json`, SHA-256 `F38484C5AB0388FEE1CD0D4937F79233DE32D7A6D7EEC2B43DDE57E435E069B4`, contains 513 O-group slots: `o.tcf=92`, `oam=220`, `oem=201`.
- External runtime-safe pool `E:\\logh7-revival\\server\\content\\generated\\logh7-face-valid-pool.json`, SHA-256 `952A6B84BAF682DCA3FD7F761982A68E5BF50501FA869B53564D5C88CD39484B`, contains 397 slots: `o=100`, `oam=96`, `oem=201`.
- These denominators are different. Under the current strict evidence, 512 of 513 decoded slots lack a strict name binding; within the usable pool, 396 of 397 lack one. Duplicate/rejected/historical guesses require their own statuses and may not be silently counted as confirmed.

## Dataset contracts

### `originalConfirmedCharacters`

Each character record has a stable `characterKey`. Every field is a fact object with `value`, `provenance`, `evidenceRefs`, `confidence` and `conflicts`. Only `ORIGINAL_OBSERVED` or `ORIGINAL_MANUAL` field facts supported by VII-specific evidence are allowed. A portrait binding additionally requires official image hash/crop, decoded atlas hash/slot, algorithm and threshold, score, runner-up, gap, verdict and independent-review evidence. Visual similarity, names or slot order alone never bind identity.

### `canonCandidateCharacters`

Each candidate retains `candidateKey`, names, faction, candidate statistics, source row, mapping method, confidence, conflicts and optional proposed confirmed key. `IV_EX`, `CANON`, `COMMUNITY` and `MIXED_LEGACY` stay `LEGACY_CANDIDATE`/`UNADJUDICATED`; they cannot promote the confirmed dataset or coverage states.

### `authoredPlayableCharacters`

Each editable record has `authoredCharacterId`, optional confirmed/candidate references and field-level origin. Every field is either an exact referenced recovered fact or is explicitly `NEW_DESIGN`/`AUTHORED_PLACEHOLDER`. Anonymous portrait assignments keep `identityConfirmed=false`. Approval owner is the user; implementation owner is `CONTENT_ADMIN`; the initial approval state is `DRAFT`.

### `portraitSlotLedger`

Each slot records atlas, slot, image hash, decoded/usable pool membership and identity status `CONFIRMED`, `UNRESOLVED`, `DUPLICATE` or `REJECTED`. `confirmedCharacterKey` is permitted only for `CONFIRMED` with the strict evidence bundle. Rights/distribution status remains separate from identity provenance.

## Research order and current receipts

The required order is the conservative union: `GENERAL_WEB -> JAPANESE_WEB -> ORIGINAL_OFFICIAL_MANUAL_RUNTIME -> USER_ADJUDICATION -> AUTHORED_REPLACEMENT`.

- General/Japanese web searches on 2026-08-28 found contemporary VII descriptions, including [4Gamer's closed-beta article](https://www.4gamer.net/games/010/G001079/20040227203229/), which names four special-slot original characters. This corroborates that original-character play existed, but it does not establish a complete roster or statistics.
- The same search did not produce an authenticated original server source or a complete official roster. This is a search result, not proof of global nonexistence.
- Official/manual/runtime sources and the external candidates above remain to be imported through a greenfield roster evidence manifest.
- User adjudication and authored replacement are `NOT_ATTEMPTED`; no inferred note is a user decision.

## Next evidence artifact

Before any 99/97/12/1 count becomes a generated greenfield dataset claim, create a roster evidence manifest inside this monorepo that binds every source path and byte hash, the count algorithm and unique keys, official HTML encoding/locator, raw portrait images, decoded atlas/slot images, matching script and thresholds, and independent review. Until then the three Task 13 dataset subjects remain unresolved and non-promoting.
