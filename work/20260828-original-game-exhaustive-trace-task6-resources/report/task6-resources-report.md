# Exhaustive trace foundation Task 6 report

## Verdict

`PASS` for the bounded static resource inventory and reconciliation. The overall reimplementation goal remains `INCOMPLETE`; actual file-open success, decode, cache ownership, model selection, GPU/audio submission, visible HUD pixels, audible playback, original-client playability, and authoritative world delivery remain `UNSEEN` or `UNKNOWN`.

## Closed resource surface

The original InstallShield payload contributes exactly 2,192 `TREE_FILE` rows totaling 321,132,163 bytes. Manifest, current tree, and inventory are one-to-one by case-insensitive path, size, and SHA-256. No patch-overlay file is merged. Every original file remains `USER_OWNED_LOCAL_ONLY`.

The hash-fixed client independently yields 806 maximal printable resource-string occurrences: 798 literal candidates and eight unresolved formatters. It also yields 924 initialized aligned pointer-cell candidates and 1,860 Ghidra references, for 2,784 conservative loader-reference candidates. Exact literal/path matching covers 673 distinct files; 1,519 files have no compiled exact literal match. A string, XREF, or pointer is not promoted to a proven loader.

The source tree contains no font file. Instead of inventing one, the inventory adds two separate `EXTERNAL_DEPENDENCY` rows for the frozen PE imports `GDI32.DLL::CreateFontA` and `OLEPRO32.DLL::OleCreateFontIndirect`. They remain OS-provided candidates with unknown runtime font selection.

## Dispositions and conservation

The normalized inventory has 2,194 rows: 2,192 original files plus two external font dependencies. All are `ENUMERATED_ONLY`, reachability `UNKNOWN`, and have only the `ENUMERATED` evidence state. No row is called integrated, dormant, shipped-reachable, player-visible, or player-audible.

The raw plus tree surface contains 5,784 unique candidate IDs. Reconciliation represents 5,384 and retains 400 as explicit `UNRESOLVED`; excluded and unaccounted counts are zero. All rows carry all eight implementation targets. The importer refuses candidate presentation receipts, receipt-only state promotion without a loader, incomplete `PROVEN` runtime keys, incomplete `PROVEN` owners, dangling path/submission joins, runtime pointers as stable owners, unsafe paths, and case-fold collisions.

The 44 `data/image/spot/*.jpg` files remain background assets only and are not converted into 44 gameplay spots. Likewise, seven TCF files are not treated as seven character portraits, resource counts are not entity populations, and a flagship filename is not a ship-class identity.

## Reproducibility and limits

Two final `-readOnly` Ghidra exports are byte-identical at `CA05628995627EA0F21B400367CDFD5745A765987775579E2DF8D2195C593AC7`; the semantic program database hash remained `888F81BFEAB5B878723345CE7B049E709A4E3DE438F72005E1F68F9A100000AF`. Two importer reproductions match inventory `42DD5300048848DA2D43D80C036B85AA0AED9F4D0EC23740B630A28044FEF405` and reconciliation `733EEA67581052C4CB8B1FCDB92F615D1C38151B1206C628BF759A1ECC25DD24`.

Focused tests pass 22/22 and all exhaustive-trace tests pass 115/115. This unit provides the closed static resource surface needed by later function and vertical-trace units; it does not itself repair the original client, prove HUD text, select the correct flagship, restore system/planet delivery, or implement commands and two-faction play.

Three final independent read-only reviews - contract, Ghidra/static provenance, and source/tree/rights - returned `APPROVE`; reviewer writes were zero.
