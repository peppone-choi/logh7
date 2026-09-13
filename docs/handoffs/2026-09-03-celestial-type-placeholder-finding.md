# Handoff: condition 5 (celestial types) — server-side cause of the "two identical blue objects" found

Date: 2026-09-03. Lane: 성계·천체·모델·렌더 (goal step 7, condition 5). Static analysis of the authority
(`natural-authority-d02/apps/server/Logh7.Server/OriginalGateway/`).

## Finding

The strategy screen shows two blue objects in the starting system. The goal states these are NOT two
identical planets — one should be a blue SUN, one a planet — and warns against "replicating one number onto
every object". The server-side cause is confirmed:

- `OriginalWorldBootstrapCodec.EncodeStaticGrid()` (opcode 0x0315) writes the system's cells with **two
  adjacent cells pointing at the SAME authored planet palette entry** (`PlanetMarker = 3`). Its own comment:
  "exactly two adjacent grid cells select the same authored planet palette entry … AUTHORED_PLACEHOLDER /
  NEW DESIGN … This is one scene only."
- `EncodeStaticGridTypes()` (0x0313) defines the palette record `[contentId, klass, variant]` for marker 3 as
  `PlanetContentId / PlanetKlass=3 / PlanetVariant=0` (`OriginalAuthoredPlayableCatalog`). Client renderer
  path `FUN_004D3BD0` accepts klass 3, variants 0..6 (planet variants).
- So both blue objects are the SAME type (planet, klass 3, variant 0). No sun/fortress/black-hole/neutron
  typing exists yet; it is a single hardcoded placeholder scene, not per-system real data.

## Condition 5 status and next unit

STATIC_MAPPED (cause identified), not satisfied. To advance condition 5:
1. Identify the client renderer's type discriminator for a SUN (and fortress/black-hole/neutron): which
   `klass`/`variant` (or marker) the client maps to `space/s###` (sun) vs `planets/p###` etc. Trace
   `FUN_004D3BD0` / the palette consumer and the `space/`/`planets/`/`strategy/bh_*` resource families.
2. In `EncodeStaticGridTypes`/`EncodeStaticGrid`, give the two cells DIFFERENT palette entries (one sun-klass,
   one planet-klass) so the scene shows a blue sun + a planet, then VNC-verify the two objects render as
   distinct types.
3. Then replace the single placeholder scene with per-system real celestial lists (80 systems / 281 planets /
   6 fortresses + special types) with correct type/model/orbit, wired from server data — the full condition 5
   scope (large, multi-session), joined to `0x031C → 0x031D` scene/base requests.

This is a large lane; this session established the exact server-side placeholder that must be replaced.

## Celestial resource families confirmed (static, client item1)

Client resource path format strings (in `.rdata` ~0x371f00-0x3728a0) confirm the type↔model families the
renderer selects, matching the goal's noted families:
- `data/model/space/s###.mdx` — **stars / suns** (fixed files s003..s006 = colour/grade variants incl. the blue sun).
- `data/model/planets/p%03d_low.mdx` — **planets** (klass 3, the current placeholder type).
- `data/model/planets/fs%03d_low.mdx` — **fortresses** (fs = fortress).
- `data/model/strategy/bh_*.mdx|.bmp|.tga` — **black hole** (bh_light, bh_flare, bh_moya, bh_wave core/wave effects).

So the type discriminator the authority must drive is which family the client picks per object. Currently
`EncodeStaticGridTypes`/`EncodeStaticGrid` only ever emit the planet family (marker 3 / klass 3 / variant 0)
for both cells → two identical planets. Next static step: trace the code that references
`planets/p%03d_low.mdx` (`.rdata` off 0x3720e6) and `space/s###` to find the klass/marker value that selects
the `space/s` (sun) family vs `planets/p` (planet); then the authority can emit one sun-typed cell + one
planet cell for the starting system and VNC-verify a blue sun + a planet. Full per-system real celestial data
(80 systems / 281 planets / 6 fortresses + special types, wired to 0x031C→0x031D) remains the large scope.

## Render-path structure (static): grid-marker bodies vs separate star

The client resource strings `planets/p%03d_low.mdx` (`0x4d464d`), `planets/fs%03d_low.mdx` (`0x4d477f`) and
`strategy/bh_wave.mdx` (`0x4d4812`) are all referenced from ONE selector function (~0x4d44xx–0x4d485x) whose
branches key off grid coordinates/markers (cmp with 0x65/0x33/0x50/7 and a per-cell marker byte
`[esp+esi+0x24]`). So **planet / fortress / black-hole** bodies are chosen per grid CELL (the 0x0315 marker
palette). The **sun** string family `space/s###.mdx` has NO immediate xref here — the star is drawn by a
separate path (system-centre star, likely the `0x031C` EncodeStaticBases scene / a sprintf'd `space/s%03d`),
NOT via a grid-cell marker.

Consequence for condition 5: the authority cannot make "one of the two blue grid objects a sun" by changing a
grid marker — grid markers only pick planet/fortress/black-hole. A sun is a distinct system-centre object. So
the two blue objects on the current scene are two grid PLANETS (both marker 3); a real system needs (a) the
central star emitted on the star path and (b) planets/fortresses/black-holes as distinct grid markers.

Next static unit: read `EncodeStaticBases` (0x031C) body and the client star-render path (find the
`space/s%03d` sprintf and its caller) to learn how the central star's type/model index is carried, then emit
a real system = 1 star (typed) + planet/fortress/black-hole cells, and VNC-verify a blue sun distinct from a
planet. Full 80-system data remains the large scope.

## 0x031C bases = orbiting bodies (placeholder); star is a separate centre object

`EncodeStaticBases` (0x031C→0x031D) emits ONE base record via `Input_ResponseStaticInformationBase::
input_from_stream` (FUN_004142E0, up to 350 records): `[baseId, gridCell, u16, u16, name(pstr16), klass,
revolutionRadius(f32), revolutionCycle(u32), revolutionDirection(u8), revolutionInitAngle(f32), radius(f32)]`
— i.e. an ORBITING body with orbit params (FUN_00425C20 labels them). It is a single NEW_DESIGN/AUTHORED
placeholder (BaseKlass, one Base 1), previously all-zero = NO DATA.

So condition 5's data spans at least three mechanisms, now statically mapped:
1. grid-cell markers (0x0315/0x0313) → planet / fortress / black-hole bodies on the board;
2. 0x031C bases → orbiting bodies with orbit params (klass-typed), currently 1 placeholder;
3. the system CENTRE STAR (sun, `space/s###`) — an orbit-less centre object on a separate render path, not
   in the grid markers and not an 0x031C orbiting base.

Full condition 5 requires, per system: the centre star (typed incl. blue sun), the orbiting bodies (0x031C,
klass-typed, real orbits), and grid markers (planet/fortress/black-hole), all from real per-system data (80
systems / 281 planets / 6 fortresses + special types), wired via 0x031C→0x031D and the scene requests. This
session mapped the mechanisms and the placeholders that must be replaced; the implementation is the large lane.

## The p/fs/bh xref function is a startup PRELOADER, not the type discriminator

`FUN_~0x4d44xx` sprintf-loops the resource families (`planets/p%03d_low.mdx`, `planets/fs%03d_low.mdx`, and 7
`strategy/bh_*` elements) to PRELOAD all celestial models/textures at startup — it is not the per-object type
selector. The runtime type→family mapping (which klass/variant a scene object uses → which preloaded model)
lives in the render dispatch (planet path FUN_004D3BD0 accepts klass 3, variants 0..6).

Condition 5 next static step (unchanged scope, now sharper): read the render dispatch that consumes a scene
object's klass/variant and picks planet vs fortress vs black-hole vs the centre-star path, to learn the klass
values for sun/fortress/black-hole. Then the authority can emit, for the starting system, a typed centre star
(blue sun) + differently-typed grid/orbit objects, and VNC-verify distinct celestial types. Implementation +
per-system real data (80 systems / 281 planets / 6 fortresses + specials) is the large multi-session lane.

## FUN_004D3BD0 is scene-init, not the klass dispatch — klass→family needs deeper RE

`FUN_004D3BD0` turns out to be the large scene-render INITIALIZER (many subsystem init calls, resource-load
loops with 0x20-sized entries, `0x7721dc` resource string), not a compact klass→family switch. So the exact
klass values for sun / fortress / black-hole are not read from a single branch here; they need either deeper
RE of the scene object consumer, or an EMPIRICAL sweep: emit a grid/base object with successive klass/variant
values from the authority and VNC-observe which family each renders (safe: server-data change + read-only
capture, one run per value). That empirical sweep is the most tractable path to the sun klass, then the
authority can place a typed blue sun + a distinct planet for the starting system. Implementation + per-system
real data remain the large multi-session lane. This session mapped the full render/data structure and the
placeholders to replace; no klass value is asserted without that verification (no guess-forcing).

## klass sweep experiment (run 20260902T191955Z) — grid palette klass is NOT the type selector

Built an experiment authority (scratch, env-controlled marker-3 palette klass/variant via
`LOGH7_CELESTIAL_KLASS`/`_VARIANT`, wired through `-CelestialKlass`/`-CelestialVariant`; dll `0973BE4D…`,
zip `F0FDBC98…`) and ran with `-CelestialKlass 4`. Live strategy screen (`vnc-c-strategy.png`): the two blue
grid objects DISAPPEARED — with klass 4 the grid renders EMPTY (klass 3 shows the two blue planets). So the
grid-palette klass is not a celestial-type selector: only klass 3 (planet, `FUN_004D3BD0` accepts klass 3
variants 0..6) renders a grid body; other klass values render nothing. Fortress/black-hole/sun are therefore
NOT reachable by sweeping the grid palette klass — they use a different mechanism (grid-marker special
handling and/or the separate centre-star path), consistent with the earlier finding that the p/fs/bh selector
keys off grid coordinates/markers, not a palette klass.

Condition 5 path update: klass-sweep on the grid palette is EXCLUDED for typing sun/fortress/black-hole. Next:
(a) variant sweep within klass 3 (0..6) to enumerate planet model variants (safe, same rig); (b) reverse the
grid-marker selector (`~0x4d44xx`, cmp with 0x50/7 and the per-cell marker byte) to find the marker values
that pick planets/fs (fortress) and strategy/bh (black hole); (c) the centre star (sun, space/s###) via its
own path. Experiment rig (`-CelestialKlass/-CelestialVariant`, scratch dll `0973BE4D…`) is reusable for the
variant sweep. Run cleaned; leftover none.

## Grid marker lookup fully decoded: FUN_004C8B70 + its 7 callers (static, client item1)

Decoded the grid cell → record resolver and every consumer, which pins the exact byte layout and REVISES the
klass-sweep conclusion into a precise, actionable model.

`FUN_004C8B70(x, y)` (0x4c8b70):
- Bounds: `x` in 0..99, `y` in 0..49 → a **100×50 grid** (5000 cells).
- Per-cell **marker byte** at `base + y*100 + x + 0x2c03cc`, where `base = *(0x7ccffc)`. The 5000-byte span
  matches the second function's `0x1388` loop bound exactly.
- Returns pointer to a **3-byte record** at `base + marker*3 + 0x2c1755` (stride 3), or the marker=0 record.
  Marker 0/1/2 are reserved; content markers are 3..0x58 (per the reverse-index builder at 0x4c8bc0).
- **Record layout = [byte0 contentId, byte1 klass, byte2 variant].** Confirmed by the consumers below.

Consumers (all 7 callers):
- **0x4d35b0** (`byte1` accessor): returns `record[1]` (klass) for a cell, or -1 if empty. Canonical "what
  klass is here".
- **0x4d3a75**: render loop gated `record[1] == 0`; reads `record[0]` (contentId) → `FUN_004C8C90(contentId)`;
  stores into a 0x28-stride object array. → the **klass-0 layer**.
- **0x4d4117**: render loop gated `record[1] == 3`; reads `record[0]` → `FUN_004C8C90` → geometry via
  `FUN_004D35E0`. → the **klass-3 (planet) content resolver**.
- **0x4d4093**: gated `record[1] == 3`; reads `record[2]` as the **model-variant selector**: values 0..6 used
  directly as model index; value 8 → index 7; anything else → empty. So klass-3 planets have 8 model slots.
- **0x58f1d7**: `sete` on `record[1] == 3` (planet-vs-nonplanet flag in a later pipeline stage).
- **0x57ae25 / 0x58d20a**: later pipeline stages (no direct `byte1` gate here).

### Why klass=4 emptied the grid, and the corrected condition-5 direction
The grid render loops only branch on `record[1] == 0` and `record[1] == 3`. Setting the marker-3 palette
klass to 4 (prior experiment) fell through both `jne` gates → nothing drawn. This is not "klass is not a
selector"; it is that **the 0x0315 grid path only renders two layers: klass-0 and klass-3 (planets)**.
Therefore **sun / fortress / black-hole are NOT produced by the 0x0315 grid marker at all** — they come from a
different object path (the `0x031C → 0x031D` scene/base list, and/or the centre-star object), exactly as the
resource families (`space/s###`, `planets/fs%03d`, `strategy/bh_*`) being separate from the grid palette
suggested. Next unit should stop sweeping grid klass and instead trace the `0x031D` base/scene consumer and
the centre-star path for their own type discriminators; the grid palette only needs the two starting-system
planet cells to differ in `record[2]` (variant) if two distinct planets are wanted, while the blue SUN must
be emitted as a scene/base object, not a grid marker.

## LIVE (run 20260902T194127Z): two DISTINCT planet models rendered — variant selector confirmed live

Turned the static grid-marker decode into a live, player-visible result. Built an env-guarded experiment
authority (`LOGH7_CELESTIAL_TWO_DISTINCT=1`) that publishes a SECOND planet palette record and splits the two
authored starting cells across two markers:
- `EncodeStaticGridTypes`: palette count bumped to 5; marker 4 record = `[contentId 2, klass 3, variant 1]`.
- `EncodeStaticGrid`: the 2-cell RLE run `(2, marker 3)` split into `(1, marker 3)` + `(1, marker 4)` (one extra
  RLE pair; body pairCount adjusted). Default build behavior unchanged when the env is unset.

Harness passthrough added (additive, defaults unchanged): `-CelestialTwoDistinct` on
`host-run-fresh-run.ps1` + `guest-prepare-fresh-run.ps1` → env `LOGH7_CELESTIAL_TWO_DISTINCT`.
Build: scratch dll `872F80138B88EC0B5A7FBB717C07E43AA3C00F80986D59B93AA25BEC277F30B0`, exe `03F87AB7…`
(apphost unchanged), zip `848E9CC4E58CA782C9894974347E8DF5418423806293E2C6983D7802C76F07BC`, migration 0011
present (`9750CEFD…`).

**Result (VNC capture `runs/20260902T194127Z-natural-l1-relogin-v1/vnc-e-strategy.png`, zoom
`vnc-e-objects-zoom.png`):** the two blue grid objects render as TWO VISIBLY DIFFERENT planet models.
Sampled centre colour: left cell (marker 3, variant 0) RGB `(57,70,188)` deep indigo, dark core; right cell
(marker 4, variant 1) RGB `(72,137,197)` brighter cyan, whiter core. The green channel nearly doubles
(70→137), and the cores differ — two distinct celestial bodies, not one replicated entry.

**What this proves (live):**
1. Record `byte[2]` (variant) is the klass-3 planet MODEL selector — variant 0 vs 1 render different models,
   exactly as the 0x4d4093 decode (byte2 in 0..6 → model index) predicted.
2. The full authority→wire→client grid path works end-to-end for PER-CELL distinct typing: different markers →
   different palette records → different rendered bodies.
3. The goal's warned-against defect ("one number replicated onto every object") is DEMONSTRABLY FIXABLE for the
   starting-system pair: the two objects are no longer identical.

**Normal shutdown also exercised this run:** the client exited through its own ゲーム終了 dialog (ESC →
「ゲームを終了してもよろしいですか?」→ 決定 at (565,436)); clean-stop then reported client `aliveBefore=false`,
authority + listener gone, PostgreSQL `pgControlState="shut down"`, `stopExitCode=0`. Run cleaned
(RUN_CLEANED, +216 MB, leftover none). Captures kept on host: vnc-a..g.

**Scope note (condition 5 still NOT fully satisfied):** this is two distinct PLANETS, not the goal's exact
"one blue SUN + one planet". The SUN (space/s###) is a scene/base object, not a grid marker (per the marker
decode above), so a true sun still needs the `0x031D` base/scene consumer path — the next unit. Per-system
real celestial data (80 systems / 281 planets / 6 fortresses + specials) also remains. But the variant/marker
mechanism is now LIVE-CONFIRMED and the reusable rig (`-CelestialTwoDistinct`, scratch dll `872F8013…`) can
enumerate planet models 0..6 the same way.

## Client celestial data-model schema recovered (debug-dump field names)

The client's debug/log format strings (`.data` 0x760800-0x761000, sink FUN_00439DA0) name the exact wire schema
for every `_INF:Response...` structure. Full extract: `work/20260903-celestial-schema/client-static-info-schema.md`.
Key facts for condition 5:
- **GridType (0x0313) record field is `fixedstar=`** — the third palette byte (earlier called "variant") is the
  `fixedstar` selector. The two-distinct live result (byte2 0→1 changed the body) is this field in action.
- **Base (0x031D) = the real celestial bodies**: fields `class_=` (TYPE discriminator), `grid=`, `diameter=`,
  `revolution_{radius,cycle,direction,init_angle}=` (orbit), and planet properties `habitability=`,
  `atomosphere=`, `commodity[]`(living/food/religion/thought/peace/approval), `budget[]`, `population=`. This is
  the field map for real per-system data (the large condition-5 remainder).
- Model families preloaded at startup: sun `space/s000..s006.mdx`(+glow), planet `planets/p%03d_low.mdx`,
  fortress `planets/fs%03d_low.mdx` (loader 0x4d464d → handles 0x9d2f74), black hole `strategy/bh_*`
  (loader 0x4d47f0). The sun is a FIXED STAR: either a GridType `fixedstar` cell or a Base with a sun `class_`.
Next: disasm the Base consumer FUN_004142E0 → render dispatch to map `class_` values to families, and a live
byte2 (fixedstar) 0..6 sweep to confirm whether the centre sun is a fixedstar grid cell or a sun-class Base.

## LIVE (run 20260902T200446Z): conditions 5 + 6 hold SIMULTANEOUSLY on one screen

Ran the two-distinct celestial authority AND the game-wide Korean runtime together (Copy mode required for
Korean): proxy `F3E208FF…` + map `D1B6E86A…` (946-glyph Hanja→Hangul) + server zip `848E9CC4…` +
`-CelestialTwoDistinct 1`. On the live strategy HUD (`runs/20260902T200446Z-.../vnc-e-strategy.png`) BOTH
goal conditions hold at once:
- Condition 6 (Hanja localization): HUD labels render Korean — 공간그리드(空間グリッド), 직무권한카드
  (職務権限カード), 통솔/지휘/기동/공격/방어 stat labels, 우주력 795년 1월 1일, 단독항행; login/exit dialogs
  too (접속/입력, 종료, 결정). Katakana stays Japanese (スポットキャラクター) as expected.
- Condition 5 (distinct celestial types): the two grid objects render as different planet models — left
  RGB (57,70,188), right RGB (72,137,197), identical to the Korean-off run, so localization does not disturb
  the celestial change.
Normal shutdown via the client's own ゲーム終了/종료 dialog; clean-stop RUN_RUNTIME_CLEANLY_STOPPED, cleanup
RUN_CLEANED (junctions removed, targets untouched, +224 MB), leftover none. This is the first live proof of two
goal conditions co-existing simultaneously (the goal requires ALL 17 at once), and satisfies the
"apply localization alongside every lane" directive. Captures kept: vnc-b(login),-e(strategy),-f(exit dialog).

## Condition 15 model bundle: celestial model coverage ledger (2026-09-03)

Pivoted to the untouched file-analysis lane (goal condition 15 "모델·애니메이션" bundle: type↔model↔loader
mapping) and produced a regenerable ledger: `work/20260903-celestial-schema/celestial-model-coverage-ledger.json`
(generator `scratchpad/gen_ledger.py`). Enumerated every celestial MDX family in the extracted install with
size/sha/magic, client format string, loader xref, handle array, coverage verdict, origin, disposition, and the
first broken boundary + next tool. Families:
- **fixed_star_sun** space/s000..s006 (7) + space.mdx — ENUMERATED. Loader = the unlocated fixedstar selector.
- **planet** planets/p### (24 ids: 0,1,10,11,20,21,30,31,32,40,41,50,51,60,61,70,71,80,81,90,91,100,101,102 ×3 LOD)
  — PLAYER_VISIBLE. Format string 0x7720d8, loader 0x4d464d, handle array 0x9d2f74 (8).
- **fortress** planets/fs000..006 (7 ×3 LOD) — XREF_MAPPED. Format string 0x772090, loader 0x4d477f.
- **black_hole** strategy/bh_core+bh_wave — XREF_MAPPED. Loaders 0x4d47f0/0x4d4812, handles 0x9d2934/0x9d2a20/0x9d2a24.
- **scene_background** space.mdx (loader 0x4e2000), galaxy/grid/null_galaxy/test_warp/06 — ENUMERATED.
- **ORPHAN candidates (UNKNOWN):** planets/y### (ids 1,2,3,4,5,9) and planets/ds### (id 0) — extracted models
  exist but the client exe has NO `planets/y`/`y%03d` or `planets/ds`/`ds%03d` format string and no loader xref.
  ds### even has a distinct MDX header magic (a000bf02 vs a000d001). Goal line 28 lists planets/y### as a family,
  so it is likely server model_file-driven (ResponseStaticInformationBase `model_file=` 0x760b48) or a different
  client build. Next: scan all client/updater binaries + the server model_file path for y###/ds### references.
This advances condition 15's model bundle and directly feeds conditions 5 and 7; it also surfaces two
orphan-file findings relevant to condition 16 (누락/고아 콘텐츠).

## CORRECTION to the orphan finding (same session): y### is referenced, only ds### is orphan

Re-scanned all client builds (item1/106/107/108/109/114). Correction to the ledger above:
- **planets/y### is NOT an orphan.** The client references it via a FULL-PATH string array (not a %03d format):
  `/../data/model/Planets/y001..y009.mdx` at 0x775998 (base LOD) and `y00N_low.mdx` at 0x775518 (low LOD),
  capital "Planets" — which is why the lowercase `planets/y` format-string search missed it. Same computed-
  base-index pattern as the sun space/s### array (no direct .text xref). Verdict -> ENUMERATED. NEW condition-16
  finding: the client references y001..y009 (9) but the extracted install has only y001-y005,y009 (6), so
  **y006/y007/y008 are MISSING from the extract** (missing content to recover from the CD/original install).
- **planets/ds### IS a confirmed orphan vs the client**: NO ds path string in ANY client build; its Base
  `model_file` route does not apply (model_file belongs to ResponseStaticInformationUnitShip, not Base). It has
  a distinct MDX header magic (a000bf02). Verdict stays UNKNOWN; next = scan updater/launcher/G7MTOracle +
  ini/manifest, else disposition ORPHAN_UNUSED.
Regenerated ledger verdicts: ENUMERATED 8, PLAYER_VISIBLE 1, XREF_MAPPED 2, UNKNOWN 1. Regenerable generator:
`work/20260903-celestial-schema/gen-celestial-model-ledger.py`.

## ds### disposition CLOSED (all-binary scan): ORPHAN_UNUSED

Scanned every shipped binary for `ds###`/`y###` references:
- `exe/g7mtclient.exe` (the ORIGINAL installed client): 27 y### refs, 0 ds### refs.
- `bootfirst.exe`, `gin7updateclient.exe` (updater, VERSION=131 in update.ini): 0 ds###, 0 y###.
- all patched client builds (item1/106/107/108/109/114): 0 ds###.
So `planets/ds###` is referenced by NO shipped executable and appears in no data manifest → disposition
**ORPHAN_UNUSED** (unused/leftover art asset; distinct MDX magic a000bf02). Verdict STATIC_MAPPED. Original
files preserved (not deleted). `y###` is reconfirmed as a real referenced family (original exe references it too).
Ledger verdicts now: ENUMERATED 8, PLAYER_VISIBLE 1, XREF_MAPPED 2, STATIC_MAPPED 1 (UNKNOWN 0 for this bundle).

## SUN model selector located (2026-09-03): pointer table 0x7726f4 indexed by a data-driven star index

Resolved the sun/star model path that earlier looked xref-less:
- The `space/s000..s006.mdx` full-path strings are reached through a POINTER TABLE at **0x7726f4** (7 entries,
  s000..s006). Same for y### (table 0x7752c0) — that is why neither had a direct string xref.
- The selector is **0x4e238d**: `mov edi, dword ptr [ebx*4 + 0x7726f4]` inside the system-scene loader
  **FUN_004E2000** (which also loads `space.mdx` at 0x4e2000). It concatenates `<baseDir 0x9d2fd0> + s###` and
  loads the model (FUN_004DD680).
- The star index **ebx** is set once at 0x4e21dc from `[esp+0x364]` = an ARGUMENT of FUN_004E2000, threaded
  in through the thin wrapper **FUN_004E1F30** (call site 0x4e1f3a). So the sun model is DATA-DRIVEN: the
  caller supplies the star index (0..6) from world/system data.
Consequence: the "blue sun" is a per-system fixed-star index rendered by the system-scene (in-system) loader.
Two remaining links, both precisely anchored: (1) trace the star-index argument up from FUN_004E1F30's callers
to the source world/system field the authority must populate; (2) RE the in-system-view world-state gate (that
view is inert in the minimal scene). Ledger sun row upgraded ENUMERATED -> XREF_MAPPED.

## Sun star-index caller chain anchored (2026-09-03)

Traced the sun star-index up one more level. Call chain (all thiscall on the system-scene object `esi`):
- `0x4f1827` (system-scene setup; nearby it constructs sub-objects via FUN_004DDE20 sized 0x258 with resource
  string 0x77466c) → `mov ecx,esi; call FUN_004E1F30`.
- `FUN_004E1F30` (0x4e1f30, thin wrapper: `mov esi,ecx; call FUN_004E1F50; mov ecx,esi; call FUN_004E2000`).
- `FUN_004E2000` reads the star index at 0x4e21dc from `[esp+0x364]` → selector 0x4e238d → table 0x7726f4.
Note: FUN_004E2000 is `ret 8` yet reads `[esp+0x364]`; the slot is either a threaded arg or a local seeded
from the scene object — resolving which needs the FUN_004F17xx scene-object constructor dataflow. So the
sun's star index is state on the in-system scene object built around 0x4f17xx. Next unit (anchored): RE the
0x4f17xx scene-object constructor to find where the star index field is written from world/system data, and
reach the in-system view live; then the authority supplies the per-system star index for a real blue sun.

## Sun scene context resolved: it is the in-system FLEET scene; sun couples to per-system data (condition 7)

The scene-object constructor FUN_004F1750 (containing the 0x4f1827 call into the star loader) builds FLEET
render effects — its sub-objects load `image/Effect/engine_core.bmp`, `engine_glow.bmp`, `dumyFleetPoints.bmp`,
`e_flare.bmp`, `ef_thruster02.bmp` into [esi+0x790]/[+0x794]/[+0x7a8]/[+0x7ac]. So FUN_004E2000 (which loads
`space.mdx` + the `space/s###` sun) is the **in-system (星系内宇宙) fleet-scene loader**, and the sun is the
system's fixed star rendered as that scene's backdrop. The star index therefore comes from the CURRENT
SYSTEM's data, available only once the in-system fleet scene is entered with real per-system content.
Conclusion: condition 5's blue sun is COUPLED to condition 7 (per-system real data) and the in-system-view
activation — they are one unit, not three separate ones. The minimal authored scene renders the space-grid
view (grid markers, where the two-distinct planets and the fixedstar byte live) but not the in-system fleet
scene, so the sun cannot be shown until per-system data drives the in-system scene. Full static path for the
sun is now mapped end-to-end: selector 0x4e238d ← table 0x7726f4 ← FUN_004E2000 ← FUN_004E1F30 ← FUN_004F1750
(fleet scene) ← current-system data. Next unit = the per-system-data + in-system-view lane (condition 7),
which also unblocks the Base class_ bodies (fortress) found unreachable in run 20260902T201223Z.

## CONDITION 1 VERIFIED (2026-09-03): first fully-closed goal condition

Bound `docs/reverse-engineering/condition-1-verification.json` (regenerable via
`bind-condition-1-verification.py`): verdict **PLAYER_VISIBLE_REPRODUCIBLE, 3/3 independent sealed runs pass
every check** (20260902T194127Z, 200446Z, 201223Z). Each run: clean boundary (forbiddenProcesses=[], no prior
authority/postgres listener, interactive console session), hash-fixed unmodified original client
(F93592F3…, Install/Copy), prep FRESH_RUN_PREINPUT_READY, natural login = exactly one SendInput credential
submission (0 retries, 0 clicks, secrets not recorded), post-login screen captured. Receipts sha-bound per run.
Condition 1 ("깨끗한 실행 경계에서 원본 클라이언트가 자연스럽게 로그인한다") is satisfied; only
INDEPENDENTLY_REVIEWED (third-party replay of the binder) remains, which is condition 17's gate.
Consolidated ledger `docs/reverse-engineering/condition-status-ledger.json` updated.

## CONDITION 3 VERIFIED (2026-09-03): second fully-closed goal condition

Bound `docs/reverse-engineering/condition-3-verification.json` (regenerable via
`bind-condition-3-verification.py`): verdict **PLAYER_VISIBLE_REPRODUCIBLE, 3/3 chronological sealed runs**
(20260902T194127Z → 200446Z → 201223Z) on the same recovered PostgreSQL source. In each run the character
アッテンボロー reappears and is entered (strategy HUD names ダスティ・アッテンボロー二等兵) AFTER the prior run's
logout through the client's own ゲーム終了 dialog and a clean authority/PostgreSQL stop (client aliveBefore=false,
pgControlState=shut down, stopExitCode 0). Receipts sha-bound per run. Condition 3 ("로그아웃 후 같은 캐릭터가
다시 나타난다") is satisfied for the recovered character; a freshly-created character is condition 2's scope.
Consolidated ledger now reports fullyVerified = 2 (conditions 1 and 3).

## CONDITION 13 VERIFIED (2026-09-03): third fully-closed goal condition (server restart restores state)

Run 20260902T205210Z, bound in `docs/reverse-engineering/condition-13-verification.json` (regenerable via
`bind-condition-13-verification.py`): verdict **PLAYER_VISIBLE_REPRODUCIBLE, 12/12 checks**.
Sequence: login → strategy (state A) → DB snapshot A → client's own ゲーム終了 → **authority RESTART on the SAME
PostgreSQL** (`guest-restart-authority.ps1`: stop client+authority, keep DB running, rotate password, restart
the same binary, relaunch client; authority pid 2672→4920, client 4896→6576) → snapshot B → relogin (one
SendInput submission, 0 retries; needed `-PrepFileName relaunch-prep.json -WireFileName server-wire-2.jsonl`
so the pre-input guard reads the post-restart wire) → strategy → snapshot C → clean-stop → cleanup.
Result: **A == B == C byte-identical** — grid unit (cell 101, authority_version 5, same updated_at),
moveCommand 0, domain_event 24, account authority_version 24 — and the restored strategy screen is identical
(same character/stats/system/date). The append-only domain_event log was preserved, not truncated or
re-applied. Harness note: after a restart, point credential/clean-stop at `relaunch-prep.json` and the wire
guard at `server-wire-2.jsonl`. Consolidated ledger fullyVerified = 3 (conditions 1, 3, 13).

## CONDITION 8 VERIFIED (2026-09-03): first full vertical path (WARP) closed — fourth fully-closed condition

Run 20260902T210224Z, bound in `docs/reverse-engineering/condition-8-verification.json` (regenerable via
`bind-condition-8-verification.py`): verdict **PLAYER_VISIBLE_REPRODUCIBLE, 15/15 checks**.
Vertical: unit 2 (ダスティ・アッテンボロー) at cell 101 → 職務権限カード tab (727,577) → card (820,512) → ワープ航行
(822,283) → grid chooser → destination cell 102 → 確認 dialog → 決定 (565,484) → client 0x0B01 → authority
**Success** (`move-grid-unit=2;source-cell=101;destination-cell=102;authority-version=25;design=new`) → screen
「グリッド選択完了しました」→ PostgreSQL `current_cell_id=102` (authority_version 5→25, move command 0→1,
domain_event 24→25) → client ゲーム終了 → **authority RESTART on the same DB** → relogin → cell 102 restored
(DB identical M==R==S; strategy camera recentred on cell 102). This also closes condition 13's remaining gap
(restart after a real state change).

**Harness finding (important):** VNC pointer clicks (vncdo) register on GDI/UI widgets (lobby buttons, card
tab, card, ワープ航行, dialogs) but do **NOT** register on the 3D strategy-grid hit-test — two VNC attempts
(plain and single-process hover→pause→click) left the chooser at "Please choose the grid." with no 0x0B01.
The destination click must be a guest-side `SetCursorPos + mouse_event` (`guest-click-point.ps1`), which
opened the 確認 dialog on the first click. Also: separate vncdo invocations reset the pointer, so
`move` and `click` must be in ONE invocation (`move X Y pause 1 click 1`); a stray corner click pans the
camera and shifts grid-object screen coordinates (re-read them from a fresh capture). After an authority
restart, point credential/click/clean-stop at `relaunch-prep.json` and the wire guard at `server-wire-2.jsonl`.
Consolidated ledger fullyVerified = 4 (conditions 1, 3, 8, 13).

## CORRECTION (2026-09-03): condition numbering fixed against the goal's 최종 완료 조건 list — fullyVerified 4 → 3

Earlier ledger revisions labeled two items with the goal's **즉시 실행 순서** step numbers (line 41 "7 = celestial
types", line 42 "8 = WARP vertical") instead of the **최종 완료 조건** numbers (lines 228-244). Verbatim, the
completion conditions are: 7 = "마지막 나가기 버튼을 포함한 모든 필요한 버튼이 동작하고 … 활성/비활성과 거부 이유가
서버 권위와 일치한다"; 8 = "원본 캐릭터 추첨 또는 명시적 NEW_DESIGN 대체 경로가 … DB와 재접속까지 동작한다";
9 = "모든 전략 기능이 입력부터 화면·서버·DB·재접속까지 동작한다". Therefore:
- The bound WARP vertical receipt (`condition-8-verification.json`, file name kept) is evidence for **condition 9**
  (one strategy feature closed end-to-end); condition 9 requires ALL strategy features → PARTIAL_LIVE, not verified.
- Real condition 7 (all buttons + enable/disable reasons) → PARTIAL_LIVE (exit button + WARP buttons + soft-reject
  reason path verified live; faction-panel 次へ inert = an unmet required button; no full sweep/matrix).
- Real condition 8 (character lottery / NEW_DESIGN path) → STATIC_MAPPED (lottery button, catalog, strings
  located; not exercised live).
- Per-system celestial data now lives under condition 5's gap (it was never a separate condition).
Corrected count: **fullyVerified = 3/17 (conditions 1, 3, 13)**. Ledger regenerated.

## Second strategy feature closed: PROMOTION (昇進) vertical (2026-09-03) — conditions 9 (partial) and 11 (인사)

Run 20260902T212309Z, bound in `docs/reverse-engineering/condition-9-promotion-verification.json`
(regenerable via `bind-condition-9-promotion-verification.py`): **PLAYER_VISIBLE_REPRODUCIBLE, 14/14 checks**.
Vertical: character 2 (rank 20 二等兵) → 職務権限カード tab → card → **昇進** (722,283) → authority serves the
promotion ladder (wire type 4608 = 0x1200, entry 「二等兵→一等兵」) → ladder entry selected → 決定 (726,608) →
client **0x0704** (1796) → authority **Success** → PostgreSQL `character.rank 20→19`, character
authority_version 5→25, `domain_event` 25 = `CharacterRankPromoted{sourceRank:20, promotedRank:19}`, account
version 24→25 → ゲーム終了 → authority RESTART on the same DB → relogin → rank 19 restored (M==R==S) → strategy
HUD shows **一等兵**. All UI clicks here were VNC (dialog widgets); no 3D-grid click was needed.
Faithful notes: (a) the in-session HUD rank label stayed 二等兵 right after 決定 — the promoted label appears on
re-entry; (b) the served 情報 text read 「二等兵 実行不可(0)」 yet the authority accepted → an enable/rejection-
reason vs authority MISMATCH (condition 7 item); (c) the dialog's list header rendered 「サーバーが混み合っています」
and its second button 「バージョンが違います」 — misindexed constmsg rows served for this dialog (conditions 6/16
items). Ledger: conditions 9 (two features closed: WARP, promotion) and 11 (personnel) now PARTIAL_LIVE with
receipts; 7/6/16 gaps updated with these concrete items. fullyVerified remains 3/17 (1, 3, 13).

## Third strategy-screen feature closed: MAIL READ (通信) vertical (2026-09-03) — conditions 11 (2nd domain) and 9

Run 20260902T213601Z, bound in `docs/reverse-engineering/condition-11-mail-read-verification.json`
(regenerable via `bind-condition-11-mail-read-verification.py`): **PLAYER_VISIBLE_REPRODUCIBLE, 13/13 checks**.
Vertical: strategy HUD mail icon (872,748) → mailbox 受信 (unread-only list 002/100: 「FIX LIVE」, 「命令（返答済み）」;
authority serves 0x0F08 lists + 0x0F04 addresses) → click row 「FIX LIVE」(440,220) → client **0x0F11** (3857) →
authority **Success** (`mail-id=6; read-updated=true; authority-version=25`) → screen shows the body (「NO ACK」)
and the opened-envelope icon → PostgreSQL `original_mail_message` id 6 `is_read false→true` (read_at set),
`domain_event` 25 = `OriginalMailRead{mailId:6}`, account version 24→25 → close mailbox (X 794,113) → ゲーム終了 →
authority RESTART on the same DB → relogin → read state restored (M==R==S) → mailbox shows only one unread
(001/100). Non-destructive: 削除/全削除 never pressed. All clicks were VNC (UI widgets).
`guest-db-inspect.ps1` gained an additive read-only `mail` query (original_mail_message). Ledger: condition 11
now has two closed domains (personnel, communications); condition 9 has three closed features (WARP, promotion,
mail read). fullyVerified remains 3/17 (1, 3, 13).
Ops note: guest C: free space trends down ~100 MB per sealed run (each run keeps its DB copy, deleteData=false):
592→487 MB after this run. Before the next runs, clean this lane's own older run copies with
`guest-cleanup-run.ps1 -DeleteData` ONLY after confirming each is regenerable from the sealed source
20260902T083838Z (never the source itself).

## Ops: guest disk reclaimed (2026-09-03) — own regenerable DB copies only

Deleted the derived PostgreSQL copies of this lane's four oldest, already-verified runs (194127Z, 200446Z,
201223Z, 205210Z) with `guest-cleanup-run.ps1 -DeleteData`, after asserting per run that `sourceRunId` is the
sealed source 20260902T083838Z and its recorded pg_control sha is 348153D8… (regenerability). Each receipt:
RUN_CLEANED, "postgres-data (derived copy) 64MB gone=True". Free space 487 → 743 MB. The sealed source, all
host receipts/captures, and the three newest runs' copies are untouched. New read-only helper
`guest-verify-cleanup.ps1` reports per-run copy existence, cleanup receipts, free MB, leftover processes and
lane listeners (pass -CheckRunIds as ONE comma-separated string; `-File` array args do not survive).

## Fourth strategy-screen feature closed: MAIL SEND (通信, real state CREATED) — conditions 11 and 9 (2026-09-03)

Run 20260902T215023Z, bound in `docs/reverse-engineering/condition-11-mail-send-verification.json`
(regenerable via `bind-condition-11-mail-send-verification.py`): **PLAYER_VISIBLE_REPRODUCIBLE, 14/14 checks**.
Vertical: mail icon (872,748) → mailbox → 新規作成 (326,166) → address book (友人 tab; only entry
ダスティ・アッテンボローン = self) → entry (190,80) + 決定 (320,534) → compose view → タイトル field (540,195) typed →
body pane (500,430) typed (VNC keyboard, ASCII) → 送信 (692,634) → client **0x0F10** (3856, len 240) → authority
**Success** (`mail-id=7; created=true; sender-character-id=2; recipient-character-id=2`) → lists auto-refresh →
PostgreSQL `original_mail_message` id 7 inserted, `domain_event` 25 = `OriginalMailSent{title, body, mailId 7}`,
account version 24→25 → close → ゲーム終了 → authority RESTART on same DB → relogin → mail 7 persisted
(M==R==S) → 送信 tab lists 「cond11 send test…」 (005/100).
Harness notes: the bottom 「※ここにメッセージを書きます」 line is the HUD chat bar, NOT the mail body — clicking it does
not move focus (the second string appended to the title); the body pane at (500,430) accepts text. VNC `type`
works for ASCII in these GDI edit controls. Render defect found: the 送信-tab title column draws new titles
over stale ones without erasing (list-refresh clipping bug) — condition 6/16 item.
Ledger: condition 11 comms domain now covers read + send (real state created); condition 9 has four closed
features (WARP, promotion, mail read, mail send). fullyVerified remains 3/17 (1, 3, 13).

## Condition 7 datapoint: order-suggest 承認 on an already-decided card is a SILENT no-op (2026-09-03)

Run 20260902T220359Z, bound in `docs/reverse-engineering/condition-7-order-approve-gating-verification.json`
(regenerable via `bind-condition-7-order-approve-gating-verification.py`): verdict **OBSERVED_GATED_SILENT, 7/7**.
Baseline DB already holds `original_order_suggest_reply` (character 2, card 39, reply_value 2, v23). Opening the
mailbox row 「命令（返答済み）」 sends 0x0F11 and the authority answers Success with
`order-suggest-resolved-card-read; reply=2; authored-card-id=39` (body 「オウム返しK」). Pressing **承認 (250,166)**
then sends NOTHING (no 0x0F14), shows NO dialog or message, and the DB is unchanged. So the client's gate
matches the authority's ALREADY_DECIDED rule (good) but gives no verifiable reason (bad): condition 7 requires
a reason for inert required buttons — recorded as a condition-7 gap item and a condition-16 dead-control
item. To exercise a REAL order reply (state creation) the scene needs an undecided authored order card
(eligibility: world entered, selected == world character, rank 1..20); the recovered DB's only card is decided.

## Condition 7 lobby sweep: 環境設定 opens fine but is a WEDGED panel (2026-09-03)

Run 20260902T221133Z, bound in `docs/reverse-engineering/condition-7-lobby-settings-panel-verification.json`
(regenerable via `bind-condition-7-lobby-settings-panel-verification.py`): **OBSERVED_WEDGED_PANEL, 9/9**.
環境設定 (122,481) consumes the click and opens a proper settings panel (画面解像度 1024 768, テクスチャー品質 MIDDLE
with 艦船/背景/エフェクト MIDDLE, モデル品質 HIGH with 艦船/背景 HIGH, an EMPTY エフェクトレベル row, 予備; buttons
変更を適用 / 戻る; no NO DATA). But once open: 戻る (762,582) is inert to a VNC click AND to a guest
SetCursorPos+mouse_event click; the left menu (クレジット) is ignored; ESC opens the ゲーム終了 dialog OVER the panel;
the dialog's 決定 (565,432) is ignored too — the client stayed alive (clean-stop aliveBefore=true) and had to be
stopped with `guest-stop-own-client.ps1` (pid identity-checked; no leftovers afterwards). 変更を適用 was
deliberately NOT pressed (user configuration). So 環境設定's 戻る is a required button that does not work, with
no reason shown — condition 7 gap + condition 16 dead-control item. It is the same inert family as the
faction-panel 次へ (condition 2): strategy-screen panels (card, WARP 確認, promotion ladder, mailbox, compose,
exit dialog from the strategy view) all consume clicks, while these lobby-side panels swallow everything but
ESC — the gate is per-panel/screen state, not input transport.
**Harness warning:** do NOT open 環境設定 from the lobby in sealed runs until the panel gate is understood; if it
happens, ESC → the exit dialog will not respond either — use guest-stop-own-client on the run's own pid.

## Condition 8 lottery: authority path complete, lobby panel INERT to all input (2026-09-03)

Run 20260902T222326Z, bound in `docs/reverse-engineering/condition-8-lottery-panel-verification.json`
(regenerable via `bind-condition-8-lottery-panel-verification.py`): **OBSERVED_PANEL_INERT, 10/10**.
Baseline: one character (slot 0, rank 20), one lottery entry already `awarded`, no pending → lottery available.
オリジナルキャラクター抽選 (122,307) → session picker → LOGH7-1 (640,270) → 「オリジナルキャラクターを選ぶ」 renders the
authority-served catalog (キャゼルヌ, シェーンコップ, アッテンボロー, ユリアン・ミンツ, ヤン・ウェンリー; 所属 帝国/同盟; 第一〜第五候補;
中止/決定; every row shows 階級「皇帝」 — wrong temporary label). The candidate row (405,416) ignored a VNC click, a
guest SetCursorPos+mouse_event click AND the Down key; no 0x1004/0x1006 reached the authority; ESC's exit
dialog 決定 was ignored too; the client was stopped with guest-stop-own-client (no leftovers, 602 MB free).
Server side is complete (0x1006 charge → original_character_lottery_entry + OriginalCharacterLotteryEntered →
random award creates the character), so condition 8's blocker is the client-side lobby-panel input gate —
the same family as the faction 次へ (condition 2) and 環境設定 戻る (condition 7): once a lobby-side panel opens,
the entire lobby input path except the ESC key is dead. Strategy-screen panels are unaffected.
**Harness rule:** in sealed runs, do NOT open 新キャラクターの作成 / オリジナルキャラクター抽選 / 環境設定 from the lobby unless
the run's purpose is to measure that gate; recovery is guest-stop-own-client on the run's own pid.

## Lobby-panel input gate — static chain so far (2026-09-03, client item1)

Facts (do not re-derive):
- `FUN_005024A0` = `mov al,[ecx+5]; ret` (manager input arm getter). Its SETTER is `FUN_005024B0`: sets
  `[esi+5] = arg` ONLY IF `[esi] == 0x63` (manager tag); otherwise logs 「監視」 (0x779c78) and leaves the arm
  unchanged. ~67 callers; the dominant pattern is per-screen init `cmp [sel*stride + TABLE], -1` → `push 1`
  (visible via 0x502ea0 + arm=1) else `push 0` (hidden + arm=0). The TABLEs (0x66eaa0, 0x66f130, 0x6709c0,
  0x6711f0, 0x6739a8, 0x673a50, 0x675138, 0x676dd0, 0x6770d8) are large-stride record arrays indexed by the
  manager's SELECTION index (`[obj+0x34c]`, `+0x61c`, `+0x2c0`, `+0x5c`, `+0x8e4`, `+0x37c`, `+0x4d4`, `+0xa0c`);
  entry 0 starts as -1 (sentinel = nothing selected → panel disarmed).
- `FUN_004EA610` is a rect→scaled-screen-coords helper on `[0x7c1b4c]+0x2a5fc` (app UI globals), NOT a server
  record lookup (earlier hypothesis withdrawn).
- Widgets are KWSWND "parts" created by iPartsID; `FUN_005025C0` returns widget `+0x15` only if `+0x08`
  (part exists) else logs 「存在しないパーツ」 (0x779c24) — that string has ~80 accessor sites (0x502205–0x5089c7).
  Related debug strings: 「それは作って無いパーツです」, 「未対応のiPartsID」, 「パーツ iIDOFS 範囲外（KWSWND_{LISTBOX,
  EDITBOX,VIEW,COMPONENT}_MAXSIZEを増やす)」.
- The only three plain byte writes to `+0x08` in the UI region (0x4f89b7, 0x4f8fb2, 0x4f92ee) are object
  constructors / an event-loop jump-table case — NOT the widget-record part-create. The part-create write for
  the 0x34-stride widget records (manager+0x4E8, count +0x3F4, list +0x470) must use indexed addressing;
  locating it (and what must succeed before it) is the precise next static step. Live evidence: lobby-side
  panels (create/faction, lottery candidates, 環境設定) ignore all input except ESC; strategy panels arm fine.

## Widget part-create located: FUN_00503A10 (2026-09-03)

`FUN_00503A10(this=view, type, a2, a3)` (340 callers) is the KWSWND part creator: it takes a slot via
`FUN_00507BF0(index)` (logs 「Null Viewに対してクリエイトしようとした」 0x779f88 and skips if the view is the null
sentinel `+0x28b364`), zero-fills a 0xD04-byte part record, sets `[part+0]=type`, `[part+4]=view/manager`,
**`[part+8]=1` (exists)**, `[part+0x15]=0` (label row), `[part+0x1c]=1`. Existence is set unconditionally on
creation, so 「存在しないパーツ」 for the lobby-side panels means their parts were never created (call skipped or
made against a Null View). Screens build panels as `FUN_0050BB40(viewId)` → `0x502ea0(visible)` →
(`0x5024b0(arm)`) → `FUN_00503A10(...)` per part. Part reset (`[+8]=0`) sites: 0x5041a1/0x5041ef/0x50423a.
Next: where the client's debug logger `FUN_005923A0` writes (file/OutputDebugString) — if capturable in the
guest read-only, a sealed run clicking an inert panel would name the failing view/part ids without a debugger.

## Two more gate facts (2026-09-03): logger is a stub; views are lazily created

- `FUN_005923A0` (the KWSWND debug logger all the 「監視」/「存在しないパーツ」/「Null View…」 sites call) is a single
  `ret` in the retail client — messages are never emitted, so there is NO read-only debug-log route.
- `FUN_0050BB40(viewId)`: viewId must be in [0,0x73); the view table `[wm + id*4 + 4]` is filled lazily (new
  0xFAC4-byte view + ctor 0x501200) on first lookup, so a Null View is not the lobby failure either.
Remaining explanation: parts exist but the per-screen arm gate (`cmp [sel*stride + TABLE], -1` → arm=0) sees
selection index 0 (nothing selected) for lobby sub-panels, and since input is dropped while disarmed the user
can never select → deadlock. Strategy screens enter with a selection set. Server-side suspect: the authority
answers the character-entry-state family with `0x1000 => EncodeZeroFilled(0x1001, 0x1c0)` — a zero body could
leave the client's lobby selection/state at 0. Testable by implementing a real 0x1001 (and 0x1005) response.

## Lobby-panel gate: suspects eliminated so far + the read-only RPM plan (2026-09-03)

Eliminated (do not re-test): input transport (VNC vs guest SetCursorPos+mouse_event both ignored), keyboard
navigation (Down ignored), Null View (views are lazily created by FUN_0050BB40 for ids < 0x73), part existence
(FUN_00503A10 sets [part+8]=1 unconditionally on create), the debug logger (FUN_005923A0 is `ret`), and the
character roster/entry data (0x1001 dump keys id/index/kind/base[]{grid_index}/name[] are served correctly by
OriginalSimpleCharacterRosterCodec — the lobby character list and the lottery catalog render).
Remaining suspects: (a) the per-screen arm gate `cmp [sel*stride + TABLE], -1` → `FUN_005024B0(0)` when the
manager's selection index is 0; (b) the event flag [+0x08] required by the dispatcher FUN_005015F0.
Measurement (read-only, no debugger): `guest-rpm-manager-arm.ps1` opens the client with PROCESS_VM_READ only,
walks committed private RW regions, and lists every candidate manager (dword tag 0x63 at +0, the FUN_005024B0
guard) with its +0x04 visible and +0x05 arm bytes. Run it twice in one sealed run — label "lobby-responsive"
(after login, before any panel) and "settings-wedged" (after opening 環境設定) — and diff which managers flip
arm 1→0 or appear disarmed. If the wedged state shows the lobby manager(s) with arm=0, hypothesis (a) is
confirmed and the fix is whatever sets the selection/arm for lobby sub-panels (client state the authority may
influence via the lobby/session responses); if arm stays 1, hypothesis (b) (event flag) is next.

## RPM arm probe, run 20260902T224357Z (in progress): image data must be scanned; candidate 0x02216C20

`guest-rpm-manager-arm.ps1` (read-only, PROCESS_VM_READ) first scanned only MEM_PRIVATE and found no manager
(5 coincidental 0x63 dwords). Widened to MEM_IMAGE writable regions (the client's UI globals live in the image
.data/.bss, e.g. uiRoot 0x02215E2C, input owner 0x022142A8): in the 環境設定-wedged state it found 25 dwords == 0x63;
the 0x0077ACCC..0x0077B20C hits are a stride-0xA8 integer table (false positives) and the two "plausible" heap
hits are data runs. The one meaningful candidate is **0x02216C20** — tag 0x63 inside the lobby/create UI global
block (the create flow stores to 0x2216c80/0x2216c6c/0x2216c34 nearby) — with +0x04 visible=0 and **+0x05 arm=0**
while wedged. A responsive-lobby baseline with the same widened probe is required to interpret it (the first
baseline used the narrow private-only heuristic). The dispatcher-layout fingerprint (+0x3F4/+0x470) does not
apply to this object type; treat `plausibleManager` as unreliable and diff by address between states instead.

## RPM diff of the lobby UI block, responsive vs 環境設定-wedged (run 20260902T224357Z, A3 vs B3)

Read-only 64 KB dumps of 0x02210000 before/after opening 環境設定 differ in 83 dwords:
- 0x02216700 block (60 dwords): a (width,height,refresh) display-mode table (0x400×0x300@0x3c, 0x480×0x360, …,
  0xa00×0x780) — the settings panel enumerating resolutions; NOT the gate.
- 0x02216C20 object (tag 0x63): +8=1, +0xC=1, +0x14=2 (current mode index), +0x4C=1, +0x50/+0x54 = 0x400/0x300
  (1024×768), and **0x02216C80 = 1** — the same flag the create flow writes (`mov [0x2216c80],eax` in the
  0x51b0xx create/session code): a shared "lobby sub-panel active" flag.
- Input-owner block: 0x022143DC/E0 (last cursor) (300,12)→(122,481); 0x02214408, 0x02214434, 0x02214BD8 :=
  (122,481) = the 環境設定 click point; **0x0221443C := 0x100 and 0x0221453C := 0x100** — a button/capture state
  left set after the click that opened the panel. Hypothesis: the panel-opening click never completes its
  button-up handling in the lobby (capture stays 0x100), so later clicks are dropped (only ESC — a key — is
  processed). Test: deliver a bare LEFTUP (guest mouse_event) and re-dump; if 0x100 clears and 戻る then
  works, the wedge is an input-capture artifact of how the lobby panels consume the press.

## Lobby-panel gate RPM measurement — result (run 20260902T224357Z): a MODAL sub-panel state, not the +0x05 arm

Bound in `docs/reverse-engineering/condition-2-lobby-panel-gate-rpm-verification.json`
(`bind-condition-2-lobby-panel-gate-rpm-verification.py`), verdict **OBSERVED_MODAL_STATE**, read-only RPM only.
- The manager input arm (+0x05 on tag-0x63 objects) is 0 in BOTH the responsive lobby and the 環境設定-wedged
  state → it does not gate the lobby (hypothesis rejected).
- Opening 環境設定 changes 83 dwords in the 0x02210000 UI-globals block: 0x02216C80 0→1 (the shared "lobby
  sub-panel" selector also written by the create/session flow at 0x51b0d9/0x51b19c and the settings flow at
  0x5210d9), the settings object 0x02216C20 (+8=1, +0xC=1, +0x14=2, +0x50/+0x54=1024×768), the click point
  (122,481) into the input-owner cells 0x022143DC/E0, 0x02214408, 0x02214434, 0x02214BD8, the display-mode
  table at 0x02216700 (w,h,refresh triples), and **0x0221443C / 0x0221453C := 0x100** (input-owner modal cells).
- One bare `mouse_event LEFTUP` (`guest-mouse-up.ps1`) IS registered (21 dwords 2→1 in 0x02214778..0x02214944)
  but the 0x100 modal cells persist and the panel stays inert → not a stuck button.
Conclusion: the wedge is a modal sub-panel state under which the dispatcher drops mouse input to the panel's
own controls. The 0x100 cells have no displacement-addressable readers (dynamic indexing), so the next static
step is the dispatcher branch (FUN_005015F0 callers) that consults the input-owner modal state, or the original
code path that clears 0x02216C80/0x100 (what the panel's own 戻る is supposed to do).
Harness: `guest-stop-own-client.ps1` now accepts `-PrepFileName` (needed to stop a relaunched client whose pid is
in relaunch-prep.json); `guest-rpm-manager-arm.ps1` supports `-DumpBlockStartHex/-DumpBlockSize/-DumpPath` for
read-only block dumps and scans MEM_IMAGE writable regions (the UI globals live there).

## CORRECTION (2026-09-03): 0x02216C80 is a SETTINGS VALUE CACHE, not a sub-panel selector

The settings flow at 0x5210b1–0x521129 fills 0x02216C6C / 0x02216C80 / 0x02216C28 / 0x02216C2C / 0x02216C34 /
0x02216708 from `FUN_004F3730(0xc6ebb0, idx)` with idx 2,3,4,0,1 (the config store: texture/model quality etc.),
so those cells are the 環境設定 panel's own value cache (hence 1,1,2,… in the diff). The create/session flow
writes the same cells for the same reason. Withdraw the "shared lobby sub-panel selector" reading. The modal
evidence now rests on the input-owner cells 0x0221443C / 0x0221453C (:= 0x100) and on the predicate
`FUN_00500B60(inputOwner 0x022142A8)` that the dispatcher (FUN_005015F0 region 0x501665/0x501695/0x5016b2) and
many click handlers call before handling input — its body decides what 0x100 means.

## Click routing chain (static, 2026-09-03): dispatcher → input-owner getters

- `FUN_00500B60(inputOwner)` = `return [owner+0x24] == 1` (input device mode predicate, owner = 0x022142A8 so
  the cell is 0x022142CC); dozens of click handlers gate on it.
- Dispatcher `FUN_005015F0` (0x501640…): after `FUN_005024A0` (arm) and `FUN_005025C0` (widget exists/+0x15) it
  calls `FUN_00500820(owner,&pt,1)` (cursor; failure logs 「カーソル情報なし」), then `FUN_00500870(owner,&buf,1)`
  and `FUN_005008E0(owner,&buf,1)` (button press/release event getters) and compares the event type with 0x10.
- `FUN_00500580(owner)` is the input-owner init: reads config 0x7c1b50, calls 0x5009d0 and `FUN_00500B70`
  (device probe: tests 0x70/0x72/0x73 via [0x66b5e8], records 0x2214c2c/0x2214c30), sets +0x134/+0x138 from
  0x779b10/14.
The meaning of the 0x100 cells (0x0221443C/0x0221453C) must come from what FUN_00500870/FUN_005008E0 read.

## Input-owner (0x022142A8) layout decoded from the getters (static, 2026-09-03)

- `FUN_00500820` cursor: +0x134/+0x138 (= 0x022143DC/E0).
- `FUN_00500870` press: valid if byte +0x155 (0x022143FD) != 0 OR (+0x18 != +0x1c and +0x18 != 0); returns
  +0x160/+0x164 (0x02214408/0C) = press point.
- `FUN_005008E0` release: valid if byte +0x154 (0x022143FC) != 0 OR (+0x10 != +0x14); returns +0x158/+0x15c
  (0x02214400/04) = release point.
- `FUN_00500B60`: +0x24 == 1 (device mode). The 0x100 cells are +0x194 (0x0221443C) and +0x294 (0x0221453C).
Host-side comparison of the A3 (responsive) / B3 (wedged) / B4 (wedged + bare LEFTUP) dumps at these offsets is
the cheapest next check (no VM): whether a press stays latched (+0x155 / press queue) while wedged.

## Input-owner state table (run 20260902T224357Z dumps; owner = 0x022142A8)

| field | A3 responsive | B3 wedged | B4 wedged + bare LEFTUP |
|---|---|---|---|
| +0x10/+0x14 release queue | 0/0 | 0/0 | 0/0 |
| +0x18/+0x1c press queue | 0/0 | 0/0 | 0/0 |
| +0x24 device mode | 1 | 1 | 1 |
| +0x134/+0x138 cursor | (300,12) | (122,481) | (122,481) |
| +0x154 release pending / +0x155 press pending | 0 / 0 | 0 / 0 | 0 / 0 |
| +0x158/+0x15c release point | (0,0) | (0,0) | (0,0) |
| +0x160/+0x164 press point | (0,0) | (122,481) | (122,481) |
| +0x18c/+0x190 last press | (0,0) | (122,481) | (122,481) |
| +0x194 / +0x294 | 0 / 0 | 0x100 / 0x100 | 0x100 / 0x100 |
Reading: the press that opened 環境設定 was recorded, but NO release was ever recorded (+0x154, +0x158/+0x15c stay
0) and +0x194/+0x294 stay 0x100 ("button down"), even after a bare user32 LEFTUP. Hypothesis: once a lobby
sub-panel opens, the client's DirectInput mouse path stops delivering updates (device not re-acquired), so the
button stays latched down and every later click is ignored; the keyboard path still works (ESC opens the exit
dialog). Decisive next check (one sealed run, read-only dumps): while wedged, MOVE the mouse (VNC) and re-dump
— if +0x134/+0x138 does not follow the pointer, mouse input is dead (acquisition lost); if it follows, only the
button state is stuck. Either way the fix target is the client's input re-acquire path on lobby panel open
(what the original client does when its window/panel gains focus), not the authority.

## 로비 패널 게이트 — 이동/클릭 RPM 판정 (런 20260902T230931Z, 읽기 전용 덤프 C1~C4)

입력 소유자 0x022142A8의 셀을 네 상태에서 비교했다 (C1 응답 로비, C2 環境設定 열림=wedge, C3 wedge 중 VNC 이동(600,300)만, C4 wedge 중 VNC 클릭(600,300) 1회).

| 셀 | C1 | C2 wedge | C3 +이동 | C4 +클릭 |
|---|---|---|---|---|
| +0x134/+0x138 커서 | (300,12) | (122,481) | **(600,300)** | (600,300) |
| +0x160/+0x164 프레스 지점 | (0,0) | (122,481) | (122,481) | **(600,300)** |
| +0x194 / +0x294 | 0 | 0x100 | 0x100 | **0** |
| +0x154/+0x155 릴리스·프레스 대기 | 0 | 0 | 0 | 0 |
| 0x02216C80 서브패널 셀 | 0 | 1 | 1 | 1 |

판정:
- **마우스 전달은 wedge 중에도 살아 있다.** 커서 셀이 이동을 따라가고(C3), 클릭은 완전한 새 프레스로 기록된다(C4). "DirectInput 미획득/전달 사망" 가설 기각.
- **0x100 래치는 "다음 DOWN까지 논리적으로 눌린 버튼"이다.** 맨 LEFTUP(mouse_event)으로는 안 풀리고(이전 런 B4), 다음 클릭의 DOWN으로 풀린다(C4). 즉 패널을 연 프레스의 릴리스가 위젯 디스패치에 소비되지 않은 채 남는다.
- 따라서 wedge의 원인은 입력 소유자 상태가 아니다. 남는 후보: (a) 패널 위젯 자체의 히트테스트/활성 플래그(패널 파트가 +0x08=1이지만 클릭 라우팅이 서브패널 셀 0x02216C80=1 분기에서 다른 매니저로 가는 경우), (b) 첫 클릭이 래치 해제에 소비되고 **두 번째 클릭부터** 컨트롤에 닿는 경우 — 이전 런의 컨트롤 클릭이 첫 클릭이었는지 재검토 필요.
- 다음 최소 라이브 검사(밀봉 런 1회): 패널 열기 → 빈 영역 1클릭(래치 해제) → 컨트롤(閉じる/OK) 1클릭 → 캡처+덤프. 반응하면 (b) 확정, 조건 2/7/8의 조작 절차가 곧바로 열린다.
- 덤프 간 21개 dword가 0→2→1→0으로 도는 0x02214778..0x02214944 블록은 프레임별 상태 배열(입력 이벤트 링)로 보이며 게이트가 아니다.

### 두-클릭 검사 결과 (런 20260902T232632Z, D1~D4)
- 순서: 로비(D1) → 環境設定 클릭(122,481)(D2) → 빈 영역 클릭(600,300)(D3) → 戻る 클릭(763,582)(D4), 각 단계 VNC 캡처.
- 戻る 프레스는 정확히 (763,582)에 기록됐으나(+0x160/+0x164) **패널은 닫히지 않았다**(vnc-r3-after-modoru-click.png). 가설 (b) "첫 클릭이 래치 해제에 소비" 기각.
- +0x194/+0x294는 D1 0x100 → D2 0 → D3 0x100 → D4 0: 클릭마다 토글되는 값(이벤트 링 파싱 위상)이며 게이트가 아니다. 이전 "래치" 해석도 철회.
- 0x02216C80=1, 0x02216C28=1, 0x02216C34=2는 패널이 열린 동안 불변.
- 남은 원인은 패널 컨트롤 라우팅/히트테스트(후보 a). 다음 최소 검사: 같은 戻る를 게스트측 SetCursorPos+mouse_event로 1회 클릭(전략 그리드가 요구한 전송) → 반응 여부.
- 게스트측 SetCursorPos+mouse_event로 戻る(763,582) 1회 클릭(gclick-modoru.json, ONE_CLIENT_CLICK_SENT)도 **무반응**(vnc-r4-after-guest-modoru.png, 패널 유지). VNC 클릭·게스트 클릭·키보드(이전 런 Down/決定)가 모두 무시되고 프레스는 정확히 기록되므로, 로비 서브패널의 컨트롤 라우팅(히트테스트 결과를 소비하는 매니저 분기)이 원인이다. 라이브로 더 좁힐 최소 입력은 없다 → 정적 분석 단위로 전환: 패널 생성 경로(0x02216C20 설정 객체를 채우는 0x5210d9 부근)와 그 매니저의 파트 목록/+0x08 활성 플래그, 그리고 FUN_005015F0 디스패처가 파트에 히트를 배달하는 조건.
- 픽셀 정량: 戻る 내부는 r1~r4에서 동일(호버/눌림 시각 상태 없음). 패널 본체는 반투명이라 별하늘 애니메이션 때문에 제목 영역은 캡처마다 달라짐(비교 영역으로 부적합).
- **릴리스 미기록**: 3개 런 12개 덤프(응답 로비 포함) 전부 +0x154=0, +0x158/+0x15c=(0,0). 프레스는 매번 기록되는데 릴리스는 한 번도 없다. 로비 메인 버튼(環境設定 등)은 프레스로 발화하므로 동작하고, 서브패널 컨트롤(戻る, 로터리 목록, 종료 다이얼로그 決定, 카드 承認)이 릴리스(button-up)로 발화한다면 관측 전부가 설명된다. 다음 판별 입력(런 1회): 戻る에 느린 클릭(mousedown → 0.5s → mouseup) 1회 후 캡처+덤프. 닫히면 "빠른 클릭의 up 엣지 유실"(폴링 간격/버퍼 크기)이며 hold-click이 조건 2/7/8의 조작 절차가 된다; 안 닫히면 릴리스 생산자(입력 소유자 +0x154/+0x158 기록 코드)를 정적으로 추적한다.
- 느린 클릭 검사(런 20260902T233650Z): 戻る에 mousedown → 0.5s → mouseup 1회. 프레스는 (763,582)로 기록됐으나 릴리스는 여전히 미기록(+0x154=0, +0x158/c=(0,0)), 패널 유지(vnc-r3-after-slow-modoru.png; 이 캡처에서 배경 별하늘이 밝은 회백색으로 바뀐 점은 원인 미상, 기록만). "빠른 클릭의 up 엣지 유실" 가설 기각. → 릴리스 생산자(입력 소유자 +0x154/+0x158 기록 코드)가 이 입력 모드에서 아예 실행되지 않는다는 뜻. 정적 단위: 바이너리에서 disp32=0x154/0x158/0x15c 쓰기 명령을 찾아 그 함수(폴러)의 분기 조건(+0x24 디바이스 모드 등)을 읽는다.

### 정정 — 마우스 생산자 해독 (정적, G7MTClient 0x500D90~0x501130, 입력 소유자 갱신 FUN_00500580)
- FUN_00500580은 전역을 입력 소유자로 복사한다: +0x134/8 ← 0x779b10/14(커서), **+0x154 ← 0x2214be0(우클릭 프레스 대기)**, +0x155 ← 0x2214be1(좌클릭 프레스 대기), **+0x158/c ← 0x2214bb8/bc(우클릭 지점)**, +0x160/4 ← 0x2214bd8/dc(좌클릭 지점), +0x00..+0x2c ← 0x2214bf8..0x2214c30: +0 우 down-now, +4 우 down-prev, +8 좌 down-now, +0xc 좌 down-prev, +0x10 우 릴리스 엣지, **+0x18 좌 릴리스 엣지(0x2214c10)**, +0x24 ← 0x2214c28(커서가 클라이언트 영역 안: FUN_00500B60은 "디바이스 모드"가 아니라 이 플래그).
- 눌림 판정(0x500edb~): 커서가 창 안이면 GetAsyncKeyState(VK_LBUTTON)<0 또는 DI 상태 [0x221442c]≥1. 새 프레스 = down-now & !down-prev → be1=1, 지점 기록. 릴리스 = down-prev & !down-now → 0x2214c10=1(1프레임), be1 클리어. 모든 엣지 전역은 매 폴 시작(0x500e53~0x500e77)에 0으로 초기화되므로 초 단위 후 덤프로는 절대 보이지 않는다. → "릴리스 미기록" 주장 철회. 이전에 "+0x154 릴리스 대기"로 적은 해석도 철회(우클릭 프레스).
- +0x194/+0x294는 FUN_005009D0(키보드 상태기; 256키 현재/이전 배열, 반복 카운터 배열 0x2214774..0x2214b74)의 것이고 마우스와 무관.
- 결론: 프레스·릴리스 생산은 정상으로 보이며, 서브패널 컨트롤 무반응은 위젯 측(owner+0x18/+0x155 getter를 호출하는 패널 파트의 히트·활성 판정)에 있다. 다음 정적 단위: 0x5007a0~0x500980 getter 열거 → 좌 릴리스 getter의 호출자 → 서브패널 버튼 클래스의 클릭 판정 분기.
- getter 해독(0x500820~0x500950): FUN_00500820 = 커서(+0x134/8); **FUN_00500870 = 좌클릭 "발생" — +0x155 프레스 대기 OR (+0x18 좌 릴리스 엣지 ≠ +0x1c 이전 엣지 이고 ≠0)이면 프레스 지점 +0x160/4 반환**; FUN_005008E0 = 우클릭 동일(+0x154, +0x10/+0x14, +0x158/c). 즉 위젯은 프레스로도 릴리스로도 발화할 수 있고, 두 경로 모두 생산자가 정상이다. 서브패널 컨트롤 무반응은 FUN_00500870을 호출하는 파트(버튼) 클래스의 히트/활성 판정 또는 FUN_005015F0 배달 분기에 있다.
- 레인 판단: 라이브 최소 입력(VNC 클릭·게스트 클릭·느린 클릭·이동·맨 LEFTUP·키보드)과 읽기 전용 덤프로 좁힐 수 있는 범위는 다 썼다. 남은 것은 디컴파일 수준의 정적 단위(FUN_005015F0 → 파트 핸들러 → FUN_00500870 호출자 중 서브패널 버튼 클래스)이며, 이는 별도 세션 단위로 인계한다. 조건 2/7/8은 이 단위가 풀리기 전까지 PARTIAL(정적/라이브 관측)로 유지.

## 게스트 런타임 사본 소실 사건 (런 20260902T233650Z) — 레인 위험
- clean-stop이 PG_CTL_NOT_FOUND_IN_RUN_COPY로 실패(guest-clean-stop.ps1을 try 안으로 강화해 영수증으로 진단). 게스트 census: 런의 postgresql 폴더는 있으나 1,563개 중 **사용 중이던 13개(postgres.exe+로드된 DLL)만 남고 1,550개가 준비(23:36Z)~23:43Z 사이에 사라짐**. 서버 배포·클라이언트 사본도 같은 위험(실행 중 파일만 살아남음).
- 복구: 새 `guest-restore-runtime.ps1`(봉인 zip sha 검증 → 없는 파일만 추출, 덮어쓰기·삭제 0) → RUNTIME_RESTORED(1,550 복원) → clean-stop RUN_RUNTIME_CLEANLY_STOPPED(pg_control 'shut down') → cleanup → census 잔여 0. 원본·봉인 소스 무손상.
- 원인 후보: 게스트 %LOCALAPPDATA%\Temp를 대상으로 한 임시 파일 정리기(Storage Sense/예약 작업). 읽기 전용 census 스크립트 `guest-census-temp-cleaners.ps1`로 확인(설정 변경 없음). 다음 런부터는 준비 직후와 각 단계 전에 런 사본 파일 수 census를 넣거나, 레인 루트를 Temp 밖으로 옮기는 안을 사용자 결정 사항으로 인계.
- **원인 확정(읽기 전용 census, census-temp-cleaners.json)**: HKCU StorageSense\Parameters\StoragePolicy = {01:1(활성), 04:1(앱이 사용하지 않는 임시 파일 삭제), 2048:0(실행 주기 = 디스크 여유 부족 시), 08:1, 128/256:30}. 런 사본(PG 134MB+서버 79MB+DB 64MB)이 올라가면 C: 여유가 180~300MB로 떨어져 Storage Sense가 %LOCALAPPDATA%\Temp의 미사용 파일을 지운다. 창 내 실행된 예약 작업은 OneDrive Reporting·OneSettings RefreshCache뿐(무관). Storage Sense 설정 변경은 금지 항목이므로 하지 않았다.
- 대책(사용자 결정 필요): (a) 레인 루트를 Temp 밖(예: C:\Users\logh7-oracle\logh7-l1)으로 이전 — 봉인 소스 런(20260902T083838Z)과 모든 게스트 스크립트의 루트 상수 변경 필요; (b) 런 전 C: 여유를 충분히(>1GB) 확보 — 옛 런 디렉터리는 증거이므로 호스트 사본 존재 확인 후에만 정리 가능; (c) 준비 직후·각 단계 전 파일 수 census(RUNTIME 파일 수 1,563 기준)로 소실 조기 감지 + restore 스크립트 자동 적용.
- **레인 루트 크기 census(census-lane-sizes.json, 읽기 전용)**: C:\Users\logh7-oracle\AppData\Local\Temp\logh7-l1 아래 352개 런 디렉터리 합계 31,036MB, 그중 postgres-data(pg_control 존재) 사본이 있는 런 242개·29,006MB. 2026-08-29~30 런들은 server 배포(79MB)+DB 사본(300~440MB)을 그대로 보유. C: 여유 496MB → Storage Sense "여유 부족 시" 조건이 상시 충족된다.
- 이 세션 런 4개(220359Z/221133Z/222326Z/224357Z)는 소스 083838Z·pg_control 348153D8…·정상 종료 확인 후 파생 DB 사본만 -DeleteData로 정리(영수증 cleanup-data.json). **옛 런 242개의 DB 사본(29GB)은 DB 삭제 금지 제약에 해당하므로 사용자 결정 사항**: 소스 체인(083838Z 이전 소스 런들)만 보존하고 나머지 파생 사본을 정리하면 Storage Sense 트리거가 사라진다. 호스트 runs/ 디렉터리에 각 런의 영수증 사본이 있는지 확인한 뒤 진행하는 것을 권고.

## constmsg.dat 형식·명령 카탈로그 (조건 6/11/16) — docs/reverse-engineering/constmsg-catalog.json
- data\msgdat\constmsg.dat: 'HFWR' | u32 0 | u32 3199(문자열 수) | u32 120(그룹 표 길이) | 그룹별 첫 문자열 인덱스(누적, 마지막 3199) | 0x1F0부터 NUL 구분 CP932 문자열 3199개. lookup(group,row)=strings[groupStart[group]+row].
- **그룹 18 = 전략 명령 이름 표 97개, 행 = 명령 id**: [0]昇進(명령 0, 승진 경로), [43]ワープ航行(0x2B = 서버 StrategicWarpCommandId) 일치. 조건 11 도메인 매핑: 補給계 44 燃料補給·62 完全補給·64 補充·65 搬出入, 수리 61 完全修理, 생산 35 艦艇建造·31 施設建設·41 エンジン建設, 인사 0 昇進·1 抜擢·2 降等·5 任命·36 募兵, 정치 15 国家目標·21 統治目標·30 税率変更·37 予算配分, 외교 20 外交·12 会談, 경제 39 航路貿易·40 船団貿易·16 納入率変更·17 関税率変更, 물류 28 輸送計画·29 輸送中止, 통신 42 メッセージ変更. 조건 16의 "죽은 메뉴 0" 기준 목록으로 사용.
- 그룹 5 = 계급명(행 19 一等兵, 20 二等兵). 그룹 103 = 로그인 결과 메시지(행 1 バージョンが違います。, 행 2 サーバーが混み合っています。). 승진 다이얼로그가 헤더=(103,2), 버튼=(103,1)을 그린 것 → 다이얼로그가 참조하는 (group,row)가 서버가 보낸 값(0x1200 BEGIN 에코/0x1201 END 바이트/0x1209 쌍)에서 파생되며 현재 값이 그룹 103으로 떨어진다. 다음: exe에서 그룹 103(push 0x67) 참조 사이트와 0x1209 핸들러의 문자열 조회 인자를 추적해 권한이 보내야 할 정확한 값을 확정.
- **승진 다이얼로그 정적 지도(클라이언트)**: 수신 디스패처(둘째, world 상대) 0x4bd686: 0x1200 → [world+0x48744c]에 BEGIN 에코 0x24바이트 저장 → FUN_004C1DD0(플래그 +0x487471/2=1, 여러 목록 카운터 0 초기화, UI 이벤트 0x1200 게시 FUN_00517CD0); 0x1201 → [world+0x487470]에 1바이트 → FUN_004C1E50(플래그 0, UI 이벤트 0x1201 게시); 0x1209 → [world+0x49c91c]에 43바이트 → FUN_004C21E0: 랭크 u16을 [world+0x585328]에 누적(개수 +0x585324, 최대 21). 점프테이블 0x4be2e8 = 0x1201..0x120F 핸들러(0x1202 0x4bd825, 0x1203 0x4bd890, 0x1204 0x4bd8c5, 0x1205 0x4bdadd, 0x1206 0x4bd968, 0x1207 0x4bd99d, 0x1208 0x4bd8fa, 0x120A 0x4bd9d3, 0x120B 0x4bda08, 0x120C 0x4bda3d, 0x120D 0x4bda73, 0x120E 0x4bdaa8, 0x120F 0x4bd85a).
- 다이얼로그 컨트롤러 0x56Axxx가 목록 위젯([edi+0x22c])을 (data=world+0x585328, count=+0x585324, formatter=0x2213e90)로 바인딩(0x56a736); 같은 함수가 0x584510, 0x585358/0x585354 등 다른 simple-info 목록도 바인딩한다. 헤더('サーバーが混み合っています。'=(103,2))·둘째 버튼('バージョンが違います。'=(103,1))은 이 컨트롤러의 텍스트 바인딩에서 constmsg(103,row)로 계산되는 것으로 보이며, row 1/2의 출처(BEGIN 에코 +0x48744c의 필드? 0x1201 END 바이트? 선택자 0x11?)를 확정하려면 0x56a600~0x56b000의 텍스트 바인딩(FUN_00522010 호출 인자)을 읽어야 한다. constmsg lookup = FUN_00522010(this=0x2217400, group, row); 범위 밖 기본 문자열 0x670904/0x670910.

## 조건 11 프로브 준비 — 추가 카드 명령 env 가드
- OriginalWorldBootstrapCodec.cs: `LOGH7_EXTRA_CARD_COMMANDS="62,61"`(기본 미설정=변화 없음)이면 EncodeStaticCards/EncodeStaticCardCommands가 권한 카드(39)의 명령 목록 [0, 0x2B] 뒤에 지정 id(1..96, 0x2B 제외, 최대 22개)를 덧붙인다(게이트 바이트는 명령 0과 동일 0xff,0xff,0x1f). 목적: 무수정 클라이언트가 constmsg 그룹 18의 명령(62 完全補給, 61 完全修理)을 카드에 표시하고 선택 시 보내는 요청 타입을 권한 와이어 영수증에서 관측 → 도메인별 코덱 구현의 진입점.
- host-run/guest-prepare: `-ExtraCardCommands "62,61"`(숫자·쉼표만 허용) → 서버 env. 빌드 zip: work/20260902-notify-message-codec/logh7-server-extracmd-win-x64.zip sha DA791001A8EC2EEADD3F47AA547389D531FBA6535F30A1CFB3A41E8C45691B34 (Logh7.Server.dll E27F57F7A74FD7712506083DFF02A24EB27004D51D9F57333E8C589D140DE796, exe 03F87AB7… 불변).
- **프로브 결과(런 20260903T000748Z, 서버 zip DA791001…, LOGH7_EXTRA_CARD_COMMANDS=62,61)**: 권한 카드에 4개 명령이 렌더됨 — 昇進(722,283)·ワープ航行(822,283)·完全補給(923,283)·完全修理(722,310)(vnc-s4-card.png). 즉 서버가 보내는 명령 id → 클라이언트가 constmsg 그룹 18 이름으로 표시(조건 16 "죽은 메뉴" 기준의 라이브 확인). **完全補給(62) 클릭 → 애플리케이션 프레임 없이 connection-closed, 클라이언트 프로세스 소멸**(vnc-s5-kanzen-hokyu.png는 게스트 바탕화면, census에 클라이언트 없음). 클라이언트가 명령 62의 다이얼로그를 여는 데 필요한 정적/동적 데이터(보급 대상 함대·물자 레코드 등)가 없어 크래시한 것으로 보임 → 크래시 census(Application Error 1000의 결함 오프셋)로 정적 추적 대상을 확정한다. 이전 런과 달리 요청 타입은 관측되지 않았다.
- **크래시 서명(WER, 읽기 전용 guest-read-wer.ps1)**: AppCrash G7MTClient.item114.exe(타임스탬프 40779eb8) / 결함 모듈 동일 / 예외 c0000005 / **오프셋 0x00171A1D → VA 0x571A1D** / EventTime 134328678265416031 = 2026-09-03T00:10:26Z(클릭 직후). 같은 큐의 다른 G7MTClient 보고서(BEX StackHash_2264, korean-client 0x0012202f)는 이전 세션 크래시. 영수증: docs/reverse-engineering/condition-11-card-command-probe-verification.json. 정적 다음 단위: 0x571A1D 부근의 역참조가 기대하는 서빙 테이블/레코드 해독 → 권한이 제공 → 재프로브로 요청 타입 관측.
- **크래시 정적 해독(FUN_00571870 = 職務権限カード 명령 패널 빌더)**: [ebp+0x4EC]=선택 명령 id를 표 0x6756B0~0x675738(8바이트 쌍 (cmdId, targetKindIdx), **17개: 0 昇進, 1 抜擢, 4 叙勲, 5 任命, 12 会談, 14 演説, 15 国家目標, 16 納入率変更, 20 外交, 21 統治目標, 24 発令, 26 部隊解散, 27 講義, 28 輸送計画, 30 税率変更, 31 施設建設, 33 施設再稼動**)에서 찾아 vtable+0x34(this, cmd, 0x78BB30[idx])로 대상 선택 위젯을 만든다(0x78BB30 포인터 표 → "TARGET_SELECT_S_CARD/UNIT/BASE/STRATEGY/RANK" 문자열; idx 6은 NULL). 이어서 명령 파라미터 템플릿 [ebp+0x4F4..0x4F8](8바이트 (kind,value))을 순회하며 kind≠6이면 인자 객체([esp+0x60])의 자식 목록 [+0x10..+0x14]에서 이름(+8)이 value 문자열과 같은 요소를 FUN_005736D0(strcmp 0x600D3A)로 찾는다. **62/61은 표에 없어 위젯이 만들어지지 않고, 템플릿이 가리키는 이름의 요소가 없으면 [esp+0x10]=0 → 0x571A19 `mov esi,[esp+0x10]` → 0x571A1D `cmp [esi+0x14]` 널 역참조(c0000005) — 관측된 크래시와 일치.** 43 ワープ航行도 표에 없지만 별도 경로(그리드 선택)로 처리된다.
- 결론: 카드에 실을 수 있는 명령은 위 17개(+43)뿐이며, 62 完全補給/61 完全修理(및 44 燃料補給, 64 補充, 65 搬出入 등)는 유닛/함대 명령 패널의 소관 — 그 패널의 명령 목록 출처(서빙 정적표 또는 클라이언트 리소스)는 별도 단위. 권한 측 NEW_DESIGN 게이트: 카드 명령 목록에는 이 17개(+43) 밖의 id를 절대 싣지 않는다(실으면 클라이언트 크래시). 다음 프로브: 30 税率変更, 31 施設建設, 20 外交, 5 任命을 카드에 실어 각 다이얼로그·요청 타입 관측(조건 11 정치/경제·생산·외교·인사 진입점).
- **프로브 2(런 20260903T001754Z, LOGH7_EXTRA_CARD_COMMANDS=30,31,20,5)**: 카드에 6개 버튼 렌더(昇進·ワープ航行·税率変更(923,283) / 施設建設(722,310)·外交(822,310)·任命(923,310)).
  - **任命(5, TARGET_SELECT_S_CARD)**: 클라이언트가 0x1200(선택자 포함)을 보냄 → 권한이 빈 목록으로 응답(Success, 16B) → 클라이언트 「実行不可 / 選択可能な項目が存在しません。」 다이얼로그 + 메시지창 "任命コマンド選択を行います。/ 選択可能な項目が存在しません。" (vnc-s6-ninmei.png). 권한 판단과 일치하는 가시적 사유 = 조건 7 유형의 정상 경로. 決定(639,436)으로 닫힘.
  - **税率変更(30, 대상 없음)·外交(20, RANK)·施設建設(31, CASTPLANET)**: 요청·다이얼로그·메시지 없이 무반응(vnc-s5/s8/s9). 클라이언트측 전제조건(통치 행성/외교 직위/기지 보유 등, 정적 정보에서 판정) 게이트로 추정 — 원본이라면 実行不可 사유가 떠야 하므로 조건 7/16 항목. 다음: 이 세 명령의 게이트 분기(FUN_00571870 vtable+0x34 팩토리 이후, 명령별 실행 가능 판정)를 정적으로 읽어 필요한 정적 정보(Base/통치/직위)를 서빙.
  - 권한 개선 필요: 승진(0x11) 외 0x1200 선택자 값을 영수증에 기록(任命 선택자 확인용) — 코드 반영 예정.
  - **비활성 렌더 발견**: 任命 다이얼로그를 닫고 카드를 다시 열자 税率変更·施設建設·外交 버튼이 어두운 색(7,70,140)으로, 昇進·ワープ航行·任命은 밝은 색(13,114,218)으로 그려짐(vnc-s8/s9). 첫 열람(vnc-s4/s5)에는 6개 모두 밝았다 → 클라이언트가 명령별 전제조건을 지연 평가해 비활성으로 표시하며, 침묵 no-op는 그 비활성 상태의 클릭 무시. 서빙한 0x0307 게이트 바이트는 6개 동일하므로 이 판정은 클라이언트 자체 로직(정적 정보: 통치 행성/직위/기지 등)이다. 조건 7 "활성/비활성이 권한과 일치"를 위해 권한이 이 전제조건 데이터를 서빙하거나(정적 단위: 판정 함수 해독), 비활성 사유를 노출해야 한다. 영수증: condition-11-card-command-probe-2-verification.json.
  - 정정: 세 버튼은 카드 재열람이 아니라 **첫 명령 클릭(税率変更) 직후**에 이미 어두워졌다(vnc-s5, x−30 지점 (12,75,143) vs 활성 (13,114,218)). 즉 첫 열람 상태는 미평가(전부 활성)이고 첫 명령 클릭이 명령별 전제조건 재평가를 유발한다.

## 승진/抜擢 다이얼로그 오문자열의 정체 — constmsg 그룹 오프셋 가설 (조건 6/16, 데이터 복구)
- 프로브 3(런 20260903T002753Z, 명령 1 抜擢·4 叙勲·27 講義·28 輸送計画): 抜擢(1, TARGET_SELECT_S_RANK)은 昇進과 **동일한 랭크 사다리 다이얼로그**를 띄움(0x1200 전송, 목록 二等兵→一等兵, 안내문 "左欄より階級ラダーを選択してください" 정상, 헤더 'サーバーが混み合っています'·둘째 버튼 'バージョンが違います' 오문자열 동일). 둘째 버튼(830,608)으로 취소 → 카드 재열람 정상.
- 카탈로그 대조: 그룹 21 = 다이얼로그별 좌측 안내문(행 0 階級ラダー … 행 82), **그룹 98 = [0]決定 [1]取消し [2]残り枚数 [3]最低条件階級 [4]実行可能コマンド一覧 … [8]階級 …** — 관측된 둘째 버튼 (103,1)이 (98,1)=取消し에 정확히 대응. 즉 exe는 그룹 103을 참조하지만 복구 CD판 constmsg.dat에서는 그 표가 그룹 98이다 → **CD판 데이터가 exe(업데이트 131 계열)보다 구버전으로 그룹 5개가 부족**하다는 가설. 검증/복구 경로: evidence/official-updates/data1.cab·data2.cab(공식 업데이트 페이로드)에서 신판 msgdat\constmsg.dat 추출 → 그룹 수·(103,1)=取消し 확인 → 게스트 클라이언트 데이터 갱신(원본 불변, 작업 사본만).
- 이 가설이 맞으면 조건 6의 "잘못된 임시 문자열"과 조건 16의 "누락 콘텐츠" 다수가 데이터 파일 버전 불일치 한 건으로 수렴한다.
- 프로브 3 계속(런 002753Z): **叙勲(4, TARGET_SELECT_S_CARD)** → 클라이언트가 **0x0F08**(0x1C바이트, 메일 목록과 동일 형태)을 보냄 → 권한이 메일 목록으로 응답(40B) → 다이얼로그 없음, 대신 HUD 우하단 메일 아이콘 점등(클라이언트가 메일함 갱신으로 해석). 0x0F08은 메일 전용이 아니라 카테고리를 가진 일반 목록 요청으로 보이며, 카테고리 필드 확정을 위해 권한 frame-processed에 월드 상태 한정 requestPayloadHex를 추가(자격 프레임은 이전 상태에서만 오므로 비밀 노출 없음). **講義(27)·輸送計画(28)**: 침묵(요청·다이얼로그 없음). 자연 종료(ESC→ゲーム終了 決定) 후 정리.
- 공식 업데이트 페이로드(evidence/official-updates/G7UPD040514.exe + data1/data2.cab, InstallShield)에서 신판 constmsg.dat을 꺼내려면 unshield가 필요한데 현재 호스트(WSL docker-desktop만)·Windows 경로에 없다. 이전 CD 추출은 `unshield -L`로 수행됐음(docs/handoffs/2026-08-24-original-client-install-handoff.md). 도구 확보는 사용자 결정(외부 다운로드 금지 제약).
- 뉘앙스 정정(런 002753Z): 抜擢 취소 후 재열람 캡처(vnc-s4)에서 叙勲·講義·輸送計画이 어두운 색(12,75,143)이었는데도 **叙勲 클릭은 0x0F08을 보냈다** → 어두운 렌더 = "클릭 무시"가 아니라 별도 상태(예: 실행 가능 판정 미완/대기)일 수 있다. 침묵 no-op(講義·輸送計画·税率変更·外交·施設建設)와 어두운 렌더의 관계는 아직 미확정 — 정적 단위로 남김.
- **프로브 4(런 20260903T003951Z, 요청 hex 빌드 extracmd4)**: 任命(5)의 0x1200 **선택자 = 0x0012**(승진 0x0011 다음 값; ResponseMetadata에 기록). frame-processed의 requestPayloadHex는 암호화된 외부 바디라 해석 불가 → 세션 복호 페이로드(decoded.Payload) 기록으로 바꿔야 함(월드 상태 한정). 이번 런에서 **叙勲(4) 클릭은 침묵**(0x0F08 없음) → 런 002753Z의 0x0F08은 叙勲 귀속이 불확실(사다리 취소 12초 뒤 발생; 주기적 메일함 갱신 가능성) — 영수증 표현을 "관측·미귀속"으로 완화.
- 다음 정적 단위(조건 11 인사 수직 경로): 任命 다이얼로그(선택자 0x12)가 기대하는 목록 알림 타입(0x1202~0x120F 중) → 권한이 임명 가능한 職務(카드) 목록 서빙 → 선택 후 클라이언트 실행 요청(승진의 0x0704 유사) 해독 → 권한 DB 변경 → 재접속 확인.
- **클라이언트 0x120x 알림 → world 목록 셀 매핑(점프테이블 0x4be2e8, 저장 루틴 헤드 해독)**: 0x1202→0x4c83a0/4 · 0x1203→0x4c17c8(cap 300) · 0x1204→0x4c4b5c/60 · 0x1205→0x620958/5c(cap 180) · 0x1206→0x580368/6c/70(cap 100) · 0x1207→0x58068c/90/94(cap 2000) · 0x1208→0x584510(cap 300) · **0x1209→0x585324/0x585328(랭크 사다리, cap 21)** · **0x120A→0x585354/0x585358(cap 100, 레코드 296B)** · 0x120B→0x61b35c/60(cap 100) · 0x120C→0x61c7b0/b4 · 0x120D→0x61da74 · 0x120E→0x58c6f8/fc(cap 200) · 0x120F→0x554da4/a8(cap 600). 0x120A 와이어: [count u8][3 pad][count × 296B 레코드] (FUN_004C22D0, +4부터 0x128 stride).
- 任命 선택자 0x0012 다이얼로그가 바인딩하는 목록은 컨트롤러 0x56A7xx의 바인딩 순서상 0x120A(0x585358) 후보. 확정 방법: 0x1200 요청 빌더에서 명령→선택자 표를 찾고, 선택자별 다이얼로그 분기가 어느 셀을 바인딩하는지 읽는다. 라이브 대안: 권한이 0x120A에 이름 문자열을 실은 296B 레코드 1건을 보내 任命 목록에 표시되는지 관측(레코드 내 이름 오프셋은 별도 해독 필요).
- **정적 정정(선택자 0x12의 목록 원천)**: 0x568380은 다이얼로그 매니저 생성자(서브패널 4개: +0x98/+0x3B8 선택자 0x17 데이터 world+0x3E098C, +0x6D8 선택자 0x10 데이터 world+0x35F35C, +0x9200 선택자 0x12 데이터 world+0x36A488). world 표 채움 핸들러는 세션 서버 0x02xx 계열(점프테이블 0x4BDE7C, base 0x0201): **0x0218 ResponseInformationPackage→0x36A488(340B)**, 0x021B ResponseInformationOutfitParty→0x35F35C(8,900B), 0x021A ResponseGridInformationOutfit→0x367E60, 0x021C ResponseOutfitInformationUnit→0x368C74, 0x020E ResponseStaticInformationArms→0x3F5902. 그러나 任命의 実行不可는 0x1200(선택자 0x12) 응답 직후 표시되므로 목록 원천은 응답에 실리는 0x120x 알림이다. 규칙 가설: 선택자 s ↔ 알림 0x11F8+s (0x11→0x1209 관측 일치) → 0x12→0x120A(FUN_004C22D0: [count u8][pad3][count×296B], cap 100, world+0x585358).
- 권한 프로브 구현(env LOGH7_NINMEI_PROBE=1): 선택자 0x12에 BEGIN 에코 + 0x120A 레코드 1건(8바이트마다 "P000","P008",… ASCII 토큰; 선두 u16 id=1) + END. 任命 목록에 토큰이 보이면 (a) 0x120A 가설 확정, (b) 렌더된 토큰이 이름 필드 오프셋을 알려준다.
- **프로브 5(런 20260903T005117Z, extracmd5, LOGH7_NINMEI_PROBE=1)**: 任命 선택자 0x12에 BEGIN + 0x120A 토큰 레코드 1건 + END를 보냈으나 클라이언트는 여전히 実行不可「選択可能な項目が存在しません。」(vnc-s5-ninmei.png; 와이어 meta=ninmei-probe-list-served;notify=0x120A;records=1). → "선택자 s ↔ 0x11F8+s" 규칙은 0x12에서 미확인(0x120A가 아니거나 레코드 필터링). 문자열 위치: 実行不可=(97,0), 選択可能な項目が存在しません。=(97,8), "%sコマンド選択を行います。"=(95,3). (97,0) 조회 사이트 0x53B7D5(FUN_0053C020 메시지 방출)와 0x57B6A3 — 판정 조건은 0x53B6A0 부근을 읽어 확정. 영수증: condition-11-ninmei-0x120A-probe-verification.json.
- 정적 보강: 「選択可能な項目が存在しません。」 문자열은 정적 조회 사이트가 없고(행이 레지스터 경유), 0x7C1BB8은 빈 std::string 센티널. FUN_00579E60 = 카드 명령 실행 디스패처: edi = FUN_00576EC0(목록 위젯 this+0x244, [this+0xB2C]) = 선택 레코드(없으면 0), [this+0x234]−2(종류 2..0x14) 스위치(표 0x579F5C, 인덱스 바이트 0x579F84) → 0x57A1F0(edi) / 0x57AA90(edi) / **0x57B640(edi, 0)**. FUN_0057B640: 레코드 NULL이면 FUN_0057B7B0(빈 문자열 다이얼로그), 아니면 (97,0) 実行不可 + FUN_004C8D70 이름 + 사유 [rec+0x10]+1 포맷. 
- 결정적 동적 검사(다음 런): 任命 클릭 직후 읽기 전용 RPM으로 world 베이스([0x7CCFFC]) → world+0x585354(0x120A 카운트)·0x585358(레코드)·0x487470~0x487472(BEGIN/END 플래그)를 덤프. 카운트=1이면 "0x120A는 도착했지만 任命 목록이 아님", 0이면 "응답 파싱/전달 문제".
- **프로브 6(런 20260903T005850Z, 읽기 전용 RPM)**: 任命 응답 직후 world 베이스 0x08AF0020; world+0x585354(0x120A 카운트)=0, 레코드 영역 0 — **0x120A 프레임은 저장되지 않았다**. 반면 BEGIN 에코 world+0x48744C = `02 00 00 00 | 12 00 | 7B 00 …`(선택자 0x0012 도착), 0x487470=01(END 처리). 원인: 클라이언트 수신 크기 표(0x4B9B27 디스패처, 표 0x4BA23C)가 타입별 **고정 바디 크기**를 요구 — 0x120A는 0x73A4=4+100×296(=cap×레코드), 0x1209는 0x2B=1+21×2. 내 300바이트 0x120A는 크기 불일치로 폐기. 수정: 항상 cap×레코드 전체 블록을 보낸다(count=1, 레코드0 토큰, 나머지 0). 이 규칙은 모든 0x120x 목록 알림에 적용된다(크기 표는 인계 아래 항목 참조).
- **클라이언트 수신 고정 바디 크기 표(0x4BA23C, 0x4BA224; 크기 불일치 프레임은 폐기)**: 0x1200=36 · 0x1201=1 · 0x1202=57,604 · 0x1203=8,804 · 0x1204=7,204 · 0x1205=804 · 0x1206=1,604(4+100×16) · 0x1207=4,804 · 0x1208=3,604(4+300×12) · **0x1209=43(1+21×2)** · **0x120A=29,604(4+100×296)** · 0x120B=15,604(4+100×156) · 0x120C=8,644 · 0x120D=12,004 · 0x120E=29,244 · 0x120F=29,604 ; 0x1003=4,004 · 0x1005=32 · 0x1006=24 · 0x1007=8 · 0x1008=128. 권한의 모든 목록 알림 코덱은 이 크기를 정확히 맞춰야 한다(NEW_DESIGN 게이트).
- **프로브 7(전체 크기 0x120A, extracmd6)**: 와이어 원본 responsesBeforePrimaryPayloadLengths=[48, 29624](푸시 실제 송신)에도 world+0x585354 카운트 0, **원시 복사 버퍼 world+0x4A2EEC도 0** → 0x120A 핸들러(0x4BD9D3)가 실행되지 않았다. FUN_004B8B00(크기 표)의 둘째 출력은 가변 타입의 레코드 수 계산용(0x120A는 고정)이라 크기 가설 기각. 통과 사례: 0x1209 푸시 9B, 3846 **기본 응답** 29,920B; 실패: 0x120A **푸시** 29,610B → "큰 푸시 프레임" 경로 문제 가설. 다음: 권한의 EncodeApplicationPush vs EncodeApplicationResponse 프레이밍 차이(외부 제어/접두사)를 대조하고, 목록 프레임을 기본 응답 프레이밍으로 보내 보는 런. 영수증: condition-11-ninmei-0x120A-fullsize-rpm-verification.json.
- 프레이밍 대조: EncodeApplicationPush와 EncodeApplicationResponse는 동일(외부 제어 0x0030, 접두사 [0,0,0,0], 같은 시퀀스 카운터). 0x1209 푸시(9B)는 통과, 0x120A 푸시는 300B·29,604B 모두 핸들러 미도달 → 수용은 **타입** 단위(현재 선택자에 짝지어진 알림만). 판별 프로브(모드 2, LOGH7_NINMEI_PROBE=2): 선택자 0x12 응답에 0x1202~0x120F 전부를 각 고정 크기·count=1·토큰("T<type>NNN")으로 보내고, 14개 카운트 셀(0x4C83A0·0x4C17C8·0x4C4B5C·0x620958·0x580368·0x58068C·0x584510·0x585324·0x585354·0x61B35C·0x61C7B0·0x61DA74·0x58C6F8·0x554DA4)을 읽기 전용 RPM으로 읽어 저장된 타입을 확정한다(드라이버 rpm2 단계).
- **프로브 8(모드 2, 런 20260903T011256Z, extracmd7)**: 선택자 0x12 응답에 0x1202~0x120F 전부(고정 크기)를 실었더니 카운트 셀 기준 **저장: 0x1205=1, 0x1206=1, 0x1207=256, 0x1208=256, 0x1209=1, 0x120E=1 / 미저장(0): 0x1202·0x1203·0x1204·0x120A·0x120B·0x120C·0x120D·0x120F**(0x120F 셀은 기존 데이터 '02 00 …'+카타카나 이름 = 다른 용도). 크기 표 재판독 결과 15개 핸들러 모두 `mov [esi],SIZE; mov [edi],0; al=1` 동일 형태로 크기 표는 정확 → 크기가 수용 여부를 가르지 않는다. 0x1207/0x1208의 256은 카운트 필드 위치/폭이 내 가정(u8@0)과 다르다는 뜻. 미저장 8개가 진짜 폐기인지 내 카운트 셀 매핑 오류인지는 각 핸들러의 원시 복사 버퍼(0x1202→0x487474, 0x1203→0x49C948, 0x1204→0x49EBAC, 0x1205→0x4C14A4, 0x1206→0x4A15E4, 0x1207→0x4A1C28, 0x1208→0x4A07D0, 0x1209→0x49C91C, 0x120A→0x4A2EEC, 0x120B→0x4B14CC, 0x120C→0x4B51C0, 0x120D→0x4B7384, 0x120E→0x4AA290, 0x120F→0x495578) 덤프(rpm3)로 확정한다.
- **프로브 9(모드 2 재실행 + 원시 버퍼, 런 ~011840Z)**: 원시 복사 버퍼 기준 **폐기: 0x1202·0x1203·0x1204·0x120A·0x120B·0x120C·0x120D(복사 0)** / **수용: 0x1205~0x1209·0x120E·0x120F(토큰 복사됨, 0x120F도 29,604B로 수용)**. 같은 크기의 0x120A(폐기)와 0x120F(수용)가 갈리고 폐기 집합이 송신 순서상 1~3·9~12번째 블록이므로, 타입이 아니라 **스트림 위치/수신 버퍼 경계**(예: 큰 프레임 뒤 경계에 걸친 프레임 폐기) 가설이 유력. 판별: 모드 3(LOGH7_NINMEI_PROBE=3, 역순 송신)에서 수용 집합이 바뀌면 스트림 효과 확정. 이 규칙은 권한이 여러 목록을 한 응답에 묶어 보낼 때 전부에 영향(NEW_DESIGN 게이트 후보: 프레임당 크기 상한/분할 송신).
- 디스크: 매 런이 C:\ProgramData\LOGH7\FreshRun\<run>\logh7-server-win-x64.zip(35MB)을 남기고 어떤 정리 스크립트도 지우지 않았다 → `guest-clean-stage.ps1`(스테이지가 .ps1 사본 + 알려진 빌드 sha의 zip만 담고 있을 때만 삭제)로 이 세션 런 24개 정리(약 840MB). host-run 자체에 스테이지 정리를 넣는 개선은 미적용(사용자 결정 불필요, 후속 작업).
- **프로브 10(모드 3 역순, 마지막 런)**: 정순(런 011941Z)과 역순에서 원시 복사 버퍼 기준 수용/폐기 집합이 **동일** — 수용 {0x1205,0x1206,0x1207,0x1208,0x1209,0x120E,0x120F} / 폐기 {0x1202,0x1203,0x1204,0x120A,0x120B,0x120C,0x120D}. 스트림 위치·크기 가설 기각, **타입 결정적 폐기** 확정. 수신 경로: 복호 후 FUN_004AE0D0(this,type,frame)(0x202/0x204 특례) → 큐 적재 FUN_004B8850(world,type,frame; 500슬롯, FUN_004B8B00 크기, malloc) → 드레인(0x4B8A58 부근)에서 FUN_004BA2B0(type,buf) → 세션 디스패처 스위치. 폐기는 이 사이 어딘가의 타입별 분기. 영수증: condition-11-ninmei-all-types-raw-forward/-reverse-verification.json. 任命 후보는 수용 목록 어느 것으로도 생기지 않음 → 후보 원천은 미서빙 0x02xx 정보 계열(0x0218 Package 340B) 가설 유지.
- 운영 교훈: 스테이지 정리(guest-clean-stage)는 host-step이 스크립트를 복사하는 폴더를 지우므로 반드시 마지막 단계여야 한다. host-step에 폴더 보장(mkdir) 패치 적용.
- 수신 경로 정적 경계(인계): 디스패처 FUN_004BA2B0 머리는 `call FUN_004C29E0`(카운터 12개 0 초기화) 후 world+0x3579CD가 1이면 메시지를 버리고 플래그를 지운다. 이 플래그는 점프테이블 0x4BE2A0의 케이스 0x4BD3C4/0x4BD407(0x99/0x97 dword 레코드를 world+0x486D2C/0x486F90에 복사하는 0x0F/0x10 계열 응답)이 world+0x487449(요청 대기)가 0일 때 세운다 — 0x120x 타입 결정적 폐기와는 무관. 폐기 지점은 FUN_004B8850(큐 적재)~FUN_004BA2B0 스위치 사이 또는 스위치 0x4BA532 이후의 범위 분기(0x301/0x33B/0x424/0x906/0x1001/0x1200/0x2000 구간) 내 타입별 상태 조건으로 남는다. 디버거 없는 다음 수단: 드레인 루프(0x4B8A58)에서 호출 직전 슬롯 타입 배열(world+0x3552BC, 0x14 stride, 500슬롯)을 읽기 전용 RPM으로 스냅샷해 폐기 타입이 큐에 적재됐는지(적재됐다면 스위치 내부 폐기) 확정.
- **정보 계열 프로브 설계(LOGH7_INFO_PROBE=1, -InfoProbe 1)**: 게임 로그인 수락(0x0206) 직후 AdditionalResponses에 0x0218 ResponseInformationPackage(340B)·0x021B ResponseInformationOutfitParty(8,900B) 토큰 프레임([0]=1, +4부터 "I<type>NNN" 토큰)을 푸시. 任命 다이얼로그 매니저(0x568380)가 world+0x36A488(0x0218)·world+0x35F35C(0x021B)를 데이터로 바인딩하므로, 任命 클릭 후 목록에 토큰이 보이면 후보 원천이 정보 계열임이 확정되고 레코드 오프셋도 드러난다. 수용 여부는 원시 버퍼 world+0x36A488/0x35F35C RPM으로 함께 확인.
- **사고·복구(권한 소스 인코딩 손상)**: PowerShell `Get-Content -Raw`/`Set-Content`(PS 5.1 기본 ANSI 디코딩)로 NaturalAuthoritySession.cs를 편집하다 비ASCII 리터럴이 '?'로 손상됨(빌드 실패 CS8086/CS1010). 이 scratch 파일은 git 미추적. 복구: d02 원본(.worktrees/natural-authority-d02/apps/server/…)에 존재하는 줄 8곳(`・` 4곳, 명령 응답 문구 3, 「命令（返答済み）」)은 원본 줄로 치환, scratch 고유 MoveGridRejectionMessages 일본어 6개는 직전 정상 빌드 zip(extracmd8)의 Logh7.Server.dll #US 힙에서 정식 파싱해 복구(指定グリッドにはワープできません / 現在位置が更新されました。もう一度選択してください / この職務権限カードではワープできません / このコマンドは実行できません / この部隊は指揮できません / 대체문 コマンドは拒否されました). 규칙: 소스 편집은 항상 UTF-8 명시(파이썬 utf-8-sig)로만 한다. scratch 소스는 미추적이므로 세션 종료 전 사용자에게 커밋 여부를 물을 것.
- 빌드 규칙: 서버 빌드는 PowerShell에서 `$env:MSBuildSDKsPath=$null; $env:DOTNET_ROOT="C:\Program Files\dotnet"`로만 한다. Bash에서 `export MSBuildSDKsPath=`(빈 값)로 실행하면 SDK 해석 실패("Microsoft.NET.Sdk을 확인할 수 없음")하고, 이어지는 Compress-Archive는 구버전 publish를 다시 묶어 겉보기 성공처럼 보인다(2026-09-03 extracmd9 첫 두 시도). 성공 판정은 종료 코드 0 + dll 갱신 시각.
- **정보 계열 프로브 결과(런 20260903T014614Z, extracmd9, LOGH7_INFO_PROBE=1)**: 게임 로그인 수락 뒤 0x0218(340B)·0x021B(8,900B) 푸시가 와이어에 실렸지만(meta=info-probe…) world+0x36A488·0x35F35C는 0 — **역시 미도달**. 任命은 여전히 実行不可. 영수증: condition-16-info-family-0218-021B-probe-verification.json. 클라이언트 수신 경로 디버그 문자열: 큐 FUN_004B8850 → 'ＩＮＤＥＸ取得失敗'(크기 표 실패)·'パケット領域確保失敗'(할당 실패)·'MPS_PACKET_MAXSIZE　over!!!'(큐 500 초과, 0x4B88B9); 라우터 FUN_004AE0D0 → '[RobotImp::handle_message] unsupported message = 0x%X'(0x4AE091 부근). 다음: FUN_004AE0D0의 타입 분기 해독; 디버거 없이 폐기 사유를 직접 보려면 작업 사본 클라이언트에 스텁 로거 FUN_005923A0(retail에서 `ret`)을 파일 append로 바꾸는 가역 패치(출처 기록)로 클라이언트 자체 디버그 로그를 켜는 방법이 가장 강력하다.
- 수용 판정 객체 경계: FUN_004AE060(vtable 0x66E0F0 슬롯 2)은 `[this+0x30]->vtable+0x1C(frame, ctx)`가 false면 'unsupported message'를 기록한다. this+0x30 객체의 클래스는 0x66E0F0이 아니며(그 슬롯 7은 단순 setter), FUN_004AE0D0(슬롯 0; 0x202/0x204 특례, 그 외 FUN_004B8850 큐 적재)이 실제 수용기일 가능성은 있으나 미확정. 여기서 정적 추적은 클래스 계층으로 확장되므로 중단하고, 클라이언트 자체 디버그 로그를 켜는 가역 패치 단위로 인계한다.

## 클라이언트 디버그 로그 가역 패치 item115 (진단 전용)
- work/20260903-client-debuglog-patch/G7MTClient.item115.exe (sha BF4A0D5449F07CA0FF0573328AEC645C253188E24353110DE79D776FAB685158, item114 기반 142바이트 차이) + receipt.json. 스텁 FUN_005923A0(`ret`+nop15) → `jmp 0x0066AEFB`; .text 끝 261B 케이브에 로거: pushad 보존, CreateFileA(C:\LOGH7_ORACLE\exe\g7mt-debug.log, OPEN_ALWAYS) → SetFilePointer(END) → 4MiB 상한 → lstrlenA(fmt) → WriteFile(fmt)+CRLF → CloseHandle → popad. 포맷 인자는 전개하지 않음(포맷 문자열만 기록). 원본·item114 불변. 목적: '[RobotImp::handle_message] unsupported message = 0x%X'·'ＩＮＤＥＸ取得失敗'·'<Response…> OK' 등 클라이언트 자체 수신 진단으로 타입별 폐기 원인 확정.
- 실행: 게스트 C:\LOGH7_ORACLE\exe\에 item115 추가 복사(sha 검증) 후 host-run에 클라이언트 변형·해시를 지정(-ClientVariantFile 전달 여부 확인 필요). 로그 파일은 런 종료 후 회수·삭제(재생성 가능 임시 파일).
- item115 갱신: 로그 경로를 사용자 쓰기 가능 위치 `C:\Users\logh7-oracle\AppData\Local\Temp\logh7-l1\g7mt-debug.log`로 변경(sha AE7E8B7F479D37FF430FC7D28EBA0B4D086D8798FE3BB979470F3FB8B4906912, item114 대비 172바이트, 케이브 191B). 게스트 C:\LOGH7_ORACLE\exe는 VIX/사용자 쓰기 불가(VIX 13, Copy-Item 예외)라 설치 트리는 건드리지 않고, 스테이지 `C:\ProgramData\LOGH7\FreshRun\_debuglog\G7MTClient.item115.exe`에 두고 guest-prepare Copy 모드의 새 `-ClientExeOverride`(해시 검증 유지, Copy 모드 전용)로 런별 사본에 복사해 실행한다.
- 레인 스크립트: Copy 모드 오버라이드 시 `-ExpectedClientOverrideSha256`(런치 사본 해시)와 `-ExpectedClientSha256`(설치본 item114 프리플라이트 해시, 기본값 유지)를 분리(한 값으로 두 검사를 하면 FRESH_RUN_INPUT_HASH_INVALID). 디버그 로그 회수는 `guest-collect-debuglog.ps1`(레인 루트 g7mt-debug.log → 런 디렉터리 복사, -Delete 옵션) + 드라이버 'debuglog' 단계.

## 결정적 확정 — item115 디버그 로그 (런 20260903T015932Z)
- 클라이언트 자체 진단 로그: `[RobotImp::handle_message] unsupported message = 0x1202 / 0x1203 / 0x1204 / 0x120A / 0x120B / 0x120C / 0x120D / **0x120F** / 0x218(×3) / 0x21B(×3)`. 즉 현재 Robot(전략 화면 메시지 핸들러)이 이 타입들을 지원하지 않아 큐 적재 전에 거부된다(크기·순서·프레이밍 무관). 이전 '0x120F 수용' 판정은 world+0x495578의 기존 데이터를 오독한 것 → 철회.
- 수용 알림의 원 이름(로그): 0x1205 NotifySimpleInformationGrid, 0x1206 NotifySimpleInformationCharacterEntry, 0x1207 NotifySimpleInformationOrderSuggestCharacter, **0x1208 NotifySimpleInformationCard**, 0x1209 NotifySimpleInformationRank, 0x120E NotifySimpleInformationStrategy/Unit(둘 중 하나; 다른 하나는 다른 타입). 부트스트랩: ResponseWorldInitialize, ResponseGridInitialize, ResponseStaticInformation{GridType,Grid,Card,CardCommand,Base,Arms,Fighters,UnitShip,UnitTroop,PowerDistribution}, ResponseInformation{Character(chr=%d),Unit,MessengerStatus}, ResponseTime, NotifyEnterGridBegin/End.
- 따라서 任命(선택자 0x12)의 후보 목록은 **0x1208 NotifySimpleInformationCard(12B 레코드, cap 300, world+0x584510/+0x584514)** — 같은 다이얼로그 컨트롤러가 0x584510을 바인딩. 다음: 0x1208 레코드 레이아웃(카드 id u16 + …)을 저장 루틴/렌더러에서 확정하고 권한이 임명 가능 職務 카드 목록을 0x1208로 서빙 → 任命 목록 표시 → 선택 → 실행 요청 타입 관측.
- 기타 로그: 'マウスデバイスの生成に失敗しました'·'デバイスの協調レベルの確立に失敗しました'(DirectInput 마우스 생성 실패 → 클라이언트가 Win32 마우스 경로로 동작; 로비 패널 wedge와 관련 가능성 있음, 조건 2/7/8 단서).
- 프로브 모드 4(LOGH7_NINMEI_PROBE=4, extracmd10 zip sha 1C8D4E64…): 선택자 0x12 응답 = BEGIN 에코 + **0x1208 NotifySimpleInformationCard**(3,604B: u16 count=3, pad2, 12B 레코드×300; 레코드 i = u16 cardId=i+1, 나머지 0) + END. 기대: 任命 목록에 카드명(constmsg 그룹 3, id 1..3) 3행. 빈 목록이면 레코드 필터(예: 공석/소속 필드) 해독으로 진행. item115 로그로 'NotifySimpleInformationCard OK' 동시 확인.
- **프로브 모드 4 결과(런 20260903T020628Z, item115 로그)**: 선택자 0x12에 0x1208 카드 레코드(cardId 1..3)만 보내자 `unsupported message = 0x1208` — 모드 2 버스트(0x1205→0x1206→0x1207 뒤)에서는 같은 0x1208이 수용됐다. 즉 Robot의 수용은 **선행 메시지에 의존하는 동적 상태**(정/역순 버스트에서 6개가 모두 수용된 것은 각 타입의 선행 조건이 버스트 안에서 충족됐기 때문일 수 있음). 판별: 모드 5(LOGH7_NINMEI_PROBE=5) = 0x1205·0x1206·0x1207(count=1, 0 레코드) 뒤 0x1208 카드 레코드. 任命은 여전히 実行不可.
- **프로브 모드 5 결과(런 20260903T021212Z)**: 0x1205·0x1206·0x1207 접두 뒤의 0x1208(cardId 1..3, 나머지 0)도 `unsupported message = 0x1208`. 로그 순서(모드 2 런 015932Z): 거부 8건(0x1202~0x1204·0x120A~0x120D·0x120F)이 `TransactionSimpleDataBegin OK`보다 먼저 찍히고, 이후 Grid/Strategy/Unit/Card/Rank/OrderSuggestCharacter OK → `TransactionSimpleDataEnd OK`. 거부는 수신 시점(타입 미등록) 판정이고 수용 타입은 상시 등록 집합인데, 같은 0x1208이 내용에 따라 거부되므로 수신 시 검사는 **페이로드 내용(count/레코드 필드)** 도 본다. 이름 매핑 보정: 0x1205 Grid, 0x1206 CharacterEntry, 0x1207 Strategy, 0x1208 Card, 0x1209 Rank; Unit·OrderSuggestCharacter는 0x120E 및 월드 진입 푸시 계열. 다음: 모드 6(레코드 비id 필드를 0x01로 채움)으로 비0 검증 여부 판별.
- **프로브 모드 6 결과(런 20260903T021831Z)**: 레코드 비id 바이트를 0x01로 채워도 `unsupported message = 0x1208`. 수용된 모드 2 프레임과의 남은 차이 = count(1 vs 3)와 토큰 바이트 → 모드 7(모드 2와 동일한 0x1208 프레임 단독: count 바이트 1, 토큰 레코드)로 분리. 영수증: condition-11-ninmei-0x1208-nonzero-unsupported-verification.json.
- **프로브 모드 7 결과(런 20260903T022414Z)**: 모드 2와 동일한 0x1208 프레임(count 바이트 1, 레코드 ASCII 토큰) 단독 → 로그 `TransactionSimpleDataBegin OK` → `NotifySimpleInformationCard OK` → `TransactionSimpleDataEnd OK`, 거부 없음. 즉 0x1208 수신 검사는 레코드 내용에 걸린다: cardId 1..3 + 0/0x01 채움(count=3)은 거부, count=1 + 토큰(첫 u16=0x3054 등 큰 값)은 수용. 후보 판별 인자: count 값(1 vs 3) 또는 필드 값 범위(예: cardId가 특정 범위/우리 카드 표 밖?). 다음 모드: count=1 + cardId=1(나머지 0) / count=1 + cardId=0x3054(토큰과 같은 id) 두 갈래로 이분.
- 공식 업데이트 G7UPD040514.exe: InstallShield **7**(ver 0x1007000) 헤더 'ISc('가 exe 오프셋 367887(data1.hdr)·401421(data1.cab)에 원본 그대로 내장(두 벌: 10335263도). 파일 275개, 디렉터리에 data\model\images\{Hi,Lo,Mid}, data\model\strategy 포함 → unshield 없이 파이썬(libunshield 로직)으로 목록·추출 가능(진행 중).
- 공식 업데이트 InstallShield 7 헤더(exe @367887): cab 디스크립터(오프셋 0x200, 7,557B), 파일 테이블 @+0x1D85(25,423B), 디렉터리 11(data\model\images\{Hi,Lo,Mid}, data\model\strategy, NetNirvana URL 6), 파일 275. 파일 디스크립터(0x57B, @파일테이블+0x2C)의 필드 배치가 libunshield v5/v6 가정과 달라(이름 오프셋 +0x38 부근, md5 @0x1A 추정) 즉석 파서는 보류. 사용자 승인에 따라 실제 unshield 확보 경로: winget으로 MSYS2 설치 후 `pacman -S mingw-w64-x86_64-unshield`(또는 twogood/unshield 릴리스). 그 뒤 `unshield -d <dir> x` 로 목록·추출 → msgdat\constmsg.dat 존재 시 그룹 수·(103,1) 검증.

## 2026-09-03 03:10Z — 任命 0x1208 count 게이트 확정, 実行不可 메시지 경로, unshield/업데이트 페이로드, 호스트 C: 정리

- **모드 8(count=1, cardId=1) 런 20260903T023227Z**: 클라이언트 수락(`NotifySimpleInformationCard OK`)·다이얼로그 実行不可「選択可能な項目が存在しません」(vnc-s5-ninmei.png). **모드 8b(count=2, cardId=1,2) 런 20260903T030020Z**: `[RobotImp::handle_message] unsupported message = 0x1208` → 수신 게이트는 **count 필드**(1 수락, 2·3 거부). 두 런 모두 자연 종료(決定→ESC→ゲーム終了)·clean-stop·cleanup·census 0·stage 삭제.
- 디버그 로그 순서: 거부 런에서는 `unsupported 0x1208`이 `TransactionSimpleDataBegin OK`(디스패치 로그)보다 **앞**에 찍힘 = 수신 시점 판정. 수락 런은 Begin OK → Card OK.
- 정적 경로(item1 사본, capstone; 스크래치 disasm.py):
  - 수신 경로: 트랜스포트 콜백 → `FUN_004AE060`(vtable 0x66e0f0 slot2, "RobotImp::handle_message") → `[this+0x30]`(= Robot+0x44 = 전역 파스 시스템 `[0x7C2498]`, `FUN_00403A80(0xF000)` 생성, vtable 0x66bf80)->vt+0x1C = `FUN_004049B0(msg, ctx, x)`: `FUN_00405250(&handler, msg)` 조회 실패 → false("unsupported"); 성공 시 handler vt+4(msg,x)·vt+8(...) 후 항상 true. ctx = Robot+0x18(vtable 0x66e0f0) → slot0 `FUN_004AE0D0(type,?,body)` = 큐 enqueue `FUN_004B8850`(500슬롯, 크기표 `FUN_004B8B00`: 0x1208=0xE14 고정, 내용 검사 없음).
  - 0x120x 파서군: `FUN_0055B790`(0x10C 바이트 객체, ctor `FUN_0055B800`; +0xCC = Input_NotifySimpleInformationCard vtable 0x67493C = [binary parse 0x55F670, text parse 0x55F7F0, ...]; +0x60..+0x80 = Input* 포인터 표). 등록: `FUN_004AD1xx`가 `[0x7C2498]+4` 레지스트리(vtable 0x66c138) vt+4(add)로 30여 핸들러 등록(0x55B790 등록 호출 0x4AD2E5).
  - `Input_NotifySimpleInformationCard::input_from_stream`(0x55F670): u16 count(>300이면 "over" 로그), 레코드 12B = {stream vt+0x20 → u16 @0, vt+0x1C → @4, `FUN_00610420(dst=@8, len=1, 0, 2)` 1바이트}, 항상 0 반환. 저장 루틴 `FUN_004C2150`: `[world+0x487472]` 리셋 플래그면 count 0, 이후 **append**(cap 300)로 world+0x584514 에 12B 복사.
  - 0x1200 송신부 `FUN_004C1DC8`(0x4C1E39 push 0x1200 → `FUN_00517CD0(type, body36)`)는 모든 목록 count 셀(0x4C17C8·0x4C4B5C·0x4C83A0·0x554DA4·0x580368·0x58068C·0x584510·0x585324·0x585354·0x58C6F8·0x61B35C·0x61C7B0·0x61DA74·0x620958)을 0으로 리셋.
  - **実行不可 텍스트의 출처**: 명령 패널 상태기 `FUN_00579E60`(index=[this+0x234]-2, 케이스표 0x579F5C/0x579F84; 상태 10·11 → `FUN_0057B640(rec,0)`) → constmsg(97, 0, row=rec[+0x10][1]) — row 8 = 選択可能な項目が存在しません; rec[+0x10][0] = 명령 id(0x4C8D70/0x7CD048로 이름). 즉 로컬 명령 레코드의 **결과 코드 8**. 코드 8을 쓰는 곳(상태 10/11 진입)은 미확정 — 다음 정적 대상.
- **unshield 확보(사용자 승인 "언쉴드 하고싶으면 해")**: winget MSYS2 설치는 무음 실패(ARP 변화 0, C:\msys64 없음). MSYS2 base tarball(sha a2d047e8…) → E: `out/scratch-c-relief/msys`(gitignore /out/) 추출 → pacman gcc/cmake → twogood/unshield 소스 빌드(`msys64/tmp/unshield/build/src/unshield.exe`). **G7UPD040514.exe 내장 블록은 3개**: data1.cab @102953(~265KB), data1.hdr @367887(33,468B), data2.cab @401421(~9.9MB) — 이전 판독(367887=hdr, 401421=cab)은 각 블록 앞 이름 레코드("data1.cab\0Disk1\data1.cab" 등)를 놓친 것. 265개 중 264 추출(out/scratch-c-relief/upd/x). **내용 = data\model\images\{Hi,Lo,Mid} 비트맵(EH0xx 함선 텍스처·d_000~015)과 data\model\strategy\{galaxy,grid,grids,g_board}.mdx 뿐** — constmsg.dat·exe 없음 → 다이얼로그 문자열 오프셋 가설은 이 업데이트로 검증 불가. 조건 5(천체/모델)에는 strategy .mdx 4종이 원본 CD 판과 다른지 비교 가치 있음(미착수).
- 호스트 C: 0MB 사건: MSYS2 추출이 C:\Users\…\Programs 에 148MB를 쓰다 가득 참 → 삭제. 스크래치의 재생성 가능 빌드 산출물(srv 238MB·celestial-exp/ss-loginok-exp/v5probe 각 79MB·zip 2개)은 `out/scratch-c-relief/`(E:)로 이동(원본 소스·봉인 zip은 E: work/ 에 있음). C: 여유 632MB. 스크래치 cmd-probe.ps1 은 이동 영향 없음.
- 다음: (a) `FUN_00405250` 조회 키(타입 외에 count/헤더 필드가 키에 섞이는지) 확정; (b) 상태 10/11·코드 8 설정부(0x1201 End 디스패치 후 후보 계산) 정적 추적 → 任命 후보 소스 확정.
- 모드 9(count=1·cardId=1·비-id 바이트 0x01) 준비 중 헛런 2회: 런 20260903T031330Z(extracmd15 6376D1C8…)는 `NaturalAuthoritySession` 게이트 `NinmeiProbeEnabled`가 "1".."8"만 허용해 기본 로스터 응답(`request-served-with-account-owned-characters`)이 나갔고, 런 031852Z(extracmd16 8E7CE34D…)는 `guest-prepare-fresh-run.ps1`:171 의 `-NinmeiProbe` 화이트리스트('1'..'8')가 env 를 지워 같은 결과. 둘 다 정상 종료·정리. 교훈: 새 프로브 모드는 codec 3곳(Enabled/CardIds/CardList)+guest-prepare 화이트리스트를 함께 고칠 것. close-run.ps1 은 이제 스크래치 last-zip.txt 의 zip sha 로 stage 를 정리한다(extracmd15 런의 stage 는 clean-stage-final2 로 별도 삭제).
- 클라이언트 요청 송신표 `FUN_004B78xx`: (esi=요청, ebx=기대 응답) 쌍 — 0x1200→0x1201, 0x0F06→0x0F07, 0x0F08→0x0F09, 0x0322→0x0323, 0x034E→0x034F, 0x032A→0x032B, 0x031E→0x031F, 0x0320→0x0321, 0x0205→0x0206, 0x7000→0x7001, 0x2000→0x2001, 0x2003→0x2004, 0x2005→0x2006, 0x2009→0x200A, 0x1000→0x1001, 0x1001→0x1003, 0x1004→0x1005, 0x032C→0x032D … (공통 꼬리 0x4B78EF: `[0x7C25F4]` 가 0이면 `[ebp+0xC]=esi` 후 성공). 조건 16 "미구현 명령" 목록 산출에 쓸 수 있는 요청 타입 전수표의 출처.
- **모드 9 런 20260903T032306Z(extracmd16 8E7CE34D…, count=1·cardId=1·비-id 바이트 0x01)**: 클라이언트 수락(`Begin OK → NotifySimpleInformationCard OK`), 그래도 実行不可「選択可能な項目が存在しません」(vnc-s5-ninmei.png). → 0x1208 레코드의 비-id 필드를 0/1로 채우는 것만으로는 任命 후보가 생기지 않는다. 후보 판정은 대상 선택 다이얼로그 위젯의 vt+0x18("선택 가능 항목 존재?")로 추정(상태 10/11 진입 `FUN_0057CA00`이 [this+0x15A8+4..] 13개 파라미터 위젯의 vt+0x18을 순회, 하나라도 0이면 al=0 반환). 다음 정적 대상 = TARGET_SELECT_S_CARD 위젯 클래스의 vt+0x18 과 그 레코드 필터.
- 정리: 런 자연 종료·clean-stop·cleanup·census 0·stage 삭제(모두 close-run.ps1). docs/reverse-engineering/client-request-response-type-table.json 신설(클라이언트 요청 43종 ↔ 기대 응답; 조건 7/16 인벤토리 입력).
- **클라이언트 메시지 타입→이름 전수표**(docs/reverse-engineering/client-message-type-names.json, 162종): 세션 디스패처 `FUN_004BA2B0`의 점프표 12개(0x4BDE7C base 0x0201 … 0x4BE324 base 0x2001, 바이트 인덱스표 포함)에서 각 케이스의 "<Name> OK" 로그 문자열을 회수. **정정**: 0x1205 Grid·0x1206 **Strategy**·0x1207 **Unit**·0x120E **OrderSuggestCharacter**·0x120F **CharacterEntry**·0x120A RankingCharacter·0x120B CompletenessSupplyOutfit·0x120C CardAvailableOutfitSeat·0x120D CardAvailableBaseSeat. 카드 관련 프로토콜 이름: RequestStaticInformationCard(0x0304→0x0305)/CardCommand(0x0306→0x0307), RequestCardCharacter(0x034E→0x034F ResponseCardCharacter), CommandCardAppointment(任命 실행)/CommandCardDismisal/CommandCardResignation, NotifyCardLoss. constmsg **그룹 3 = 261행 職務(카드) 이름**(4 大本営参謀, 28 宇宙艦隊司令長官, 39 艦隊司令官…), 그룹 6 = 같은 261행 설명. 권한의 정적 카드 표(`EncodeStaticCards`)는 카드 0..39 를 전부 0 필드로(39만 byte5=11+명령) 서빙 → 任命 후보 필터가 정적 카드 필드를 보면 카드 1은 빈 카드. 프로브 8c(count=1, cardId=39) 런 진행.
- **프로브 8c 런 20260903T033556Z(extracmd16, 0x1208 count=1·cardId=39=플레이어 카드)**: 수락(Begin OK → Card OK), 그래도 実行不可「選択可能な項目が存在しません」. → 정적 카드 표에 명령이 있는 카드(39)도 후보가 아니다. 해석: 任命(TARGET_SELECT_S_CARD, kind 5) 후보는 0x1208 레코드 내용이 아니라 **정적 카드 표(0x0305, world+0x3E0C8C 원본 복사; `FUN_004C4A10`이 0x12C개×0xC4 캐시로 전개)의 필드**(예: 임명권자 카드/계급 조건)로 걸러지는 것으로 추정 — 권한의 `EncodeStaticCards`가 모든 필드를 0으로 보내므로 어떤 카드도 임명 가능으로 판정되지 않는다. 다음 정적 대상 = 상태 10 진입 `FUN_0057CA00`의 실패 경로가 호출하는 `FUN_0056A950`(model, 6, &param, 0)과 kind 5 위젯의 아이템 소스(0x0305 캐시 필드 판정).
- **권한 요청 커버리지 영수증** docs/reverse-engineering/authority-request-coverage.json: 클라이언트 요청 43종 중 권한 소스에 타입 상수가 등장하는 것 24종. **미등장 19종(조건 7/16 갭 목록)**: 0x0316→0x0317 ResponseInformationGrid, 0x0320→0x0321 ResponseInformationInstitution, 0x0322→0x0323 ResponseInformationCharacter, 0x0324→0x0325 ResponseInformationUnit, 0x0326→0x0327 ResponseInformationWarehouse, 0x0328→0x0329 ResponseInformationPackage, 0x032A→0x032B ResponseInformationOutfit, 0x032C→0x032D ResponseGridInformationOutfit, 0x032E→0x032F ResponseInformationOutfitParty, 0x0330→0x0331 ResponseOutfitInformationUnit, 0x0336→0x0337 ResponseTacticsCharacter, 0x033A→0x033B, 0x033E→0x033F ResponseTacticsInformationCorps, 0x0340→0x0341 ResponseTacticsInformationFillShield, 0x0346→0x0347 InformationObstacle, 0x0348→0x0349 ResponsePositionUnit, 0x034E→0x034F ResponseCardCharacter, 0x0408→0x0430, 0x0B00→0x0B0B NotifyMovedBase. (정적 grep 기준; 라이브 증명 아님. 0x0322/0x0324 는 ResponseInformationCharacter/Unit OK 로그가 찍히므로 다른 경로로 서빙됨 → 상수 표기 차이 가능성, 재확인 필요.)
- 업데이트 strategy .mdx 4종 sha256(앞 16자): galaxy cfde6e8d880eaf4a · grid e4e739a19703d0ff · grids ef52025453ece042 · g_board 0d68a237eea0af13. 원본 설치 트리 사본이 E:\logh7-greenfield 아래(out/ 제외)에 없어 CD 판과의 비교는 미완(설치 트리 위치 확인 필요).
- 정적 분석 도구·주소 요약을 메모리(logh7-client-static-analysis-toolkit)에 저장. 남은 任命 정적 대상: 후보 판정이 정적 카드 표(0x0305) 필드에 의존한다는 가설의 코드 확인(kind 5 위젯의 아이템 소스와 `FUN_0057CA00`/`FUN_0056A950` 경로) — 또는 권한이 `EncodeStaticCards`에 원본 의미의 필드를 채우는 실험(byte5=11 외 필드 후보: 임명권자/계급 조건).
- **任命 후보 필터 확정(정적)**: 명령 패널 상태 12(TARGET_SELECT_S_CARD; 디스크립터 생성 0x5808A3/0x580F83 `push 0xc`) 진입 `FUN_0057CBF0`: 모델 kind 5(0x1208 Card 목록 위젯 +0x1E0)가 idle 이면 `FUN_0056A950(model,5,…)`로 목록 요청(0x1200 selector 0x12), 로드 완료(vt+0x24)면 0x57CC85: **레코드마다 `FUN_004C9140(카드표 0x7CD048, X=[0xC9EAC0], cardId)` = (정적카드[cardId].u16@+6 == X) 일 때만** 후보 추가(`FUN_00577050(this+0x244, {이름=constmsg(3,cardId), rec+8 byte, cardId, …})`). 정적 카드 캐시 = `FUN_004C8700` → world+0x3416D8(+0 로드 플래그, 레코드 = +0xA + cardId×70; 파서 0x40EE80 배치: id u16@0, 4바이트@2..5, **u16@6**, u16@8, …). X=[0xC9EAC0]는 0x57D3F0에서 (X-0x18)≤0xBE 범위로 5분류되는 값 = 카드 id 범위(24..214) → 플레이어의 현재 카드 id(39 艦隊司令官)로 추정. 즉 원본 의미: **정적 카드의 @6 = 임명권자(상급) 카드 id**, 任命 후보 = 내 카드가 임명권자인 카드들(40 艦隊副司令官·41 艦隊参謀長·42 艦隊参謀·43 艦隊司令官副官 …). 권한은 지금까지 @6=0 으로 보내 후보 0.
- 실행 경로(0x574891): 선택 레코드의 u16@0(cardId)와 TARGET_SELECT_S_CHARACTER 위젯 선택(레코드 stride 0x120=288B → 0x1207/0x120E 계열)을 묶어 `FUN_004B53A0`으로 CommandCardAppointment 송신.
- **프로브 10 준비(extracmd17 40C93F9F…)**: `LOGH7_STATIC_CARD_APPOINTER="40:39,41:0,42:1,43:11,44:19,45:20,46:103,47:255,48:5"`(정적 카드 40..48의 @6 스윕)+`LOGH7_NINMEI_PROBE=10`(0x1208 을 카드당 count=1 프레임으로 9장, 저장 루틴 append) + cmd-probe.ps1 'rpmcard' 단계(읽기 전용 RPM: [0xC9EAC0], world+0x3416D8 캐시 헤더, 0x1208 count/레코드). 나타나는 카드가 X 를 알려준다. host-run/guest-prepare 에 -StaticCardAppointer 통과 추가(정규식 검증).
- **프로브 10 런 20260903T035028Z(extracmd17) — 결정적 발견 2건**: (1) 읽기 전용 RPM `[0x00C9EAC0] = 0x27 = 39` = 플레이어 카드 id(艦隊司令官) → 任命 후보 조건은 "정적 카드[cardId].u16@+6 == 내 카드 id" 로 확정. (2) 0x1208 원본 복사 버퍼(world+0x4A07D0) 선두가 `00 01 00 00 …`, count 셀 = 300(cap) → **클라이언트의 0x120x 알림 u16/u32 필드는 빅엔디언**(권한의 정적 표 `WireWriter.WriteUInt16`은 이미 BE로 쓰고 있었음). 내 0x1208 인코더는 LE(`01 00`)였으므로 count 1→256, 2→512, 3→768 로 읽혀 300 초과분은 "over"→unsupported. **즉 "count==1 게이트"는 엔디언 착시**였고 레코드 cardId(LE `28 00`)도 0x2800 으로 읽혀 후보 판정에 실패. 수정: OriginalSimpleRankCodec 의 0x1208 count·cardId를 BigEndian 으로(모드 8/9/10 공통). 정적 카드 @6=39 는 WireWriter 가 BE 라 이미 올바름.
- 부수: 이번 런의 디버그 로그가 4MiB 캡에 도달(1,494,316줄)해 Card OK 줄이 잘림 → 긴 런에서는 클릭 전 `-Delete` 수집으로 리셋할 것. cmd-probe.ps1 에 'rpmcard' 단계 추가 중 도구 계층이 heredoc 의 `\f`/`\r` 를 제어문자로 바꾸는 문제 확인 → 파이썬에서 chr(92)로 조립해 복구(메모리 기록 예정).
- **프로브 10b 런 20260903T040557Z(extracmd18, BE count/id)**: 0x1208 프레임 9개 모두 `NotifySimpleInformationCard OK`(수락), RPM: count 셀 = 9, 파싱된 12B 셀 = `00 00 | 7B 00 | 00 00 28 00 | 00 00 00 00` → 클라이언트의 **0x1208 와이어 레코드는 count u16 BE 뒤에 패딩 없이 7바이트 팩드 {u16 cardId, u32, u8}** 이며 파서(0x55F670)가 12B 셀 {u16@0, pad, u32@4, u8@8}로 전개한다. 내 인코더는 id를 오프셋 4에 써서 cardId=0·u32=0x00280000 으로 파싱됨 → 후보 0(実行不可). 수정: id를 오프셋 2에(모드 10) / 7바이트 스트라이드(모드 8/9) — extracmd19 빌드 예정. 정적 카드 40의 @6은 WireWriter(BE)로 `00 27`=39 정상.
- 바인더 docs/reverse-engineering/bind-condition-11-ninmei-appointer-sweep-verification.py → condition-11-ninmei-appointer-sweep-verification.json(OBSERVED_PROBE_SERIES 5/5: count 게이트=엔디언, [0xC9EAC0]=39 라이브 2회, LE count→300 오버플로, BE count→9 파싱, 6런 모두 정상 종료). 인자로 10c 런 id를 주면 후보 렌더 런을 추가한다. extracmd19(159B5B27…) = 패킹 7B 레코드 인코더.
- **프로브 10c 런 20260903T041442Z(extracmd19 159B5B27…, 패킹 7B BE 레코드) — 任命 후보 목록 최초 렌더**: 0x1208 9프레임 수락 후 任命 다이얼로그가 열리고 좌측 목록에 **艦隊副司令官(카드 40, @6=39)** 1건만 표시(vnc-s5-ninmei.png) — 스윕한 41..48(@6 = 0/1/11/19/20/103/255/5)은 제외 → 술어 "정적카드.@6 == 내 카드 id(39)" 라이브 확정. 다이얼로그: 좌 목록 헤더 (103,2) 誤인덱스 문자열·情報 패널 3칸·「あなたは」칸·프롬프트「左欄より任命する職務を選択してください」·決定/(103,1). 다음: 행 선택 → 決定 → 2단계(캐릭터 선택; 상태 3 = 모델 kind 2 = 0x1202 NotifySimpleInformationCharacter) 요청 관측.
- 상태→목록 kind 표(정적, 각 진입 함수의 `FUN_0056A950(model, KIND)`): 3→0x1202 Character, 7→0x1203 Outfit, 9→0x1207 Unit, 10→0x1209 Rank, 12→0x1208 Card, 13→0x120D CardAvailableBaseSeat, 14→0x1205 Grid, 15→0x1206 Strategy, 17→0x1204 Base, 18→(+0x3F4 13번째 위젯).
- 10c 계속: 목록 행(290,214) 선택 → 決定(726,608) → 클라이언트가 **0x1200 selector 0x0004** 송신(임명 대상 캐릭터 목록 요청; 상태 3 = 0x1202 NotifySimpleInformationCharacter). 권한은 기본 로스터(`request-served-with-account-owned-characters`, 0x120F CharacterEntry)로 응답 → 2단계 実行不可「選択可能な項目が存在しません」(vnc-s7-kettei.png). 다음: selector 0x0004 에 0x1202(BE, 레코드 288B 셀; 파서 0x55BA80 의 읽기 순서로 와이어 배치 확정) 로 부하 캐릭터 후보를 서빙 → 캐릭터 선택 → CommandCardAppointment 요청 타입 관측 → DB 반영. 런 정상 종료.
- **0x1202 NotifySimpleInformationCharacter 와이어 배치(파서 0x55BA80 읽기 순서, BE)**: u32 characterId · u8 cardCount(≤13)+u16[cardCount] · u16 · u16 · u8 n2(≤16)+u16[n2] · u8 flagA(≤1)[=1이면 u32,u8,u8,u8 nB(≤1)[=1이면 u32,u16,u16,u8 n+u16[n]…]] · u8 n3(≤4)[×{u16,u32,u16,u16,u8 m(≤13)+u16[m]}] · u32 · u32 → 최소 레코드 20B. 셀 stride 0x120(288), cap 200(본문 57,604 고정). 권한: `LOGH7_NINMEI_CHARS="1,2"` → selector 0x0004 에 Begin + 0x1202(캐릭터당 1프레임) + End (`EncodeNinmeiCharacterTransaction`, 세션 분기, host-run/guest-prepare `-NinmeiChars`). extracmd20 ADFB44B1…. 런 10d: 카드 40..43 모두 @6=39(4개 후보) + 캐릭터 1,2.
- **런 10d 20260903T042254Z(extracmd20)**: 任命 후보 4건(카드 40..43 @6=39) 렌더 → 행 선택 → 決定 → selector 0x0004 에 0x1202 2프레임 서빙 → `NotifySimpleInformationCharacter OK` 2회 **수락됐지만** 2단계 実行不可, RPM count 셀(world+0x4C83A0)=0. 원인: 0x1202 파서(0x55BA80)는 **count 를 u8(vt+0x24)** 로 읽는다(0x1208 은 u16). 내 프레임 `00 01` → count 0 → 저장 0. 또 0x1202 레코드는 **이름 내장**: {u32 id; u8 nameLen(≤13); u16 name[nameLen](UTF-16); u16 @; u16; u8 len2(≤16)+u16[…]; u8 flagA; …} — 상태 3 로드 경로(0x57B9C4)는 술어 없이 모든 셀을 후보로 추가하며 셀 +0(id)·+6(이름 문자열)·+0x20(u16)을 표시에 쓴다. 수정 예정: u8 count, 레코드 오프셋 1부터, DB 캐릭터 이름 삽입(extracmd21).
- extracmd21 BBE4F025…: 0x1202 = u8 count + {u32 id BE, u8 nameLen(len+1), UTF-16 BE name(≤12자), u16, u16, u8, u8, u8, u32, u32}; 세션은 `LOGH7_NINMEI_CHARS` id 중 계정 소유 캐릭터를 DB(`ListCharactersAsync`, LastName)로 채워 보냄. 런 10e 진행.
- **클라이언트 요청 종류 전수표** docs/reverse-engineering/client-request-kind-table.json: 요청 송신기 `FUN_004B78A0` 의 kind 1..0x80 → 점프표 0x4B864C(124종에 요청 타입 존재). **任命 실행 = kind 0x6D → 요청 0x0707 CommandCardAppointment**(송신 헬퍼 `FUN_004B53A0`; 0x0704..0x0709 = 명령군, 昇進은 0x0704). 클라이언트 디스패처는 0x0707 수신 시 160B 를 world+0x43C8F0 로 복사 후 `FUN_004BFCD0` 호출("CommandCardAppointment OK") → 권한은 0x0707 요청에 대해 (0x0704 昇進과 같은 형식의) 응답/에코를 내야 한다.
- **런 10e 20260903T043049Z(extracmd21)**: 任命 2단계 렌더 성공 — 캐릭터 목록에 「アッテンボロー」(id 2, DB LastName) 표시, 정렬 드롭다운「階級順」, 프롬프트「左欄より任命する人物を選択してください」(vnc-s7-kettei.png). 캐릭터 1은 계정 소유가 아니라 필터됨(1건). 인물 행 선택 → 決定 → 클라이언트가 **0x0322 RequestInformationCharacter**(선택 인물 상세; 情報 패널용) 송신 → 권한 Invalid → 연결 종료(와이어 마지막 행 type 공백). 즉 任命 실행(0x0707) 직전에 0x0322→0x0323 ResponseInformationCharacter 응답이 필요(미처리 19종 중 하나). 다음: 0x0322 요청 본문(캐릭터 id 위치) 디코드 + 기존 0x0323 인코더(로그인 시 자기 캐릭터용 푸시)를 요청 id 로 재사용 → 0x0707 관측.
- extracmd22 CCC20DE9…: 세션에 (a) **0x0322 RequestInformationCharacter 핸들러** — 요청 u32 BE id 가 월드 캐릭터(또는 0)면 부트스트랩과 같은 `OriginalWorldEntryCodec.EncodeCharacter` 0x0323 프레임으로 응답(그 외 id 는 Invalid; 영수증에 payload hex), (b) **0x0707 CommandCardAppointment 프로브 에코** — `LOGH7_NINMEI_CHARS` 설정 시에만, 요청 본문 hex 를 영수증/ResponseMetadata 에 기록하고 160B 로 패딩한 에코를 0x0707 응답으로 반환(상태 변경 없음; 본문 레이아웃 확정 후 실제 임명 처리 구현 예정).
- **런 10f 20260903T044…Z(extracmd22)**: 0x0322 요청 본문 = `0322 00000002` (앞 2바이트 = 애플리케이션 타입, 이어 u32 BE 캐릭터 id 2). 내 핸들러가 오프셋 0에서 id 를 읽어 `original.information-character.unknown-id` → 연결 종료. 수정: `Payload.AsSpan(2)` (extracmd23 9F246B3B…). 0x0707 에코 핸들러는 그대로.
- **런 10g 20260903T044421Z(extracmd23 9F246B3B…) — 任命 전 과정 최초 통과**: 0x0322 → 0x0323 서빙(2회, requested=2) → 확인 다이얼로그「をに任命します。コマンドポイント160MCP消費。0G時間待機の後、0G時間の所要時間を要します。よろしいですか？」(vnc-s9-kettei2.png; 인물/職務 자리 문자열은 비어 있음 — 0x0323 의 이름 필드/카드 표시 확인 필요) → 決定(565,516) → **0x0707 CommandCardAppointment 요청 본문 캡처**: `0707 | 00000000(time) | 00000002(characterId) | 00000000(pcp) | 00000000(mcp) | 00000002(targetCharacterId) | 0028(cardId 40) | 0000 | 00000000 ×3 | 0000` (38B). 권한은 프로브 에코(160B)로 응답 → 클라이언트 핸들러 `FUN_004BFCD0`는 본문을 읽지 않고 대기 명령 셀 초기화 후 갱신. 런 정상 종료.
- 구현 extracmd24 2EFA0DD5…(publish 에 migrations/0012 포함): 마이그레이션 0012_original_card_appointment.sql(original_card_appointment 이력 + original_character_card 현재 職務), `IAccountStore.AppointCardAsync`/`PostgresAccountStore`(fingerprint 재생 방지, authority_version 증가, domain_event CharacterCardAppointed, `AuthorityStateHash.CharacterCardAppointed`), 세션 0x0707 핸들러(appointer==월드 캐릭터 검증 → 저장 → 명령 에코 응답). 정적 카드 @6 임명권자·0x1208 목록·0x1202 후보는 아직 프로브 env(LOGH7_STATIC_CARD_APPOINTER/NINMEI_PROBE/NINMEI_CHARS)로 서빙 — 다음 단계에서 DB 기반(original_character_card)으로 전환.
- **런 10h 20260903T045258Z(extracmd24)**: 전 과정 재현 후 0x0707 이 `original.card-appointment.identity` 로 거부·연결 종료. 원인: `_worldCharacterId` 는 `RestorePersistedCharacterAsync` 가 채우는데(0x0322 핸들러·昇進 경로는 먼저 호출) 0x0707 핸들러가 호출하지 않아 0 과 비교됨. 수정(extracmd25 5617F1ED…): 핸들러 선두에서 복원 호출 + 거부 사유에 값 포함. DB 조회(guest-db-inspect `cardAppointment`/`characterCard` 추가)는 비어 있음(acct version 24, 변화 없음) — 거부가 저장 전이었음을 확인.
- **런 10i 20260903T045939Z(extracmd25)**: 거부 사유 `identity;appointer=2;world=2;target=2;card=0` → 0x0707 본문의 카드 id 는 **u32 @+22**(`00000028`), u16 판독이 0. 레이아웃 확정: `{u16 type, u32 time, u32 characterId, u32 pcp, u32 mcp, u32 targetCharacterId, u32 cardId, u32 ×3, u16}` (38B). 수정 extracmd26 A36B162A…. 런 정상 종료.
- **任命 수직 경로 완주 — 런 10j 20260903T050523Z(extracmd26 A36B162A…)**: 職務権限カード → 任命 → 艦隊副司令官(카드 40) → 決定 → アッテンボロー(캐릭터 2) → 決定 → 0x0322/0x0323 → 확인 決定 → **0x0707 Success**(`card-appointment-accepted;card=40;target=2;appointer=2;authorityVersion=25`) → PostgreSQL: original_card_appointment{2→40→2, v25}, original_character_card{2: card 40 by 2, v25}, domain_event 25 CharacterCardAppointed, account.authority_version 24→25 → 클라이언트 다이얼로그 닫힘·전략 화면 복귀(vnc-s11-after.png) → **권한 재시작(같은 DB, pid 6600→1784) → 재로그인 1회 제출 → DB 동일(A==R0==R1)** → clean-stop(relaunch-prep)·cleanup·census 0·stage 삭제. 바인더 docs/reverse-engineering/bind-condition-11-appointment-verification.py → condition-11-appointment-verification.json.
- 관측 메모: (a) 확인 다이얼로그의 인물/職務 자리 문자열이 비어 있음(「をに任命します」) — 표시 텍스트 갭(조건 6/7), 0x0323 이름 필드/카드명 바인딩 정적 확인 필요; (b) 재시작 레그의 권한은 `LOGH7_EXTRA_CARD_COMMANDS` 등 프로브 env 를 물려받지 않아 재진입 카드에 任命 이 없음(guest-restart-authority env 통과 미구현 — 도구 갭, 재진입 렌더 검증에는 필요); (c) 임명 결과는 클라이언트 화면에 별도 표시가 없음(원본도 NotifyInformationCharacter 등 후속 알림으로 반영했을 것 — 미서빙); (d) 후보/정적 카드 임명권자는 아직 env 프로브 → DB(original_character_card) 기반 서빙으로 전환 필요.
- **env 없이 재현 가능하게(extracmd27 62B22EEF…)**: (1) `OriginalWorldBootstrapCodec.DefaultCardAppointer` = {40,41,42,43 → 39}(NEW_DESIGN: 艦隊司令官의 부하 職務; env 는 덮어쓰기), (2) 카드 명령 기본값에 任命(5) 포함(`ExtraCardCommandIds` 기본 {5}), (3) selector 0x0012 기본 = 계층의 부하 職務를 0x1208(카드당 1프레임, holder = original_character_card 의 현재 보유자, 0=공석)로 서빙(`EncodeNinmeiPostTransaction`), (4) selector 0x0004 기본 = 계정 소유 캐릭터 전원(0x1202), (5) `IAccountStore.ListCharacterCardsAsync`. 프로브 env 는 여전히 우선. 검증 런(프로브 env 없음) 진행.

### 독립 리뷰어용 任命 재현 절차(조건 17 입력)
1. 권한 zip extracmd27(62B22EEF…) 이상, 소스 런 20260902T083838Z, 클라이언트 = 해시 고정 원본 또는 item115 진단 사본(로그만): `host-run-fresh-run.ps1 -RunId <id> -SourceRunId 20260902T083838Z-natural-l1-relogin-v1 -ClientMode Copy … -HostServerZipPath <zip> -Expected*Sha256 …` (프로브 env 불필요).
2. VNC 127.0.0.1::6001: 로그인 1회 제출(guest-submit-credential) → (122,191) 로스터 → (650,300) 전략 → (727,577) 職務権限カード 탭 → (820,512) 카드 → (923,283) 任命 → (290,214) 艦隊副司令官 → (726,608) 決定 → (290,214) 인물 → (726,608) 決定 → (565,516) 확인 決定.
3. 기대 와이어: 0x1200 sel 0x0012 `ninmei-post-list-served`, 0x1200 sel 0x0004 `ninmei-character-list-served`, 0x0322 ×2 `information-character-served`, 0x0707 `card-appointment-accepted;card=40;target=2`. DB(guest-db-inspect): original_card_appointment 1행, original_character_card{2:40}, domain_event CharacterCardAppointed, account.authority_version +1.
4. 재시작 레그: guest-restart-authority(-OutputPrepFileName relaunch-prep.json -WireFileName server-wire-2.jsonl) → 재로그인 → guest-db-inspect 동일 → guest-clean-stop -PrepFileName relaunch-prep.json → cleanup → census. 바인더: bind-condition-11-appointment-verification.py <runId> <tag> <reloginTag>.
- **env 없는 재현 런 20260903T051509Z(extracmd27)**: 프로브 env 전무 상태에서 任命 4개 職務(艦隊副司令官/艦隊参謀長/艦隊参謀/艦隊司令官副官) 렌더(vnc-s5-ninmei.png) → 전 과정 → 0x0707 accepted → DB v25 → 권한 재시작 → 재로그인 → DB 동일 → 재진입 任命 목록에 보유자 `40:2` 서빙(vnc-r-5-ninmei.png; 화면상 보유자 표시는 없음) → clean-stop(relaunch-prep)·census 0. 바인더 재실행 → condition-11-appointment-verification.json(env-free, 11/11), 이전 프로브 런 영수증은 -probe-env.json 으로 보존.
- **확인 다이얼로그 템플릿의 출처**: exe·constmsg 에 없고 `data\msgdat\messages_com_0.dat`(HFWR 형식, 호스트 사본 evidence/installshield-extract/…/data/msgdat) 오프셋 1356: `$com_xfullname$を$com_xexecutive$に任命します。\nコマンドポイント160MCP消費。\n0G時間待機の後、\n0G時間の所要時間を要します。\nよろしいですか？` — 변수 `$com_xfullname$`(임명 대상 전체 이름)·`$com_xexecutive$`(職務명)가 비어 렌더됨. 같은 파일에 임명 통지 템플릿 `あなたは$com_mexecutive$に任命されました。今後も一層の精進に励んでください。`(임명 후 대상에게 보내는 메시지 = 원본은 NotifyCommandMail/메일로 통지했을 가능성)와 叙勲 템플릿 `$com_mtitle$を授与されました`. 프로브 extracmd28(2438DA5C…): 0x1202 두 번째 문자열(n2)=FirstName・LastName, u16×2=rank 로 채워 `$com_xfullname$` 원천 확인 중.
- 공식 업데이트 vs CD(evidence/installshield-extract) strategy 모델: galaxy.mdx 동일(cfde6e8d…), **grid.mdx 변경**(44,140B d3d51f08… → 11,934B e4e739a1…), grids.mdx·g_board.mdx 는 CD 에 없는 **신규**. 게스트 클라이언트는 CD 데이터 기준(업데이트 미적용) — 업데이트 데이터 파일 적용 여부는 사용자 결정 사항(exe 는 업데이트에 없음 → 해시 고정 exe 유지 가능).
- 프로브 런 20260903T052437Z(extracmd28: 0x1202 n2=전체이름, u16×2=rank): 확인 다이얼로그 자리 문자열 여전히 비어 있음(vnc-s9-kettei2.png) → 0x1202 두 번째 문자열은 `$com_xfullname$` 의 원천이 아님. 정적: 템플릿 변수 표 0x786B78 {이름 ptr, resolver}: `com_xexecutive`→0x521820(slot 3), `com_xfullname`→0x5218E0(slot 6) — resolver = `FUN_005229D0([0x786B70], slot)` = 컨텍스트 객체 +0x2D1C + slot×32 의 32바이트 문자열. 이 슬롯을 쓰는 코드(패널 선택 시점) 추적 중. 0x0707 은 이번 런에서도 accepted(재현 3/3). 런 정상 종료.
- 확인 다이얼로그 자리 문자열 정적 추적 결론(미해결, 표시 텍스트 갭): 변수 슬롯(컨텍스트 +0x2D1C+32n, 15개)은 생성 시 0으로 초기화(0x521EFD)되고 15개 resolver(0x52176A…0x521AEA, slot 0..14)는 모두 **읽기**뿐 — 슬롯을 쓰는 코드가 disp 0x2D1C 검색으로는 없음 → 슬롯 값은 다른 경로(포인터 산술 setter 또는 서버가 보내는 변수 값)로 채워질 가능성. 같은 파일의 `$com_m…$` 템플릿(임명 통지)은 서버 발 통지가 변수값을 실어 보내는 구조로 보임. 우선순위: 낮음(기능 동작·영속·재시작 검증 완료, 텍스트 두 자리만 공백). 다음 후보 작업: (a) 미처리 요청 19종의 최소 응답 구현(연결 종료 방지, 조건 7/16), (b) 叙勲(0x0F08 카테고리 목록) 디코드, (c) 임명 후 통지(0x0F15 NotifyCommandMail 등) 원본 의미 복원.

## 2026-09-03 05:45Z — 사용자 결정 반영(옛 DB 사본 정리, 업데이트 데이터 적용) + 전략 커맨드 원장
- **옛 런 DB 사본 정리(사용자 결정)**: 게스트 레인 루트 352 런 중 소스 체인(20260902T083838Z)·오늘 런 제외 351개 목록(old-run-delete-list.txt sha 38CB12D5…) → `guest-cleanup-old-runs.ps1`(목록 sha 검증, 소스 체인 보호, postgres 프로세스 검사, **postgres-data·server 하위 폴더만 삭제**, 영수증·로그·PNG 보존) 드라이런 후 삭제: 238 런, 12,311MB 제거, 게스트 C: 여유 1,031 → 13,393MB(Storage Sense 트리거 소멸). 영수증 runs/20260903T054500Z-maint-update-data/oldruns-delete.json.
- **업데이트 데이터 적용(사용자 결정)**: G7UPD040514 data 250파일 zip(work/20260903-official-update-strategy-models/g7upd040514-data.zip sha B2A0C5E7…, 매니페스트 json) → 게스트 `_debuglog`에 스테이징(host-copy-to-guest.ps1 신설) → `guest-apply-update-data.ps1`(zip sha 검증, exe/dll 거부, 덮어쓰는 원본은 `C:\LOGH7_ORACLE\data-backup-preupdate-<ts>` 에 백업, 해시 검증, 영수증). 드라이런 1·2는 경로 버그(8.3 TEMP 경로 길이) — GetRelativePath 로 수정 후 재실행.
- **전략 커맨드 원장** docs/reverse-engineering/strategy-command-ledger.json: constmsg 그룹 18 = 97개 커맨드(id = 클라이언트 명령 id). 구현 3/97(昇進 0·任命 5·ワープ航行 43), 카드 패널 정의 17개(0,1,4,5,12,14,15,16,20,21,24,26,27,28,30,31,33), 나머지는 유닛/기지/행성 패널 소속. 사용자 지시: 전술보다 **전략 커맨드 전수 구현 우선**.
- 전술 모드 계획(사용자 질문 답변): 클라이언트 전술 프로토콜(요청 0x0336/0x033E/0x0340/0x0344/0x0346/0x0348/0x034A, 응답 0x0337/0x033F/0x0341/0x0345/0x0347/0x0349/0x034B, 명령 0x0400~0x0422, 통지 0x0424~0x0442, 개시 0x0F1F NotifyTactics, 종료 0x035A NotifyEnding)을 권한이 서빙하면 원본 전술 씬 진입 가능. 순서: 정적(0x0F1F→씬 전환, 7개 응답 파서 배치) → 권한 교전 상태(NEW_DESIGN) → 라이브 진입 렌더 → 이동/선회/공격 수직 경로 → 결과/종료/복귀 → 두 클라이언트.
- **업데이트 데이터 적용 결과**: 봉인 설치 트리 `C:\LOGH7_ORACLE\data` 는 ACL 로 쓰기 거부(Access denied) → 설치본을 건드리지 않는 **오버레이** 방식으로 전환: `guest-apply-update-data.ps1 -OverlayRoot C:\LOGH7_UPD -Apply` = robocopy 로 data 2,187파일 복제 후 업데이트 적용(new 108, identical 94, replaced 48; 교체 원본은 `C:\LOGH7_UPD\data-backup-preupdate-20260903T054347Z` 보존; 영수증 runs/20260903T054500Z-maint-update-data/overlay-apply.json). Copy 모드 런은 `host-run … -DataRoot C:\LOGH7_UPD` 로 data 정션을 오버레이로 향하게 한다(guest-prepare `-DataRoot`, prep 의 dataJunctionTarget 에 기록). 교체 48건 중 strategy 는 grid.mdx, images Hi/Lo/Mid 의 galaxy_all/galaxy_alpha/grid01/grid02/neb000/neb001/EH·EM 계열.
- `LOGH7_COMMAND_ECHO=1`(host-run `-CommandEcho 1`): 미구현 명령군(0x0700-0F, 0x0900-0F, 0x0C00-0F, 0x0B00-0F; 0x0704/0x0707 제외)을 영수증에 hex 기록 + 160B 에코 응답(상태 변경 없음) → 한 런에서 여러 전략 커맨드의 요청 본문을 수집(전략 커맨드 원장 채우기용). 클라이언트 요청 송신 지점 표 docs/reverse-engineering/client-request-sender-sites.json(88 지점; 0x0704~0x0709 = kind 106~111).

## 2026-09-03 card-command echo sweep (run 20260903T054448Z-natural-l1-relogin-v1, extracmd29)

Setup: `-ExtraCardCommands '1,4,12,14,15,16,20,21,24,26,27,28,30,31,33' -CommandEcho '1' -DataRoot 'C:\LOGH7_UPD'` (update-data overlay).
The card panel rendered all 18 buttons (3 rows x 6): 昇進(722,283) ワープ航行(822,283) 任命(923,283) / 抜擢(722,310) 叙勲(822,310) 会談(923,310) /
演説(722,337) 国家目標(822,337) 納入率変更(923,337) / 外交(722,364) 統治目標(822,364) 発令(923,364) / 部隊解散(722,391) 講義(822,391) 輸送計画(923,391) /
税率変更(722,418) 施設建設(822,418) 施設再稼動(923,418). Sweep tool: scratchpad `sweep.ps1` (click, capture, pixel-diff vs the card
baseline, wire tail excluding 0x0300 heartbeats); screenshots `vnc-c01*`..`vnc-sw-c15*` in the run dir.

Results (no command body captured yet — every picker came back empty):

| command | observed |
| --- | --- |
| 抜擢 (id 1) | ladder 0x1200 selector 0x0011 (served) -> row + 決定 -> **0x1200 selector 0x0015** (person picker) -> roster fallback rejected: 実行不可 「選択可能な項目が存在しません」 |
| 発令 (id 24) | **0x1200 selector 0x000B** -> same 実行不可 |
| 部隊解散 (id 26) | **0x1200 selector 0x001E** -> same 実行不可 |
| 叙勲 会談 演説 国家目標 納入率変更 外交 統治目標 講義 輸送計画 税率変更 施設建設 施設再稼動 | silent: no request, no dialog (<320 px change = cursor). Client-side preconditions fail while the character is 艦内 (aboard ship) holding card 39; targetKind BASE/UNIT/CASTPLANET commands need a docked/commanding context the authority does not serve yet. |

Static notes from this pass (scratchpad `disasm.py`, `callers.py`):
- 0x1200 request wrapper FUN_004B5810 (kind 70): arg0 -> body+4 = selector (u16, the authority's `simpleInformationSelector`),
  then u16@+0xA, u32@+0xC, u8@+0x10, u32@+0x14, u16@+0x18, u16@+8, u32@+0x20, u16@+0x28. Direct callers push 0x0F (mail/messenger
  0x52D405/0x52D476/0x52D4BE, 0x53A47E) and 0x13 (lobby roster 0x595CD2); every panel list goes through the list-widget
  method 0x56B0F0 (vtable slot 0 of the 13 list-widget vtables 0x674BBC..0x674E2C stride 0x34; slot 3 = reset, sets +0x28=-1)
  reading selector from widget+0x28. Immediate selector writes 1..0xB sit in 0x580131..0x58C1BE (strategy-panel lists);
  the personnel selectors are built in a per-state stack table (0x577B00 = 0x12, 0x57D5C5 = 0x04), so selector->kind must
  be observed live or read from the widget factory (open).
- 0x52A33D..0x52B541 (modes 2..0x29) is a list-dialog LAYOUT descriptor switch, not the notify kind. 0x5A4A34 (0x15/0x16) is
  D3D format code (0x8876086C error constant) — false lead.
- 0x57D3F0: switch over the player card id ([0xC9EAC0]-0x18, 0..0xBE) via byte table 0x57D458 / jump table 0x57D440.
  0x57DA00: switch over 0..0xB0 (command id) returning small codes (16 targets) = the targetKind table.
- The guest debug log hit its 4 MiB cap (1.5M lines) before the sweep, so it holds nothing about the probes.

Next (extracmd30): `LOGH7_LIST_KIND_PROBE="15:1202,0B:1202,1E:1202"` serves the 0x1202 character list for those selectors
(env-free selectors unchanged); the echo probe now covers every unhandled world request type. Run with `-CommandEcho 1
-ListKindProbe 15:1202,0B:1202,1E:1202` and drive 抜擢 -> picker -> 決定 -> confirm to capture 0x0705.

## 2026-09-03 抜擢 (0x0705) captured — run 20260903T061617Z-natural-l1-relogin-v1, extracmd30

`LOGH7_LIST_KIND_PROBE=15:1202,0B:1202,1E:1202` + echo. Panel state read live at `0x00CA3710+0x234` (the singleton IS the
panel object; its first dword is the vtable 0x670690):

| command | list request | panel state | served kind | client result |
| --- | --- | --- | --- | --- |
| 抜擢 person picker | 0x1200 selector 0x0015 | **3** | 0x1202 character list | picker populated (アッテンボロー, 「左欄より昇進させたい人物を選択してください」) -> 0x0322 info x2 -> 決定 -> confirm 「をに抜擢します。コマンドポイント320MCP消費。0G時間待機の後、0G時間の所要時間を要します。」決定(565,516)/取消し(638,516) -> **0x0705 CommandSpeciallyRankUp** sent (client sends even with MCP 0) |
| 発令 | 0x1200 selector 0x000B | **15** (-> 0x1206 NotifySimpleInformationStrategy) | 0x1202 (wrong kind) | 実行不可 選択可能な項目が存在しません |
| 部隊解散 | 0x1200 selector 0x001E | **6** (sub-menu on-enter FUN_0057BFF0: five constmsg entries 0x125..0x129 gated by a bitmask at 0xC9E638; list kind not in the state table, probably 0x1207 Unit) | 0x1202 (wrong kind) | 実行不可 |

0x0705 payload (34 bytes, big-endian): `0705 00000000 00000002 00000000 00000000 00000002 14 00000000 00000000 00 0000`
= `[type][u32 time][u32 actorId][u32 pcp][u32 mcp][u32 targetCharacterId][u8 targetRank = target's current rank from the
ladder][u32 achievement][u32 moveSpot][u8 moveCount + u32[]][u16 tail]` — the 0x0704 layout with the target inserted after mcp.
Authority: `OriginalSpecialRankUpCodec.cs` (decode/encode, 0xA0 response like 0x0704) + handler before the 0x0704 block; persistence
reuses `PromoteCharacterAsync` with `CharacterRankUpWrite(..., EventType: "CharacterSpeciallyPromoted", ActorCharacterId)`
(character row rank-1, character_rank_command replay row, domain_event CharacterSpeciallyPromoted) — NEW_DESIGN: the
original server's MCP cost (320) and wait time are not modelled yet (the served pcp/mcp are 0).
Client 0x12xx names: 1203 Outfit, 1204 Base, 1205 Grid, 1206 Strategy, 1207 Unit, 120A RankingCharacter, 120B CompletenessSupplyOutfit,
120C CardAvailableOutfitSeat, 120D CardAvailableBaseSeat, 120E OrderSuggestCharacter.

## 2026-09-03 抜擢 vertical VERIFIED (run 20260903T062748Z-natural-l1-relogin-v1, extracmd31) + 0x1206/0x1207 wire layouts

- Flow (scratchpad `batteki-verify.ps1 -Tag v1`): 抜擢 -> ladder row -> 決定 -> picker (0x1202 for selector 0x0015) -> row -> 決定 -> confirm 決定
  -> `0x0705 special-rank-up-accepted;target=2;rank=20->19;updated=True;authorityVersion=25`; DB character 2 rank 19, domain_event
  CharacterSpeciallyPromoted {sourceRank 20, promotedRank 19, actorCharacterId 2}. `ninmei-relogin.ps1 -Tag r1`: authority restart on the same
  PostgreSQL -> DB identical (version 25) -> relogin -> HUD 「ダスティ・アッテンボロー一等兵」 (vnc-r1-4-card.png). Binder:
  `docs/reverse-engineering/bind-condition-9-special-promotion-verification.py <run> v1 r1` -> `condition-9-special-promotion-verification.json`.
- Parser table (.rdata 0x6748BC, 20-byte rows {outA,outB,binParser,textParser,common}) identified by the "over" strings at 0x78bxxx:
  0x55ED10 = Input_NotifySimpleInformationStrategy (0x1206): wire `u8 count(<=200)` + records `{u32 BE id, u16 BE, u8 (0..2), u8 (0..2)}` (8 B);
  parsed cell {u32@+4,u16@+8,u8@+10,u8@+11}, world store FUN_004C20D0 cap 100 at world+0x580368.
  0x55F1F0 = Input_NotifySimpleInformationUnit (0x1207): wire `u16 count(<=600)` + records `{u32 BE id, u8 (0..2), u16 BE}` (7 B);
  parsed cell {u32@+4,u8@+8,u16@+10}, world store FUN_004C2250 cap 2000 at world+0x58068C.
  0x55FF30 = Input_NotifySimpleInformationRankingCharacter (0x120A): u8 count(<=100) + character-shaped records.
  Helper 0x610420(dst, 1, 0, 2) = read one byte and range-check 0..2.
- 部隊解散 (state 6): on-enter FUN_0057BFF0 builds five category rows (constmsg 0x125..0x129 -> values 5,4,3,2,0) gated by a bitmask from
  FUN_004FDF20(0xC9E638) (the player's unit types); with no units under command every row is filtered -> 「選択可能な項目が存在しません」.
  The selector 0x001E request is the unit-list prefetch (0x1207). So 部隊解散/納入率変更/輸送計画 need served units under command (world context),
  not just a list kind — the next authority modelling step (ResponseInformationUnit 0x0324 is still unhandled).

## 2026-09-03 personnel sweep (run 20260903T063644Z-natural-l1-relogin-v1, extracmd31, ExtraCardCommands 2,6,7,22,3,25,35)

Card rendered 降等(722,310) 罷免(822,310) 辞任(923,310) / 作戦計画(722,337) 叙爵(822,337) 部隊結成(923,337) / 艦艇建造(722,364).
- **降等 0x0706 captured**: ladder (sel 0x0011) -> row -> 決定 -> person picker sel 0x0015 (0x1202 served, 「左欄より降等させたい人物を選択してください」)
  -> row -> 決定 -> confirm 決定(565,516) -> `0706 00000000 00000002 00000000 00000000 14 00000002 00000000 00000000 00 00000000` (36 B):
  `[type][time][actor][pcp][mcp][u8 targetRank][u32 target][u32 achievement][u32 moveSpot][u8 moveCount+u32[]][u32 tail]` — rank byte BEFORE
  the target id (0x0705 has it after). Authority: `OriginalRankDownCodec.cs` + handler (extracmd32, zip sha 5B758744…): character.rank+1 via
  `PromoteCharacterAsync(EventType "CharacterDemoted")` (store now accepts PromotedRank == ExpectedRank+1 for that event type).
- **罷免**: post list (sel 0x0012 -> 0x1208 posts 40..43, all vacant on a fresh DB copy) -> row -> 決定 -> **0x1200 selector 0x0005** (holder
  picker; roster fallback -> 実行不可). To reach 0x0708: appoint a holder first (任命) and serve 0x1202 for selector 0x0005.
- Host C: hit 0 MB free during the sweep (VIX Add-Type could not write its temp dll). Only this task's regenerable temp was removed:
  `%TEMP%\item116-ghidra-4cce…` (Ghidra headless project, 156 MB). Other large Temp entries (VS installer manifests `txlfskud`,
  `logh7-foundation-refresh-*` from another worker, VS Code) were left alone. The session transcript on C: is 210 MB — avoid screenshot
  reads when the pixel diff (sweep.ps1) already tells the state.

- **辞任 0x0709 captured** (same run): confirm 「を辞任します。コマンドポイント80MCP消費…」 決定 -> `0709 00000000 00000002 00000000 00000000 00000027 00000000 00`
  (27 B) = `[type][time][actor][pcp][mcp][u32 cardId 39][u32 0][u8 0]`. Decoder `OriginalCardResignationCodec.cs`; the handler waits for the
  character's current card to become authority state (world entry still serves the constant AuthorityCardId).
- **作戦計画**: local plan-type list 防衛作戦/占領作戦 + slider (no request) -> 決定 -> 0x1200 selector 0x0021 (roster fallback -> 実行不可);
  panel state 18 read live (outside the 18-entry on-enter table). Target kind still unknown (0x1204 Base / 0x1205 Grid candidates).
- **叙爵**: silent. **部隊結成**: visible rejection 実行不可「選択可能な拠点が存在しません。選択可能な拠点のある星系グリッドにワープしてください。」
  (client-side, no request) — the current grid has no base; another world-context prerequisite.

### 0x1200-family parser table fully named (docs/reverse-engineering/client-simple-information-parser-table.json)
.rdata 0x6748BC, 13 rows x 20 B, row k -> type 0x1208 + (6 - k): 120E OrderSuggestCharacter 0x563C20, 120D CardAvailableBaseSeat 0x563430,
120C CardAvailableOutfitSeat 0x562890, 120B CompletenessSupplyOutfit 0x561C80, 120A RankingCharacter 0x55FF30, 1209 Rank 0x55FAF0,
1208 Card 0x55F670, 1207 Unit 0x55F1F0, 1206 Strategy 0x55ED10, 1205 Grid 0x55E940, 1204 Base 0x55E200, 1203 Outfit 0x55D6C0,
1202 Character 0x55BA80. New wire layouts: **0x1205 Grid** = u8 count(<=200) + u32 gridId[]; **0x1204 Base** = u8 count(<=200) +
{u32 baseId, u16, u16, u8 n(<=13) + u16[n]} (cell stride 0x24). These are the candidate kinds for 作戦計画's selector 0x0021.

## 2026-09-03 personnel run (20260903T065309Z-natural-l1-relogin-v1, extracmd32, ExtraCardCommands 1,2,6, ListKindProbe 15:1202,05:1202)

scratchpad `personnel-verify.ps1` (phases a-d; host-step names must be lowercase `^[a-z0-9-]{1,48}$` — an uppercase tag aborted the first attempt).
- a 任命: card 40 -> holder 2 (`card-appointment-accepted;card=40;target=2;appointer=2;authorityVersion=25`).
- b **罷免 0x0708 captured**: post list -> post 40 -> holder picker (selector 0x0005 served as 0x1202 -> populated) -> holder -> 決定 -> confirm 決定 ->
  `0708 00000000 00000002 00000000 00000000 00000002 00000028 00000000 00` (27 B) = `[type][time][actor][pcp][mcp][u32 target][u32 cardId][u32 0][u8 0]`.
- c 抜擢: accepted 20 -> 19 (authorityVersion 26).
- d 降等 0x0706: the authority CLOSED the connection (wire `connection-closed`, no frame row, server.stderr empty): the demotion reused
  `PromoteCharacterAsync`, but `character_rank_command` is constrained to promotions (`CHECK (promoted_rank = source_rank - 1)`, ranges
  2..20 / 1..19) so the INSERT threw and the session dropped. Fix in progress: migration `0013_character_rank_down.sql`
  (`character_rank_down_command`, demoted_rank = source_rank + 1) + a store branch for the CharacterDemoted event; also route store
  failures of personnel commands to a visible soft reject instead of a disconnect (condition 7).

## 2026-09-03 降等 vertical (run 20260903T070357Z-natural-l1-relogin-v1, extracmd33 zip 321C29D6…, ExtraCardCommands 1,2, ListKindProbe 15:1202)

`personnel-verify.ps1 -Tag x1 -StartPhase c`: 抜擢 `special-rank-up-accepted;target=2;rank=20->19;authorityVersion=25` then 降等
`rank-down-accepted;target=2;rank=19->20;updated=True;authorityVersion=26` (no connection close); DB character 2 rank 20, domain_event
CharacterDemoted {sourceRank 19, promotedRank 20, actorCharacterId 2}. `ninmei-relogin.ps1 -Tag r2`: restart on the same PostgreSQL -> DB
identical (26) -> relogin -> DB identical. Binder `bind-condition-9-demotion-verification.py <run> x1 r2` -> `condition-9-demotion-verification.json`.
Authority changes (extracmd33): migration 0013 `character_rank_down_command`; `PromoteCharacterAsync` branches on EventType
CharacterDemoted (replay + insert into the down table, character.rank+1); `RejectCommandVisibly` turns store failures of 0x0705/0x0706
into the client's 0x0500 NotifyInvalidMessage with a Japanese reason instead of a disconnect.

## 2026-09-03 list-kind probe run (20260903T071237Z-natural-l1-relogin-v1, extracmd34 zip DE0851D0…, ExtraCardCommands 22,24, ListKindProbe 21:1205,0B:1206)

- **発令** (state 15) accepts 0x1206 Strategy records: rows render 「NO DATANO DATA 占領作戦」 (two unresolved name placeholders from the
  record's ids + plan type) with the prompt 「左欄より実行する作戦計画を選択してください」 => 発令 executes an existing 作戦計画. Step 2 after
  choosing a plan: 0x1200 selector 0x000C with panel state 6 (the unit-category sub-menu) => the recipient is a unit under command.
- **作戦計画** (state 18) accepts 0x1205 Grid records: rows 「NO DATA」 (grid 101) / 「アイゼンヘルツ」 (grid 102), prompt
  「左欄より作戦対象となる星系を選択してください」. Step 3 after choosing the system: the client sends **0x0F08** (the mailbox list request family)
  with some category; the authority answered it as the mailbox (mail-list-count=2;box=1) and the client showed 実行不可. This is the same
  0x0F08 category seen once for 叙勲. The mail-list metadata now logs the decoded payload hex (extracmd35) so the category can be read.
- Both remaining steps need world context the authority does not model yet: units under command (0x1207 lists + 0x0324 unit info) and the
  0x0F08 category catalogue. NO DATA in the pickers comes from ids without static names (condition 6 relevance: the authority must serve
  resolvable ids only).

## 2026-09-03 correction (run 20260903T072035Z-natural-l1-relogin-v1, extracmd35): 0x0F08 is the client's periodic mailbox unread poll

Repeating 作戦計画 -> 防衛作戦 -> アイゼンヘルツ -> 決定 with 0x0F08 payload logging: **no 0x0F08 is sent**; the client shows 実行不可
「選択可能な項目が存在しません」 locally (step 3 = choosing the participating units from local world data, which is empty). The 0x0F08 rows
seen earlier (also the one once attributed to 叙勲) are the periodic unread poll (character 2, box 1, unread-only). Ledger corrected.
Convergence: 作戦計画 step 3, 発令 step 2, 部隊解散, 納入率変更, 輸送計画 all need **units under the character's command** in the client's
world model (0x1207 Unit lists + the unit-category bitmask object at 0xC9E638 consulted by state 6) — that is the next authority
modelling target; 部隊結成/演説/施設* need a base in the current grid.

## 2026-09-03 state-6 sub-menu decoded: a POST mask in the static card-command records (extracmd36)

FUN_0057BFF0 (panel state 6, used by 部隊解散 and 発令 step 2) builds five rows constmsg 0x125..0x129 = 艦隊司令官(5) 艦隊副司令官(4)
艦隊参謀長(3) 艦隊参謀(2) 艦隊司令官副官(0) and keeps row n only if bit n of the byte returned by FUN_004FDF20(0xC9E638) is set. That byte
is `staticCache + card*196 + 0x5214 + slot*8 + 4 + 7` = the LAST metadata byte of the 8-byte command entry served in
ResponseStaticInformationCardCommand (0x0307: u16 id, gates FF FF 1F, meta 00 00 **00**). The authority always served 0 => the sub-menu was
always empty (「選択可能な項目が存在しません」). extracmd36 serves 0x3F (env LOGH7_UNIT_CATEGORY_MASK overrides) and adds a 0x1207 Unit list
encoder (records {u32 unitId, u8 kind, u16}) served for selectors mapped by LOGH7_LIST_KIND_PROBE (1E:1207, 0C:1207) with the character's
grid unit. Run 20260903T072xxxZ probes 部隊解散 and 発令 with ExtraCardCommands 26,24.

### Run 20260903T072741Z (extracmd36): the post mask is served but the sub-menu is still empty — slot index is -1
Read-only RPM with the 部隊解散 実行不可 dialog open: static cache block for card 39 (`world+0x3416D8+0x5214+39*196`) =
`27 00 05 00 | 00 00 FF FF 1F 00 00 3F | 2B 00 … 3F | 05 00 … 3F | 1A 00 … 3F | 18 00 … 3F` (u16 cardId, u8 count, pad, 8-byte entries
{u16 id, FF FF 1F, 00 00 3F}) — the mask byte IS 0x3F now. But the context object 0xC9E638 has `+0x484 (command slot) = 0xFFFFFFFF`
while `+0x488 (card) = 39`, so FUN_004FDF20 returns `block - 4` and the tested byte is the block's pad byte (0) => no rows. The slot is
only READ by direct address (0x52F73B … 0x57C5C6); its writer is indirect (search for `[reg+0x484]` stores pending). Hypothesis: the slot is
the command's index inside the 70-byte static CARD record's own command-id list (0x0305; the authority lists 0, 0x2B, then the extras) or
is assigned by a per-command lookup that fails for ids appended via LOGH7_EXTRA_CARD_COMMANDS.

### Run 20260903T072741Z (extracmd36) conclusion: the command SLOT is set for card-target commands only
RPM: with the card open `0xC9E638+0x484 = -1`; with 任命 open (state 12) `+0x484 = 2` = 任命's index in the card's command list
`[0, 0x2B, 26, 24]`... (in this run the list is [0,0x2B,26,24] and 任命 was not exposed; the value 2 came from the default card list) —
so the launcher sets the slot when a CARD-target command opens. 部隊解散/発令 (targetKind BASE) reach state 6 with the slot still -1, so
FUN_004FDF20 reads the block's pad byte and the post sub-menu is empty. The 0x0307 parser (FUN_0040F9F0) block layout is
{u16 cardId, u8 count, u8 pad, 24 x {u16 id, g1 g2 g3, m1 m2, m3}} (stride 196), i.e. the mask byte we serve is right but unused here.
=> BASE-target commands must be launched from a base context (character docked at a base), which sets the slot from the base panel.
The character record (0x0323, authority EncodeCharacter) has several zeroed u32 fields before gridUnitId; one of them is the base/location
(HUD 「惑星／要塞軌道上: 艦内」). Next: map the 0x0323 record (parser FUN_00417390) and serve the character docked at base 1 of grid 102.
ESC on the strategy screen opens the ゲーム終了 prompt (取消し at 638,436); the list dialogs close with their right button (822,608).
The client keeps g7mt-debug.log open, so -Delete fails while it runs (collect it only after the client exits).

### 0x0325 ResponseInformationUnit wire layout (parser FUN_00419CA0, "over than 600")
`u16 count(<=600)` then per unit (parsed cell stride 0x58): `u32 unitId, u16, u8(0..2), u32, u32, u32, u8 n(<=10) + u32[n], u32, u8(0..2),
u8(0..2), u16, u16, u32, u32, [vt+0xC] 8-byte field` = 46 + 4n bytes on the wire. The request 0x0324 is still unhandled by the authority
(the generic echo answers it under LOGH7_COMMAND_ECHO). Other information parsers located by their strings: Base 0x0414xx (cap 4),
Institution 0x04168xx (cap 4), Warehouse 0x041A8xx (troops 24 / ships 99), Package 0x041B2xx (troop packages 24 / other 3), Outfit 0x041BCxx (cap 100).

## 2026-09-03 罷免 vertical VERIFIED (run 20260903T080842Z-natural-l1-relogin-v1, extracmd37 zip A219EC80…, ExtraCardCommands 1,6)

罷免 (CommandCardDismissal 0x0708) is now PLAYER_VISIBLE_REPRODUCIBLE (condition-9-dismissal-verification.json 18/18,
bind-condition-9-dismissal-verification.py). Path: 任命 card 40 -> character 2 (event 25), then card 罷免 -> post 艦隊副司令官(40)
-> holder picker (0x1200 selector 0x0005 -> 0x1202, lists アッテンボロー = character 2) -> holder -> confirm
「をから罷免します。コマンドポイント160MCP消費…」決定 -> `0x0708 card-dismissal-accepted;card=40;target=2;appointer=2;authorityVersion=26`
-> original_character_card emptied, domain_event CharacterCardDismissed{cardId 40, target 2} -> restart on same DB -> relogin ->
appointment stays removed (account 26 stable). Authority (extracmd37): migration 0014 `original_card_dismissal_command`,
`DismissCardAsync` (inverse of AppointCardAsync: verifies the target holds the card, DELETEs original_character_card, records the
dismissal, emits the event); store failures soft-reject via `RejectCommandVisibly` (0x0500) 「罷免できません…」 instead of a disconnect.
**Card geometry gotcha**: 罷免's screen coordinate depends on the extra-command count — with 2 extras (ExtraCardCommands 1,6) row2 is
抜擢(722,310) 罷免(822,310); with 3 extras (1,2,6) it is 抜擢/降等/罷免 and 罷免 sits at (923,310). Always confirm the button by screenshot.
Remaining personnel command: 辞任 0x0709 (payload captured; needs the character's current card as authority state, not the constant
AuthorityCardId served at world entry).

## 2026-09-03 CORRECTION: card 0 = 個人 is a first-class ORIGINAL state (run 20260903T085429Z, extracmd38)

An earlier entry in this handoff called 辞任 (0x0709) blocked on "an undefined design decision: what a player with no post
should become". **That was wrong, and the client disproves it.** Evidence:
- Static: constmsg **group 3 row 0 = 個人** (the card-id -> post-name table) and **group 4 row 0 = 皇宮**. `EncodeStaticCards`
  already emits card 0 with `commandCount = 0`.
- Live: `LOGH7_WORLD_CARD_ID=0` (new `OriginalAuthoredPlayableCatalog.WorldCardId`, env override of the world-entry character
  record's card, default still AuthorityCardId 39) -> the unmodified client enters the world normally, renders 職務権限カード as
  **「皇宮 ： 個人」** with 「個人」 in the info pane and an **empty command grid**, HUD and stats intact, no crash
  (screenshot `vnc-s4-card.png`). "No post => no strategy commands" falls out of the original data, not from new design.

**Consequence: 辞任 is implementable with a defined target state** — resigning sets the character's card to 0 (個人). What is
still missing is not a design decision but a mechanism: world entry serves a constant card, so it must instead read the
character's current card from `original_character_card` (falling back to 39). Note `original_character_card.card_id` has
CHECK (card_id BETWEEN 1 AND 65535), which must be widened to allow 0.

Harness: `host-run-fresh-run.ps1` / `guest-prepare-fresh-run.ps1` gained `-WorldCardId` (validated `^[0-9]{1,5}$`).

## 2026-09-03 辞任 vertical VERIFIED — the personnel command family is complete (run 20260903T090158Z, extracmd39)

辞任 (CommandCardResignation 0x0709) is PLAYER_VISIBLE_REPRODUCIBLE (condition-9-resignation-verification.json,
bind-condition-9-resignation-verification.py). This closes the 0x0705-0x0709 personnel family: 抜擢, 降等, 任命, 罷免, 辞任.

Authority changes (extracmd39):
- **Migration 0015**: widen `original_character_card.card_id` CHECK to allow **0**, add `original_card_resignation_command`.
- **`ResignCardAsync`**: verifies the card the character actually holds (appointment row, else the authored default 39)
  matches the post the client asked to resign from, then sets the card to 0, records the resignation, emits
  `CharacterCardResigned`, bumps the account version. Soft-rejects visibly (0x0500) on mismatch/replay.
- **World entry now serves the PERSISTED card**: `LoadPersistedCardAsync` reads `original_character_card` during
  `RestorePersistedCharacterAsync`, and the six world-entry `EncodeCharacter` sites use `EffectiveWorldCardId`
  (`LOGH7_WORLD_CARD_ID` still force-overrides for probes). This was the real blocker — a mechanism, not a design call.

Result: 辞任 at (722,310) -> confirm 決定(565,516) -> `0x0709 card-resignation-accepted;from=39;to=0` -> DB card_id 0
-> authority restart -> relogin -> the client renders 「皇宮 ： 個人」 with an empty command grid (`vnc-r4-4-card.png`).

## 2026-09-03 command slot MEASURED for both target kinds (run 20260903T093348Z, extracmd39)

An earlier section in this handoff asserted "BASE-target commands reach state 6 with the slot still -1" while only ever
having measured the slot with the card panel merely OPEN. That reasoning was unsound even though the conclusion happened
to hold. Both kinds are now measured directly, with the command's own sub-panel open:

| command      | id   | target | 0xC9E638+0x484 (slot) | panelState |
|--------------|------|--------|-----------------------|------------|
| 任命          | 5    | CARD   | **2**                 | 12         |
| 部隊解散      | 26   | BASE   | **-1**                | 6          |

Card 39's static command block (world+0x3416D8+0x5214+39*196) is `27 00 04 00` then four 8-byte entries
`00 00|FF FF 1F|00 00|3F`, `2B 00|...|3F`, `05 00|...|3F`, `1A 00|...|3F` — i.e. the 部隊解散 entry AND its post mask
0x3F are served correctly. The empty sub-menu is therefore **not** a mask problem: with slot = -1, FUN_004FDF20 computes
`block + (-1)*8 + 4` and reads bytes before the entry array (the u16 cardId / u8 count / pad), yielding mask 0.

Open question for the base-context work: **why does the CARD-target path set the slot while the BASE-target path leaves
it -1?** No absolute write to 0xC9EABC exists in .text (only reads), so the write goes through a register-based setter.
Next step: hardware breakpoint on 0xC9EABC (guest-hwbp-manager-probe.ps1) while opening 任命, to catch the setter, then
check why the BASE path does not reach it (hypothesis: BASE-target commands are meant to be launched from a base panel
reached by 碇泊/docking, not from the card panel).

Harness note: `ExtraCardCommandIds()` ALWAYS prepends id 5 (任命), so the served command list is
`[0, 0x2B, 5, ...env ids]`. Card button index = position in that list (row-major, 3 per row), so
`LOGH7_EXTRA_CARD_COMMANDS='26'` puts 部隊解散 at index 3 = (722,310), NOT (923,283) which is 任命.

## 2026-09-03 HWBP dead end: Wow64SetThreadContext does not apply debug registers (runs 103508Z / 110044Z)

Goal was to find what writes the command slot 0xC9EABC (set to 2 on the CARD path, left -1 on the BASE path).
No absolute write to that address exists in .text and none of the seven methods invoked with ecx=0xC9E638 touch
+0x484, so a hardware DATA-WRITE watchpoint was the plan. Built `guest-hwbp-write-probe.ps1` (DR0 + DR7
R/W0=01 LEN0=4, hit tested via DR6 bit B0, read-only, same attach/detach shape as the manager probe).

**Result: 0 hits, and the instrumentation says why.** After arming 14 threads (Wow64SetThreadContext returned
true for each), reading the context back gives `dr0 = 0x00000000, dr7 = 0x00000000`. The debug registers are
never actually applied to a WOW64 thread through the 32-bit context path, so the watchpoint never existed.
Exception samples during the window are 8x 0xC0000005 (first-chance access violations the client handles with
its own SEH) plus the initial 0x80000003 attach breakpoint — no single-step, as expected with DR7 = 0.

Consequences:
- To set debug registers on a WOW64 thread from a 64-bit debugger, the **64-bit CONTEXT** must be used
  (Get/SetThreadContext with CONTEXT_AMD64|CONTEXT_DEBUG_REGISTERS, Dr0@0x48 Dr7@0x70, 1232 bytes, 16-byte
  aligned), not Wow64Get/SetThreadContext. That is the fix if this path is resumed.
- **No previously bound evidence is affected**: every condition-2 receipt is RPM/read based
  (subPanelCell, inputOwner, armByte05 ...); `guest-hwbp-manager-probe.ps1` was never the basis of a bound
  verification. Its execution breakpoints were presumably equally inert, which is worth knowing before trusting it.
- Separately fixed in both probes: `continue` inside do{}while() exits the loop in PowerShell, which made
  DisarmAllThreads stop at the first foreign thread. Harmless while DR7 never applies, but it would leave a stray
  DR7 (and kill the client) the moment the 64-bit path works.

The slot writer therefore remains unidentified, and the BASE-target sub-menu question is still open.

## 2026-09-03 HWBP part 2: the 64-bit CONTEXT path WORKS, but the writer is still not captured

Following the earlier finding that `Wow64SetThreadContext` silently drops debug registers, the probe was converted
to the **64-bit CONTEXT** path (`GetThreadContext`/`SetThreadContext` on a 16-byte aligned 1232-byte buffer;
ContextFlags@0x30 = CONTEXT_AMD64|CONTROL|INTEGER|DEBUG_REGISTERS, Dr0@0x48, Dr6@0x68, Dr7@0x70, Rip@0xF8).

**That fix is confirmed working** (run 20260903T131036Z): read-back now returns
`dr0 = 0x0000000000C9EABC, dr7 = 0x00000000000D0001` on every armed thread, where the 32-bit path had returned zeros.

**But the write is still never captured**, and the remaining measurements point at the method, not the address:
- Run 20260903T131702Z read the slot immediately before arming: **slot = -1, card = 39**. So a 任命 click had to
  write it (the CARD path sets it to 2). The click was sent, yet `totalHits = 0`.
- After any HWBP attach, subsequent guest RPM steps fail with `GUEST_EXIT_CODE=1` even though the client process is
  still alive, and `disarmed` stays 0 even after the thread enumeration was rewritten to be array-based.
- Exception traffic during the window is dominated by first-chance 0xC0000005 that the client handles in its own SEH.

**Working hypothesis (not yet proven): the client cannot process input while the debugger is attached.** The target
is frozen between WaitForDebugEvent and ContinueDebugEvent, and with a first-chance AV storm the client is stopped
and resumed constantly, so the VNC click never reaches the command panel and no slot write ever happens. That would
explain hits = 0 together with a demonstrably armed watchpoint.

To settle it, the click must come from INSIDE the same guest process that owns the debug loop (e.g. fold
guest-click-point.ps1's SendInput into the probe and fire it a few seconds after arming), so the click is not
competing with a frozen target over VIX. Until then the slot writer at 0xC9EABC remains unidentified and the
BASE-target sub-menu question is still open.

Cost note: this path needs a fresh run per attempt and has now consumed four; the host C: drive also filled to 0 MB
mid-way (cleaned to ~8 GB by clearing uv/npm/Temp caches only).

## 2026-09-04 HWBP part 3: attaching a debugger reliably KILLS this client - path abandoned

Three further attempts, each fixing a real defect found in the previous one, all ended the same way.

| attempt | fix applied | watchpoint armed | hits | client after |
|---------|-------------|------------------|------|--------------|
| 131036Z | 64-bit CONTEXT for DR0/DR7 | yes (dr7 = 0x000D0001 read back) | 0 | guest RPM steps start failing |
| 225555Z | click fired from INSIDE the debug loop (SetCursorPos + mouse_event) | yes | 0 | **dead** |
| 230121Z | disarm rewritten inline over the armed set | yes | 0 | **dead** |

The last run also explains the recurring `disarmed = 0`: by the time the loop ends, a fresh thread snapshot finds
only ONE thread of the process (`disarmCandidates = 1`) and even that one fails to clear (`disarmFailed = 5260`) -
the client is already collapsing before the probe detaches, so the stray-DR7 theory was a symptom, not the cause.

What actually happens is narrower and more damning: the debug-event stream is dominated by first-chance
**0xC0000005** access violations (19 in a 30 s window) that the client normally swallows with its own SEH. Under a
debugger those exceptions are routed to us first, and no matter that they are passed back with DBG_EXCEPTION_NOT_HANDLED,
the client does not survive the window. No EXCEPTION_SINGLE_STEP is ever delivered, so the watchpoint never fires
even though DR0/DR7 verifiably hold the right values.

A steady stream of self-inflicted access violations is a common anti-debugging shape, so the working conclusion is
that **this client cannot be observed with an attached debugger at all**, and the technique - not the address, not
the DR encoding, not the click transport - is what is wrong. Further HWBP attempts are not worth the cost: four
runs were spent, each needs a fresh VM run, and the host C: drive filled to 0 MB twice during them.

Everything learned here is still worth keeping: the 64-bit CONTEXT DR fix is real and now proven, and it is the
right implementation if a debugger is ever usable against a different binary.

**The slot writer at 0xC9EABC therefore stays unidentified.** The BASE-target sub-menu question should be attacked
from the other side instead - reaching the base panel through 碇泊/docking, which is the entry point the original
seems to intend - and that is pure RE plus world modelling, with no debugger involved.

## 2026-09-04 base path: the card-command dispatch table explains two of the three failures

Static RE of the dispatcher (no debugger needed) - receipt `client-card-command-dispatch.json`.

`FUN_00571870` walks an 8-byte table at **0x6756B0..0x67573C** (`{u32 commandId, u32 kindIndex}`, **17 entries**) and
resolves kindIndex through the pointer table at 0x78BB30 into a `TARGET_SELECT_*` state-name. The 17 card-panel
commands are 0 昇進, 1 抜擢, 4 叙勲, 5 任命, 12 会談, 14 演説, 15 国家目標, 16 納入率変更, 20 外交, 21 統治目標,
24 発令, 26 部隊解散, 27 講義, 28 輸送計画, 30 税率変更, 31 施設建設, 33 施設再稼動.

**部隊結成 (25) and 艦艇建造 (35) are not in that table.** Serving them via LOGH7_EXTRA_CARD_COMMANDS draws a button
the dispatcher has no target kind for, so they can never open a picker. Their 「選択可能な拠点が存在しません」/silence
was never a world-context problem, and the earlier ledger note blaming "needs a base in the current grid" was wrong.

The state-number -> name jump table at **0x5710C8** decodes as: 1 S_CHARACTER, **3 S_BASE**, 4 S_OUTFIT,
5 S_SUPPLY_OUTFIT, 6 OUTFIT_TYPE, 7 S_UNIT, 8/9 S_RANK, **10 S_CARD**, 11 S_CARD_OUTFIT, **12 S_CARD_BASE**,
13 S_STRATEGY, 15 CASTPLANET, 16 GRID, 18 OUTFIT_TYPE.

**Correction to earlier notes in this handoff**: the value read via RPM as `panelState` (0x00CA3940+4) is NOT this
TARGET_SELECT state. 部隊解散 dispatches as BASE = 3 but measured 6. Statements like "state 6 = the post sub-menu"
and "state 12 = TARGET_SELECT_S_CARD" conflated two numbering schemes and must be re-derived before reuse.

Two hypotheses were tested and **both refuted** for the remaining BASE commands:
- position: the character and the authored base share grid cell 101 (DB `current_cell_id = 101`), so "warp to a grid
  with a base" was already satisfied;
- class: serving the base with class 3 (the known planet family) instead of 1 changes the rendered scene but still
  yields 「選択可能な拠点が存在しません」. `LOGH7_BASE_KLASS` was added (extracmd40) to sweep the byte.

So for 演説/発令/部隊解散/施設再稼動 the open question is precisely **what TARGET_SELECT_S_BASE (state 3) enumerates**,
and that is the next thing to read - a static question, no debugger and no world modelling required.

### TARGET_SELECT_S_BASE resolved: the BASE target is the character's CURRENT base, not a list

Reading the dispatcher through to the target-kind jump table at **0x571D84** (`jmp [kindIndex*4+0x571D84]` at
0x571A6A) gives the per-kind handlers: 0 RANK 0x571A71, 1/2 STRATEGY 0x571A81/0x571A8B, **3 BASE 0x571999**,
4 UNIT 0x571A95, 5 CARD 0x571A9F, 6 none 0x571AAF, 7 CASTPLANET 0x571AC2.

The BASE handler builds no list at all:

    00571987  cmp   dword ptr [esi+edi*8], 3     ; requirement kind == BASE
    0057198d  mov   ecx, [esp+0x20]              ; context object (arg -> +0x1c)
    00571991  call  0x4B5B50                     ; lea eax,[ecx+0x318]
    00571996  mov   eax, [eax+8]                 ; field +0x320
    00571999  mov   [esp+0x3c], eax              ; that single value IS the target

So 演説/発令/部隊解散/施設再稼動 answer 「選択可能な項目が存在しません」 because **+0x320 (the character's current
base) is unset** - the character is 艦内, never docked. This retroactively explains both refuted experiments: the
authored base sharing grid cell 101 and its class byte were never consulted, because the command never looks at
bases in the grid.

**This vindicates the docking route.** 碇泊 is a UNIT command (constmsg group 0 row 54; row 21 「出撃」 describes
駐留 -> 碇泊), so the ordering is: authority serves units (0x1207 is currently count = 0) -> a unit command docks the
character at the authored base -> +0x320 becomes valid -> the four BASE card commands open. No debugger needed for
any of it.

### Unit record field map recovered, and the base field verified live (run 20260904T071118Z)

`FUN_0042F930` (the `_INF:NotifyChangeFlagShip#` logger) prints the 0x58-byte unit record with field names, giving the
full map: +0x00 id, +0x04 kind, +0x08 mode, +0x0A grid, +0x0C outfit, +0x10 boarding_ship, +0x14 troop count,
+0x18.. troop_units[n], **+0x40 base**, +0x44 morale_max, +0x48 rebellion, +0x49 damaged, +0x4A destroyed,
+0x4C supplies, +0x50 mobilization, +0x54 cruising. On the wire `base` is the u32 right after the troop array - the
slot `EncodeUnit` had been writing as 0.

Serving `base = BaseId` (LOGH7_UNIT_BASE, default now the authored base) **works and is visible**: the HUD's
lower-right view button changes from 「星系内宇宙」 to **「惑星第1拠点」**. The field identification is therefore correct.

It is not sufficient for the BASE commands: 部隊解散 still reports 「選択可能な項目が存在しません」, the HUD location
still reads 艦内, and the new 惑星第1拠点 button is inert. **The unit's base field is not the BASE-command context
field +0x320.** The unit now belongs to the base; the character has not entered it.

Next: identify the object that owns +0x320 (the BASE handler reaches it as arg->+0x1c then +0x318+8) and what sets it.

### Build environment moved off C:
NuGet packages and the build temp now live on E: (`NUGET_PACKAGES=E:\logh7-build
uget`, `TMP`/`TEMP=E:\logh7-build	emp`).
Builds no longer consume the host C: drive - free space actually rose during the last build. Set these three variables
in any PowerShell that runs `dotnet publish`; without them a restore can fail with "디스크 공간이 부족합니다".

### +0x320 traced: the BASE target comes from TacticsInformationUnitShip, which the authority never serves

Chain: `FUN_00571870` BASE branch takes `ecx = arg->+0x1c`, calls `FUN_004B5B50` (just `lea eax,[ecx+0x318]`) and
reads `[eax+8]`, i.e. **+0x320**.

The structure at +0x318 is identified: its ONLY writer is `FUN_004B5B60` (`rep movsd ecx=0x16`, i.e. 0x58 bytes into
+0x318), whose single caller at 0x4C3C80 lives in code whose failure string is **`TacticsInformationUnitShipが無い`**
(0x771178). The `_INF:ResponseTacticsInformationUnitShip#` logger at 0x422630 confirms the record's fields:
id, morale, confusion, character, {x,y,z}, direction, detachment_leader, {x,y,z}, detachment_direction, search.

So the BASE-target commands read their target out of the **tactics unit-ship record** - not from a base list, and not
from the unit's own `base` field (which we separately proved only changes the HUD view button). The authority serves
no unit-ship information whatsoever: **0x030A RequestStaticInformationUnitShip / 0x030B Response... are unhandled**,
and nothing ever populates the tactics record, so +0x320 stays unset and all four BASE commands report
「選択可能な項目が存在しません」.

Next concrete step: serve unit-ship information (start with 0x030A -> 0x030B; read the layout from
`Input_ResponseStaticInformationUnitShip::input_from_stream`) and see whether the tactics record then materialises.

## 2026-09-04 correction and expansion: strategic WARP, every command surface, and tactical combat

The subsequent `0x030B/0x033B` work proved that the preceding `+0x320` interpretation was incomplete. `FUN_004C32A0`
joins a `0x033B TacticsInformationUnitShip.id` to a `0x0325 InformationUnit.id` and copies the full 0x58-byte
InformationUnit into scene `+0x318`; scene `+0x320` is therefore copied InformationUnit `+0x08 = grid`, not `base`.
The authority now serves a compact non-empty `0x030B`, proactive and requested `0x033B`, and selector `0x001E ->
0x1207`, but that was only the scene/context bootstrap and was not by itself a playable-game proof.

The WARP paths are also two distinct protocols:

- strategic grid movement: `0x0B01 CommandMoveGrid -> 0x0B07 NotifyMovedGrid`;
- tactical ship transition: `0x0404 CommandWarpShip -> 0x0425 NotifyWarpedShip`.

`Input_NotifyMovedGrid::input_from_stream` at `0x0044B460` proves the compact `0x0B07` body is
`time:u32, id:u32, grid:u32, base:u32, mode:u16, count:u8, count*{unit:u32, cruising:4B}` (cap 70). The four-byte
cruising scalar is the same representation that InformationUnit `+0x54` logs and renders as `float`. The previous
authority sent zero in that pair and sent zero again in the follow-up `0x0325`; it moved the cell while erasing the
cruise value. This is now corrected with an explicitly `NEW DESIGN` authored value: initial cruising `10.0`, one-hop
cost `1.0`, destination value `9.0`. Both `0x0B07` and the post-move `0x0325` project the same `9.0`. These numbers are
not recovered original balance constants and still need original-runtime validation.

`0x0425 NotifyWarpedShip` does **not** carry cruising, morale, supplies, or coordinates. Its logger at `0x004A60A0`
names only `time, grid, base, mode, unit[]`. Tactical coordinates instead live in `0x033B` and are commanded by
`0x0400 MoveShip`.

The command audit is no longer limited to the 17 card-dispatch entries. The generated coverage receipt is
`work/20260904-warp-state-reverse/evidence/gameplay-command-coverage.json`:

- 97 strategy commands from `strategy-command-ledger.json`; only 7 are currently `PLAYER_VISIBLE_REPRODUCIBLE`;
- 31 tactical command types in `0x0400..0x0422`;
- 29 tactical outcome/notification types in `0x0424..0x0442`;
- every tactical row includes request kind, client expanded structure size, recovered compact layout where available,
  implementation state, and a separate live state.

Implemented and test-covered now:

- `0x0400 MoveShip`: exact compact decoder, controlled-unit gate, session-authoritative x/y/z/direction, command echo,
  and `0x033B` re-projection;
- `0x0404 WarpShip`: exact compact decoder, controlled-unit gate, and `0x0425` projection;
- `0x0405 AttackShip`, `0x0406 ShootShip`, `0x040A Stop`: exact compact decoders, ownership/target validation, and
  original command relay.

Current verification: `Logh7.Server.ProtocolTests` 33/33 pass. This is focused-test evidence only. All new tactical
paths remain `UNSEEN` in the original runtime. Attack/shoot currently have `outcome=not-resolved`: no damage,
destroyed state, retreat, ending/victory, persistence, or strategy-scene return has been implemented. Therefore the
earlier `docs/handoffs/2026-09-04-unitship-playable-vertical.md` title must not be read as full game or tactical-combat
completion.

Controlling reverse evidence and next path:

- `work/20260904-warp-state-reverse/evidence/E-002-warp-tactical-static.md`;
- next: add a second opposing tactics unit and faction/character projection, then implement damage notification plus
  InformationUnit damaged/destroyed state, retreat/ending/victory, persistence, and return to the strategy scene;
- only after those paths pass tests should one natural-client run verify pre/during/post wire, UI, memory, and DB.

## 2026-09-05: non-empty 0x033F restored tactical selection; natural-client 0x0400 MoveShip is live-verified

The remaining selection failure was not in `0x030B` communication-range encoding. The live v8 run proved the static
template contained `communication_range=100` while each tactical entity still had range 0. `FUN_004C32A0` only copies
that static range after it joins the entity owner/character ID against the table at world `+0x4044B8`. The dispatcher
writer proves that table is populated exclusively by `0x033F ResponseTacticsInformationCorps`; the authority had been
serving an empty response.

The broken Ghidra exception-function boundary was bypassed with an instruction-range export. The real binary parser
starts at `0x00422D80`, rejects counts over 600, expands each record to `0x3C`, and consumes an exact 55-byte packed
record. The adjacent logger at `0x00423560` names the fields:

`id:u32, mission:u8, target_kind:u8, target:u32, command_range:f32, tactics_chief:u8, file:u8, power_move:u8,
power_warp:u8, power_sensor:u8, power_beam:u8, power_gun:u8, power_shield:u8[6], fill_beam:u16, fill_gun:u16,
fill_shield:u16[6], damaged_shield:u16[6]`.

`OriginalSystemSceneCodec.EncodeTacticalCorps` now emits that shape. The record layout is `ORIGINAL_STATIC`; the two
corps IDs and balanced nonzero power allocation are explicitly authored playable server data, not claimed original
balance constants. Focused protocol tests are now 49/49.

Natural-client run `20260904T203500Z-natural-l1-relogin-v1` is the first live tactical movement receipt:

- server ZIP SHA-256 `DD4698B3474CBD723FB536AB42D2813F90D4FA2AD8E360D30B9DCE65C11DB24D`;
- `tactical-pick-layers-v9.json`: player entity range 100, selection manager far 100 / near 67.525, player flags
  `0x00010481` (`friendlySelectable=true`);
- the player ship was clicked once; `sample-selection-v9.json` recorded candidate=1 and selection=1;
- Move icon row 0 column 0 entered target mode. The input handler around `0x0050EAD7..0x0050EDE3` proves path kind 2
  completes when the current position plus two endpoint clicks yields three path points; this was used instead of a
  blind click loop;
- `wire-after-move-confirm-v9.json` records processed application type 1024 (`0x0400`) and
  `tactical-move-ship-accepted;unit=2;x=-9.926877;y=-3.9191113;z=0;direction=0`;
- `corps-position-v9.json` reads the client pick registry after the response: player position changed from
  `(-10,0,0)` to `(-9.926879,0,-3.919014)`. The client maps the server's tactical Y onto its rendered Z axis.

This changes only `0x0400 MoveShip` from UNSEEN to live wire+memory+UI verified. Tactical combat remains PARTIAL:
AttackShip/ShootShip target selection, `0x0426` damage, destruction, retreat/ending, victory/result, persistence, and
return to strategy are still not live-closed. The current run remains active for that continuation.

## 2026-09-05 — Flagship model selector, shifted creation fields, PostgreSQL roundtrip

User asked why Brunhild always appears and where to check the flagship. Current evidence and next start:
`work/20260904-warp-state-reverse/evidence/E-004-flagship-selection.md`.

- The active run is `20260904T205700Z-natural-l1-relogin-v1` (v10 autoattack server). Guest PID 1112 is alive,
  responding and rendering. A prior host PID lookup was wrong; no post-0x0426 crash is established.
- `wire-after-autoattack-v10.json` proves 0x0405 accepted with enemy damage 25; full client damage/result closure remains open.
- `flagship-model-memory.json` proves both active tactical units have staticIndex=0/modelFile=0. The server still
  provides only one static ship template. `FUN_004F3D80` selects model_file from normalized template +0x20C;
  `FUN_004F3F70` chooses the LOD table and loads it through `FUN_004F3A10`. Values 0/1 select GE/EM001.
- `ship-model-catalog.json` contains 360 original LOD pointers, 348 distinct paths, 247 present files and 101 missing
  table paths. Do not infer named-ship identity from constmsg row number: group 85 row 8 says Brunhild but is not model_file=8 evidence.
- Original 0x1008 parser/logger proved the old field names were shifted: SpecialAbilityCount was called Title,
  Title was called Rank, Rank was called FlagshipClass, and FlagshipType was called FlagshipModel. Corrected the DTO/parser.
- Added migration 0016 and type/kind persistence through ordinary/lottery insert, event, list and session restore.
  Existing rows without source values remain NULL in DB; current compatibility projection still uses zero.
- 51 protocol tests pass. Separate actual PostgreSQL probe passed legacy migration, nonzero/full-u16 selection
  create/read across reconnection, idempotent replay, event values and paired-null constraint. Cluster 55433 was stopped;
  the live game DB was not modified. Receipt: `flagship-storage.json`. The changed server is not yet deployed.
- Manual and static consumer say common Information icon -> third entry `旗艦情報`. Current tactical book icon
  tooltip `情報` is observed, but the detail sheet did not open in the retained clicks. Do not claim live UI completion.

Next: recover the original selected kind-to-template/model assignment (including lottery characters), replace the
one-template bootstrap coherently, determine the tactical Information menu event gate, then deploy and validate
different ships and relogin persistence. Continue remaining strategic/WARP and tactical damage/ending work;
the full goal and entire-resource reverse-tracing requirement remain active.

## 2026-09-05 — Actual flagship sheet, cease-fire live check, 0x040C connection-drop repair

Read these bounded receipts next: `work/20260904-warp-state-reverse/evidence/E-004-flagship-selection.md`,
`E-005-attack-submodes.md`, `E-006-control-power.md`. This section supersedes the earlier unverified Information-sheet status.

- Correct tactical Information button: paper/document at `(752,744)`, NOT book `(802,746)`.
  `0054E760(mode1) -> 00519C50(selector0)` enables manager 0x0B/category1/index7, attached manager0x16.
  Click third menu row `(795,614)` for `旗艦情報`; the original three-tab sheet was live-opened.
- Original static ship input skips the name buffer for an empty counted string; the sheet consumes it as a
  NUL-terminated string. Sending an explicit terminating element fixed the garbage name in the live v11 sheet.
  `20260904T215200Z-natural-l1-relogin-v1/flagship-v11.png`, SHA256 `A90B253153216EC37DE8244019F06D02643982DCF4733E31B93425CECB489161`.
  It now stays blank, not correctly named: the one-template/model-0 bootstrap still needs original assignment data.
- Original faction table: 0 Unified, 1 Neutral, 2 Empire, 3 Alliance, 4 Pirates. New authored lottery entries now
  use Alliance 3 and authored opponents use 2/3. Existing power-2 rows are unchanged; do NOT blanket migrate 2 to 3.
- `00522010` returns `NO TABLE` when the requested group is absent, `NO DATA` when its row is absent.
  Missing tactical constmsg groups (including 0x7B) require the original data/version/loader join, not guessed labels.
- Attack submenu indices 0x1E/1F/20 -> UI modes1/2/3 -> wire kinds1/2/0 through `004B4110`.
  v10 ignored kind and would damage even on cease fire. Fixed kind validation and stop target resolution.
- **Live cease fire passed** in v12: selected friendly, inspected `攻撃停止` tooltip, clicked once.
  `wire-after-cease-fire.json`: kind0 accepted, enemy damage0/destroyed0, no extra damage pushes, session retained.
  `memory-after-cease-fire.json`: UI mode3, selection1, entire enemy InformationUnit unchanged versus `power-before.json`.
  This is not proof of cancelling a running continuous attack; timed attacks remain unimplemented.
- v11 disconnected when closing the flagship sheet generated `0x040C CommandControl` over the SENSOR control.
  `wire-after-disconnect.json` has the exact 31-byte request and unexpected-type rejection. Implemented decoder,
  owned-unit/budget gates, per-session corps power state, expected command echo and refresh; bad input is now a
  visible soft rejection. Condenser/recharge/speed/weapon effects/wait timing/relogin persistence remain incomplete.
- v12 live tests verified two over-budget control requests are visibly rejected without disconnect. Initial
  track click did nothing; knob drags unexpectedly changed displayed SENSOR 10->15->16 while authoritative
  character memory stayed10. **Positive accepted control change is UNSEEN**, despite files named `power-accepted*`.
  Rejected HUD value resynchronization also remains. Do not repeat those drags: trace the exact native widget
  input handler (manager0x36/category1/index10, setup005123B0) before the next input.
- The third command u32 is logged `id=`, sourced by `004B4A90` from the current actor and consumed by `004C1700`
  as actor identity; it is NOT an order counter. New Control DTO uses ActorId and rejects a foreign actor.
  That last hardening is source-only after v12 deployment. Older tactical DTO `Order` names need an audit.
- Current source tests: **66 passed, zero skipped**. v12 was built before the final actor check, with 65 tests.
  v12 ZIP `E:/logh7-build/logh7-server-v12-control.zip`, SHA256 `887C3164EDE988AC84FFDF8443CC9B060BB9E0F159E9A1A9F17DEC22700A7BAC`,
  DLL `FD5E1A3EC3EC5C76FEA22180338ACCC63C51D0CBA500DD909C05BC37DC6B108E`.

### Current live target / next start

Active run **`20260904T220400Z-natural-l1-relogin-v1`**: guest client PID **2648**, HWND **`0x00000000031301FC`**,
authority PID **5716**. Same VM `E:/logh7-vms/oracle-win11-hd-re/oracle-win11-hd-re.vmx`, client item116 SHA256
`AEF3827602CD13A395618BDEF0F48F44BCB8ED60F4FA9C2017F2D2E128660F2F`. These are GUEST PIDs, never inspect them on the host.
v10 and v11 clients/authorities/PostgreSQL copies were cleanly stopped, with data/logs/screens preserved; no run deletion.
New `host-step` names are single-use. Preserve existing receipts and inspect the current surface before input.

Next bounded work: power widget input semantics + rejected-value resync; include actor hardening in next deploy;
then timed attack/weapon/damage/destruction/ending/return and original flagship template assignment. WARP cruising,
other commands, celestial/planet/orbit joins and complete resource-use tracing remain part of the active overall goal.

## 2026-09-05 — Accepted control was queued in the far future; v13 executes and changes native sensor range

Read the last two sections of `work/20260904-warp-state-reverse/evidence/E-006-control-power.md` next.
This supersedes the preceding accepted-control UNSEEN status and incorrect SENSOR widget index.

- SENSOR is manager0x36/category1/index6 (not10). Orientation3 is reverse-horizontal: right decreases.
- v12 evidence-backed right drag accepted sensor5, but HUD5 did not mean execution: normalized character stayed10.
  Input vtable0066D870/parser0049E820 and output vtable0066DAD4/writer00495AA0 agree on the 29-byte body.
- Root cause: sender004B4370 does not initialize Time/Wait. Queue selector004B8B00 case040C uses their sum;
  004B8950 only dispatches zero/due times. Same-run `control-queue-clock.json` proved deadline222547400 versus
  clock3019. The last received-control scratch was still the initial enemy record, not the player's new command.
- Added exact-payload `EncodeImmediateResponse` for the authority's immediate control policy: copy, clear both
  scheduling fields, preserve the rest. Two new tests failed before the fix, then full protocol suite **68/68 passed**.
  Other tactical command echoes are not silently normalized by this fix; audit them next.
- v13 deployed and original-client state verified: SENSOR10->5, BEAM20->0, SENSOR5->30. Accepted control is now
  present in both received scratch and normalized character, and the receive queue is empty.
- Native player entity +0x958 sensor range changed **50->100**; enemy stayed70. HUD shows beam0/sensor30.
  This is an ORIGINAL_CLIENT derived effect, not server visibility filtering or a distant-target detection test.
  `sensor-thirty-state.json` SHA256 `B1955B564057E894CFA83A3D1818A06D41741A3AD2941FE0B851B98209FE7400`;
  `sensor-thirty.png` SHA256 `59D450D8B8421127C852A5BD791876F0BC47B148D6F950FB58CD9BF5AC168B6B`.
- v13 includes prior actor-ID hardening. ZIP `E:/logh7-build/logh7-server-v13-control-time.zip`, SHA256
  `16A52BE307022CBC8384B35028FE21B5B8352B0ACF7B2671A69413B6286953D5`; DLL
  `E23D41BC6A327B25DF911F1ECF514A21B455FA770B7356342D7DAB2F4574A092`.

### Current live target / next bounded work

Run **20260904T224600Z-natural-l1-relogin-v1**, guest client PID **7180**, HWND **0x00000000032003DC**,
authority PID **8400**. Same oracle-win11-hd-re VM and item116 hash
`AEF3827602CD13A395618BDEF0F48F44BCB8ED60F4FA9C2017F2D2E128660F2F`. These are GUEST PIDs.
Latest screen is tactical, BEAM0/SENSOR30; last selection count0. v12 client/authority/DB were cleanly stopped;
its data, deployment and all receipts remain, no deletions. Revalidate target before the next input.

Next: restore canonical HUD allocation after rejection; trace condenser/channel effects and audit all other
command time/wait/actor fields before timed combat work. Full WARP/cruising, strategy commands, battle outcomes,
original flagship kind/template/model assignment, missing tactical text, planet/orbit data joins, tactical map
data ownership and **all installed resource use-site/selector tracing (WI-013)** remain active goal requirements.

## 2026-09-05 — 0x0426 damage/destroy/morale corrected; actual first-hit projection verified on v14

Read the final continuation sections of `work/20260904-warp-state-reverse/evidence/E-005-attack-submodes.md` next.

- Original logger004A6400 definitively names 0x0426 fields: time, unit, arms, target_kind, target, damage,
  destroy, direction_shield, damaged_shield, **morale**. Old DTO names and session assignments were wrong.
- Receiver004C0DF0 sets native normal=Number-damage, remaining=Number-destroy, morale at entity+0x954.
  Effects use004C7790 arms ranges0..7/8..11/12..15 -> kinds1/3/2 and the004B3460->004CAB80->004E4450 chain.
- Shoot HUD indices21/22/23 set family0/1/2; this is not the actual weapon ID required by a hit notification.
- v13 live single continuous-attack click: server damage25/destroy0, but native enemy75normal/75remaining/morale0.
  Over six minutes without another input still showed the same state: periodic continuous fire is absent.
- Fixed the notification builder and DTO names, shared equipped-weapon capabilities with the static codec,
  resolved Shoot families through equipped arms, and stopped zeroing gun/missile fields in030B. Default hull
  remains the existing authored beam-only model0; named/original ships were not invented.
- Corrected terminal destroy from boolean1 to cumulative100 for the current authored100-unit/four-hit rule.
  That combat rule is still a placeholder, NOT recovered original damage balance.
- Three damage/count regressions, eight weapon-resolution cases and one static weapon wire regression all
  failed before their fixes; full source protocol suite now **79 passed, zero skipped**.
- v14 LIVE first-hit proof: received arms1/target-kind1/damage25/destroy0/morale100; native enemy
  **normal75 / remaining100 / morale100**, player100/100/100, empty receive queue, connection3 retained.
  `attack-first-native-state.json` SHA256 `088C5030C2A14531108C36C3436171126B643E5D4C34E914FDCF8ECE6090043E`;
  `attack-first-wire.json` `583735D50BB8D2ADF3991A1E2E48045CA33F1DF4932989098E8CCE73A3D362FF`.
  The screenshot is a tactical HUD receipt, not proof of visible enemy damage or a shot animation.

### Current target and next executable boundary

Active run **20260904T231600Z-natural-l1-relogin-v1**: guest client PID **7588**, HWND **0x0000000003160192**,
authority PID **5260**. Same oracle-win11-hd-re VM and item116 SHA256
`AEF3827602CD13A395618BDEF0F48F44BCB8ED60F4FA9C2017F2D2E128660F2F`. One attack has occurred; enemy25/0,
friendly selected, beam20/sensor10. v13 runtime was cleanly stopped; all data and evidence preserved, no deletions.
v14 ZIP `E:/logh7-build/logh7-server-v14-attack-fields.zip`, SHA256
`D6C0970BB058EA7ED117A577D59F5A5B91FD372FFED857108208251CBCD8A646`, DLL
`60C3C10482BA9C4779839C8CF1F47049348A68D4E8529F4453BEAE58AA209B07`.

Next, recover original0301 clock parser/synchronizer and power-table/charge consumers. Current0301 sends
fixed bytes00000040 (byte meaning not yet verified); current beam/gun charge tables are placeholders, and
NaturalAuthorityServer.HandleConnectionAsync only advances on complete inbound frames. Add a single-owner
battle tick/push path after those semantics are grounded, not a guessed repeating25-damage timer. Also trace
the result HUD and035A fields before promoting the current minimal ending summary to a tactical victory.
No terminal battle/return or continuous-fire pass is claimed. All prior strategy/WARP/cruising, other commands,
flagship assignment, celestial/map data and complete installed-resource tracing requirements remain open.

## 2026-09-05 — original clock rollback fixed and live-verified on v15

Details and hashes: `work/20260904-warp-state-reverse/evidence/E-007-original-game-clock.md`.

- Original0301 parser004AA250 reads one network-order u32 tick.004C5A30 anchors the clock;004C5A70 advances it at24Hz.004B68F0 requests sync after strictly more than300ticks (~12.5s), storing its last tick at007CD000. Sender004B78A0 switches on requestKind-1: kind30→case2F→0300; field-update kinds2E/2F instead send0348/034A.
- v14 always sent body00000040=64. A read-only120s sample directly captured clock4068→66 at a new response. This also explains the observed sync intervals growing by about12.5s after each reset.
- v15 removes0300 from timeless bootstrap, writes supplied BEu32 time, and shares one monotonic24Hz clock across server connections. **NEW DESIGN:** process-local epoch, not proven original calendar or restart-persistent time.
- Ten clock/wire regression assertions failed before the fix. Final suite **88 passed, zero skipped** after removing a separate mistaken test: HUD+252 divisor belongs to the static ship TotalPower, not the charge table. Existing correct030B TotalPower test remains. This test correction changed no deployed production code.
- LIVE v15:275 read-only samples over70.1s; **6 new time responses, zero observed backwards samples**, tick11076→12752. Client anchor ticks11081/11383/11684/11985/12287/12587 exactly match server metadata. Connection3 remains SessionServerReady; sync interval approximately12.5s. Sample SHA256 `6A61C840285DF3AE795DF9FF608134F111C8E1158CFC056770BC4905A01425E1`; wire `F66F7E3EE0777D498069688E1F7185CD7D201AA85CE7F16B535B3A0387AD1AA6`.
- Final screenshot `clock-verified-tactical-screen.png` SHA256 `BABAD27A05C0E656D00ED14B0089150589E2924310F7BCB2EAE1A2CF5CFEF5B1` shows the distinct tactical HUD. It is not evidence of timed firing or battle completion.

### Current live target and next-start boundary

Active run **20260904T234900Z-natural-l1-relogin-v1** in the same oracle-win11-hd-re VM:
guest client **PID3248/HWND0x00000000037703FC**, authority **PID7068**. Original item116 hash unchanged:
`AEF3827602CD13A395618BDEF0F48F44BCB8ED60F4FA9C2017F2D2E128660F2F`.
v15 ZIP `E:/logh7-build/logh7-server-v15-clock24.zip`, SHA256
`D1BC50098A3C8AEC59251610B44E55A126CEC8007CCECAD272846CA20F7554C5`; DLL
`E4D89EF63D7F431BE811B1E6F74D4511088954FC4C83CAD2E318F3AFDAF559B5`.
v14 client/server/PostgreSQL copy stopped cleanly; no files deleted. No battle input has occurred in v15;
friendly is not selected, baseline beam20/sensor10. Always refresh same-run guest identity and screen before input.

Next: trace033F FillBeam/FillGun through normalized entities and the HUD/update consumers, then implement
single-owner authoritative timed combat and stop cancellation without concurrent sequence/socket writers.
004C5300 copies0309 power data to world+2BFE64; direct usage found so far is004C1700 shield recovery-time
lookup, not established beam/gun cadence. Audit its template+286 index range0..8 against authored Shield100,
and six-bit BeamAngle against authored180. Do not hide invalid stats by setting all table cells to100.
Actual firing timing, command time/wait normalization, shot effects, destruction,035A result fields and return
remain unverified or absent. All strategy/WARP/cruising, flagship assignment, celestial/map joins and complete
2,295-resource tracing remain required; a stable clock or one tactical screen does not complete the goal.

## 2026-09-05 — v17 valid weapon masks, no false campaign ending, native destruction verified

Details and hashes: `work/20260904-warp-state-reverse/evidence/E-008-weapon-masks-and-ending-boundary.md`.

- Original00411A60 logs six direction bits: beam/gun/missile angle bytes are masks, not degrees. Corrected authored180(B4) to its lower six bits52(34), renamed fields and reject high bits. Shield100 selected invalid column9 in004C1700; authored90 now selects valid8, capacity100 unchanged. These remain authored values, not recovered named-ship balance.
- Original035A/00433250 carries character/session-ending data (id, power, camp, state, age, begin_session_age, flagship, evaluations and session statistics); it is NOT a tactical victory summary. Removed the fake035A after terminal casualties. Damage updates remain0426/0325/033B. Two red/green regression cycles; final95 tests pass, zero skipped.
- LIVEv17: normalized Shield90/Capacity100/BeamMask34 verified. Four separately requested volley-kind1 commands caused native enemy normal75/50/25/0 and remaining100/100/100/0, morale100; friendly unchanged. Read-only sample captured0/0 -> destroyArmed1 -> removal from the active entity registry about1.52s later. `terminal-hit-lifecycle-sample.json` SHA256 `4E0FAB940E3843DAA35E9A864EBB38F43BEEDC0FAEE41486679385C8C2833DB8`.
- Exactly four accepted commands, three damage pushes each, connection3 remainsSessionServerReady. `terminal-full-wire.json` SHA256 `FD12A1D500D78D8444D3FB99FEEB246B97C015557E2D275E8CE7BAA1999FEFBA`. No campaign-ending screen. `post-terminal-screen.png` SHA256 `9B4D0CE2D6AE7AE8A117C71661014FA14C491642687EF7A2380FC5A2F8C3949B` still shows TACTICAL HUD. Native removal is proven; a visible beam/enemy-model disappearance is not.

### Current live target and next-start boundary (supersedes v15)

Run **20260905T002630Z-natural-l1-relogin-v1**, guest client **PID6648/HWND0x0000000003920454**, authority **PID7132**, same oracle-win11-hd-re VM and item116 hash. ZIP `E:/logh7-build/logh7-server-v17-ending-boundary.zip` SHA256 `18DF4DB12DE24E504A04909119F3A8EA1FA2D109E2AD979A20CA5CA7A199B1AD`; DLL `E01AB93BADBE121FB3C0A30412D871B1CED2F0F162445D33A06E2C40B135B9AF`. v15 stopped cleanly; v16 prepared but stopped before credential/world entry, not live-validated. No files deleted.

Enemy0x7F000001 is destroyed and absent from native active entities; friendly2 remains selected at100/100/100. **Do not attack the removed enemy again.** Next: proper tactical completion and strategy return. Original manual requires no enemies AND occupation of planet/fortress objectives when present. Original0F1F receiver004C1B20 maps first byte1 to transition2, otherwise0 and initiates world reload, but server scene/grid/objective state must agree. Do not call sending0 alone a complete encounter.

Timed continuous fire remains absent: these were four inputs and the25-per-command/four-hit balance is an authored placeholder. FillBeamGun definition00424200 exists but0343 consumption is unproven. Full command/WARP/cruising, flagship assignment, celestial/map joins and2,295-resource tracing remain required.

## 2026-09-05 — v18 NotifyTactics wire and grid corrected, live verified

Details: `work/20260904-warp-state-reverse/evidence/E-009-notify-tactics-wire-and-return.md`.

- **Correction to prior reverse claims:**0F1F is compact **state:u8 + grid:u32**, not two u32s or a battle id. Binary input0048CB80 reads one raw byte through00610420, then network-orderu32; logger0048CC40 explicitly names state/grid. Dispatcher copy is an eight-byte padded object, not the wire layout. Removed the misleading two-u32 fixture and unused TacticalBattleId constant.
- LIVE pre-fixv17: notification decoded **state0/grid256**, SHA256 `A6F56B90EA64D5BC4810E98D67F5ED94CF45F417B6535C3DA42DC5089199D39B`. Source now sends state1/current grid101. Three exact-wire regressions failed before correction; full suite **97 passed, zero skipped**.
- LIVEv18 natural login/entry: native **state1/grid101**, expanded0100000065000000, both units100/100/100. Receipt `tactics-wire-native.json` SHA256 `2F901F98E16F02C73805DF146D3725B7BD8E8F20404D9E0E056D85E5649D1730`. Original tactical HUD visible, screenshot `tactics-wire-screen.png` SHA256 `16E75D0DDFDB3A42D38AC2095A473DE293263653407F564B66BA8544460D17E8`; connection3 retained/no invalid or rejection. NO TABLE/NO DATA labels remain.

### Current target (supersedes v17) and next executable return boundary

Active **20260905T005710Z-natural-l1-relogin-v1**, client **PID2336/HWND0x0000000002AA03F6**, authority **PID9464**, same oracle-win11-hd-re VM/item116 hash. v18 ZIP `E:/logh7-build/logh7-server-v18-tactics-wire.zip` SHA256 `33D15C950BA5F29BA73BABBFAD934CD61D1F0579F0D671D9BA58B443116F4BBC`; DLL `16AE9982632522BC254ECF6ED0204C1E00C1F66733A0E338D66268DC15CDBB11`. v17 stopped cleanly without deleting any data. **No combat input yet in v18**, selection0, beam20/gun20/engine20/warp10/sensor10.

Next: implement objective-aware encounter completion plus matching world reload. Server currently always emits0317tacticsState1 (including generic0316 responses) and unconditionally projects the enemy on every refresh. Current Base1 objective ownership is absent; do not treat unknown ownership as captured or remove objectives to obtain a pass. Coordinate surviving0325/character/033B/033F projections, inactive0317, correct0F1Fstate0/grid, and a complete0F02 refresh. Original035A is campaign ending, not this path. Also+357E8C drives loading/fade presentation in004EBE80/004F4280: do not equate it alone with actual scene mode. Continuous firing, persistence, balance, flagship assignment, strategy/WARP/cruising and complete installed-resource tracing all remain open.

## 2026-09-05 — v19 original base ownership response and native join restored

Details: `work/20260904-warp-state-reverse/evidence/E-010-base-ownership-projection.md`.

- Original031F `InformationBase` supplies power/camp, consumed by004C32A0 when it imports0345 bases. Beforev19 the source had no031E handler and sent no031F. Livev18 had tacticalBaseCount1 but informationBaseCount0.
- Recovered full031F compact record from00414C70/004161F0:82 fixed bytes plus actual arrays (outfit/transport<=30, budgeting<=6, budget<=5, commodity<=3), not expanded0x180-byte stride.031E is countu8<=4 +u32 ids. Implemented full codec, requested-id filtering and capacity checks; bootstrap now sends031F before0345.17 regression assertions failed before their respective fixes; **114 protocol tests pass**.
- **Authored replacement data, not original geography:** catalog Base1/grid101 is explicitly power2(Empire),camp0; priceIndex1/supplies100/armor100, other economics0/empty. Common catalog owner does NOT change with caller faction. Missing BaseInformation remainsnull/unknown, not captured; actual base capture and persistent ownership changes remain absent.
- LIVEv19 native031F: id1/power2/camp0/grid101/supplies100/armor100. Correct separate base registry: id1/kind0/**power2/camp0**, staticIndex0. `base-owner-registry.json` SHA256 `DDE6932ACA6DE16863D6F178AB4781A5C39F6C7443AD343D4F7E477F7ED7529C`. This proves owner data -> normalized base, not visible planet/fortress geometry. Tactical HUD and connection3 remain active, no invalid/error/rejection.
- Probe correction: bases are **world+126718+174124**, ten slots of stride8CC (004C7EF0); ships are manager+4,600 slots of stride9EC. Earlier empty `baseEntities` from scanning ship slots was not evidence of base absence. Native base position is influenced by original orbital update; do not equate it to the server's spawn coordinate without tracing that update.

### Current target and next-start boundary (supersedes v18)

Active **20260905T011810Z-natural-l1-relogin-v1**, client **PID3964/HWND0x0000000003010438**, authority **PID2660**, same oracle-win11-hd-re VM/item116 hash. v19 ZIP `E:/logh7-build/logh7-server-v19-base-owner.zip` SHA256 `A8F15A3EF9DE61E892C6FD9B4B6395CCBDE0AF65DF7B836E36F41D3E6488390B`; DLL `7A8BEEB3641DFDD5BBBA3BE7596E52B24DD83183D11206FF9D63C576E54E9C00`. v18 stopped cleanly, no deletion. **No tactical input yet in v19**, two healthy ships, selection0, default power distribution.

Next executable unit: use canonical base ownership in original no-hostiles/all-objectives condition; implement completion state, surviving projections and correct0F1Fstate0/grid with inactive0317 and full0F02 transaction. Do not hardcode victory after four hits or treat unowned/enemy/unknown bases as captured. Stop defeated-enemy respawn in world/character/corps/periodic-position projections; prove actual strategy HUD and retained connection. Base capture and persistence then remain required for hostile-objective encounters. All continuous-fire/charge, WARP/cruising, other strategy/tactical commands, flagship model assignment, celestial joins and complete2,295-resource tracing requirements remain active.

## 2026-09-05 — v20 native tactical defeat to actual strategy HUD verified

Details and immutable hashes: `work/20260904-warp-state-reverse/evidence/E-011-tactical-completion-and-return.md`.

- Added session-owned encounter completion: no enemy survivors (template Number minus Destroyed) and known same-grid objectives matching power/camp. Null ownership data blocks completion; authored empty open-space data is distinct. Final defeated-unit damage frames precede once-only0317state0 /0F1Fstate0/grid101; subsequent unit/character/corps/position projections exclude the defeated enemy.0316 no longer reasserts active tactics after completion.
- Sixteen genuine red regressions before implementation; fresh full suite **130 passed, zero failed/skipped** (`encounter-final-130-green.trx`). The immediate notification timing,25-per-hit and common Base1 Empire ownership remain authored replacement choices, not recovered original timing/balance/geography.
- **LIVE bounded PASS:** four separate volley-kind1 commands reduced native normal75/50/25/0 and remaining100/100/100/0. Final command10:52:27.878328+09:00 recorded completed=True; client requested0F02 itself at10:52:33.6150522+09:00. Full refresh logged completed=True/enemy-present=False. No extra click, retry, memory write or fabricated035A.
- Actual original **STRATEGY HUD** returned: bottom character/strategy minimap panel replaces tactical controls. Separate native dump confirms tacticalRegistryActive0/strategyRegistryActive1, NotifyTactics0/grid101, InformationUnit list only player2. Connection3 retained and later time syncs succeed. Input-free check over6min later retained the same mode and sole-player list. Latest image `runs/20260905T014150Z-natural-l1-relogin-v1/post-return-stable-screen.png`, SHA256 `253386B4FB9618DE1CBADC75AC806173A7505E2E2362655B4EF37AF3C6D24831`.
- Evidence boundary:30s lifecycle sample captured notify0, terminal0/0, destroyArmed1 and enemy removal, but ended BEFORE strategy registry activation. Separate wire/state/screens prove final return; do not describe the sample as uninterrupted proof of the whole transition. Transient modal text remains unestablished with mismatched installed strings. Visible weapon effects and planet geometry are not proven by this result.

### Current target (supersedes v19) and remaining executable units

Active run **20260905T014150Z-natural-l1-relogin-v1**, guest client **PID2428/HWND0x00000000037D03DC**, authority **PID9444**, same oracle-win11-hd-re VM and item116 hash. v20 ZIP `E:/logh7-build/logh7-server-v20-tactical-return.zip`, SHA256 `5F8794E2A9CF9435E3D6D94A1D52936DF7AC9B2FF1F98E16312387D330B7D063`; DLL `69B499153D94A02D303901684C0D1CE5889706D8BBE7C54C7EBEBAE366953A67`. v19 client/server/PostgreSQL stopped cleanly without deletion.

**Current mode STRATEGY, selection0, enemy removed.** Refresh same-run guest identity/screen before input. Do not send a fifth attack, repeat the sealed four-command sequence, or restart solely to improve evidence. Completion currently lives only in this session: shared/persistent outcomes, reconnect survival, later re-entry and hostile-objective capture remain unimplemented. Next combat unit is original charge/cadence tracing and single-owner timed firing/cancellation; four deliberate attacks do NOT establish continuous combat. Full WARP/cruising and remaining strategy/tactical commands, named flagship assignment, celestial/orbit/map data joins and complete2,295-resource use-site tracing remain in scope. This bounded return PASS does not complete the playable-game goal.

## 2026-09-05 — charge definition versus real dispatch; authored cadence decision

Details: `work/20260904-warp-state-reverse/evidence/E-012-fill-dispatch-and-cadence-design-boundary.md`.

- **STATIC boundary now byte-verified:**0343 FillBeamGun has a parser/logger definition, but queue classifier004B8B00 selects default004B9D8C(returnAL0), and scene dispatcher004BA2B0 selects default004BDCEE. Both selector bytes are42. Neighbor033F/0341/0345 select real handlers. Eight branch/table regions match pristine client and item116. Reproduce with `scripts/verify-fill-dispatch.ps1`; no live0343 sent.
- Compact FillBeamGun record is unitu32/beam_next_timeu32/fill_beamu16/gun_next_timeu32/fill_gunu16,16bytes after countu16<=600; expanded stride20. A serializer definition is not proof of a gameplay consumer.
- Corps+9B0 copy includes fill fields+9CE/+9D0, but no proven downstream beam/gun timer. Fresh004B2740 is shield recovery/command progression/destruction, not a recovered beam/gun loop. Static beam/gun value tables cannot be reinterpreted as tick intervals without evidence. Do not repeat the same offset-only search or emit0343 hoping it starts continuous combat.
- Current server has only frame-driven command handling; one continuous input causes one immediate authored hit and no scheduled progression. Recommended implementation keeps one session/socket/cipher-sequence owner and one pending complete-frame read while timer wakeups advance charge/shots. Numeric charge/output policy must be explicitly authored/replaceable while original-rate research remains open, not called original balance.

**No new deployment or game input.** Latest target remains v20 from the preceding section; analysis filenames endingv21 are not serverv21. Exact live identity must be refreshed before input. Existing server/protocol approval remains; asking whether unrecovered cadence/power numeric rules may be authored is a distinct fidelity choice. Brainstorming skill requires this design approval before production timer implementation. Safe independent resource/data tracing remains possible, so do not mark the overall goal blocked. All original scope remains active.

## 2026-09-05 — actual Base model_file/orbit join, schema corrections, sun layers

Controlling evidence: `work/20260904-warp-state-reverse/evidence/E-013-base-model-orbit-and-sun-resource-joins.md`. This supersedes older model_file/class/radius/sun-boundary statements above.

- Exact031D logger00414A30 proves **id/grid/model_file/kind/name/class_/orbit/diameter**. The <=13 parser check is nameCount, not class_; the last float is diameter, not radius. Model_file is present in Base, contrary to the prior orphan discussion. GridType klass and Base kind/class are not interchangeable.
- Full Base join: raw031D model_file+6 ->004C4C50 ->normalized Base+248 at world+2EB800+index*250 ->native Base staticIndex+8BC ->004F3FF0 ->004F2920(mode1)/004F40A0 ->high00775108 and low00775310 model tables.260 slots extracted. Model0 is p000;110/111 bothy001;32 isNULL. Named map identity remains unknown.
- Orbit is server-supplied Base data, calculated client-side. kind!=1 enables orbit; initialAngle is degrees, cycle is original ticks, x=sin(phase)*orbitRadius,z=cos(phase)*orbitRadius. Render radius=diameter/2. Current authored cycle1 always gives tick%1=0, so it is static. v19 native position0.008726535/0/0.9999619 exactly matches radius1/initial0.5degree; it is not serverBaseSpawn(0,30,0).
- **LIVE input-free v20** at02:23:39Z confirms raw and normalized Base1 agree: grid101,model0,kind0,class1,orbit1/cycle1/direction0/initial0.5,diameter1. Receipt `static-base-model-orbit.json` SHA256 `AAF6AEF75C521C1F73FDB6424ED1AA8C916E5FA9FEFA7ADFEE4D75F542F1C349`. Client2428/authority9444 identity/listener verified; strategy registry1/tactics0. This is data-join proof, not a new visible planet.
- **Sun correction:**004E2000 is generic space background only; adjacent004E2190 is the selected sun-layer loader.0347 circle.model_file ->normalized obstacle+8DE ->004BE520/004F2820/004E1F70 ->004E2190 binds fs### body,fs_glow###,space/s###,light/l### together. Calling fs### universally fortress art was unsupported. Slots7..9 areNULL despite wrapper<10. Current circles are empty; no live sun proof.
- New sidecar `base-model-use-ledger-v22.json` binds exact selectors/loaders and verifies **85 present files /7 absent paths** against2295-file inventory. Missing:light/l000 plusy006..008 base/low.34 residual Planet rows remain unresolved; do not label middle LODs/p032/ds globally unused just because this route lacks them. Existing full graph is preserved, not falsely promoted.

No server deploy or game input in this unit. Analysisv22 names are not a runtime version; **v20 remains current**. Next: typed031D field correction and strategy orbit-view Base draw/list gate, then data-driven grid/Base/model/orbit/obstacle catalog and missing-asset recovery. Do not merely enlarge the authored body or change class to claim the visibility issue fixed. Timed-fire numeric-rule approval remains pending; full strategy/WARP/cruising, other commands, combat/persistence, flagship and all-resource requirements remain active.

## 2026-09-05 — lowest-button live proof; strategic preview is fixed, not Base data

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-014-strategy-orbit-preview-and-render-boundary.md.

-One guarded click of the actual lowest minimap-right button(618,722) changed strategy mode1->4 and selected grid1/1.004D68D0 changed the first four model wrappers from hidden1 to0. Screen strategy-orbit-after.png shows four rings, but no identifiable planet bodies. **Planet visibility remains UNVERIFIED.**
-The strategy preview is hardcoded:004D3BD0 preloads p000/p010/.../p070_low.mdx;004D68D0 shows the first four at fixed scale0.00625 and offsets/radii0.5/0.75/1/1.25. It does not read031D/031F/0345 Base data. Therefore E013's tactical Base orbit/diameter diagnosis must not be used as the explanation for missing planets in this strategic preview. Actual tactical Base and sun data paths remain distinct and data-driven.
-Each wrapper has a nonzero node and one mesh. The first four remain hidden0/category10, mesh+18=0/flags0. This rules out absent wrappers/empty meshes, not all resource or renderer faults.
-Strategy renderer004D8280 draws categories11/12/13, but common renderer004E8540 DOES invoke category10 before the strategy pass. Its gate0076E1C5 is live1. Reject the tempting but unsupported claim that category10 is globally missing/disabled. Next boundary is model-container membership, node transforms, camera/projection, submission/culling/material and overlay order; actual draws have not been instrumented.
-Fresh receipts: strategy-orbit-draw-pass-flag.json SHA2569FCB2A2F8F05F46B2AF179E19DC7EB2C59725AC142774A4E9F9851E9F8048D35; strategy-orbit-mesh-state.json SHA256E21BF70614F9884E6C38089117CA851EDC4D2F1627EE275AE15A045D349CC2E2. See E014 for click/state/screenshot hashes. Final read-only probe hash0BF90E8BFCE88B4617FC78477E6C39E0DED01EB8A235ACAD8887A614CDAD6335.

**Current target remains v20 run20260905T014150Z-natural-l1-relogin-v1, client2428/HWND0x00000000037D03DC, authority9444, STRATEGY orbit mode4/grid101.** One UI click, no attack/relaunch/server deploy/client-memory write in this unit. Do not repeat the click or re-run the sealed battle sequence. Analysisv23 is not a deployment. Full resource coverage, gameplay scope and pending authored-cadence fidelity choice are unchanged; no complete/blocked promotion.

## 2026-09-05 — preview render membership and five-pixel scale

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-015-preview-model-membership-and-projected-size.md.

- Fresh input-free renderer traversal found all8 preview wrappers in26 containers/14 total wrappers. The first4 have renderPrepared1, clipState1/flags0, one geometry group and nonzero draw buffers; local and world matrices match with nodeDirty0. Thus absent list membership or stale world transforms are no longer the next hypothesis. These are snapshot prerequisites/preparation state, not GPU draw-call capture.
- The mesh-definition bounding boxes are[-5,5] on each axis. Original scale0.00625 gives world diameter0.0625. Recorded view/projection/clip matrices are mutually consistent(max error2.55e-6).
- Offline1024x768 projection produces centers(553.210,388.120),(573.816,388.120),(594.422,388.120),(615.028,388.120), with bounding widths5.420/5.555/5.689/5.824px. This is tiny and overlaps the second blue marker/glow area in the prior screenshot. **Overlap is a candidate explanation, not verified final occlusion or planet visibility.**
- Reproduce with scripts/project-strategy-preview-bounds.ps1 against strategy-orbit-projection-inputs.json(SHA2568A8CC8983905EE4C74EFD20D26E3BD25E2709AF4AB41DC4E6C317571DF368FFC). Membership receipt SHA256F8F798FC301348D34F518A8452ACD22F8ED4331F5D125F700A2F8632F8EB188F. Both are in the samev20 run directory.

No input, memory write or deployment in this unit. Current target remainsv20 strategy mode4/grid101. Next discriminator: final mesh submission/material and marker draw ordering, or a separately guarded natural camera comparison. Do not change Base fields or inflate client models as an unexplained fix. Full goal scope and pending authored-cadence fidelity decision remain unchanged.

## 2026-09-05 — one natural zoom reveals all four preview planets

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-016-natural-zoom-shows-four-preview-planets.md.

**Bounded PLAYER_VISIBLE_PASS:** the existing four fixed strategic preview planets visibly render after one plus-button zoom. This supersedes E014/E015's inability to identify their pixels, not their finding that the preview is independent of actual Base data.

- Guarded one-click input at341,645 on the minimap-left plus; no repeat. The200ms image was too early. Later state changed view matrix, retained projection/mode4, and kept every recorded model/static Base object unchanged.
- Settled screenshot shows four small bodies near expected centers594/636/677/718 at y392, with approximately2px capture-origin offset. Calculated widths increased5.42–5.82px ->11.43–13.11px. Three bodies visibly overlap the blue marker yet remain distinguishable; do not retain complete occlusion/missing render-pass as established causes.
- Screenshot strategy-zoom-settled.png SHA256285921EAC8A93883382F88CEAD98C6B5BF0A3E23969CC90C8C627195B45DEEFD; after-state SHA256139AF884687D158900B77364F96D27B8B4BE5FB3BA64FCCFA227C0D5D0F60726; click SHA2561CA90D7FB353D03F1AA3E33625E3464CDB611A3E787635AFD594F66FFAE6852D. Files remain under the samev20 run.
- Preliminary hover/focus did not yield a fresh tooltip; later settled screenshot confirms the strategy-map zoom tooltip. Do not infer click success from the helper alone. Original-client state plus settled pixels establish this bounded result.

**Current target unchanged: v20 client2428/HWND0x00000000037D03DC, authority9444; strategy mode4/grid101, one-step zoom-in retained.** No model/server value changes, memory patch, battle restart or deploy. Do not repeat the input. Next: recovered031D typed data/catalog integration and actual Base/tactical content, not more attempts to prove the now-visible fixed preview. All-command/WARP/cruising/combat/persistence/flagship/full-resource goal remains incomplete.

## 2026-09-05 — typed031D Base catalog implemented locally; tactical ID join next

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-017-static-base-catalog-to-wire.md.

- Added OriginalStaticBaseRecord and global catalog.staticBases. Names/model_file/kind/class/orbit/diameter now reach031D through the existing bootstrap codec and NaturalAuthoritySession call site. Explicit[] emits no definitions; missing/null uses the preserved legacy fallback. Each definition has provenance.
- Shipped JSON explicitly declares the existing Base1/grid101 values as NEW_DESIGN and reproduces their exact old default bytes. No new map names, balance, planet variants or orbit numbers were invented. BaseDiameter replaces the misleading BaseRadius name; class<=13 and Base/GridType-class equivalence comments corrected.
- TDD:9 catalog rejection failures reproduced, then green;3 wire-substitution failures reproduced, plus shipped-data failure. Final protocol project **145/145 pass,0 skipped** including15 new tests. TRX files: E:/logh7-build/test-results/static-base-v26/. Test custom model110/kind1/class200/diameter8 is only an offline layout fixture, not deployed content or recovered game balance.
- **Not deployed; v20 remains current.** No live client input/relaunch in this unit. Explicit catalog records supersede legacy environment probes; probe overrides survive only when no definitions are supplied.
- Still hardcoded: NaturalAuthoritySession0344/0345 and entry/base-position/target paths use BaseId1/template.BaseSpawn. Next join must align031D IDs,031F ownership,0345 instances and actual-grid target/position lists. Do not deploy arbitrary multi-Base definitions yet or report full map integration.

Full gameplay/resource objective remains incomplete. Existing safe native preview result stands; do not repeat its clicks. Numeric authored-cadence approval remains a separate pending choice.

## 2026-09-05 — tactical Base ID/grid projections and fail-closed objectives

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-018-tactical-base-id-grid-joins.md.

- Added template.tacticalBases and validated instance/ownership IDs against global031D definitions. Queries now filter by actual grid and requested IDs; no phantomBase1 on empty/other grids, no duplicate request rows. Shipped instance retains prior Base1 coordinates/attributes.
-0344->0345, Base-position queries and tactical startup now share these projections. Startup ownership/position frames split at4 records; tactical count limit16 per grid enforced. Stop Base-ID context validation uses actual-grid instance IDs.
- Completion now receives ProjectBaseObjectives: missing ownership for a known static Base remains unknown and cannot pass victory. This fixes the previous empty-ownership false-completion case.
- TDD reproduced3 validation failures and5 projection failures, then8 including bounds/default cases. Final **156/156 protocol tests pass**,11 new join tests. Results E:/logh7-build/test-results/base-join-v27/. This is local code/codec/encounter evidence, not a new live session.
- **No deployment or client input; v20 remains active.** WARP base argument and unit Base association still hardcodeBase1. OriginalGridUnitRecord has only grid persistence, not Base association; do not choose the first available Base as a substitute. Recover/represent the actual association before multi-Base deployment.
- Coordinates in0345/position responses remain authored instance coordinates; original client kind-dependent orbital updates are separate. No server arbitrary-tick orbit parity or native multi-chunk accumulation was proven.

Full objective remains open. Next start: unit Base association and WARP/undocking transitions, then a bounded fresh native catalog validation after local joins are complete. Preserve existing preview/run evidence and pending authored-cadence choice.

## 2026-09-05 — Unit Base persistence and strategic-WARP projection consistency

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-019-unit-base-association-and-migration.md.

- Fixed local contradiction: permitted strategic101->102 WARP next state,0B07 notification and subsequent0325 now agree onBase0. Unit records carryBaseId; session restore/move/tactical-WARP/unit frames use it instead ofBase1. Explicit wire association0/7/u32-max is preserved. Tactical docking/undocking behavior itself was not invented.
- Added0017 original_grid_unit.base_id and movement-history destination_base_id. Npgsql queries/reader/update/history replay carry the fields. Compatibility backfill/seed preserves authored grid101/Base1 and moved grid102/Base0, explicitly NEW_DESIGN, not original docking history.
- TDD reproduced5 failures, then **162/162 protocol tests pass**. E:/logh7-build/test-results/unit-base-v28/. These tests do not exercise the full Npgsql transaction.
- **Actual PostgreSQL17.11 SQL test PASS** on a new isolated cluster/port55433: actual0011+0017 applied, backfill/seed/u32 constraints/committed Base7/reconnect/history independence verified. Game DB55432 untouched. Receipt unit-base-existing-cluster-sql-test.json SHA256D4EBA134D08E5B8509D97243AEC20C7EC2F2AD708DEF52EFF9015D409A0F4A24.
- Test runner initially hung after pg_ctl startup. Read-only inspection identified runner540 and ready test server10140. Only owned runner540 was stopped; SQL continued on the same cluster with hidden Start-Process, then test server stopped. Original failed step preserved. Shutdown independently confirms no55433 listener/no stalled runner. Test files preserved.
- Fresh input-free game check after test: client2428/authority9444 remain valid, strategy1/tactical0/mode4. **v20 still deployed; new server not launched.**

**Next step at E019 (now updated by E020):** actual PostgresAccountStore read/move/replay/rollback on isolated full schema, then controlled native validation. **Correction:** NaturalAuthorityServerCommand.RunAsync DOES automatically apply0017 via the migration runner before listening; the earlier contrary statement was an incomplete call-chain inspection. Native Base/catalog/WARP/cruising and the full goal remain incomplete.

## 2026-09-05 — actual full-schema store PASS; stagedv21 ready for native validation

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-020-real-store-transaction-and-v21-package.md.

- Ran the real compiled PostgresAccountStore and production migration runner against a new disposable DB with all17 migrations.16 checks passed, including fresh connection restore, moveBase7->0, atomic versions/history/event, historical replay despite currentBase9, stale/conflicting/cross-account rejection, and rollback after an injected late domain-event failure. No mocks or substituted SQL for the tested store methods.
- Result unit-base-store-probe-result.json SHA256C78A270B2D91613656AD61947CB99FC89D44704C6552C31061C99029367E1B55. Driver SHA256438C1150A1A5F3BECAE1D12F89AF71AC8756A26A11F458440CC917DB22DEF49E. New test DB retained; test55433 server stopped and independently verified absent. Live game DB55432 untouched.
- Protocol regression rerun162/162 pass. All17 executed migration hashes match current source.
- **Auto-migration correction:** Hosting/NaturalAuthorityServerCommand.cs:31 calls ApplyAllAsync before listener startup. Do not manually apply0017 outside its migration history. Allow controlled clone startup to apply it and verify the result.
- **Staged, NOT deployed v21 ZIP:** E:/logh7-build/logh7-server-v21-base-catalog-association.zip, SHA256B6957F55E81C9F9B964AE4F47174F3C955EA51CD4E1DE29C06DF55578284B535. Server DLL SHA256C3984534205193152E32EA05333AE836D8AD1E395B4C46094B3421C0B06FF91D exactly matches the one executed by the successful store probe.

**Current game remainsv20/client2428/HWND0x00000000037D03DC/authority9444/gamePG2700**, run20260905T014150Z-natural-l1-relogin-v1, strategy mode4/grid101/one-step zoom retained. Fresh identity/listener/memory read confirms strategy1/mode4. No gameplay input or game restart this unit.

Next: owned clean shutdown and consistent DB copy into a newv21 native-validation run; verify automatic0017/startup and original031D/WARP101->102/0325 Base+cruising/persistence agreement. Do not repeat sealed four-attack inputs or claim the staged ZIP is running. Full strategy/other commands/tactics/maps/flagships/resources objective remains incomplete.

## 2026-09-05 — v21 deployed; actual tactical entry and flagship sheet; retreat remains

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-021-v21-native-entry-and-retreat-boundary.md.

- Oldv20/client2428/authority9444/PG2700 were identity-checked and cleanly stopped. Source DB/files retained, no deletion. Source pg_control09E7D984C89E56FEECDB223488F36F48E678C157D067A2C8F3803EA3C4200B9F bound a new copiedDB.
- **Current run20260905T041218Z-natural-l1-relogin-v1, v21/client3644/HWND0x00000000019403C2/authority388.** ZIP B6957F55E81C9F9B964AE4F47174F3C955EA51CD4E1DE29C06DF55578284B535, DLL C3984534205193152E32EA05333AE836D8AD1E395B4C46094B3421C0B06FF91D. Same item116client; no new patch.
- Startup through automatic migration path and real stored-unit restore succeeded. No manual0017 application. SQL migration-history rows were not independently dumped; E020 remains the separate full-schema/hash proof.
- One native login/existing-character selection reached **actual tactical HUD**, memory tactical1/strategy0. Static/normalized Base1/grid101/model0 and nativeBase1/staticIndex0 match. Player InformationUnit2 hasgrid101/Base1/cruising10.0.83 processed frames,0 rejects in initial89-line snapshot. This is native entry/catalog evidence, not WARP or full-map PASS.
- Actual information icon752,744 -> thirdrow 旗艦情報795,615 opened flagship sheet. Both units still model0; blank shipname, NO TABLE labels and faction/content inconsistencies remain. Screenshot flagship-information.png SHA97A6DCC9C279EB748C54FC68C90A6910FE45415E836C5DB8E11FD7FC30F8F345. **Sheet remains open.**
- No attack, warp, power change, zoom or celestial-view toggle in newrun. Old four-volley sequence not repeated. v20 victory was session-local; new login starts another tactical encounter, so strategic WARP verification is not reached.

**Next:** trace natural retreat before strategyWARP. Manual says maximum WARP allocation plus outside tactical circle. Current allocation10/warpEnabled0; ProcessTacticalWarpAsync sends0425/refresh but does not itself transition encounter/grid/strategy reload. No native retreat attempted yet; do not invent radius or forge0B01. Recover eligibility and0425 consumer -> implement authorized lifecycle -> natural101->102/Base0/cruising9/0325/relogin proof. Recheck currentv21 identities and screen before input. Full gameplay/resource goal remains open; original named flagship assignment/cadence choice unchanged.

## 2026-09-05 — original retreat sender/timing and radar resource trace

Controlling E022/report: docs/2026-09-05_reverse-retreat-report.md, work/20260904-warp-state-reverse/evidence/E-022-retreat-and-radar-boundaries.md.

- Original004C187E comparesWARP allocation against50 and setsentity+94C0/1. Native50boundaryinputnotyettested.
- Manualp52(2521–2538) specifies5s wait,150s preparation, outside radar circle; othercommandsremainavailableduringprep; participatingfleet/range capturedatstart; planetgroundtroopsremain. Do notcallinstant0425retreatcorrect.
- **Actual0404sender004B4500 usesselector0x55.**004B78A0 decrementsselector; previouslyinspected0x54/004B3B20float-pairpathis0403reverse-turn,notawarpcodecdefect. Existingcount*4warpIDsremaincorrect. SenderleavesTime/Waituninitialized;servermustnotadoptcommand.Timeasnotificationdeadline.
-0425→004C1990 deactivatescharacterthrough004C2C60/004C8440 andsetsentity+5B8/+5B9=1;004C94E0immediateremovalbranch,nodestructionanimationwait. **Own-characterinactive-to-sceneaftermathremainsunknown**;E021servermethod'sabsenceofexplicitreloadisnotproofthatclientcannottransition. Do notunconditionallyadd0F1Fwithouttracing.
- User-requestedresourcebacktrace:actualinstalledimage/Rader/Rader_parts.tgaSHA4429BA4B7B0F1C33B763AC893EDAC56EED7EC8E571B23A8C8F3584B67B52DEB4→007744B8→004EDE60→owner+A730/A734→004EC700. Hardcodedprojection±250to±95px,center118/125;circlebakedinatlas.±250isnotretreatradius;~75pxringonlysuggests~200worldunits,notexactoriginalserverrule. ThisisfixedUIgeometry,notevidencethatserverBase/obstaclelayoutishardcoded.326unresolvedresourcesretained.
-7coderegions/5constantsbyte-identicalbetweenpristineBD192...anditem116AEF382...;verifierretreat-byte-verification-v30.jsonSHA377C3502F44BEB4E4E6FF78627E24DEC67DA5A5F86DEB91EEC3221DD6E24F0FC.Reproducewithscripts/verify-retreat-boundaries.pyusinganewoutputpath.

**Currentv21unchanged:client3644/HWND0x00000000019403C2/authority388,run20260905T041218Z-natural-l1-relogin-v1,flagshipsheetopen.**Freshread-onlybaselinehashD611AB8884DAD7CBBF764D13093C1F97461023DA2E1BE26DCBDBC30B89A9DE1C.No gameinput,deploymentorDBchange. Next0404ack/preparation andown-character0425sceneaftermath,thenproperretreatlifecycle andnaturalstrategyWARP/persistence. Entiregoalactive.

## 2026-09-05 — E023: 워프 완료 통지의 서버 시각 수정, 로컬165 tests PASS

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-023-warp-authority-clock.md.

- `OriginalTacticalCommandAuthority.AuthorizeWarp`는 미초기화 request Time/Wait 대신 authorityTick을 사용한다. `NaturalAuthoritySession.ProcessTacticalWarpAsync`에서 `_gameClock.Tick`을 전달한다. 기본0 sentinel은 시계 없는 호출용이며 원본 준비 시간이0이라는 주장이 아니다.
- 요청 쓰레기 시간4045620583 반사 실패 → authority tick321/golden frame 실패 → 실제 세션 expected24/actual0 실패를 각각 확인했다. 마지막 수정 후 **165/165 프로토콜 테스트 통과,0 skipped**. 여섯 TRX는 case evidence/retreat-v31-tests/에 보존. 최종 TRX SHA256 ACBDD38078642A90DD99F5D2F6BB228D979455C096C4722365997B50309F2620.
- 세션 테스트는 인증·월드 진입 완료 fixture에서 실제 암호문 복호화/dispatch/완료 응답을 검증한다. 로그인·PostgreSQL·native gameplay 증거는 아니다. 초기 fixture의 필수 인터페이스 메서드 누락 컴파일 오류는 RED로 세지 않았다.
- 추가 Ghidra 추적:0425 direct dispatch→004C1990→공통 끝,004E96F0 world-header 검사,004C9640 렌더 객체 정리 확인. world header와 개별 character active는 다르다. **자기 함선 철수 뒤 scene 전환은 여전히 UNKNOWN**이며 간접 전환 부재도 입증하지 않았다.
- **로컬 수정만 완료. v22 미생성/미배포; native 입력0/게임DB 변경0.** 준비 시간 예약·중복 완료 방지·목적지·영속 철수 수명주기는 아직 구현하지 않았다. 기존 즉시0425를 완전한 철수로 보고하지 않는다.

마지막 확인 기준은 v21/run20260905T041218Z-natural-l1-relogin-v1/client3644/HWND0x00000000019403C2/authority388, 기함 정보창이다. E023에서는 이를 새로 조회하지 않았으므로 다음 입력 전에 동일성/리스너/화면을 재검증한다. 정확한 원본 서버 철수 반경·도착 그리드 규칙은 미확정; 교체 가능한 NEW_DESIGN 허용 질문은 미응답이다. 반경 약200을 원본 상수로 확정하거나 무조건0F1F로 전략 화면을 만들지 않는다. 다음은0404 준비 상태/자기0425 이후 실제 scene 경로 및 철수 수명주기. 전97전략명령·31전술명령·29통지, 기함/맵, 전체2295리소스 사용처/326미해결 범위를 유지한다. 전체 goal은 active이며 완료하지 않는다.

## 2026-09-05 — E024:0404 준비 수신부터 함선 상태 막대까지 연결

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-024-warp-ack-countdown-and-dispatch.md.

- 기존 서버가 생략한 단계 확인:0404 receipt metadata(004B90B4)→004BFC40(flag1)→각 entity+5BC/+5C0=(Time+Wait)-clientTick.004B2740은5BC를 프레임 delta만큼 감소/0 clamp하고,004F0260은5BC/5C0을004F0B90 상태 막대에 전달한다. 실제 native 준비 막대는 아직UNSEEN.
- **명령별 queue 의미가 다름:**0404는Time에dispatch,0405/0406은Time+Wait에dispatch.0404 실제dispatch(004BB86A)는004BFC40(flag0)으로 초기화를 하지 않는다. 공용 receiver라고 공통 예약 규칙을 만들지 않는다.
- pristine/item116 실제hash 재확인, 다섯 범위 독립 disassembler 출력 일치. artifact retreat-ack-pristine-item116-disassembly-v32.txt SHA2560E772D90AFCE2F0A0F4E90163F6020D30AD129CB0591683A30150EDC29A74617. 이는 decoded instruction 비교이지 raw byte 전체 동일성 검사는 아니다.
- 004C8440은활성character lookup; 비활성 쓰기는004C2C60이다.004BE600의 frame처리와그리기manager,004C1B20 직접xref 확인. **자기 함선 철수 뒤scene 전환은 여전히UNKNOWN.**0425 actor0과자기 actor2 callback을 혼동하지 않는다.
- 매뉴얼5초/150초는확인했으나Time/Wait 숫자 투영은미확정. Time=current+120/Wait3600은가능한NEW_DESIGN 예일뿐 원본서버값아님. 반경·목적지 NEW_DESIGN 허용질문도미응답.
- 이번단위는정적진전:production code0/runtime change0/input0/deploy0/DB change0.165 tests는E023의지난실행결과이며새테스트실행아님. 마지막v21run20260905T041218Z-natural-l1-relogin-v1/client3644/authority388을이번에새로조회하지않았으므로다음입력전검증필수.

다음은0404 수신/대기표시→authoritative 준비→조건부0425와survivor/destination/scene 일관성. 즉시0425 뒤모든함선refresh를완전철수로보지않는다. 전체명령·워프후항속/상태·전술전투·기함·실제맵·2295리소스사용처/326미해결목표유지; goal active.

## 2026-09-05 — E025: 전략 워프 모델과20개 소리 파일 등록 경로

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-025-warp-model-and-sound-table.md; sound-static-use-ledger-v33.json.

- 실제전략효과loader는06.mdx(007720B8→004D473E→004D1F30→handle009D2FAC).004D6B70의scene owner+4C=1/+3C비0일때wrapper표시/위치/animation처리하고후속fade단계를갱신한다. test_warp.mdx는명시적사용처미해결상태유지;파일이름으로대체하지않는다. native새픽셀검증없음.
-20음원의직접stringxref0문제는간접테이블로해결:descriptor0076E190(+18 filenameBase0076CD90,+1C count20)→soundmanager006178D0/vtable00682034+18→00617B50의256-byte filename슬롯등록. category table0076CD30,2개,stride0x30.
-BGM choice0..6은category0의단일entry0076CD00 fileindex를변경한다.004B0150은SEcategory1의13entry에fileindex7..19를설정한다.004AFF50/004B0000 packedID→00618320으로group/entry/file을연결. **SE4→file11→warp1.wav**확인,실제warp event발동은미확인.
-전술effect004E64A0의d8값1→SE7,2→SE8,3/12→SE9,4/5/8→SE10;004E6540→SE11.서버무기enum과d8동일성/실제소리재생은아직확인하지않았다.
-20원본파일해시재검증PASS. ledgerSHA2562833D44AD4A86CFB4574556DFB49FFB6C3EC26280D24CD78DB30BBD6B12865FB. 모든playbackStatus는UNSEEN. **326미해결queue는다른분류(DATA_DRIVEN_OR_UNRESOLVED)만포함하므로20을빼지않는다.** 기존graph는보존하고별도semantic ledger추가.
-이번은리소스분석진전. production code0/runtime0/input0/deploy0/DBchange0. 마지막v21/run20260905T041218Z-natural-l1-relogin-v1/client3644/authority388은이번에새로조회하지않음. 기함배정·철수반경/목적지NEW_DESIGN 허용질문미응답유지.

다음은개별음원발동/전술effect생성상위호출과전략워프06.mdx/fade/항속통지의자연스러운검증. 원본확인과새규칙승인을구분하며전체명령/전술/맵/기함/2295리소스사용처목표를계속유지한다.

## 2026-09-05 — E026: 관찰자마다 바뀌던 NPC 진영 수정,176 tests PASS

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-026-battlefield-npc-power-consistency.md.

- 재로그인시적이살아나는구조를조사하다동일NPCid의Power를접속자반대진영으로매번계산하는모순확인. **전투결과DB저장은아직구현하지않았고,이번에는선행NPC진영일관성만수정했다.**
- OriginalBattlefieldTemplate.EnemyPower를catalog필수값(0..4)으로추가. 기본NEW_DESIGN catalog는3을명시해기존v21/제국플레이어의NPC값을유지. 동맹플레이어에게도동일NPC3을보내며플레이어자신의Power는바꾸지않는다. 원본NPC배정표회수아님.
- NaturalAuthoritySession은선택적battlefieldCatalog생성자인자를지원하고ActiveBattlefieldCatalog를통해NPC/Base정의·좌표/소유권/Stop대상/목표를일관되게조회한다. 미지정시기존defaultloader. DLL과catalog는함께배포해야하며enemyPower없는구schema1파일은이제거부된다.
- 실제암호화세션0322→0323 테스트에서viewer3expected3/actual2실패확인후수정. null/5/255거부실패,configured0/1/2/4를무시하고3을보내는실패도확인. **최종176/176 protocoltestsPASS,0skipped**. finalTRXSHA256004B9EA9E01B01BC407DA74F95CB21A2656304BE6A53D22CFE28EC43858B74AC, case evidence/encounter-v34-tests/.
- Nullable컴파일오류와InlineData nullable-byte바인딩오류는기능RED로세지않고원래실패영수증보존. 기존clock회귀테스트도통과.
- **미배포/패키지미생성/게임입력0/게임DB변경0.** 마지막v21/run20260905T041218Z-natural-l1-relogin-v1/client3644/authority388은이번에새로조회하지않음. 재로그인복원PASS아님.

다음필수:NPC Face/AbilityValues/Rank등아직source복사인부분을world-ownedidentity와분리;실제grid/template/NPC공유전투키,동시수정/rollback,피해+완료원자저장,중복재전송vs정당한동일payload공격구분후store/migration/relogin구현. 동맹관찰자에게NPC3은동맹소속이므로친선공격/적배치/승리까지양진영완성으로해석하지않는다. 기함배정·철수반경/목적지NEW_DESIGN질문미응답유지. 전체명령·워프/항속·전술·기함/맵·2295리소스목표는active/미완료.

## 2026-09-05 — E027: 실제 전술 HUD WARP50과 자기 함선 활성화 확인

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-027-native-warp-power-50.md.

- v21/run20260905T041218Z-natural-l1-relogin-v1/client3644/HWND0x00000000019403C2/authority388을 새로 검증했다. 원본005123B0 WARP슬라이더max50과 역방향 좌표를 확인하고 세 번만 드래그: BEAM20→0, GUN20→0, WARP10→50. 재시도0/메모리쓰기0.
- **서버040C accepted3 → 자기characterWARP50 → entity+94C(warpEnabled)0→1 → 실제 전술HUD50**을 확인했다. 적warpEnabled0/count100, 자기count100, 방어막합20 유지. 실측10/50이며49/50양측경계실측은아니다.
- 새wire snapshot은총6626줄의tail2000.05:40Z이후1284event/reject0/connectionclose0/0404·0405·0406각0. requestPayloadHex는암호문이므로ObservedApplicationType1036/metadata로대조했다. 호스트/게스트시계차가있어절대시간순서로인과관계를확정하지않는다.
- 최종stateSHA1F5D23B296CBE365CF25DA6ED8B127D3758C4B26051D038250B74D72189D0330, 화면warp50-max-v35.pngSHA5A09C4859260BCD917A3E3BDA9C67D08A7D01A6187D209878BF3C68F49EECA7F, wireSHA0518811EE41DD4C9A2CE43CC07D0020F0B6EFDFA58F9ACF514C7EB0C55401752. 모두해당run폴더에보존.
- **현재기함정보창은계속열려있고NO TABLE/빈기함명문제유지. 배분은BEAM0/GUN0/ENGINE20/WARP50/SENSOR10.** 철수/공격/새배포/게임DB직접변경없음. E023/E026수정은로컬만;176tests는E026의이전결과로이번에재실행하지않음.

다음은0404준비통지/상태막대→0425자기함선제거후scene경로. 이단위는배분/활성화한정PASS이며철수반경·목적지·5초/150초투영·전략워프후항속/재로그인PASS아님. 미응답NEW_DESIGN질문유지. 세드래그를반복하지말고다음입력전동일성/화면재검증; 게임종료용escape helper로정보창을닫지않는다. 전체명령·전술·기함/맵·2295리소스사용처/326미해결목표는active.

## 2026-09-05 — E028: 서버도 WARP 배분50 조건 검사,181 tests PASS

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-028-warp-authority-power-gate.md.

- 원본scene역추적을진행하다서버가배분10에서도0404를수락하는실제결함확인. **NaturalAuthoritySession.ProcessTacticalWarpAsync는현재accepted040C의PowerWarp가50미만이면0500으로거부하고0425/함선refresh를보내지않는다.** 초기배분만보거나클라이언트활성화flag를신뢰하지않는다.
- 실제암호화세션5case로default10/0/10/49/50→49거부를검증. 초기testsequence1재사용문제로생긴4실패는RED에서제외하고수정후다섯case모두기존수락실패확인. 기존clocktest도먼저040C50을적용하도록변경.
- 전체181/181PASS,0skipped. finalTRXSHA256E23FB65D6E3025DE1D296006FCD2518B8870D3897ADC9D1E879663A515B3C965; validREDSHA256DD48D2F4EB511A8FABAE5F630594ADCC392B8C552FD962A6D627B845EEF6C421. caseevidence/warp-power-v36-tests/보존.
- 새Ghidraexport3개:자기함선조회후보004C5580은waypoint갱신,004FD100은UI모드처리,004F3010은카메라보간.004B68F0은reload완료와InformationGrid+35F35A에따라전술/전략builder선택.35837C..384직접displacement134개/scene호출자31개확인. alias/간접쓰기부재까지입증하지않았고**자기0425뒤원본서버메시지순서는여전히미확정**.
- **로컬수정만,새패키지/배포/native입력/게임DB변경0.** 0404준비encoder나수명주기를이번에추가한것은아니다. 50이상에서는기존미완성즉시0425경로유지. 마지막native는E027의v21/3644/388/정보창/WARP50이며이번에재조회하지않음.

다음:0404준비통지/예약완료/중복방지와survivor·도착지·scene연결. 전력검사통과를전체철수승인으로해석하지않는다. 반경/목적지NEW_DESIGN질문미응답유지. E023/E026/E028은함께배포전검증필요. 전명령·워프후항속/재로그인·전술전투·기함/맵·2295리소스/326미해결목표active.

## 2026-09-05 — E029: 공격 회신·피해 통지를 서버 시각에 맞춤,188 tests PASS

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-029-attack-authority-time.md.

- **최초가설정정:**004B4110은0405/0406Time을클라이언트시각으로초기화한다.0404스택미초기화와같지않다. Wait주소007CC704/007CBFF4는직접xref없음만확인했고미초기화라고단정하지않는다.
- 원본큐는0405/0406Time+Wait,0426Time에dispatch한다. 기존서버는피해를즉시적용하면서요청Time으로0426을보내고전체요청을회신했다. **이제현재즉시적용정책에서한번읽은server appliedAt을피해통지와0405/0406회신에공유하며회신Wait0**. 사격중지도회신시각수정/피해없음. Stop/Move/WARP시간일괄변경없음.
- 실제암호화세션7caseRED확인(expected24/actual0또는4045620583등),수정후전체188/188PASS0skipped. 연속정상sequence1/2에서tick24→48/피해25→50,나머지명령바이트와암호문입력보존검사. 재전송idempotency증거는아님.
- finalTRXSHA61A94421D4186949183A9BAF9378412F79484A159D01E175BF233A5DA7F6E1C2,REDSHA49D64325549681DDFB17B533D5B320CDB85B114A3559C6CFDF1A9A5A964CF92E. caseevidence/attack-clock-v37-tests/보존.
- **로컬미배포/새패키지0/native입력0/게임DB변경0.** 원본사격주기복원이나Wait0원본값주장이아니다. 25피해와한번만공격하는자동모드/세션로컬전투결과는여전히미완성. 마지막v21/3644/388/정보창/WARP50은E027상태이며이번에재조회하지않음.

다음:명령모드·무기·장전/사격주기·결과영속성연결,철수준비/목적지/scene및전략워프항속검증. E023/E026/E028/E029미배포묶음검증필요. 미응답NEW_DESIGN질문유지;전명령·전술·기함/맵·2295리소스/326미해결전체goalactive.

## 2026-09-05 — E030: 장전량 쓰기 후보가 별도 UI 객체임을 확인

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-030-corps-fill-alias-boundary.md.

- Ghidra로9B0..9EB의부모필드사용24개를추적. **00546570의+9CE/+9D0쓰기는전술함선이아닌UI객체021A9B70의인물레코드복사다.** 호출자00549816/00549908은0x370인수를만들어전달한다. E012의미확정장전쓰기후보에서이경로를제외한다.
- 실제EBX=entity+9B0(004C3D4E)에서이어지는004C3D33..3F44는전력12..1C/방어막22이후를소비하며장전1E/20의증가루프는확인되지않았다. 전체간접사용부재나unused주장아님.
- pristine/item116전체hash재확인및세범위(00546570/670bytes,005497F3/42bytes,004C3D33/530bytes)독립바이트동일성PASS. 새export2개와각hash는E030에보존.
- 이번은정적후보제외진전. production/build/test/deploy/native입력/게임DB변경0.188PASS는E029지난실행이며현재native동일성도새로조회하지않았다.

다음에는이UI복사와이미제외한0343default를반복하지않는다. 원본서버장전/출력수치또는다른gameplay소비경로가미해결이고명시적authored cadence승인질문은미응답이다. 마지막native는E027v21/3644/388/정보창/WARP50;다음입력전재검증. 전명령·전술·워프항속·기함/맵·2295리소스/326미해결전체goalactive.

## 2026-09-05 — E031: 반전0403의 마지막 방향 byte와0424 회전 경로 회수

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-031-reverse-command-and-turn-notification.md; reverse-turn-static-layout-v39.json.

- **0403형식은Time/Wait/Order u32,count u8,unit별{IDu32,directionf32},마지막directionu8**. body14+8*n/application16+8*n,count<=32. 마지막00610420(dst,1,0,2)는현재cursor에서1바이트읽기이며2바이트나enum범위검증아님.
- 0403직접dispatcher는world+437C08에복사/명령기록만하고회전handler를호출하지않는다. **0424(timeu32/unitu32/directionf32)→004BF970→004BF4C0**가현재위치유지회전trajectory를구성한다. 네pristine/item116범위바이트동일성확인.
- manual2517–2519:제자리180도/대기10초/기동능력에따른속도. sender mode6/7이쓰는per-unitfloat1/2와마지막direction의UI의미는미해결. 임의로1/2라디안또는±π로치환하지않는다.
- coverage생성기에0403layout추가,별도JSON에0403/0424형식보존. **둘다NOT_IMPLEMENTED/UNSEEN유지**,기존coverage snapshot을과거시점PASS로재작성하지않음. 새서버코드/build/test/deploy/native입력/게임DB변경0.

다음:mode6/7·마지막direction설정UI→10초예약/방향상태/후속명령취소/실제회전검증. echo만추가해서완료로보지않는다. 마지막nativeE027v21/3644/388/정보창/WARP50은이번에재조회하지않음.188PASS도E029지난결과. 전명령·전술·워프항속·기함/맵·2295리소스/326미해결전체goalactive.

## 2026-09-05 — E032: 좌우 반전 선택 확정, 전술 아이콘 204개 사용 경로 연결

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-032-reverse-choice-and-tactical-icon-bindings.md; tactical-widget-resource-bindings-v40.json.

- 원본 등록 루프 00512F02..00513014 → 00503A10 → 004DDA90와 UI 선택/송신 분기를 연결했다. **hanten_left: widget0x2B → mode7 → unit direction float2.0; hanten_right: widget0x2C → mode6 → float1.0.** 최종 라디안 값과 구분한다.
- 새 hash-bound 생성기로 전술 widget60개/상태 이미지 연결240건/고유 리소스204개를 추출했다. 기존 인벤토리와 전체 크기·해시 일치. 그중 201개는 과거 STRING_NO_DIRECT_XREF였으며 간접 테이블 등록/로더 경로가 확인됐다. 모든 명령 구현이나 화면 표시 검증이 아니다. 과거 graph와 별도326 queue는 재작성하지 않았다.
- 5개 원본/item116 코드·테이블 범위와 사용 문자열 바이트 동일. JSON SHA252E5B6C8DFE5859BF1DBDFA47FD5D3A6E3DBE0DD492F465938CB5EB061B856F. 생성기는 기존 출력 덮어쓰기를 거부하며 그 방지도 확인했다.
- 마지막 direction byte 007CC8B0 직접 참조 없음은 간접 쓰기 부재/항상0을 뜻하지 않는다. 회전 부호·속도·10초 예약·후속 취소/native는 미해결. production/build/test/deploy/native/DB 변경0. 188PASS와 E027 native 상태는 이번 재검증이 아니다.

다음: 실제 회전 보간 소비자 및 native 요청의 tail byte/Time/Wait 확인. 입력 전 새 PID/HWND/listener 확인. 전체 플레이 가능성과 2295 리소스 사용처 확인 목표는 유지한다.

## 2026-09-05 — E033: 원본 Turn 회전율과 통지 시각, ±π 경계 확인

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-033-turn-rate-clock-and-angle-boundary.md; turn-projection-static-check-v41.json.

- **030B Turn → template+214 → 24*Turn 회전율**, 004CA040의 duration은 abs(normalized target-current)/rate. 004CA620 type3이 시간 보간한다. 004C9A80 최초 catchup은 uint32(clientTick-notificationTick)/24. 원본 배율24를 나눗셈으로 잘못 해석하지 않는다.
- 현재 authored Turn1이면 반전 segment 약0.1309초라는 계산이며 원본 수치나 native 실측이 아니다. 매뉴얼의10초 명령 대기와 회전 소요시간은 별개다. 인물 기동 배율의 원본 서버 계산은 미해결.
- 004DC320은 [-π,+π] 정규화. float32 모델에서 heading1 +π, heading-1 -π의 단순 target 생성이 반대 부호로 정규화되는 경계 후보를 재현했다. 실제 x87/native 재현이 아니고 보정 epsilon도 미확정이다.
- 8개 PE 범위/7개 상수 동일,4개 모델 사례 확인. JSON SHA9DBE569203708419CE7C042A9FC270C21A72EBC0DC5C10D3E6A9A11526324425. production/build/suite/deploy/native/DB 변경0. 188PASS와 E027 runtime은 과거 결과다.

다음: fresh identity/화면을 확인한 native 요청 Time/Wait/tail과 좌우 반전 표현, 서버 예약/취소/중간 heading 연결. 즉시 echo나 임의 회전율로 완료 처리하지 않는다. 전체 목표 active.

## 2026-09-05 — E034: 반전 codec 구현204PASS, 실제Turn1 확인, 화면 캡처 실패

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-034-reverse-wire-codec-and-native-baseline.md.

- **0403 exact parser/0424 encoder를 로컬 구현**했다. 별도 tail byte, 최대32, 정확한 길이, NaN/Inf 방어, signed/unwrapped float 보존. literal 테스트16개 추가; 미구현stub RED11/16 이후 전체204/204PASS0skip. GreenTRXSHA6FF312513F290F6BDD683D2E6E7CC22BF5940941A3CB99DA9FE536CE9EBBD4D4.
- **아직 NaturalAuthoritySession 실행에는 연결하지 않았다.** CODEC_IMPLEMENTED_LOCAL/EXECUTION_NOT_WIRED. 10초 예약·후속 취소·방향 상태·native 반전은 미완료. 패키지/배포/DB변경0.
- 새07:02/07:04Z read: client3644/authority388 살아 있음, client경로/item116hash와listener소유권확인. tactical1/strategy0, selection0, WARP50, 양측100/morale100. 실제template+214 Turn1/raw0000803F, 자기heading0/적pi. request/reversebuffer0은 **미전송 기준 상태**이지 tail항상0 증거가 아니다.
- computer-use로 반환된VMware창을 선택/활성화했으나 `SetIsBorderRequired failed (0x80004002)` 캡처 오류. 새창정보로1회재시도도동일, **게임입력중단/재시작없음**. 새스크린샷/guestHWND확인은못얻음. 오래된닫기/반전좌표를쓰지않았다.
- run reverse-rpm-v42.json SHA3255960CCA6A80D5FFD38C9B8CC16DF6A447C0A34B6E5AE141F3C447016AF1B3; reverse-preflight-v42.json SHAF1C440642874B9DDDCF306E02D4B9C2692C443A3851AFCFB1658C0D5EECBD513.

다음: 서버반전예약/취소/중간heading 연결과 화면관찰 복구 후 native검증. capture오류를게임종료로해석하지않고바로재실행/클릭하지않는다. 미응답NEW_DESIGN선택유지, 전체목표active.

## 2026-09-05 — E035: VM 대체 캡처도 UI 관찰 불가, 프로세스 시계 진행 확인

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-035-live-clock-with-unobservable-screen.md.

- 새 guest PrintWindow와 별도GDI PNG는 흰색, 인증된vmrun captureScreen PNG는 검은색. 세 이미지를 직접 확인했다. HWND0x19403C2 owner3644/visible/foreground, item116hash/path/TCP연결은확인. **캡처 성공이 게임 화면 관찰 성공은 아니다.**
- 새07:15:40Z RPM은같은PID/생성시각, clock262394. E03407:04:54Z246869에서15525tick진행. 실제출력이빈것인지캡처경로문제인지아직모름; 게임멈춤/device-loss단정없음.
- 사용자에게 실제VM화면도빈지 비동기질문,응답대기. 같은캡처뺑뺑이/옛좌표클릭/게임서버재시작없음. 새host-capture-vm-screen.ps1은기존DPAPI출처로인증해읽기전용캡처하며비밀번호기록없음.
- gameinput/production/test/deploy/DB변경0. 204PASS는E034과거실행. E035run파일과해시는controllingreport참조.

다음은화면관찰회복후native요청검증,그동안안전한서버반전예약/취소/상태연결작업. UI경로문제를전체goal완료/blocked로바꾸지않는다. 전체명령/전술/워프항속/기함/맵/리소스/영속성목표active.

## 2026-09-05 — E036: 정지 원문과 반전 예약 교체 규칙의 승인 경계

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-036-stop-semantics-and-reverse-reservation-boundary.md.

- manual2599–2600은대기0초정지가현재실행중함선명령을취소/정지한다고명시. 대기반전예약과새이동/반전의교체규칙은아직명시적원본근거없음. Stop의현재서버구현은권한검사/echo이며예약취소상태없음.
- 서버는프레임을완전히읽어야ProcessAsync/송신하므로입력없는10초예약실행경로가필요. 하나의pendingframe read/상태소유자/암호화송신순서를유지해야한다. 공유전투/영속성목표를세션별독립타이머로대체하지않는다.
- 추천미확정규칙: Stop은현재동작/예약취소, 새Move/Reverse는이동계열기존예약교체. **NEW_DESIGN 선택이며아직승인/구현아님.** 기존서버/프로토콜변경승인은유지. brainstorming구조변경검토에서이선택확인전구현대기.
- production/build/test/deploy/native/DB변경0;204PASS는E034과거실행. 캡처반복/게임재시작없음. 전체goalactive.

## 2026-09-05 — E037: 잘못된 업데이트CAB 전제 정정, 원본250리소스 복구

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-037-update-cabinet-recovery-and-constmsg-boundary.md.

- 기존official-updates/data1.cab은MSCF설치엔진9파일이며unshield게임CAB가아니다. 로컬unshield1.6.2는이미있음. G7UPD040514.exe의0x19200overlay를분석해**실제data1.cab@0x19229/264894,data1.hdr@0x59D0F/33492,data2.cab@0x6200D/9468513**을분리했다. 원본실행/다운로드없음.
- unshield목록/test265개정상. 모델/텍스처250개만새evidence디렉터리로추출:기존인벤토리와202동일/48다름/0신규경로. 변경은47BMP와전략grid.mdx(44140→11934bytes). ShipMDX/누락101모델을복구한것아님. runtime/originaloverlay미적용.
- **이업데이트에는constmsg/msgdat없음.** 과거의이파일에서새constmsg복구가설제외. 현재constmsg는HFWR/tableCount120/stringCount3199,0054D4E0이요구하는0x78/0x7B는실제범위밖→NO TABLE. 서버기함번호교체로해결되지않음. group85row8브륀힐트와model8관계는여전히미확정.
- carve receipt SHA94C25F766F712EC815AE6E9F779662B7F9FBBC859D1E438311086D2B67418898;resource receipt SHAE74ECC26201737C276814BEE1494C9F6E48F4BDACE7E1702E59B4EA0C2F3D743. 두재현스크립트와모든파일해시는report참조.

다음:별도호환constmsg원본/loader버전,48개asset차이소비자,기함kind-modeljoin. 잘못된기존CAB추출을반복하지않는다. 반전예약규칙/화면질문미응답유지. production/testsuite/deploy/DB/native입력0,204PASS는E034이전결과. 전체goalactive.

## 2026-09-05 — E038: 기함 이름179행과 selector 분리 확인

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-038-flagship-name-selector-and-source-boundary.md.

- 원본 constmsg group85의179행을 hash-bound 직접 파싱: row0=戦艦, row8=ブリュンヒルト. 정보창0054D896은 character+31C u16로 이 표를 조회하고, 외형은 별도 template.model_file 경로다. 현재 단일 kind0/model0 bootstrap은 일반 전함 이름과 공통 외형을 공급하는 상태이며 model0을 브륀힐트라고 확정하지 않는다.
- wrapper004C8DC0의5개 직접 caller와 서로 다른 레코드 selector를 회수했다. 정보창/5caller/wrapper의7개 코드 범위 pristine/item116 동일. 다른 scalar55 후보는 widget색상 인자나 group16 row55여서 기함 이름 소비자가 아니다. JSON SHADF6E60ABE872BFFDEDBE165E914747C84F89C1470FE90EE4C3F1797BFD6E57E7.
- 두 로컬 raw constmsg 사본은 동일hash/114905bytes. 이전 revival overlay120..123은 빈 PLACEHOLDER/DERIVED이며 원본 호환 신판으로 재사용하지 않았다. NO TABLE/기함배정은 아직 미수정.

다음:00572EE0 레코드 공급자와0057C890 목록 생성, 생성/추첨 kind→030B template→model_file 연결. row8을 model8로 대입 금지. 확인 위치는 이전에 검증한 情報→旗艦情報이며 현 화면은 재관찰하지 않았다. 이번 production/testsuite/deploy/DB/native입력0,204PASS는E034과거결과. 전체목표 active.

## 2026-09-05 — E039: 실제 생성 selector 정정, 기함 종류 ACK 공급 확인

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-039-creation-flagship-ack-and-selector-correction.md.

- **004B78A0 decompile의 case라벨만 믿지 않는다.** 실제DEC/128pointertable로 selector0x0E→1008,0x0D→1007을 확인했다. 생성00595DE0도0x0E송신. 새 rawtable127성공코드분기/1오류분기, 전체table/branch bytes두PE동일. 반전54/철수55는이전결론과같다. 기계적전체+1정정금지.
- 생성00595DB0는buffer128bytes0초기화.00595E00는성공event후응답pointer에서128bytes복원.00597720기함페이지는buffer+5E kind를group55로표시하고함명만저장한다. 현재서버category0..3그대로echo이며원본기함배정규칙미구현. 종류8을model8로대입하지않는다.
-00572EE0→004C6930은TARGET_ORGANIZE의편성수량목록이며기함모델대응표가아니다.6개생성/편성범위pristine/item116동일. 핵심JSON request-selector-table-v46-complete.json SHA0439A655953EB447E55963B3E333D248B8FAD53F66E3D6F082CCB14D9A989596.

다음:생성/추첨의서버배정자료및kind→template→modeljoin. 다른명령selector도새rawtable대조. 기존 decompile 기반case라벨을원본번호로재사용하지않는다. 이번production/testsuite/deploy/DB/native입력0;204PASS는E034과거실행. 전체목표active.

## 2026-09-05 — E040: 저장된 함명0323 누락 수정,210PASS

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-040-character-flagship-name-projection.md.

- **E039해석정정:**Ghidra에`switch((selector&0xffff)-1)`가명시돼있다. case0x0D와caller0x0E는일치한다. 앞서case만읽은분석자오류이며Ghidra오류가아니다. rawtable은유효.
- OriginalWorldEntryCodec.EncodeCharacter의0323flagship_name이항상빈문자열이었다. 이제저장/restoredcommand.FlagshipName을전송한다. 원본00417390/00419300확인및두PE범위동일. 함종/model/배정규칙변경없음.
- NPCclone이새전송필드까지viewer함명을복사하는회귀를RED로잡고미지정NPC는빈함명유지. literal빈/영문/일문/최대13글자,실제암호화0322→storefixture복원→0323(두새세션),NPC분리총6cases. expandedRED4/5,NPCRED1/1후전체 **210/210PASS0skip**. finalTRXSHA A7B9F1F50BDB388CA3A660876192D735C5200DA179C7276E755A3F94872C9C6D.

로컬미배포/DB/native입력/새패키지0. 테스트저장소fixture는Postgres/native재로그인이아니다. 030Btemplate명과0323개인함명을혼동하지않고기함정보창전체표시수정으로보고하지않는다. 기함kind/modeljoin·전전략/전술/워프항속/전리소스/영속성전체목표active.

## 2026-09-05 — E041: 함명 raw→인물→UI 복사 경로 확인

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-041-character-name-copy-and-display-boundary.md.

-0323raw+28 count/+2A u16[13]→dispatcher snapshot→004C2C80 normalized+4C/+4E→00549FD0 stackcopy→00546570 UI+9F0/+9F2. 이경로는13칸을복사하며종료문자를추가하지않는다.4개PE범위동일.
-실제그리기소비자의count/NUL방식은미확정. 배열꼬리가남을수있다는사실을화면오류로단정하거나wire를임의로줄이지않았다. 직접4E/2A/9F2/절대주소검색을반복하지않고UI전체객체표시바인딩또는동일실행픽셀검증으로이어간다.

이번production/testsuite/deploy/DB/native입력0.210PASS는E040지난실행. 실제VM화면정상여부미응답,실패캡처/옛좌표재시도없음. 전체goalactive.

## 2026-09-05 — E042: 정지 실행 결함 확인,0423 codec226PASS

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-042-stop-boundary-and-movedship-codec.md.

-040A직접case는명령복사/공통order처리. 서버도Stop은echo이며Move는즉시목적지를저장한다. 이동중현재좌표가없으므로마지막목적지를정지위치로보내지않는다.
-0423parser004A5870/logger004A5A20:timeu32/unitu32/directionf32/xyzf32/routes8,본문25(application27/full31),expanded28과구분.004BF870은route>=0때410/414갱신,음수때mode4/경로재구성. Stop과정확히어떻게연결되는지아직미확정.
-MovedShipNotificationencoder추가,route경계4+NaN/Inf12cases RED16후전체 **226/226PASS0skip**. 생산실행분기미연결/로컬미배포. GreenTRXSHA ABC19AFB4EEC10D561F992C68648A764C2AB25B17EFF2F78BE47F2B083947F56.4개PE범위동일.

다음:410/414경로진행소비자와권위이동보간/정지위치/취소/공유전투연결. 신규위치통지를임의Stop레시피로사용하지않는다. 배포/DB/native입력0,NEW_DESIGN및화면질문미응답유지. 전체goalactive.

## 2026-09-05 — E043: 경로 진행 제한과 소행성대 감속 데이터 연결

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-043-route-progress-and-asteroid-speed.md.

- 004CA620은 +410 route/+414 dirty를 경로 구간 진행 한도로 변환한다. X/Z가 일치하는 첫 구간을 선택하며 Y/type은 비교하지 않는다. elapsed 누적은 현재 구간 <= 허용 구간일 때만, 완료는 elapsed > duration이다. 보간 계산은 이 제한보다 앞서므로 화면이 완전히 정지한다고 단정하지 않는다.
- 004B2C80은 환경 배율 +420을 1로 초기화하고 소행성대 내부에서 0.5로 설정한다. 0347 radius/range → innerRadius=radius-range → 장애물+8C4 → 엄격한 안쪽/바깥쪽 반경 비교. +418=+420*+41C; 이동 경로 속도는 여기에 24를 곱한다. 맵 데이터가 외형뿐 아니라 이동 규칙에도 연결된다. 전체 원본 맵/16개 레코드 동작을 복구했다는 뜻은 아니다.
- 원본/패치본 9개 구간 동일, 경계값 15개 검증 통과. **STATIC_MODELED_NOT_NATIVE**. receipt SHA256 4F4775617D9FF8D92E6EEB272577FE5647428D30F4D31F61C7C7EF9193FFF241. 실제 서버의 목적지 즉시 저장은 아직 남아 있다.

다음: 초기 경로 진행 허용값/명령 응답 연결을 확인하고 공유 전투 소유의 시간별 이동 상태로 연결. Stop은 같은 상태에서 현재 위치를 얻어야 한다. 임의 통지 주기/예약 교체 규칙 추가 금지. 이번 production/testsuite/deploy/DB/native 입력 변경 없음; 226PASS는 E042 결과. 전체 리소스 사용처 역추적을 포함한 전체 목표 active.

## 2026-09-05 — E044: 이동 응답의 초기 route와 일반/평행 이동 구분

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-044-move-response-initial-route.md.

- 0400 → 004BE8F0는 mode2, 0402 → 004BF320은 평행 이동 mode4. 실행 분기는 Time+Wait와 목적지/velocity를 004BF4C0에 전달한다. 별도 flag 분기는 대기 카운트만 설정한다.
- 004BF4C0이 rebuild+60=1, dirty+414=1, route+410=0을 설정한다. 실행된 새 이동은 기존 경로를 덮어쓴다. 미실행 예약 교체 규칙까지 확인한 것은 아니다. 004C9A80은 elapsed/currentSegment를 0으로 초기화하며 첫 양수 시간 갱신에서 route0이 허용 구간을 공급한다.
- 일반 단일 목적지는 선회/이동/최종 선회 3구간, 평행 이동은 이동/최종 선회 2구간이다. 퇴화하지 않은 X/Z 조건에서 route0은 최초 이동 구간까지 허용하므로 최종 구간을 위한 후속 진행 통지가 필요하다. 정확한 원본 서버 송신 시점은 미복구.
- 현재 서버는 목적지를 즉시 저장하고 echo+전체 함선 snapshot을 보낸다. snapshot만 삭제해도 권위 위치/진행 통지 결함은 남는다. 정확한 화면 영향은 미관찰. 4개 PE 구간 동일, 신규 export SHA1FA51023F1BE8D33EC10EB654AFF99C47F1840FF0CFBFD8931E6571CBDE94532.

초기 route 추적은 종료하고 공유 권위 이동 상태/경로 경계 통지로 이어간다. 이번 production/testsuite/deploy/DB/native 입력 변경 없음. 전체 목표 active, 226PASS는 E042 이전 실행 결과.

## 2026-09-05 — E045: 전술 장애물 합계10 제한 서버 반영,232PASS

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-045-native-obstacle-capacity-guard.md.

- 예약 교체 질문만 남긴 직전 턴은 no-progress로 분류하고, 승인과 무관하게 확인 가능한 맵 소비 경로로 진행했다. 004C7EF0 category2/004BE520/004B2C80 모두 manager+17991C,stride8E0의 합계10 슬롯 사용. 부족 시 allocator0,004C4970의 후속 복사에는 null검사가 없다. 실제 crash재현은 하지 않았다.
- 서버 종류별 최대1/1/10/1/5는 합계18까지 허용했다. OriginalBattlefieldCatalog.Validate에서 합계>10을 거부하도록 수정. 정상10은 모든 레코드 유지. wire/geometry/예약정책 변경 없음. 이전 count>16 diagnostic을 asteroid16지원 근거로 삼지 않도록 E043 설명도 정정.
- TDD 신규6cases: RED3거부누락/3정상PASS → 전체 **232/232PASS0skip**. GREEN TRX SHA256 BFEEE968BF928D709253559021E5AEB2B605CEB3E271A9366ED608E5C4956149. 원본/패치본4개코드구간동일.

로컬미배포,DB/native입력/재시작0. 기본맵은 여전히 장애물없는 authored field이며 행성표시수정으로 보고하지 않는다. 다음은 별도Base(category0)10슬롯과 현재wire16제한의 실제생성경로 대조. 전체전략/전술/워프항속/기함/맵/전리소스/영속성 목표 active; 예약질문 미응답 유지.

## 2026-09-05 — E046: 전술 기지의 필드10/패킷16 분리,235PASS

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-046-native-base-capacity-guard.md.

- 004C32A0의 전술 Base 레코드가 category0 생성으로 이어지고,004C7EF0/004BE4D0은 manager+174124,stride8CC의 별도10슬롯을 사용한다. 장애물 pool과 합산하지 않는다.3개원본/패치코드구간동일.
- OriginalBattlefieldCatalog.ValidateBaseJoins의 같은grid 생성한도16→10 수정. OriginalSystemSceneCodec의 패킷한도16은 유지. 전역카탈로그합계10으로 제한하지 않는다.
- 수정전11/16거부test2실패,17은기존거부. 별도grid마다10개씩총20개를 유지하고 위치packet4/4/2분할을 확인. 전체 **235/235PASS0skip**. GREEN TRX SHA256 410C3AAFCA83A053185EA0ADA72DE2B24B4766764C344E4F1F61E00EE1935861.

기지/장애물수용량추적은 이검증범위에서종료. 기본1기지·행성표시상태는 바꾸지 않았다. 로컬미배포/DB/native입력/재시작0. 다음은E043/E044근거의 실제이동상태연결로복귀하며 미확정예약규칙은분리한다. 전체goalactive.

## 2026-09-05 — E047: 거부된 이동의 상태오염과 비유한값 수정,267PASS

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-047-move-state-integrity.md.

- 첫0400은 권한검사전에 기본XYZ0/morale0/search0 함선을 캐시했다. 거부된요청뒤033A도 잘못된상태를 반환했다. 이제맵template의함선을 로컬후보로구하고 승인후에만 캐시한다.
- 이동9개float의NaN/±Inf를 권위검사에서 거부하고 상태/이동projection을 만들지 않는다. structuraldecoder/연결은 유지하며 기존 visible rejection 사용. 새속도범위/예약규칙 추가없음.
- TDD RED31/32→전체 **267/267PASS0skip**. 암호화0400거부후033A조회에서맵spawn50/7/-20,heading1.5,morale100/search1유지확인. GREEN TRX SHA256 50A5511ED24CD36CB4202606F4F54F072A977E54C85B79F0489C0816E67649BC.

실제생산분기수정이나 유효이동의목적지즉시저장은아직남아있다. 연속이동/정지/공유전투/예약구현으로보고하지않는다. 로컬미배포/DB/native입력/재시작0,전체goalactive. 다음은E043/E044기반시간별이동상태연결이며 이번입력결함을재조사하지않는다.

## 2026-09-05 — E048: UI 효과음 사용처2개와 워프음 후보 구분

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-048-sound-trigger-discrimination.md.

- 승인질문만반복한직전턴은no-progress. 독립적으로가능한전리소스조사로진행해004AFF50의5개직접호출을확인했다.4곳은ID3→cursor.wav의전술/전략UI선택,1곳은ID1→SOUND01.WAV의로그인form이벤트0D분기다. Enter해석은추론이며인증성공음이아니다.
-5곳모두warpID4가아니다. warp1.wav는등록확인/발동미확인/미사용확정아님유지. 실제음성출력미관찰. sound-trigger-use-ledger-v55.json에2리소스/5호출/조건/원본해시를추가overlay로기록했으며326queue수치를빼지않는다.
-5개PUSH/CALL코드구간원본/패치동일,두음원hash현재확인. 신규export SHA256 CE324E45BCC6C79F5B3BB8B619DDBA031A8DFBD5FB59CB175F408021B86F3908. 같은직접wrapper조사반복금지.

이번production/build/testsuite/deploy/DB/native입력0,267PASS는E047이전결과. 예약규칙/화면질문미응답이며자동계속을승인으로보지않는다. 독립리소스추적이진전해전체goalactive유지.

## 2026-09-05 — E049: 전략/전술 BGM 선택설정과7곡 연결

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-049-bgm-mode-and-settings-selection.md.

- 004AFD90의직접caller6개회수:초기화choice0,설정미리듣기2곳,전략/전술선택2곳,unmute기억곡1곳. 전술UI mode1은설정0D,전략mode2는0C를읽는다. 004B68F0→0054E570→004C32A0/004C4170으로모드의미를대조했다.
- 0054EF40설정UI가곡/음량변경을처리하고닫힐때설정10..13저장함수/004F34F0호출. 디스크영속성검증은아니다. bootstrap0=SwanLake,다른곡은설정값이며특정전투별고정곡배정근거없음.
- bgm-trigger-use-ledger-v56.json에7곡과선택조건을추가.7개원본음원hash/7개PE구간검증. 실제재생UNSEEN/기존326queue불변.같은BGM직접호출추적반복금지.

다음은등록/발동/실재생을구분하는리소스원장통합. 이번production/build/testsuite/deploy/DB/native/audio설정변경0,267PASS는E047이전결과. 전체goalactive,예약정책/화면질문미응답유지.

## 2026-09-05 — E050: 음원20개 증거 통합과 단계별 미검증 유지

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-050-consolidated-sound-evidence.md.

- 최신통합view는sound-use-consolidated-v57.json. E025등록20/E048UI2/E049BGM7/기존E025무기5를path+hash+실행파일계보로합쳤다. 등록20,부분발동근거14,발동미해결6,실재생검증0. 무기5는기존근거구조화이며신규발견으로세지않는다.
- 미해결6:W_NOISE/SOUND02/warp1/fengine/bburn/exp3. 모두미사용확정아님. 역사적326queue와2,295전체인벤토리수치불변.
- 검증utility7tests RED→GREEN,실제실행파일및20개원본음원hash검사통과. 다른hash/계보/unknown/중복및정적증거로playback승격을거부한다. 통합JSON SHA256 62C9C7A7DAD6023BC582CE6AA77B15049EBEEB882200134B62EE7B989147362C.

이번게임production/protocolsuite/deploy/DB/native/audio설정변경0. utility7PASS를이전protocol267PASS에합산하지않는다. 전체플레이목표active,이동/정지/예약및화면미확정유지. 이후음원검토는이통합view부터읽고같은등록/직접호출조사를반복하지않는다.

## 2026-09-05 — E051: 공식 전술 명령표와 패치 기록 복구

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-051-official-tactical-reference-recovery.md.

공식 gineiden.com의 Wayback 원시 HTML 4개를 official-web-v58에 고정 URL·해시와 함께 보존했다. 공격은 자동 공격, 사격은 지정 무기의 단발로 구분된다. 정지는 실행 중 명령을 대상으로 설명하지만 예약 취소 범위는 명시하지 않는다. 2004-06-25 전술 조정 공지는 명중률의 공격/방어 보정과 주둔 유닛 차폐 변경을 기록하며 수식은 없다. 고정25피해를 원본 규칙으로 취급하지 않는다.

매뉴얼 페이지에서 2004-10-07 갱신 gin7manual.pdf 경로를 확인했으나 PDF 자체의 로컬 판본 비교는 아직 안 했다. 다음 시작은 이 판본 비교 또는 보존 업데이트 공지의 명령 대기·취소 변경 조사다. CGI num과 캡처 날짜를 함께 검증할 것. 이번 production/test/deploy/DB/native 입력0,267PASS는 이전 결과. 전체 목표 active; 예약 규칙이나 실제 플레이가 해결된 것으로 보고하지 않는다.

## 2026-09-05 — E052: 매뉴얼 중복 경로 종료, 기함 구매 ID96으로 연결

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-052-manual-identity-and-late-gameplay-update.md.

기존 manual-variants의 Wayback PDF와 IA PDF는 동일 SHA256 FF9B7B638582FEBBA723413D9956F4166AECBC20746CB35BB4AFDDCEF9515080. E051의 매뉴얼 후보는 새 판본이 아니며 재조사하지 않는다. 기존 파일이 있는데 초기 index.csv의 ERROR만 보고 미확보라고 판단하지 말 것.

기존 공식 update05의 본문을 다시 대조: 수도성 기함 공창의 개인 카드에서 기함 구매, 함종별 평가 포인트/계급 제한, 격침 후 구축함 복귀. 우주 전술 철퇴는 위치 방향의 인접 그리드로 바뀌었다는 설명이다. 정확한 기하·가격·모델 ID·현재 PE의 업데이트 적용은 미확정.

strategy-command-ledger.json ID96에 출처/문서상 UI만 추가하고 NOT_STARTED/request=null 유지. 개인 카드0 빈 목록 제공 관찰을 원본 전체 무명령 규칙으로 일반화했던 문장을 정정했다. 다음은 ID96 표시 조건→목록 공급자→요청 생성 추적이다. 이번 게임 코드/test/deploy/DB/native 입력0; 전체 목표 active.

## 2026-09-05 — E053: 기함 변경0358의 직접 후속 처리 없음

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-053-flagship-change-receiver-stub.md.

00571870의17개 대상 선택 표에는ID96이 없으며, scalar60 UI 후보 네 함수의 해당 값은배치였다. 구매 요청은미발견이고클라이언트전체기능부재를확정하지않는다.

0358 NotifyChangeFlagShip 분기는world+4332D0에확장92바이트를복사한뒤005266E0호출. 이함수는C2 04 00(RET4)뿐이며다른통지도공유한다. 원본/item116의함수3바이트·dispatcher54바이트·표136바이트동일. 서버0358송신만으로기함교체표시완료라고보고할수없다. 다른정보갱신경로가능성은열려있다.

다음은기함kind/template의실제갱신소비자또는지원클라이언트판본확인이다. 같은scalar검색·네UI후보·이미완료한G7UPD040514추출반복금지. 이번production/test/deploy/DB/native변경0. 전체goalactive,ID96 NOT_STARTED유지.

## 2026-09-05 — E054:033B는기존entity/model갱신증거가아님

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-054-unit-snapshot-versus-render-binding.md.

033B는world+4271A8에확장0x79E4바이트복사후004BE750호출. 이함수는B0 01 C2 04 00으로성공값1만반환한다. 함종은004C46A0 category1→identity004C5130→entity+8BC에서설정된다. 모델연결004F19E0의직접경로는004B64C0→004BE440및004C32A0→004BE490이다. 원본/item116의7구간동일; 간접갱신전체부재나원본전체미구현을단정하지않는다.

서버033B전송/테스트를실제기존함선표시갱신으로승격하지말것. 다음은E042–E044의0423통지를실제이동authority에연결하는핵심작업으로복귀한다. 033B재전송으로대신하지않고같은stub/caller조사를반복하지않는다. 이번production/test/deploy/DB/native0,전체goalactive.

## 2026-09-05 — E055: 전술 경로cursor C# 구현,300PASS / 아직미연결

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-055-tactical-trajectory-cursor-implementation.md.

OriginalTacticalTrajectory.Step을서버프로젝트에추가했다. E043/E044의004CA620을따라type2/4보간,type3회전,XZ첫match,승인구간경과시간,엄격한elapsed>duration,새ack프레임잔여루프중지등을구현했다. 실제함선추종좌표가아닌Desired계산이며x87비트동일주장없음.

TDD33테스트: 초기21/22실패→기능22PASS→확장guard10/33실패→전체300/300PASS0skip. GREEN TRX SHA25641023F36B8FFAB8C57B03B517291D6FD5F9CE4BA0CF97FFC57FFEF81ABC636F9. 입력방어는재구현API규칙으로분리.

아직테스트만호출하며0400목적지즉시저장문제를고친것이아니다. 다음은기존004CA2E0 actual pursuit와004C9D30 builder,이후공용battle/clock/0423/Stop연결이다. Desired를실제좌표로저장하거나033B재전송으로대신하지않는다. DB/deploy/native0,전체goalactive.

## 2026-09-06 — E056: 실제 추종 계산 구현과 XYZ 거리 정정

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-056-tactical-pursuit-implementation.md.

OriginalTacticalTrajectory.Pursue를 추가했다. 004CA2E0의 이동 한계·도달 시 XYZ 복사·회전의 signed 비교·moving flag clear 조건을 이식했다. 원본/item116 전체 hash 및 8개 코드/상수 구간 동일 확인. 새25개 테스트 포함 최종325PASS. 중간322PASS는 아래 정정 전 결과이므로 최종 근거로 쓰지 않는다.

중요 정정: 004CA160→005DD880은 XZ가 아니라 XYZ 거리다. 004DC2B0은 그 XYZ 거리로 정규화한 벡터에 asin/acos를 사용하므로 Y가 다를 때 atan2(dx,dz)와도 다르다. E043의 거리 설명은 폐기한다. 경로 승인 XZ 매칭과 asteroid XZ 판정은 다른 함수이며 변경하지 않는다. 004CA1E0 duration도 같은 XYZ helper를 사용한다.

아직 runtime 미연결이다. 다음은 기존 export의004C9D30/004CA0D0/004CA040/004CA1E0 builder를 구현하고 shared battle/clock/0423/Stop에 연결한다. 편대 leader transform과 render derivative는 미구현. 현재0400 즉시 저장은 그대로이며 DB/deploy/native0. 반복 helper 조사 금지, 전체goalactive.

## 2026-09-06 — E057: 경로 생성기 구현,349PASS / 명령 연결은 다음

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-057-tactical-path-builder-implementation.md.

OriginalTacticalTrajectory.Build로mode2 일반/mode3 선회후이동/mode4 평행 및 저장origin 보정 fallback을 구현했다. 실제entity pose로 첫 거리/회전을 계산하되 interpolation origin은entity+110으로 따로 유지한다. rotation target은(0,heading,0),duration은짧은호/turn rate이고 translation은XYZ거리/speed. 004CA0D0 거리0은 기존heading 유지. 13개 원본/item116 구간 동일.

신규24RED→전체349PASS. 생성→cursor→actual pursuit 조합 test 포함. 하지만 production handler에는 미연결이며0400 목적지 즉시 저장은 남아 있다. 다음은 builder/helper 반복분석이 아니라 shared battle/clock/실제motion 수명주기와0423/Stop 연결이다. idle에서도 due event를 내보낼 single writer와 진행 중 frame read를 보존할 것. pending예약 교체 정책은 미승인 상태 그대로이며 읽기요청 기반 위치갱신/033B 재송신을 완성으로 취급하지 않는다. DB/deploy/native0,전체goalactive.

## 2026-09-06 — E058: 비동기 통지 I/O 루프 연결,360PASS

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-058-idle-capable-connection-pump.md.

NaturalAuthorityServer가OriginalConnectionPump를사용한다. 단일전체frame read task와session.PendingNotifications를동시에기다리며cipher sequence/송신callback은직렬이다. 부분prefix/body수신중통지를처리해도원래read를재시작하지않는다. 종료시양쪽wait를cancel/회수하고channel writer도complete한다. 기존길이경계와응답batch순서를유지한다.

실제loopback TCP7RED→7PASS,추가회귀4포함11case/전체360PASS. 전체host로그인native검증은아니다. PendingNotifications는bounded64 Wait 내부큐이며현재전투producer는없다. 따라서다음은shared battle/motion clock/subscription과실제due-event공급이다. 0400목적지즉시저장/per-session상태는아직남아있다. I/O만연결한것을게임이동해결로승격하지말것. DB/deploy/native/restart0,전체goalactive.

## 2026-09-06 — E059: 관리자 직접 접근 분류와 데이터 음원 경로

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-059-alternate-sound-data-path.md.

예약 정책 질문은 미응답이며 자동 continuation을 승인으로 간주하지 않았다. 독립 전체 리소스 조사로00616CA0 getter38CALL(16함수) 중 신규10함수를 분류했다. 대부분 생성/정리/설정이며0061D1E0 case2에는 이벤트ID→stride10h 레코드→memory source→자식 음원 생성 경로가 있다. fengine/bburn 연결은 아직 미확정. packed10000106 literal1건은실행불가.rdata이므로play CALL이 아니다.

신규13함수 원본/item116 구간 및 미확정6음원hash검사. sound-alternate-access-v65.json SHA2562E8EBD6AF45306A61B925A558A56B1B3CD599F1FBC348D1958BE4B7414D5B215. 기존20/14/6/native0과2295전체/326queue 불변. 다음음원추적은0061D120/0061D1E0 owner+14/+18 descriptor 공급자와payload다. 같은getter/등록/래퍼직접호출 반복금지. production/test/deploy/DB/native0,전체goalactive.

## 2026-09-06 — E060: 대체 음원 경로는 GSD로 한정, 반복 조사 종료

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-060-gsd-sound-path-boundary.md.

0061CBE0이owner+14에source+11C를저장하고subselection ID로descriptor+10table의row를선택해owner+18로연결한다. 이객체는0061C590 format2→0061CA00→vtable00682458이만들며format2는gsd다. source mode0/2(file/memory)와format0/1/2/3(wav/ogg/gsd/ikm)을혼동하지말것.

현재20음원은WAV/OGG,보존설치원장2295행과별도CD추출data2185파일에서gsd/ikm파일명0. 내장/후공급데이터부재까지단정하지않지만이GSD경로를fengine/bburn발동근거로쓸수없다.15구간원본/item116일치와20음원hash검사. receipt sound-format-boundary-v66.json SHA2564C23BFFEBDB712F0269321E94FCEBA4B60E90024CAE21C979353548162BA2054.

새GSD/embedded bank근거전까지이분기반복조사금지. 음원20/14/6/native0과전체2295/326queue불변. 다음은다른미해결리소스사용자추적또는새관찰조건이갖춰진native검증. 예약정책승인미응답,production/test/deploy/DB/native0,전체goalactive.

## 2026-09-06 — E061: 공유 NPC피해와 실제 비동기 producer 연결,371PASS

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-061-shared-npc-battle-and-idle-damage.md.

NaturalAuthorityServer가grid별OriginalTacticalBattleRegistry를모든session에주입한다. 기존NPC피해/생존/종료는process공유이며relay의표적확인→변경→publish를commandlease로직렬화한다. 관찰자는0426과종료0317/0F1F를PendingNotifications로받는다. actor개인snapshot은타인에게복사하지않는다. 구독은grid변경/연결종료시해제된다. overflow는slowpeerchannel을실패시키며lock안에서대기하거나조용히이벤트를누락하지않는다.

5통합RED→5GREEN,추가6검증포함전체371PASS. 다른key의무입력observer가실제loopbackTCP에서session암호화sequence2/3,피해25/50수신. 최종피해후종료통지/latejoin완료유지/다른grid격리/동시공격/서로다른unit2/3도검증. 최종TRX SHA2563A85E77072D33208762C6C2A02E0ED3B679E4C91E10F99039600E4988B3DE534.

E058의producer없음은NPC피해/종료에한해해소. 플레이어이동/편대/모델생성/DB영속성은아직공유하지않으며0400즉시저장도남아있다. 원본0426수신자가관찰자에게없는공격자unit을필요로하는지확인해야하므로native표시성공으로승격하지않는다. 다음은해당조건/참여자bootstrap또는공유motion연결이다. 원본미확정예약정책/25피해balance변경0,DB/deploy/native/restart0,전체goalactive.

## 2026-09-06 — E062: 타인 공격자 누락 시 피해 전체 무시, 증분 함선 진입 경로 확인

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-062-incremental-tactical-participant-entry.md.

원본 004C0DF0은 공격자/피격자 중 하나라도 lookup 실패하면 효과뿐 아니라 피해·잔존 수·방어막·사기 갱신 전체를 건너뛴다. 따라서 E061의 서로 다른 unit 통신 테스트는 관찰자 화면의 피해 반영 증거가 아니다.

0B09는 임시 인물 count를 비우고, 전술 mode +126711==0의 0B0A는 004C2A80(1)→004C32A0(1)을 호출한다. 후자는 033B와 0325를 unit ID로 조인하고 실제 0323 인물과 모델 kind를 연결한 뒤 004BE490 renderer bind를 호출한다. E054의 033B 단독 hot-apply 불가와 구별할 것. 004C4970은 중복 ID 슬롯을 재사용하지만 0x8BC 공통 영역을 덮어쓰므로 기존 viewer/NPC를 반복 진입시키면 안 된다.

다음 구현은 원본 함수를 더 재조사하는 것이 아니라 (1) 요청 응답이 끼어들지 않는 단일 알림 batch, (2) 실제 actor descriptor 및 observer별 known ID, (3) 누락 actor 진입 뒤 0426 순서다. 현재 per-frame pump에는 중간 응답이 임시 0325/033B를 덮을 위험이 있다. 최소 packet 집합과 corps/shield 조건은 보고서의 확인/미확인 경계를 유지할 것. 타인에게 자신의 CharacterContext/full-scene/0F1F를 복사하지 말 것.

원본/item116 9구간 일치, 9명령 assertion PASS. receipt incremental-ship-entry-v68.json SHA256 368AE2E7A2EDFAEE9FF4D939ED727D74857D7854AB500986365C42BDC47504D3. production/test 재실행/DB/deploy/native/restart 0. 직전 371 PASS는 통신/서버 검사이며 native 성공으로 승격하지 않는다. 전체 리소스 목표와 예약정책 미응답 상태는 그대로, 전체 goal active.

## 2026-09-06 — E063: 공격자 증분 진입을 공유 피해에 연결, 전송 묶음과 377 PASS

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-063-observer-participant-batches.md.

알림 채널을 OriginalTacticalNotificationBatch 단위로 변경했다. 등록 시 프레임을 복사하고 grid/subscription ID를 붙인다. Registry는 observer별 known unit을 추적해 처음 보는 공격자만 0B09→0323→0325→033F→033B→0B0A로 등록한 뒤 0426을 보낸다. 배우의 실제 character/기함명/현재 pose·사기·corps를 사용하며 recipient/NPC를 진입 목록에 포함하지 않는다. 단 kind는 기존 0 유지로 기함 모델 선택 복원이 아니다. 인물 데이터 없는 새 공격자는 사용할 수 없는 피해를 전송하는 대신 해당 observer 연결을 실패시킨다.

Pump는 generic notification callback을 지원하며 실제 host는 한 callback에서 전체 묶음을 인코딩·전송한다. Full queue는 새 사건 일부만 받지 않는다. Grid를 떠났다가 같은 grid로 돌아와도 옛 subscription 묶음은 sequence 소비 전에 폐기한다. 최초 2개 실제 실패를 확인했고 추가 경계/TCP 포함 신규 6개, 전체377PASS. 다른 key의 실제 TCP에서 진입 중 들어온 조회 응답은 진입·피해 sequence2–8 뒤 sequence9로 전달됐다. Final TRX SHA256 6727A675B6C1041AD10F903F8447987839756F626E7F2E7770573319A21DB2BD; evidence/participant-v69-tests에 보존.

이것은 공격 시점의 누락 actor 보완이지 전체 참가자 입장/퇴장/조회 동기화 완료가 아니다. 0341 조건부 방어막 보조 데이터와 0337/UI 후속 소비자, native 전술 scene 준비 조건이 남았다. 다음은 그 데이터 조인과 full participant lifecycle이며 같은 null guard/idle pump 반복 조사 금지. DB/deploy/native/restart/resource 신규 검증0, E023+ 미배포, 예약정책 미응답 유지, 전체 goal active.

## 2026-09-06 — E064: 방어막 0341 데이터 연결, 388 PASS

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-064-shield-fill-import-data.md.

00423890 파서/00423CC0 logger 확인: count:u16<=600, unit:u32 + next_time:u32[6] + shield_step:u16[6], 레코드40바이트. 원본은 +9==0 함선의 레코드를 unit ID로 찾고 없으면004C4005 오류 경로에 들어간다. next_time은 초기 elapsed=period-next_time에 쓰이며 절대 game tick이 아니다. 004C1700이 정적 shield table에서 period를 만들고004B2740은 strict elapsed>period일 때 [0,3,5,4,1,2] 축 순서로 회복시킨다. shield_step은 이 import loop에서 읽지 않으며 미해결이지 unused가 아니다.

OriginalTacticalShieldCodec을 추가해 초기 scene/0340 요청/새 actor 진입에 해당 함선의0341을 공급한다. 기존 정적 table100을 공통 상수로 뽑아 next_time100→초기elapsed0, authored step0으로 연결했다. 정적 table wire는 불변이며 충전량100을 step으로 복사하지 않는다. 동적 서버 방어막 회복·phase 영속성은 아직 구현하지 않았다.

최초 bootstrap/actor 실패와 query fixture 수정 후 empty-branch count 실패를 확인했다. 신규11개 포함 전체388PASS; TCP actor batch에0341이 추가되어 진입·피해sequence2–9 뒤 query응답sequence10. TRX SHA256 97463016CB1DBD6CCF4755E60F49053B9AB841B92D812361BE88EE79506A1F51. 원본/item116 9구간·8명령·3label 확인 receipt shield-fill-layout-v70.json SHA256 C1674975EB2C33737219829E73B40E4E1BF9C85733908DDC64E930567951A4D2.

0341 누락은 서버/codec 범위에서 보완됐다. 다음은0337/UI 소비자와 전체 참가자 입장·퇴장·조회 연결 또는 shield_step의 실제 소비 경로다. 같은0341 parser/null-record 재조사 금지. native 모델/방어막 표시 성공으로 승격하지 않는다. DB/deploy/native/restart/resource 신규 검증0, 미배포·예약정책 미응답·전체goalactive 유지.

## 2026-09-06 — E065: 참가자 목록 경계와 동일 그리드 재생성 수정,392 PASS

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-065-roster-and-scene-rebuild-boundary.md.

0337 handler는+431AB4에0x259dword 복사 후004BDD33 cleanup으로 간다. 직접 buffer use는 writer1개이며0337만으로 모델/HUD refresh를 일으킨다는 근거가 없다. 전체 unused 주장은 금지. 실제 증분 진입은 E062의0323/0325/033B→0B0A import다. 반대로0B08→004BECE0은 인물 context 제거와 UI 이벤트 후 entity+5B8/+5B9를 설정한다.004BEDA4 lookup 후 null guard 없이 dereference하므로 임의 unit ID를 모든 observer에게 퇴장 통지하면 안 된다. Packed reader/maxcount는 아직 미회수.

조사 중 같은grid0F02 scene rebuild에서도 known actor ID가 남아 다음 공격의 진입이 생략되는 버그를 찾았다. ResetBattleObservationForSceneImport와 battle lease를 initial bootstrap/refresh에 연결해 구독 token/known ID를 갱신하고 옛 pending batch를 폐기한다. 공유 encounter 피해는 그대로다.2RED→2GREEN,추가2검증 포함전체392PASS; 재생성 뒤 actor 재진입과NPC25→50 유지 확인. Native 재현은 아님.

TRX SHA256 518A1F00CBA1EFC8C68ED18D1C18A367BA87CA37633F9E883C5855AB8118E735.3구간/5명령 확인 receipt roster-scene-boundary-v71.json SHA256209328C8D6DE13CC22D395F18D6513E702B01CB40601E50BC76F589B9064E933. 다음은 shared participant descriptor와 전체join/query projection,정확한0B08 wire/native scene-ready 경계다. 같은0337직접buffer scan 반복금지. 첫 공격 때만추가하는현재동작을전체입장완료로부르지말것. DB/deploy/native/restart/resource 신규검증0,미배포/미응답정책/전체goalactive유지.

## 2026-09-06 — E066: 서버·클라 동시 검증으로 전환, 현재 게스트 화면 검정

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-066-server-client-paired-checkpoint.md.

사용자가 “서버 처리랑 클라 확인을 동시에 해야지”라고 정정했다. 이후 서버만 확장하지 말고 명령 한 개마다 서버 처리→원본 화면 관찰을 함께 닫는다. 참가자 snapshot/초기 scene/조회 연결은 빌드 가능한 상태이며6RED→6GREEN, 전체398PASS. 전술 방어막 테스트 reflection 인자 변경1건을 고쳐 full397/1FAIL→398PASS. 기존 관찰자의 eager join/leave와 lifecycle 검증은 미완료이며 E066은 중간 체크포인트다.

현재runningVM1개는oracle-win11-hd-re.vmx. VMwarePID11704/window407570838, read-only guest query에서G7MTClientPID3644/Logh7.ServerPID388. @oai/sky는SetIsBorderRequired/0x80004002 실패, 같은호출반복안함. Orca1.4.190의computer-use로같은VMware창을restore/capture했고 창테두리는정상이나게스트영역은검다. 게임키/클릭/종료/재시작없음. 사용자에게 실제화면도검은지,정상이라면캡처제공을요청했다. 답전동일capture반복/옛좌표입력/화면실패만으로재시작금지.

화면 native-v72-vmware-black.png SHA2565FD71C42CE628023D32DCE1A068BF47B4DD45D2E041479381AF080ADE4C25CAF. 현재fullTRX SHA2569B0105DF3D35DEB53D36EFC0675A7E0A862D0C79AD0424B31CFB294725F3E669. 다음은화면구분→client/serverhash·session/listener현재검증→배포/run경계확정→실제장착무기1회발사·명중·피해를wire와함께검증. 현재미사일미장착,빔화면효과미관찰. Native OBSERVATION_BLOCKED/전체goalACTIVE,DB·배포·게임입력·리소스검증0.

## 2026-09-06 — E067: 게임 화면 확인, 서버·클라 쌍 검증 일부 완료

사용자가 현재 실행 중이라고 알려준 후 새 캡처에서 전술 조작 패널과 기함 정보 창을 확인했다. E066의 현재 검은 화면 상태는 해소되었으나 원인은 미확정. native-v73-game-visible.png SHA2564D2A1874EF704E5D23FAF68A4707ADA0F85743213AA65249D5992A3A9C6E599B. 패널 BEAM/GUN0 및 무기명 NO TABLE 관찰은 미장착/발사불가 증명이 아니다.

기존 단발 read-only probe로 G7PID3644의 run경로/item116해시/생성시각, 서버PID388의 prep경로/생성시각/listener47900, 클라이언트 두 함선 슬롯을 확인했다. 영수증 native-state-v73.json SHA2562AB67D13D82D25048262E8942E5EAF9CD1121D20FE42BF3C00717C3EACD87778. 서버 파일 해시·실제 TCP 연결은 이 probe 범위 밖이다.

추가 native-live-pair-v73 진단이 응답하지 않아 정확히 식별한 호스트 진단 pwsh36900만 종료했다. guest 진단의 staging/완료 여부 UNKNOWN, 복사 영수증 없음. 이 step명 재사용/가능한 guest출력 덮어쓰기/무작정 재조회 금지. VM·게임·서버 프로세스는 종료하지 않았다. E020 공격4회/E027 power drag 봉인 유지, 게임입력/재시작/배포/제품코드변경0. 빔·미사일 실제 효과 UNSEEN, 현재398PASS 소스는 계속 미배포. 자세한 경계와 다음 시작점은 work/20260904-warp-state-reverse/evidence/E-067-native-visible-paired-preflight.md. 전체goalACTIVE, 서버와 클라이언트를 한 검증 단위로 진행한다.

## 2026-09-06 — E068: 화면은 있으나 연결 종료, 원본 heartbeat 복구 및 진척도 계획

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-068-native-heartbeat-and-progress-plan.md.

v73 진단과 일치하는 게스트 프로세스 없음/결과 파일 없음 확인 후 재실행하지 않았다. 별도 단발 v74 진단은 직접 vmrun으로 성공. client3644와server388 경로·exe해시·생성시각, 서버DLL C3984534205193152E32EA05333AE836D8AD1E395B4C46094B3421C0B06FF91D(v21) 확인. netstat에는 두 서버LISTENING만 있고 클라TCP없음. native-connection-v74.json SHA2569DD825D15F760E7D3B79F83562BCAEF9C0A944071B45F13CBA293BB1A3AF7D0D.

보존한server-wire-disconnect-v74.jsonl(19,485,535bytes/31,567lines/SHA25647C1A7F160DAAAE4A3B922846029BB08423B1CC95D854FA04B161D5BF6D6A648)의 마지막은2026-09-05T12:34:56Z connection3 control0002/payload0→unexpected-control→Rejected/closed. 원본004AD93E의30000→00612570→00614BC0×1000→00615520 설정,00615550 밀리초타이머가 raw프레임00020002를 생성한다. 약8h20m주기와실제종료시간부합. 플레이어로그아웃으로추정하거나0002echo를추가하지말것.

NaturalAuthoritySession.ProcessAsync에서 활성handshake/lobby/session의 빈0002를 응답·암호sequence·참가자publication없이 소비하도록 수정했다. payload있는0002/terminalstate는기존거부유지.11case중7실제RED(실제TCPreset포함)→전체409PASS. TRX heartbeat-full-green-v74 SHA2562C7443862ED91886D8DC603ED04E899043B888865DBD834CDCD6B37FDA0148B0. 실제TCP는00020002두번뒤암호화seq1요청/서버seq1응답성공. 원본장시간재검증은미완료이며아직미배포다. v22publish는NETSDK1047(net10.0/win-x64 restoretarget없음)실패,출력디렉터리·패키지없음. E:환경restore후진행해야한다.

사용자 최신 요구: 유저↔유저/NPC↔유저/NPC↔NPC, 모든게임동작과자동NPC AI, 전략·전술명령실행과효과, 명령·제안전체과정,2D아트·스프라이트·3D모델정체/출력, 고장난시스템파악, 기능복구먼저·한글화나중, 관련도구충분히활용. 이후“진척도를어떻게확인할지계획”요청으로배포진행을멈추고 docs/superpowers/plans/2026-09-06-gameplay-progress-verification.md 작성. 기능×시나리오별 서버/실제클라/저장증거로완료판정하고테스트수·xref수를게임완료율로쓰지않는다. 전략원장97행의과거7PASS표시/90NOT_STARTED,리소스2295정적분류는현재전체기능완료율아님. 현재피해경로고정NPC/명령제안자기대상카드제한도현황에반영했다. 새게임입력·재시작·배포·AI구현0,전체goalACTIVE.

## 2026-09-06 — E069: 함선 위 라벨은 인물 display_name, v22 패키지 준비

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-069-tactical-overhead-name-and-v22-checkpoint.md.

사용자가 이름 위치를 함선 바로 위 라벨로 특정했다. Ghidra로 00419300의 raw0323 필드명과004C32A0 전술 import를 연결: parentage가 있으면 +B8 길이/+BA display_name을 entity+6BC로 복사한다(004C3C2C..59). 함명이 비어서 대체하는 분기가 아니다. 004F0260은 함선 투영좌표 위로 Y를 옮겨004F0516에서004C8020을 호출하고,004C807B/88은 entity+6BC를004E8AF0 텍스트 경로로 넘긴다. 원본 SHA BD19263C...의3구간 바이트 일치. 원본 정적분석 확인이며 item116 새실행/화면변경 검증은 아니다. 서버 flagship_name 전송 E040만으로 이 라벨은 바뀌지 않는다. 인물 display_name을 함명으로 전역 덮어쓰기 금지; 함명 라벨 요구는 클라 전용 표시 경로 변경과 범주/갱신/실기 검증이 필요하다. 이번 진단은 패치하지 않았다.

E068의 publish 실패는 E: win-x64 restore로 해결. v22 self-contained ZIP SHA B7816B9AB65C9B4828335552E4C021038EE9AEDE534723A1A1C5311A1FCD8DCA, DLL SHA16375E706B99895749D1D31F68AF9C4C4829EF5A4F7FB1631699F73F6CFE63E4; full409PASS/TRX SHA5B928889A57B56E2380B66B125C38C2450F12B0B4D0AD5AD25D812F60600EABF. 패키지만 준비, 미배포. 기존 client3644는 이미 없고server388/DB는 살아있다. 별도 exited-client preflight 성공 후 기존 clean-stop을1회 실행했으나PG_CTL_NOT_FOUND_IN_RUN_COPY로 processStops0/gameInputs0 실패. 같은 stop 반복금지. 정확한 소유 DB runtime/pg_ctl 확보 후 안전한 새run 경계를 잡아야한다. 사용자 라벨 질문으로 배포 중단, DB·서버 종료/새run/게임입력 없음. 전체goalACTIVE.

## 2026-09-06 — E070: v22 실제 배포, 자연 로그인과 전술 화면 재진입

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-070-v22-deployed-native-login-tactical-reentry.md.

E069의 missingpg_ctl을 검증된 PostgreSQL17.11 ZIP에서 별도db-tools-v76폴더로 복원. 정확한 oldserver388/DB8808 소유권 확인 후 서버종료와pg_ctl fast shutdown 성공, cluster=shut down/남은postgres0. 기존DB·로그 보존, 삭제0. 복사용 cleanpg_control SHA54851DD42DE0C339DDD7230ED0B6CC2AC7EABDE81D89C15519DD9898DA5457B4. v22ZIP의17migrations/catalog/DLL은소스와모두일치했다.

새run **20260906T030656Z-natural-l1-relogin-v1**, guestroot C:\Users\logh7-oracle\AppData\Local\Temp\logh7-l1\20260906T030656Z-natural-l1-relogin-v1. 실제client9508/HWND0x00000000030A0440/start03:08:37.0583111Z/item116hashAEF382...; server3420/v22DLL16375E...; 새DB는기존계정1/인물1/gridUnit2|2|39|101을복사했다. 한국어runtime/신규계정/probe flags없음. Prep의variant문구item114는install검증대상이며실제launchoverride/hash는item116이다.

초기VMware호스트캡처검정,게스트GDI는흰시작창. focus1회/제목줄클릭1회로변화없었고보이는File메뉴클릭1회후로그인화면이관찰됐다. 지연초기화의정확한원인은미확정. ID필드클릭후DPAPI자격증명1회38keyevents전송, 로비에LOGH7 v22 native verification공지와TCP Established확인. 게임시작1회→기존첫캐릭터1회선택.900ms후화면은picker였으나뒤wire에서session3/0200→SessionServerReady,0205/0F02/0300/0348/034A성공확인하여재클릭하지않았다. world-v76.png에서전술操艦패널과자기함선·인물명라벨관찰,BEAM20/GUN20/ENGINE20/WARP10/SENSOR10/NO TABLE남음.03:21:19Z TCP127.0.0.1:51998→127.0.0.2:47900 Established. 화면SHA597D7458DC01AE41C0EFFA30A114E9F061631F6DCC1702A52F0817BC1F62E7F8,wire86lines/비Success0/SHA C64B851A2B3EBEBA26D060DAF56A4211601323F64C38A5F023FEAAD19D9685E9. 공격·이동·전력변경은이번run에서0회,빛을빔발사증거로쓰지말것.

처음read-only진단1540이CPU를계속소모하여정확한path/start확인후그진단만종료했다. 이후raw client-startup-v76.json은Get-Content provider metadata직렬화로19.9MB로팽창: 다시전체출력금지,compact.json사용,새진단은문자열cast필수. x64.NET의WOW64module목록누락을D3D미로딩으로해석금지. 지금새run은실행중이며다시시작할이유없다. 다음은이run현재PID/hash/TCP/화면재확인후장착무기/목표를고정한한번의실제명령·효과검증. 원본장주기heartbeat/일반PvP/NPC자동AI/전체명령효과·영속성은미완료. 전체goalACTIVE.

## 2026-09-06 — E071: 실제 빔 사격을 막는 0311 무기 성능 0 데이터

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-071-native-beam-zero-arms-range-gate.md.

같은 v22 run/client9508/server3420의 실제 TCP·해시·HWND·pick ID 확인 후 자기함선→射撃→빔 선택. 화면 밖 NPC를 세 번의 서로 다른 줌 입력으로 화면 안에 넣고 (750,385) 적 목표를 정확히 한 번 클릭했다. 46프레임/6.702초 보존, wire0406 없음, 두 함선100→100,0426 buffer0. 선택/명령가능 상태는 정상이며 현재 빔 목표선택모드로 남아 있다. 같은 클릭 반복 금지.

원인은 서버 OriginalWorldBootstrapCodec의0310→0311/432바이트 전체0 응답과 연결된다. 원본004BACA6→world+3F5902,004C5140의27행×8short 정규화→004C73D0 조회→004C7820 사거리 구성→004F1180 목표필터. 실메모리 arms1 거리성능8칸은 모두0,6방향 끝값[0,0,1,0,1,1], 실제적거리20이라 sender004B4110 전 단계에서 제외된다. 원본파일3바이트구간 검증, static-chain와 live-gate 영수증 보존. 030D나0343으로 대체하지 말 것.

정정: 미사일 arms코드0만으로 미장착이라고 한 판단은 근거 부족. 원본은0도 유효 인덱스로 받고FF를 특별 처리한다. 현재 missile power/mask0은 별도 미완성 데이터이며 무기 정체 확인은 남았다. 다음은 실제0311 표의 출처/값 및 packed reader 회수→명시적 직렬화/테스트/서버 사거리 검증→같은 기능의 native 재검증이다. 임의100/사거리8로 채우고 원본 복원이라고 하지 말 것. 이번에는 제품코드/테스트/배포/DB/재시작 변경 없음, 빔 발사·피해 성공은 아직 UNSEEN, 전체goalACTIVE.

## 2026-09-06 — E072: 무기표 구현, 매뉴얼 실제 수치 확인, 임시 곡선 승인

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-072-static-arms-codec-manual-data-and-approved-curves.md.

00412EB0는 count/ID 없이27×8개의16비트 값을 읽고00412F90은hit[8]로 기록한다. signed MOVSX와원본/item1165구간씩 검증. OriginalStaticArmsTable의크기검증·불변복사·network직렬화 및bootstrap/암호화session주입 연결:11실제RED→420PASS. 사용자 “그렇게 할 것. 아, 매뉴얼에 데이터 있을텐데?”로 임시 전투 수치 승인. 매뉴얼을 이미지로 다시확인하여10월판PDF79/90에SS75/동맹787의실제빔파괴력48/64,속도·장갑·군수품등표 확인;4개기함/표준I행 manual-ship-combat-rows-v78.json에전사. 함선파괴력과0311명중값은다르며Brunhild에표준전함수치를씌우지않았다.

기본allzero를잡는2RED후27개AUTHORED_PLACEHOLDER거리곡선을기본0311에연결,전체422PASS. 무기수치원본복원·실제확률식복원주장금지;이전cadence/power/예약정책까지승인된것아님. 기존고정피해25는그대로이며서버range/hit시뮬레이션은아직연결안됨. E071보완:일반선택무기사거리끝은마지막유효칸+1로최대8,현재임시beam1은5라20거리적은표만바꿔도닿지않는다. 정상접근/방향/사선확인이필요하다.

v23ZIP E:/logh7-build/logh7-server-v23-static-arms.zip SHA608E7164CF897549BE3CD2E47AD45C05ACD4A095E515DE14DB688CF2BB34C6CD,DLL6E437F288562586E1B387F4AC2E39DCC568C7DFD9DFF9301DA730992E9422102. 테스트TRX3038F9ED664D7A752A239F88F316777225646F0B72A8052271F9362C62791F50. 사용자후속“실행중인게임과서버재시작해야하지않아?”에따라E073에서실제교체진행. 전체goalACTIVE;동일실패클릭/봉인입력반복금지.

## 2026-09-06 — E073: v23 실제 재시작과 원본 무기표·사거리 적용 확인

Controlling evidence: work/20260904-warp-state-reverse/evidence/E-073-v23-restarted-native-arms-import.md.

사용자명시재시작요청으로 oldclient9508에WM_CLOSE1회→server3420종료→DB2208pg_ctl fast shutdown 성공. shut down/남은runtime0,oldDB·로그삭제0. Cleanpg_control4824123033F018EFA993D1784E14F3BA73EC234BB7F62CC0C2B07436D73E6B29를새run으로복사,계정1/캐릭터1/gridUnit2|2|39|101유지.

현재run **20260906T042731Z-natural-l1-relogin-v1**,guestroot C:/Users/logh7-oracle/AppData/Local/Temp/logh7-l1/20260906T042731Z-natural-l1-relogin-v1. **client1996/HWND0x00000000047D0446/start04:29:45.1216633Z/item116AEF382...**, **server120/start04:29:40.41022Z/v23DLL6E437F...**. Prep의item114라벨은override전metadata이며실제hash는item116.

흰시작창→보이는File메뉴1회/빈클라영역클릭으로닫기1회→로그인화면. ID클릭/자격증명제출각1회,GameStart1회/기존인물1회선택으로전술재진입. lobby-v79는초기스플래시,world-v79는NOWLOADING이다;전술증거는world-ready-v79.png. 새v23공지와TCP확인. 이후자기함선→射撃→빔각1회선택.

ReadProcessMemory로27행216개hit값이배포곡선과모두일치,현재beam1=[65,65,55,40,20,0,0,0]. 실제선택사거리끝[0,0,5,0,5,5]로이전1에서5로바뀜. arms-selected-v79.json SHABD7018C60CB6C071E778E2419CE08344E2764C8ABDE6D8409256B756C3693C81,화면63B5807CCB0EE05668A053314598DB69670E9BD4CC425AD5F1AC8282B50FE676. wire실패0/0406요청0:발사·피해성공아님. 적거리20이고현재빔targetmode4/selection1로남아있다. 새로운재시작필요없음. 다음현재실행재확인→타겟선택안전취소→정상줌/이동/방향/사선→유효거리내실제사격1회. RawEscape는게임종료이므로취소용으로쓰지말것. 서버motion/hit/range권위/NPC AI/일반PvP등미완료,전체goalACTIVE.

## E074 — 정상 접근 확인, 사격 각도와 일반 함선 배정 미완료 (2026-09-06)

같은v23 run20260906T042731Z/client1996/server120에서 정상UI0400 1회. 시점이 평면을 옆에서 보아 처음 잡힌z16경유점은 확정하지 않고 취소; 오른쪽드래그로기울인후 목적지 render(6.663802,0,-0.0927124)로 실제중간좌표를거쳐도착. 적거리3.337. 원본 wire두번째좌표→renderZ이며004C4240은높이를0으로둔다. 디코더축교환버그아님.

현재trajectory0098229C는 current2/authorized1/elapsed0/active1, 마지막회전승인누락. 실제yaw는pick+18/entity+24; 초기guest-motion-read-v80의direction은rotationX(+20)이므로heading으로쓰지말것. 보정readerguest-route-yaw-v80와영수증보존.

Shoot→beam→enemy각1회후0406요청0/피해0. mask34의rangeends[0,0,5,0,5,5]에서현재yaw0.500532는sector1/end0; 최종요청yaw1.5673도정면sector0/end0이므로거리가짧아도불가. 가상의pi회전은sector4/end5지만실행하지않음. 현재uiState6 shooting target, unit2/적둘다100. 같은클릭재시도금지. 서버motion/0423/Stop아직미완성.

사용자추가요청: 적도왜브륀힐트이며우리도일반함선이어야한다. Fresh양쪽templateIndex0/modelFile0. 서버030B가Kind0/ModelFile0단일template,CreateTacticalEnemyCharacter가아군레코드의FlagshipType/Kind를상속하는원인확인. 진영별일반함선의독립배정·모델·무장·각도데이터연결을새작업우선순위로명시. 아직교체/배포하지않음. EM027='standard'라는과거명칭은임시라폐기;EM012/FM003의반복variant참조는후보일뿐SS75/787확정아님. 이름표row=modelID추정금지.

상세 work/20260904-warp-state-reverse/evidence/E-074-natural-approach-and-beam-arc-boundary.md. 이동/경로/사격48프레임/원시wire/양쪽PE7구간동일성영수증보존. 이번생산코드수정/빌드/재시작0,전체goalACTIVE. 다음일반함선모델식별과독립배정→매뉴얼능력/각도연결→수정패키지배포후실제양쪽모델검증;경로완료통지도해결할것.

## E075 — 일반 함선 독립 배정 구현, 179개 함선 이름/120 모델 슬롯 정리 (2026-09-06)

030B에kind0/model12와kind89/model1003후보를서빙하도록분리.0325아군은저장FlagshipKind보존,NPC는전장EnemyPower(2→0,3→89)로독립배정;0/1/4호환kind0유지. EM012/FM003은일반전함후보일뿐원본정체·화면확정아님. 능력치와mask34미변경. 전체426/426PASS, v24ZIP1BB11CD31C6EB182AC914CC1C716B15976DED27D0CAB4B3B16A197A54191EE7F/DLL3566E84E547EE3771669F714A8A7840BB888D366F412B14277FA2062723C02ED. 압축216파일/publish해시일치/17migration검사.

사용자 “각각 무슨함선인지 정리해놔” 요청에 docs/reference/2026-09-06-reverse-ship-identity-report.md와work/20260904-warp-state-reverse/evidence/ship-identity-index-v81.json작성. 원문179이름/79썸네일/120모델슬롯,348고유경로중247존재101미확보,해시변경0. 확정이름/번호일치썸네일후보/미확정3D모델을구분. iu000제국전함/iu089동맹전함/iu008브륀힐트/iu009바르바로사이미지확인. 이름row≠modelID, M/H/L=LOD.

아직재시작하지않았다. 현v23run20260906T042731Z client1996/server120/PG6520유지;05:35:36Zread-onlypreflight및v24zip만stage. guest-stop-v23-v81.ps1은로컬준비만했으며실행0. 다음identity재검사→preparehelperstage→정확한소유runtimecleanstop→새runDB보존copy/v24launch→자연로그인/실제전술양측모델확인. 상세 E-075-regular-hulls-and-ship-identity-index.md. 전체goalACTIVE,실제발사/함종능력치/경로완료/NPC AI미완료. 기존사격클릭재시도금지.

## E076 — v24 실제 모델 분리, 경로 승인 v25 구현, 전체 제작 우선 (2026-09-06)

사용자 “전체 제작을 최대한 빨리 시작” 지시. 모델 완전식별을 게임 구현의 선행조건으로 두지 않고 이동/회전→사격/피해/격침→NPC 자동행동→전략 실행효과/영속성 흐름으로 수렴. 미응답 예약정책을 원본 규칙으로 간주하지 않는다.

v23client1996/server120/DB6520 정확한소유정상종료,삭제0,원DB보존copy. **현재run20260906T054802Z-natural-l1-relogin-v1/client4128/HWND0x031A0392/start05:48:30.6335360Z/server9404/start05:48:25.8458931Z/v24DLL3566E84E547EE3771669F714A8A7840BB888D366F412B14277FA2062723C02ED**. 계정1/캐릭터1보존. 자연로그인/전술HUD 확인. native아군kind0/model12,적kind89/model1003. HullOblique-v82.png에서회색아군선체출력. 적확대외형/정확한원본함종대응은미확정;녹색마커더블클릭은적식별/카메라중앙화PASS아님. label은여전히인물명. 근거models-entry-v82.json/HullOblique-v82.png/v24-wire-v82.jsonl. 모델원장읽기보고서에상태추가.

E074최종회전cursor2/authorized1원인인0423미송신에실패테스트추가. ProcessTacticalMoveShipAsync에서033B뒤0423(route=waypointCount=1,서버24Hztime)를보내도록연결. 비음수범위밖route는원본004CA620에서최종segment승인,음수경로재구성안함. 선승인은NEW DESIGN,도착완료나원본서버타이밍복원아님. 현재즉시목적지저장/공유movementclock부재는여전히미완료. RED1FAIL/1PASS→GREEN2→full428PASS.

**v25미배포**: E:/logh7-build/logh7-server-v25-route-authorization.zip SHA5F7600F35542383F085A42ED27E5519375C43307F510AC1CB471C157C3BF82E5; DLL E8A829263275595A5B57B0066AFE3208CD801E49963A0373A0F487F602CE77D7. 상세 work/20260904-warp-state-reverse/evidence/E-076-v24-native-models-and-route-authorization.md. 다음현재v24identity/DBmaster재검사→v25archive검사/보존재시작→정상0400최종yaw/route완료native확인→사선내사격. 빔mask0x34=52이며정면은range0;E074동일사격반복금지/rawEscape금지. 전체goalACTIVE,실제사격/PvP/NPC AI/전략효과/저장아직미완료.

## E077 — 실제 이동 완료·사격·적 제거·전략 복귀 (2026-09-06)

**현재v25배포완료/run20260906T060947Z-natural-l1-relogin-v1/client3100/HWND0x038403DC/start06:10:18.2172844Z/server5964/start06:10:13.6213931Z/DLL E8A829263275595A5B57B0066AFE3208CD801E49963A0373A0F487F602CE77D7.** v24소유프로세스정상종료/원DB보존copy/계정1캐릭터1보존. 현재화면은전략HUD이며연결유지.

정상UI0400한번:render(−10,0,0)→중간좌표→(6.663802,0,−0.0927124),최종yaw−2.97969842. trajectory0098229C current3/count3/authorized2/active0으로E074최종회전막힘해소. 033B뒤0423 route1실제효과확인;서버시간운동전체완료아님.

유효측면사선에서실제0406총4회. native정상함수100→75→50→25;생존총수100유지후4회차serverdamage100/destroyed100/encounter-completedTrue. 적entity비활성·marker제거후06:27:06Z **원본전략HUD로복귀**. 임시4hit밸런스한전투루프증거이며원본피해계산/AI/PvP/보상/영속성완료아님. 첫사격160프레임보존/전체해시일치;시각검토055/056/059에서빔은미확인. 미사일/포장비0,발사미검증. copy실행중zip열기잠금실패는동일session89658종료확인후새추출폴더로처리했고캡처/공격재시작없음.

근거 work/20260904-warp-state-reverse/evidence/E-077-native-movement-damage-and-strategy-return.md, route-motion-v83.json, combat-terminal-wire-v83.jsonl, post-destruction-v83.png. wire0400=1/0406=4/Invalid0. 이번생산수정·테스트실행0(428PASS는직전E076). 다음이미끝난전투반복/재시작말고 **일반부대별공유전투상태+권위movementtick+NPC자동행동**을구현. 현재AuthorizeTargetCommand는임의비자기target을허용하나실제피해는고정EnemyDamage뿐인결함;이것을일반PvP/NPCvsNPC로확장해야함. 전략97개실행효과/워프항속/명령제안/전체리소스/저장범위유지,goalACTIVE.

## E078 — 플레이어 표적 피해·관찰자 수신·양 진영 전투 처리 (2026-09-06)

0405/0406의 고정 NPC 전용 피해 분기를 부대별 공유 피해로 확장했다. 같은 전장의 등록 플레이어에게 피해 적용, 자기/타인0325 재조회 유지, 없는 표적·우군·격침 표적 거부, 격침 플레이어 공격 거부를 구현했다. 적 플레이어가 남으면 NPC 격침만으로 종료하지 않으며 동맹은 아군 NPC를 죽일 필요가 없다. 기지 소유 조건은 유지한다.

관찰자가 공격자 또는 표적을 모르면 0B09/data/0B0A를 먼저 보낸다. 표적 import는 피격 전 수치를 고정해0426 차분을 유지한다. 자동 표적 선택은 요청1회의 선택일 뿐 NPC 자동 AI가 아니다. 최초8RED→8GREEN, 양진영/피격전import 보완3RED→전체 **438PASS/0FAIL/0skip**, 새 테스트10개. 인메모리 roster+암호화 session 검증이며 실제 두 클라이언트 PvP나 DB 저장 증거가 아니다.

**v26 미배포** E:/logh7-build/logh7-server-v26-player-combat.zip SHA1A1E53A677267B2B7790273410D39A49AEECFACED70B26048A0C7BE6D612A97D; DLL8EB59BC90F9470998E7B4B9673D5C0F066DD27BD5CC156EEA71F9EBEAC0FA127. 216파일 hash 일치/17migration. 최신 TRX player-combat-v84-verified.trx SHAD7C6A91236FF0DD181835AFD77E75FB96CA7CA7D7580204BEDD5FD77A182A5A2.

이번 native입력/재시작/DB쓰기0. 마지막 실제 관측은 E077 v25/run20260906T060947Z/client3100/server5964/전략HUD; 현재 생존 여부를 새로 확인한 것은 아니다. 피해는 프로세스/그리드 수명이고 임시100/+25 유지. 다음 보존 배포와 독립 native 사용자들의 실제 피해·격침 수신 검증→공유 판정에 NPC 자동행동 연결. NPCvsNPC/권위movementtick/사거리·확률·shield/전투재개·저장/전략효과는 미완료. E077 전투 재생으로 PvP를 주장하지 말 것. 상세 E-078-player-target-combat-and-observer-imports.md, goalACTIVE.

## E079 — 현재 연결 확인, 검은 VM 화면, 다중 계정 준비 제한 수정 (2026-09-06)

Guest 열거에서 client3100/server5964와 PostgreSQL이 존재한다. current-wire-v85.jsonl은07:03:38Z까지 connection3의0300/Success를 확인한다. 하지만 sky 캡처는 재조회 후에도0x80004002, 인증된 vmrun 캡처와 Orca 전경 캡처는 모두 게스트 영역이 검다. 검은 원인/현재HUD 미확정. 사용자에게 실제VM화면 확인 요청; native입력/재시작/DB쓰기0, v26미배포.

**현재 읽기 전용 진단이 아직 실행 중:** host exec session89134 / vmrunPID28936(start15:58:27KST) / guestPowerShell7776. guest-runtime-preflight-v85.ps1의 runtime-preflight-v85.json은 별도Exists에서 아직없음. 같은handle을 확인할것; 관찰 지연만으로 종료·재실행·VM재시작하지말것. 상세path/start/hash/socket preflight미완료이므로 기존PID기록만으로 새게임입력금지.

실제PvP준비 중 guest-prepare-fresh-run.ps1의계정1/인물1고정검사를 발견. ExpectedAccountRows/ExpectedCharacterRows(기본1/1)로 정확한 기대행수 대조와receipt기록을추가.2RED→7PASS(PS7/WindowsPS5.1). 실제파라미터블록+검사분기실행이고DB복사/다중계정생성검증아님. 사용자행누락/예상외행은여전히거부한다. 상세 E-079-native-black-screen-and-multiplayer-preflight.md. 다음화면/현재진단해결→보존배포/독립native참가자검증; 전체goalACTIVE.

**E079 종료상태 정정07:08:38Z:** 동일session89134가exit1/Guest program nonzero로종료. 후속receipt CopyFrom은파일없음. 새열거에서hostvmrun28936/guestPS7776없고client3100/server5964/DB는남음. 위“진단실행중”은이전관측이며현재pending진단없음. 결과파일없어실패단계미확정; 다음관측은실행/출력경계를보완해야하며같은스크립트무조건재실행·게임재시작금지. 현재화면검정에대한사용자응답대기.

## E080 — 관측 복구, v26 실제 배포·기존 캐릭터 전술 재진입 (2026-09-06)

새 단계별진단을RunInteractive/session1에서 실행해path/start/hash/DB조회성공. 원인미확정v85를그대로재실행하지않았다. 호스트캡처는검지만게스트session1의기존input-free GDI캡처는실제전략HUD를보임. 게임종료아님; **이관측경계는사용자조치없이해결**. sky0x80004002/호스트검정캡처반복말고guest-capture-desktop사용.

소유v25client3100/server5964/PG6112정상종료,삭제0/원DB·로그보존. cleanpg_control E6D9B1B6B8A87E2BE2581212B81D26213D633873786526F82D1BC98542A77846를새run으로복사. **현재v26배포완료: run20260906T071511Z-natural-l1-relogin-v1/client5008/HWND0x019D03C2/start07:16:04.2671678Z/server4156/start07:15:59.5871484Z/DLL8EB59BC90F9470998E7B4B9673D5C0F066DD27BD5CC156EEA71F9EBEAC0FA127**. 계정1/캐릭터1/grid2|2|39|101유지. 새PGmasterPID아직미확인;구6112재사용금지.

File메뉴→빈곳닫기→ID선택→보호된기존계정제출1회→v26공지확인→게임시작→기존캐릭터선택→실제전술HUD. native tacticalActive1/units2,아군kind0/model12·적kind89/model1003/각100. world-wire69처리/실패0/0406=0. **PvP실제검증아님**, 인물명라벨미수정, NPC가재등장하는것은전투상태process수명때문이며전투영속성미완료.

근거 E-080-v26-deployed-native-reentry-and-capture-recovery.md, world-ready-v86.png SHA98219289FD6B2F90FF2FB34A87968E7FAB0577E9A494185ED4210076E1F7092B, combat-entry-v86.json SHA9DB5468E4C52DFFC67FD3D13AE108641421733DF5DC88A37C2C00B63B9CDDEBC. 이번서버코드수정/테스트0(438PASS는E078),pendinghandle없음. 다음현재v26유지→독립두번째계정/캐릭터·원본다중실행조건확인→실제양측피해와관찰자import검증. credential-v86소비됨,재제출금지. 전체NPC AI/전략효과/공유motion/리소스/저장목표유지,goalACTIVE.

## E081 — 승인된 임시 NPC AI 로컬 구현, 원본 AI 위치는 미확정 (2026-09-06)

사용자 “임시 AI도 구현해야지”로 탐지→접근·선회→사격→재선택 임시 서버 AI 승인. 이어 “AI까지 서버 데이터였어?”에 **원본 AI가 서버 데이터/서버 코드였다는 증거는 없으며 아직 위치 미확정**이라고 정정했다. 원본 클라의 명령·피해 수신 경로와 우리 서버의 자동 판단 루프 부재를 혼동하지 말 것.

OriginalTacticalNpcController와 BattleRegistry.Npcs, 실제 호스트250ms 타이머를 연결했다. 같은 그리드의 생존 적·센서·기관/무기출력·임시무기표 사거리·기존6방향 마스크·3초 재사격 간격을 판정하며 사용자 요청 없이 진행한다. +25/4회 피해는 기존 공유 권위 사용. NPC 위치는 재조회/재진입 시 초기 배치로 돌아가지 않는다. 0400/033B/0423과0426을 기존 단일 연결 writer로 전달하고 미등록 전투원은 피격 전 수치로 import. 격침 NPC 행동/격침 사용자 이동 차단, 이탈 표적 재선택.

TDD 정책5RED→registry4RED→host/scene2RED, 접근 경계 정지1RED 및 격침 후 이동1RED 수정. 최종 **459PASS/0FAIL/0skip**, 새 AI21개. 실제 NaturalAuthorityServer 타이머+인메모리 roster에서 입력 없이 피해 발생 확인이며 **네이티브 AI 전투/PvP/DB 검증 아님**. TRX E:/logh7-build/test-results/npc-ai-v88/npc-ai-v88-final.trx SHA BA9504A2FE3084BACFAAB34C758B2CEF22F2A7F253CAB977AF2336C69977313A.

**미배포, 새 release 패키지도 아직 없음.** 이번 게스트 입력/재시작/DB변경0. 마지막 검증 runtime은 E080 v26/client5008/server4156이며 이번에 신원 재조회하지 않았다. 다음 새 패키지→현재 runtime/hash/PGmaster 확인→보존 재시작→자연 로그인→NPC 이동/사격과 아군 피해, 사용자 반격을 연속 화면·메모리·wire로 증명할 것. 과거 credential/launch 재사용 금지.

플레이어0400은 여전히 목적지 즉시 저장, 전체 공유motion 미완료. 플레이어 사거리·쿨다운/명중·shield/탄약/점령·패배·영속성/원본 AI 추적/다중 NPC 실제 장면/전략효과·제안/함명라벨/리소스 전체 매핑은 남아 있다. 상세 work/20260904-warp-state-reverse/evidence/E-081-approved-temporary-npc-ai-local-implementation.md, WI083. 전체 goalACTIVE.

## E082 — v27 배포, 실제 NPC 자동 공격·아군 격침, 사망 후 재진입 반복 발견 (2026-09-06)

v27 배포 완료. ZIP E:/logh7-build/logh7-server-v27-npc-ai.zip SHA2BC1666198C2BBE7CE5CADDFEAC82BA18DAFA1B58EFEE8CCE46B41B80B0A9186, DLL0B83FC5471A5C5AC4D019707529E119BDA2D4E89107F99F88A51F6E660038AF9.216파일hash일치/17migration/재검사459PASS. 이전v26client5008/server4156/PG7588만 정상 종료하고 DB·로그 보존, 삭제0.

**현재 run20260906T080624Z-natural-l1-relogin-v1/client460/HWND0x045E040A/start08:08:23.7079502Z/server3784/start08:08:17.4401967Z/PG9452/start08:08:14.7482160Z**.08:21:04Z현재path/start/hash/listener재확인. 원래 계정1/캐릭터1/grid2|2|39|101 유지. 기존portproxyPID2952는 건드리지 않았다.

자연 로그인1회→v27공지→게임시작→기존캐릭터선택. 이후 게임입력0/client0405·0406=0. NPC0x7F000001이 X10→−6로 접근·선회 후08:14:28.449/31.445/34.446/37.442Z에 사격, 서버피해25/50/75/100·최종destroy100. 실제클라 아군 normal100→75→50→25→0, remaining100→0, 폭발 후 own entity 비활성. NPC100/100. 파란 광선효과를2번째사격시점frames81/82에서 관측; 정확한 muzzle/resource동일성·미사일은 별개미확인. 접근 대부분NOW LOADING중 발생했으므로 화면상 연속이동을 확인했다고 하지 말 것.

140프레임(08:14:08.814→48.790Z)과 동시읽기전용 상태/hash전부검증. 실제영상 **work/20260904-warp-state-reverse/evidence/ai-combat-v89.mp4**(원본frames67–116/13.96초/합성없음). ai-observe-v89.json SHA90777D0CAC6178D072083141539B99036CFF509FB7CD5B071152273F85330286, ai-wire-final-v89.jsonl SHA9CEC57A449B75DBD1F0684AE5B18578BBCDCE6788216A807085CAC9830EBEB6F.90처리/실패0,16이동+4사격/20통지배치. **NPC→사용자만 실제 검증; 상호교전/PvP/native NPCvsNPC 미완료**.

**새 플레이 차단 결함:** 격침 뒤 클라이언트가0F02를6–8초마다 자동 요청. 서버는 encounter-completed=False/enemy-present=True로 죽은own unit을 재전달, native0/0 own entity가128프레임에서 재등장→133에서 또 비활성. 사용자/에이전트 재클릭이 아니다. 원본 개인 함대 격침→재편/기지/관전자/복귀 프로토콜부터 역추적할 것. NPC와 아군기지가 남은 전장 전체를 임의로 종료하거나035A 캠페인종료로 대체하지 말 것. 현재 재현 실행을 무조건 재시작하지 말 것.

다음 우선순위: 이 사망 후 재import루프의 실제 세션 재현테스트/원본복귀경로 수정→사용자 지속공격/중지의 공유타이머 연결→같은실행에서 반격 검증. 전체공유motion/사거리·명중·shield·탄약/전투영속성/원본AI위치/전략효과·제안/함명·리소스·모델 매핑은 유지. credential-v89/launch-v89 소비됨, 재사용금지. sky실패/호스트검정반복 없이guest session1캡처 사용. copy36112 종료 전 파일잠금/후속wire없음을 봤지만 같은handle완료 후 재검증했음; 현재pendinghandle없음. 상세 E-082-v27-native-autonomous-npc-combat-and-death-refresh-loop.md/WI084, goalACTIVE.


## E083 — 개인 격침 귀환 규칙과 캐릭터/장면 필드 경계 (2026-09-06)

**재import 루프는 아직 미수정.** 매뉴얼 인쇄51쪽: 기함 격침은 캐릭터 부상→귀환 행성 이동이며, 인쇄13쪽: 미설정이면 출신지. 일반 극소확률 사망/추후 하드코어 고확률은 사용자 신규 제안(NEW DESIGN), 확률 확정·영구 사망 적용0.

0F1A CommandSetReturnBase 수신은 time/id/base 3*u32, 00480CF0 parser/00480DD0 logger/004BD51F→004BC063의 12바이트 copy 확인. 단순 status ACK 금지. 실제 UI 송신 producer/ACK consumer는 아직 미회수. 00429730은 다른 큰 캐릭터 logger 내부라 SetReturnBase 전용 함수로 재추적 금지.

**offset 의미 정정:** expanded InformationCharacter+24=flagship ID지만 HUD+24=character ID, HUD+48=flagship ID. 004B5B80은 HUD+24를 반환하므로 무조건 기함 getter라 부르면 안 됨. return_base expanded+18→HUD+3C, spot+1C→+40, spot_owner+20→+44, state+06→+2A. HUD+318 embedded InformationUnit의 grid(+08)는HUD+320, base(+40)는HUD+358. 서버 EncodeCharacter는 이 위치/귀환 필드를0고정, 저장에도 부상/귀환 없음.

원본/item116 각8구간 검증(총16일치), manual hash도 일치. death-return-static-v90.json/dispatch-and-scene-v90.json/byte-verification-v90.json 및 verify-death-return-v90.ps1 보존. 0050D2A6의 자기entity없음→state1/fade,004B76E0 step0F→kind14/0F02→step10 playerInfojoin 확인. 004B68F0 world+35F35A가전술/전략분기,004C4170→004C45F0전략mode2는HUD+320grid사용. next: 부상enum/spot·출신지 공급·0317개인참여 관계→귀환저장/새장면session회귀→native배포. dead-own만삭제/전체전투강제종료/즉시부활로우회금지.

이번 서버 코드·서버 테스트·guest입력·재시작·DB쓰기0. 마지막 runtime 신원은 E082 08:21:04Z client460/server3784/PG9452이며 이번 새로 확인한 것은 아니다. constmsg lookup(118,36)은 CD catalog(group118 count7)와 불일치하므로 문구 추정 금지. 상세 work/20260904-warp-state-reverse/evidence/E-083-character-return-and-death-scene-data.md/WI085. 전체 goalACTIVE.


## E084 — 귀환 행성 설정 서버 구현과 실제 PostgreSQL 검증 (2026-09-06)

0F1A(time/id/base 3*u32) 설정을 선택 캐릭터 소유권·알려진 같은 진영 기지 검사→DB commit→전체 레코드 ACK로 연결했다. base0은 미설정. 0323 return_base와 새 세션 복원에 실제 저장값을 전달한다. 이는 설정 경로 구현이며 **격침 후 부상/자동 귀환/장면 전환은 아직 미구현, E082 재import 루프 미수정**이다. 영구 사망 적용0, 미배포.

migration0018_original_return_base.sql과 PostgresAccountStore.ReturnBase.cs 추가. account lock 아래 설정·domain_event·authority version/hash·요청 영수증을 원자 저장. 실제 격리 PG17.11에서 새 데이터소스 재조회, 과거 논리 A→B→replayA, 초기 no-op replay,8개 동시 중복, 타 계정 거부, 강제 실패 rollback, 기존 캐릭터 삭제/영수증 보존 확인. NPC의 viewer 귀환 설정 상속과 새 FK의 기존 삭제 방해를 각각 RED로 재현 후 수정.

검토 중 raw payload hash가 fresh A→B→A(time0)도 중복으로 오인함을 발견. 같은 연결의 새 순번과 새 연결의 같은 순번을2RED로 재현, per-session Guid+decoded.Sequence+payload+domain prefix로 교정. wire 변경 없음. 재접속 재인코딩 요청의 dedup 보장을 주장하지 않음. 읽기 전용 재검토 PASS(이 설정 기능만).

최종 **472PASS/0FAIL/0SKIP, 새13개**. E:/logh7-build/test-results/return-base-v91/return-base-reviewed-full-v91.trx SHA E4267CE858DFCE9D5F7ADC00079D215B287897C60BAEE4F713D704EAD16236D7. realPG 시험 cluster E:/logh7-build/return-base-pgdata-v91,localhost55439,lastmaster32452는18:10:59KST 정상 종료/shut down/checkpoint0/1A85DA8. 파일/시험데이터 보존. 첫 시작 wrapper60284는 pg_ctl 종료 후 후손까지 기다리는 Start-Process -Wait 문제로 그 wrapper만 종료했고 DB는 유지했다. 이후 PassThru.WaitForExit 사용. pending handle 없음.

게임 VM 입력/DB쓰기/재시작0. 실제 runtime은 E082가 마지막 관측, 이번 새 신원 확인 아님. E083의 부상enum/spot·출신지/0317 개인 참여 경계를 이어서 구현하고, migration18 포함 보존 배포 후 native 설정/격침 귀환 검증으로 합류할 것. 0F1A만 보려고 격침 재현 실행을 초기화하지 말 것. 상세 work/20260904-warp-state-reverse/evidence/E-084-return-base-preference-and-persistence.md, return-base-implementation-v91.json/WI086. 전체 goalACTIVE.


## E085 — 귀환 목적지 사전 점검과 임시 후방 행성 선택 (2026-09-06)

현재 소스와 보존 v27 패키지 catalog.json은 SHA ADEBF0384E05CA18986BFA78EC4ACDD08E1315EE803C8028DD2DAB57EDAC57B2로 동일. Base1/grid101/power2/camp0 하나뿐이며 전투 그리드 밖의 기지0, power3 귀환기지0. 기존 인접grid102는 정의되어 있지만 기지가 없다. 원본 출신지 공급도 미복원. 기존 인접그리드에 NEW_DESIGN 후방 귀환행성을 추가해 검증할지, 원본 출신지 데이터부터 복원할지 사용자에게 질문했고 아직 답변 미수신.

별도 injury state enum/치유 타이머의 존재나 필수성은 입증되지 않았으므로 추정 숫자 때문에 수리를 막거나 임의 추가하지 말 것. 같은 전투그리드 기지 내부로 귀환 가능한 원본 참여 규칙도 미확정; '귀환 전부 불가능'이라고 확대 해석하지 않는다. 새 행성/출신지/영구 사망 적용0. 서버코드/배포/VM입력/DB쓰기0, 실제 runtime 신원 새 확인 아님. E084 472PASS는 이전 검증. 다음 사용자 선택→목적지와 개인 위치/0317 join을 연결해 실제 격침 재진입 루프 수정. 상세 E-085-return-destination-content-preflight.md/return-destination-preflight-v92.json/WI087. 전체 goalACTIVE.

## E086 — 임시 귀환 행성 승인·추가와 비전투 장면 분리 (2026-09-06)

**사용자가 “임시 귀환 행성을 추가한다”로 승인했다. E085의 답변 대기는 해소됐다.** Base2/grid102/power2/camp0, 이름 帰還惑星（仮）, NEW_DESIGN을 데이터 파일에 추가했다. static/tactical/ownership/position을 같은 ID로 연결하고, 명시 template의 spawnEnemy=false로 적 자동 배치를 막았다. modelFile0은 기존 selector 재사용이며 정식 행성 모델 동일성/native 렌더 증거가 아니다. 출신지/기본 귀환지 강제 설정·power3 후방 기지·영구 사망 적용0.

NPC 없는 장면은0317=0/자기 unit·character만, 없는 NPC 공격/조회 거부. 기존 전장101은 계속 활성이다. 실제 적 플레이어가 오면 교전 가능하며 영구 안전 지역은 아니다. 없는 primaryNPC를 이미 import했다고 기록하던 문제1RED→수정. 검토에서 기존 quiet 관찰자의 전투 시작 누락 발견1RED→grid lease 안에서0317=1/0F1F=1을 한 번 통지하도록 수정. 뒤늦은 NPC도 AI 이벤트 전에 시작 통지와 선행 import를 보장하는1RED를 통과했다. 같은 장면 반복 통지/다른 그리드 전달 없음(서버 검사).

최종 **481PASS/0FAIL/0SKIP, E084 대비9개 추가**, 실제 격리 PostgreSQL 포함. TRX E:/logh7-build/test-results/return-planet-v93/return-planet-full-final-v93.trx SHA840529672C05157905A90131CD3FA5FDA26BD824B8E609E3AE06EEAFE63BEF83. 별도 읽기 전용 재검토 PASS(이번 단위만). 초기 전체14FAIL은 합성 fixture에 추가 template 참조가 남는 문제와 기존 한 행성/102전투 가정 교정으로 해소했고 production join 검증을 약화하지 않았다. 중간 activation-first는 subscription getter 재귀로 ABORTED이며4부분PASS를 성공으로 세지 않는다. owned testhost55728만 확인 후 종료, 부작용 없는 encounter getter로 수정. 최종 PGmaster61176은09:39:40Z 정상 종료/shut down/checkpoint0/1DAACE0, 데이터·로그 보존.

**미배포, 새 패키지 없음, E082 격침 뒤0F02 재import 루프와 자동 부상 귀환은 아직 미수정.** 이번 VM입력/재시작/DB쓰기0, live 신원 새 확인0. 다음 개인 귀환 위치·피해 원자 저장→flagship join 보존→목적지 scene→native 격침 검증. 단순 grid 이동은 encounter 피해를 지워 부활시키고 persisted grid restore가 다시 덮어쓸 수 있으니 둘을 함께 처리할 것. 원래 전장 전체 강제 종료/dead-own만 삭제/임의 injury enum·치유 타이머 금지. 다른 그리드0317 조회0의 기존 한계도 남는다. 이전 credential/launch/IDs 재사용 전 경계 재확인.

상세 work/20260904-warp-state-reverse/evidence/E-086-approved-return-planet-and-quiet-scene.md, return-planet-implementation-v93.json(10개 소스 SHA)/WI088. 전체 전략 명령 효과·제안, 지속 공격·권위motion, 영속성, 실제 PvP/NPCvsNPC, 함명 및 전체2D/3D/음향 리소스 역추적 목표 유지. goalACTIVE.

## E087 — 선택된 후방 기지로 개인 격침 귀환·손실 보존, v28 준비 (2026-09-06)

**로컬 구현·검증 완료/미배포/native 귀환 UNSEEN.** 자동0F02에서 격침된 자기 unit을 감지하고 명시 return_base가 같은 진영의 비전투 후방 기지이면 위치·기지·손실·격침ID·이벤트·authority version/hash를 함께 저장한다. migration19 damaged/destroyed/injury_return_id/request_hash 추가. commit 뒤에만 원래 관찰/참가를 해제하며 다른 참가자의 전투는 종료하지 않는다. 기함/캐릭터 join과100/100 격침 수치를 보존, 재조회/새 registry 재접속에도 피해가0으로 돌아가지 않는다. 치유나 대체 함선 생성은 없다.

**NEW_DESIGN 비참가자 어댑터:** 귀환한 캐릭터를 전투 roster에서 제외하고 자기0317만 전략 장면으로 공급, start/hit/tactical peer 통지를 보내지 않는다. 목적지에서 다른 플레이어가 싸워도 부상자가 다시 전술로 끌려들어가지 않는다. 원본 injury enum/spot 의미를 복원했다는 주장은 아니다. original004FEF90 전략 루프는0050D230 전술 own-entity 상실 검사와 별개이며, HUD context는 그대로 유지해야 한다.

검토에서 목적지 lock 미흡·나중에 적 진입·0404 격침 전후 우회·상충 replay를 재현해 수정했다. 두 grid를 숫자 순으로 잠갔어도 기존0B01이 이를 우회함을 **실제 암호화0B01 경쟁 테스트1RED**로 추가 발견: 전략 이동도 source/destination 잠금 안에서 저장과 참가자 등록을 마친다. 격침 직후 아직 return marker가 없는0B01도1RED→source 생존 검사로 차단. 모두최종 재검토 PASS(이번 단위).

최종 **490PASS/0FAIL/0SKIP, E086 대비9개 추가**, 실제 격리 PostgreSQL 포함. TRX E:/logh7-build/test-results/injury-return-v94/injury-return-terminal-final-v94.trx SHA7BE7D3FA6D8A46264F5DC06B761A00A00432CEB9094942D7722EE915496DFADF. PG는 새 합성schema에 migration19까지2회 적용,8중복/새datasource/타계정/상충replay/강제domain_event CHECK rollback 확인. testmaster50188은10:04:29Z 정상 종료/shut down/checkpoint0/29D3EC0. 시험 파일·로그 보존.

v28 **패키지만 준비**: E:/logh7-build/logh7-server-v28-injury-return.zip SHAB033D96A9AAA32B45DC335A74D174B33491E64F341569D5CF64E21C8F7A3AA3A; DLLB3FCD792779C605105CA74A05F66EE918C6E1613E8D4D54F7B749D93AC537BC1. self-contained win-x64 Release/218파일/19migration ZIP해시 전부일치. injury-return-package-v94.json에 전체목록.

**실제 실행10:04:34Z 재확인:** run20260906T080624Z/client460/start08:08:23.7079502Z/item116 SHA AEF3827602CD13A395618BDEF0F48F44BCB8ED60F4FA9C2017F2D2E128660F2F; server3784/start08:08:17.4401967Z/v27 DLL0B83FC5471A5C5AC4D019707529E119BDA2D4E89107F99F88A51F6E660038AF9; PG9452/start08:08:14.7482160Z/localhost55432. 서버202.8.80.179 및127.0.0.2:47900, 별도proxy2952미변경. boundary-current-v94.json SHA3F8CC4DA498E9C9FB1818BDE363968FBCF009BB60998FD9D928F859EF8C13E26. 새 게임입력/재시작/게임DB쓰기/화면확인0.

원본4영역 byte 대조는 전부일치. item116은전략루프/import/mode3영역동일하나0058EE70 HUD에서10개의 PUSH immediate가0x68→0x63으로 다르다(리소스그룹 인자). 이를 원본동일이라고 숨기지 않았다. verify-injury-strategy-v94.ps1의 기본 strict실행은그차이로실패하고 -ReportDifferences는비교결과만낸다. injury-strategy-static-v94.json 및 injury-strategy-byte-comparison-v94.json 보존, 이번 바이너리변경0.

**다음 실제 귀환검증 선행조건:** return_base=0이면출신지 데이터미복원으로 birthplace-unavailable이며 native검증이 되지 않는다. 시험캐릭터에 Base2를 명시적으로 선택/설정했다는 별도근거를 남긴 뒤 v28보존배포→자연로그인→실제NPC격침→개인전략장면/기지2/손실/반복0F02해소를 연속화면·wire·DB로 확인할 것. 임시Base2지정을 원본출신지로부르거나 현재v27에서이미귀환했다고말하지말것. 같은전장/적있는목적지귀환, 치유·재편, 격침즉시(현재0F02시점)와 그전서버중단손실영속성, 부분피해의전략이동보존, 전계정unitID/실제다중클라교전은아직미완료. 기존credential/launch/IDs재사용전경계재검사, 데이터삭제/전체전장강제종료/원본미확정수치승격금지.

상세 work/20260904-warp-state-reverse/evidence/E-087-selected-injury-return-and-loss-persistence.md, injury-return-implementation-v94.json(10개소스SHA)/WI089. 전체전략효과·제안/지속공격·motion/실제PvP·NPCvsNPC/함명/전체리소스추적목표유지, goalACTIVE.


## E088 — v28 배포·임시 귀환지 지정, 흰 게임 창에서 실제 검증 대기 (2026-09-06)

**v28 서버 및 19개 migration 배포, 시험캐릭터2의 return_base2 지정 완료. 실제 격침 귀환 UNSEEN.** 임시 Base2/grid102는 NEW_DESIGN이며 원본 출신지/실제0F1A 입력 근거가 아니다. 실제 PG setup 검사1RED→1GREEN, 전체491PASS/0SKIP. TRX E:/logh7-build/test-results/native-return-v95/native-return-setup-full-v95.trx SHA87024D36A8008BEA532A5A5E1F2F56712F15B505BCD333D850449B74EDF2E076.

기존 v27client460/server3784/PG9452 정상 종료 후 원본DB를 새 run20260906T102317Z-natural-l1-relogin-v1로 보존 복사. 삭제0/proxy2952 유지. 새server9776/start10:39:27.5854335Z/v28DLL B3FCD792779C605105CA74A05F66EE918C6E1613E8D4D54F7B749D93AC537BC1, PG3704/start10:39:25.9441340Z/55432, client3512/start10:43:39.1673439Z/HWND0x00000000003000D4/item116. 10:52:31Z 재확인. 원본pg_control SHA E6B942B830C12A499C122F101E382E7CA8064697E7328E1D25F0B12145BB3014 동일.

첫 prepare는 TEST_RETURN_BASE_SETUP에서 PS5가 UTF8 일본어catalog를 기본인코딩으로 읽어 실패했다(서버/DB는 이미 실행, 설정/클라 실행 전). 실패영수증 보존→default decode 실패/UTF8 성공 및 무설정 DB 상태 확인→별도 일회 resume-client-v95.ps1로 설정·클라 실행만 이어갔다. 추가 서버/DB 재시작0. v95c/복구스크립트 각각 읽기 전용 검토 PASS. 암호 stdin 전달/HBA finally 복원·reload, 복사본 DB 암호 CurrentUser DPAPI 저장, 평문 기록0.

**현재 화면은 흰 게임 창**(diagnostic-v95.png, SHA184F3F54F856A789BA7D2FFA3F7A5C4D05BBE013C1E36F7E85267CCB03DF898E). FRESH_RUN_PREINPUT_READY는 HWND 생존 검사일 뿐 로그인/게임화면 준비 증거가 아니다. runtime-db-v95-final.json SHAB6628989FE9880B949CE27804B31B1B42F9D1585585D5EC493591FF9C7161B47: unit|2|2|101|1|0|0|none,19migration/설정1/OriginalReturnBaseChanged1, injury-return event0, wire listener-ready뿐. 로그인/전투 미발생. 게임입력0.

사용자에게 게임창을 클릭해 시작/로그인 화면이 나오는지 확인 요청했다. 흰 화면 원인 미확정, Computer Use 인증 화면 자동조작 제한으로 로그인은 사용자에게 요청. 다음 fresh boundary→화면복원→로그인→실제NPC격침/전략귀환/Base2/grid102/손실/반복0F02해소 확인. setup과 launch/resume 재실행 금지. 기존 DB/새 runtime 보존. 조회 helper의 깊은 JSON 직렬화 지연은 Get-Content 메타데이터를 순수문자열로 바꾼 final reader에서 해소; 이전 reader5837 exit1, helper-stop 성공영수증 없음, 게임/서버/PG는 final조회에서 동일. pendingexec없음.

상세 work/20260904-warp-state-reverse/evidence/E-088-v28-deployment-and-explicit-test-return-base.md, WI090. 치유·재편/출신지/실제PvP·NPCvsNPC/전략효과·함명·전체리소스 역추적 유지, goalACTIVE.


## E089 — 흰 화면에서도 렌더 프레임은 진행, 표시·입력 경계로 조사 축소 (2026-09-06)

**흰 화면 미해소/로그인·귀환 UNSEEN.** 원본004013F0 idle67바이트를 원본PE(hash BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16)·Ghidra·현재item116 두 메모리 관찰과 대조해 전부일치했다. MainWnd.IsIconic=false, global007C1B4C=07B50048, +2A5F4=1, byte0075E740=1이므로 이 idle 허용 조건은 닫혀 있지 않다. renderObject07B50088/vtable0066BBC8, windowed1/active1/renderReady1/device07F367A0.11:04:33Z framecounter56→31(600ms,주기적reset)/fps68.84849. visible renderchild005E00D6 rect(3,46)-(647,530),parent003000D4.

005DC9E0은device TestCooperativeLevel→update/render→frame/FPS→005DD450이다. 005DD450은Present virtual+3C를호출하지만 HRESULT를저장하지않으므로 **프레임 진행≠Present/표시 성공**.005DB3B0의메뉴진입/종료는virtual+34 pause경로. 이전E070/E073의File메뉴후회복과관련될수있지만현재실행인과는미확정. MFCidle과별도005DC8F0loop혼동금지.

Computer Use 반환VM창407570838를선택해activate1/Ctrl+G1/Alt+Tab1시도. hostcapture는기존SetIsBorderRequired0x80004002실패,반복0. 매입력후accessibility재조회했으나 guest11:05:40Zforeground5196/흰pixels/TCP0는불변. host키의실제guest전달미입증,추가키반복중지. screenshotSHA184F3F54F856A789BA7D2FFA3F7A5C4D05BBE013C1E36F7E85267CCB03DF898E는E088과동일. 게스트UI직접조작/자격증명전송0,메모리쓰기/일시중지0.

11:09:02Z server/DB/client신원재확인: run102317/client3512/server9776/PG3704/v28동일,proxy2952유지,원본pg_control동일. unit|2|2|101|1|0|0|none,19migration/귀환설정1,wire listener-ready만. 이번서버코드/배포/재시작/DB쓰기0.491PASS는E088이전검사.

**다음 사용자 확인은 VM 내부 게임의 ファイル(F) 메뉴 열기/닫기 후 화면 갱신 여부.** 단순 창생존/렌더FPS만으로게임준비완료주장금지. 인증화면은Computer Use제한상사용자가로그인.원격키반복/추정좌표/무조건재시작/설정재실행금지.분석대상은실제Present결과·표시경계이지서버초기화가아니다. startup-static-v96.json SHA578CACF45DC162F795140DE9BFBA0BB03DE51296CB7BCBBB5A74B93EBA5FEEEC, startup-memory-v96b.json SHAB028AE2DB5976838C36AA82AA32732B6DD12560D14E41A56EE91273F91F9515A, verify-startup-idle-v96.ps1 재현가능. 상세E-089-startup-render-progress-and-white-display.md/WI091. goalACTIVE.


## E090 — 직접 로그인과 실제 NPC 격침 후 귀환 확인 (2026-09-06)

사용자의 “로그인도 너가 해야지. 이전엔 잘하더만.” 요청으로 기존 합성 시험 계정의 guest CurrentUser DPAPI 보관 자격증명을 사용해 직접 로그인했다. 평문 출력/기록0, credential submit1(38 key events), ID focus1/Game Start1/기존 캐릭터 선택1. E088/E089의 사용자 로그인 대기는 해소됐다. 첫 auth-preflight-v101은 실행 중 wire의 ReadAllLines 공유 위반으로 입력 전에 실패; 실패 영수증 보존 후 새 v101b의 Get-Content로 통과했다. credential-v101 consumed 영수증과 PID/HWND/path/start/hash/foreground 검증을 적용했고 재전송0.

현재 run20260906T102317Z-natural-l1-relogin-v1. v98에서 old client3512 부재 확인 후 client만7152로 재실행했으며 이번 v101 재시작0. client7152/start11:24:11.5802403Z/HWND0x0000000001E603B0/item116 SHA AEF3827602CD13A395618BDEF0F48F44BCB8ED60F4FA9C2017F2D2E128660F2F. server9776/start10:39:27.5854335Z/v28 DLL B3FCD792779C605105CA74A05F66EE918C6E1613E8D4D54F7B749D93AC537BC1, PG3704/start10:39:25.9441340Z/55432. 원본 v27 pg_control SHA E6B942B830C12A499C122F101E382E7CA8064697E7328E1D25F0B12145BB3014 동일, proxy2952 유지. setup SQL 재실행/수동 DB 변경/메모리 쓰기0.

**단일 실행 관찰 PASS: 로그인 → 캐릭터 진입 → NPC 4회 공격 → 자기 함선 격침 → 개인 전략 HUD/임시 기지2 귀환.** 11:54:57.0107318Z LobbyReady. 12:00:50.4541755Z 최초0F02. 60초 연속217프레임 RPM에서 자기unit2 normal/remaining100/100→75/100→50/100→25/100→0/0·destroyArmed1→자기entity 소거. 서버 fire는12:00:54.5051432Z/57.7454222Z/12:01:00.7467111Z/03.7446404Z, damaged25/50/75/100 및 destroyed100. frame152의 전술 HUD/폭발/광선과 frame216의 전략 HUD를 실제 확인했다. frame105는 메모리 전술 활성에도 NOW LOADING 화면이므로 메모리 활성만으로 화면 진입을 주장하지 않는다. 로딩 중 AI 시작 시점은 추가 점검 대상이다.

12:01:10.7521583Z 자동0F02 1회 후 전략 HUD 전환. 원래 전장의 enemy-present=True/encounter-completed=False는 전체 전투를 강제 종료하지 않은 상태. 12:02:52.9929773Z까지 추가0F02가 없어 기존6–8초 dead-own 재import 반복은 이번 약102초 구간에서 재현되지 않았다. 12:03:53.0643572Z fresh screenshot도 전략 HUD/연결 유지. NPC와 전투 수치는 승인된 authored-temporary-v1이지 원본 AI/수치 복원 완료가 아니다.

DB 12:01:28Z 및12:02:52Z 두 번 읽기 모두 unit|2|2|102|2|100|100|f4d4ff17-d97e-48b8-9474-ecf17f8b6462. 위치101/base1→102/base2, 손실100/100 보존, OriginalUnitInjuryReturned1/OriginalReturnBaseChanged1/설정 영수증1/19migration. 치유·대체 함선·재출격 미구현, 이번 native 재접속 검증0. 파란 구체를 정식 행성 모델로 판정하지 않았다. 전체 기능/PvP/NPCvsNPC 완료로 확대 금지.

증거: work/20260904-warp-state-reverse/evidence/credential-v101.json, login-wire-v101.jsonl, character-select-v101.json, return-observe-v101.json(SHA C0A73571E4C9BED96435EB81D53C6C65877EB5FB5798C8C861C761E28165D261), runtime-db-v101-stable.json(SHA A014F06A63EDB02637206FA4806F6E53E92C64D24DBD849BEA1D042798C84289), return-frame-v101-152.png, return-final-v101.png, return-stable-v101.png(SHA EBA5FFF6069D47BE9A3F5DD85961D5436641DBE5561E7FA2FDCBA5C523819441). 전체217프레임/ZIP은 current guest run의 return-frames-v101와 return-frames-v101.zip에 보존. 새 input-free/read-only 관찰 helper scripts도 보존. 이번 서버 코드 변경/새 테스트 실행0,491PASS는 E088 이전 검사.

다음은 현재 부상 귀환 상태를 보존하고 fresh runtime 확인 후 치유·재편·재출격의 원본 HUD/리소스→명령→데이터 경로 조사. 함명 라벨/모델 식별/전체 리소스 사용처/전략 효과·제안/지속 공격·권위motion/실제 PvP·NPCvsNPC 목표도 유지. 소비한 credential·character-select·return setup 재사용, 검증을 위한 기존 DB 초기화 금지. 로그인은 더 이상 사용자에게 넘길 blocker가 아니다. 이번 단위 보고 후 대기, 전체 goal ACTIVE/미완료.


## E091 — 재편성 원본 UI→0C02 구조 회수와 서버 코덱 (2026-09-06)

**코덱 구현/검사 완료, 재편성 효과·재출격 미연결/미배포.** 매뉴얼 인쇄43–44쪽(PDF44–45)을 렌더해 재편성은 부대↔부대창고 이동, 보충은 동일 타입 함선·승무원 재고를 사용한다는 규칙을 확인했다. 수리와 격침 취소를 혼동하거나 임의 무료함선을 생성하지 않았다. 원본 기함부상 치유시간/재출격 조건은 아직 확인하지 못했다.

005313F0에서 명령63→004B4FC0,00574BA0 TARGET_ORGANIZE 확인 경로→같은 sender. base는004B5BF0,outfit은004B5BA0에서 읽는다. 내부 selector101(0x65)→dispatcher(selector-1) index100→0C02. 004B4DA0는selector100 완전보급이고004B4E00는selector62의다른명령이므로 혼동하지 않는다(E007/E039의기존bias규칙재확인). 00584420은TARGET_ORGANIZE/SendOrganizeDataCommand를 구성하지만 실제 이미지리소스경로/loader종결은미추적.

로그00551DD0/길이00551860/쓰기00551A80/읽기00555EB0로 expanded0x310과 wire40+5*ships+5*troops를 구분했다. caps99/24, 함선unitNumber:i8/boatNumber:u16, 육전대unitNumber:i16/grade:u8, maxTroop/maxCrew:i32, supplies:u32. 시간/id/mode/PCP/MCP/base/outfit/kind도보존. OriginalReorganizationCodec.TryDecode/Encode는엄격type/길이/잘림/후행/배열상한검사,mode의권위적의미·client값채택·재고변경은하지않는다. 원장63을CODEC_TESTED_AUTHORITY_NOT_IMPLEMENTED_NATIVE_UNSEEN으로수정. 미구현0Cxx echo를실행효과라고부르지않는다.

원본/item116 각11개선택구간,총22개바이트대조모두일치. verify-reorganization-v102b.ps1과 reorganization-byte-comparison-v102b.json SHA9E343442BC8B34552194479B0181605C25E25B8E2CA228591D831E3E455F3F85. 신규7검사는5RED/2PASS→7PASS. 전체 **495PASS/0FAIL/3SKIP**(PostgreSQL필요3개이번미실행); E088491PASS/0SKIP를새검사로재사용하지않는다. TRX E:/logh7-build/test-results/maintenance-v102/maintenance-full-v102.trx SHA2AB0E1FC7050DA6846B1DC8C8CF9CB1C67430ED2CBDFCD6FAAA90B0035C4BFFC. 별도읽기전용검토PASS,Critical/Important0(코덱만).

12:23:04Z runtime read-only 새검사: client7152/start11:24:11.5802403Z/server9776/start10:39:27.5854335Z/PG3704/start10:39:25.9441340Z/v28동일. 귀환후grid102/base2/damaged100/destroyed100/returnID f4d4ff17-d97e-48b8-9474-ecf17f8b6462/귀환event1 유지. 원본v27pg_control동일/proxy2952유지. 이번게임입력·메모리쓰기·DB쓰기·재시작·배포·새화면캡처0. 이전E090화면을현재새화면이라고주장하지않는다.

다음은 TARGET_ORGANIZE preview/commit 목록공급과0C03/04 begin/end,004C6930/004C6A70의원본수량계산→부대창고/승무원/소유권/동시작업lock 구현→부상해제·기함재지정→실제재출격. 기존격침/귀환DB초기화,credential/설정/전투영수증재사용금지. 상세 work/20260904-warp-state-reverse/evidence/E-091-reorganization-codec-and-original-command-path.md 및 reorganization-static-v102.json SHA086132E7CF9A8E7451BBDD42A7501699B635B24332D9BA678E0C3B4280BC54E5. 전체전략효과·제안/PvP·NPCvsNPC/함명/전체리소스목표유지,goalACTIVE.


## E092 — 재편성 인원 규칙과 함선 보급 필드 하드코딩 해소 (2026-09-06)

**함종별 정적 logistics 입력 연결/검사 완료, 창고·치유·재출격 미구현/미배포.** OriginalStaticUnitShipLogistics(Price:u32, Resources/Cost/Term/Crew:u16)를 template의 선택 필드로 추가하고 항상0을 쓰던 다섯 writer를 교체했다. 생략하면 기존0 fixture 유지; 원본 수치·무료 재고·치유시간을 만들지 않았다. 이 코드는 인코더 연결이며 재편성의 실행 효과가 아니다.

원본004C6930/004C6A70은 signed delta를 보낸다. 규칙표00788398=(0,0),(1,1),(2,1),(-1,-1). category1은 승무원,2는 육전대(일본어 진단 문자열 확인). 004C6DD0 rule1은 승무원 합=param6, 육전대 합≤param7. -1은 가전송 전 미확정으로 거절. 0052FF2E는 MaxCrew(world+43DBDC)에 baseline+A780을 더해 UI+A980을 설정; MaxTroop(world+43DBD8)는UI+A984. MaxCrew를 최종 총수로 추정 송신하지 말것. 0C03/04는 logger 존재만 확인했고 활성 parser/consumer 미확정이므로 E091의 begin/end 송신 계획을 그대로 실행하지 말것.

실제030B packed reader는004109A0. 이전 테스트의00411630 주석은 잘못된 내부주소여서 이번 관련 주석 정정. 00410B50–00410BBF 및logger00411A60로필드순서검증. Ghidra 새logger의body_size1메타데이터는불완전하므로전체함수검증이라고하지않는다. 원본/item116 각9개선택영역총18바이트대조모두일치. logistics-byte-comparison-v103.json SHAE03F8B6307713FBF46686732D3FF8D988FE2AF46342AEE9CF038A921D4BACC62.

TDD3FAIL/1PASS→4PASS,전체499PASS/0FAIL/3PGSKIP. TRX E:/logh7-build/test-results/maintenance-v103/maintenance-full-v103.trx SHA88C38CCAA4DF484C8E7BEDE0CBD7E557E2E4AD52A00F755D64301A4ADA6A7566. 별도읽기전용리뷰PASS/Critical0/Important0(필드연결범위만). 현재12:42:58Z client7152/server9776/PG3704/v28/19migration동일,grid102/base2/손실100/100/귀환event1유지. runtime-db-v103-stable.json SHAAC29E060C588FFF999B6A6E446920265A13D6F69ED98ADD0B98AEED8D0F626BA. 게임입력/DB쓰기/재시작/배포/새화면0. 로그인대기없음.

다음은 부대·부대창고 조회 응답/소유권 연결→서버재고를 반영한 mode0 한도→원자적 mode1 차감→기함/치유/재출격이다. 실제재고없는데echo성공송신하거나현재격침/귀환DB초기화금지. 상세 work/20260904-warp-state-reverse/evidence/E-092-reorganization-crew-rules-and-logistics-fields.md, 정적자료 reorganization-logistics-static-v103.json SHA7742631E2C8CA66E478D9D7E28D0C185DFAA0321C688019EC78E6E7DE31973A6. 전체게임목표ACTIVE/미완료.


## E093 — 네 재편성 목록 공급자와 부대 결성 선행조건 (2026-09-06)

**0326/0327 warehouse 코덱 구현/검사 완료, 실제 조회 핸들러·재고 영속성·결성·재편성 효과·재출격은 미구현/미배포.** 재편성 화면00568380/005685B0은 기지창고(outfit0),부대창고(actualoutfit),부대편성,운송품을 각각 selector23/23/16/18→0326/0326/032E/0328로 조회한다. 004C5D50가네버퍼를합쳐row+10/+14/+18/+1C수량에둔다. kind/grade로합성하며troop staticcategory0x13은crew다. BoatNumber의물리정규화/손실의미는아직미확정.

OriginalWarehouseCodec: requestbody8(base/outfit:u32), responsebody26+5N+5M(base/outfit/index:u32,N:u8,shipkind:u16/unitNumber:u8/boatNumber:u16,M:u8,troopkind:u16/grade:u8/unitNumber:u16,supplies/food/mineral:u32),99/24한도. unsignedstock과0C02signeddelta분리. exact길이/type/count/null검사. 빈코덱응답가능≠실제없는창고를빈성공으로서빙. 원본writer0040C2D0/logger0040C320,reader0041A870/logger0041AFF0. 원본/item116각8개선택구간총16바이트대조일치. warehouse-byte-comparison-v104.json SHA8E2C82A3F01251E388042386E28123E7A79AB5789FE2EE3036AA33E6CE3629A8.

중요선행조건: 현재OriginalWorldEntryCodec.InformationUnit.outfit는항상0이고실제부대생성/소속저장없음. **部隊結成(command25)은전용TARGET_ORGANIZE/TARGET_SELECT_OUTFIT_TYPE→004B4E00→selector62→0903 CommandCreateOutfit.** generic17-entrytable에없다는이유로picker경로없다고한옛원장결론정정. command25원장status ORIGINAL_PATH_TRACED_AUTHORITY_NOT_IMPLEMENTED_NATIVE_UNSEEN/request0903. 실제결성과부대창고를만들지않고재편성만붙이면안됨.

TDD6FAIL/4PASS→10PASS,전체509PASS/0FAIL/3PGSKIP. TRX E:/logh7-build/test-results/warehouse-v104/warehouse-full-v104.trx SHAC59E9B97F051C2B92272B6CB39F7EDF7A8C235ADD78D310A5162BBEDAE948E9F. 별도읽기전용코덱리뷰PASS/Critical0/Important0. 현재13:01:32Z client7152/server9776/PG3704/v28/19migration동일,grid102/base2/손실100/100/귀환event1유지. runtime-db-v104-stable.json SHA65B6B22DFC15793FC874CD129224BCD634E41276FE353336F5EC54BCD3A98F94. 게임입력/메모리쓰기/DB쓰기/재시작/배포/새화면0.

**다음첫작업: 0903 serializer/response effects 및032F InformationOutfitParty→실제부대ID/소속·기지별공유재고·원자적결성/배분→0C02 preview/commit→재출격.** 추가필드/빈목록코덱만반복하며실제기능구현을대신하지말것. 현재부상/격침DB초기화,무료함선생성,소비한로그인/설정영수증재사용금지. 최초stock출처/NEW_DESIGN은분리하고기지재고를계정별로복제하지않는다. 상세 work/20260904-warp-state-reverse/evidence/E-093-warehouse-wire-and-outfit-prerequisite.md, warehouse-static-v104.json SHABDF779B4212C357D004BC58D98BD39132444E77F402489B189B676D9F283E505. 전체목표ACTIVE/미완료.


## E094 — 공유 창고 영속성과 원본0326 조회 연결 (2026-09-06)

**공유 재고 저장·원자적 이동·원본 조회 핸들러 구현/검사 완료, 미배포. 부대 결성·재편성 효과·재출격은 아직 미연결이다.** PostgresWarehouseStore와 migration0020은 (base,outfit) 전역 창고/독립 카운터, account+character grants, 요청 영수증을 저장한다. 계정별 재고 복제/프로덕션 stock seed0. 계정→전역 창고 키 순 lock으로 계정 간 경쟁/반대 방향 이동을 직렬화하며 양쪽 수량·버전·actor authority hash/event·중복 영수증을 단일 트랜잭션으로 커밋한다. 부족·overflow·99함종/24병력그룹 한도·권한을 검사하고 replay는 현재 재고를 되감지 않는다. 저장/grants/hash 정책은 NEW_DESIGN, 원본 초기 재고나 직책 규칙 복원 주장이 아니다.

NaturalAuthoritySession.Warehouse는 암호화0326→현재 인증계정/선택캐릭터→PostgresAccountStore→공유snapshot→0327로 연결됐다. 없음/무권한/저장실패를 빈성공으로 만들지 않는다. Index=0은 명시적 NEW_DESIGN 미확정 대용값이고 warehouseVersion과 혼동하지 않으며 native 소비는 UNSEEN이다. TransferAsync는 창고↔창고 primitive이지0C02의부대↔창고 효과가 아니므로 그대로 명령완료로 승격금지.

0903 writer0048DA80/reader0048FB80/length0048D860/logger004908F0,expanded324. BODY52+5N+5M/opcode포함payload54+5N+5M/application4바이트message-codeprefix포함58+5N+5M이며 transport/encryption overhead별도다. 생성ID expanded+308/body32+5(N+M)/payload34+5(N+M). 최초 “payload52” 혼용은 검산 후 정정. 004C5650→004C31F0는 outfitID keyedcache를 갱신하지만 unit.outfit 소속을 바꾸지 않는다. 0904/05는 outfit:u32 하나를 별도session필드에 저장하나 완료순서/소속효과미확정. maxTroop/Crew는 raw32bits, signed UI sentinel가능성 유지. create-outfit-static-v105.json SHA7DF7B3FCB6F463F9F9ED23724556AE4527474205DB9E0FF4927A7D2EC2DC9610.

실제격리PG:5RED→5GREEN→추가7GREEN,session3RED4PASS→7GREEN. 최종 실제PG+암호화세션 통합 포함 **전체527PASS/0FAIL/0SKIP**. 마지막재고 두계정경쟁,8동일재전송,역방향동시성,한도,권한,후반event강제실패전체rollback,새저장소/새세션조회와조회무변경검증 포함. 읽기전용독립리뷰 저장/조회각PASS/Critical0/Important0. TRX E:/logh7-build/test-results/warehouse-v105/warehouse-full-v105.trx SHAC395CF6A20C8733C8E0BFE519B6F50A0E7074836E5D9401EDDFBECE373BA1527. 로그인·native게임통합시험이 아니라 인증/worldfixture부터의 암호화 요청이다.

13:25:15Z 실제run client7152/server9776/guestPG3704/v28/19migration동일. unit2/grid102/base2/손실100/100/returnID f4d4ff17-d97e-48b8-9474-ecf17f8b6462/귀환event1/원본v27pg_control동일. runtime-db-v105-stable.json SHAE19BE07DB0C11EF98E104306281BE3C3CD924FAF09BB3C3AB9C8B00D69CF7A2D. 게임입력/실제DB쓰기/배포/재시작/새화면0. 호스트테스트PG9764(port55805,data E:/logh7-build/warehouse-pgdata-v105)만 신원검증 후13:27:07Z 정상종료/port부재/cluster shut down확인,파일삭제0·데이터/로그보존.

**다음첫작업: 032F InformationOutfitParty와 unit.outfit 갱신→실제부대ID/소속저장→결성/배분과 창고/부대재고 단일트랜잭션→0C02 preview/commit→기함/부상/재출격.** 기본stock/grants출처/정책을분리하고 실제게임DB초기화·무료함선·소비한credential재사용금지. 상세 work/20260904-warp-state-reverse/evidence/E-094-shared-warehouse-authority-and-query.md 및 warehouse-authority-verification-v105.json에 코드해시/검사경계/테스트클러스터보존기록. 이번은progress이며blocker아님. 전체전략·전술/PvP·NPCvsNPC/AI/함명/리소스목표ACTIVE/미완료.


## E095 — 다중 유저 유닛 ID 결함과 부대 편성 관계 분리 (2026-09-06)

**서버의 다중 계정/캐릭터 진입 결함 수정·검사 완료, 미배포.** 부대 소속 경로에서 기존 seed_original_minimal_grid_unit이 계정마다unit2만 만들고 두번째slot을 ONCONFLICT로 생략하는 반면 세션은unitID=characterID로 조회하는 결함을 발견했다. migration0021은 legacy ID를characterID로 맞추고 누락slot만기존authored초기상태로추가한다. 기존손실/기지/버전/귀환ID/hash/과거event·command보존,oldIDalias기록. 임의nonlegacy매핑/범위밖ID는거절. NEW_DESIGN player1..0x7EFFFFFF,0x7F000000+NPC예약; 기존NPC0x7F000001/2충돌은리뷰후수정했다. gateway중지후적용필수/살아있는클라에hotrenumber금지.

실제PG ID2RED→GREEN,두계정/세slot진입·두DB기반암호화세션PvP(피격대상25/공격자0)·legacy손실/history·잘못된매핑/예약IDrollback검사. 예약ID첫실패는OVERRIDING SYSTEM VALUE누락fixture오류라유효RED로세지않았고,정정후2RED1PASS확인. 원래unit2-bound인consumednative설정SQL은수정하지않고시험캐릭터를원래run처럼2로고정. injury시험은생성된unitID사용. 최종 **전체552PASS/0FAIL/0SKIP**(신규ID7/codec18), 독립리뷰최종PASS/Critical0/Important0. TRX E:/logh7-build/test-results/outfit-v106/outfit-full-green-v106.trx SHADDB4CE1A5E9C8AFEFB56E0A82E865CAF9BEB3E20C8A22BF650E7DD36E2CE12D0. 이는native두창PvP아님.

032E BODY9(outfit/base/mode),writer00464AC0/length0040C890/logger0040C8C0.032F reader0041CBA0/logger0041EAA0,expanded8B04,BODY39..35145/opcode+2/applicationmessagecodeprefix+6. 인물10/이름13u16,함선60/각unitIDs70,병력24,일반cargo3/병력cargo24,notTogether함선/병력별도. OriginalOutfitPartyCodec18RED→18PASS; unitNumber와unitIDscount별도/strictlength·count·rawUTF16보존. **권위032F핸들러/부대저장·0903결성·0C02효과미구현**.

**E094 다음단계 정정:** 부대인물·함종별유닛·캐릭터기함은별도관계이며0903cache만으로생성자현재기함을새부대에자동배속할근거없다. 단건0325→004C2C80(mode1)은preview슬롯만;activeself는004C2A80(character.flagship==unit.id)→mode0.0B0A일부분기에호출돼도formation통지로임의재사용금지.005686C0→004C5D50편집공급은일반ships/troops/supplies만,notTogether를합치지않는다.mode1관찰/정확한mode·notTogether기지판정미확정. outfit-party-static-v106.json SHAC2AFC30D6A8AF91460AEB39B167CB6CB6A11462F5A7A4EF7D5F4CC5C3A44B23D.

13:47:03Z client7152/server9776/guestPG3704/v28/19migration/grid102/base2/손실100/100/귀환event1유지. runtime-db-v106-stable.json SHA8D6BA7697AB7C5FFD78A91A54FD0E6DBFD4C3C32446BAA08A9CEB7D0AC8DEB9F. 게임입력/실제DB쓰기/배포/재시작/새화면0. hosttestPG52304/55805만13:48:14Z정상종료/데이터·로그·스키마보존/삭제0.

다음은현재격침·귀환DB보존하에새배포/실제다중계정진입·PvP검증,그리고부대인물·유닛관계저장→032F실제조회→0903/0C02원자효과→재출격이다. 기존데이터초기화/무료함선/빈성공/단독0325소속완료주장금지. 상세 work/20260904-warp-state-reverse/evidence/E-095-player-unit-identity-and-outfit-party.md 및 player-unit-identity-verification-v106.json. 이번progress/외부blocker없음,전체게임·전략/전술/AI·함명/리소스목표ACTIVE.


## E096 — v29 보존 배포 완료, 새 클라이언트 흰 화면으로 재로그인 미도달 (2026-09-06)

**v29 서버와 migration21 배포 PASS, 실제 재로그인 NOT_REACHED.** 현재 run20260906T140233Z-natural-l1-relogin-v1: server9640/start14:04:05.2831942Z, PG2524/start14:04:04.8083928Z, client5636/start14:05:34.5360172Z/HWND0x00000000040B0406. PG binary는 이전102317Z run을 읽기 전용 재사용하고 데이터는 새 run/postgres-data이다. 이전 client7152/server9776과 PG3704는 소유권 확인 후 각 한 번 종료; old DB/로그/DPAPI/영수증 모두 보존, 삭제0. proxy2952/127.0.0.1:47900 유지.

배포 script guest-rollforward-v29-v107b.ps1 SHA3B2639786456BAE891A514C9389AABF2C32EDAC858D0848FE8C970E423F2B5FE. 초기 v107 Stage만 실행하고 별도 사전 리뷰에서 PS5 Process.Handle 보존/pg_controldata English locale 두 문제 수정 후 새 v107b Stage→Stop→Start→Client 완료. 135634Z는 미사용 server stage만 남음. v29 ZIP SHACE0851D6E40A7998ED1F1CA73EC68C6A4A7A791311194B48E7FB4FE324512E9F, DLL SHA37CBE0C953623DD779CA9B74C3BF66D61700701EA71CDC92844A301BB11FF8B7.

source→copied→migration 후 gameplay state SHA d3f0100c3c9cbcdd591c369ae5bee6be0914aa2dfdc3afd2d7a5769a2b1f7779 모두 일치. account/character/unit/event/return-request 전체 정렬 JSONB를 DB 내부에서 해시했고 민감 필드 출력0. cold source pg_control SHAF4CD6F94D931CFF62A77366F8C381BDDF96696515256AD59E0436F6E7AC07C69 동일. 14:12:42Z 별도 read-only 확인: migrations21/unit2/grid102/base2/손실100·100/returnID f4d4ff17-d97e-48b8-9474-ecf17f8b6462/귀환event1,warehouse0,identity-alias0,SCRAM 유지. 비밀번호 회전·hba 변경·무료재고·귀환 설정 재실행0.

client 실행 전 self session1=console1/input desktop Default 확인. 그러나 14:05:57Z와14:09:01Z game content 흰 화면, foreground PID5196/notClient, TCP없음. Computer Use에서 host Ctrl+G/Alt+Tab 각1회 후 guest전달/foreground전환 입증 못함. element69 click은 coordinate input geometry is unavailable, 새 window state screenshot은 SetIsBorderRequired failed/0x80004002. 같은 입력 반복/PowerShell UI 우회0; 스킬의 동일 턴 UI 방식 혼용 제한으로 중단. **인증 제출0, wire listener-ready 한 줄뿐: E090 로그인 성공을 이번 성공으로 재사용하지 않는다.**

이번 publish/guest Stage/실제 배포/독립 사전 리뷰 PASS이지 새 전체 시험이 아니다. **552PASS/0FAIL/0SKIP는 E095 결과**. 실제 두 클라 PvP/032F 조회/0903·0C02 효과/재출격 미검증·미연결 경계 유지.

다음 첫 단계: VM 내부 게임 ファイル(F) 메뉴 한 번 열기/닫기로 화면 갱신 여부를 사용자 확인한 뒤 fresh PID/화면으로 로그인 경계를 재개한다. 확정 해결책으로 단정하지 않는다. 서버 배포/consumed 단계·credential 반복·DB초기화 금지. 상세 work/20260904-warp-state-reverse/evidence/E-096-v29-preserved-deployment-and-native-display.md, rollforward-verification-v107.json. runtime-db-v107.json SHA4E1563E4BEA764F809421118B8F2049F38ACB3B159163DBC8C976784A5764DAF; client-focus-v107.png SHADCFCDA5D43135B5BA2E38BC927BA2AADEB0FACC07FB2261C998DA677E46AD34E. 전체 목표 ACTIVE/미완료.


## E097 — NPC 조기 접근과 임포트/획득 반응 경계 수정 (2026-09-06)

**로컬 구현·전체 검사 PASS / 미배포 / 새 native 동작 UNSEEN.** 서버0F02에서 roster를 즉시 NPC표적으로 쓰던 경로를 분리했다. 해당grid의0F02 후 유효0348에 자기unitID가 포함돼야 NPC표적이 되며, 실제 허용된 공격도 보호를 해제한다. 빈/타인/NPC만/잘린0348·0300·034A는 활성화하지 않는다. 같은grid 재임포트/grid이동은 준비만 초기화하고 공유 피해·NPC pose 유지. 중복접속은 하나가준비되면같은unit을계속표적으로취급. NPCvsNPC/다른준비참가자전투는계속되고 살아있는로딩참가자는승리판정에서사라지지않는다. NEW_DESIGN 72tick/3초 반응은NPC생성시점이아닌실제표적획득·재획득부터계산.

원본004B68F0→004C32A0임포트→004B64C0구성 뒤004B6E00가최대600활성엔티티ID열거→selector0x2E→minus1/index0x2D→0348. 원본/item116 각6선택구간/총12바이트대조일치. verify-npc-import-v108.ps1 및 npc-import-byte-verification-v108.json SHA05AE3B2B9793F110F7FAA8D24876A413CBC0FAB415F28CEE409E2A480B99A2D8. **0348은원본Ready명령도화면페이드완료증명도아니다.** 정책을원본서버동작으로승격하지않는다.

E090원본기록시간대조정정:0F02 12:00:50.4541755Z,첫0348 53.8467854Z,첫fire54.5051432Z. 보존frame114(53.5323659Z)NOWLOADING,117(54.3715923Z)전술HUD+로딩아트페이드,118(54.6593673Z)전술HUD/로딩아트없음. 따라서로딩중접근은관찰했지만첫피해가순수NOWLOADING화면에서발생했다고단정하지않는다. PNG3개는과거frame회수이며새촬영아님. load-boundary-wire-v108.json SHADAB7A056E45E6BF85B8C2956284CF84259FCD5C5974B3A04AA141F2A5CC899EE.

검사 initial8RED1PASS→9GREEN,lateacquisition1RED12PASS. 독립리뷰가MoveGrid뒤구장면0348의목적지조기활성화를발견:실제0B01+MoverStore경로1RED→bootstrapgrid검사추가. 기존2NPC motionfixture는self0348전송후움직임을검사하도록갱신/기존효과assert유지. **최종실제격리PG포함566PASS/0FAIL/0SKIP**,별도리뷰최종PASS/Critical0/Important0. TRX E:/logh7-build/test-results/scene-import-v108/scene-import-full-green-v108.trx SHA43A8B9352D4838A0887B184F65ADBEF7C8A9017936238B476BA10D889726BC20. reviewed session SHA8B1A39F8EF906DCB75951C5DB92A3AD9699704226A52D9F3DFFDE140CCB7B94F. 원본wire에scene generation이없어같은grid의오래된poll완전구별/일반PvP로딩보호·악의적반복정책까지해결한것아님.

14:39:33.3006535Z fresh read-only: v29/server9640/client5636/guestPG2524/migration21/grid102/base2/손실100·100/귀환event1/warehouse0/clientTCP없음/wirelistener-ready유지. runtime-db-v108.json SHA86800F0E79DE486EDD3B5CE0217524F3B6DE807D4FE8D8604ED0100068D72EFD. 이번게임입력/실제DB쓰기/재시작/배포/새화면0. 호스트격리PG22820/55805만14:37:25.3505249Z정상종료/데이터·스키마·로그보존/삭제0.

다음은현재표시경계복구→재로그인→기존손실보존상태의검토된배포·별도건강한검증참가자로접근/첫사격화면+wire검증이다. 기존부상캐릭터를무료치유·리셋하지않는다.032F권위조회·0903결성·0C02효과·치유/재출격및전체전략·전술/PvP·NPCvsNPC/리소스목표미완료. 상세 work/20260904-warp-state-reverse/evidence/E-097-npc-scene-import-and-reaction-boundary.md 및 npc-scene-import-verification-v108.json. 이번progress/goalACTIVE.


## E098 — 기존 실행에서 원본 로그인 화면 표시 회복 (2026-09-06)

**현재 원본 로그인 화면 관찰 PASS / 인증·게임 재진입 NOT_REACHED.** Computer Use로현재목록의유일VMware창407570838을재선택하고activate_window1회→접근성element69포커스확인. 이후14:45:36.7295545Z input-free guest캡처에서흰내용이아닌원본ID/비밀번호/ログイン화면을관찰했다. 파일메뉴를열어화면을복구해달라는E096요청은이제불필요하다. 활성화가회복의직접원인인지/중간자연갱신·사용자행동여부는미입증.

run140233Z/client5636/HWND0x00000000040B0406/item116 SHA AEF3827602CD13A395618BDEF0F48F44BCB8ED60F4FA9C2017F2D2E128660F2F. guestforegroundPID5196/notClient,clientTCP없음. 로그인성공아님. 이번키/클릭/인증제출0,재시작/배포/DB쓰기0. Computer Use스킬의인증자동조작금지와동일턴PowerShell UI입력혼용금지로인증단계는중지했다. 다른입력경로로우회하지않았다.

증거 work/20260904-warp-state-reverse/evidence/client-activated-v109.png SHA F5DA7268032EDFA0C33350DC725BA14600E2BDB158FF0434616A30BA2871E09D,동명JSON,상세E-098-native-login-screen-restored.md. 서버/DB별도확인은E097의14:39:33Z가마지막이며새조회라고하지않는다. NPC개선566PASS/리뷰PASS는여전히미배포. 다음인증→기존귀환캐릭터재접속검증;격침/부상데이터초기화금지. 이번화면관찰로다음행동이바뀐progress,전체goalACTIVE.


## E099 — 자동 로그인 및 v29 귀환 캐릭터 재접속 PASS (2026-09-06)

사용자의 “로그인은 자동으로 할 수 있는데?” 지적에 따라 E090에서 성공한 **게스트 전용 입력 경로**를 현재 v29 실행에 맞춰 재검증·적용했다. 특정 Computer Use 경로의 제약을 모든 자동 로그인 불가능으로 확대했던 E098의 설명/사용자 로그인 대기는 잘못됐으며 해소됐다. 이번 턴에는 Computer Use JS 입력을 호출하지 않았고 다른 Windows 입력 방식과 혼용하지 않았다.

기존 run140233Z/client5636/start14:05:34.5360172Z/HWND0x00000000040B0406/server9640/start14:04:05.2831942Z/PG2524 동일. preflight14:53:06Z는 단일 클라이언트/path/start/hash/PG master와 data directory/서버 listener-only/Default input desktop와session1을 검증했다. 기존 protected 시험 계정 재사용, 비밀값 출력·기록0. ID초점 클릭1, credential 제출1(38 key events), GameStart1, 기존 첫캐릭터 선택1. 소비 영수증 재사용/재전송0. client/서버/PG 재시작·새계정생성·DB수동수정0.

**실제 새 실행에서 자동 로그인→로비→기존 캐릭터→귀환 전략 HUD PASS.** 14:54:10.4353245Z LobbyReady, 실제 로비 공지 LOGH7 v29 distinct player units 관찰. 캐릭터 선택 직후 캡처는 아직 picker였지만 재클릭하지 않았다. 이후 SessionServerReady/0205/0F02/시간응답 성공,14:56:47.0614378Z world-ready-v111.png에서 전략 그리드 HUD·帰還惑星（仮） 표기를 확인했다. 전술 전투나 새로운 귀환을 수행한 것이 아니다.

14:56:37.8301755Z DB read-only: unit|2|2|2|102|2|100|100|f4d4ff17-d97e-48b8-9474-ecf17f8b6462,21migration,OriginalReturnBaseChanged1/OriginalUnitInjuryReturned1,warehouse0,alias0,SCRAM 유지. 클라이언트127.0.0.1:52160→127.0.0.2:47900 Established. 이 실행의0F02는1회,frame-processed 실패0;격침 손실·귀환 위치가 새세션에도 보존됐다. 무상치유/새함선/재출격 증거로 확대 금지.

증거 work/20260904-warp-state-reverse/evidence/credential-v111.json SHA10453816523C027075F0649BC02CBF3E7975D00CBC780E4B431828D6FA59673F;runtime-db-v111.json SHA532AF523F2A6B7CED655F050C4E61AC6FF87467C43AD5B776E284BB6B26205ED;world-ready-v111.png SHA77745ABB3B3B6E0C416073FA2CA9110244CCA792CC428C8898546CFB26127015. scripts guest-auth-preflight-v111/guest-interactive-session-v111/guest-click-v111/guest-submit-credential-v111/guest-runtime-db-v111는 현재 실행 신원용이며 제출 영수증은 소비됨.

이번 새 테스트·배포0. E097 NPC임포트/반응 수정566PASS는 여전히 로컬 미배포다. 다음 실제남은기능은 부대·창고/재편성·치유/재출격 및 별도 건강한 참가자의 새 AI 검증이다. 현재원본로그인/표시blocker없음;기존귀환캐릭터상태보존하고부대상태를만들어실제플레이로이어갈것. 전체 전략·전술·AI·명령/제안·리소스/PvP·NPCvsNPC 목표ACTIVE/미완료.


## E100 — 기함 격침 후 구축함 복귀의 영속 저장 구현 (2026-09-07)

**LOCAL_STORAGE PASS / 세션 미연결 / 미배포 / 실제 재출격 UNSEEN.** E099 이후 로그인 보고만 반복한 직전 턴은 no-progress로 분류하고, 영구 부상 상태를 끝내는 실제 저장 코드를 구현했다. 로그인 성공을 다시 플레이 완료로 세지 않는다.

규칙 정정: 보존된 공식 update05의104행은 기함 격침 시 계급에 관계없이 구축함으로 복귀하며 상위 기함은 다시 구매한다고 명시한다. 이 자료는 E052에서 이미 찾았던 것이며 새 발견/새 다운로드가 아니다. 일반 부대 재편성·창고 보충을 개인 기함 복귀의 필수 선행조건으로 둔 E099 다음 순서는 수정한다. 원본 규칙과 NEW_DESIGN 저장 구조를 구분한다. source E:/logh7-greenfield/evidence/manual-variants/wayback/update05-20050418003249.utf8.txt SHA337D4CA847E0D4ED6D8DB41B20C1D42C7135675E140707F1704D7B22B4A1100A.

원인: IsRecoveringFromInjury는 InjuryReturnId 존재만으로 영구 이동 금지하고, encounter 피해는 unitId만으로 공유한다. 플래그만 제거하면 이전100/100피해가 새 기함에 복원될 수 있다. 또한 OriginalMoveGridAuthority.Transition은101→102만 허용하므로102귀환지에서의재출격경로도미구현이다.

이번코드: migration0022가 ship_generation(기존0)과 original_flagship_recovery 이력표를 추가하되 기존손실은치유하지않는다. RecoverOriginalFlagshipAsync는 account→unit/character잠금, 반환ID/버전/소유/완전손실검사 후 이전손실·귀환request hash를 보관하고 새세대/무손실활성함선/constmsg함종2진영→3,3진영→93/authority/event를 한 트랜잭션으로 저장한다. 유닛ID=characterID와사용자함명유지. 함종3/93은group85 구축함 CLASS행이지3D모델번호가아니다. 해당모델/실제성능·원본클라이언트버전별복귀적용은아직미검증. API호출부는아직없으며현재게임을무상치유한것아니다.

과거귀환/복귀재전송은이력hash/버전을검사하고현재상태만반환한다. 두번째손실뒤첫복귀요청을보내도새함선을치유하지않는다. Unsupported비군사진영은거절하며원본규칙을추측확장하지않는다. generation/request ledger는교체서버NEW_DESIGN이다.

검사: 기능미구현2RED(NotSupportedException)→2GREEN; 복귀후과거귀환재전송2RED(INJURY_RETURN_SOURCE_STALE)→2GREEN. 첫호스트PG시작에서포트옵션을누락해5432로떠연결거절이났으며해당출력은기능RED로세지않는다. 소유PID54528확인후정상종료하고55805로재시작했다. 첫구현의character.updated_at없는컬럼오류42703은실제스키마에맞춰제거했다. 최종실제격리PG포함 **568PASS/0FAIL/0SKIP**; initial동시8요청중1회지급/후속8재전송/재오픈/실패시archive·unit·character전체rollback/이력보존/2저장생명주기검사. 두번째손실fixture는저장층검사이며실제두번전투나안전한귀환경로증거가아니다.

TRX E:/logh7-build/test-results/recovery-v112/recovery-full-final-v112b.trx SHA749872D476A7BB01EB8EAC148AF4A2D788C8B1BC02E2997B705802C9DB9A4E85. 읽기전용review_recovery_v112 최종저장단위PASS/Critical0/Important0/Minor0. 중요연결주의: 기존MoveOriginalGridUnitAsync의과거replay결과는새ShipGeneration을기본0으로구성한다(PostgresAccountStore.cs 약1537행); 이를현재세대로오해하지말고현행state와분리해야한다.

호스트시험PG25300/start15:12:39.9320248Z를15:19:08.3564715Z정상종료,postmaster.pid없음/55805listener없음. DB데이터·시험schema·로그보존/삭제0. 이번guest입력/DB쓰기/배포/재시작0. 마지막실제게임관찰은E099이며이번새관찰로승격하지않는다. E097NPC수정도여전히미배포.

다음실제연결순서:
1. 안전한귀환장면경계에서복귀호출→현재character/함종재조회;0325/0358단독모델갱신으로완료주장금지.
2. ship_generation별encounter손실과오래된세션/participant갱신분리. 같은encounter.Id를여러생명주기귀환ID로재사용하면archive충돌하므로세대별lossID가필요하다.
3. 함종3/93의실제3D모델/정적능력과표시경로확인. 항속을단순목적지상수로되돌리지말고권위지속값으로연결하며102→101재출격경로구현.
4. 위연결후에만DB보존배포와원본클라+서버동시검증:귀환→구축함표시→워프→전투/재격침→재접속. migration만배포하거나DB수동손실초기화로통과하지않는다.

전체전략·전술명령/효과·NPC AI/PvP/NPCvsNPC/명령제안·함명/모델/전체리소스목표는ACTIVE/미완료. 상세해시·시험범위 work/20260904-warp-state-reverse/evidence/flagship-recovery-verification-v112.json.


## E101 — 복귀 저장을 장면 재생성에 연결하고 함선 세대별 전투 상태 분리 (2026-09-07)

**LOCAL_SESSION_AND_STORAGE PASS / 미배포 / native 구축함·재출격 UNSEEN.** E100은실제저장구현·PG검증진척이며이번에는0F02→귀환→구축함복귀호출→새장면projection을연결했다. 전체게임목표는ACTIVE/미완료다.

함선세대경계: registry의unit별최대generation으로오래된participant/NPC-ready정보를제외하며새owner종료후에도이전함선을되살리지않는다. encounter는같은세대관찰에서손실max병합,새세대에서만새손실로교체한다. 다른유닛/NPC·원래전장종결상태를리셋하지않는다. 새세대adoption은tacticalship/corps/임포트준비/관찰구독을비우고선택인물의현재함종을재조회한다. 옛장면의명령은재임포트전거절한다.

0F02에서귀환상태를적용한뒤친군·비전투귀환기지의grid lease하에RecoverOriginalFlagshipAsync를호출한다. 저장반환unit/세대/손실/위치를검사하고인물재조회후새gridCharacter로0325/0323/전술장면을재구성한다. 미지원시험store는기존부상상태유지. 원본0358단독갱신을쓰지않았다. 군사진영함종3/93은constmsg CLASS행일뿐모델번호/원본정적성능증명아니다. 실제미배포이므로현재실행중캐릭터에새함선을지급하지않았다.

반복격침: generation0은기존encounter.Id를그대로사용하여기존귀환ID와호환한다. 이후는 original-defeat/v2|encounterGuidN|unitId|generation UTF8의SHA256앞16바이트로서버내부defeatId를만든다(NEW_DESIGN). 같은전장두번째격침을첫귀환replay로오인하지않는다. 원래전장의죽은함선손실은다른grid복귀시에보존하며해당unit이새세대로그전장에재진입하면그unit만새손실로전환한다.

같은unitId여도observer KnownUnits가generation을비교하여0426등사건전에새인물/함선entry를전달한다. 자기함선세대가오래된observer에는새전투를발행하지않고,이미queue에있던batch도EncodeNotificationBatch에서버려cipher sequence를소비하지않는다.

검사: initial3RED→3GREEN,복귀호출/peer재임포트2RED→5GREEN,두번째lossID/옛observer2RED→7GREEN. 독립리뷰가발견한중요3건(restore와명령잠금사이의경합,다른접속의옛함종잔류,기존queue인코딩)을실제세마포어FIFO·2세션·batch시험으로3RED→10GREEN. 모든전술변경·전략이동/장면snapshot의mutation lease안에서generation을재확인한다. 0348기존내부lease를새외부snapshot lease와중첩해첫전체시험이교착되어해당호스트test handle64977만Ctrl+C취소했다. 원인확인후내부재취득제거/외부lease유지. 일시관찰timeout을완료로세지않았다.

**최종Logh7.Server.ProtocolTests 전체578PASS/0FAIL/0SKIP,실제격리PostgreSQL포함.** 실제PostgresAccountStore+NaturalAuthoritySession연결fixture(제국):세대2상태를시험SQL로101에배치→0205/0F02→encounter100/100→0F02→귀환/복귀→0325kind3/손실0,DBgeneration3/grid102,event3;refresh로추가지급없음. 시험SQL배치는아직미구현인102→101실제워프증거가아니다. 동맹저장시험은E100경계를유지하며동맹native복귀를증명하지않는다.

TRX E:/logh7-build/test-results/incarnation-v113/incarnation-full-final-v113.trx SHA75DB4565071C10605748549C92BF37C513B8DD5DEC73112E46AFFF060CA7E275. review_incarnation_v113 재검토LOCAL CODE ready/Critical0/Important0;minor였던송신sequence미소비assert도추가하고최종전체시험완료. 최종Session SHA21CF5B4788FAEC9CDC14658C10852634D8236EF7142F512DF0B9B773F1886511. 상세9개파일hash/검증범위 work/20260904-warp-state-reverse/evidence/ship-incarnation-verification-v113.json.

호스트시험PG3448/start15:32:39.3661731Z를15:40:07.5963005Z정상종료,postmaster.pid/55805listener없음. 데이터/schema/로그보존,삭제0. guest입력·실제DB쓰기·배포·재시작·새캡처0. 실제게임마지막검증은E099이며이번관찰로승격하지않는다.

다음은kind3/93→정적함선표→실제모델/능력→native구축함표시를확인하고,102→101복귀후출격경로와지속항속을구현한다. 현재OriginalMoveGridAuthority는101→102만허용하고항속은목적지기반상수다. E100의과거이동replay가generation0기본값을반환하는주의도유지(현재gateway는replay를거절하므로이번새세대로채택하지않음). 연결완료후migration22+E097NPC수정을기존격침/귀환DB보존배포하여클라화면과서버처리를같이검증한다. 전체전략·전술효과/명령제안·NPC AI/PvP/NPCvsNPC·전체리소스/함명/모델확인은여전히남아있다.


## E102 — 원본 MDX 76개 추출·비교 및 복귀 구축함 정적 모델 연결 (2026-09-07)

**LOCAL_CANDIDATE_MODELS_CONNECTED / 미배포 / native 모델표시 UNSEEN.** E101은세대별복귀세션구현진척이었다. 이번에는kind3/93에대응하는030B정적템플릿누락을확인하고수정했다. 기존서버는kind0/model12와kind89/model1003만보냈다. 원본004C46A0→004C5130은kind를그대로entity+8BC로넘기고,004F3D80은world+2C1A78+kind*2A8+20C에서modelFile을읽는다. 빈슬롯의값0을읽으면모델테이블0/GE EM001이선택되므로이름변경만으로복귀구축함이표시되지않는다.

원본함종이름표와모델표는별개다. Ghidra의.MDX로더는004DD6A0→005DE500→005E3C00(param3=1)→005E39E0→005F6230이며.MDS의005F5D70과혼동하지않는다. 파일에남은옛heap포인터는직접rebasing해쓸수없다.005F6230 count-walk와005F61D0의descriptor36/vertexcount*stride/indexcount*2/vertexmapcount*2를재현한mdx_geometry_v114.py로GE/FP중간LOD76개를추출했다. 전부EOF/222basegroups. 원본파일을수정하지않았다.

ship-geometry-v114b/{GE-0,GE-1,FP-0,FP-1}.png는각파일의첫비어있지않은base entry를무텍스처local좌표로투영한비교표다. 게임캡처·자식transform·애니메이션·정확한재질/전체함체native렌더가아니다. 초기v114의4개NoPrimaryGeometry는entry0이비어서발생한표시문제이며파서실패가아니었다. FM014는entry6이다. 두출력폴더모두보존.

원본iu003/iu093썸네일(기존sheet0)과비교하여 **VISUAL_CANDIDATE kind3→model18/GE EM018,kind93→model1014/FP FM014**를선택했다. EM018의쌍장축구조/전방부,FM014의작은분리형선수/중앙선체형상을근거로한검증용배정이며원본서버배정표확정이아니다. model1014는테이블64이며이름93/파일번호93대입이아니다. H/M/L6원본파일현재SHA대조일치. 원본구축함능력치미회수이므로기존승인임시능력치유지,정식balance주장금지. 기존0/89는보존하고030B에3/93템플릿추가(4records/439bytes). docs/reference/2026-09-06-reverse-ship-identity-report.md에최신후보/비교표를추가하고v23/v24현재표현은당시기록임을명시했다.

검증: Ghidra004C5130전체8/004F3D80전체155/005F6230선택시작52/005F61D0전체81bytes를원본BD19263…및item116AEF382…양쪽과대조,8선택검사일치. verify-model-loader-v114.ps1 및 model-loader-byte-verification-v114.json. MDX파서3RED→3GREEN;리뷰의empty-model.trailingBytes누락1RED→수정,variant/secondary인공fixture포함최종5PASS. 실제76개코퍼스는모두base분기이며비교표정체를자동확정하지않는다. review_destroyer_assets_v114가76원본해시/그룹/EOF독립대조0불일치,LOCAL후보연결/진단도구ready/Critical0/Important0;minor위항목수정완료.

서버missingkind2RED→6focusedGREEN;최초전체시험은기존2record길이assert1FAIL/579PASS였고기존능력assert를보존하여4record로갱신·모든추가record의count/power비영0검사추가했다. **최종실제격리PG포함ProtocolTests580PASS/0FAIL/0SKIP.** TRX E:/logh7-build/test-results/destroyer-v114/destroyer-full-final-v114.trx SHAF4674656E52D0BECEF885E6A2CE6411641A88F7E813BB745E803122B336D24DD. codecSHAD9C6C4AA25C8D0A20D9004D09F3E7B4215CB2316A73ED8EF192B0609F56C80F5. 상세source/LOD/9artifact SHA work/20260904-warp-state-reverse/evidence/destroyer-model-verification-v114.json.

호스트PG53512/start15:58:13.5188433Z→16:01:39.9083760Z정상종료,postmaster.pid/55805listener없음/삭제0. guest입력·실제DB쓰기·배포·재시작0. Codex의오프라인제국비교표open은queued였으며원본게임화면을열었다고하지않는다. 마지막native실행검증은E099. 이번워프/항속코드는변경하지않았으며 **다음즉시작업은102→101재출격경로+지속항속** 이다. 이후보존배포/migration22/E097NPC수정포함으로실제kind3/93→template→model/표시와귀환→출격→전투를서버·클라동시에확인한다. 전체전략·전술효과/AI/PvP/NPCvsNPC/명령제안/전체리소스목표ACTIVE/미완료.

## E103 — 지속 항속·왕복 워프·재접속 연결 (v115, 2026-09-07 KST)

**LOCAL_STORAGE_SESSION PASS / 미배포 / 실제 클라이언트 워프·재출격 UNSEEN.** 직전 로그인 기록 설명은 no-progress이며 이번에는 실제 저장·세션 코드를 변경했다. E102의 다음 작업이었던 102→101을 포함하는 authored101↔102 왕복을 연결했다. 경로·초기항속10·차감1·새 기함항속10은 NEW_DESIGN이며 원본 밸런스 회수로 주장하지 않는다.

Migration0023은 original_grid_unit.cruising(real)을 저장한다. 기존102행은 이전0325표시값9, 나머지는10으로 초기화하며 기존 손실·기지·위치·함선세대는 바꾸지 않는다. 과거 소모 이력을 복원한 것이 아니다. 새 이동 결과의 cruising/generation/damaged/destroyed를 명령 원장에 함께 기록하고 재전송 시 그대로 반환한다. 이동·항속·이벤트·버전/hash는 계정/유닛 잠금 아래 한 트랜잭션이다. 음수/NaN/Infinity 및 항속부족은 거부한다. 부상귀환은 남은 항속을 유지하고, 실제 fallback issuance에서만 NEW_DESIGN10을 부여한다.

NaturalAuthoritySession은 payload만으로 영구 중복 처리하던 fingerprint를 연결scope+inner sequence+payload로 바꿨다. 원본 wire에 cross-connection intent token이 관찰됐다는 뜻이 아니다. 그리드 lease 뒤 DB행 재검증, 동일 함선세대 확인, ApplyPersistedGridUnit로 이동 결과 적용, 0B07/0325에 실제 소모 후 항속 연결. 기존 grid102→9/그외→10 투영은 실제 세션에서 제거했다. OriginalWorldEntryCodec.EncodeUnit의 구형 fixture helper는 남아 있으나 실제 authoritative 세션은 CurrentPlayerInformationUnit/EncodeUnits를 사용한다.

**실제 격리 PostgreSQL 포함 최종586PASS/0FAIL/0SKIP.** E:/logh7-build/test-results/cruising-v115/cruising-upgrade-full-v115.trx SHA AF5E47AF55782F217D01E8DFF7DF1FC2BC0E6B0C2E4A03DB06E47148DE2D2334. 8동시 중복요청1회차감, 10회왕복10→0·11회거부, 이벤트실패rollback, 손실25/10·세대3 replay일치, DB재접속, 22→23업그레이드에서 기존행JSON필드전체보존·migration재실행항속보존을 검증했다. 실제 암호화0B01 세션102→101→102→101의0B07/자기0325는9→8→7, 새datasource/session의0F02도7, 이후 격침→귀환→새구축함generation3/cruising10을 검증했다. 이전 SQL로 출발위치만 조작하던 recovery session 테스트를 실제0B01로 대체했다.

RED 영수증: storage9예상/10실제, fresh fallback10예상/7실제, historical replay 7/8손실불일치. 최초 session RED는 잘못된 마지막NPC record를 읽어 근거에서 제외하고 자기unitID+0325offset46으로 수정했다. 이후 구형 grid기반 투영을 잠시 복원한 mutation은 정확히 자기항속9예상/10실제로 실패했으며 수정코드를 복원하고 최종전체재검증했다. review_cruising_v115의 minor replay손실누락도 재현→수정→재검토하여 Critical/Important/Minor0. 추가 upgrade test는 리뷰 이후이며 production변경은 없다.

배포용 **E:/logh7-build/v30-cruising-v115.zip**, SHA1F62A82EE79FED9ED704EF614536CB0947F50136C54D4AFEAB0DC70136F17E98. publish E:/logh7-build/publish-v30-cruising-v115 (Release/win-x64/self-contained, migrations23). DLL1138142128513185EB160A03F21F1C67C8C385CABEE638EE794F07EF5BDF78C8, EXEC525060083F0F0CF7860D482B3C16E3E392B29BF9CFB5678DAB7551BFE3F99A4. Migration0023 SHA7F2867D140BDBE2554944CF5C97C98A1CB7E90A57C0280B83BBEAE3AA1FA8B64. 전체 영수증 work/20260904-warp-state-reverse/evidence/cruising-verification-v115.json.

**실제 상태 새 검증:** 2026-09-06T16:22:24.3975909Z guest-runtime-db-v115에서 v29/server9640/start14:04:05.2831942Z, PG2524/start14:04:04.8083928Z, client5636/start14:05:34.5360172Z 경로·시작시각·DLL/클라이언트hash·data directory·listener 소유를 재확인했다. 현재도 migration21/unit2 grid102/base2 loss100/100/기존injuryID, SCRAM. proxy2952 그대로. 새 capture before-cruising-v115.png는 검은 화면이므로 전략/전술 HUD를 새로 관찰했다고 하지 않는다. 자동 로그인 가능은 E099에서 이미 해결됐으며 사용자에게 로그인을 넘기지 않는다. 이번 guest입력0/DB쓰기0/배포0/재시작0. 호스트 시험PG60872/start16:15:27.3909779Z→16:27:26.5311926Z 정상종료, postmaster.pid/55805listener 없음. 데이터/schema/log 삭제0.

**즉시 다음:** 이미 만든 v30 zip을 사용해 새로운 run에 보존 roll-forward 준비→정확한 기존 client/server/PG 및 상태hash 재확인→기존클러스터 clean stop/cold copy→copyhash 확인→v30 시작/migration22+23 보존검증. E097NPC/기함복귀/세대차단/3·93 model/이번 항속 변경을 모두 포함한다. 기존 script guest-rollforward-v29-v107b.ps1은 구조 참고만 하고 소비된 stage/stop/start/client 영수증·credential-v111 제출은 재사용하지 않는다. 신선한실행신원과 영수증으로 자동 로그인→실제 구축함표시→102→101워프/항속→전투/귀환을 서버·클라·DB 동시에 관찰한다. 수동 heal/teleport/계정reset 금지. 전체 전략/전술/AI/PvP/NPCvsNPC/명령제안/전체리소스 목표 ACTIVE, 미완료.

## E104 — v30 보존 배포·자동 로그인·실제 기함복귀, HUD 항속 불일치 발견 (v116, 2026-09-07 KST)

**진척: 배포 및 실제 클라이언트 요청으로 기함 복귀 확인. 전체 플레이 완료 아님.** E103 zip을 배포했다. 처음 부모가 Start를 -Interactive 없이 호출한 오류로 서버/PG가 session0에서 시작됐다. Stage/clean Stop/cold copy/migration23의 기존 상태 해시 d3f0100c3c9cbcdd591c369ae5bee6be0914aa2dfdc3afd2d7a5769a2b1f7779 일치는 확인됐으나 대화형 Client guard에서 PROCESS_IDENTITY_CHANGED, 클라이언트생성0으로 중단했다. session1에서는 session0 서버Path/StartTime이 읽히지 않았다. PG5400은16:34:49Z child2564 exception0xC0000142 후 실제 종료; 프로세스/55432/postmaster.pid 부재를 확인했다. pg_control은 in production이므로 로그의 shutdown 문자열을 clean shutdown으로 오인하지 않는다.

복구 전 검토에서 b의 기존 wire FileMode.CreateNew 충돌/로그overwrite/Client session guard 누락을 수정했고 b실행0. c Stop은 DATABASE_NOT_SHUT_DOWN으로 변경없이실패. d는JS replace replacement문자열의 $' 확장으로 깨진 스크립트라 parser실패/실행0, **절대 실행금지**. 올바른 literal callback 편집의 e는PS5.1검사·review_rollforward_v116 Critical/Important0. 기존 v29 clean source를 보존하고 중단된 v30 DB를 postgres-data-pre-session-repair-v116e에 추가복사한 뒤 정확히 소유된 session0서버5252만 종료했다. 새 **session1 PG6996/server6412**가 기존업그레이드DB를 WAL복구했고 전후statehash일치. 회복로그에서 redo/checkpoint/ready 확인,16:57:45Z 12분이상현재생존·실제SELECT검증. 바탕화면세션변경으로 재현구간이 해소됐지만DLL초기화실패의내부원인을확정한것은아니다.

**현재 RUN: C:\Users\logh7-oracle\AppData\Local\Temp\logh7-l1\20260906T163027Z-natural-l1-relogin-v1**.
server6412/start2026-09-06T16:45:46.9822816Z/session1, DLL1138142128513185EB160A03F21F1C67C8C385CABEE638EE794F07EF5BDF78C8, wire **server-wire-v116e.jsonl**/stdout server-v116e.stdout/stderr server-v116e.stderr.
PG6996/start16:45:44.7860841Z/session1/55432/data 현재run postgres-data, binary는이전102317Zrun의postgresql/pgsql/bin.
client540/start16:46:09.3613798Z/session1/HWND0x000000000133044A/item116 SHA AEF3827602CD13A395618BDEF0F48F44BCB8ED60F4FA9C2017F2D2E128660F2F.
proxy2952/127.0.0.1:47900 그대로. 이전140233Zrun source pg_control SHA080036A7CBC4C0D2D11C2C0BDD17501DA9E42CEB70CD0B71F2F6FCD54ED18777 및DB 보존. 삭제0,수동heal/teleport0.

VMware Capture는검정이지만게스트CopyFromScreen은흰650×533시작창을보였다. focus API1회로변화없음. 핸드오프E076/E079의이미알려진동작을다시찾아 **실제보이는File메뉴(30,35)1회→로그인화면표시→메뉴닫기(605,502)1회** 확인했다. 렌더초기화근본원인미확정,다시추측클릭/포커스루프금지. ID(324,331)1회→새credential-v116e 영수증으로보호시험계정38keyevents1회→실제로비(v30공지)→GameStart(125,192)1회→기존캐릭터(650,307)1회. 모든좌표는해당새캡처기준이며다음run재사용금지. rawEscape는종료일수있어사용하지않았다. 자동로그인을다시사용자에게넘기지않는다.

**실제 world요청 0F02 → OriginalFlagshipRecovered(version27) → DB state PASS.** 16:57:45.9052497Z unit|2|2|2|102|2|0|0|none|1|10|3; migration23/recoveries1/warehouse0/alias0/SCRAM. 이벤트에이전damage100/destroyed100/generation0/cruise9/returnId f4d4ff17-d97e-48b8-9474-ecf17f8b6462가보존되고새kind3/type0/generation1/cruise10이기록됐다. login후clientTCP127.0.0.1:52180→127.0.0.2:47900 Established,worldwire0F02및시간응답Success. 이것은새native입력으로발생한저장효과이며fixture나수동치유아니다.

**다음 실제 blocker: 전략HUD航続는0인데DB항속10.** window-world-v116.png에서그리드전략HUD/귀환행성/艦内는관찰했으나3D구축함형상·전술HUD·새전투·102→101워프는아직관찰하지않았다. source0325→HUD 소비(인물→unit/current base/boarding 소속 포함)를역추적하고기지내표시규칙인지데이터누락인지분리할것. 서버저장10만으로사용자에게항속표시/워프정상이라고보고하지않는다. 최초runtime-db-v116의unit행이빈문자열인것은nullable flagship_kind 문자열연결probe오류였으며b/final에서coalesce로수정했다. 실제row소실아니다.

증거 work/20260904-warp-state-reverse/evidence/deployment-recovery-verification-v116.json.
최종runtime-db-v116-final.json SHA621FE68A1A0CBE23CB12DBC376F31FD4A4D8E6CF4049678BC1198060ACE812D0.
world PNG SHA2391C1CE45BD35D144B7D158851C29423E7385A9AA7AE70F4F26B79F0207F229.
credential-v116e.json SHA4358D38DFC20CE938F9E6B39E2B94FFD346114AFEBFB641DA7B2796FAD4F4998.
server-wire-world-v116.jsonl SHA088232E38E12C2C32E7835B9B9D17E8DF7C22968F3703BCD030DCDC03D7AE079.
postgres-v116-recovered.log SHA009C47407DB795DEED559CE5D8D2DFF0A1135AF7517D96D820781CD8BE59CC1A.
scripts guest-session-repair-v116e Stop/Start/Client 및 credential-v116e 모두소비됨. e재시작/로그인반복금지. 현재runtime그대로이어가며새조회영수증사용. guest-window-observe-v116.ps1은새Tag로현재창관찰가능; guest-click-v116e는fresh신원·화면을확인하고새ReceiptPath로새로운실제필요입력만. auth-preflight-v116e가사용중인identityanchor. 전략항속/HUD→실제출격/워프→새kind3/93형상/전투 확인이우선이며전체전략·전술·AI·PvP/NPCvsNPC·명령제안·전체리소스목표ACTIVE/미완료.

## E105 — 전략 항속0은 원본 double/정수 포맷 버그, 별도 표시 패치 준비 (v117, 2026-09-07 KST)

**원인 확정 / LOCAL_PATCH3PASS / 적용승인 대기 / 실제 HUD 수정 UNSEEN.** E104의 “DB10/HUD0”는 서버송신이나기지내항속초기화오류가아니다. 원본 HUD초기화0058D140/업데이트0058EE70는004B5B50(context+318)의InformationUnit+54 float를읽고 double로승격해 CString::Format00646616→0064630E에전달한다. 그런데0078D574의ASCII형식은 **%-4d\n**(252D34640A000000), 즉정수용이다. 실제명령0058F7DD FLD[ECX+54]→SUB ESP,8→FSTP double[ESP]→PUSH0078D574→CALL00646616. 두참조는0058D523/0058F7EA. 원본PE BD19263C…와item116 AEF382…에서format8바이트와호출27바이트가모두일치하므로Ghidra만의타입추정/우리서버NEW_DESIGN문제가아니다.

**새실제RPM 17:05:12.1122424Z:** client540/server6412/PG6996 경로·시작시각·파일hash·PGmaster/data directory검증후읽기만수행. world08AF9020/context08AF902C/unit08AF9344, unit2/kind3/**cruising10(raw00002041)**. double승격0000000000002440의정수하위32비트는0으로HUD증상과일치. 게임데이터를10으로강제로쓴것이아니며memoryWrites0/gameInputs0. 증거 cruising-read-v117.json SHA8A523FFA4619041811676CA3DB52B1C1E1F36EF248E122D0230AB6456BC5013E. 4개원본함수decompile cruising-format-{0058d140,0058ee70,00646616,0064630e}-v117.txt저장.

별도패치 **work/20260903-client-debuglog-patch/G7MTClient.cruising-format-v117.exe**, SHA C4098D75546F44F888212D11E80D91AD687B9D88AE70B2FAB739013DC21A50DC. 입력item116을보존하고0078D574의8바이트영역만 **%-4.0f\n**(252D342E30660A00)로수정. 실제변경4바이트/파일길이3960832동일/명령·나머지리소스·모델·wire·게임상태불변. 소수항속이있으면0자리로반올림하는표시방식은NEW_DESIGN UI수정이며원본정상표시회수주장이아니다. 기존G7MTClient.item117.exe는이미존재하여exclusive출력이거부됐고그파일은건드리지않았다. v117은이작업버전이며globalitem117을덮어쓰지않는다.

TDD: identity baseline의실제Windows CRT_snprintf에서10/9가0으로출력되는2실패와알수없는입력허용1실패를관찰후수정. **최종3testsPASS**: 실제CRT10/9/0출력,8바이트밖전체불변/길이보존,다른SHA/재적용거부. builder scripts/patch_cruising_format_v117.py, tests/test명은같은scripts/test_patch_cruising_format_v117.py. 출력파일은xb로만생성해덮어쓰기금지. review_cruising_format_v117가바이너리전체차이·2개double호출·정확한형식과3tests를독립확인해blocking0. 상세receipt cruising-format-verification-v117.json.

**클라이언트적용승인질문을보냈으며현재미응답.** 이전서버/프로토콜변경승인과구분해서원본클라이언트표시형식패치승인을요청했다. 현재실행클라이언트540/item116은그대로이며live메모리수정·클라재시작·게임DB쓰기·서버변경0. 승인전실행파일교체나실행메모리패치로승격하지않는다. 승인후이패치만적용한새실측으로HUD10을확인하고워프후9/재접속유지까지검증할것. 서버항속을정수비트로위조해서표시를맞추지말것. 승인대기중다른전략/전술분석은가능하며전체goalACTIVE/미완료.
정확한원장경로는 **docs/reverse-engineering/strategy-command-ledger.json**, **docs/reverse-engineering/client-card-command-dispatch.json**(work/evidence아님). 현재게임·서버·PG계속사용,소비된v116e시작/로그인영수증재실행금지.

## E106 — 출항60은 CommandSwitchMode0B06, 서버 처리 누락 확인 (v118, 2026-09-07 KST)

**STATIC_CHAIN_CONFIRMED / AUTHORITY_MISSING / NATIVE_UNSEEN.** Ghidra LOGH7/G7MTClient.exe version33에서 재출격 명령을 추적했다. 0058C85E가00C9E3EC에00583E20을 설치하며, 명령테이블 시작00C9E2FC와 차이를4로 나누면60이다. 명령 원장의 출항60과 일치한다. 00583E20은 GetMoveBaseMode(vtable00676C48,+8=00570940), FLOW_FLAGNUM5/6에 조건부인 command60 확인 흐름, SendWarpCommand(vtable00676AEC,+8=005737D0)를 만든다.

00570940은004B5B90의 현재outfit ID와004C54D0/004C5470 두 레코드 존재를 확인한다. context+40이0이면flag5, 양수이면flag6. 005737D0은flag5→004B4A00(4),flag6→004B4A00(5)를 호출한다. 이 분기는0B00/0B01 이동과 별개이다. 004B4A00은0x164 구조체를0으로 초기화하고 actor004B4A90, modeu16+14, unit_count0+16을 채워 내부selector0x42로 송신한다. 004B78A0은selector-1의case0x41에서 **0B06 CommandSwitchMode**로 매핑한다. 00448C90 길이계산은unit_count+16<=70,move_character_count+138<=10,30+4*N+4*M. 모드4/5를 전략/전술 번호라고 추정하지 않는다.

현재 server-scratch/apps/server/**/*.cs(bin/obj 제외)를 --no-ignore로 검색했을 때 SwitchMode 및0x0B06/0xB06 처리 참조가0이다. 따라서 버튼 노출만 추가하지 말고 요청 codec·서버 권한검사·영속 mode/base 상태·NotifyChangeMode 및 현재투영의 결합을 먼저 구현해야 한다. 아직 전체writer/응답필드 의미를 회수하지 않았으므로 이번 서버수정0,클라이언트수정0,DB쓰기0,게임입력0. 전략명령원장60을NOT_STARTED에서 정적경로확인/권위구현누락/실클라미검증으로 수정했다.

증거 **work/20260904-warp-state-reverse/evidence/departure-static-v118.json**에 원본 decompile/listing을 저장했다. 다음 시작은 **00448EA0 Output_CommandSwitchMode::output_to_stream**와 **NotifyChangeMode** serializer/receiver이다. Ghidra에서00448EA0은 아직data로 취급되므로 decompile이 단일명령만 반환할 수 있다. 정확한 코드경계를 확인해 분석하거나 로컬PE disassembler를 사용할 것. opcode를 추정해 임시성공응답을 보내지 말 것. E105항속표시패치 승인은 여전히 미응답이며 변경하지 않았다. 자동로그인은E104에서 성공했고 직전턴에client540/server6412/PG6996 생존만 재확인했다. 이번턴 새runtime상태·출항·전술진입 검증으로 승격하지 않는다. 전체goalACTIVE/미완료.

## E107 — 출항 요청·모드변경 알림 코덱 구현, 영속모드 연결 남음 (v119, 2026-09-07 KST)

**CODEC_TESTED / AUTHORITY_NOT_CONNECTED / NATIVE_UNSEEN.** Ghidra의00448EA0과004A79B0에 함수 정의를 생성해 실제writer/reader를 회수했다. 분석DB만 변경했으며 EXE바이트 수정0. 원본SHA BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16과writer/reader/004C1D20/004B5DB0의각96바이트가일치했다. 전체함수바이트대조로확대하지않는다.

0B06 body는 u32 time,id / u16 card / u32 pcp,mcp / u16 mode / u8 unitCount / u32 unit[N] / u32 spot,spotOwner / u8 moveCharacterCount / u32 moveCharacter[M]. 최대70/10,body30+4*N+4*M. 00449190 logger에서두번째u32는id이며wait가아니다. 기존MoveGrid와필드를혼동하지말것. 042F NotifyChangeMode reader004A79B0/logger004A7F20은 u32 time / u8 kind / u32 targetBase / u8 unitCount / (u32 id,f32 direction,x,y,z)[N] / u32 spot,spotOwner. 최대32,body18+20*N. 요청70과알림32의차이는향후서버에서분할송신하되권위상태는원자적으로처리할것.

server-scratch의 **OriginalGateway/OriginalSwitchModeCodec.cs** 및 ProtocolTests/OriginalGateway/OriginalSwitchModeCodecTests.cs 추가. 요청파서는정확한길이·type·두배열상한을검사하고,알림인코더는원본필드순서/floatbits/4byte메시지코드prefix를보존한다. codec은권한·PCP/MCP·위치·mode정당성을승인하지않는다. 테스트의기대바이트는writer/reader에서수기로작성했으며생성함수로기대값을계산하지않았다. RED빈구현5FAIL/1PASS→GREEN6PASS. 모든관련CodecTests **113PASS/0FAIL/0SKIP**. 이는전체프로토콜/DB/실클라통과아니다.

중요한다음구현: 004BC169는042F를004C1C30으로넘기고,004C1D20은kind4/6에서entity+5C4=0,5에서5,7에서6;004B5DB0으로모드설정,004B5E80으로기지연결초기화/재설정,좌표/방향및기지상대좌표를적용한다. 자기unit은004B5DB0이context+31E(InformationUnit+6)의mode를직접바꾼다. **현재OriginalWorldEntryCodec.EncodeUnits는mode를항상0으로송신한다.** 세션0B06handler뿐아니라영속mode/base 및0325재접속투영을함께수정해야한다. 임시042F만보내서출항완료라고하지말것. 현재codec은NaturalAuthoritySession에연결하지않았고서버재시작/배포/DB쓰기/실클릭0이다.

영수증 work/20260904-warp-state-reverse/evidence/switch-mode-codec-v119.json에decompile과원본pin저장. TRX E:/logh7-build/test-results/switch-mode-v119/{switch-mode-v119-red,switch-mode-v119-green,switch-mode-v119-codecs}.trx. 최종113테스트TRXSHA40FD6C9B1FABDDADACE37EB7BEA529DAAA4479E256470E22864CE9CDC7594818. codecSHA8813E027EF81003E19121824C5E08A278B62EF0BB416B5035754249105518C51,testsSHA4B077C886042C6CE76C49442EB418B094F9F60DDDEA565F5FA446A0413BD493C. 독립리뷰/전체DBsuite/실클라출항은미실시. 다음은actor/card권한·outfit검사와mode4/5기지상태전이를기존저장트랜잭션에연결하고실패/재전송/재접속시험으로검증하는것이다. E105클라표시패치는아직승인대기,전체goalACTIVE.

## E108 — 출항 사전검사는outfit이아닌base, 유닛모드 전송초기화 제거 (v120, 2026-09-07 KST)

**정정:** E106/E107에서004B5B90/thunk004B5BF0의반환값을outfit이라고쓴것은잘못이다. 현재Ghidra에서004B5BF0은context+358을읽고004B5B50은context+318을반환한다. 따라서유닛+40의 **base** 이다.004B5E80도정확히context+358을쓴다. 기존client-card-command-dispatch.json의unitFieldMap+40=base와일치한다. 출항00570940의조회키는현재기지ID이며,잘못된outfit검사를새로추가하지말것. 위절과v118영수증은과거조사기록으로남기며이정정이우선한다. 00570940의별도context+40분기를unit+40과혼동하지말것.

**구현:** OriginalInformationUnitProjection에Mode(byte,default0)를추가하고OriginalWorldEntryCodec.EncodeUnits가그값을0325에기록하도록수정했다. 같은유닛정보를다시송신할때serializer가모드를무조건0으로덮던부분을제거했다. 기존호출자의기본동작은0으로유지되며이것만으로실제출항상태가생기는것은아니다.

TDD: OriginalUnitModeProjectionTests의mode4/5/6/7 모두실제payload0으로4FAIL을재현한뒤수정. 두유닛의서로다른mode/base,42byte레코드오프셋,mode바이트외전체frame불변을검증한다. 관련CodecTests포함 **117PASS/0FAIL/0SKIP**. E:/logh7-build/test-results/unit-mode-v120/unit-mode-v120-green.trx SHA08915A26E8964D71A837C24BAEDF29C7345B4E5A26A99D84DC850B1712219470. 근거 work/20260904-warp-state-reverse/evidence/unit-mode-projection-v120.json.

**미완료/다음:** 이번시작때계획했던영속저장까지완료하지못했다. OriginalGridUnitRecord/PostgresAccountStore에는아직Mode필드가없으며CurrentPlayerInformationUnit도Mode를공급하지않아실제세션은여전히0이다. mode4/5가요구하는base/spot상태전이를확정한후migration·트랜잭션·과거명령재전송·격침귀환/새함선세대·재접속투영을같이연결해야한다. 특히042F004C1C30/004C1D20/004B5E80에서targetBase가있으면기지ID와상대좌표를다시설정하므로모든출항에무조건base0을대입하지말것. 004C9170은context+40/+44를기지내spot목록에매칭하는추가소비자이다. 이번DB쓰기0/배포0/재시작0/게임입력0,독립리뷰와실클라검증미실시. E105클라이언트표시패치는여전히승인대기. 전체goalACTIVE.

## E109 — 유닛모드 영속보존·재접속 투영 연결 (v121, 2026-09-07 KST)

**STORAGE_AND_SESSION_TESTED / DEPARTURE_AUTHORITY_UNCONNECTED / NATIVE_UNSEEN.** E108의미연결중Mode저장·조회·세션투영을구현했다. migration0024_original_unit_mode.sql은original_grid_unit.mode와original_grid_move_command.result_mode(integer0..255,default0)를추가한다. 기존송신값0을보존할뿐과거출항을추측하지않으며기존필드는변경하지않는다. OriginalGridUnitRecord.Mode추가,Find/Move/부상귀환/기함복귀의4개조회에서mode를읽는다. 공통reader는mode열명을사용하고부가컬럼ordinal을바꾸지않았다.

Move트랜잭션은기존mode를유지하며이동명령원장에result_mode를기록한다. 과거재전송은당시mode를돌려주고현재상태를덮지않는다. OriginalGridUnitMoved이벤트와move-grid-v3해시에mode를포함했다. NaturalAuthoritySession.CurrentPlayerInformationUnit은_persistedGridUnit.Mode를0325로전달하므로새세션/참가자투영에도연결된다. 기존Cruising업그레이드시험의행보존비교는새mode열만제외하도록갱신했고,모드자체업그레이드는별도시험한다.

실제격리PostgreSQL에서RED열개수1예상/0실제→저장시험통과,이어새DB연결+새세션의암호화0205/0F02→0325에서mode7예상/0실제를재현→세션연결후통과했다. SQLfixture로mode4를설정한뒤실제Move저장호출,다음fixturemode7후과거명령재전송4/현재7유지,두번새datasource/session응답7,mode-1/256DB거부,기존행JSON필드보존,이벤트mode4와독립canonical해시를검증했다. **SQLfixture는실제출항명령이아니다.** 전체ProtocolTests최종 **597PASS/0FAIL/0SKIP**, E:/logh7-build/test-results/unit-mode-v121/unit-mode-v121-final.trx SHA8266949A133BDA79D4F9F31B28721113D9E33756B855732278BA1AA63FD58D9E. migrationSHA28167806CFE648368553D6B4645E2EC0CB15EFC93A23E418458755301CBF4CA0.

호스트시험PG39260/start2026-09-06T17:41:06.3421538Z,경로E:/logh7-build/return-base-postgres-runtime-v91/pgsql/bin/postgres.exe,dataE:/logh7-build/warehouse-pgdata-v105,port55805를확인하고17:45:00.7838158Z정상종료했다. postmaster.pid없음/listener0. 새시험schema/로그/데이터는보존하고삭제0. 게스트게임/서버/DB에입력·쓰기·배포·재시작0,게스트는여전히v30/migration23이다. 로컬migration24를배포완료라고하지말것.

다음은 **0B06의실제출항handler와권한/기지·spot상태전이** 이다. 그후042F알림·0325갱신·원본화면에서출항→워프→전투를닫는다. 현코드는Mode를저장/보존할수있지만게임명령으로Mode를변경하는경로는아직없다. 부상귀환/기함복귀도기존mode를보존하며새생명mode규칙은미확정이다. 독립리뷰/원본출항/재접속UI검증미실시. 근거work/20260904-warp-state-reverse/evidence/unit-mode-storage-v121.json. E105항속표시패치승인여전히미응답,전체goalACTIVE.

## E110 — 전략 인물 위치 갱신은0B0B 경로, 알림코덱 추가 (v122, 2026-09-07 KST)

**STATIC_PATH_CONFIRMED / CODEC_TESTED / AUTHORITY_UNCONNECTED / NATIVE_UNSEEN.** 042F의004C1C30은world+126718이0이면아무처리도하지않는다. 전략장면에이알림만보내면인물위치가갱신되지않을수있다. 반면0B0B NotifyMovedBase는004BCF83→004BEE60,world+2A58F8조건에서600개playercontext를순회하고move_character[] ID와맞는인물의context+40(spot),+44(spot_owner),004B5BD0(unitmode+31E),004B5BE0(unitbase+358)를갱신한다. **move_character[]가비면갱신대상이없다.** 004C2C80은CharacterInformation[7]/[8]→context40/44와InformationUnit[6]/[40]→모드/기지매핑도확인한다. 기존서버에0B0B경로없음.

reader0044BEE0/logger0044C310의body는u32 time,id,base / u16 mode / u32 spot,spot_owner / u8 move_character_count / u32 character[N],최대10,23+4*N바이트이다. Ghidra0044BEE0함수정의만추가했으며EXE바이트패치0. **OriginalMovedBaseCodec.cs**와**OriginalMovedBaseCodecTests.cs**추가:4byte메시지코드prefix+type+body,필드순서,0/10명길이,11명/null거부검증. RED3FAIL→관련CodecTests/ModeProjection **120PASS/0FAIL/0SKIP**. TRX E:/logh7-build/test-results/moved-base-v122/moved-base-v122-green.trx SHA105A303BAD70FCF1A3D206B1F084917FC5FC643361E9EE9053D2E0BCF30E673A. 이번전체DBsuite는미실시이며E109의597을새검증으로표현하지않는다.

근거work/20260904-warp-state-reverse/evidence/moved-base-v122.json. 0B0B는0B00응답으로도쓰이므로0B06뒤원본서버가보낸정확한알림순서가관찰됐다는뜻은아니다. 새서버의변경상태에맞춰인물컨텍스트와전술엔티티투영을선택한다. 다음은CharacterInformation spot/owner의현재서버투영·영속위치를확인하고실제0B06권한검사/트랜잭션에연결하는것이다. 모드4/5의최종base/spot전이는아직미구현. 코드생성만으로전략출항성공아님. 게스트입력/DB쓰기/배포/재시작0,호스트PG는E109정상종료상태를변경하지않음. E105표시패치승인대기,전체goalACTIVE.

## E111 — 전략 자기 함선 출항 저장·세션 연결 (v123, 2026-09-07 KST)

**STRATEGIC_OWN_MODE4_STORAGE_SESSION_TESTED / NATIVE_UNSEEN.** 중단돼 있던 NaturalAuthoritySession.Departure.cs의 nullable 캐릭터 Power 접근 두 곳을 수정하고 실제 출항 요청 연결을 검증했다. 0B06에서 world/actor/zero-fields/mode4/현재 그리드/함선 세대/생존/우호 기지를 검사한다. 현 서버의 spot/owner=0인 자기 함선 경로만 연결했으며 mode5, 여러 유닛, 적이 있는 전술 장면 출항은 명시적으로 미지원 응답한다. 이를 전체 출항 완료로 해석하지 않는다.

migration0025_original_departure_request.sql과 PostgresAccountStore.Departure.cs: 계정·유닛 행 잠금 후 base0/mode4, OriginalUnitDeparted 이벤트, 계정 버전·해시, 요청 이력을 한 트랜잭션으로 저장한다. 비용/시간/출항 최종 상태 규칙은 원본 서버 회수가 아닌 NEW_DESIGN이다. 과거 저장 요청 재실행은 현재 유닛을 반환하며 이후 함선 세대/위치를 되돌리지 않는다. 0B0B에는 실제 대상 인물 ID를 넣고 0325도 갱신한다. 전술042F 좌표/기지 상대 위치 연결은 남았다.

실제 격리 PostgreSQL 테스트: 동시8요청 중 변경1회, 외부 계정·오래된 버전 거부, 이벤트 저장 실패 시 유닛/이력 롤백, 후속 세대에 과거 요청 재생해도 불변, 암호화0B06으로 base2/mode7 fixture에서 base0/mode4 전환, 새 datasource/새 세션0205/0F02에서도 유지 확인. 0325 packed base는 frame+28이며 초기 잘못된 +34 검사를 고치고 출항 전 base2도 단언했다. 같은 통신 순번을 성공으로 기대한 초기 테스트는 기존 strict sequence 규약과 충돌해 수정했다. 중복 순번은 Invalid로 세션을 종료하므로 마지막에 검사하며, 새 순번으로 다시 출항해도 이미 base0이어서 상태 변경 없이 거절된다. 기존 transport를 느슨하게 바꾸지 않았다.

최종 전체 ProtocolTests **601PASS/0FAIL/0SKIP**. TRX E:/logh7-build/test-results/departure-v123/departure-v123-final.trx SHA D8992A241F512678FAC2BE79FC131DD5E349F982FF9D47A55CBD827BC8C12602. 상세 영수증 work/20260904-warp-state-reverse/evidence/departure-v123.json. 호스트 PG32568/start17:54:22.7037700Z/data E:/logh7-build/warehouse-pgdata-v105/port55805 신원 재검증 후18:06:16.8655994Z 정상 종료, pidfile 없음/listener0, 데이터·schema·로그 보존.

**다음 실제 플레이 경계:** 게스트는 아직 v30/migration23이다. migration24/25와 이 서버를 데이터 보존 배포하고 자동 로그인→실제 출항→워프→전술 진입을 확인해야 한다. 소비된 v116e 시작·로그인 영수증을 재사용하지 말고 새 실행 신원으로 수행한다. 이번 게스트 입력/DB쓰기/배포/재시작0. E105 클라이언트 항속 표시 패치는 여전히 별도 승인 대기이며 배포하지 않았다. 자동 로그인 가능은 E099/E104에서 확인됐고 사용자에게 로그인을 넘기지 않는다. 독립 리뷰 및 실제 출항 화면 검증은 아직 없음. 전체 전략/전술/AI/리소스 목표 ACTIVE/미완료.

## E112 — v31 보존 배포·자동 로그인·실제 전략 화면 재진입 (v124, 2026-09-07 KST)

**V31_DEPLOYED / NATIVE_LOGIN_STRATEGY_WORLD_PASS / DEPARTURE_UNSEEN.** E111의 로컬 출항 처리와 migration24/25를 실제 게스트에 배포했다. 새 run은 C:\Users\logh7-oracle\AppData\Local\Temp\logh7-l1\20260906T181000Z-departure-v124. 호스트 publish E:/logh7-build/server-v31-departure-v124, zip E:/logh7-build/v31-departure-v124.zip SHA37C07C08DD12048DD2FB992CB7638AFDCA4A9248DD5DEC8F016F00BFAE27F621, DLL FE3E5A02DBB9335E8757A1F82DC05493FDAD1924BD482E7389B33DFFDE19BFC5. Release win-x64 self-contained publish 성공; 전체601테스트는 E111 영수증이며 이번 새 전체suite 실행은 아니다.

기존client540/server6412/PG6996의 경로·시각·바이너리·DB 및 포트 소유를 새로 검증하고 정상 DB종료 후 cold copy했다. 기존 163027Z run은 오프라인 복구용으로 보존, pg_control SHA D5E59E2CC2F691D05A986C1A4CE090E2E48E700D30E52156D546F6BADABC3D3E. 사전/복사후/업그레이드후 상태해시 cb29734c3af635b776e0e197d43f8de6876ca7b6a259ac5ce8b83626deae75bd 일치. 범위는 account/character/unit(mode신규열제외)/event/return-base preference이며 모든 테이블 비교라고 확대하지 않는다. migration25,unit2/grid102/base2/loss0/0/gen1/cruising10/recovery1 유지. 삭제0, proxy2952/127.0.0.1:47900 그대로.

현재 **server4920/start18:11:30.7518583Z, PG1812/start18:11:30.2695908Z/55432**, 둘다대화형session1. client6944/start18:12:05.8663449Z/HWND0x0000000003E80264, 기존item116 SHA AEF3827602CD13A395618BDEF0F48F44BCB8ED60F4FA9C2017F2D2E128660F2F 그대로. 새서버와client경로는 새run/server,새run/client/exe. PGbinary는 기존102317Z run/postgresql/pgsql/bin, data는새run/postgres-data. wire새run/server-wire.jsonl. host-vmrun-v79.ps1 게스트 Run은 Start/Client/화면/입력에 반드시 -Interactive를 사용한다. 초기auth-preflight-v124는 비대화형 세션에서 HWND_CHANGED로 실패했고, 재시작 없이 새 v124b 영수증에서 동일PID/start/HWND 대화형검사가 통과했다.

실제 화면에서 흰650x533시작창→보이는File메뉴(30,35)1회→로그인화면→메뉴닫기(605,502)→ID(324,331)→DPAPI 시험계정38keyevents1회→v31공지 로비→GameStart(125,192)→기존캐릭터(650,307)로 이동했다. 모든 입력은 해당 실행 새 캡처 기준이며 좌표 재사용 금지. 첫메뉴 영수증의 기본 coordinateProvenance 문구(fullscreen)는 부정확하며 실제 근거 window-before-login-v124.png는0,0의650x533창이다. 이후 영수증에는 실제 캡처명을 넣었다. SKY Computer Use JS 입력과 혼용하지 않았으며 기존 게스트 입력 경로 사용. 비밀값 출력/저장0, 로그인시도1/재시도0.

로그18:16:26.0666883Z LobbyReady, 기존캐릭터 선택 후 SessionServerReady/0205/0F02 성공. **window-world-ready-v124.png SHA2DC8D6F13AD28E770CD183F61D04D9E3812EAE28D6B2BB4F781301AAE56DEB7D**에서 전략그리드·帰還惑星（仮）·艦内 확인. runtime-db-v124.json에서 kind3 구축함/cruising10/손실0 유지. HUD항속0은 E105표시패치 미적용으로 남아 있으며 이번에 해결했다고 하지 않는다. world-wire-v124.jsonl SHA6E35D1C80F51768880D21ED8941FD6E53D8CA1AF197380A7E75ED3EC108447A8. 상세 work/20260904-warp-state-reverse/evidence/deployment-login-v124.json.

**다음:** 현재 실행을 유지하고 새 신원/화면 확인 후 command60 출항 UI 진입점을 찾아 실제 클릭→0B06/0B0B/0325→DB효과→워프→전술진입을 검증한다. 아직 출항 클릭/새워프/전술전투0이므로 출항 완료로 승격하지 않는다. 소비된 v124 Stage/Stop/Start/Client 및 credential-v124 영수증 재실행 금지. 로그인은 완료했으므로 다시 사용자에게 요구하거나 불필요한 재로그인을 하지 않는다. E105표시패치 별도승인대기/미적용. 전체goalACTIVE.

## E113 — 출항 handler만 있고 기본 메뉴 목록에 없던 연결 누락 수정 (v125, 2026-09-07 KST)

**LOCAL_MENU_FIX_TESTED / v31 LIVE UNCHANGED / NATIVE_DEPARTURE_UNSEEN.** Ghidra 역추적에서 004F58C0이 manager0x65의 클릭 이벤트를 읽고 StaticInformationCard 캐시의 stride0x46/+0x20 명령ID 배열에서 선택한 u16을004F93C0으로 넘기는 것을 확인했다. 004F93C0은 컨트롤러+0x1C의 함수표에서 해당 명령 flow를 만든다. 0058C750은 command60 위치00C9E3EC에00583E20을 설치한다. 00C9E2F8은 현재flow 포인터이며 command표의0번이 아니므로 인덱스를1씩 옮기지 말것. constmsg의単独航行은 동항/단독항행 전환 설명과 연결돼 있어 출항 버튼으로 가정하지 않았다.

서버 OriginalWorldBootstrapCodec.ExtraCardCommandIds 기본값[5] 때문에 0305/0307의 card39 명령은 **[0,43,5]뿐**이었다. E111의0B06handler를 연결했지만 이를 고르는 UI 목록을 놓쳤다. TDD 두 테스트에서60없음으로 실제 실패한 뒤 기본목록을[5,60]으로 변경, 양쪽응답에 출항60을 한 번씩 추가했다. 이 카드 배정은 NEW_DESIGN이며 원본 직책 권한 회수나 mode5 지원 주장이 아니다. 원본클라 바이트 변경0.

OriginalDepartureMenuTests는0305의고정19byte헤더/u16명령들과0307의3byte헤더/8byte명령들을 독립파싱해 전체frame소비/24상한/card39의60정확히1회/0·43·5유지/다른카드에60없음을 확인한다. **579PASS/0FAIL/24SKIP(total603)**; DB전용24개는 이번 격리PG를 시작하지 않아 미실행이며 E111의601PASS와 섞지 않는다. TRX E:/logh7-build/test-results/departure-menu-v125/departure-menu-v125-green.trx SHA F8FDC97FD43BFDA28BB399236BCD972FB63B0C47F21E330EE56ECCB0B7B8E340.

새배포후보 **E:/logh7-build/v32-menu-v125.zip** SHA45B511235A73B62EBE22B12519E05A641D61D984CE6C4AD748BF6838FB390A89, DLL F57E4F4431323A676D18761F3A37E0EB359090CBF2A1FC69F36A32D7642E2A68, publish E:/logh7-build/server-v32-menu-v125. migration25동일/신규DB변경없음. 아직 게스트배포안함. 근거 work/20260904-warp-state-reverse/evidence/departure-menu-v125.json에실제decompile저장, Ghidra004F5986에Note/LOGH7_command_menu_v125북마크추가.

runtime-db-v125.json은 새읽기검사로 E112의server4920/PG1812/client6944 신원 및 unit2/grid102/base2/loss0/gen1/cruising10/kind3/migration25를 확인했다. original_character_card행은0이며 LoadPersistedCardAsync의미지정fallback은39이다(강제env는v124기동때LOGH7_*를제거했음). 입력/메모리쓰기/DB쓰기/재시작0. 실제메뉴가60을표시하거나클릭했다고하지않는다.

**즉시다음:** v32는새migration이없으므로불필요한전체DB복사/PG재시작을반복할필요없다. 현재실행신원을검증하고원래server/client보존한side-by-side서버교체와클라이언트재접속으로새카탈로그를받는다. 현재PG1812와data는유지가능. 새로그인영수증으로게임진입→職務権限カード/카드39선택→出港60→실제0B06/0B0B/0325/DB효과→워프/전술검증이남았다. 메뉴가명확히보이기전좌표추측금지,소비된v124로그인/배포영수증재사용금지. E105표시패치는여전히승인대기/미적용. 전체goalACTIVE.

## E114 — v32 출항 메뉴 실클릭, Institution 데이터 누락으로 원본 사전검사 거부 (v126, 2026-09-07 KST)

**MENU_VISIBLE / NATIVE_CLICK_REJECTED_INSTITUTION_DATA_MISSING / AUTHORITY_NOT_REACHED.** v32를 동일181000Z run/server-v32-v126에 side-by-side 배포했다. PG1812(start18:11:30.2695908Z)/55432/data는 그대로 유지, DB종료0·신규migration0·삭제0·proxy2952변경0. 기존server4920/client6944만 정확한 경로/시각 검증 후 종료했다. 상태해시(account/character/unit/event/schema_migration범위)0b1c726ea3b70ec2327561ac79a3489f894422fc2ca04cd2894e94357d6d5247이Stage/Stop/Start동일. 전체테이블동등성으로확대하지않는다.

현재 **server2508/start18:31:03.1752238Z**, DLL F57E4F4431323A676D18761F3A37E0EB359090CBF2A1FC69F36A32D7642E2A68. **client4448/start18:32:03.2568600Z/HWND0x0000000004120448**, 기존item116 SHA AEF3827602CD13A395618BDEF0F48F44BCB8ED60F4FA9C2017F2D2E128660F2F, 경로는 동일run/client/exe. server경로는run/server-v32-v126/Logh7.Server.exe, wire는run/server-wire-v126.jsonl. 기동/재접속 영수증server-swap-{stage,stop,start,client}-v126.json. 소비된Action재실행금지.

새 auth-preflight-v126 및 대화형Default검사 후, 관찰된흰창File메뉴→로그인표시→메뉴닫기→ID→DPAPI38keyevents1회로자동로그인했다. v32공지로비/기존캐릭터/전략HUD확인. 직무권한탭(732,578)→보이는카드행(822,517)에서昇進/ワープ航行/任命/**出港**4버튼실제표시. role-command-menu-v126.png SHA9D816C5098EEFA55DCA68287CC12992D6DFA365A51529463D81CB37B2020714C. 出港(724,312)1회 클릭 후 원본「実行不可」「拠点から離れているため態勢変更できません…」거부,0B06요청0/DBdeparture0. 실패재클릭없이확인버튼만닫아현재전략HUD유지. 클릭좌표는이번캡처에서만유효.

**실제 RPM 18:39:13.6455553Z:** world08AFB020/context08AFB02C/actionContext동일/unit08AFB344. unitBase2,mode0,spot0,spotOwner0,kind3,cruising10. base summary(world+2B6A74)count1/ID2, **Institution(world+2B7078)count0**. departure-cache-read-v126.json SHA4435FFA2EFED0E345331DC594EC4094D186BE818E91426B0F692C23218234BD8. memoryWrites0. DB도unit2/grid102/base2/loss0/gen1/cruise10. 따라서잘못된기지ID나거리값이라고추측해변조하지말것.

Ghidra00570940실명령은기지getter004B5B90→0아님→004C54D0(기지ID)→004C5470(동일ID)를검사하며 이경로에는거리계산이없다. 004C54D0은world+2B7078/u8 count,records2B707C stride2378;004C5470은2B6A74/u8 count,records2B6A78 stride180. **누락된건031F요약이아니라0321 ResponseInformationInstitution**이다. 004BA559의switchbyte표004BDEF4에서index28(0305+28=0321)의slot12→004BDEC8→004BAE8E. 004BAE8E는0x2379dwords를world+3FB2F8에복사하고, 전략import004C4170이이를2B7078로복사한다. 한화면버튼만의조건이아니며004C9170/00591450거점시설·장소목록도같은데이터소비.

**다음구현에필요한회수된wire:** reader004167F0/logger00417140에Ghidra함수정의추가(EXE수정0). 0321 body=u8 baseCount(<=4);각 u32 base,u8 institutionCount(<=36);각기관 u16 kind,u32 id,u8 spotCount(<=20);각spot u16 kind,u32 id,u16 file. packed길이1+5B+7I+8S. 실제전체decompile/수신/검사listing은work/20260904-warp-state-reverse/evidence/departure-native-v126.json에저장. body_size1은Ghidra함수정의경계메타데이터이며전체decompile출력과구분. reader해석에맞는단위테스트/원본바이트pin검증은다음필요.

시설이름004C8D10→constmsggroup73,장소004C8CF0→group74. group73kind4=宇宙港,kind11=係留所,kind16=居住区,kind17=ホテル. group74kind6=旗艦桟橋,kind13=居室. **hex0x11=17을함선시설이라고추정하지말것.** kind/file리소스및spotID모델연결을회수해작은시험기지구성을명시NEW_DESIGN으로정의한다. 빈Institution레코드만추가해출항gate통과를목표완료로대체하지말것.

**즉시다음:** 0321codec + 기지별Institution/Spot카탈로그 + 최초0F02/장면진입/워프/재접속의전략import이전전송을함께연결한다. 필요시0320요청writer도회수한다. 메뉴추가때처럼codec/handler만만들고실제bootstrap투영을놓치지말것. 이후새단일실측으로시설캐시base2존재→출항0B06→0B0B/0325→DB→워프/전술을검증. 현재실행은정상로그인상태이며이번출항실패1회를그대로보존. E105클라이언트항속패치승인대기/미적용. 전체goalACTIVE,모든전략/전술/AI완료아님.

## E115 — Institution 코덱·카탈로그·장면 투영 구현, 배경 이미지 selector 회수 (v127)

**LOCAL_CODEC_CATALOG_BOOTSTRAP_TESTED / NOT_DEPLOYED / NATIVE_DEPARTURE_STILL_UNVERIFIED.** OriginalInstitutionCodec.cs에 0321 big-endian packed 응답과 Base/Institution/Spot typed records를 추가했다. 최대4/36/20, null nested records/lists 및 overflow를 거부한다. OriginalInstitutionCodecTests는 독립 literal bytes, 최대24075byte frame, empty cache-clear, invalid input을 검증한다. stub RED4FAIL → GREEN4PASS.

OriginalBattlefieldTemplate.BaseInstitutions nullable data를 추가하고 static base ID join, base/기관/장소 ID 중복, 기관/장소 zero IDs, nested count, 그리드별4base상한을 로드 때 검증한다. EncodeInstitutionFrame은 현재그리드만 투영한다. Native receiver는 전체캐시교체이므로0321은 batch 분할하지 않는다. NaturalAuthoritySession.EncodeTacticalSceneBootstrapFrames에서031F 다음0321을 추가하여 초기/refresh 양쪽0F02 경로에 연결했다. 새 encrypted dispatch test는 실제 session handler를 거쳐 두 순번의0F02 응답에서0321정확한내용과0B0A/0F03이전순서를 확인했다. 초기initialization boundary/재시작/로그인은 이 테스트의 범위가 아니다. test-only RosterStore가 소유목록을 공급한다. catalog/bootstrap RED11FAIL → suite GREEN.

전체 **594PASS/0FAIL/24SKIP(total618)**. 격리PG를 시작하지 않아 DB전용24개는 이번 미실행. TRX E:/logh7-build/test-results/institution-v127/institution-v127-suite.trx SHA61DCF9404411E152831BBB79DE9ED30CE1651B5563489239779CAA6118972B77. 테스트의 file7/9는 wire검증용이며 실제 아트 배정이 아니다. shipped catalog.json은 아직 수정하지 않았고 기본시설내용은 여전히 없음. 빈base행만 넣어gate통과로 대체하지 않았다. 게스트배포/DB쓰기/클라패치/재시작0.

**Ghidra resource join 회수:** 004D5030은 004C9170에서 반환한 선택spot의u16+8을 읽어004D4F10에 전달한다. 004D4F10은00772204의 `../data/image/spot/bg%03d.jpg` 형식으로 경로를 만들어004D1F70로 로드한다(정확한 슬래시 표기는 string bytes로 추가 pin 필요). 따라서 file은kind와별개의 직접숫자배경selector. 선택spot이없는경우004D5030은004D4FD0결과에따라42/43또는-1을 선택한다. 함선 내부 배경의42/43 진영의미는 아직 추가회수필요. decompile전체는 work/20260904-warp-state-reverse/evidence/institution-v127.json. project.list_programs는tool outputSchema검증오류였으나 기존G7MTClient.exe inspect두함수는정상성공. 새import/EXE수정없음.

**다음:** 원본 설치/추출 경로에서spot/bg*.jpg실파일확인 및 이미지관찰→spaceport/flagship-pier 등 작은 기지구성 NEW_DESIGN으로 JSON작성, file선택의원본사실과시설배정의신규설계구분. rootworktree rg에서 bg*.jpg는없었으므로 기존게스트설치/원본asset inventory를조회할것. content/schema/re-entry/cross-grid검증후v33후보배포→자동로그인→시설캐시→실제출항→DB→워프/전술. 0320request layout은아직미회수/미구현,031F다중batch의원본cache대체문제는별도audit필요. live v32 server2508/client4448/PG1812유지. 직전새캡처 window-login-check-20260907a-v126.png에서전략HUD·艦内를직접확인했고현재로그인완료이므로사용자에게로그인을넘기지말것. 전체goalACTIVE.

## E116 — 원본 장소 배경 확인, 두 시험기지 시설 내용과 v33 배포후보 준비 (v128)

**CONTENT_IMPLEMENTED / ASSETS_PRESENT_IN_GUEST / v33_NOT_DEPLOYED.** 원본asset inventory는 worktree가 아닌 E:/logh7-greenfield/docs/reverse-engineering/client-assets-inventory.csv에 있었고, JPG들은 root evidence/installshield-extract/____________s___/____/data/image/spot에 있다. bg001(침대·의자있는방),bg006(녹색함선도크),bg007(회색함선도크)을 직접 관찰. bg001과bg007은 게스트현재run/client/data/image/spot에도 존재하며 원본추출해시와일치. guest-institution-assets-v128.ps1은읽기전용이며영수증 institution-assets-v128.json UTC19:00:52.0381207Z. UI입력/게임상태변경0.

catalog.json base1/grid101,base2/grid102에각宇宙港kind4→旗艦桟橋kind6/file7 및居住区kind16→居室kind13/file1 추가. 기관IDs101/102,201/202;spotIDs1001/1002,2001/2002. 모두NEW_DESIGN으로원본서비스데이터복원주장아님. 출항gate용빈레코드아님. 원본이미지→file wire연결및관찰내용·해시·배정은 docs/reverse-engineering/institution-background-content-v128.md에정리. 전체리소스추적완료아님.

OriginalInstitutionContentTests RED2FAIL/1PASS→GREEN3PASS. 전체597PASS/0FAIL/24SKIP(total621),DB전용미실행. 기존OriginalBattlefieldBaseJoinTests/OriginalStaticBaseTests합성fixture가staticID를교체하면서기본시설base1을남겨8실패했으므로그두helper에서시설배열을비우도록분리했으며기존assertions유지. 최종TRX E:/logh7-build/test-results/institution-v128/institution-content-v128-green.trx SHA9B18C34A51317EA4896FA3F44934827302E2C2F669B9DA4E57043B0ADB4BFDEE.

Release win-x64 self-contained publish성공. v33candidate E:/logh7-build/server-v33-institutions-v128, DLL EF15A3BD0832CA07A2B8649D8821C208F4FBA6A5BC552FFB9D35B052B52230BB. ZIP E:/logh7-build/v33-institutions-v128.zip SHA259F37642B4E9C6BF55E5BD15FF71BB10AF9432A51DE6B9D31C664EBC60310BB. migration25변경없음. 아직게스트배포/게임서버재시작없음.

**즉시다음:** 실행신원재검증→PG1812/data와proxy보존→v33side-by-side서버교체/client재접속→자동로그인→0321base2시설캐시→실제出港0B06/0B0B/0325/DB→워프/전술. v126consumedStage/Stop/Start/Client/credential재실행금지;새영수증으로진행. 장소배경실제UI렌더도별도검증필요. E105항속표시클라패치승인대기유지. 전체goalACTIVE.

## E117 — v33 보존 배포·자동 로그인·원본 출항 실제 처리와 DB/클라 반영 (v129)

**NATIVE_DEPARTURE_COMMAND_EFFECT_PASS / RECONNECT_PERSISTENCE_NOT_YET_RUN / WARP_NEXT.** E116 v33을 동일181000Zrun/server-v33-v129에side-by-side배포. v32server2508/client4448만경로·시작시각·해시확인후종료. PG1812(start18:11:30.2695908Z)/55432/data/proxy유지,DB종료0/파일삭제0/신규migration0. Stage/Stop/Start account-character-unit-event-migration상태해시0b1c726ea3b70ec2327561ac79a3489f894422fc2ca04cd2894e94357d6d5247동일. 다른테이블동등성주장아님.

현재 **server3480/start2026-09-06T19:04:21.0986239Z**,path run/server-v33-v129/Logh7.Server.exe,DLL EF15A3BD0832CA07A2B8649D8821C208F4FBA6A5BC552FFB9D35B052B52230BB. **client2340/start19:04:45.2312199Z/HWND0x00000000008A00D6**,동일run/client/exe/item116해시AEF3827602CD13A395618BDEF0F48F44BCB8ED60F4FA9C2017F2D2E128660F2F. wire run/server-wire-v129.jsonl,stdout/stderr server-v129.stdout/.stderr. PG1812는기존바이너리/데이터유지. v129 Stage/Stop/Start/Client action 모두소비됨;재사용금지.

새대화형Default확인/auth-preflight-v129후관찰화면File메뉴→메뉴닫기→ID클릭→credential-v129 DPAPI38keyevents1회,재시도0. 원본v33공지로비확인→게임시작→기존캐릭터→전략HUD재진입. 자동로그인완료이므로사용자에게수동로그인을요구하지말것. SKY JS입력혼용없음. 로그인secret값출력/영수증저장없음.

**실측전후:** departure-cache-read-v129.json UTC19:08:55.2901247Z에서unit2/base2/mode0/cruising10,summarycount1/ID2,facilitycount1/ID2. E114의시설count0누락해소. 원본메모리쓰기0. 역할탭→카드→出港1회→확인창실제진입. 확인창은「惑星or要塞名」「コマンドポイント」「MCP」「G時間」등미치환자리표시자와「宇宙港に駐留します」문구가남음. 이설명문구는실제mode4출항과불일치하며추가수정대상;확인문완성판정금지.

확인(565,517)1회후 **0B06 native request UTC2026-09-07T04:10:29.6314559+09:00,Success,departure-unit=2;authority-version=28;design=new**. reply48bytes/additional64bytes (암호프레임길이;이길이만으로독립wire필드검증주장안함). DB unit|2|2|102|0|4|0|0|1|10,OriginalUnitDeparted1회,original_departure_request1회. eventsourceBase2/sourceMode0/destinationBase0/mode4,loss0/0/gen1/cruising10보존. hostevidence live-after-departure-v129.json에동일run메타데이터/DB/event/wire저장.

window-departure-result-v129.png SHA6F59D1A62AFDE44447986DEC133E7A4921CC0B2B501FCFE142271328FF1E2811에서아래위치가帰還惑星（仮）→**星系内宇宙**로바뀜. departure-cache-after-v129.json UTC19:11:02.7368387Z SHA FB6C6D350FE04A3D8727E06B144DBEF6F2CE4D2CE9F02259E61A126EE1FF1A2C에서클라unitBase0/mode4/cruising10직접확인. 원본입력→서버처리→DB→클라메모리→HUD위치효과까지이번실측. 출항후재접속영속성은아직새실측아님. E105항속숫자HUD0표시문제별도승인대기/미적용.

**즉시다음:** 현재정상로그인된출항후상태유지. 새화면관찰후워프→항속소비→전술진입/공격/피해/종결을이어검증. 출항반복·로그인반복·DBfixture텔레포트금지. 현재카탈로그facility기지2내용은있지만시설메뉴/배경JPG실제렌더는아직안봤음. 출항확인문구와비용시간치환추적,mode5/다함선/전술항구route,0320request/031Fcachebatchaudit도남음. 이번새코드테스트없으며E116597PASS/24SKIP와구분. 전체goalACTIVE.

## E118 — 출항후 실제102→101워프 커밋, 클라이언트 도착전환 미완료 특정 (v130)

**SERVER_WARP_COMMITTED / CLIENT_CONTEXT_STALE / TACTICAL_NOT_ENTERED.** 기존v33server3480/client2340/PG1812그대로사용,재시작/재로그인/DBfixture변경0. fresh live-warp-pre-v130.json은unit2/grid102/base0/mode4/loss0/0/gen1/cruising10을확인. 역할카드→ワープ航行→화면왼쪽인접그리드→확인1회. 확인문은통상320MCP/작전목표근접80MCP로표시되지만원본비용실제차감검증아님.

실제0B01 UTC2026-09-07T04:14:23.6438185+09:00 Success,authority29,source102/destination101. DB unit|2|2|101|0|4|0|0|1|9. 기존출항이벤트1회유지. live-warp-after-v130.json 및live-warp-settled-v130.json에동일실행결과. 서버응답추가104byte는0325두유닛projection경로이며현재code는0B07후EncodeTacticalBattlefieldUnits만추가한다. 도착직후0F02요청없고0300시간동기화는계속옴.

**클라상태불일치:** window-warp-settled-v130.png는여전히전략HUD. warp-cache-v130.json UTC19:16:57.1742092Z에서unitBase0/mode4/**cruising10**,strategyFlag1,tacticalFlag0,sceneMode2,facilityIDs[2]. DB9를클라반영PASS로승격금지. 현재플레이어컨텍스트는이전그리드장면에남아있다. 프레임의항속0은별도E105표시버그이며이번내부10→9전환누락과구분한다.

**Ghidra새분기:** dispatcher0B07→004BEE20→00517CD0. 004BEE20은world+2A58F8전략flag검사;00517CD0은*DAT02215E2C값1/2/3에따라manager42/6F/56의root에event0x16(type0B07,payload)을큐잉한다. vtable006702C0+8=005751B0를읽고그주소에ReceiveResult_MoveGrid_WarpEffectWait함수정의추가(원본EXE수정없음). 이함수는event0x16/type0B07소비후local+34=1,전역009D2A7C=2/009D2A74=0으로전환하고상태3까지기다린다. 004D6B70은warp모델/효과존재시상태2의시간을증가시켜3으로변경한다.

warp-effect-v130.json UTC19:19:22.3879954Z: **warpEffectState1/time0**,warpModel90104624/child90066416,warpEffect90011728모두nonzero. 따라서효과리소스없음이나상태2fade정체라고단정하면안된다. 정상event수신후상태2로가지못한경계가다음대상. 0055FC28의07 0B는CALL상대offset일뿐opcode소비자가아님. 상세decompile및판정work/20260904-warp-state-reverse/evidence/warp-transition-v130.json.

**다음:** 현재warp대기실행을보존한채decoded0B07캐시/현재UI manager/명령flow의event0x16수신과dispatch후처리를RPM/정적분석으로대조. sourcegrid/destinationgrid의로컬정보갱신순서와0B09/0B0A필요조건을회수하여서버변경을TDD로연결한다. 무조건NotifyTactics추가나DB텔레포트/새로그인으로전환누락을감추지말것. v130워프확인재실행금지. 실제전술진입/공격/종결은이번0회. 전체goalACTIVE.

## E119 — 원본0B07 캐릭터 응답ID 불일치 확인과 v34 수정 (v131)

**ORIGINAL_RESPONSE_ID_MISMATCH_PROVEN / LOCAL_FIX_TESTED / v34_NOT_DEPLOYED.** 동일v33실행을입력없이RPM검사. warp-event-v131.json UTC19:21:26.7202882Z에서world+437714의decoded0B07은time0/Id0/grid101/base0/mode0/records1(unit2,cruising9)였다. 클라가파싱하지못한것이아니라완료ID가잘못됨. UIroot89335856/mode2/registry152211488,manager6F222573936존재,currentflow0. warp-matcher-v131.json의world+3584A0=2,world+357EC0=1,pendingrecord010B0000070B000048FD1900은0B01→0B07대기잔류를확인. memoryWrites0/입력0/재시작0.

Ghidra dispatcher0x0204 SSCharacterIDResponce가004BA3E9에서world+3584A0에**캐릭터ID**를저장. 0B07branch는local_1c=param3[1](Id),004BDD50/7C는그값이로그인캐릭터와같을때localresult004BE350및expected-responsequeue매칭을진행. 따라서unit배열의Unit값과별개로완료Id는캐릭터여야한다. 기존OriginalMoveGridAuthority.Notification은Time0/Id0/Mode0이었고NaturalAuthoritySession이그대로실전송한것이오류. 전체decompile/pending증거는work/20260904-warp-state-reverse/evidence/warp-identity-v131.json및warp-matcher-v131.json.

NaturalAuthoritySession.ProcessMoveGridAsync의0B07인코딩에 Id=_worldCharacterId,Time=_gameClock.Tick,Mode=movedUnit.Mode를연결. records의unitID와cruising은기존authoritative결과유지. 원본시간지연/비용복원주장아님. 새OriginalWarpCompletionIdentityTests는캐릭터41/함선7분리fixture로realencrypted0B01→0B07응답을검사하여Expected41/Actual0 RED를확인후GREEN. 시간24/목적101/base0/mode4/recordsunit7/cruising9검증. storage부분은testdouble이며PG원자성/native전환테스트아님.

전체 **598PASS/0FAIL/24SKIP(total622)**. TRX E:/logh7-build/test-results/warp-identity-v131/warp-identity-v131-green.trx SHA16D69CCD955AFE8B3656FFB6E4019E15F3D6D3C6685313504CEE4EBB4E1F7A00. Release win-x64 self-contained publish E:/logh7-build/server-v34-warp-identity-v131 성공. DLL937BDD9785FC57E0554F51DE7E8AA36DC6DDBF6B2820F4733ACA2E490944481E. ZIP E:/logh7-build/v34-warp-identity-v131.zip SHAD351855AE3697918693231E93EF35CD90A9FCFED0943B8579C1A9799AD79C58A. migration25동일. 아직게스트배포없음/현재v33유지.

**다음실행:** 현재DBgrid101/base0/mode4/cruising9는이미커밋됐으므로reset/텔레포트/워프replay금지. v34데이터보존배포와자동로그인으로이위치/항속복원을검증하되그재접속전술화면을이번워프전환수정PASS로세지않는다. 새정상플레이이동에서0B07Id=캐릭터,expectedqueue해제,클라항속갱신,0F02/전술HUD를확인해야한다. 004BEE20event0x16과전체expectedqueue의상호작용때문에Id수정만으로모든문제가끝났다고단정하지말것. v129consumedactions/credential및v130워프submit반복금지. 전체목표ACTIVE.

## E120 — v34 배포·재접속 항속 복원·전술 HUD와 NPC 격침 확인, 귀환 미완료 (v132)

**V34_DEPLOYED / RELOGIN_CRUISING_RESTORED / TACTICAL_HUD_VISIBLE / NPC_DAMAGE_RECEIVED / DESTRUCTION_RETURN_PENDING.** 동일181000Zrun/server-v34-v132에side-by-side배포. 현재server4244/start2026-09-06T19:28:41.4903593Z,DLL937BDD9785FC57E0554F51DE7E8AA36DC6DDBF6B2820F4733ACA2E490944481E. client8540/start19:29:31.3154961Z/HWND0x000000000490042C,path동일run/client/exe,item116SHA동일. PG1812/start18:11:30.2695908Z/data/55432/proxy유지. DB중단0/삭제0/migration25동일. Stage/Stop/Start상태해시24867faf8f84e7054aeadf61dd2768182db9155f39f51f1cd7a9a04fd0d76479동일(account/character/unit/event/migrations범위). v132스크립트초안의광역PID치환이신규DLL해시944481부분을변경한것을전송전검사에서발견해정정;Stage부터는원래정확한hash로검증했다.

새auth-preflight/Default확인→File메뉴·닫기·ID포커스→credential-v132DPAPI38keyevents1회→v34공지로비→기존캐릭터진입. 모든UI입력은새캡처기준/재시도0. server-swap-v132 Stage/Stop/Start/Client및credential모두소비됨. 현재wire run/server-wire-v132.jsonl,stdout/stderr server-v132.stdout/.stderr. 기존v33바이너리보존. 새로운0B01워프는이번실행0회.

live-reconnected-v132.json/ live-tactical-v132.json에서DB unit2/grid101/base0/mode4/loss0/0/gen1/cruising9유지. departure-cache-read-v132.json UTC19:33:02.3077026Z에서클라kind3/cruising9/base0/mode4확인. 따라서E118워프커밋의재접속영속성/항속복원확인;**E119ID수정후새워프전환PASS는아직아님.** window-world-ready-v132.png SHA8033E466DDFB84683DF81C3FD46FAE2EC37E1A7BCA374BDF823C7E6482A014BA는전술레이더·BEAM/GUN/ENGINE/WARP/SENSOR분배·명령아이콘HUD를실제표시. 다만함선상세는NO TABLE/NO DATA,함선모델이나발사광선의직접관찰은이번없음.

**NPC 실제권위처리:** live-tactical-v132.json SHABCA4FD32758FF1D8C633E5F7392BF92AD2E3EAB67849317395B5B8C123CCE273의npc-ai결정은04:32:14.4688333+09부터unit2130706433→target2접근,04:32:18.217/21.203/24.209/27.210경4회fire,damaged25/50/75/100,destroyed마지막100. authority-notification-sent도각fire1frame. 후속0348/034A scene요청은계속됨. DB손실이0인상태이므로전투손실DB완료나귀환완료로승격안함.

새읽기tactical-entities-v132.json UTC19:35:07.8847726Z SHA405DEBDCC3AA82649C9A4611061D5B857E5DC62A01A1CD4BD04293E0D27D160A: tacticalFlag1/sceneMode0,kind3staticNumber100. 600entity슬롯스캔에서slot0unit2/power2/kind3/normal0/remaining0/maximum100/destroyArmed1/mode0/localFlag0,slot1NPC2130706433/power3/kind89/100/100/destroyArmed0/mode0/localFlag0. 피해수신은확인됐지만자기entity소거→귀환0F02가안옴. 전술플래그/잔존수만으로게임완성판정금지. 기함버튼클릭1회는정보/카메라변경없었고반복안함.

**다음실측경계:** 현재격침플래그1인자기kind3entity가왜소거·귀환되지않는지추적한다. 원본004C32A0는kind별staticNumber와피해값으로8D4/8D8/8DC를채우고끝에서004C1D20으로mode를적용한다. guest-return-observe-v101.ps1의기존entityfield맵/원본소거loop와kind3model/폭발리소스/5B8·5B9/5BC·5C0변화를대조. 이번서버코드수정/새suite실행0,새전술공격입력0. 서버PrepareInjuryReturn은클라새0F02에의존하므로죽은상태를수동DB치유/귀환으로덮지말것. v34새워프전환검증은귀환/정상플레이상태확보후필요. 전체goalACTIVE.

## E121 — 함선 모델 존재와 mode4 표시·격침 소거 차단을 실측 (v133)

**ORIGINAL_MODE_GATE_PROVEN / AUTHORITY_FIX_PENDING.** 직전 로그인 상태 확인은 로그인 문제가 아닌 전술 상태 문제로 다음 행동을 좁혔으나 게임 기능 수정은 아니었다. 이번에는 살아 있는 client8540/server4244/PG1812의 경로·시작시각·클라/서버 해시를 확인하고 읽기 전용 RPM을 수행했다. 재시작/입력/메모리쓰기/DB변경0. guest-tactical-lifecycle-v133.ps1과 tactical-lifecycle-v133.json을 새로 작성·실행. UTC2026-09-06T19:44:03.9415978Z, receipt SHA6F96B0B8A5CD1030C11C83EB075E396B2EA25B64E3070604940E5B134F8CB9FD.

자기unit2는 remaining0/destroyArmed1/effectState3/effectTime14595이나 **renderEnabled(+5BA)=0**. context active1/owner2/mode4, fallbackOwner0/fallbackMode0. 렌더러slot599가 존재하며 kindSelector18/model89138304(nonzero), initialized0. NPC2130706433은 renderEnabled1/contextOwner2130706434/mode0, renderer598/kindSelector53/model89177728/initialized1. 따라서 이번 자기함선의 첫 실패 경계는 모델 파일 누락이 아니다.

Ghidra004B5D50은 자기함선의 effectiveMode를 entity+8C0 context의 +31E에서 읽는다. 004B2740은 effectiveMode4면 entity+5BA를0으로 하고 반환한다. 004C94E0은 +5BA!=0일 때만 renderer를 찾고 표시 또는 격침 소거를 처리하므로, 현재의 mode4는 모델 표시뿐 아니라 entity active0/폭발 후처리도 막는다. 004C1D20에서 mode4와6 모두 entity+5C4=0으로 바꾸므로 E120의 entity mode0을 effectiveMode0이라고 해석하면 틀린다. mode5는 기지 상대위치, mode6/7은 +5BA=1 경로. 이 사실만으로 실제 서버 도착 모드6을 확정하지는 않는다.

**추가 원본 명령 의미 경계:** command60→00583E20의 GetMoveBaseMode vtable00676C48+8은00570940. assembly에서 flow context+40이 양수면 FLOW6,0이면 FLOW5. 이후005737D0은 FLOW5→004B4A00(4),FLOW6→004B4A00(5). 즉 단순 고정 출항이 아니라 spot-dependent toggle이다. 현재 NEW_DESIGN 서버는 mode4 요청을 base0/mode4 최종 출항으로 저장한다. 이전 E117의「宇宙港に駐留します」확인문 불일치와 함께 이 해석을 재검토해야 한다. 아직 항구/우주/전술의 완전 상태표 회수나 서버 수정은 안 했다.

원본 decompile 영수증 evidence/destruction-mode-gate-v133.json SHAC45217B64CC81285289AEA8C09ECD33AE76DCDD50CF801213EB0EA11834637B0. 위00570940 branch 숫자는 decompile에서 인자가 누락되어 assembly00570997/005709A8로 확인했다. 원본EXE 패치0, E105 미승인 유지. 새 테스트/빌드/배포0.

**다음:** command60의 실제 toggle 상태표와 0B06 request mode→0B0B/042F/0325 result mode 및 base/spot을 원본 소비자·문구·매뉴얼로 복원한다. 이를 기준으로 잘못된 출항 저장/전술 진입 투영을 수정하고 회귀시험한다. 단순 +5BA 클라 패치나 임의 mode6 투영으로 감추지 말것. 현재 격침 실행 보존; DB 치유/텔레포트/로그인·출항·워프 consumed 입력 재실행 금지. 수정 후 정상 상태 전환과 종결/귀환의 실제 화면·서버 영수증까지 필요. 전체목표 ACTIVE.

## E122 — 원본 전술 태세 4/5/6/7과 기존 출항 구현 의미 오류 (v134)

**MODE_SEMANTICS_CROSSCHECK / PREVIOUS_DEPARTURE_ACCEPTANCE_RETRACTED_IN_PART.** E121은 표시·격침 gate를 실제 RPM으로 특정한 진척이었다. 이번에는 원본 Ghidra0050D230(0050F968 포함)의 전술 태세 버튼→요청 및 현재 PostgresAccountStore.Departure.cs를 대조했다. 새 UI입력/재시작/런타임상태변경0. 이전 실행 신원을 이번 새 실측이라고 주장하지 않음.

0050D230의 버튼0x35/36/37/38은 DAT02216698에 각각4/6/7/5를 저장하고 state10으로 전환한다. state10은 같은 태세 유닛을 제외한 뒤 mode4/5만 기지 표적 선택을 요구하고,004B4490에 selectedUnits/targetBase/mode를 보내 commandID0x37을 직렬화한다. mode6/7은 기지 선택 없이 요청된다. 원본 constmsg.dat SHA5B3FAFBA7DD7230CDEB5F2FF9ACF9BBBE20FD95ADE25C425BC0D11AE645C383C 직접 재확인. group0 rows51-54는 駐留(자진영기지),航行(통상),戦闘(공격중시/기동저하),碇泊(기지궤도). 버튼 순서와 target 조건, E121 mode4 숨김/mode5 기지상대좌표/mode6·7 표시가 맞물려 **4=주둔,5=궤도정박,6=통상항행,7=전투**로 교차확인된다. 단 버튼→tooltip 개별 바인딩은 별도 회수하지 않았다는 한계를 영수증에 명시했다. group118 rows32-35는 원본에서 빈 문자열이므로 해당 알림만으로 이름을 회수했다고 주장하지 않는다.

기존 보존 매뉴얼 OCR E:/logh7-greenfield/evidence/manual-variants/internet-archive/gin7manual_djvu.txt의 態勢変更 절은 네 태세를 별도로 정의한다. 이어 出撃은 주둔함선이 나오는 별도명령이며 유닛당20초라고 설명한다. OCR오류/공백 존재, 이번 PDF직접렌더나 원본시간구현 아님. group18 command60 이름은 出港이나00570940의 spot-dependent toggle과 확인문을 같이 봐야 한다.

**정정:** 현재 저장코드72/75/80행은 request4를 base0/mode4 및 OriginalUnitDeparted로 기록한다. 이는 주둔 태세를 기지없는 우주 출항으로 저장하는 의미 오류다. E117의 DB/클라 위치·모드 반영 실측 자체는 유효하지만「정상 출항 실행·효과 PASS」로 더 이상 인용하지 말것. 원본 요청과 게임 규칙을 바꾸는 NEW_DESIGN으로 오류를 정당화하지 않는다.

증거 evidence/unit-mode-semantics-v134.json SHA192D7FBD791D1178FFBB5F7EABD471AD8848DC7305B2E4CE997E4C10E399B03C에 전체0050D230/상태맵/근거/한계 저장. 서버 코드수정·테스트·배포는 아직0. **다음 구현:** 주둔요청4는 기지/시설spot을 유지·연결,出港요청5와 실제出撃/항행6 경로를 구분해 저장·통지·재접속을 고친다. 기지없는mode4의 기존 잘못된 상태는 자동 DB치유가 아닌 명시적 상태수정·손실보존 경로를 설계해야 한다. 현 격침/모델 상태를 단순 클라flag패치나 mode6 표현층 치환으로 덮지 않는다. E105 별도 승인대기 유지, 전체목표 ACTIVE.

## E123 — 주둔 기지 보존과4→5정박 실제 저장·세션 수정 (v135)

**LOCAL_AUTHORITY_FIXED_PARTIALLY / 622_TESTS_PASS / NOT_DEPLOYED.** E122는 명령 의미 오류를 확인한 진척. 이번에는 테스트를 먼저 변경해 실제 격리Postgres에서 ExpectedBase1/ActualBase0 RED를 재현했다. PostgresAccountStore.Departure.cs는 mode4에서 BaseId를 지우지 않고, 성공 이벤트를 OriginalUnitStanceChanged로 기록한다(과거 이벤트 수정없음). IAccountStore.OriginalDepartureWrite에 TargetMode(default4)를 추가하고4/5만허용. mode5는 현재mode4에서만허용하고기지를보존한다. 같은태세 재요청은 거부하며 기존fingerprint 재생은 현재상태 반환으로 이후함선/태세를되돌리지 않는다.

NaturalAuthoritySession.Departure.cs가 zero-field own request4/5를 구분해 저장으로 전달한다. 실제 암호화0B06→0B0B/0325는 mode4 및mode5 모두 base2 유지. mode5미지원RED를 먼저 확인후GREEN. 동일테스트는 동시8요청중변경1,외부계정/버전거부,이벤트실패rollback,신규연결·세션재접속mode4/base2,4→5실제저장,그뒤과거fingerprint재생불변을검사한다. 기존「출항 후기지0」기대값은 원본 의미오류라 수정;권한·동시성·rollback 단언은유지. 지상spot/정박좌표까지완성이라고주장하지않는다.

전용hostPG37872/start2026-09-06T19:52:29.2040446Z,bind127.0.0.1:55805,data E:/logh7-build/unit-stance-pgdata-v135를새로초기화했다. 기존게스트DB와별개. suite후정확한PID/path/data/port검증후정상종료,postmaster.pid없음/listener없음,파일삭제0. guest입력/재시작/배포/DB쓰기0. 빌드환경NUGET_PACKAGES/TMP/TEMP E:설정.

전체ProtocolTests **622PASS/0FAIL/0SKIP**(실제DB테스트포함). TRX E:/logh7-build/test-results/stance-v135/stance-v135-full.trx. RED및focusedGREEN도같은폴더별도파일. 변경/한계영수증 work/20260904-warp-state-reverse/evidence/stance-authority-v135.json. publish/package는아직하지않았다.

**바로다음 필수:** mode4의실제우주항施設spot/spotOwner를카탈로그에연결해0B0B와0324캐릭터재접속투영에반영한다. 지금은여전히spot0으로원본UI가같은주둔요청을선택하므로아직배포하지말것. 현재기지없는mode4격침실행은이번코드로자동치유되지않으며손실보존종결경로별도필요. 전술042F/출격·항행6,이전워프전환,모델·피해·귀환실클라검증남음. 원본클라E105패치승인대기유지,전체goalACTIVE.

## E124 — 주둔 공용 부두 위치를0B0B·재접속0323에 연결 (v136)

**LOCAL_PUBLIC_PORT_PROJECTION_TESTED / NATIVE_UNSEEN.** E123은실제저장수정진척. 이번TDD에서주둔0B0B spot Expected2001/Actual0 RED를확인후수정했다. OriginalWorldEntryCodec.EncodeCharacter에optional spot/spotOwner를추가하고body+24/+28에직렬화한다. **캐릭터응답은0323이다. E123의0324표기는오류이며새테스트도처음그오류를따라프레임검색에실패해0323으로정정했다. wire타입은바꾸지않았다.**

NaturalAuthoritySession.Departure.cs의 FindPublicFlagshipPort는현재grid/BaseId의institutionKind4/spotKind6 중최저ID를선택한다. CurrentPublicPortSpot은persistedMode4/BaseId>0에서만그ID,그외0. 0B0B및세션내9개캐릭터인코딩호출은자기character/unit에만같은projection을사용하며NPC에는전파하지않는다. mode4요청은실제공용부두없으면저장전거부한다. 원본004C9170는base시설목록에서context+40=spotID를찾고+44(owner)=0이면공용spot로해결한다;private16/17소유자경로와혼동하지않는다.

실제격리DB+암호화세션시험: mode4/base2→0B0Bspot2001/owner0;새DB연결·세션0205/0F02→0323spot2001/owner0;4→5후0B0Bspot0;0322조회→0323spot0. 기존base보존·rollback·재전송·피해/세대보존검사유지. 전체 **622PASS/0FAIL/0SKIP**. TRX E:/logh7-build/test-results/spot-v136/spot-v136-full.trx. 영수증 work/20260904-warp-state-reverse/evidence/public-port-v136.json.

hostPG27684/start2026-09-06T19:58:02.7121915Z는이전시험전용unit-stance-pgdata-v135/127.0.0.1:55805로실행;끝에PID/start/path/data/port확인후정상종료/postmaster.pid없음. 파일삭제0/guest입력·DB쓰기·재시작0. 아직package/publish/deploy없음.

**제약/다음:** 이projection은현재카탈로그의공용부두만대상인NEW_DESIGN. 일반캐릭터장소영속화/사유공간/시설선택은아니며catalog변경시파생spot도변할수있다. 현재live격침base0/mode4는별도권위오류복구와손실영속화가필요하다. 재시작으로in-memory피해를잃거나DBteleport/치유하지말고,현재NPC최종피해영수증과권위손실저장경로를이어확인한다. 이후정상주둔/출항toggle UI·0321부두배경·전술종결실측필요. E105클라패치미승인유지. 전체목표ACTIVE.

## E125 — 피해 영속화 누락 실측과 별도 손실 저장 트랜잭션 구현 (v137)

**DURABLE_DAMAGE_STORAGE_TESTED / ATTACK_PATHS_NOT_CONNECTED / NOT_DEPLOYED.** E124는주둔spot구현진척. 이번 fresh guest-live-read-v132 -Tag casualty-v137은client8540/server4244/PG1812신원을검증하고UTC2026-09-06T20:02:32.8993786Z에DB unit2/grid101/base0/mode4/damaged0/destroyed0/gen1/cruising9,최종NPCfire04:32:27.2104305+09 damaged100/destroyed100을다시확인했다. evidence/live-casualty-v137-v132.json. 입력/DB쓰기/재시작0;현재피해를치유하지않았다.

**원인 확대:** OriginalTacticalBattleRegistry.Npcs.AdvanceNpcsAsync는RecordUnitDamage→Publish만수행하며Hosting.NaturalAuthorityServer.RunNpcLoopAsync는결과로그만쓴다. 플레이어ProcessTacticalCommand도3194부근RecordUnitDamage→통지뿐이다. PrepareInjuryReturnAsync(0F02전용)가유일손실저장·귀환경로여서NPC만고치면PvP가남는다. completed encounter에서NPC루프는skip하므로새AI공격을기다리는것은복구가아니다. ObserveShipGeneration은같은세대면max(메모리,DB)로합치므로실행중DB0재읽기가현재손실을0으로줄이지않지만서버재시작시메모리는소실된다.

**새코드:** IAccountStore에OriginalUnitDamageWrite/StoreResult 및SaveOriginalUnitDamageAsync(defaultNotSupported)를추가했다. PostgresAccountStore.Damage.cs는계정→함선행잠금,계정/인물/함선소유권,grid/shipGeneration/return상태,손실단조증가와number범위를검증한다. 동일누적손실은updatedfalse/이벤트추가없음. 실제변경은damaged/destroyed+authorityVersion+OriginalUnitDamaged event+계정stateHash를같은트랜잭션에저장한다. 기지/모드/위치/항속/세대를바꾸지않는다. 귀환이나치유가아니다. 기존테이블사용,신규migration0.

OriginalUnitDamagePostgresTests는미지원기본API NotSupported RED→구현후GREEN. 실DB동시8동일피해중변경1,25/0→100/100저장/새연결재조회,이벤트실패시함선rollback,이전손실재전송거부,다른계정/그리드/세대거부,손실범위검증,성공피해이벤트2개를확인. 전체 **623PASS/0FAIL/0SKIP**. TRX E:/logh7-build/test-results/damage-v137/damage-v137-full.trx. hostPG54772/start20:04:10.1790457Z는시험전용data/unit-stance-pgdata-v135/127.0.0.1:55805;끝에PID/path/start/data/port검증후정상종료/postmaster.pid없음. 파일삭제0. 현재게스트변경0.

**필수 다음 연결:** 새SaveOriginalUnitDamageAsync는아직실제공격에서호출되지않는다. 레지스트리참여자에소유계정/캐릭터/함선세대에바인딩된저장callback(또는동등한권위서비스)을연결하고,NPC와플레이어두경로에서**DB커밋→RecordUnitDamage→Publish**를보장해야한다. grid명령lease보유중인공격에서PrepareInjuryReturnAsync를호출하면재진입grid lock교착위험;피해저장과귀환은분리한다. DB실패시메모리피해/0426전송없음,끊긴피해자/중복세션/새함선세대도회귀검증. 기존guestv34의이미발생한100/100을안전하게인계하는별도종결경로는여전히필요. storage테스트를native저장완료로인용금지. v135/v136상태·spot변경도아직미배포,전체goalACTIVE.

## E126 — NPC/PvP 피해를 저장 후 적용하도록 실제 경로 연결 (v138)

**ATTACK_COMMIT_BOUNDARY_CONNECTED / 626_TESTS_PASS / DISCONNECT_RACE_AUDIT_PENDING.** E125의손실저장API를실제공격에연결했다. Registry.Presence에선택적PersistDamage callback,UpdateParticipant에동일인자를추가했다. CommitUnitDamageAsync는현재grid/unit/generation의최신presence를선택해callback을await한뒤RecordUnitDamage한다. NPC AdvanceNpcsAsync와플레이어사격처리가모두이함수를호출한다. NPC/명시적인메모리호환참여자는callback이없다.

실제세션 PublishOwnParticipantSnapshot은persistedunit이있으면 BindOwnDamagePersistence callback을등록한다. accountId/store/character/unit/grid/shipGeneration을지역값으로캡처하여세션변경으로피격대상이바뀌지않는다. SaveOriginalUnitDamageAsync결과의identity/loss를검사하고불일치는예외. NPC루프에서피해자세션필드를동시에변경하지않으며다음권위조회에서version을갱신한다. DB미지원은조용히무시하지않음.

TDD: NPC저장실패callback무시 RED(No exception)→GREEN;플레이어실제암호화0406에서도동일RED→GREEN. 두경로실패후피해0, NPC0426미전송검사. OriginalSessionDamagePersistenceTests는실DB+새세션0205/0F02등록후피해자추가요청없이레지스트리손실적용→새DB연결조회에서25/0/version2를검사;처음Expected25/Actual0 RED후세션callback연결GREEN. 이시험은공통commit진입의실DB연결증거이며실제클라/NPC루프전체DB end-to-end와구분.

전체처음625PASS/1FAIL은OriginalInjuryReturnSessionTests.MoverStore가새저장메서드미지원이어서실패. 해당시험double에인물/함선/grid/generation/손실검증및피해저장을추가하고기존scene-ready명령검사는유지했다. 최종 **626PASS/0FAIL/0SKIP**,TRX E:/logh7-build/test-results/damage-v138/damage-v138-final.trx SHA4786D8FA9B34CD17C894C5B12556A832E353F66404CFD2D4EDC04397F16C7721. hostPG11684/start20:09:51.9145236Z/시험전용unit-stance-pgdata-v135/55805는확인후정상종료,파일삭제0. guest입력/DB쓰기/배포/재시작0.

**배포 전 다음:** 현재CommitUnitDamageAsync는대상presence를조회하는시점에접속종료로제거됐으면callback없이메모리적용할위험이있다. 동일unit중복세션에서최신presence의callback선택·오래된incarnation제거도검증필요. 공격이채택한대상과지속적인소유권저장sink를바인딩하여disconnect사이에도저장누락을막고,DB실패/await중알림없음/종결손실재시작복원을검증한다. 아직이경쟁조건해결전deploy하지않는다. 또한guestv34의기존100/100손실은새코드로소급저장되지않으므로현재실행종결인계가별도로필요하다. v135-138미배포,원본UI태세/귀환검증미완료. 전체goalACTIVE.

## E127 — 접속종료 피해 저장 누락 수정과 v35 빌드 (v139)

**DISCONNECT_COMMIT_BOUNDARY_FIXED / 630_PASS / v35_BUILT_NOT_DEPLOYED.** E126은공격저장연결진척. 이번에는RemoveParticipant후이미채택한피해를commit할때저장callback이사라지는문제를RED2건(예외없음/저장대기없이피해적용)으로재현했다. Registry에unit별최신durablePresence를유지하는_damageOwners를추가해transport제거와분리했다. callback없는중복등록은기존durablebinding을지우지않으며revision/generation이오래된등록이새binding을덮지못한다. 이map은offline전투참여자명부가아니다.

CommitUnitDamageAsync는expectedGeneration을받아전후현재generation을검사하고,저장binding의grid/generation불일치면거부한다. NPC는대상snapshot.ShipGeneration,플레이어사격은대상participant.ShipGeneration을넘긴다. DB실패·대기·disconnect에서DBcommit→메모리손실→통지순서를유지한다. 최신세대가도착한후과거hit가새함선저장callback을부르는것도막는다. NPC및순수in-memory호환참여자에는아직별도DBowner가없으며NPCcampaign영속화완료주장아님.

회귀6개: NPC저장실패시피해/0426없음,실제암호화플레이어0406저장실패시피해없음,접속종료후저장실패전파,접속종료후저장대기동안피해0→해제후25,oldgen0→newgen1거부,oldgrid101→newgrid102거부. 새generation fixture에immutableclass를record로잘못취급해컴파일오류1회후명시적constructor로정정했다. full실행완료는session76027동일handle로확인. 전체 **630PASS/0FAIL/0SKIP**,TRX E:/logh7-build/test-results/damage-v139/damage-v139-full.trx.

Release win-x64 self-contained **E:/logh7-build/server-v35-stance-damage-v139** publish성공. v135-139의주둔기지보존/4→5정박/공용spot/피해저장/공격commit/disconnect수정이포함된다. migration25유지. 아직ZIP/guest배포/서버재시작없다. hostPG35760/start20:14:20.6157139Z/시험전용unit-stance-pgdata-v135/55805는신원확인후정상종료. 삭제0,guest입력·DB쓰기0.

**다음 실제플레이경계:** guestv34는여전히NPC누적100/100이DB0/0인상태다. v35재시작은이기존메모리손실을자동인계하지않는다. 기존run최종피해/동일계정unit2/gen1/grid101/서버해시를읽기검증한후,이미구현된SaveOriginalUnitDamageAsync로권위손실을정상트랜잭션으로인계하는정확한one-shot복구도구를준비해야한다(손실을소스로그에서맹목재생하지말고동일실행메모리와대조;currentmode4/base0는임의치유/teleport금지). 그후v35데이터보존교체/자동로그인/격침귀환및정상태세·새워프진입을실클라검증한다. 함선소거가mode4에서막힌기존불일치의정상화도아직남았다. 원본EXE/E105패치미승인유지,전체goalACTIVE.

## E128 — 실행중 v34 격침 손실을 v35 저장 함수로 실제 인계 (v140/v141)

**LIVE_DAMAGE_CHECKPOINT_COMMITTED / RESTART_NOW_LOSS_SAFE_FOR_THIS_UNIT / v35_NOT_DEPLOYED.** E127은disconnect저장수정·v35빌드진척. 이번 freshRPM tactical-lifecycle-v140.json UTC2026-09-06T20:16:38.2965833Z SHA1F11ED89111CC2B99FA12D8CCDC68DC0DFBAB58F685C684E34BE1AD1895EA6B1에서동일client8540/server4244/PG1812확인. unit2/normal0/remaining0/max100/destroyArmed1,mode4,cruising9. 최종서버NPCfire100/100과교차확인했다.

원본프로세스쓰기없이새 DamageCheckpoint one-shot콘솔도구를만들었다. work/.../damage-checkpoint-v140은초안,**v141이성공본**. v141은정확runroot/클라·서버PID/path/start/hash/PGPID/data/port/freshRPM15분/최종NPCfire/DBunit2character2grid101base0mode4gen1cruise9version29loss0/0을검사한다. dry-run/commit별CreateNew영수증으로재실행차단. 연결secret은guest DPAPI에서환경변수로만전달하고출력안함. SaveOriginalUnitDamageAsync만호출;직접SQL UPDATE/텔레포트/치유없음. stage는새도구디렉터리만추출.

v140ZIP전송exec45272가아직진행중인데stage/dry-run을너무일찍실행해실패. 동일전송handle완료확인후읽기전용stage상태확인(ziphash일치/destination없음/receipt없음)하고준비했다. 이어v140실제dryrun은IOException/before null로중단,DB쓰기0. v141에서쓰기중인wire파일을FileShare.ReadWrite로읽도록고치고단계명진단을추가했다. v141ZIP전송35533은완료까지기다린후stage실행했다. v140실패영수증은보존했고재사용하지않았다.

**v141 preflight:** damage-checkpoint-v141-dry-run.json UTC20:22:42.9542151Z VERIFIED_DRY_RUN,DBbefore unit2/grid101/base0/mode4/loss0/0/gen1/cruise9/version29. **commit:** damage-checkpoint-v141-commit.json UTC20:23:08.1507671Z DAMAGE_CHECKPOINT_COMMITTED. after는동일unit/grid/base/mode/gen/cruise에damaged100/destroyed100/version30. 기존위치·모드·항속·함선세대보존검증. 게임입력0/재시작0/치유0/relocation0.

독립후속 guest-live-read-v132 -Tag checkpoint-after-v141의 live-checkpoint-after-v141-v132.json UTC20:23:12.9506016Z READ_ONLY_RUNTIME_VERIFIED에서 **unit|2|2|101|0|4|100|100|1|9**,migration25,departure1개유지확인. 따라서이기존격침은이제DB에보존되어재시작시0/0으로잃지않는다. 전함선/모든세션상태의스냅샷인계주장아님. 정상공격의자동저장실클라증거가아닌,구버전누락에대한검증된one-shot운영인계다.

성공도구 E:/logh7-build/damage-checkpoint-v141 및ZIP SHA3C2D91C93534CCA3D19EB8C12BAD5AC78DF550B12489853390A6ADF1A3EDBADE,helperDLL3BC2EB933B90163FD1CD448058BF3EB2B302CC72F359F8BA71C7FB1F7C621D01. 함께실행한Logh7.Server.dll은v35 SHA7DDDBC41C5AD4F7C29CEFB7DEB1F1B6C307E184F30FEE015289466E865122F01와일치. 서버본체는아직v34server4244. v141stage/dry-run/commit소비됨,**재실행금지**.

**다음 바로실플레이:** v35정확패키지/실행신원재검증→PG1812/data/55432및proxy보존한서버·클라교체→자동로그인→0F02에서persisted100/100으로PrepareInjuryReturn이실제우호귀환행성base2/grid102를커밋하는지확인한다. 새함선복구가언제발생하는지손실/세대로그와분리해서검증. mode4/base0의구버전오류는손실인계에서수정하지않았으며새재접속귀환경로가해소하는지확인해야한다. 주둔/출항toggle/부두그림/정상warp→전술/자동피해저장/종결은미검증. E105클라패치미승인유지,전체goalACTIVE.

## E129 — v35 실제 배포·자동로그인·손실보존 귀환 및 새 함선 복구 (v142)

**V35_LIVE / NATIVE_RELOGIN_RETURN_AND_RECOVERY_OBSERVED / STRATEGY_PORT_HUD_VISIBLE.** E128의기존피해인계뒤v35를side-by-side배포했다. 현재server1084/start2026-09-06T20:26:16.1538048Z,path181000Zrun/server-v35-v142/Logh7.Server.exe,DLL7DDDBC41C5AD4F7C29CEFB7DEB1F1B6C307E184F30FEE015289466E865122F01. ZIPv35-stance-damage-v139.zip SHAB7A3D107ED8F839D3E42BA9F61FF91BA0734CA5F46F37A9E50B39DB6E53BBC81. 현재client2428/start20:26:34.7656141Z/HWND0x0000000003DE03DC,동일client/exe,item116SHA동일. PG1812/55432/data와proxy보존,DB중단0/삭제0. Stage→Stop→Start 상태hash9e309a3da326b9aa1cec82feec5ea4935234e3839b0483e2963836c772d11925동일. v142 Stage/Stop/Start/Client는모두소비됨. wire server-wire-v142.jsonl,stdout/stderr server-v142.stdout/.stderr.

새interactive/auth-preflight완료. click-v142초안이oldDLLhash를검사해첫File메뉴입력전실패했으므로새v142bscript에정확v35hash를고정하고재관찰뒤입력했다. 첫성공File메뉴캡처는template의__TAG__두번째치환누락으로guest **__TAG__-v142.png**에저장되었으며hostevidence/filemenu-b-v142.png로회수했다;클릭반복없음. 이후CloseMenu→FocusId. credential-v142첫호출은id-focus이름/LOGIN-ID명칭불일치때문에입력0/키0으로실패. 실제focus-id/FocusId영수증을검사하는v142b로정정후credential-v142b1회성공. 자동로그인확인: window-lobby-v142.png에v35공지. GameStart→기존캐릭터1회. 사용자수동로그인요구금지.

**실제 귀환과 복구:** live-returned-v142.json UTC20:32:18.5367541Z unit|2|2|102|2|4|0|0|2|10. events-returned-v142.json은기존version30 OriginalUnitDamaged100/100/gen1 → **version31 OriginalUnitInjuryReturned100/100 sourceGrid101/base0→destination102/base2** → **version32 OriginalFlagshipRecovered previousGeneration1→2/previousCruising9→10/previousKind3→3**를확인. returnId dbf4a678-2490-c4dc-49ea-37dd275aa0db. 손실로그를남긴정상귀환/새함선복구이며수동치유/DBteleport아님. 기존v26/27과이번v31/32를혼동하지않는다.

초기window-world-ready-v142.png는전환중캐릭터선택overlay였으므로완료화면아님. 후속 **window-return-settled-v142.png**는전략HUD로실제전환했고,오른쪽 **宇宙港 / 旗艦桟橋**,위치 **惑星帰還惑星（仮）**가표시됐다. 따라서v136spot연결이원본HUD에서식별된다. 현재전술HUD가아니며전술모델/격침애니메이션을이번에봤다고주장하지않는다. 항속HUD0표시는E105원본표시버그여전히미패치;DB항속10과구분.

**바로다음:** 지금정상로그인된grid102/base2/mode4/spot부두/함선세대2상태에서역할카드出港toggle를선택해이번에는request5/responsemode5/base2/spot0이되는지실제확인한다. UI카드메뉴·확인문·행성모드전환을각새스크린으로검증. 그후warp→통상항행6의정상mode계약/항속변화/전술재진입/새공격피해자동DB저장·종결을검증한다. 이번귀환은v34피해one-shot인계+재접속이며v35실시간피격무입력저장/native종결애니메이션PASS아님. 원본EXE/E105미승인유지,전체goalACTIVE.

## E130 — 실제 출항4→5·기지 보존·부두→궤도 HUD 반영 (v143)

**NATIVE_UNDOCK_COMMAND_EFFECT_PASS / NEW_WARP_NOT_RUN.** v35server1084/client2428/PG1812그대로유지. 새화면확인→직무권한카드탭→카드→出港→확인1회. 카드열기직후캡처는아직이전panel이어서추가클릭없이settled관찰후出港을눌렀다. consumed role-v143/card-v143/undock-open-v143/undock-submit-v143영수증,입력재시도0. DBfixture변경/재시작/로그인0.

확인문 window-undock-dialog-v143-v142.png는이번에는「惑星or要塞名から宇宙へ上がります」다. E117의宇宙港に駐留します와달리부두spot가있어서원본FLOW6→mode5출항선택이실제작동한다. 비용/시간/행성이름자리표시자는여전히미치환이라완성된설명문판정은아님.

실제0B06 frame-processed timestamp2026-09-07T05:35:51.4922447+09:00 Success,departure-unit2/authority33. events-undocked-v143-v142.json에서 **OriginalUnitStanceChanged version33 mode4→5,base2→2,grid102/gen2/cruising10/loss0/0유지**. 기존잘못된OriginalUnitDeparted version28이벤트는그대로보존. 새서버단위효과로반드시구분한다.

**원본메모리:** departure-cache-read-v143.json UTC2026-09-06T20:36:46.1650109Z,같은client/server/PG신원검증,memoryWrites0. unit2/kind3/**unitBase2/unitMode5/spot0/spotOwner0/cruising10**. baseSummary/facility각1/ID2,actionContextnonzero. 따라서응답을보냈다는것만이아니라원본상태반영까지확인. window-undocked-v143-v142.png는HUD가 **宇宙港/旗艦桟橋→惑星／要塞軌道上/艦内**로전환했다. E105항속HUD0은내부10과구분,미패치유지.

**다음:** 현재정상정박mode5/base2/grid102에서신규워프를검증해야한다. 단현MoveOriginalGridUnit이mode를무조건보존하므로base0목적지에mode5를남길수있다. E121의원본mode5는base상대위치전용이다. 새warp목적지의base/mode계약을소스와원본0B07/0B08소비자로확인하고필요시출발mode5→도착항행6을저장·통지에일관되게수정한뒤실제이동한다. 표현층mode만치환하거나과거워프를replay하지말것. 이번은전술아님/공격0회. v35실시간피해자동저장/연속전술종결/전체명령·리소스검증미완료. 전체goalACTIVE.

## E131 — 우주공간 워프 도착 태세6 일관 저장·응답 및 v36 빌드 (v144)

**WARP_ARRIVAL_MODE_FIXED_LOCALLY / v36_BUILT_NOT_DEPLOYED.** E130의실제정박mode5/base2상태에서다음워프전에저장코드를검사했다. 현재지원되는101↔102 route는항상destinationBase0인데출발unit.Mode를DB/history/event/hash/returnedrecord에그대로유지했다. 원본004B2740/004C1D20의mode5기지상대좌표규칙과충돌한다.

PostgresAccountStore.MoveOriginalGridUnitAsync에arrivalMode6을적용했다. DBupdate mode,original_grid_move_command.result_mode,event mode/hash,result.Unit.Mode가모두6. 이벤트에sourceMode를별도로기록. 기존저장history의mode는변경하지않아과거replay는기록된결과그대로이며현재행을되돌리지않는다. 이번규칙은현재우주공간도착route용NEW_DESIGN으로원본서버전체도착/비용/대기규칙복원주장아님. 항행6 의미는원본전술태세역추적근거(E122). 지도route/비용확장미구현이며pure OriginalMoveGridAuthority.Notification 기본Mode0은실제세션의committedmode override와구분한다.

OriginalUnitModePostgresTests의이전mode4그대로보존기대는정정했다. fixture출발mode5→새DB워프결과Expected6/Actual5 RED→GREEN. 독립canonicalhash mode6,history/reopen보존,이후fixturemode7후과거replay현재7유지검사. 추가실제DB+암호화0205/0F02/0B01로반대방향워프하여0B07mode6/base0/cruising8확인. 인증은시험fixture이며native게임입력이아니다. 현재guest정박함선이동0/재시작0.

전체 **630PASS/0FAIL/0SKIP**,TRX E:/logh7-build/test-results/warp-mode-v144/warp-mode-v144-final.trx. Release win-x64 self-contained **E:/logh7-build/server-v36-warp-mode-v144** publish성공(session97308동일handle완료확인). migration25동일. hostPG62776/start20:39:28.8419614Z/시험전용unit-stance-pgdata-v135/55805는identity확인후정상종료/삭제0. 현재guest는아직v35server1084/client2428/PG1812.

**바로다음실플레이:** v36side-by-side배포/DB보존서버교체/자동로그인(현재mode5base2grid102gen2cruise10재접속유지확인)→새원본UI워프102→101→0B07Id/time/mode6/base0/항속9→pendingqueue해제·전술HUD·함선모델·실시간NPC/PvP피해DB를검증한다. v142consumeddeploy/credentials/v143출항반복금지. 현재정박까지성공했고warp새입력은아직0회. E105항속HUD표시패치미승인유지,전체goalACTIVE.

## E132 — v36 실제 워프→전술→NPC 누적피해 자동DB저장→귀환·복구 연속 검증 (v145)

**NATIVE_WARP_TACTICAL_NPC_DAMAGE_RETURN_CHAIN_PASS / FULL_GAME_INCOMPLETE.** v36server9812/start2026-09-06T20:44:24.4000440Z,path동일181000Zrun/server-v36-v145/Logh7.Server.exe,DLL443083E5D2D767716AF2064967D741F2B95EED806D1FF72817E6A091A33633E8. ZIPv36-warp-mode-v144.zip SHACF889690EA3A5FBDC37B74800CB32D1C002EE8CC065F8A7A158CD117D0A3791A. client10092/start20:44:42.8429702Z/HWND0x0000000004E80446,item116SHA동일. PG1812/data/55432/proxy보존,DB중단0/삭제0. Stage/Stop/Start상태hashfb497e7fb09f8f3d473fb93e2d40c8cf1d280ae7a9e373a86676194d63453603동일. v145Stage/Stop/Start/Client모두소비됨. wire server-wire-v145.jsonl.

새interactive/auth-preflight→File메뉴/닫기/FocusId→credential-v1451회→v36공지로비→기존캐릭터. 초기lobby캡처는BOTHTEC로고였으므로추가입력없이새관찰로로비확인. 재접속DB는grid102/base2/mode5/gen2/cruise10/loss0/0,전략HUD는궤도/함내. 출항반복없음. 역할카드→ワープ航行→왼쪽인접grid→확인1회. v145clicktemplateは全placeholder置換、全入力新観察、再試行0.

**실제워프:** 0B01timestamp2026-09-07T05:50:13.7503825+09Success authority34,source102/dest101. DB **unit2/grid101/base0/mode6/loss0/0/gen2/cruising9**. 이어05:50:14.982232+09원본클라0F02자동요청,world-bootstrap enemy-presentTrue. window-warp-after-v145.png는레이더/에너지분배/전술명령HUD에실제전환. 따라서이번에는재로그인으로도착전환을우회하지않았고E119응답ID수정및E131태세수정후정상새워프전환이실측됐다. 항속10→9는DB증거;이번크루징직접RPM별도미실시. E105화면0표시는여전히미패치.

**무입력자동전투·영속화:** NPC2130706433이접근후unit2에4회사격. events-tactical-live-v145.json에서OriginalUnitDamaged **v35=25/0,v36=50/0,v37=75/0,v38=100/100**,모두shipGeneration2/sourceGrid101. 최종NPCfire05:50:31.5625478+09 tick8764,직전75fire05:50:28.5596383. 이는v141운영인계가아닌v36실시간NPC→저장→피해통지경로다. 이번피해에수동DB쓰기/치유/재시작/재로그인0.

**연속귀환:** 뒤이어OriginalUnitInjuryReturned **version39**,source101/base0→102/base2,loss100/100,returnId39bba15c-2a9c-15fb-3888-9aa2afbf01c4. OriginalFlagshipRecovered **version40**,gen2→3,kind3유지,cruising9→10,과거손실100/100event보존. 현재DB **unit|2|2|102|2|6|0|0|3|10**. window-tactical-live-v145.png는이름과달리이미귀환후전략HUD다. 전술에머문화면이라고잘못인용하지말것.

**남은실제문제:** 전술함선위라벨은ダスティ・アッテンボロー로여전히인물명이다(사용자가고치라고한버그재현). 원거리작은표식은보였지만함선모델정체/빔·미사일·폭발애니메이션의시각검증은이번캡처에없다. 반환/복구저장은Mode6을보존해base2/mode6/spot0(HUD궤도/함내)로돌아왔다;원본귀환후태세4/부두로의정상규칙은별도정리해야한다. 전체PvP·NPCvsNPC실클라/모든전략명령효과/임무제안/전체리소스추적미완료.

**다음 플레이작업:** 현재살아있는귀환gen3세션유지. 인물라벨→함선명소비자역추적및원본0348/라벨이름join검증,귀환태세/부두규칙,이번공격의실제effect/모델/적기함구분을진행한다. 시험사격을반복하려고DBreset/로그인/warpreplay하지말것;새정상플레이시나리오로명시. 현재커밋630테스트이후추가코드수정없음. E105클라패치미승인유지. 전체goalACTIVE.

## E133 — 함선 라벨 클라이언트 변경 경계 확인 (v146)

E069의 인물명 직접복사 결론을 새 Ghidra decompile/listing으로 확인했다. 새 영수증 work/20260904-warp-state-reverse/evidence/E-133-tactical-label-change-boundary-v146.md. 004C3C2C의 parentage+7D 조건, 004C3C39의 length+B8, 004C3C3F의 text+BA, entity+6BC 복사를 확인. 함명은 별도 +28/+2A. 두 offset만 교체하면 parentage 없는 함선이 빠지고, 복사 블록에는 명시적 종료문자가 없어 재사용 시 짧은 함명 suffix 검증도 필요하다. 천체도 같은 entity 이름필드를 사용하므로 전역 이름 변경 금지. 서버 display_name 오염 없이 요구를 충족하려면 원본 클라의 함선 표시 전용 수정이 필요하다. 별도 승인 질문; E105 승인을 포함하지 않는다. 패치/배포/DB쓰기/게임입력0, 현재 runtime 재관찰0. 전체 goal ACTIVE.

## E134 — 귀환 행성 주둔 태세·부두 응답 연결 및 v37 빌드 (v147)

**LOCAL_RETURN_STANCE_AND_DOCK_PROJECTION_VERIFIED / v37_NOT_DEPLOYED.** E132의 연속 귀환은 base2/mode6을 남겼다. 현재 ReturnInjuredOriginalUnitAsync SQL은 위치/손실만 변경하고 mode를 생략했으며 recovery도 이를 보존했다. 이번 새 귀환의 도착 태세를4(주둔)로 저장·returnedrecord·이벤트에 일관 적용했다. 이벤트 sourceMode/mode를 추가하여 기존 payload 기반 authority hash에도 포함된다. replay 조기반환은 변경하지 않아 과거 귀환이 이후 새 함선을 다시 주둔시키지 않는다. 과거 이벤트/기존 살아있는 gen3 행 수정 없음, migration 추가 없음.

규칙 출처 구분: 매뉴얼 OCR gin7manual_djvu.txt 2470–2478은 격침 후 지정 귀환 행성으로 순간 복귀를 설명하지만 주둔 태세를 명시하지 않는다. mode4의 원본 의미는 E122. **귀환 도착4 선택은 현재 replacement server NEW_DESIGN**이며 원본 서버 규칙 복원 주장 아님. 사용자 승인된 서버 구현 범위에서 진행; 원본 라벨/E105 패치는 둘 다 미적용.

실제 격리 PostgreSQL OriginalInjuryReturnPostgresTests fixture를 mode6으로 설정해 expected4/actual6 RED를 확인했다(return-stance-v147-red.trx). 수정 후 rollback 시6 보존, 정상 return4/독립 datasource reopen/8회 replay/이벤트 source6→4를 검증했다. OriginalFlagshipRecoveryPostgresTests는 복구 후4 보존, 실제 암호화0205/0F02 응답0323에서 공용 부두 spot2001/owner0 확인, 이후 3회 정상 워프 사이 과거 귀환 retry가 현 mode6/위치/항속을 그대로 반환하는지 추가 검증했다. 이는 fixture 인증+실제 저장소·세션 응답 검증이지 native 화면 실측이 아니다.

최종 **630 PASS / 0 FAIL / 0 SKIP**, E:/logh7-build/test-results/return-stance-v147/return-stance-v147-final.trx SHA93D42E62327E6CB7DF6B5BDBE467C2457C8D3DFE79603C246D23B5184BC3599D. Release win-x64 self-contained E:/logh7-build/server-v37-return-stance-v147 publish exit0, DLL SHACF9419F82B1132E84217188BC2ED2C4DB8BDFDFE35A2B01A379EBECD5D528FF4. 테스트 hostPG53704/data unit-stance-pgdata-v135/55805 신원검증 후 정상 종료, 데이터 삭제0. guest 게임/서버/PG는 이번 턴 접근·재시작0; 최신 live 증거는 v145이고 현재 생존을 재확인하지 않았다.

다음: v37 데이터 보존 배포 전 guest 현재 신원/상태 fresh read. 현재 gen3를 SQL로 주둔시키거나 재격침 fixture로 조작하지 말 것. 정상 플레이에서 다음 실제 귀환이 부두 HUD를 표시하는지 검증하고, 같은 전투에서 무기/폭발 영상도 준비해 확인한다. 라벨 패치 승인 대기는 전체 서버 작업 blocker가 아니다. 전체 goal ACTIVE.

## E135 — v37 실배포·자동로그인·격침 폭발·주둔 부두 귀환 실측 (v148)

**NATIVE_CONTINUOUS_CASUALTY_DOCK_RETURN_PASS / PROJECTILE_TRAIL_AND_EXPLOSION_VISIBLE.** 현재 guest runroot는 동일 20260906T181000Z-departure-v124. server5216/start2026-09-06T21:05:03.7130110Z/server-v37-v148/Logh7.Server.exe, DLL CF9419F82B1132E84217188BC2ED2C4DB8BDFDFE35A2B01A379EBECD5D528FF4. client10160/start21:05:30.3684375Z/HWND0x000000000178044A/item116 AEF3827602CD13A395618BDEF0F48F44BCB8ED60F4FA9C2017F2D2E128660F2F. PG1812/55432/기존data 및 proxy 보존. stage/stop/start hash4a1cbc0d73f9d189e554c73c720f98b76b2ac1076120348ad7613f2402c98788 동일(계정/캐릭터/함선/이벤트/마이그레이션 범위). DB중단0/파일삭제0. v148 Stage/Stop/Start/Client 소비, 반복금지. ZIP v37-return-stance-v147.zip SHAAFAF4E5D25636BC5020A97F94E9E262E7D888317E7403A5A8EA025067EDC9A59.

fresh events-pre-v148-v145는 기존 gen3/grid102/base2/mode6/loss0/cruise10 확인. Windows sky 캡처는 창 재선택 포함2회 SetIsBorderRequired 0x80004002, 입력0 후 기존 guest 도구로 전환. interactive/auth-preflight→File/CloseMenu/FocusId→보호된 자격증명1회→v37공지 로비→GameStart→기존캐릭터. credential/capture host handle12539가 진행중인데 이미지파일을 일찍 읽어 파일없음 오류가1회 있었으나 같은 handle 완료 후 파일을 읽었다; 로그인/입력재실행0. 실제 재접속도 gen3/mode6 그대로, 기존 행을 임의로4로 바꾸지 않았다.

정상 역할카드→ワープ航行→왼쪽grid→결정1회. UI확인 직전65초 capture-only 시작(handle66969), 게임입력은 별도 fresh screenshot 기반1회씩. 신규 MoveGrid event version41 source102→101/mode6/cruising10→9/gen3. NPC2130706433→unit2 실제 fire 21:10:34.0842433Z/37.0610555Z/40.0580843Z/43.0614379Z. 손실DB event42=25/0,43=50/0,44=75/0,45=100/100. 재시작/재로그인/수동DB쓰기 없이 **event46 OriginalUnitInjuryReturned sourceMode6→mode4/source101base0→102base2**, event47 OriginalFlagshipRecovered gen3→4/cruising9→10/kind3 유지. returnId db72a53c-d9bf-1cfa-1d02-8ee4adfee3d9. 현재 unit|2|2|102|2|4|0|0|4|10.

**실제 화면:** window-return-after-v148.png에 전략 HUD의 宇宙港/旗艦桟橋가 표시된다. E134 새 도착태세+기존 spot2001 연결이 native에서 동작했다. 이전 전술 HUD와 혼동 금지.

**연속 캡처:** battle-capture-v148.json은21:10:06.0426833Z–21:11:11.0231097Z 358 PNG/게임입력0/메모리쓰기0. ZIP 전송 handle55231 완료 확인, 새 host evidence/battle-frames-v148로 해제, 모든358개 SHA가 guest 영수증과 일치. 처음+마지막과 대표 사격 프레임만 눈으로 검사했으며 전프레임 시각검수 주장은 아님. 0162(21:10:35.2221402Z)에는 푸른 발사체 점과 긴 궤적이 있으며 사격 전0155/직후0159에는 없었다. 0204에는 푸른 점, **0206(21:10:43.4519693Z)**과0210에는 최종100/100 뒤 큰 주황/백색 폭발·섬광과 인물 라벨 소거가 보인다. 0224에는 전환 중 큰 회색 3D함선이 보임; 이를 실제 전투 함종/적 모델의 확정 증거로 쓰지 않는다. 수평 흰 선/고정 배경 별은 발사증거가 아니다. 빔/미사일 정확한 종류 및 모든 무기별 source/target/effect join은 미검증.

주요 SHA: events-return-after-v148.json DDCC3A6A8D262F6290368DED6D64584724B297581C14565390DB2BD4696AE6BC; window-return-after-v148.png 91120118081FD2796FAF950A39C1CC7F8DC0355CCC01C4CD3B644FC3A410B35F; battle-capture-v148.json E1E40F84126FCC1EFAE3370E9AABD84DB0C5381CD9C20C5E3483798AC0F72CC5; battle-frames-v148.zip D1A51B25BE4320E773322D42772FFF7D4A710DE11D2CDDBBBCD8DCAC890E82C6; 0206.png 27B7ADB0B7637F60B7FE62EAC6F66D268702196BB6B0E23E31D2681C08334717.

다음: 현재 gen4/주둔/부두 세션 유지. 공격자가 화면 밖에 있어 정확한 무기종·아군 공격·적 모델식별은 이 연속캡처만으로 완료되지 않는다. 기존358프레임과0426/arms·시각리소스 소비자 분석을 먼저 이어가고, 새 실제 입력은 새 장면 관찰을 기준으로 한다. NPCvsplayer 피격·폭발·귀환 한 경로는 확인했지만 PvP/NPCvsNPC 및 전체 전략/전술기능은 미완료. 라벨/E105 EXE패치 미승인 유지. 현재 helper guest-click-v148/guest-window-observe-v148/guest-return-events-v148와 auth-preflight-v148 신원 사용; credential/warp/배포 재실행금지. 전체 goal ACTIVE.

## E136 — 무기 ID→효과→모델 발사 위치 역추적 (v149)

영수증 work/20260904-warp-state-reverse/evidence/E-136-arms-model-launch-points-v149.md. 새 Ghidra004C7790/004B3460/004E4450/004E6010/004E62B0/004DC940/004DC8C0 확인. arms0..7→effect1/BEAM,8..11→3/GUN,12..15→2/MISSILE;16..26/FF는0426함선피격 경로에서 시각효과0. 00772DF4 원문bytes로이름확인. 이 문자열은 actor model의 entry table+108/count+10C를 검색해 transform+58을 얻는 발사위치 selector다. 전체리소스추적에서 모델 geometry뿐 아니라 발사점까지 연결해야한다.

v148는 trail/explosion실측이나 npc-ai로그에 Arms가 없어 무기 ID는 source로만 예측할수있었다. OriginalNpcEvent nullable Arms와 fire시 실제0426무기값 기록을 추가. host타이머 실제JSON missing arms RED→GREEN. NPCvsplayer/NPCvsNPC 송신0426 byte14와 event Arms 일치/이동null 추가검사. 첫 검사 전역hit1개 가정은NPC상호사격2개로실패해 actor10으로정확히좁혔다. 최종 관련34PASS/0FAIL/0SKIP; full630은v147과거결과로이번전체검사아님. TRX SHA4EFB9275DBDE1A22B1796A571BD9FD7D78489A72ED517EF62F907EA9132AF4E0. production은로그필드만 변경, gameplay/wire/DB변경0; 미배포이며 guestv37에는아직없음. 다음은 모델별 발사점/실제플레이어사격 검증, passive격침 반복금지. 전체goalACTIVE.

## E137 — 247개 함선 MDX 노드 추출·model18 발사점 결손 후보 (v150)

**STATIC_NODE_INVENTORY / MODEL18_RUNTIME_JOIN_UNVERIFIED.** scripts/mdx_nodes_v150.py는005F6230의header88/count+4/stride232로직을따라노드앞64bytes의NUL종료ASCII이름을읽는다. 기존geometry분석과달리geometry/animation을평가하지않는다. serialized pointer무시/잘린header·배열거부/잘못된name거부/노드밖문자열무시를검증했다. 처음3RED1PASS→새4테스트+기존geometry5테스트총9PASS. 모델파일을변경하지않는읽기전용분석이다.

기존ship-model-catalog의348distinct path를신규대조:247 SERIALIZED_NODES_READ,101 MISSING,invalid0. 누락은원본표경로와현재추출root사이의미발견이지다른설치/패치에도없다는주장아님. 모든존재파일SHA/node index/offset/name/LODuses를 evidence/ship-model-nodes-v150.json에기록(SHA8843469D72DB57FE3F392169FCEFD061F6B8F6BA2CDCDC90F250E9687224978C). 스크립트SHA72A5FE7D9086FF22BEBBE4F02CD05D29081AA1DC426FA0F850F988CB99742690. 인벤토리 markers는동일이름또는family_ prefix기준분류이며전체nodes원문도보존한다.

추가Ghidra005FE1F0는대소문자구분substring검색(strstr형동작)으로확인:원본004DC940/004DC8C0는단순prefix보다넓게일치한다. JSON의prefix분류를원본matcher완전재현으로승격금지. 아래model18은전체이름에substring BEAM이없는지도별도확인했다. 004F2AF0는renderer+B24별B38/B34/B30/B2C 모델포인터를선택한다;이값들의런타임LOD매핑/로드후이름보정은별도확인필요.

현재OriginalWorldBootstrapCodec는kind0→model12,89→1003,**kind3→18**,93→1014. 현재v148귀환후kind3이므로이분석은다음플레이어사격에직접관련있다. GE/EM018과EH018은8노드(B2,B1,C4,C1,C2,C3와hull이름),EL018은1hull노드;세파일모두BEAM/GUN/MISSILE/ENGINE문자열노드없음. SHA EM018=479B827AAC9BF20AD508A39ACE953DB15AB4AC6C4989EC29101DAB3D3CE706A8,EH018=B9ED3B30B70295513F518DF225EE54808266A29B79CE71D86DB395A124F72BE1,EL018=AD0AC8ED9C73AC853E927061EF1BD2230CCC3AB637E7DD1D2906A6B4652240EB. 반면FP/FM014/FH014는12노드로BEAM_01/GUN_01/MISSILE_01/ENGINE_01있고lowFL014는2노드/ENGINE만있다. GE/EM012/EH012도발사점있고EL012에는없다. **lowLOD에발사점이없는것만으로원본무기가고장났다는뜻이아님**;실제로어떤모델포인터를검색하는지확인해야한다. model18은현재임시함종배정과원본발사점연결의불일치후보로기록,멋대로다른선체/브륀힐트로바꾸지않았다.

플레이어사격소스검사:NaturalAuthoritySession.ProcessTacticalTargetCommand의0405/0406은소유·적대·생존·장착검사뒤즉시ApplyAuthoredDamage하며사거리/사각/출력/쿨다운검사가없다. NPC쪽과동등한전체게임규칙이라고할수없다. 이번은그검증미구현,게임/DB/서버변경0(로컬분석스크립트와영수증만추가),v149로컬Arms로그추가는그대로미배포. 다음:생존gen4부두세션에서새무작정격침실험보다model18 loader runtime목록/발사점변환/원본함종join을확인하고플레이어사격권위누락을구현한다. 이미요청한라벨/E105클라패치승인미응답유지. 전체goalACTIVE.

## E138 — 전력배분0→플레이어 공격 피해 차단 및 v38 빌드 (v151)

**LOCAL_POWER_COMMAND_EFFECT_VERIFIED / v38_NOT_DEPLOYED.** 직전발견중무기출력0에서도피해가나가는경로를수정했다. NaturalAuthoritySession 실제명칭은 ProcessTacticalRelayAsync(E137의ProcessTacticalTargetCommand표기는오류). 장착검사뒤현재 _tacticalPlayerCorps 또는초기corps의선택무기출력을확인하고0이면TACTICAL_WEAPON_POWER_INSUFFICIENT/0500일본어설명으로거부한다. DBcommit/메모리피해/NPC보호해제/0426발행이전에거부. family0=PowerBeam,나머지는기존NPC임시정책처럼PowerGun;포/미사일장비별완전원본정책은아님. 현재템플릿은빔만실제장착되어그분기를실세션검증했다.

OriginalPlayerCombatTests 4cases(0405/0406 × NPC/타플레이어표적): 실제암호화040C beam0승인→사격거부/0426없음/공유damage0→040C beam20승인(gun0유지)→사격1회/0426/damage25. 처음4RED(actual tactical-command-accepted)→GREEN. 기존사거리·사각·쿨다운없는즉시피해정책은여전히남아있으며이번guard로완전전투권위라고주장하지않는다. 전력은현재세션상태이며중복세션/재접속영속공유도아직없다.

격리실제PostgreSQL포함전체 **634PASS/0FAIL/0SKIP**. E:/logh7-build/test-results/fire-power-v151/fire-power-v151-full.trx SHA9E2A013FA673B8E013A180D785F3DB3500526C888461C12DFAADD46B7D8872FD. v149 NPC Arms로그추가도이번전체검사에포함. Release win-x64 self-contained E:/logh7-build/server-v38-fire-power-v151 publish exit0,DLL AD026B1AB5D0ACBE9452017E327C2841ED0699D39870CE3D47667BC636CBAEAA. host시험PG56644/55805/data unit-stance-pgdata-v135 신원확인후정상종료,파일삭제0. guestv37/client10160은이번턴접근0/재시작0/배포0,최신native영수증v148유지.

다음: v38배포전현재guest신원/손실fresh확인;model18발사점/로드후노드변환을우선읽기검증하여새플레이어사격에서어디를봐야하는지정한다. 미승인원본EXE/모델파일을고치거나임의로선체를바꿔문제를감추지않는다. 전력명령native효과및플레이어사격실측은미완료;사거리/사각/쿨다운·지속공격은진짜게임구현의다음항목. 전체goalACTIVE.

## E139 — 실제 모델 검색 대상 정정: transform node가 아닌 mesh, 부두 신원 재검증 (v152)

**LOADER_MESH_NAME_JOIN_CONFIRMED_STATIC / LIVE_MODEL_CACHE_EMPTY_AT_DOCK.** guest-model-cache-v152.ps1는v148실행신원·EXE/DLL·PGdata검사뒤RPM만실행했다.21:30:03.2986173Z client10160/server5216/PG1812생존. 원본자기unit2/kind3/base2/mode4/spot2001/owner0/cruising10확인. tacticalFlag0/sceneMode2/ships·renderers·modelCache빈배열. 따라서현재부두에전술모델목록이없으며읽기실패/모델파일없음과다르다. 메모리쓰기0/게임입력0/로그인0/재시작0. receipt model-cache-v152.json SHA0065EBAF57D5B9CE6CD7D55E3B9A7E524B4CBD2CC280237598044DF463E60485. 같은부두조회반복금지;전술상태에서만active모델읽기가의미있다.

중요정정:E137의노드이름목록은파일사실이지만원본발사점검색대상자체는아니다. 004DD6A0 loadMdxOrMdsModelFile에서 .MDX/.mdx→005DE500→005E3C00(type1)→005E39E0→005F6230. 004F2920는모델별005DE8E0→005DE550(case1)→**005DF940**으로런타임객체를만든다. 이함수는rawheader+4의transform node수를 runtime+104/포인터+100에놓고, **rawheader+2C의mesh수를 runtime+10C/포인터+108**에놓는다.005DE920/005E0900은meshentry+4에raw384byte mesh레코드포인터를저장;이raw레코드앞이name. mesh의+80 transform-nodeindex로runtimeentry+0에변환노드를연결한다.따라서004DC940/004DC8C0의검색은meshname,행렬은해당변환노드+58이다.005FE1F0의대소문자구분substring규칙은유지된다.

mdx_nodes_v150.py를기존검증된mdx_geometry_v114.extract의meshNames와연결하고실제substring분류로보완했다. 이전v150JSON은보존;새 **ship-model-mesh-launch-points-v152.json**에node와mesh둘다보존. 새247성공/101missing/invalid0,기존parser9테스트PASS. 출력SHA4C6522E690E96C4E08D892E61F43C9C6C5AA328CF18954B07930F564639307D5. 현재스크립트는E137옛SHA와다르므로구영수증재생성에덮어쓰지말것.

model18의실제mesh도EM018/EH018각8개,B1/B2/C1..C4및hull명뿐,EL0181개hull명;모두BEAM/GUN/MISSILE0. model1014의FM014/FH014 mesh12개,BEAM1/GUN1/MISSILE1,FL014mesh2개/무기0. 따라서model18위험후보는올바른검색테이블로도재현됐다. 단live active model선택/사격fallback위치실측은여전히미검증.004F2AF0 selector0/1/2/3→B38/B34/B30/B2C,004F2920/004F3F70과결합하면0low/1medium/2high/3fallback경로. 어떤LOD가발사시선택되는지다음실플레이에서읽어야한다.

v38패키지는미배포,기존v37/gen4부두유지. 이번분석은임의모델교체나클라패치허가가아니다. 다음구현은플레이어사격사거리·사각·쿨다운과0405지속공격정책을진행하고,다음정상전술진입에model-cache관찰을붙여model18의실제발사결과를한번에확인한다. 전체goalACTIVE.

## E140 — 플레이어 사거리 경계 연결, 회귀 fixture 조정 진행중 (v153)

**RANGE_VERTICAL_FOCUSED_PASS / REGRESSION_NOT_GREEN / DO_NOT_DEPLOY_CURRENT_SOURCE.** 새Ghidra004F1180/004EFD70/004EF740과기존E071/E074를대조했다. E074실측distance3.337486/max5,원본엄격distance<directionalEnd규칙. 이번생산변경은NaturalAuthoritySession.ProcessTacticalRelayAsync의0405/0406 공통피해직전 rangeguard:현재자기pose와현재공유NPC pose 또는targetParticipant pose, (_staticArms??authored).EncodeResponse의실제선택arms행에서최후positive bin+1을최대사거리로계산. wireXY=renderXZ, double유한평면거리검사, distance>=range 또는nonfinite거부0500/TACTICAL_TARGET_OUT_OF_RANGE. 권한좌표만사용하며요청의가짜거리없음. 현재zero-distance는range만으로배제하지않으며사각·충전·명중·0405지속공격은별도미완료.

OriginalPlayerCombatTests.Shooting_outside_the_equipped_weapon_range_does_not_damage_the_npc:초기-10/+10거리20에서기존코드actualaccepted RED→guardGREEN. 실제0400이동요청으로x5까지이동후경계5도거부,다시x6이동후거리4에서0426/피해25확인. fixture SQLteleport/손실직접주입아님;인메모리권위세션의현재즉시이동정책을사용하므로native이동시간일치주장아님. E:/logh7-build/test-results/range-v153/range-v153-focused.trx 1PASS/0FAIL,원RED range-v153-red.trx보존.

전체suite(이번에는PGenv미설정)에서원거리NPC즉시명중을전제로한기존tests가실패했고PG테스트는SKIP이므로전체통과주장금지. session6603은running상태를동일handle로확인. OriginalSharedBattleTests.UnknownActorWithoutIdentityFailsObserverInsteadOfSendingAnUnusableHit 481부근에서공격거부뒤알림채널실패를무기한기다리는소스를확인하고해당test실행만Ctrl+C종료(exit1). 단순시간초과로프로세스사망을추정한것아님. 전체TRX range-v153-scope.trx는종료전미생성으로완결영수증없음.

**다음 즉시 작업:** 테스트의본래검증조건을유지한채사격전적법한위치fixture/0400접근을명시한다. 실패확인대상 OriginalSharedBattleTests의Attack/AttackActor/Session과특수actorpose123,4,567,OriginalPlayerCombatTests NPC표적 power復帰/종결,OriginalNpcSceneImportTests.Accepted_player_fire_removes_npc_protection...,OriginalShipIncarnationSessionTests.Old_scene_cannot_attack_as_replacement_before_reimport. 그외실패명도다음bounded검사로회수. 무조건globaltest사거리확대/생산rangeguard삭제/기존assert약화금지. 알림기다림은원래사격이승인됐는지먼저assert하고시간상한을주어회귀가hang되지않도록한다. 이후PG포함전체재실행→남은geometry/사각/cooldown검증. 현재v38빌드는E138당시코드라신규rangeguard없음;현재생산소스는미빌드패키지/미배포. guestv37/gen4은이번접근0. 전체goalACTIVE.

## E141 — 사거리 회귀 정상화·전체635PASS·v39 빌드 (v154)

**RANGE_REGRESSION_GREEN / v39_BUILT_NOT_DEPLOYED.** E140 생산rangeguard는유지했다. OriginalSharedBattleTests.CloseCombatCatalog는해당관찰자테스트용기존맵복제에서enemySpawn.x만-7로설정한다(player-10과거리3);원래생산catalog/무기수치변경0. 특수actor pose는X123→-8로만바꾸고Y4/Z567/yaw0.75/morale73/confusion2/identity/배치순서assert유지(적-7,0과거리sqrt17). 첫bootstrap observer도같은fixture를사용해중간bootstrap에서NPC가원거리로새등록되지않도록했다.

관찰자missing-identity 테스트의채널실패대기는5초상한+TestContext cancellation적용. 처음xUnit1051컴파일오류는WaitAsync cancellation누락때문이라추가후해소. 다음전체scope검사는정상종료하여12FAIL/597PASS/26PGSKIP을회수했다(range-v154-scope.trx). 같은근거리fixture를실패한시계6cases,전력복구NPC표적2cases,혼합플레이어/NPC종결,수입전공격보호해제,재생성후새공격검사에만명시적으로주입했다. 기본Session/global맵전체를바꾸지않아E140의원거리거부·실제0400접근테스트는원래-10/+10배치를유지한다. 다음scope2는609PASS/0FAIL/26SKIP.

별도hostPG54500/55805/data unit-stance-pgdata-v135를실행해실제DB포함 **635PASS/0FAIL/0SKIP**. E:/logh7-build/test-results/range-v154/range-v154-full.trx SHA3D88FA6F6D6B0166F438CC964817369EECC4598EED25A7132101FAD2E931D1FC. Release win-x64 self-contained E:/logh7-build/server-v39-fire-range-v154 publish exit0,DLL ED175BAA6CCFA57B4B7CD339C07944195C1595DE13C9EC5120EA4D5BD421A647. v149무기로그/v151출력gate/v153rangegate포함. hostPG정확소유경로/data확인후정상종료,삭제0. guestv37/client10160/PG1812는이번턴접근0이며최신실측v152(gen4부두)유지.

E140의REGRESSION_NOT_GREEN은이번전체결과로해소됐으나native사거리/출력표시검증은아직없다. 사각·재사격간격·0405지속공격/추적/중지·다중접속전력상태공유미구현. 다음은v39배포와명시적플레이어공격시나리오또는이권위기능추가. 수동격침/치유/DB이동없이실제명령경로검증;현재model18발사점결손후보와클라이언트라벨/E105승인대기는유지. 전체goalACTIVE.

## E142 — 사각 guard 회귀 진행중·자동로그인 상태 확인 (v155)

**ARC_FOCUSED_IMPLEMENTED / FULL_REGRESSION_NOT_GREEN / DO_NOT_DEPLOY_CURRENT_SOURCE.** NaturalAuthoritySession의 기존 사거리 guard 뒤 실제 선택 무기의 angle mask와 OriginalTacticalNpcController.Sector를 연결했다. 평면 atan2(dx,dy), 현재 함선 heading을 사용하며 거리0/비유한 heading/허용되지 않은 sector는 TACTICAL_TARGET_OUTSIDE_WEAPON_ARC로 피해 commit 전 거부한다. 동일 NPC의 6sector 정책이며 원본 x87 경계 완전 재현 주장은 아니다.

OriginalPlayerCombatTests.Turning_into_an_equipped_arc_enables_an_in_range_target 0405/0406 두 테스트: 근거리 표적을 사각 밖에서 거부하고 실제0400 제자리 선회 뒤 명중하도록 검증. 수정 전 두 RED(arc-v155-red.trx). 수정 후 전체 비PG 실행은 정상 종료했으나 **599PASS/12FAIL/26SKIP**, E:/logh7-build/test-results/arc-v155/arc-v155-scope.trx. 실패는 기존 PvP 시험 함선이 같은 좌표로 시작하는 fixture와 관련된 것으로 추정되며 아직 수정/재검증하지 않았다. 새 두 사각 테스트는 해당 실패 목록에 없다. 생산 guard 제거/각도 mask 확대/원본 맵 변경으로 테스트를 통과시키지 말 것. 다음은 OriginalPlayerCombatTests.Session helper 및 이를 쓰는 DamageCommitBoundary/InjuryReturn/ReturnPlanet 시험에서 명시적인 서로 다른 근거리 위치를 배치하고 PG 포함 전체 회귀를 실행한다. 마지막 전체 GREEN 빌드는 v39이며 신규 arc 소스는 미배포.

사용자의 자동로그인 지적에 대해 v148 성공 경로를 확인하고 새 read-only window-login-check-v155-v148.png를 관찰했다. v148 pinned client PID/start/HWND 검사를 통과한 새 캡처이며 전략 HUD의 宇宙港/旗艦桟橋가 현재 표시된다. 지금 이미 로그인된 상태이므로 자격증명을 다시 입력하거나 재로그인하지 않았다. VM 관찰 실행 exit0, 입력0/DB쓰기0/재시작0. 자동로그인 가능 여부를 사용자 수동 로그인 필요로 잘못 보고하지 말 것. 새 캡처는 work/20260904-warp-state-reverse/evidence/window-login-check-v155-v148.png. 전체 goal ACTIVE.

## E143 — 사각 회귀637PASS·v40 빌드, 실제 PvP 입장 좌표 중첩 발견 (v156)

**ARC_REGRESSION_GREEN / v40_BUILT_NOT_DEPLOYED.** 직전 턴은 pinned native 화면으로 로그인 유지/부두를 확인한 evidence progress였으며 재로그인은 필요하지 않았다. 이번에는 E142의 실패12건이 공유 OriginalPlayerCombatTests.Session helper의 동일 playerSpawn을 쓰는 경로임을 추적했다. 이 전투 시험 helper에서만 power3 함선의 X를 기준+3으로 배치하고 나머지 pose/원본 catalog 값은 보존했다. player2의 custom heading을 보존하므로 사각 밖→실제0400선회→명중 검사는 그대로다. 생산 angle mask/거리/DB/맵은 수정하지 않았다. 비PG scope는611PASS/0FAIL/26SKIP.

격리 PostgreSQL을 포함하자 OriginalPlayerUnitIdentityPostgresTests.Two_database_backed_players_remain_distinct_targets_in_one_battle 하나가 실패했다(636PASS/1FAIL/0SKIP, arc-v156-full.trx). 이 경로는 시험 helper가 아니라 실제0205/0F02 진입을 사용하며 두 플레이어 모두(-10,0)에 입장한다. 따라서 **운영 기본 PvP 입장 좌표 중첩은 실제 미완료 항목**이며 fixture 수정으로 해결됐다고 주장하지 않는다. 해당 시험에는 처음 중첩 사격이 TACTICAL_TARGET_OUTSIDE_WEAPON_ARC로 거부되고 피해0임을 명시했고, 실제0400 명령으로 자기 함선만(-13,0)으로 이동 후0406을 보내 다른 플레이어에게25 피해/올바른 target ID/자기 피해0을 검증했다. DB teleport나 직접 손실 주입이 아니다. 현재 이동 즉시 목적지 정책은 여전히 원본 시간 보간 완전 재현이 아니다.

최종 실제DB 포함 **637PASS/0FAIL/0SKIP**, E:/logh7-build/test-results/arc-v156/arc-v156-final.trx SHA96157DCBD084DD5AC5597A48725AD0AC413F55441309AEF066667A57AE41AAE1. Release win-x64 self-contained E:/logh7-build/server-v40-fire-arc-v156 publish exit0, DLL SHA0F6122E7E37DA58855C333CD564E818B1790D4FE7977A31AFE8A972D09ADAFB0. 포함: v149 실제 NPC Arms 로그, v151 전력 gate, v153 사거리 gate, v155 사각 gate. hostPG16500/data unit-stance-pgdata-v135/port55805의 정확한 executable/data 소유 확인 후 pg_ctl fast 정상 종료, 삭제0. guest 서버/클라/PG는 이번 접근0·배포0·재시작0. 현재 마지막 직접 화면은 v155의 로그인된 부두이며 live 서버 버전은 v37.

**다음 실제 플레이:** v40 데이터 보존 side-by-side 배포를 위해 fresh guest PID/start/hash/DB 상태를 확인하고, 새로운 버전의 Stage/Stop/Start/Client/자동로그인 영수증을 사용한다. v148 consumed 작업 재실행 금지. 정상 전술 진입에서 플레이어의 전력 배분/거리/방향 변화에 따른 공격 거부·명중 및 NPC arms 로그와 실제 효과를 대조해야 한다. model18 active LOD/mesh 발사점 관찰도 같은 시나리오에 결합한다. 기본 입장 겹침은 PvP 배치 정책(출발방향/진영/유닛별 겹침방지) 구현 대상으로 유지. 지속공격/추적/중지/재사격 주기, 모든 전략 명령/제안/리소스/2클라 전투 검증은 미완료. 라벨/E105 원본 EXE 패치 미승인 유지. 전체 goal ACTIVE.

## E144 — v40 실제 배포·자동로그인·기존 부두 세션 복원 (v157b)

**V40_DEPLOYED_STATE_PRESERVED / NATIVE_AUTOLOGIN_AND_DOCK_REENTRY_PASS / NEW_FIRE_GUARDS_NATIVE_UNSEEN.** 새 읽기 events-pre-v157-v148.json은 이전 server5216/client10160/PG1812 생존과 unit|2|2|102|2|4|0|0|4|10을 확인했다. v40 zip SHA7AE0CB230B4AAABB815310B9ABEB69CCA118A4CE016327E05CEB23704155DBA1. 최초 copy exec handle61574가 running인데 Stage를 먼저 실행해 ZIP 공유잠금 오류 발생(21:58:07Z). 서버/클라 중단 전 실패, v157 receipt 보존. 같은 copy handle을 기다려 terminal exit0 확인 후 새 v157b script/출력/별도 directory로 Stage를 수행했다. 실패 작업 재실행·영수증 덮어쓰기·패키지 재전송0. 이후 모든 의존 작업은 이전 명령 exit0 확인 후 수행.

guest runroot 동일20260906T181000Z-departure-v124. v157b Stage/Stop/Start의 account/character/unit/event/migration stateHash **dbf96152892af98d80b50b74a4626c89a2d0ce46b40765611fc57b31fe3aa48b** 동일. PG1812/start2026-09-06T18:11:30.2695908Z/기존 postgres-data/55432 유지, 중단0·삭제0·SQL 상태조작0. 마이그레이션25개 구파일 hash 동일. 원본 EXE SHA AEF3827602CD13A395618BDEF0F48F44BCB8ED60F4FA9C2017F2D2E128660F2F 미변경.

**현재 live server8712**,start2026-09-06T21:59:01.0991040Z,path runroot/server-v40-v157b/Logh7.Server.exe. DLL0F6122E7E37DA58855C333CD564E818B1790D4FE7977A31AFE8A972D09ADAFB0,EXE C525060083F0F0CF7860D482B3C16E3E392B29BF9CFB5678DAB7551BFE3F99A4. wire server-wire-v157b.jsonl,stdout/stderr server-v157b.stdout/.stderr. notice LOGH7 v40 fire power range arc. **현재 client1192**,start2026-09-06T21:59:27.7541301Z,HWND0x0000000004300448,동일 client/exe/G7MTClient.exe.

fresh interactive/auth preflight→초기흰창 File메뉴 클릭으로 실제 로그인폼 redraw→닫기→FocusId 새화면 기반1회씩→credential-v157b 22:01:31.9388461Z 단1회 제출/재시도0/비밀값 기록0. window-lobby-v157b.png에 v40 notice 확인. GameStart→기존 캐릭터1회. events-relogin-v157b.json READ_ONLY_RUNTIME_VERIFIED/unit|2|2|102|2|4|0|0|4|10 보존. 최초 window-relogin은 재접속 선체 연출 중 캡처; 이후 window-dock-ready-v157b.png에서 정상 전략 HUD 宇宙港/旗艦桟橋 확인. 이 모델 연출은 전술 함종 식별 증거가 아니다. 아직 새 워프/공격 입력0.

사용 script suffix는 모두 **v157b**: guest-server-swap, guest-interactive-session, guest-auth-preflight, guest-submit-credential, guest-window-observe, guest-click, guest-return-events. host bridge host-vmrun-v79.ps1 유지. v157b Stage/Stop/Start/Client/credential 모두 CONSUMED 재실행금지. window/events read-only는 새 Tag만 사용. click에 ExpectedPid1192/HWND04300448/PrepFileName auth-preflight-v157b.json 사용, 이전 v148/v152 identity script 직접 실행금지. functions.store click157 템플릿은 모든 placeholder replaceAll 후 새 screenshot에 근거해 한 번만 입력. 다음 정상102→101 워프 전 capture/model-cache 관찰 계획을 준비한 뒤 플레이어 명령과 NPC arms/효과를 함께 검증한다. 수동 격침/치유/DB 순간이동 금지. 기존 v40까지 로컬637PASS는 native 사격 guard 검증으로 대체되지 않는다. 전체 goal ACTIVE.
## E145 — v40 전술 연속 동작·실제 NPC arms1 확인, 관찰 타이밍 실패 (v158)

**NATIVE_V40_WARP_NPC_DAMAGE_RETURN_PASS / NPC_SELECTED_ARMS_1_LOGGED / PLAYER_SHOT_UNSEEN.** 이전 턴은 v40 배포·자동로그인 실제 progress. 새 2026-09-08 pre-v158 읽기는 v157b runtime 동일 신원을 확인했다. server8712/client1192/PG1812 유지. 부두→역할카드→운용→워프→왼쪽grid→첫 확인(내용 없는 확인창)→두번째 실제 워프 비용 안내 확인으로 정상 새102→101 워프. 첫 클릭 준비 때 functions.store click157가 사라져 JS TypeError, 도구 실행/입력0; 현재 pinned 신원으로 click158를 새 구성한 뒤 관찰 기반 각 입력1회. 기존 입력 재생0.

event48 이동: gen4/sourceMode4→6/base2→0/cruise10→9. **RPM model-cache-tactical-v158.json** 01:50:39.6892052Z unit2/kind3/base0/mode6/cruise9 확인. 하지만 tacticalFlag0/sceneMode0/ships/renderers/modelCache empty: 전환 중 너무 이른 조회였으며 모델 없음의 증거 아님. window-arrival-v158-v157b.png는 흰 전환 화면. 뒤의 window-battle-v158-v157b.png는 좌측레이더/우측에너지분배/하단전술명령 HUD이며 입장 선체 연출이 아직 겹쳐 있다.

**NPC 실제 무기 선택 로그:** events-after-v158-v157b.json의 npc-ai decision은 actor2130706433→target2, x-6/y0/heading-0.52359915, fire **01:50:47.6059117Z / 50.8293185Z / 53.831758Z / 56.8360135Z**, 매번 **arms1**. 이동 event arms는null. 기존 E135에서 코드로 추정만 하던 무기번호가 이번 v149 로그 연결 배포 후 실제로 관찰됐다. 원본004C7790 분류상1은 빔군이지만 이번 캡처가 실제 빔궤적을 잡았다는 뜻은 아니다.

damage events49/50/51/52=25/0→50/0→75/0→100/100. event53 returnId42bb4e83-6291-ac9d-3c56-173b4622d625/grid101→102/base0→2/mode6→4. event54 recoveredgen4→5/kind3/cruise9→10. 최종 **unit|2|2|102|2|4|0|0|5|10**. DB쓰기/재시작/재로그인/수동격침0. 전술 캡처 후 BeamPowerMinimum 의도 클릭(x695,y39)은 실제 후캡처에서 이미 부두였다. wire의040C/0405/0406 요청0이므로 출력변경/사격성공 주장은 금지. 현재 부두에서 같은 클릭이나 격침 반복 금지.

**다음 행동을 바꾸는 원인:** 도구별 캡처·전송 시간 사이에 전투가 끝나 사용자 공격검증을 놓쳤다. 현재 AI 접근 후 약9초만에4회 피해; HUD 입장연출과 실제 게임입력 가능 시점을 분리하지 않았다. 모델 관찰은 고정 지연이 아닌 실제 active renderer/mesh 준비 상태로 해야 한다. 신규 scripts/guest-model-cache-ready-v159.ps1를 추가: ReadProcessMemory only, 최대45초 동안 실제renderer와선택LOD의meshcount>0을 기다린 뒤 기존 modelCache 분석 수행. UI입력/메모리쓰기0, 준비실패는 ACTIVE_MODEL_NOT_READY_WITHIN_45_SECONDS로 실패 처리. host PowerShell AST PARSE_OK만 확인, **guest 전송·실행 아직0**. 다음 실행 전 필요한 skill/현재신원을 확인하고 새로운 정상 플레이에만 붙일 것. 이 스크립트가 플레이어 조작창 부족 자체를 해결하지는 않는다. 클라이언트 준비통지/시작연출/AI 초기취득 타이밍을 역추적하여 실제 조작 가능한 전투를 만들어야 한다. 소스 전투수치/패키지변경0, v40 계속실행. 전체 goal ACTIVE.
## E146 — NPC 준비조건 정상 동작·원본 전술 입력 전단 gate 추적 (v159)

**IMPORT_GATE_CONFIRMED / PRESENTATION_INPUT_READY_NOT_PROVEN.** v158 영수증을 시간 범위로 재분석했다. 최초 광범위 출력은 하루치0300 폴링까지 포함해 잘렸으므로 뒤의01:50:35Z–48Z 좁힌 결과로 판정. 0B01=01:50:37.1603815Z,0F02=38.6347814Z,최초0348=43.685529Z,034A=43.6869428Z,NPC첫move=43.8401355Z,첫fire=47.6059117Z. 따라서 NPC가0F02부터 공격하거나 scene gate를 무시했다는 가설은 반증됐다. 자체함선0348 뒤 약0.15초에 이동,약3.92초에첫사격. OriginalNpcSceneImportTests 새실행 **12PASS/0FAIL/0SKIP**(E:/logh7-build/test-results/scene-ready-v159/scene-ready-v159.trx); 이는 원본 화면 조작성 테스트가 아니다. 생산 동작/전투수치/DB/guest입력/재시작 변경0.

NaturalAuthoritySession.MarkOwnNpcSceneImported는 destination bootstrap을 받은현재함선의0348 또는acceptedAttack에만NpcTargetReady를발행한다. QueryParticipants(npcTargetsOnly:true)는이를필터한다. Controller는목표취득시lastShot=tick,72tick후사격하며매tick이동을6tick상한으로제한한다. 서버가모델로드완료/입장연출끝메시지를받는코드는아니다.

새Ghidra decompile: **004B68F0**은sceneMode+126711==0 및tacticalFlag+126718!=0일때0050D230→0050CF10→004B6E00(0348폴링)순서로호출한다. **0050D230** 시작은 *( *(param+0xC)+0x3A0 )==0이면 즉시return,world/자기entity체크,그뒤004B7890이false면return. 즉 전술함수가earlyreturn해도 바깥004B6E00은호출가능하여0348만으로입력준비증명불가. 이UI활성플래그의실제runtime값/부모포인터는아직미회수.

**004B7890→004B8950**은fade 전용함수가아니라500개수신큐를시간순으로검사/실행하는함수다. world+3552B4 duecount,큐+3552B8/stride20. global007C25F4==0이면1반환,그외004AD4B0호출뒤world+357EC0==0일때만true. 이분석은gate존재의정적증거이며v158에서실제로막혔다는동적증거는없다. 시간 맞추려고 임의NPC 지연/원본패치하지 않았다.

evidence/decompile-004b68f0-v159.c,0050d230-v159.c,004b7890-v159.c,004b8950-v159.c 보존. 0050d230 전체export는보존했지만이번분석범위는위의전단gate와0348상위호출관계;전체커맨드분석완료아님. guest-model-cache-ready-v159.ps1에queueGateGlobal/queueGateState/dueQueueCount 읽기필드추가,AST PARSE_OK. 아직guest전송/실행0;modelReady가inputReady라는주장금지. 다음은이45초read-only준비관찰을워프최종확인전에실행하고 실제active모델및gate상태를같이회수한다. 현재v40/gen5부두는v158마지막live증거이며이번새생존조회0. 전체goalACTIVE.

## E147 — 실제 model18 발사점 결손·queue gate 열림 실측 (v160)

**ACTIVE_MODEL18_MESH_NAMES_VERIFIED / PLAYER_POWER_OR_SHOT_NOT_VERIFIED.** 직전정적분석을실제RPM에연결했다. 시작부두카드클릭의200ms후캡처는변화없었으나새관찰에서카드가정상열림. 클릭재시도0,서버시간조회계속됨을확인해freeze/restart로오판하지않았다. v157b server8712/client1192/PG1812 신원검사유지.

새정상워프최종확인전 guest-model-cache-ready-v159.ps1를처음guest전송/실행. hostwait handle17615가running임을확인한채별도정상워프확인. 관찰은01? 정확히 **2026-09-08T02:01:30.9621407Z wait시작→02:01:50.8529753Z modelReady**. handle17615 terminalexit0을동일handle로확인. 영수증 model-cache-ready-v159.json SHA **D3D5794988FE7579306BCEB268F05FAB9F611EA8B04C610452CB33902A4E70DD**, RPMonly/입력0/쓰기0. 스크립트/출력CONSUMED 재실행금지.

실제unit2/kind3/cruise9/base0/mode6,world08AF9020/context08AF902C,tacticalFlag1/sceneMode0,두unit모두normal100/remaining100/destroyArmed0. **queueGateGlobal89148848/nonzero, queueGateState0, dueQueueCount0**: E146정적조건상그순간queuegate는열려있다. 이것은모든입력준비증명아니며*(param+0xC)+0x3A0 UI활성값은여전히미회수. actionContext021AA888은0.

**자기실제renderer slot599/id2/kindSelector18,selectedLOD selector1**, modelptr335656160. 실제검색대상mesh8개: EL018:Layer1,B2,B1,C4,C1,C2,C3,EL018_02. **BEAM/GUN/MISSILE 문자열0**이며이제파일상의후보가아닌로드후실제mesh목록증거다. 004DC940/004DC8C0의발사점이름검색과join됨. 발사체fallback/사용자자기사격의실제효과는여전히UNSEEN. 잘못된명명node필드는영수증원본대로보존하되내용은mesh이다. 이증거만으로새이름을임의부여하거나원본모델수정하지말것.

적entitykind89/id2130706433의renderer slot598은kindSelector53으로관찰됐다. selectedLOD항목은기존관찰코드가selector>3이면skip하므로modelCache에적entry가없다. 이유는그순간selector원값을기록하지않았기에미확정. 과거89→1003 추정/조인과불일치가능성이있으므로**53의의미와원본함종→렌더러모델조인을다음역추적**한다. 자기18과적53을브륀힐트fallback이라고통칭금지.

워프event55/gen5/02:01:43.7619122Z source102→101/cruise10→9. 최초0348=48.9648527Z,modelReady=50.8529753Z,첫fire52.0806791Z(arms1),55.0838025Z/58.079942Z/02:02:01.3282412Z손실100. 따라서모델준비관찰약1.23초뒤첫명중,약10.48초뒤격침. window-hud-v160-v157b.png에아직입장선체연출/어두운전술HUD. BeamPowerMinimum시도후캡처는이미부두이며040C/0405/0406요청없음. 이세션에서명령전송실측실패를숨기지않는다. event56..59손실,60returnIdf71a84d5-f785-95f9-f8fa-c98c31f101ac,61recoverygen5→6. 현재 **unit|2|2|102|2|4|0|0|6|10**. DB수동쓰기/재시작/재로그인0,source/package변경0,v40계속실행.

**다음은같은재출격반복금지.** 이제모델18의발사점결손은충분히입증됐으므로enemy53조인/원본입장연출의UI활성+3A0/실제플레이어조작진입경로를정적으로좁힌다. 현재임시AI가모델준비직후공격한다는타이밍은확인됐으나원본서버기준이나안전한조작창정책은미결. 필요하면승인된임시AI의전투페이스를명시적인NEW_DESIGN으로설계하되도구지연을감추는테스트전용무적/운영DB조작금지. 전체goalACTIVE.

## E148 — 적89→1003→내부53→FM003 정상 join 확인·LOD5 관찰 누락 수정 (v161)

**MODEL_MAPPING_DISCREPANCY_RESOLVED_STATIC / NOT_A_SERVER_MAPPING_BUG.** E147 actualrenderer53은filecode1003과다른번호체계였다. 새Ghidra **004F19E0**은entity+8BC(kind)→004F3D80→renderer+B28→004F2920(param2=0) shiploader. **004F3D80**은world+kind*2A8+2C1C84(기본staticrecord+20C)의ushort filecode를읽고 /1000그룹0=그대로,1=나머지+50,2=나머지+100,3=나머지+110으로변환한다. 1003→3+50=53,18→18. 적kind89/renderer53의E147관찰은이정적join과일치한다. 원본파일코드에대한동적직접읽기는이번추가하지않았으므로이를새RPM1003실측이라부르지않는다.

004F3F70는내부index로LOD별포인터테이블조회: medium00774B68,high00774D48,low00774F28. medium53주소 **00774C3C**의raw **38877700→00778738**,그문자열 **/../data/model/Ship/FP/FM003.mdx**를Ghidra memoryread로확인했다. 따라서적이Empire모델53을잘못받았다는가설은반증. 파일code와테이블index,unitkind를혼동하지말것. production모델/맵/DB변경0.

**LOD5는지원되는숨김상태.** 004F2920초기+B24=5,LOD0/1/2/3선택시도뒤5fallback. 004F2B40(param2=5)는현재selectedmodel이있으면005DF450로루트에서떼고+B24=5,이후004F2300. E147의관찰코드는selector>3을그냥skip했으므로적renderer있음/modelCache없음은선택값원본을잃은관찰누락이다. 실제적selector가5였다는증거는없으며새정적분석만으로과거값을만들지않는다.

새 scripts/guest-model-cache-ready-v161.ps1은소비된v159를덮지않고clone. selector>3도entry를남김,5=HIDDEN_NO_SELECTED_LOD/그외UNMAPPED_SELECTOR,모든entry에modelTableIndex추가. SELECTED_LOD와분리. AST PARSE_OK,guest전송/실행0. 45초읽기대기의종료조건은여전히실제mesh있는모델이며입력준비판정은아님. decompile-004f19e0/004f3d80/004f3f70/004f2b40-v161.c 보존.

자기model18 실제발사점문자열결손은E147로유지된다. 다음은그효과생성fallback/실제함종의원본modelcode가18이맞는지데이터원천검증및입장UI활성+3A0의경로확인. 잘못된적매핑을고친다며모델을교체하지말것. 같은짧은재출격반복0/클라이언트패치0/guest접근0. 현재v40/gen6부두는E147마지막실측이며이번생존재확인0. 전체goalACTIVE.

## E149 — 발사점 이름 없음≠효과 생성 중단: 원본 fallback 확인 (v162)

**STATIC_LAUNCH_ORIGIN_FALLBACK_CONFIRMED / PLAYER_SHOT_STILL_UNSEEN.** E147 model18 실제mesh에BEAM/GUN/MISSILE0은사실이다. 그러나이를빔안나옴의원인이나모델수정필요로간주할근거는없음이이번새Ghidra제어흐름으로확인됐다. 기존발사점결손표현은이름기준소켓없음만뜻하며발사불능주장으로승격금지.

004E4450은effect+E4=4(visualtype10은1),+E8=0뒤004E6010호출. 004E6010은actor renderer+108이없으면return0하지만있으면종류별BEAM/MISSILE/GUN검색004E62B0후E4개발사효과초기화를계속하고004E64A0호출. **004E62B0**은먼저005DD5A0으로identity64byte행렬,005DD640으로renderer+89C회전반영,005DD6B0으로renderer+88C xyz를stackmatrix+30translation에복사한다. 검색count0이어도E4반복문을돌며004DC8C0반환값을검사하지않고004DC400으로위치/방향을꺼낸다. **004DC8C0**은모델null/이름불일치시output행렬을쓰지않고0반환. 따라서no-marker이면초기actor world transform이남는다. 004DC400은matrix+30 xyz복사와방향추출/정규화를한다. matching mesh일때만별도mesh transform복사. 이는임의추가한fallback이아닌원본코드경로.

새decompile-004e4450/004e6010/004e62b0/004dc8c0/004dc400/005dd5a0/005dd640/005dd6b0-v162.c 보존. launch-point-fallback-v162.json에런타임모델receipt SHA와정적chain/제한명시. 단순listing호출은첫instruction만돌려줘전체listing검증주장없음. model18실제플레이어사격,발사체origin/이후effectlifetime/render성공은아직UNSEEN. NPCarms1/v148발사체궤적을자기함선발사증거로인용하지말것.

**다음우선순위변경:** 모델18이름수정이나적정상모델교체를하지않고실제0405/0406입력전송→0426→effectrecord를우선확인. 반복격침을더하지않도록입장연출UI활성및명령선택방식을정적으로확인하고,필요한임시전투페이스는NEW_DESIGN으로원본사실과분리. 이번source/game/model/DB변경0,guest접근0,기존v40/gen6부두는E147마지막실측. 원본EXE패치승인경계유지. 전체goalACTIVE.

## E150 — 전술 UI 활성 포인터 확정·부두 RPM 검증·공격 메뉴 선택조건 (v163)

**UI_GATE_ADDRESS_RESOLVED / LIVE_DOCK_READ_VERIFIED / TACTICAL_INPUT_GATE_UNSEEN.** Ghidra listing004B6DBC는MOV ECX,[02215E2C],004B6DC2 CALL0050D230. 따라서0050D230 param은이전추정actionContext021AA888(당시0)아닌**[02215E2C] UIcontroller**. 그+0C의UIroot+3A0이전단활성byte. 다음명령상태는controller+4,이전state+8. 바깥0348호출은같은UIgate를조건으로하지않는다는E146결론유지.

새읽기script guest-ui-state-v163.ps1을currentv157b신원/해시/PGdata확인후실행(exit0). **02:10:49.2699696Z** controller88942640/root152199200/uiActive1/commandState1/previous1/selectedCount0(02216660)/commandableSelectedCount0(02216664)/queueGateState0. sceneMode2/tacticalFlag0/unit2/base2/mode4/cruising10으로부두다. server8712/client1192/PG1812생존검사통과,입력0/메모리쓰기0/재시작0. 이부두값을과거전술입장uiActive값으로대체하지말것. script/output CONSUMED. modelCache-readonly status명을재사용했으나추가UI필드가목적이며전술모델증거는아님.

0050D230 case0은선택수02216660/명령가능수02216664와배열초기화후state1로진행. 상태1은map선택을02214D2C 배열에모으고004EC6B0통과유닛을02215128/02216664에따로모은다. 메뉴button IDs2..5는category(旗艦/艦艇/司令官/要塞)교체이며**그버튼만누르면자기함선선택되는것으로추정하지말것**. attack계열button9는선택된commandable units를돌며004C7820의장비가능조건을통과한무기별subbutton21/22/23을켜는경로. 따라서실제발사검증시자기함선map선택→선택/명령가능수>0→해당공격메뉴/무기→target선택을연결해야한다. 구체화면좌표/전술동적검증은미실행. 수신큐나uiActive만으로공격버튼활성증명불가.

기존v159의decompile0050D230에서이번엔초기선택초기화/ship필터/메뉴button2..5/9분기를확인했다;전체2750줄의모든명령을이번완료했다고주장하지않는다. 생산서버/원본EXE/모델변경0. 반복출격없이실제UIgate주소와선택조건을확보한progress. 다음readiness관찰에controller/root+3A0와선택두count를함께넣고입장연출시uiActive를관찰해야한다. 공격선택이불가능한시간대에기존부두이미지좌표로반복클릭금지. 전체goalACTIVE.

## E151 — 명령 가능 선택 플래그 생성·소속/반경 join 확인 (v164)

**COMMAND_ELIGIBILITY_STATIC_CHAIN_RESOLVED / OWN_ELIGIBILITY_LIVE_UNSEEN.** 0050DF03 MOV ECX,00C515F0→0050DF08 CALL004EC6B0,selectedunitID와param3=0 전달을listing확인. 004EC6B0는picker700개/stride3C에서base+68 ID,base+6C flags를찾아**0x10000**이면true(다른param3는0x20000). 이결과가commandableSelectedCount02216664/배열02215128로들어가며단순ownerID검사가아니다.

생성자004EF0D0는picker+48+count*3C entry에renderer/entity위치와ID+20/entitypointer+28를저장. entryflags+24는entity+9종류에서1/81/101/201로초기화. 004B5C00 관계owner가자기004B5B80 ID와같으면+400;소속power/보조flag가같으면+800;다르면+1000 enemy. 자기/동일소속인경우picker+A724원점과함선위치거리 < **picker+A714 effectiveRadius**이면+10000. **picker+A718 outerRadius < distance**이면+20000. strictboundary가있으므로radius0이라면자기거리0도pass안됨(그러나실제radius0관찰은없음).

004EC600은A718=param3,A710=param4,A70C=nonnegativeparam5업데이트뒤ratio=A70C/param4를최대1로제한하고A714=ratio*param3. 0050D230후단은자기entity8C4/5BA 조건하에이함수를호출하므로현재프레임UI선택과이전/다음프레임반경갱신순서도있다. 원본004C32A0 shipimport는8C4=1,8CC=120. 매칭corps/static처리경로는static+254에서8C8 radius를읽고작으면1.6 fallback. 이경로가실제자기함선에서실행된값은미측정. 서버에없는값이라고추정해추가하지않는다.

004C7820은entity8BC kind의staticrecord에서Beam/Gun/Missile 장비byte+28A/+28F/+293을읽고FF/27이상거부;방향mask와거리8bin을별도로전개. 선택eligible와장비eligible는별도단계. 이번사실은기존E150선택경로를세분화한것이지실제공격버튼활성PASS가아니다.

새 guest-command-eligibility-v164.ps1은소비된script수정없이v161clone,모델ready대기후UIactive/commandstate/selectedcount/commandablecount와picker현재entry ID/flags/10000/20000/entityptr/effectiveRadius/outerRadius를읽는다. AST PARSE_OK,guest전송/실행0. export decompile-004ec6b0/004ef0d0/004c7820-v164.c. 원본서버생산코드/EXE/모델/DB변경0,guest접근0. 다음실측은이통합관찰을사용해자기eligibility를확인한후만선택/무기버튼검증;무작정같은워프/격침반복금지. 전체goalACTIVE.

## E152 — 플레이어 공유 재사격 간격 구현·회귀 진행중 (v165)

**PLAYER_CADENCE_FOCUSED_PASS / FULL_REGRESSION_NOT_GREEN / DO_NOT_DEPLOY_CURRENT_SOURCE.** 실제UI선택관찰은E151준비상태로유지하고,명확한서버전투누락인0405/0406즉시연속피해를구현했다. 신규 OriginalTacticalBattleRegistry.PlayerFire.cs는unit별(longGeneration,uintTick)를공유ConcurrentDictionary에저장,session/reimport/grid전환으로지우지않는다. 같은generation에서uncheckedelapsed<72 또는역행half-range초과를거부. **NEW_DESIGN 72tick(3초)**: 승인된임시NPC FireIntervalTicks와동일한함선단위cadence이며원본무기별충전곡선복원아님. 무기전환으로즉시우회되지않는다. 세대가다르면새함선이며기존stamp미적용. 프로세스재시작영속화는없고모든tick은server-process시계다.

NaturalAuthoritySession.ProcessTacticalRelayAsync의grid lease내appliedAt=_gameClock.Tick,기존출력/거리/사각/표적검사뒤 IsPlayerWeaponRecharging으로0500/TACTICAL_WEAPON_RECHARGING. CommitUnitDamageAsync 성공직후만 RecordPlayerShot,이후ready해제/피해notification. 실패하거나거부된사격은cooldown소비안함. 단일그리드lease가해당행위를직렬화한다;서로다른grid의동일unit stale참여방지및generation변경동시성은추가회귀확인필요. dictionary가원자적이라는이유만으로check/commit전체원자성입증금지.

OriginalPlayerFireCadenceTests 2cases0405/0406 실제암호화세션: 동일unit두세션/동일TestClock/근거리catalog,firstshot25→다른세션동일tick거부→2999ms(71tick)거부→3000ms(72tick)50. 요청시각F1234567/WaitDEADBEEF를써도권위시각사용. **2RED(actualaccepted)→2GREEN**. OriginalPlayerCombatTests.Session helper마지막optionalTimeProvider를추가하여본래기본System보존.

전체비PG scope 정상종료 **20FAIL/593PASS/26SKIP/639total**, cadence-v165-scope.trx. timeout30s설정했으나hangkill없이전체종료;IdleObserverTCP는기존5초취소실패. 즉시동일unit연사를가정한SharedBattleTests11개+PlayerCombat3개+AttackClock6개가실패. AttackClock6개는1000/2000ms→1000/4000ms 및응답tick24/48→24/96으로명시적인적법간격변경,wire시각/요청불변/피해assert유지. 새focused cadence+clock **9PASS/0FAIL**(cadence-v165-clock.trx). 나머지14개재검증아직안함. 마지막전체GREEN/v40빌드는E143/637개,현재source는그보다새롭고배포금지.

**다음즉시작업:** SharedBattleTests와PlayerCombatTests 실패목록을TRX에서읽고각테스트의기존검증목적을유지하며공유fakeclock을도입해성공사격사이에72tick진행. ParallelAttackSessions같은중복접속동시사격은합당한기대(단1회사격/나머지reload拒否)로바꿔야하며예전누적100기대를억지유지하지말것. 서로다른실제unit은독립쿨다운검증도추가. FailedPersistence재시도,재import/세대교체/구generation,wrap/역행시간테스트와PG포함전체를통과시킨뒤빌드. 테스트기다림상한유지,생산guard삭제/0tick테스트모드우회금지. guest접근/재시작/DB/EXE/모델변경0,운영v40유지. 전체goalACTIVE.

## E153 — 재사격 회귀 정상화·전체639PASS·v41 빌드 (v166)

**CADENCE_REGRESSION_GREEN / V41_BUILT_NOT_DEPLOYED.** E152의20실패중잔여14개를처리했다. OriginalSharedBattleTests는각testinstance전용BattleClock과하나의OriginalGameClock을공유한다. 새세션마다시계epoch를다시0으로만들던시험factory에optionalgameClock만추가해운영처럼같은시계를주입했다;일반기본값은보존. observer동일/다른grid/재import/latejoin/완료/TCP테스트는본래목적·ID·피해·순서assert를유지하고성공사격사이에fakeclock3000ms를명시. Attackhelper에서자동시간진행하지않는다.

ParallelAttackSessionsDoNotLoseOrDuplicateCumulativeDamage는동일unit2의8세션동시시도결과를**25피해1회+7건TACTICAL_WEAPON_RECHARGING**으로검증한다. DifferentOwnedUnitIdsStillAttackTheSameMapNpc는서로다른unit2/3이동일tick에각사격해누적50이됨을그대로검증하여전역쿨다운오류를막는다. Shared22PASS. PlayerCombat의3개누적격침/승패시험도독립CombatClock으로적법간격추가;비PG613PASS/26SKIP.

새cadence시험에두번째session0F02재import뒤71tick거부/72tick허용추가. Failed_persistence_prevents_player_shot_damage는고정fakeclock에서저장IOException/피해0뒤persistence callback정상화→**같은tick재시도25**를추가해실패사격이cooldown소비하지않음을검증. 이것은운영DB실패주입아니라시험callback경계검증이다. 임의운영손실변경0.

hostPG60680/55805/data unit-stance-pgdata-v135 실행, 실제DB포함 **639PASS/0FAIL/0SKIP**. E:/logh7-build/test-results/cadence-v166/cadence-v166-full.trx SHAECBFFF11737837474FBAF7037BED96290892A200D83B36D4DA3A8D7572F9088E. Release win-x64 self-contained E:/logh7-build/server-v41-player-cadence-v166 publishexit0,DLL **8A9A17995F54AEB1060E52CD28BF06B7414E16350709AFC2EC2B53920B83C3E4**. PGexactexecutable/data소유확인후fast정상종료,파일삭제0. 새일반PG실행시기존postmaster.pid/port먼저확인.

v41은E152생산cadence를포함하고이번생산코드추가변경없음. 서버재시작시쿨다운영속화/모든무기별원본충전/0405지속공격복원은포함하지않는다. generation교체/wrap/서로다른grid동일unitstale동시성의cadence전용추가회귀는아직미실시;기존incarnation/move전체통과만을그새경계전부의증명으로확대하지말것. **운영은v40,guest접근/배포/재시작0**,최신직접신원은E150(server8712/client1192/PG1812부두)이며이번fresh생존조회없음.

다음: cadence전용경계검증보완또는v41데이터보존배포후현재E151의UI선택eligibility관찰과실제플레이어사격시나리오합류. UI테스트가플레이어command송신을입증하지못한상태유지;세대6부두를DB로reset하거나같은단기격침반복금지. 전체goalACTIVE.

## E154 — 재사격 시계 순환·늦은 접속 검증, 전체642PASS (v167)

**CADENCE_WRAP_AND_LATE_CONNECTION_VERIFIED / V41_STILL_NOT_DEPLOYED.** OriginalPlayerFireCadenceTests에기존생산경계보호3cases추가. firstShotTime10000ms와178956970000ms 두case: 실제OriginalGameClock.Tick240/4294967280에서최초사격25,서버clock1ms역행거부,2999ms후71tick거부,3000ms후72tick허용50. 순환case의다음tick은**56**을literalassert하여uintwrap을확인한다. fakeTimeProvider사용이며운영시계조작0.

늦은접속test는동일OriginalGameClock을공유:첫session0ms생성/1000ms사격,두번째2000ms생성/즉시거부,4000ms(첫사격+3초)허용. OriginalPlayerCombatTests.Session에optionalgameClock만추가해시험factory로전달,기본동작유지. 새접속마다epoch0으로만드는시험helper와운영서버공통epoch를혼동하지않는다. focused5PASS. 기존생산코드를바꾸지않은회귀검사라신규productionRED→GREEN주장없음.

hostPG22584/55805/data unit-stance-pgdata-v135에서전체 **642PASS/0FAIL/0SKIP**, E:/logh7-build/test-results/cadence-v167/cadence-v167-full.trx SHA **F363E46394F2A2A6B1617B2359E7231C28E5FDF8878BC9E45867F5A2D18D2F2B**. 동일handle21730 terminalexit0확인후PG정확소유검사→fast정상종료/삭제0. 생산source변경0이므로기존v41패키지DLL8A9A17995F54AEB1060E52CD28BF06B7414E16350709AFC2EC2B53920B83C3E4 유지,새publish안함.

generation교체/crossgridstale동시성의cadence전용검증은아직별도남음;전체게임목표완료아님. 다음v41데이터보존배포와E151통합UI관찰/자기함선선택/실제사격으로합류한다. guestv40/runtime/DB이번접근0,최신직접신원E150,현재생존은배포전fresh검사필수. 같은UI관찰실패격침을반복하지않고선택eligibility와입장활성값을먼저확인한다. 전체goalACTIVE.

## E155 — v41 데이터 보존 배포 및 자동 로그인 완료 (v168)

**V41_DEPLOYED / AUTOMATIC_LOGIN_AND_STRATEGY_DOCK_VISIBLE.** 2026-09-08 UTC02:33 서버 교체. Stage/Stop/Start/Client 각각 1회 실행 및 완료 확인. 이전 server8712/client1192만 종료, PG1812 유지. 저장 상태 SHA18b3d69b6ad0eaa135f54d4fc293bf4f796ec29e69f56f3c03ecc79ef446877c 교체 전후 동일. DB 정지/삭제/직접 상태 조작 0. ZIP E:/logh7-build/v41-player-cadence-v166.zip SHA EC7B70582D63DA32C14B098EE16CB7E578C81881DF41E711216F7B22DE274035. DLL은 E153의 8A9A1799... 유지.

현재 guestroot 동일 20260906T181000Z-departure-v124. 서버 **2508**, startUTC2026-09-08T02:33:43.6359545Z, server-v41-v168/Logh7.Server.exe. 클라이언트 **6688**, startUTC2026-09-08T02:33:57.6993550Z, HWND **0x00000000009C036C**, 원본 SHA AEF38276... 변경 없음. Wire server-wire-v168.jsonl. PG1812 그대로.

새 interactive-session/auth-preflight 성공 후 실제 UI FileMenu→CloseMenu→FocusId→DPAPI 자격 증명 1회 입력→로비 공지 `LOGH7 v41 shared player recharge` 확인→GameStart→기존 캐릭터 선택. 사용자 수동 로그인 요구 없음. 자격 증명 값 출력/기록 없음. 자동 로그인은 실제 성공했고 단순 입력 전송 성공과 구분한다. window-login-result-v168.png, game-start-v168.png, window-dock-confirmed-v168.png를 직접 확인. 마지막 화면은 **전략 그리드/우주항/기함잔교**, 전술 HUD 아님. 초기 캐릭터 선택 직후 캡처는 아직 로비여서 재클릭 없이 서버 기록과 후속 캡처로 진입 확인.

evidence/events-login-v168.json READ_ONLY_RUNTIME_VERIFIED, unit|2|2|102|2|4|0|0|6|10 유지. 0F02/0F06 및 정적 데이터 요청 Success 확인. 이번 전투/0405/0406 입력 0이므로 v41 실제 플레이어 재사격 검증은 아직 UNSEEN. v168 Stage/Stop/Start/Client, credential 및 클릭 영수증 모두 CONSUMED, 재사용 금지. 새 guest-*-v168.ps1들이 현재 신원 영수증을 참조한다. 다음은 E151 UI 선택 eligibility와 실제 자기 함선 선택·사격 검증이며 짧은 격침 반복/DB 리셋 금지. 전체 goal ACTIVE.

## E156 — v41 정상 출항과 궤도상 전략 상태 실측 (v169)

**NATIVE_UNDOCK_EFFECT_VERIFIED / SAME_GRID_TACTICAL_ENTRY_NOT_OBSERVED.** 직전 v168 배포/로그인은 progress. 현재 client6688/server2508/PG1812 그대로 신원 검증. 새 화면에서 역할 탭→운용 카드→出港→확인 각 1회. 카드 설명에 인사/출항과 맞지 않는 정보 문구가 보이고 출항 확인문은 아직 미치환 행성명/CP/MCP/G시간 자리표시자임. 완성 UI로 세지 않는다. 원본 입력만 사용, DB 직접쓰기/재시작/로그인/워프/전투 0.

0B06 UTC2026-09-08T02:41:05.2720645Z Success, metadata departure-unit=2;authority-version=62;design=new. events-undock-v169-v168.json 상태 unit|2|2|102|2|5|0|0|6|10. window-undocked-v169-v168.png에서 우주항/旗艦桟橋→惑星/要塞軌道上/艦内 변경 확인. 이는 정상 주둔4→출항5이며 전술 진입이나 출격6의 증거가 아니다.

새 읽기 전용 guest-command-eligibility-v169.ps1은 v164의 process/hash/PG/선택 테이블 경계 검사를 유지하되 model-ready 대기를 제거하여 전략 상태도 측정 가능하게 함. command-eligibility-v169.json UTC02:42:32.6543382Z COMMAND_ELIGIBILITY_SNAPSHOT_VERIFIED. uiActive1, commandState1, selectedCount0, commandableSelectedCount0, pickerCount0, effective/outerRadius0, unit2/mode5/base2/spot0, sceneMode2/tacticalFlag0/queueGateState0. **전략 모드의 빈 picker와 radius0을 전술 모드 결함으로 추론 금지.** read-only/memory writes0/gameInputs0. 실제 전술 선택 가능 판정은 아직 미관찰.

현재 안전한 grid102 궤도상5/세대6 상태 보존. role/card/undock-open/undock-submit-v169 및 command-eligibility-v169 영수증 CONSUMED. 다음은 같은 그리드에서 궤도상5→출격/항행6 원본 진입 경로를 확인해 적의 짧은 격침 없이 선택·이동·에너지 명령을 먼저 검증할 수 있는지 조사. 경로가 없다면 추측으로 모드6/전술flag를 덮지 말고 원본 소비자와 서버 bootstrap 조건을 대조. 실제 플레이어 사격/재사격은 여전히 UNSEEN; 전체 goal ACTIVE.

## E157 — 평화 그리드의 전술 전환 탐색 방향 수정: 매뉴얼 시작 조건 확인 (v170)

**MANUAL_AND_SCENE_GATE_CROSSCHECK / QUIET_GRID_STRATEGY_NOT_A_PROVEN_BUG.** E156은 정상 출항 실측 progress. 이번에는 원본 Ghidra 004B68F0,004C1B20,004B76E0을 새로 읽어 evidence/decompile-*-v170.c에 보존하고 현재 서버 IsCurrentTacticalFieldActive와 대조했다. E156의 '출항5→항행6으로 바꾸면 같은 평화 그리드에서 전술 검증 가능할 것'은 입증되지 않았으며 더 이상 전제로 삼지 않는다.

보존 매뉴얼 OCR E:/logh7-greenfield/evidence/manual-variants/internet-archive/gin7manual_djvu.txt SHA256 A15C2402C4B3132BA6B844D919A7BE44CAB1FE130A0DEB172EA5A78F2069F368, lines2172–2184: 전술 시작은 한 그리드의 아군·적군 유닛 공존. 종료는 적 유닛 부재 및 행성/요새가 있으면 전부 점령 조건. 이번 확인은 OCR 텍스트이고 PDF 원본 시각 재검증은 아님. lines2220–2260에는 자동 지휘권 우선순위(온라인/계급/평가/공적) 및 자기 기함·개인 명령의 커맨드 범위 영향 제외가 별도 명시됨. 현재 단일 소유자 구현이 이 지휘권 전체를 구현했다는 뜻이 아니다.

현재 NaturalAuthoritySession.cs:3496의 전술 활성은 부상 복귀 제외, 교전 미완료 AND (primary NPC 존재 OR 생존한 타진영 참여자). 이 값은0317 state와 초기0F1F 발송에 사용된다. mode5/6 자체를 활성 조건으로 사용하지 않는다. 004B68F0은 world+35F35A로 tactical/strategy import를 선택하고 sceneMode+126711=0/tacticalFlag+126718!=0일 때0050D230 실행. 004C1B20은0F1F state1→transition2,그 외→0으로 reload를 시작. 태세mode와 전술씬은 다른 축이다. 매뉴얼의 공존 조건과 현재 서버의 큰 방향은 일치하나 모든 세력관계/오프라인/주둔 유닛 조건까지 검증한 것은 아니다.

fresh events-field-gate-v170-v168.json READ_ONLY_RUNTIME_VERIFIED, server2508/client6688/PG1812 신원 확인 및 unit|2|2|102|2|5|0|0|6|10 보존. 이번 UI 입력/원본 메모리쓰기/DB쓰기/빌드/배포0. 원본 전술flag 강제수정이나 평화 그리드에 근거 없는 전술 알림 추가하지 않았다. 다음 실제 교전 검증은 기존 E151 선택·명령권 계측과 연결해야 하며, 현재 NPC의 3초 첫 사격/25 손실로 인한 짧은 UI 관찰 창을 해소하지 않은 동일 격침 재시도 금지. v41의 플레이어 사격/재사격 native UNSEEN 유지. 전체 goal ACTIVE.

## E158 — NPC 센서 배분 누락 재현, 선형 후보 회귀 실패로 미배포 (v171)

**LOCAL_CANDIDATE_INCOMPLETE / 7_REGRESSIONS / LIVE_V41_UNCHANGED.** 상세 다음 시작점: work/20260904-warp-state-reverse/evidence/sensor-policy-v171.md. 매뉴얼 p48 센서 배분→탐지범위 규칙과 현재 NPC의 nonzero-only 최대범위 사용을 대조. 정확한 원본 수식은 미회수. 임시 선형 후보를 테스트 우선으로 추가: 6cases 중 예상대로2RED/4PASS 확인 후 생산센서범위에 적용. 관련 NPC/import40cases 실행결과 **33PASS/7FAIL**. sensor-v171-green.trx라는 파일명은 성공 판정이 아님.

기본 SENSOR10/SearchingRange100→후보 범위10인데 기존 거리20에서 자율접근/재import 이동을 요구하는7cases 실패. 수색행동이 없으므로 임의 기대값 수정/배분확대만으로 가리지 말것. 수정 파일은 OriginalTacticalNpcController.cs 및 OriginalNpcAiTests.cs이며 기존 타변경 보존. 후보 완성 또는 명시적 철회 전 successor 배포 금지. 전체suite/Release publish/guest입력/배포0. 마지막 실제운영신원은E157, 이번fresh생존조회없음. RED TRX SHA43D58473167054035885B8EBD1D80F65E020EAC450CB5DDB17BB8202018DB5F9; regression TRX SHAF1261DA454DF49C8EB4C8F3AF4986D4F44949C61B9C4036FCE6F1597498AC49E. 전체 goal ACTIVE.

## E159 — 원본 센서 구간식 회수·선형 후보 철회·전체654PASS (v172)

상세 evidence/sensor-range-v172.md 및 listing-004c18c4-v172.txt/listing-005ff374-v172.txt. Ghidra 스킬로004C1700의 디컴파일에서 누락된 x87 계산을 실제 어셈블리로 확인. sensor=CommandControl+1C→context+314. index=min(3,floor(sensor*4/30)),float32배율0.5/0.7/0.9/1.0,template+258 SearchingRange 곱→__ftol절삭→entity+958. 구간0–7/8–14/15–22/23–100. 원본 클라이언트 범위 계산에 근거해 NPC adapter를 교체했으며, 서버의 확률 탐지/은폐/시야 공유·완전x87재현을 회수했다는 뜻은 아님.

v171의 근거 없는 선형 기대값을 명시적으로 철회. 신규 원본 구간 경계12cases는 기존 선형식에서4RED/8PASS, 수정 후 NPC/import46PASS. 기존7실패 fixture/기대값 완화0. 실제DB 포함 전체 **654PASS/0FAIL/0SKIP**, sensor-v172-full.trx/handle15082 terminalexit0. hostPG58100 정확 소유 확인후fast정상종료,삭제0. Release win-x64 self-contained **E:/logh7-build/server-v42-sensor-v172**,DLL **8C6A0B8DA1B3392BD6A25C4B08057E8BB282797BB57547B8973D7BEF5650A369**,publishexit0.

이번guest조회/배포/입력0. 마지막운영증거는E157의v41/server2508/client6688/grid102/mode5/gen6. 다음v42데이터보존배포 전fresh생존/DB검사필수. 실제 센서 표시·플레이어040C/0405/0406 사격 검증 미완료이며 같은 짧은 격침 반복금지. 전체 goal ACTIVE.

## E160 — v42 데이터 보존 배포·자동 로그인·궤도상 복원 (v173)

**V42_RUNNING / NATIVE_RELOGIN_WORLD_VISIBLE / COMBAT_NOT_RUN.** fresh pre-v173 읽기로 기존v41/server2508/client6688/PG1812와 unit|2|2|102|2|5|0|0|6|10 확인. v42 ZIP SHA C2555D749CC34ADF2225673D3446A9146B63E8FA98B42C06F04EEC3AD8394087. 새 guest-server-swap-v173.ps1 Stage→Stop→Start→Client 각1회 성공. stage/stop/start stateHash **5a8fd42bdfd63b24f0ebe48e6400f9d6b2fb233c4650234bc86042df549193e1** 동일. PG정지/DB직접쓰기/삭제0. 영수증 호스트 복사 명령의 경로 결합 오류는 CopyFrom만 수정하여 회수했고 배포 action 재실행0.

현재 guestroot 동일20260906T181000Z-departure-v124. 서버 **4824**, startUTC **2026-09-08T02:58:58.8753325Z**, server-v42-v173/Logh7.Server.exe,DLL8C6A0B8D... (E159 전체해시). 클라이언트 **9884**, startUTC **2026-09-08T02:59:04.4840972Z**, HWND **0x0000000005740410**, 원본SHA AEF38276... 불변. PG1812 유지. wire **server-wire-v173.jsonl**.

새 interactive-session/auth-preflight 후 화면별 확인→FileMenu→CloseMenu→FocusId→DPAPI credential1회→v42 sensor bands 공지 로비→GameStart→기존캐릭터. window-world-v173.png에서 전략그리드/惑星・要塞軌道上/艦内 복원 확인. events-relogin-v173.json READ_ONLY_RUNTIME_VERIFIED,0F02 Success, unit|2|2|102|2|5|0|0|6|10 보존. credential 및 배포/클릭 영수증 CONSUMED; 재실행 금지. 새 guest-window-observe/click/return-events-v173.ps1은 현재 영수증 신원을 참조한다.

실제센서 임계값/플레이어사격/재사격 전투 검증은 이번0. v42배포·재접속을 전투PASS로 세지 않는다. 다음은 원본 UI 선택/명령가능수/센서entity+958의 통합 관찰과 실제사격 검증. 불충분한관찰창으로같은짧은격침반복/DBreset금지. 전체 goal ACTIVE.

## E161 — 전술 준비·선택 가능 시간축 실측, 센서 정밀도 정정 (v174)

**NEW_TIMELINE_EVIDENCE / PLAYER_SHOT_STILL_UNSEEN / SENSOR_PRECISION_LOCAL_FIX.** 직전배포progress. 새 guest-entry-timeline-v174.ps1은 현재v173 프로세스/해시/PG검사 후 읽기전용 250ms 간격으로 scene/UI/state/picker/own잔존/sensorRange를 최대90초 관찰, 전술→부두귀환에서 종료. 입력/메모리쓰기0. 과거 model-ready 한점 관찰과 달리 전체 입장·피해·귀환183샘플 회수. 실행handle82351 terminalexit0 확인. entry-timeline-v174.json SHA **2758DF22C28A8AFE9309AE7979D199E48626D2D44395171233CB6C44F2555C4C**.

새 역할카드→워프→왼쪽grid→grid확인→비용확인 각1회. 최종확인 전 관찰 시작. 워프 이후 공격/선택/에너지 입력0으로 시간축을 관찰했다. 03:07:09.4215211Z tacticalFlag1/uiActive1/own100. 03:07:12.3040067Z ownpickerflags132225(0x20481),03:07:12.5735949Z commandState1/flags66689(**0x10481 명령범위내**)/잔존100. selected/commandableSelected는 입력하지 않아0. 'UI활성 전에 격침' 가설은 이번 실행으로 반박; 실제클릭 수용까지 입증한 것은 아님.

NPC arms1 첫fire03:07:16.3287398Z,후속19.3011158/22.312602/25.3082867Z로손실25/50/75/100. 명령state1부터첫피격약3.755초/격침약12.735초. 03:07:33.9265721Z strategy2/mode4 귀환. events-after-v174-v173.json 및 window-returned-v174-v173.png 확인: **unit|2|2|102|2|4|0|0|7|10**,server4824/client9884/PG1812 유지. 수동heal/reset0. consumed v174 입력/관찰 영수증 재실행금지.

**E159 정정:** 실제 기본SENSOR10/template100에서 entity+958는 **70**으로 관찰됐다. v172의extended/double곱→69는 실행환경 관찰 없이 둔 가정이었으며 철회. OriginalTacticalNpcController에서 float32곱 후절삭으로수정,관련sensor경계테스트를70/90모델로변경하고 실제10/70case추가. 기존v42에서3RED/10PASS→수정후NPC/import **47PASS/0FAIL**. 90 및다른구간은float32모델추론/테스트,이번실측값은70뿐;실행FPU controlword자체읽음주장금지. 관련 TRX E:/logh7-build/test-results/sensor-v174/sensor-v174-red.trx 및 sensor-v174-green.trx. 전체suite/새publish/배포아직0,운영v42는아직69모델. 다음전체검증후후속빌드반영,핵심native사격은준비된선택가능창에서실제자기함선선택→무기→표적을연결해야함. 동일관찰목적격침반복금지. 전체 goal ACTIVE.

## E162 — 센서 반올림 전체655PASS/v43빌드, native배분입력 타이밍 실패 (v175)

**V43_BUILT_NOT_DEPLOYED / NATIVE_CONTROL_NOT_SENT.** E161의float32곱수정전체검증:실제DB포함655PASS/0FAIL/0SKIP, E:/logh7-build/test-results/sensor-v175/sensor-v175-full.trx SHA DFEF35979D99E462DF9895BA634F030E99FFA418952CF0C872DB406110FD8D00. handle19795 terminalexit0. 테스트PG23560의정확executable/data소유확인후fast정상종료/삭제0. Release win-x64 self-contained E:/logh7-build/server-v43-sensor-rounding-v175 DLL **E74DDD2400AC8A767CCE4B69BFC76F239E5FEED7037167C4D222D0695885D9C3**,publishexit0. 운영은v42계속,배포0.

새entry-timeline-v175는v174읽기에entity+9C4(sensorPower)추가. 새warp입력1회로전술진입후보이는SENSOR슬라이더좌측696,140을1회클릭했으나 **이미귀환후입력**: timeline전략mode4귀환03:14:07.5419454Z,clicksent03:14:11.1387330Z. window-hud-v175-v173.png는전술이나postclick은전략. 따라서입력성공영수증은커맨드성공아님. 040C요청0,관찰own63샘플sensorPower10유지. entry-timeline-v175.json TACTICAL_RETURN_TIMELINE_RECORDED,handle26784 terminalexit0. 같은입력재시도금지.

events-after-v175-v173.json **unit|2|2|102|2|4|0|0|8|10**,server4824/client9884/PG1812유지. DBreset/heal0. 이번다른그리드warp목적은에너지배분명령검증이었으나실패;플레이어사격도UNSEEN. 다음실행전현재guest-click-v173의ExpectedStage가검사조건아닌로그일뿐인점을보완해야함. stale화면→입력지연동안scene이바뀌어도PID/HWND만같으면클릭하는경계: 입력직전살아있는전술scene/own생존조건검사와측정된도구지연을해결하지않고또같은짧은격침시도금지. v175입력/관찰영수증모두CONSUMED. 전체goalACTIVE.

## E163 — 입력 직전 전술·생존 검사 구현, 현재 전략 상태 실거부 (v176)

**TACTICAL_INPUT_GUARD_IMPLEMENTED / LIVE_NEGATIVE_CHECK_PASS / INPUT_LATENCY_STILL_OPEN.** E162 전체검증/build는progress이나native제어실패. 새 scripts/tactical-input-state-v176.ps1 및 test-tactical-input-state-v176.ps1: live/damaged-live허용,전략복귀/로딩/UI비활성/command초기화/격침/다른unit/미존재거부9cases. no-op경계에서7RED/2PASS→실제판정후9PASS. 기존운영게임코드/DB수정0.

guest-tactical-click-v176.ps1은v173click신원/해시검사를보존하고ExpectedUnitId필수추가. 현재world+126711/126718,controller→UI+3A0,commandState,600entity배열의own정상수>0을cursor처리전과hover후mouse-down직전에검사. 실제클릭영수증에마지막guard샘플포함. 조건변경시TACTICAL_INPUT_REJECTED_BEFORE_CLICK영수증기록후거부. GuardOnly는판정후입력없이종료하는진단옵션. 기존v173자동로그인·전략click스크립트는변경안함. 원본memory쓰기0.

새guest스크립트2개복사후현재client9884/server4824신원에서GuardOnly1회실행. vmrunexit1은예상된거부이며재실행하지않고영수증확인: evidence/guard-only-v176.json UTC03:19:14.4714138Z statusTACTICAL_INPUT_REJECTED_BEFORE_CLICK,errorTACTICAL_SCENE_NOT_ACTIVE,sceneMode2/tacticalFlag0/uiActive1/commandState1,clicks0/gameInputs0. guard-only영수증CONSUMED. 부두/전략에서실수로누르지않는실경계확인이지live전술positive클릭PASS아님.

아직남음: 스크린샷관찰→실제입력의총지연해소,전술생존상태의positive실입력,040C와사격검증. guard는마지막읽기후게임tick과경쟁하는극소window를완전히없애지는않음. 현함선상태최신DB는E162gen8부두,이번DB접근0. v43(E74DDD...)미배포/운영v42유지. guard추가만으로동일짧은격침시도를재개하지말것. 전체goalACTIVE.

## E164 — 운영 배치·입력 지연 계량, 같은 자동 전투 재시도 중단 (v177)

**LATENCY_CAUSE_QUANTIFIED / NO_GAMEPLAY_MUTATION.** E163은guard구현/실전략거부progress. 운영 server-v42-v173/battlefields/catalog.json을 새로 읽어 evidence/live-battlefield-catalog-v177.json 보존,SHA0012B284E491220458B6A4565C0D45FEA6731355FCF70A24D81FE64AE4857ACB. 기본 open-space 및rear template 모두playerX=-10/enemyX=10. 운영에별도근접enemyX=-7fixture가남았다는가설은반박됐다. 현재Speed1/engine20 모델의최대접근속도4.8좌표단위/초는작성된임시정책이며원본밸런스아님.

새회수 window-hud-v175-v173.json captureStart03:13:57.7239832Z 대 sensor-min-v175-v173.json sentAt03:14:11.1387330Z: **end-to-end13.4147498초**. 이는host/guest/관찰/판단/입력전체합이며개별원인을전부분해측정한것아님. E161 명령가능→격침12.735초보다길다. 전술guard는오입력을거부하지만관찰지연자체를해결하지않으므로같은자동격침loop금지.

이번서버/DB/배치/피해/AI속도/원본EXE변경0,UI입력0. 검증통과를위해전투규칙을약화시키지않음. 실제native사격의다음선택으로사용자의한차례직접조작협조를요청하고서버·프로토콜·상태기록을동시검증하는방법을제시. 사용자응답전워프/공격실행하지않음. 이는전체게임목표완료나모든개발작업blocked판정아님. 현재마지막상태E162gen8부두/운영v42,v43미배포. 전체goalACTIVE.

## E165 — 중복 접속 에너지 배분 공유·발사 판단 수정, 전체659PASS (v178)

**SHARED_CONTROL_FIXED_IN_LOCAL_SOURCE / V44_BUILT_NOT_DEPLOYED.** E164 입력협조응답전새전투안함. 다른플레이어조회가오래된값이라는초기가설은테스트PASS로반박: ProcessAsync끝PublishOwnParticipantSnapshot가이미갱신함. 그와달리같은함선중복접속은원래session-local배분을읽고다시발행해실제control20을10으로되돌리는경계. OriginalSharedControlTests:다른unit조회PASS/같은unit중복조회1RED 확인. 최초control-v178-red.trx는PASS이므로이름만으로RED주장금지;실패증거control-v178-duplicate-red.trx.

OriginalTacticalBattleRegistry.PlayerControl.cs에unit+shipGeneration별acceptedcorps공유상태추가. 오래된세대쓰기최신세대덮어쓰기불가. NaturalAuthoritySession.CurrentPlayerCorps를통해scene/projection/participant발행/beam·gunpower/warpPower/control수정기준을일치시킴. accepted040C후기록,거부명령은기록0. 서버재시작간DB영속화나중복접속즉시push는이번범위아님. 기존energyfill/shield값보존.

4개회귀:서로다른unit조회,중복unit조회,중복접속의꺼진beam사격거부/피해0,신규세대비상속/oldgeneration덮어쓰기거부. focused4PASS,control/warp/cadence관련19PASS(추가2cases전),실제DB포함전체 **659PASS/0FAIL/0SKIP**. control-v178-full.trx SHA207699B1E3067E2301B6E1C3457D7FC9ACB17ACE5429844A6CB06CC21EA261E1. 테스트PG22440소유확인후fast정상종료/삭제0.

Release win-x64 self-contained **E:/logh7-build/server-v44-shared-control-v178**,DLL **9CF9DF3949798B9010F1117736A15BA1CBF53C56B6D250CA1F2CA3B18A02B186**. v174센서float32수정도포함;v43별도배포불필요. guest조회/입력/배포0,운영v42마지막생존E163,DBgen8부두마지막E162. native040C/사격미검증유지. 다음v44배포시fresh신원확인필수,사용자협조응답전새짧은전투loop금지. 전체goalACTIVE.

## E166 — 격침 함선의040C수락 차단, 전체660PASS/v45빌드 (v179)

**DESTROYED_CONTROL_REJECTED / V45_BUILT_NOT_DEPLOYED.** 직전E165실수정/전체검증progress. ProcessTacticalControlAsync에는세대확인만있고개별함선생존확인이빠짐. 교전전체미종결/자기손실100에서040C를보내자tactical-control-accepted가나오는1RED를실제session경로로재현했다. 이후기존move/attack과같은_tacticalEncounter.HasSurvivors판정을grid controlLease안,배분Apply/공유기록전에추가. 거부코드TACTICAL_ACTOR_DESTROYED,원본visible메시지. 테스트는거부뿐아니라session-local/shared배분미기록과033F갱신미발송확인.

관련47PASS,실제DB포함전체 **660PASS/0FAIL/0SKIP**, dead-control-v179-full.trx SHA **BE62C7F86686A5CD0F6ACB336BD15BE5222A1AFAB8842A4ACD72BF3C9F5FADA3**. handle84692 terminalexit0후hostPG36284소유확인→fast정상종료/삭제0. Release win-x64 self-contained **E:/logh7-build/server-v45-dead-control-v179**,DLL **07625B85596C60D27CD15EC33E8BDF8092A9DF5BC2541386AA992EB304AD8F25**,publishexit0. v43센서반올림+v44공유배분수정포함,중간버전별도배포불필요.

guest실행조회/입력/배포0,운영마지막확인v42/gen8부두유지판정은과거증거이며이번생존주장아님. native040C/플레이어공격은아직UNSEEN. 직접조작협조답변전새전투시작하지않는E164경계유지. 다음배포시fresh신원/상태검사필수. 전체게임요구(전체전략·전술명령,실제효과,AI,자산연결)는미완료이며전체goalACTIVE.

## E167 — 전체 콘텐츠 범위 및 함대 캐시의 실제 선행 패킷 확인 (v181)

사용자는 개별 소수 수정 반복보다 다진영 동시 함대전과 전체 콘텐츠 구현을 요구했다. 범위는 docs/goals/2026-09-08-original-client-all-content-scope.md에 기록했다. 기존 새 엔진 목표 문서로 작업 대상을 바꾸지 않는다.

이번 원본 정적 분석에서 004C32A0의 nonzero unit.outfit → world+811FC 부대 캐시 조인을 재확인했고, 004C2A80의 캐시 재구축이 **032B ResponseInformationOutfit** 테이블(world+3DFE98, record stride1C)을 소비함을 확인했다. 032F 편성 응답은 다른 테이블(world+35F35C)이며 이것만 공급해도 부대 캐시가 생긴다는 가정은 금지. 캐릭터 없는 함선은 부대 캐시에서 faction/camp를 얻는 경로가 있다. 모든 일반 함선에 임의 인물 레코드를 붙이는 설계로 원본 의미를 대체하지 않는다.

영수증: work/20260904-warp-state-reverse/evidence/fleet-cache-join-v181.md 및 fleet-field-import-v181.c, fleet-cache-rebuild-v181.c, fleet-unit-parser-v181.c. 서버에서는 unit.outfit/boarding_ship=0 고정과 032E/032F 코덱의 세션 호출자 부재를 확인했다. 다음 첫 작업은 032B reader/logger의 packed layout 회수 후 부대 목록·유닛 소속·편성 조회를 같은 권위 데이터로 연결하는 것이다. 이번 서버 코드 변경/빌드/배포/게임 입력0, 함대전 실플레이 UNSEEN, 목표 ACTIVE.

## E168 — 032B packed 레이아웃 회수 및 부대·유닛 소속 직렬화 구현 (v182)

원본 0041BBD0 reader(기존 Ghidra에서 함수 미정의여서 분석 함수만 생성, EXE 변경0), 0041C330 logger 회수. count:u8 최대100. 각 레코드 id:u32, kind/power/camp/index:u8, achievement:u16, strategy_id:u32, practice warp/speed/command/offence/defence/antiaircraft/search/deception/landbattle/airbattle 각u8. native stride28, packed24바이트이며 padding 전송금지. 증거 evidence/outfit-reader-v182.c 및 outfit-logger-v182.c.

OriginalInformationOutfitCodec.Encode 추가: 032B 응답, 실제 필드 전송, null/101개 거부, 100개 허용. OriginalInformationUnitProjection에 Outfit/BoardingShip 기본0 필드 추가, EncodeUnits가 해당 값 전송하도록 변경. 새 OriginalFleetWireTests는 실제 프레임의 소속 위치 및 손계산 literal packed bytes, 빈 목록/최대 목록/초과/null 경계를 검증한다. 처음 using Xunit 누락 컴파일 오류는 RED 증거로 세지 않음. 수정 후 API 누락2FAIL, 이후 packing2FAIL/소속1PASS, 구현 후 관련 **21PASS/0FAIL/0SKIP**, dotnet terminal exit0. TRX E:/logh7-build/test-results/fleet-wire-v182/fleet-wire-v182-green.trx.

현재 코덱은 아직 세션 실서빙 호출자가 없고 부대 목록 공급/편성 권한/다함대 카탈로그 연결은 미완료. 다음은 NaturalAuthoritySession의 0F02 refreshFrames 및 bootstrap gridFrames가 같은 부대 스냅샷의032B를 원본 재구축 전에 공급하도록 연결하고, 실제 부대 소속 데이터와032E 조회를 구현하는 것. 빈 응답만 삽입해 통합 완료로 세지 않는다. 전체 테스트/새publish/운영 배포/게임 입력0. 이번 변경만 별도 배포하지 않고 함대 기능 묶음으로 합류한다. 목표 ACTIVE.

## E169 — 콘텐츠 기반 다함대 등록·032B 실제 공급·두 전장 NPC 교전 연결 (v183)

OriginalBattlefieldFleet/FleetShip 및 template.Fleets 추가. 명시된 exact grid에 여러 부대와 일반 함선을 배치한다. 임시 ID namespace7E000000..7EFFFFFF, 부대/함선 ID 전체 카탈로그 중복 거부, fallback grid0의 fleet 배치 거부(다른 grid로 동일ID 복제 방지),100부대 및598함선 상한, 실제030B로 공급하는 일반함선kind3/89/93만 허용한다. 이 범위/훈련0/동일capabilities100/AI는 NEW_DESIGN 임시 정책이며 원본 밸런스 회수 주장이 아니다. 플레이어 다수로 총600을 넘는 입장 정책은 추가 구현 필요.

NaturalAuthoritySession.RegisterAuthoredNpc가 카탈로그 fleet를 기존 공유 registry에 등록한다. 기본단일NPC는 SpawnEnemy로 별도 유지. 초기/refresh 0F02에 참여자 스냅샷의 부대정보032B를 공급한다. OriginalTacticalParticipantSnapshot.Outfit 추가 및 동적 entry에서032B 포함/없는 CharacterFrame 생략, NPC 이동·damage projection과 플레이어 표적 projection에서 Outfit 보존. 별도 가짜 인물 생성 없음.

원본004B5C00/004B5C50은 non-player entity+96C에 지휘식별자를 저장/조회하며, 인물연결 객체는 해당 PLAYER_INFO+24 경로를 쓴다. 새 일반함선은 TacticsInformationUnitShip.Character 필드와 Corps.Id에 부대ID를 넣어 이 non-character 경로를 사용하려는 CANDIDATE 연결이다. faction/camp는004C32A0의 outfit lookup 경로. 실제 네이티브 일반함선 label/control/0341 소비 검증 전 원본 함대 지휘 구현완료 주장금지.

OriginalFleetContentTests: 기존소스에서 중복ID 허용/미등록2FAIL 확인. 첫 실행의 namespace 누락 컴파일오류는 RED 아님. 이후 scene fixture에 createdCharacter 없는 문제를 기존 OriginalPlayerCombatTests.Session으로 고쳐 실제0F02경로 사용. 두 진영2부대4함선 등록, 소속/032B/빈인물부재/이동후동적entry보존/NPC 상호사격 확인. 두 번째 그리드103에 별도ID의2부대4함선을 같은 registry로 등록하여 같은 tick 진행에서 두 전장 모두사격하고 대상이 다른grid에 섞이지 않음을 확인. 이는 서버 통합 테스트이며 DB/원본 화면증거가 아니다.

관련 최종 **101PASS/0FAIL/0SKIP**, terminal exit0; E:/logh7-build/test-results/fleet-content-v183/fleet-content-v183-multigrid.trx. 전체suite/운영catalog변경/새publish/배포/UI입력0. 운영은 과거v42증거뿐, 이번 생존조회 없음. 다음: 일반함선의0322 조회에 빈 CharacterFrame을 응답하지 않도록 처리, 부대032A/032E 조회와 명령 지휘권·Corps 공유를 연결, 콘텐츠 설정의 추가 경계 테스트, 실제 native 선행 검증 후 묶음 배포. 함대 명령·진형·승패 영속화·실제 사용자전투·전체콘텐츠는 미완료. 목표 ACTIVE.

## E170 — 기함/휘하 구분 및 v184~v187 통합 다음 시작점

사용자지적에따라 E169의 “8함선”은8전투유닛으로정정. 실제함선수와유닛수를구별한다. 다음상세영수증을순서대로참조(work/20260904-warp-state-reverse/evidence):

- fleet-query-v184.md:032E→032F 전장NPC부대조회연결,0322 빈인물응답차단,108관련PASS.
- unit-complement-v185.md:유닛별편제상한/생존/피해적용,서로다른편제NPC등록,공격자수량으로표적생존판정하던버그수정,79관련PASS.
- flagship-complement-manual-v186.md:매뉴얼OCR에서기함1/일반300반복확인.손해상한검증을영속쓰기전에이동,24관련PASS.
- subordinate-kind-complement-v187.md:일반subtype32/56/119/139를별도030B슬롯으로공급,서버등록도동일300.기존기함후보0/3/89/93은100유지.109관련PASS.첫4종누락RED와최종TRX명시.

현재기함1척은아직구현완료아님.기함Number1과내구Existence·damage/destroyed를함께복원해야한다.임시25피해와Number1만결합해1발격침을원본처럼만들지말것.함대지휘관/권한/진형/032A와native화면검증도미완료.운영은과거v42증거만있고이번묶음배포0.기존liveDB피해리셋/소비된입력반복금지.전체goalACTIVE.

## E171 — v188~v193 기함 수량·영속화·클라이언트 투영 및 현재 승인 경계

work/20260904-warp-state-reverse/evidence의후속영수증:
- flagship-damage-and-grid-capacity-v188.md:damaged/destroyed는척수,Existence=HP근거없음.원본재편성코드의유닛수*300확인.그리드당진영별300유닛/최대2진영은함선300척과별개.
- single-hull-policy-v189.md:승인된임시단일척정상→손상→격침(두번피격)정책,원본피해공식아님.
- single-hull-storage-return-v190.md:등록수량을세션피해저장·귀환요청에전달.
- unit-complement-persistence-v191.md:schema026 unit_number DEFAULT100로기존손실보존,실제DB1/100재접속복원,전체690PASS.
- flagship-template-projection-v192.md:030A→030B에현재기함저장수량투영.동일kind수량충돌은030A/0F02거부.이미로드된template에대한동적새참가자/워프캐시검증은미완료.
- return-complement-validation-v193.md:새귀환요청수량과DB일치검증,과거replay보존,실제DB포함전체693PASS/0FAIL/0SKIP。TRXSHA DBBF6CD6E24BF299A2FE910A49A66EE2CFF858C1E01B9CF14E7E94024107AE29。PG54284정상정지.

현재사용자응답대기:기존100척임시기함을1척으로변환할때원래수치를보존하고정상/손상생존/격침상태를대응할지,기존유지하고새기함부터적용할지질문함.응답없이운영기함값변환금지.아직원본기함1척운영검증완료아님.운영v42이후생존이번확인없음,새publish/배포0.변환선택은전체개발blocked를뜻하지않음.동적입장template일관성/기함·휘하지휘/전체콘텐츠가남아있음.전체goalACTIVE.

## E172 — v194~v196 진영과 기함/휘하 지휘 경계

영수증은 work/20260904-warp-state-reverse/evidence 아래에 있다.

- power-camp-combat-v194.md: Power+Camp 적대 판정과 동일 Power의 다른 Camp 교전. 관련 82 PASS; 원본 반란 Camp 수치 회수 또는 플레이어 Camp 저장 완료는 아님.
- quiet-field-camp-v195.md: 귀환/출항의 후방 안전 검사에 Camp 및 아직 등록되지 않은 콘텐츠 유닛 반영. 관련 77 PASS, DB 테스트 1 SKIP. v193 전체 테스트가 이 변경까지 검증한 것은 아님.
- flagship-command-identity-v196.md: 기함 1척/일반 유닛 300척 및 명령권 구분을 재확인. 004C3B16→004B5CF0의 인자는 InformationUnit.outfit이며 entity+970/playerInfo+324 저장으로 연결된다. 지휘관 필드라고 임의 해석하지 말 것. 자기 기함 단독 승인 제한은 아직 남아 있다.

v196은 정적 조사와 범위 보강만 수행했다. 새 테스트/빌드/배포/클라이언트 입력은 없고 전체 목표는 ACTIVE다. 다음은 지휘권 배정 통지와 선택 가능 유닛 필터에서 실제 제어자 관계를 추적하는 것이다.

## E173 — v197 제어자 인물 ID와 지휘권 변경 통지 연결

영수증: work/20260904-warp-state-reverse/evidence/changed-authority-consumer-v197.md 및 authority-004c2c80/004c09e0/004c7360/004c96c0-v197.c.

004C2C80 생산자 확인으로 PLAYER_INFO+24는 인물 ID, +48은 기함 유닛 ID로 분리됐다. 원본 InformationCharacter+24가 기함 ID인 것과 혼동 금지. 004B5C00은 PLAYER_INFO+24 또는 일반 entity+96C의 제어자 인물 ID를 반환한다. entity+970은 별도 outfit이다.

NotifyChangedAuthority 0439 →004C09E0→004C7360: 기준 유닛에서 제어자 인물 ID를 읽어 대상 유닛들에 복사한다. 네이티브 레코드 +0 기준 유닛 ID, +4 u8 개수, +8 대상 ID 배열. 136바이트 네이티브 구조를 그대로 wire로 보내지 말 것; input_from_stream 미회수. 004C96C0은 현재 인물 ID와 일치하는 유닛에 선택정보 분류0x400, 다른 동일 Power0x800, 다른 Power0x1000을 부여한다. 전체 명령 가능 필터는 별도004EC6B0의0x10000/0x20000 생산자 추적 필요.

현재 fleetId를 Ship.Character/Corps.Id에 넣는 임시 투영은 실제 지휘권 증거가 아니다. 다음은0439 wire reader/지휘권 변경 request sender와 명령 가능 필터를 회수하고 서버 배정·AI 제어 양도·복수 유닛 명령에 연결한다. 이번에는 정적 조사 및 증거 저장만 수행; 테스트/배포/실전 입력0. 전체 목표 ACTIVE.

## E174 — v198 지휘권 변경 통지 wire 회수와 인코더

영수증 work/20260904-warp-state-reverse/evidence/changed-authority-codec-v198.md 및 changed-authority-reader-v198.c. 원본 reader004A94D0 확인:0439 이후 기준 UNIT ID u32, 대상 수 u8(최대32), 대상 UNIT ID u32 배열. 네이티브+5..+7 패딩은 wire에 없음. 시간 필드 없음.0개 허용. Ghidra 함수 생성만 했고 EXE 패치는 없음.

OriginalChangedAuthorityCodec/Tests 추가. 미구현 상태5RED→관련52PASS/0FAIL/0SKIP. TRX E:/logh7-build/test-results/authority-codec-v198/authority-codec-v198-green.trx. 인코더는 아직 세션 핸들러에서 호출하지 않으며 실제 지휘권 배정·AI 제어 양도·지휘 범위·복수 명령 처리는 미완료다. 서버 단독 표시용 권한 통지를 먼저 보내지 말고 서버 상태/명령 승인과 함께 연결할 것. 새 배포/실전 입력 없음. 전체 목표 ACTIVE.

## E175 — v199 지휘권 요청0420 및 지휘 원 선택 플래그

영수증 work/20260904-warp-state-reverse/evidence/authority-request-range-v199.md. reader004A3D60/listing과 logger00499F20으로0420 time/wait/id u32, count u8<=32, unitIDs 배열, target u32 확인. OriginalTacticalCommandCodec.TryDecodeChangeAuthorityCommand 추가;3RED/1PASS→관련56PASS/0FAIL/0SKIP. 아직 세션 실행 핸들러와 연결되지 않았다.

004EF0D0은0x10000/0x20000 거리 플래그 생산자다. 자기 제어 및 같은 Power+Camp 다른 제어 유닛 양쪽 모두 거리 플래그를 받으므로 이를 권한 승인과 동일시하지 말 것.004EC600/004EE250 지휘 원 반경=min(elapsed/duration,1)*max,00510CCB 인자는 own entity+4/+8C8/+8CC/+8D0. 렌더러 중심Y=-0.08과 시간 단위/예외를 원본 서버 법칙으로 무비판 복제하지 말 것. 다음은0420 request sender의target 의미와 지휘권/AI 제어 상태를 연결한다. 새 배포/실전 입력0, 전체목표ACTIVE.

## E176 — v200 지휘권 target은 선택 유닛 ID

영수증 work/20260904-warp-state-reverse/evidence/authority-target-meaning-v200.md.004B47F0의 유일 호출0050F378은(1,&clickedUnit,DAT_02215128)을 전달한다. 즉0420 unit[]은 새 클릭 유닛, target은 기존 선택 유닛 ID다. 인물/부대 ID 아님. 클릭 mask981→004F12B0은 same-side other-controller800 및 flagship/ordinary80/100을 허용한다. 원본이 이 경로에서 일반 유닛만 허용한다고 가정하지 말 것.

0439 reference=selected target / destinations=clicked units 방향은 원본 sender/consumer+매뉴얼에서 도출한 추론이며 원본 서버 왕복 관측은 없다. 현재 NPC registry는 생존 NPC를 모두 Advance하므로 Ship.Character 또는0439만 변경해선 AI 제어가 중지되지 않는다. 배정 영속화/전장 투영/AI 제어 양도/권한 검증을 같은 grid lease 아래 일관되게 구현해야 한다. 이번 정적 조사만 수행, 새 테스트/배포/실전 입력0. 전체목표ACTIVE.

## E177 — v201 NPC 자율 제어 중지/재개 기반

영수증 work/20260904-warp-state-reverse/evidence/npc-control-suspension-v201.md. OriginalTacticalNpcController.SetAutonomousControl 추가. 중지중 Advance는 이동/표적/사격 없이 기존 snapshot을 보존한다. 제어 전환시 표적/시간 이력을 비워 재개 첫 tick 사격·catch-up 이동을 막고, 같은 mode 재설정은 cooldown을 초기화하지 않는다. 임시 AI 구현 정책이지 원본 AI 복원 사실은 아니다.

실제 controller 테스트2RED/1PASS→NPC/함대 회귀49PASS/0FAIL/0SKIP. TRX E:/logh7-build/test-results/npc-control-v201/npc-control-v201-green.trx. 아직 지휘권 배정 핸들러가 이 메서드를 호출하지 않는다. Ship.Character/영속 배정/0439송신과 원자적으로 연결해야 하며 이 API 자체는 권한을 주지 않는다. 기본 NPC는 기존처럼 자율 제어한다. 배포/실전 입력0, 전체목표ACTIVE.

## E178 — v202 전장 배정 투영과 AI 모드 연결

영수증 work/20260904-warp-state-reverse/evidence/npc-assignment-projection-v202.md. Registry.ApplyNpcControlAssignment는 기존 NPC를 찾아 Ship.Character/Corps를 일치시켜 교체하고 자율제어 모드를 적용한다. Unit/좌표/소속/진영/세대/피해원장은 유지한다. 새 snapshot 구성·검증 후 변경하며 잘못된0/Corps ID 불일치는 기존 상태를 바꾸지 않는다. 기존 CharacterFrame을 유지하므로 기함 재배정 UI까지 해결한 것으로 간주하지 말 것.

실제 registry 테스트2RED→관련51PASS/0FAIL/0SKIP. 재등록 refresh에도 배정 유지, AI중지 중에도 적의 공격 대상으로 남음 확인. TRX E:/logh7-build/test-results/assignment-v202/assignment-v202-green.trx. 이 경로는 단일 유닛의 신뢰된 내부 투영 적용이며 세션 요청/권한검사/DB저장/0439송신은 아직 미연결이다. 호출자는 grid lease 및 저장된 배정의 세대/리비전과 실제 인물 데이터 검증을 책임져야 한다. 다음은 영속 배정 및 복수 유닛 원자 처리·플레이어 명령 라우팅. 배포/실전입력0, 전체목표ACTIVE.

## E179 — v203 복수 배정 선검증·일괄 반영

영수증 work/20260904-warp-state-reverse/evidence/assignment-batch-v203.md. ApplyNpcControlAssignments와 ExpectedGeneration 포함 레코드 추가. 모든 대상/중복/세대/Corps를 검증하고 detached snapshot 준비 후 commit하므로 뒤쪽 오류로 앞쪽만 변경되지 않는다. caller-held grid lease 기준 명령/AI 사이 원자성이며 DB/lock-free reader/송신 원자성은 아니다. 기존 단일 적용 경로에 세대 체크가 새로 들어간 것은 아님.

2RED/3PASS→관련56PASS/0FAIL/0SKIP. TRX E:/logh7-build/test-results/assignment-batch-v203/assignment-batch-v203-green.trx. 실제0420핸들러/DB배정저장/복원/0439송신은 미연결. 다음은 영속 배정과 원본 인물/부대 권한 및 장면 복원 연결. 배포/실전입력0, 전체목표ACTIVE.

## E180 — v204 일반 함정 유닛 별도 DB 편제 기반

영수증 work/20260904-warp-state-reverse/evidence/fleet-roster-schema-v204.md. 기존 original_grid_unit은 unit_id=character_id/인물당1행이므로 일반 유닛 저장 불가.0027_original_fleet_unit 추가:부대/종류/진영/그리드/척수/손실/좌표/항속/선택적 실제 제어인물FK/AI모드/세대/리비전. 기존 기함 데이터 변환·새운영seed 없음.

격리PostgreSQL table absent1RED→새편제테스트+기함피해저장1/100 총3PASS/0FAIL/0SKIP. 새연결2유닛/590잔존척/가짜인물0 확인. PID50404 exe/data확인 후 정상정지, 테스트schemas보존. 운영DB/게임 미접촉.

아직 storeAPI/실제카탈로그import/장면복원/배정트랜잭션 없음. 중요:player ID범위가7E authored와 겹쳐 두테이블간 ID중복 방지 필요. 전체 전술 필드/명령상태 저장도 미완료. 이 schema만으로 편제영속화 완성/배포가능이라 판정하지 말 것. 다음은전역ID충돌 방지와 저장/복원API. 전체목표ACTIVE.

## E181 — v205 player/fleet 공통 unit ID 충돌 방지

영수증 work/20260904-warp-state-reverse/evidence/global-unit-identity-v205.md.0028 공통ID등록테이블 및 두유닛테이블 INSERT/ID UPDATE 트리거 추가. 기존중복은 migration실패(자동재번호/삭제없음), 새충돌은 unique23505로거부. 삭제후에도 category ID예약 유지(복원서버설계).

실제PG 기함먼저/일반먼저/병렬attempt 모두양쪽성공하던3RED→기존편제/기함피해저장포함6PASS/0FAIL/0SKIP. 병렬테스트는 동시dispatch이며 실제lockwait겹침강제검증은아님. TRX E:/logh7-build/test-results/global-unit-v205/global-unit-v205-green.trx. PG33756정상종료, 테스트schemas보존. 운영DB/게임미접촉.

다음은fleet저장/복원API와revision CAS배정트랜잭션, 장면복원,0420권한핸들러. 전체전술명령상태저장도남음. 배포/실전입력0, 전체목표ACTIVE.

## E182 — v206 편제 저장·조회·CAS API 및 전체722PASS

영수증 work/20260904-warp-state-reverse/evidence/fleet-store-v206.md. PostgresFleetUnitStore/OriginalFleetUnitRecord 추가. 누락행만 생성,그리드별조회,revision/generation/소속일치시에만 동적상태저장. 초기seed 재등록이 기존손실/위치를덮지않음. 새datasource복원/그리드변경/오래된버전거부 실제DB검증.

집중7PASS 및 누적전체722PASS/0FAIL/0SKIP(실제PG포함), exec85722 exit0. TRX E:/logh7-build/test-results/fleet-store-v206/fleet-store-v206-full.trx SHA256 9E58B63A420917CD98F16224412C6BCCCB9BA89259D676B069136E96FE844015. 초반stub CS9113 수정후 실제NotImplemented1RED확인. PG51796확인후정상정지,schemas보존.

아직host/session에서store호출안함. scalar편제만저장하며 전체Corps/shield/detachment/order상태미완료. catalog기존행불일치조정,복수배정DB트랜잭션,인물/부대권한,장면복원/피해연결이남음. 운영게임/DB미접촉,배포/실전입력0. 테스트전체PASS를원본게임플레이완료로승격금지. 전체목표ACTIVE.

## E183 — v207 실제0F02경로에 일반편제DB복원 연결

영수증 work/20260904-warp-state-reverse/evidence/fleet-scene-restore-v207.md. PostgresAccountStore가IOriginalFleetUnitStoreProvider 제공, scene reset async→RestoreFleetRosterAsync. 누락카탈로그행seed(기존행불변),현재그리드DB조회→종류/부대/진영/척수확인→저장위치/항속/세대/피해등록. 이미메모리에있는NPC는refresh로되돌리지않음. 다른저장구현/primarylegacyNPC경로유지.

수정된실제계정fixture+PostgreSQL store로0F02최초/refresh,033B좌표,35/10복원 및메모리50피해가DB35로안돌아감 확인. 최초fixture는인물설정누락으로다른경로였으므로RED근거에서제외. 수정fixture에서restore호출만제거한red-corrected1FAIL→최종관련15PASS/0FAIL/0SKIP. TRX E:/logh7-build/test-results/fleet-scene-v207/fleet-scene-v207-final.trx. v206전체722는이번변경이전임. PG40864정상정지,schemas보존.

다음최우선:전투중일반유닛피해/이동DBwriteback. 현재복원만연결돼새전투결과는아직프로세스재시작에유실가능. human/controller!=null 복원은명시적거부(실제인물/Corps resolver미완료). 전술전체필드/배정DB트랜잭션/0420권한/동적template/다중그리드이동일관성남음. 운영DB/게임미접촉,배포/실전입력0,전체목표ACTIVE.

## E184 — v208 일반 유닛 피해 저장 연결

영수증 work/20260904-warp-state-reverse/evidence/fleet-damage-writeback-v208.md. 초기복원후 registry에PostgresFleetUnitStore/행revision binding. 공통CommitUnitDamageAsync의 비플레이어경로에서fleet CAS저장성공후피해확정. 현재피격유닛위치도함께저장. 실패는FLEET_UNIT_SAVE_CONFLICT,피해메모리불변. viewer session이아닌registry소유.

실제DB공통피해commit60/20→새연결revision2→새session0F02복원확인. 외부revision변경뒤80/30거부·메모리60/20유지.1RED→관련39PASS/0FAIL/0SKIP. TRX E:/logh7-build/test-results/fleet-damage-v208/fleet-damage-v208-green.trx. 테스트는공통commit직접호출이며native사격증거아님. PG11196정상정지,schemas보존.

남음:피격없이이동만한유닛의pose writeback(현재없음),저장성공응답유실의멱등처리,세대전환시binding교체,지휘권DB트랜잭션/복원/명령승인. 운영미접촉·배포/실전입력0,전체목표ACTIVE.

## E185 — v209 피격 없는 일반 유닛 이동 저장

영수증 work/20260904-warp-state-reverse/evidence/fleet-motion-writeback-v209.md. PrepareAdvance가복제한pending AI에서계산→PersistFleetPoseAsync CAS저장→AI snapshot/timing commit→이동통지. 저장실패시해당actor위치/AI진행/이동통지불변. bound일반유닛만DB저장,legacy무DB AI경로유지.

실제PG/registry AI의이동-only새연결저장·외부버전충돌시위치와큐불변2RED→관련52PASS/0FAIL/0SKIP. TRX E:/logh7-build/test-results/fleet-motion-v209/fleet-motion-v209-green.trx. PG35208정상정지,schemas보존. 매이동유닛별저장이라대규모성능미측정;전체tick/사격원자성아님(앞actor또는이동성공뒤다음피해실패가능).DB응답유실멱등처리도남음.

다음은지휘권배정DB트랜잭션/실제인물Corps복원/복수명령연결. 전술전체필드,세대/그리드전환binding,실제클라검증미완료. 운영미접촉,배포/실전입력0,전체목표ACTIVE.

## E186 — v210 지휘권 복수DB트랜잭션

영수증 work/20260904-warp-state-reverse/evidence/fleet-control-transaction-v210.md. SaveControlAssignmentsAsync/OriginalFleetControlWrite 추가. unit/grid/outfit/generation/revision일치시제어인물/AI모드/revision만변경,ID순서잠금.뒤행불일치/예외면전체rollback.위치/피해불변.

실제DB실존인물1명에일반2유닛배정후새연결확인,뒤행stale및없는인물FK오류23503둘다앞행까지rollback.1RED→관련5PASS/0FAIL/0SKIP. TRX E:/logh7-build/test-results/fleet-control-v210/fleet-control-v210-green.trx. PG58128정상정지,schemas보존.

아직runtime배정caller없음. 이store를단독호출하면registrybindingrevision이낡으므로다음pose/damage CAS충돌. 다음은prepared메모리배정+DBcommit+bindingrevision동기화연결,실제인물Corps복원과0420권한/0439송신. 멱등request처리도남음. 운영미접촉,배포/실전입력0,전체목표ACTIVE.

## E187 — v211 DB배정과registry제어/저장버전동기화

영수증 work/20260904-warp-state-reverse/evidence/fleet-control-commit-v211.md. CommitFleetControlAssignmentsAsync 추가. grid lease하에전체대상/binding/세대/부대/datasource확인,메모리commit준비→DB전체트랜잭션→binding revision 및제어자/AI일괄적용. 저장실패메모리불변. 아직요청권한/0439송신은수행하지않는신뢰된하위경로.

실제DB/registry배정→즉시피해저장시새버전연속성확인. 뒤쪽DB버전외부변경후전체배정거부·앞snapshot및DB모드불변.1RED→관련11PASS/0FAIL/0SKIP. TRX E:/logh7-build/test-results/fleet-commit-v211/fleet-commit-v211-green.trx. PG30648정상정지,schemas보존.

다음:원본0420권한검사/실제인물·부대·Corps resolver/0439송신과human복원. 저장commit응답유실멱등성,lock-free관측원자성미완료. 운영미접촉·배포/실전입력0,전체목표ACTIVE.

## E188 — v212 지휘권에 앞서 실제 플레이어 부대소속 필요

영수증 work/20260904-warp-state-reverse/evidence/player-outfit-membership-gap-v212.md. 현 CurrentPlayerInformationUnit은Outfit생략→0,original_grid_unit에도outfit없고canonical인물/부대관계테이블없음. 창고ACL을부대소속으로전용금지. CreateOutfit/재편성의실제소속결과연결이0420동일부대검증의선행조건이다.

v105재확인:0903의004C5650→004C31F0은부대캐시만갱신,기함소속갱신증거없음. 새수신확인:0325→005266E0은ret,단일행이면004C2C80 mode1(보조world+80E8C)갱신이므로단일0325송신만으로active소속변경완료라판정금지. 전체scene재구성의실제InformationUnit.Outfit+032B동시투영을연결해야한다.

다음:canonical부대/인물·기함소속저장과실제생성·편성결과,그후0420동일부대/범위/재배정조건. 같은진영전체유닛권한부여·창고ACL전용금지. 이번정적조사만,새테스트/배포/실전입력0,전체목표ACTIVE(전체blocked아님).

## E189 — v213 canonical 부대·인물소속DB기반

영수증 work/20260904-warp-state-reverse/evidence/outfit-membership-schema-v213.md.0029 original_outfit(032B메타/훈련10bytes/revision)와 original_outfit_member(인물당1소속/revision/실존인물진영 및부대Power+Camp FK)추가. 기존플레이어자동소속/창고ACL전용/유닛제어변경없음. 초기훈련0은복원설계기본값이지원본수치아님.

실제DB존재/진영/Camp제약및새연결유지1RED→관련4PASS/0FAIL/0SKIP. TRX E:/logh7-build/test-results/outfit-membership-v213/outfit-membership-v213-green.trx. PG18204정상정지,schemas보존. 일반unit.OutfitId와canonical부대FK연결/카탈로그동기화,인물삭제·진영변경소속처리,실제부대생성/편성효과/소속API/기함Outfit투영은미완료. 운영미접촉·배포/실전입력0,전체목표ACTIVE.

## E190 — v214 저장된 플레이어 소속을 기함 장면에 연결

영수증 work/20260904-warp-state-reverse/evidence/outfit-scene-projection-v214.md. 계정 소유 인물의 canonical 소속 조회를 구현하고, 0F02 장면 갱신에서 기함 InformationUnit.Outfit, participant snapshot, 032B 부대 정보를 함께 투영한다. 소속 없으면 0 유지. Power 불일치 및 미지원 nonzero Camp는 거부하고, 동일 부대 ID의 상충 메타데이터도 거부한다. 부대 소속은 지휘권 자체가 아니며 자동 권한 부여는 없다.

실제 격리 PostgreSQL에서 RED(null membership) → 집중 1 PASS → 전체 727 PASS/0 FAIL/0 SKIP. TRX E:/logh7-build/test-results/outfit-scene-v214/outfit-scene-v214-full.trx. PID4740 소유 경로 확인 후 정상 정지, schema 보존. 운영 데이터·기존 기함 함선 수 변경 없음, 배포/실전 입력 0.

다음: 실제 생성/재편성 소속 변경, 일반 유닛과 canonical 부대 연결, 0420 권한·범위·정지 조건 및 실제 인물 Corps 복원/0439 송신. 소속 갱신은 현재 scene import 시점만이다. 실제 클라이언트 지휘·함대전은 미검증, 전체 목표 ACTIVE.

## E191 — v215 배정된 실제 지휘관 상태 저장·휘하 유닛 복원

영수증 work/20260904-warp-state-reverse/evidence/fleet-controller-restore-v215.md. 0030 original_tactical_corps 추가. registry 배정 시 실제 Corps를 유닛 제어자/AI 모드와 같은 DB 트랜잭션에 저장한다. scene 복원은 실제 인물/Power/Corps를 조회하여 Ship.Character와 Corps.Id에 연결하고 human 모드는 자동 이동·발사를 정지한다. 기함 자신의033F도 저장된 같은 지휘관 상태를 사용하도록 연결했다. 기함/일반 유닛 ID나 함선 수를 합치지 않는다.

실제 DB 배정→새 연결/registry→0F02 복원, 배정 실패 시 Corps rollback, human actor 이동·발사 없음 검증. 복원 불가 RED와 기함033F 기본값20/저장값7 불일치 RED 후 전체727 PASS/0 FAIL/0 SKIP. TRX E:/logh7-build/test-results/fleet-controller-v215/fleet-controller-v215-full.trx. PG49140 소유 확인 후 정상 정지, schema 보존. 운영/배포/실전 입력0, 전체 목표 ACTIVE.

**다음 우선순위:** 현재 저장은 배정 시점 snapshot이다. 이후040C 배분 변경의 저장·휘하 유닛 전파 및 기함 세대/격침·교체 시 snapshot 무효화가 없으므로 배포 전에 연결해야 한다. character별 공유 상태의 동시성/CAS도 필요. 그 후0420 권한·범위·정지/명령 조건/0439 및 실제 클라이언트 검증. 이번 결과를 전체 Corps 영속성·플레이 가능 판정으로 승격 금지.

## E192 — v216 저장된 지휘 상태의 기함 세대 경계

영수증 work/20260904-warp-state-reverse/evidence/controller-incarnation-v216.md.0031은 Corps snapshot에 controller_unit_id/ship_generation 출처를 추가한다. 기존 행은 추정 backfill 없이 null 유지. 조회는 현재 기함 ID·세대와 일치하는 행만 허용한다. durable 배정은 명시한 지휘관 기함 ID/세대를 DB 행 잠금으로 확인한 뒤 휘하 유닛 및 snapshot을 같은 트랜잭션에 기록한다. 옛 세대 재저장은 false, 유닛 변경 없음.

실제 격리DB 세대 변경→옛 조회 거부/옛 배정 거부→새 세대 명시 배정 복원 검증. RED 후 전체727 PASS/0 FAIL/0 SKIP. TRX E:/logh7-build/test-results/controller-life-v216/controller-life-v216-full.trx. PG60484 소유 경로 확인 후 정상 정지, schema 보존. 운영/배포/실전 입력0, 전체 목표 ACTIVE.

다음:040C 변경 저장·공유 유닛 전파, 같은 세대의 snapshot revision/CAS, 격침·교체 후 휘하 배정 정리/유효 새 상태 마련. 현재 오래된 지휘관 snapshot이 없어지면 휘하 scene 복원은 거부되며 자동 AI 전환을 만들지 않았다. 이 저장 경계만으로 격침·귀환·재출격 전체를 완료 판정 금지.0420과 native 검증도 남음.

## E193 — v217 출력 배분040C 저장·휘하 유닛 전파

영수증 work/20260904-warp-state-reverse/evidence/power-distribution-writeback-v217.md. SavePlayerCorpsAsync는 계정 소유/기함 ID·세대를 행 잠금으로 확인하고 같은 세대의 이전 JSONB 상태 비교 후 저장한다.040C는 저장 성공 전에 메모리나 성공 응답을 내지 않고, 성공 후 같은 grid·지휘관의 휘하 snapshot에 Corps를 전파한다. 기존 human/AI 모드는 유지한다.

실제 격리DB 및 session040C→휘하 beam7→10→새 registry/scene10 복원 검증. 다른 계정 거부·메모리 유지, 오래된 before-state 저장 거부, human 자동 이동/발사 정지 유지. RED 후 전체727 PASS/0 FAIL/0 SKIP. TRX E:/logh7-build/test-results/power-write-v217/power-write-v217-full.trx. PG19452 소유 확인 후 정상 정지, schema 보존. 운영/배포/실전 입력0, 전체 목표 ACTIVE.

다음:격침/교체 후 휘하 배정·새 지휘 상태 복구,0420 권한/범위/정지·명령 조건 및0439. 현재040C는 contents CAS이며 revision/request 멱등성이 아니다. 배정 snapshot upsert에는 별도 stale-source 방어가 더 필요하고, 외부 DB 변경 충돌 후 registry 캐시 재동기화 정책도 미완료. 모든 관전자에 즉시 push된다는 native 증거 없음. 전체 플레이 가능 판정 금지.

## E194 — v218 격침 후 지휘권은 클라이언트가 자동 복구해 주지 않는다

영수증 work/20260904-warp-state-reverse/evidence/controller-loss-static-v218.md 및 동명JSON. Ghidra fresh004B2740/004C94E0은 해당 유닛 격침 상태/활성 표지를 처리하며, 조사한 몸체에 휘하 지휘권 재배정은 없다.004B5C50 setter xref는 생성004C46A0의 두 호출과0439 경로004C7360 한 호출. 다른 직접 메모리 쓰기까지 전부 배제한 것은 아니다.

0439→004C09E0은 source/destination UNIT이 모두 조회돼야 지휘관을 복사한다. 사라진 기함을 donor로 보내는 방식은 무효다. 매뉴얼은 진입 시 온라인/계급/평가/공적 배분과 부상 귀환을 명시하지만 격침 직후 재배분 우선순위는 이 절에서 확인되지 않았다. 당시 offline AI도 미구현으로 표기되어 있다.

다음: 서버 injury-return/휘하 controller lifecycle을 구현하고 valid donor 또는 검증된 scene rebuild로 클라 반영. 임시 post-loss AI/승계는 복원 설계로 명시해야 한다. 유닛/함선 수/피해/위치 보존, 실제 지휘관 이탈과 데이터 손상 구분, 저장 후 공개가 필요. 옛 Corps를 새 기함 세대로 추정 승격 금지. 이번 정적 증거 추가만이며 새 테스트/배포/실전 입력0. 마지막 전체 테스트는v217의727 PASS, 전체 목표 ACTIVE.

## E195 — v219 지휘관 세대 상실 시 임시 AI로 scene 복구

영수증 work/20260904-warp-state-reverse/evidence/orphan-controller-ai-v219.md. 승인된 임시AI 범위의 NEW DESIGN이며 원본 승계 규칙으로 주장하지 않는다.0032는 휘하 row별 배정 지휘관의 기함 ID/세대를 저장한다. 현재 기함 ID/세대 불일치 또는 부상 기록 확인 시 제어만 해제해 기존 catalog AI로 돌린다. 출처 불명·인물 누락은 해제 근거로 쓰지 않는다. 함선 수/피해/위치 보존, DB CAS 후 runtime/binding 갱신.

격리DB에서 세대 변경→fresh scene 및 기존 registry scene refresh→AI move/fire와 후속 저장 검증. RED 후 전체728 PASS/0 FAIL/0 SKIP. TRX E:/logh7-build/test-results/orphan-ai-v219/orphan-ai-v219-full.trx. PG51172 소유 경로 확인 후 정상 정지, schema 보존. 운영/배포/실전 입력0, 전체 목표 ACTIVE.

현재 실행점은0F02이며 즉시 격침/부상/AI tick 통합은 아님. 실제 return/recovery 연쇄 및 injury flag 분기는 별도 검증 필요. 다음은 관전자 없이도 loss 후 AI 복구, 부상 지휘관에 대한 배정/040C 저장 거부, 전체 클라이언트 표시 검증이다. 유닛별 commit이므로 fleet 전체 원자성 아님. 사라진 donor0439 전송 없음. 플레이 가능 완료 판정 금지.

## E196 — v220 장면 재요청 없이 AI 진행에서 지휘관 상실 복구

영수증 work/20260904-warp-state-reverse/evidence/orphan-controller-tick-v220.md. binding에 원래 catalog fallback을 보존하고 controlled row 복원 시 명시적으로 제공한다. AdvanceNpcsAsync가 grid lease 아래에서 지휘관 상태를 확인·저장 후 제어를 해제하고 기존 AI 진행을 수행한다. 새0F02에 의존하지 않는다.

격리DB 세대 변경 후 추가 scene request 없이 AI tick만 호출하는 세 번째 변형에서 RED(controller1 유지)→복구/이동·발사/후속 저장 확인. 전체729 PASS/0 FAIL/0 SKIP. TRX E:/logh7-build/test-results/orphan-tick-v220/orphan-tick-v220-full.trx. PG17204 소유 확인 후 정상 정지, schema 보존. 운영/배포/실전 입력0, 전체 목표 ACTIVE.

주의: 현재 controlled unit마다 매 AI 호출에서 DB transaction/행 잠금을 수행한다. 대함대 성능 미측정이며 지휘관별 조회 통합/이탈 이벤트 방식 검토 필요. dedicated native 제어 변경 notification 미연결, 실제 부상·귀환·교체 연쇄 및 injured actor 배정/040C 거부도 미검증. 마지막 운영 패키지와 장기 미배포 source 간 차이를 확인한 뒤 실제 클라이언트 검증으로 합류해야 한다.

## E197 — v221 실제 게스트는v42, 최신v47은 로컬 빌드만

영수증 work/20260904-warp-state-reverse/evidence/runtime-readonly-v221.json 및 runtime-rollout-gap-v221.md.06:52:41Z 게스트 server4824(v42-v173 경로)/client9884 실행·상호TCP연결 확인. 서버DLL8C6A0B8D…50A369, client AEF38276…660F2F. 시작시각 각각02:58:58Z/02:59:04Z. 별도47900 proxy2952 보존. 화면·현재모드는 관찰하지 않았다.

Release win-x64 self-contained v47을 E:/logh7-build/server-v47-fleet-control-v221 에 새로 publish 성공. DLL E2C1AB5AD3504937D4113157313B0B84082C5309BBA096FA68D30E15C288ED38. 기존v46 대비0026~0032 추가, 구 migration 변경 없음. 게스트 staging/배포/재시작/게임입력/운영DB변경0.

**다음 실제 플레이 차단점:** 배포 catalog grid0/102 모두 fleets=0이다. 테스트의 다함대 설정은 배포 데이터에 들어있지 않다. 서버 교체만으로 함대전 구현 확인 불가. 명시적 임시 플레이 시나리오 catalog와 실제 loader설정, 운영DB migration preflight, 클라이언트 관찰을 한 흐름으로 묶어야 한다. v47 실행 검증도 아직0, 마지막 전체테스트는v220729PASS. 전체 목표 ACTIVE.

## E198 — v222 실제 배포 함대전 JSON·선택 경로와v48

영수증 work/20260904-warp-state-reverse/evidence/deployable-fleet-scenario-v222.md. server/battlefields/fleet-skirmish.json 추가: grid101에 power2/3 각각2개300척 전투유닛, 기존 기함·기지·후방102 보존. 기본 catalog 변경 없음. LOGH7_BATTLEFIELD_CATALOG에 서버 환경의 절대 경로를 지정하면 선택하며, 미설정은기존 catalog, 잘못된 지정은 오류. 첫 사용 시 lazy 캐시이므로 변경 후 프로세스 재시작 필요.

실제 packaged JSON에서4유닛 import 및 양방향fire, 선택 loader/오류 처리를 RED→GREEN 검증. 전체731 PASS/0 FAIL/0 SKIP. TRX E:/logh7-build/test-results/deploy-fleets-v222/deploy-fleets-v222-full.trx. PG49860 소유 확인 후 정상 정지, schema 보존.

새 로컬 publish E:/logh7-build/server-v48-fleet-scenario-v222. DLL D37ED84F207A49955A1630EFCF60BD3506847DA9434BEE841E0959D0A5E655F5, scenario B7D431FBCFDB8E28B1CC2496A2D1DFDC91A811BB01567F40553EEFA4A8294C60. 아직 guest staging/배포/재시작/운영DB/게임입력0. 다음은실제DB migration preflight→v48 guest경로설정 포함교체→원본 클라이언트 관찰. 전체 목표 ACTIVE.

## E199 — v223 운영DB 읽기 전용 사전 점검 통과

영수증 work/20260904-warp-state-reverse/evidence/db-preflight-v223b.json 및 operational-db-preflight-v223.md.07:04:04Z transaction_read_only=on 확인. 적용25개 migration hash가v48과일치, playerunit ID중복0, 피해기본수량100 제약위반0, 새scenario6개 ID충돌0. 신규fleet/outfit/corps 테이블없음. unit2는grid102/base2/gen8/damage0/destroyed0/injuredfalse.

PG1812 exe/start/data/listener현재확인. 첫진단은postmaster 경로슬래시차이로SQL전실패했고별도identity조회후GetFullPath정규화한v223b만새로실행했다. 비밀값출력/이동없음. DB변경·마이그레이션적용·게임재시작·입력0. 이결과는captured preflight이지마이그레이션실행/백업/native PASS아님.

다음:게스트disk/runtime재확인→v48 stage→실제게스트scenario절대경로설정포함교체→자동로그인/실제클라관찰. PostgreSQL과proxy2952보존,소비한이전입력재실행금지. 마지막fulltest v222731PASS, 전체목표ACTIVE.

## E200 — v224 게스트v48 stage 완료, 아직실행은v42

영수증 work/20260904-warp-state-reverse/evidence/server-stage-v224b.json, stage-preflight-v224b.json, guest-stage-v48-v224.md. 게스트 기존run/server-v48-v224 에새폴더로준비완료. DLL D37ED84F…655F5, scenario B7D431FB…294C60, migrations32 확인. 기존 server4824/client9884 경로·hash·Get-Process 정밀시각·listeners현재확인, C여유14.93GB.

첫신원검사CIM/Get-Process시각정밀도차이를v173원실행영수증과대조해수정. archiveCopy exec98491 완료전검증시파일잠금실패; 같은copyhandle exit0확인후새v224b stage에서absent destination 추출성공. 실패영수증덮어쓰기/완료copy재실행없음.

현재 V48_STAGED_NOT_RUNNING. 서버/클라중지·시작·DB변경·게임입력0. 다음은DB백업 및app-data보존digest→신원재검증→scenario절대guest경로환경변수를포함한교체. 새migration으로변하는schema전체hash와보존할게임데이터hash를구분할것. PG1812/proxy2952/구패키지보존. 전체목표ACTIVE.

## E201 — v225 운영DB백업 및 기존24테이블 보존해시

영수증 work/20260904-warp-state-reverse/evidence/backup-v225.json 및 database-backup-v225.md. 게스트기존run/before-v48-v225.dump 생성:88642bytes, SHA4DC2614F…3B24DF. pg_dump와pg_restore --list 모두exit0,139TOC항목. 실제restore시험은하지않았다. dump본문/자격증명host복사없음.

기존public24테이블(schema_migration제외,신규unit_number필드제외)의정렬JSONB행해시가백업전후동일:2a8697ad34c161636a58319408402df5f08cf654973aa0438d1db5e42b52c4c4. 게스트run/preserved-data-v225.sql SHA527EB156F8B33D9B59BDD60E0C66B334FFE1F0AC86B94623329A2A7C3C59AA60. 다음교체전후같은고정쿼리로기존행보존확인하고32migration은별도검사할것. 이digest는sequence/schema/새테이블까지포함한전체DB해시가아니다.

PG1812신원검사·readonly사용. 서버/클라재시작·DB변경·게임입력0. 다음은신규swap스크립트로v48실행및scenario절대guest경로선택,PG/proxy/구패키지보존. 현재v48은stage만완료. 전체목표ACTIVE.

## E202 — v226 v48 실제 실행·32migration·클라이언트 재실행

영수증 work/20260904-warp-state-reverse/evidence/v48-runtime-swap-v226.md 및 swap-stop-v226/swap-start-v226b/client-launch-v226.json. 옛server4824/client9884만신원확인후중지. 첫v48PID6996은25006(readonly)로종료:진단PGOPTIONS상속원인. 종료확인후새v226b에서자식시작전PGOPTIONS해제,검사용psql에만readonly복구하여성공.

현재server4112 start2026-09-08T07:20:08.0597703Z, 기존run/server-v48-v224/Logh7.Server.exe. 두서버listener확인,적용32migration hash일치. 기존24테이블digest2a8697ad…52c4c4전후동일. scenario절대guest경로환경변수설정됨(lazy실제scene로드미검증). PG1812/proxy2952/백업/구패키지보존.

원본client4348 start2026-09-08T07:21:09.6858433Z, HWND0x0000000003F00392, item116기존hash동일. CLIENT_LAUNCHED_NO_INPUT:로그인/화면/함대전아직미검증. wire기존run/server-wire-v226b.jsonl. 다음fresh desktop/auth preflight→새영수증자동로그인→실제scenario원본클라확인. 이번Stop/Start/Client모두CONSUMED재실행금지. 전체목표ACTIVE.

## E203 — v227 로그인/화면 자동화 경계

영수증 work/20260904-warp-state-reverse/evidence/native-auth-observation-v227.md 및 wire-auth-boundary-v227.jsonl. 현재Computer Use 스킬/필수guidance를읽고sky로유일한VMware창407570838선택. 첫list_apps timeout은2초후1회재조회성공. get_window_state는SetIsBorderRequired/0x80004002,창재열거후1회재시도도동일실패. 새화면/좌표없음.

wire복사본은listener-ready1건뿐,login/connect이벤트없음. 현스킬은인증대화상자자동조작및동일turn다른PowerShellUI경로혼용을금지하므로사용자로그인·화면확인요청. 우회입력/이전좌표/소비스크립트재실행금지. 서버측다른진전은가능하며전체goal blocked/complete아님. 이번재시작/입력0. 사용자이어짐후현재신원및post-login화면다시확인.

## E204 — v228 사용자 지적 후 게스트 직접 화면 확인

최신 v243: `work/20260904-warp-state-reverse/evidence/fleet-supplies-persistence-v243.md`. migration0033 함대 Supplies 저장/재조회/CAS/장면복원 연결 구현. default100은 기존 임시표시 보존, 원본재고 주장 아님. 2RED→focused8PASS→전체738PASS/0FAIL/0SKIP. 테스트PG62176 검증후정상종료, 자료보존. 미배포; 운영v48/피해상태 그대로. 실제수리/소비/권한/원본UI 연결은 남음.

최신 v242: `work/20260904-warp-state-reverse/evidence/repair-state-refresh-v242.md/json`. 0325 batch가 활성전술 피해 갱신 보장 못함 확인. 별도042D NotifyRepairFleet body14(maneuver_unit,target,maneuver_supplies u32,target_damage u16)와004C15A0 정상수/물자 갱신 회수. 원본은 maneuver_unit을 두번조회하는 특이점, fullrepair61과 동일명령 아님. Fleet DB Supplies 필드 부재도 확인; 단순damage reset 구현 금지. 운영 변화 없음.

최신 v241: `work/20260904-warp-state-reverse/evidence/repair-ack-cache-boundary-v241.md/json`. 0C00 초기 소비는 결과버퍼 복사→메시지 표시, 공통 본인 기록은 type/+4/+8만 저장. 여기에는 per-unit 피해 갱신 루프 없음(간접소비 전면배제는 아님). 결과응답만으로 실수리 완료 처리 금지; 권위상태+정확한 유닛 갱신 경로 필요. 운영 변경 없음.

최신 v240: `work/20260904-warp-state-reverse/evidence/repair-request-result-direction-v240.md/json`. 중요 정정: 원본004B4D40 송신은 actor/count/unit ID만 채우며 result_damaged/supplies는 초기화하지 않음. v238 ‘클라 계산 결과’ 추정 철회, 요청값 검증 전제에도 쓰면 안 됨. 수신도0C00, binary reader00554F80/dispatcher004BCDA2→world43D254→004BFCD0 확인. 수리 결과 소비·물자식은 추가추적 필요. 코덱 형식 불변, 주석 정정만.

최신 v239: `work/20260904-warp-state-reverse/evidence/completeness-repair-codec-v239.md`. strict0C00 코덱 구현, 새7+인접7=14PASS/0FAIL/0SKIP(새테스트5RED 선확인). 잘림/초과70/후행byte/잘못된type 거부. 실제 수리·물자·권한·응답 처리 및 native61은 아직 미구현/미검증, 메뉴/dispatcher 미연결. 운영v48 변경 없음.

최신 v238: `work/20260904-warp-state-reverse/evidence/completeness-repair-wire-v238.md/json`. 완전수리 별도0C00 등록/직렬화/필드명 회수: body17+12N,N<=70,time/id/pcp/mcp 및 unit/result_damaged/result_supplies. 원장61에 정적 회수만 반영. 다음 strict codec+tests, 후속 응답/권한·물자계산 검증. UI61 송신·실수리 미검증, 미노출. EXE/운영DB 변경 없음.

최신 v237: `work/20260904-warp-state-reverse/evidence/repair-replenishment-boundary-v237.md`. command60 원장의 낡은 v126 실패 상태를 v233 단일유닛 출항 효과 검증으로 정정(JSON 검증). 매뉴얼에서 수리/격침분 보충·동일함종 창고재고·승무원 구분 확인. 수리61 wire 미회수,00571870 일반 대상수집의 null 참조 경계를 listing으로 재확인했지만 repair61 경로와 동일성은 미입증. 게임/DB 변경 없음.

최신 v236: `work/20260904-warp-state-reverse/evidence/render-observation-boundary-v236.md`. 다음 전투용 guest-render-timeline-v236에 유닛 modelFile/좌표와 렌더러 LOD/모델 포인터/노드 수 추가. 현재 프로세스 1초 검증은 scene2/ships0/renderers0으로 전투 후 정리 상태 확인. 활성 모델 경로 및 카메라/픽셀은 아직 검증 안 됨. 현 전투 재생/데이터 복원 없음.

최신 v235: `work/20260904-warp-state-reverse/evidence/fleet-persistence-and-assets-v235.md`. 실제 전투 DB 영속성 확인: kind56 2유닛 각 손상275/격침0, kind119 2유닛 각300/300. 모델 EM/EH/EL018 및 FM/FH/FL003 6파일 모두 설치 경로에 존재·원본 해시 일치. 재귀열거의 missing은 data 정션 미추적 오판 후보였으며 exact-path로 반박. 렌더 로딩/카메라/픽셀 확인은 남음; 파일교체·전투재실행·DB복원 없음.

최신 v234: `work/20260904-warp-state-reverse/evidence/native-fleet-battle-v234.md`. 실제 warp→grid101/base0/mode6/cruising9, 전술 HUD 표시, 양측 2유닛씩 NPC 상호사격과 클라 normal300→50 반영 확인. 서버 두 적 유닛 destroy300 기록 후 전략 화면으로 자동 복귀. 플레이어 loss0, 사격 입력 미실행. NPC 모델/발사효과 가시성·전투 종결/영속성 상세는 추가검증. 소비된 전투 재실행/격침 유닛 암묵적 재생성 금지.

최신 v233: `work/20260904-warp-state-reverse/evidence/native-undock-v233.md`. 원본 출항 입력 0x0B06 Success/authority77, DB mode4→5 및 화면 우주항→행성·요새 궤도상 확인. **현재 grid102/base2/mode5/gen8/loss0/cruising10**. 워프·전술 진입 전 현재 incarnation 관찰/생존 guard 준비 필요. 직무카드 설명 및 확인창 자리표시자는 미해결.

최신 v232: `work/20260904-warp-state-reverse/evidence/native-world-entry-v232.md`. 승인된 테스트 계정 자동로그인 1회 성공 → 로비 → 기존 캐릭터 → 전략 HUD/임시 귀환 행성 우주항을 화면과 0x0F02 Success로 검증. **현재 로그인 화면이 아니라 전략 화면**. 추가 클릭 없이 전환 완료 재관찰. 전술 함대전은 이번 incarnation에서 아직 미검증. v232 입력 스크립트 모두 소비됨.

최신 v231: `work/20260904-warp-state-reverse/evidence/native-login-render-v231.md`. AttachThreadInput으로 전경 소유권 확인 후 파일 메뉴 단일 클릭 → 로그인 화면 표시, 별도 배경 단일 클릭 → 메뉴 닫힘을 캡처 검증했다. **현재 흰 화면이 아니라 로그인 화면이며 인증 입력은 아직 없다.** 소비한 메뉴 복구 스크립트 재실행 금지.

후속 v229/v230 영수증: `work/20260904-warp-state-reverse/evidence/native-menu-boundary-v230.md`. 창/메뉴 읽기와 새 화면 캡처 완료. 메뉴 클릭은 입력 전 foreground 검사에서 진행되지 않았고, 별도 1회 포커스 시도 후에도 클릭 영수증 없음. 정확한 다음 조사 경계는 해당 영수증 참고. 로그인·전투 진입 미확인.

사용자 “너가 볼 수 있잖아” 지적 반영. host sky 실패를 전체관찰불가로 취급한 것은 잘못. native-screen-v228.png는VMware black이지만 guest-window-observe-v228.ps1을client-launch-v226에묶어읽기전용capture하여 window-current-v228.png 확보. Windows바탕화면/게임제목·메뉴/흰내부영역이보인다. 로그인dialog는관찰되지않았으며render장애로단정도금지. 이번focus/click/credential/restart0. 화면요청을사용자에게불필요하게넘기지말것.

동시에진행중이던서버수정:LockControllerAsync에injury_return_id IS NULL 추가. 부상지휘관출력/배정저장거부,부상기록에따른해제시비제어필드보존을격리DB에서3RED→3PASS검증. TRX E:/logh7-build/test-results/injured-controller-v228/injured-controller-v228-green.trx. PG30828확인후정상정지. 소스미배포,게스트v48변경없음. 전체목표ACTIVE.

## E205 - v244: manual full-repair rule and atomic ordinary-unit persistence

영수증: `work/20260904-warp-state-reverse/evidence/full-repair-atomic-storage-v244.md`.
매뉴얼 PDF68/71 표 전체 확인: 완전수리160CP, 대기0/소요0, 부대 보유 군수물자 전량소비, 기함 및 모든 함정유닛 수리. 함당 RepairCost 식과 구분한다.
`PostgresFleetUnitStore.Repair.cs`에 일반함선 다중수리 원자 저장 구현: DB의 destroyed를 damaged 결과로 유지하고 supplies0, 신원/세대/revision 충돌 시 전체 롤백. 격침 부활/위치/지휘권 덮어쓰기 없음. 7RED→7PASS, 전체745PASS/0FAIL/0SKIP.
**명령61/0C00 자체는 아직 미연결**: 전체부대 검증·권한·기함 물자 영속성·PCP/MCP 잔액/160차감·동일 트랜잭션·클라이언트 갱신이 남음. 현 primitive를 바로 handler에 연결하지 말 것. 다음은 포인트 및 기함 상태를 포함한 명령 전체 처리. 운영v48/클라이언트/DB 재시작·수리·재생성하지 않았음. 테스트 TRX는 E:/logh7-build/test-results/repair-v244/.

## E206 - v245: PCP/MCP wire fields and recovery/substitution requirements

영수증 `work/20260904-warp-state-reverse/evidence/command-points-wire-v245.md/json`. 원본00419300/00417390의0323 expanded+50/+54 PCP/MCP와 context+74/+78, HUD0058D140을 확인했다. EncodeCharacter의 6개0 묶음에서 포인트를 분리해 서버값 직렬화 매개변수 추가(기존 기본0 유지). 새3RED→3PASS, 인접11PASS, 전체748PASS/0FAIL/0SKIP. 아직 잔액 저장/호출부 연결/실제 포인트 표시·소모는 미검증.
매뉴얼 PDF27(인쇄26) 직접 확인: PCP는政略/MCP는軍事, 부족 시 다른 풀2배 대납; 2게임시간/실제5분마다 오프라인 포함 회복, 전술 중 회복없고 종료 후 시간집계 시작. 부족분 대납 산식·회복량/상한은 미확정. 잔액·기함·수리의 전체 트랜잭션으로 이어갈 것. 0323 first-response upsert는 mode1 임시문맥이므로 주HUD 즉시갱신으로 단정금지. 운영미배포/게임입력0, 목표ACTIVE.

## E207 - v246: persistent PCP/MCP to restored encrypted character response

영수증 `work/20260904-warp-state-reverse/evidence/command-points-persistence-v246.md`.
0034 마이그레이션으로 character PCP/MCP(u32범위bigint, 기존투영보존0) 추가. DB계정소유 조회→캐릭터 복원→암호화0323 실제응답 연결. 적캐릭터에 내잔액 전파하지않음. 실제격리PG 재연결+새세션2회321/uint.MaxValue 유지검증, 새RED1+기존확장RED1 후 집중10PASS/전체749PASS(FAIL/SKIP0).
소스미배포, 운영잔액지급/재시작0. 차감/회복/원자수리·native갱신 미완료. 캐시된캐릭터는Restore가earlyreturn하므로 향후잔액변경 commit후세션갱신 필수; 캐릭터생성idempotent-replay의잔액refresh도 남음. 지금의0잔액으로 기존명령을차감형으로전환하지말고 초기지급/회복규칙을먼저연결할것. 목표ACTIVE.

## E208 - v247: explicit self-info query refreshes current balances

영수증 `work/20260904-warp-state-reverse/evidence/command-points-refresh-v247.md`. 같은세션에서 DB변경후0322 요청이낡은321을반환하는버그 RED→수정. 명시적self조회시계정소유row에서잔액갱신, 소유row없으면캐시반환거부. 실제격리DB321→161, MCP max→23을동일세션및다음재연결의암호화응답에서검증. 첫전체검사의테스트설정2실패수정후750PASS/FAIL0/SKIP0. 운영미배포/입력0. 차감·회복·수리전체transaction·nativeHUD/peer push는여전히남음. 생성replay초기bootstrap의캐시복원도별도경계이며다음self조회에서만이수정적용. 목표ACTIVE.

## E209 - v248: CP substitution must be separated from growth usage

영수증 `work/20260904-warp-state-reverse/evidence/command-point-policy-boundary-v248.md/json`. PDF15(인쇄14) 직접확인: 대납으로소모한CP는능력성장용사용량에누적하지않음. 직접소모와대납소모를향후event에서분리할것. 사용량threshold/경험치선택식은미확정. 원본송신004B78A0/수리caller 및actoroverride globals조사에서대납산식미회수, 일본어웹검색에서도수치근거못찾음.
임시정책 제안은 PCP/MCP각1600초기/상한, 실제5분마다각160회복(전술중제외), 부족분만2배대납. **아직승인·적용안됨**, 다음사용자응답확인할것. 원본데이터로표시하지말것. 운영변경0/전체목표ACTIVE; 미승인일때기함저장등다른안전한작업은가능.

## E210 - v249: flagship supplies survive persistence and project into scene

영수증 `work/20260904-warp-state-reverse/evidence/flagship-supplies-persistence-v249.md`. 기함물자를매번100으로투영하던경로수정. 0035 original_grid_unit.supplies 기본100(기존authored값보존), 공용reader사용6조회연결, CurrentPlayerInformationUnit에서저장량사용. 실제격리DB물자23을피해동시갱신/rollback/격침/reopen중보존, 암호화0F02응답0325의0/23/u32max 검증(각RED선확인). migration옛필드보존테스트2개새열반영후전체753PASS/FAIL0/SKIP0. 운영미배포/게임입력0, 임시CP정책여전히미승인.
다음: 비기본물자값의워프replay/current-row roll-forward 검증(과거move결과객체에는물자snapshot없어기본100이므로현재권위값으로쓰지말것), 전체수리transaction/권한/native연결. 새기함복구시물자정책도별도확정필요. 목표ACTIVE.

## 2026-09-12 재개 주의: 위 v249 이후 구현이 이미 진행됨

**2026-09-13 최신 배포: [2026-09-13-controller-profile-deployment.md](2026-09-13-controller-profile-deployment.md).** 지휘권 변경 및 오프라인 지휘관 정보 복원 수정 배포, 서버 PID7732/스키마44. 새 백업 실제 복원·후보 서버 기동·운영 교체 전후 공적 포함 인물/유닛/함대 보존 확인. 전체1014 테스트 통과. 게임은 PID5968 그대로이며, 배포 후 캡처는 로그인 화면이다. 네이티브 전술 검증과 최고사령관/임무 AI 등은 미완료.

**최신 배포는 [2026-09-12-mission-merit-deployment.md](2026-09-12-mission-merit-deployment.md)**. 실제 VM 서버 PID2080/스키마44, 신규 백업의 별도 DB 복원과 43→44 업그레이드 검증 후 배포. 기존 인물·유닛·함대 데이터 보존 확인, 전체1012 테스트 통과. 아래 이전 배포/미배포 표시는 과거 시점이다. 최고사령관 선출·임무 AI/보상·네이티브 플레이 검증은 미완료. 검은 화면은 화면 절전 타이머 초기화로 복구했으며 마지막 시각 확인은 게임 로그인 화면이다.

최신 배포 시작점: [2026-09-12-service-observer-deployment.md](2026-09-12-service-observer-deployment.md). 수리·보급 거리/수행함 잠금/NPC 잠금/시간 응답/관전자 갱신 수정 배포, 전체962 테스트 통과, VM 서버 교체 전후 유닛·함대 보존 확인. 완료·취소 작업 연결과 실제 클라이언트 검증은 미완료. 아래928/배포없음 기록은 이전 시점의 이력이다.

최신 시작점은 `2026-09-09-command-points-and-live-hostile-fleet.md`와 `2026-09-12-current-state-reconciliation-and-warp-supplies.md`. 과거의CP미승인/수리미구현 메모로현재코드를되돌리지말것. 현재마이그레이션0043/전술35타입등록, 실제격리PG포함전체928PASS/FAIL0/SKIP0 재검증. 워프+replay 뒤현재물자23유지/320MCP중복차감없음 보강검증. 이번guest관측·입력·배포없음. 기존PID/HWND는재검증필수. 전체플레이완료를의미하지않으며목표ACTIVE.

## 2026-09-13 NPC 무기 선택 수정 배포 — 위 배포 기록을 대체하는 최신 상태

**추가 최신 배포:** [무접속 NPC 핸드오프](2026-09-13-headless-npc-bootstrap-gap.md) 최상단 LIVE 절. 서버PID2964/DLL8C9FFB.../스키마44. 무접속 함대 복원·재시작 이동 보존·NPC 독립 프로필 및 양방향 창고 primitive 반영, 전체1033PASS. 새백업 실제복원/후보기동/운영교체/독립포트점검 완료. 기존12함대·인물·기함보존,카탈로그 후방지원함2추가. 게임5968미접속,네이티브전투와전략전체미완료. 아래1260은이전상태. 전략 작업은 [현재 감사](2026-09-13-strategy-command-current-audit.md)에서 계속.

**다음 플레이 기능 결손:** [2026-09-13-headless-npc-bootstrap-gap.md](2026-09-13-headless-npc-bootstrap-gap.md). 자동 틱은 서버 시작 시 실행되지만 NPC 등록은 플레이어 장면 진입에 묶여 있다. 무접속 재시작 후 NPCvsNPC 진행을 위해 계정과 독립된 영속 함대 부트스트랩 필요. 기존 직접 RegisterNpc 테스트로 이 경계를 통과했다고 간주하지 말 것.

**추가 최신 운영: 같은 [사거리 핸드오프](2026-09-13-npc-sparse-range-positioning.md)의 overlap 배포.** 거리0 이격 수정까지 PID1260/DLL132BB2...에 반영. 전체1028PASS, 새 백업 실제 복원·후보 기동·운영 교체 전후 데이터 보존 확인. 스키마44. 아래5284/미배포 기록은 이전 상태이며 실클라 전투는 아직 미검증.

**최신 배포: [2026-09-13-npc-sparse-range-positioning.md](2026-09-13-npc-sparse-range-positioning.md).** 사거리 이격/접근 수정 운영 반영, 새 서버 PID5284/스키마44. 백업 실제 복원·후보 기동 및 교체 전후 인물/유닛/함대 해시 보존 검증. 전체1019PASS 이후 추가 통합4건 포함 사거리9PASS. 실클라 전투 검증은 미완료. 아래 미배포/PID3796 기록은 이전 상태다.

후속 로컬 수정(미배포): [2026-09-13-npc-sparse-range-positioning.md](2026-09-13-npc-sparse-range-positioning.md). 유효 사거리 중간에 명중불가 구간이 있을 때 NPC가 그 안에 멈추던 문제 수정. 너무 가까우면 유효 구간으로 이격하며, 무효 거리에서 발사하지 않는다. 회귀2건 RED 후 관련52PASS. 전체 테스트·배포·실클라 검증은 다음 단계.

후속 리소스 조사: [2026-09-13-model-launch-marker-gap.md](2026-09-13-model-launch-marker-gap.md). Ship MDX 261개 원본 바이트 조사, 153개에 무기 마커. 현재 무장 kind56의 GE E018에는 마커가 없고 E012/F003에는 존재한다. 005FE1F0 부분 문자열 검색 확인; 004E62B0는 발사점 조회 실패를 무시하므로 마커 부재를 효과 부재로 단정하지 말 것. LOD/런타임 변환 연결 및 실제 발사 위치는 다음 검증 대상. 이번 서버/클라이언트 변경 없음.

**추가 최신 배포: [2026-09-13-npc-fire-commit-retry.md](2026-09-13-npc-fire-commit-retry.md).** NPC 피해 저장 실패 시 재충전 소비 수정, 전체1017 테스트 통과. 새 백업 실제 복원·후보 기동·운영 교체 전후 데이터 보존 검증. 서버 PID3796/스키마44, 게임 PID5968 미접속. 미사일 차감 통합 저장·실클라 검증은 미완료. 아래 PID4760은 이전 배포다.

[2026-09-13-npc-weapon-range-selection.md](2026-09-13-npc-weapon-range-selection.md): 현재 거리별 무기 선택 수정, 전체1017 테스트 통과. 새 백업 실제 복원 및 운영 교체 후 인물·유닛·함대 보존 확인. 서버 PID4760/스키마44, 게임 PID5968 미접속. 기존 바이너리·백업은 보존했다. 실제 클라이언트 전투 및 임무 AI 검증은 미완료.
