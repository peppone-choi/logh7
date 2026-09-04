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
