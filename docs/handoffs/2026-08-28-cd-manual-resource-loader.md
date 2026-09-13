# Handoff: D01 CD operation-manual RESOURCE_LOADER

## Completed unit

- `RECOVERY:D01:RESOURCE_LOADER:E8B07A6802B17EEF`
- bounded status: `PASS`
- overall status: `INCOMPLETE / STRUCTURAL_FATAL`

The exact original file `銀英伝７マニュアル.pdf` is a 69-page BOTHTEC operation manual. Its G7MTClient runtime asset-loader boundary is `NOT_APPLICABLE`. The real CD launcher behavior is preserved separately as `G7Start.exe OPENS_DOCUMENT manual.pdf` through `ShellExecuteA`; it is not modeled as `LOADS`.

## Evidence and verification

- PDF SHA-256: `1C4CF3DB13A172361277264C06ADA6E2499BE0969494C6557EB84BC4CC005399`
- G7Start SHA-256: `1023C4A045F184BF76CA84AB603E0C03DB989799F02B701BF8DD89B21EA78F93`
- inspector SHA-256: `4F55234F6BDF22A2AA24C99818CD0D064E35E9F878560B37420EA4879C3EC1CC`
- static receipt SHA-256: `16B20FDEB11539BF2B434F55B78629102282750A01432DD485C90426F74A42B2`
- adjudications SHA-256: `023FE64A893BD5F51D025220953D81DEAD013357B6B7E41D4C425E2BEB4DBBA7`
- resource inventory SHA-256: `A1DDAAF70CE5BDED0B8D6E82A1147EE27A6EBF33BD3B5BDFB1C1E980524E2FE7`
- graph SHA-256: `05771703EEB797301C46C412476267CD6A6C7492B960E211EF04392AAFC7092F`
- coverage SHA-256: `2D1EF1E97072E0A9DDBD1E802975955DC10E9BFF696D26A6E0632AA1D51D7AE7`
- work-package SHA-256: `28BB966D276DC342A473D24AB682E3D4E322805E932B2FA5B62FF9A4DEB4951F`
- recovery SHA-256: `F43295C69F2A4A85E006F8D9CB396045DFEEB2B4AB2661996904C86CCD0FAE77`
- aggregate receipt SHA-256: `187F17E7978F9ACF24BC1F73AE13F1C499977C54074F2E12D74E43F6BCDA6235`
- independent receipt SHA-256: `A5986499541DDEF97C1923EDE0F0C23539E76126406F98A49E2D226BAEE6F088`
- independent verdict: `APPROVE`
- tests: 280/280 PASS
- deterministic artifacts: 32/32 matched
- protected inputs: 95/95 unchanged
- fatal: exactly `FEATURE_REACHABILITY_LEDGER_ABSENT`
- evidence gaps: 25,606

## State transition

- old unit count: 0
- target loader: `NOT_APPLICABLE`
- format: `PROVEN / PDF_1_4 / HASH_BOUND_PDF_ANALYSIS`
- document role: `ORIGINAL_OPERATION_MANUAL`
- exact relation: `ORIGINAL_CD_ARTIFACT:G7START.EXE OPENS_DOCUMENT target PDF`
- target next boundary: `RESOURCE_OWNER`
- target next unit: `RECOVERY:D01:RESOURCE_OWNER:0F8AD043BA655644`
- global next unit: `RECOVERY:D01:RESOURCE_LOADER:F9CBE1F4AEAE7D6B`
- global next path: `RESOURCE:FILE:original-installshield-payload:doc/___p_`vii___p_k__.txt`

## Next start

Start one newly authorized bounded unit only. Deterministic ordering selects the TXT documentation loader adjudication. If original playability is reprioritized above deterministic inventory order, select a separately authorized vertical login-to-world-to-first-command unit rather than calling this documentation closure gameplay progress.

## Forbidden retries and promotions

- Do not treat arbitrary `.pdf` extension matches as operation manuals or loader N/A.
- Do not emit `LOADS` from `G7Start.exe`; the proven relation is external `OPENS_DOCUMENT`.
- Do not claim a VM user clicked the PDF or that the installed shell object existed at runtime.
- Do not treat Poppler font substitution as pixel-identical original rendering.
- Do not promote `STATIC_MAPPED`, `RUNTIME_OBSERVED`, `PLAYER_VISIBLE`, gameplay, authority, persistence, both factions, Gate-A, or Gate-B.
- Do not use manual content alone as proof that a described gameplay function was shipped or implemented.
