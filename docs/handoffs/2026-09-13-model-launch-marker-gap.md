# Ship launch-marker gap — 2026-09-13

## Fresh evidence and scope

Previous turn made progress: VMware CLI display wake and fresh capture proved Windows unlocked and G7MTClient at empty LOGIN, not in battle. This unit makes no guest writes, inputs, deployment, or gameplay completion claim.

Using existing read-only Ghidra Unit10Input/g7mtclient.exe, exported 005FE1F0, 004DC940, 004DC8C0 and 004E62B0 to `work/20260904-warp-state-reverse/evidence/model-launch-selector-20260913.txt`. Existing E136 chain is the starting reference; this export closes its compare-helper uncertainty.

- 005FE1F0 is case-sensitive substring search (strstr semantics), not exact equality. Thus BEAM matches BEAM_01; numeric suffixes do not prevent lookup.
- 004DC940 counts matching runtime model names; 004DC8C0 copies the selected object's transform at +0x58 on success, returns false without writing on failure.
- 004E62B0 prepares an actor-derived matrix before the loop. It ignores 004DC8C0's return and still calls 004DC400. Zero matching nodes therefore does **not** establish no visible effect: fallback transform/origin behavior must be measured. Do not patch this based solely on missing strings.

## Actual installed resources versus current source templates

Read-only byte scan of all 261 Ship MDX files found 153 files with uppercase BEAM/GUN/MISSILE printable markers. Reproducible script: `work/20260904-warp-state-reverse/scripts/inventory-ship-launch-markers-20260913.ps1`; per-file path, byte length, SHA256 and raw offsets in `work/20260904-warp-state-reverse/evidence/ship-launch-markers-20260913.json`.

| Current subordinate kind | Model candidate | High/medium raw markers | Low raw markers |
| --- | --- | --- | --- |
| 32, armed | GE E012 | BEAM_01, GUN_01, GUN_02, MISSILE_01 | none |
| 56, armed | GE E018 | none | none |
| 119, armed | FP F003 | BEAM_01, GUN_01, GUN_02, MISSILE_01 | none |
| 139, unarmed picket | FP F014 | BEAM_01, GUN_01, MISSILE_01 | none |
| 57/58, unarmed support | GE E018 | none | none |

Source freshly read: OriginalSubordinateShipCatalog.Templates and its zero-arc overrides. These are authored visual model assignments, not recovered original canonical identities. Marker duplicates in serialized node and mesh names must not be counted as separate emitters. The scanner is deliberately not a runtime node/transform parser. Scan count is not all-resource reverse-engineering completion.

## Next action

### Follow-up: selected LOD is also the emitter source

Fresh read-only exports `model-launch-lod-getter-20260913.txt` and `model-launch-lod-selection-20260913.txt` close the first next action. 004F2AF0 reads renderer+B24: 0 returns B38, 1 returns B34, 2 returns B30, 3 returns B2C, otherwise null. In 004F2920's ship branch (param2==0), 004F3F70(modelIndex,0/1/2) populates B38/B34/B30 respectively. The existing loader's path tables identify these as low/medium/high. Slot3 is a separate request and is not assigned a named LOD here.

004F2B40 changes B24, obtains that selected model, rejects a null candidate by restoring the old selector, detaches the previous model from B3C, and attaches the selected model with 005DF340. Thus 004E62B0 obtains the same selected LOD via 004F2AF0; no independent high-detail emitter lookup is present in this chain. All three selected E012/F003/F014 low files lack uppercase weapon markers in the prior scan. This creates an additional zoom/detail-dependent origin test, not proof effects are absent at low LOD.

Initialization tries selector0 first, then1/2/3/5 only on failure. Later distance/detail policy callers must still be traced before claiming which LOD is used in battle. Scalar searches at `E:/logh7-build/model-b24-20260913.txt` and `model-b30-20260913.txt` supplied candidates; unrelated classes using the same offsets are not renderer evidence. No production mutation or native battle verification in this follow-up.

### Follow-up: distance hysteresis recovered

`model-lod-update-policy-20260913.txt` freshly exports 004F2020/004F2660. References to 004F2B40 (`E:/logh7-build/model-lod-callers-20260913.txt`) include initialization, cleanup, and these two update paths. In 004F2020, for visible B20<2 objects, current low switches toward medium at distance<20 when quality>=1; medium switches toward high at distance<3 when quality>=2, otherwise toward low at distance>25; high switches toward medium at distance>4 when quality<=2, with low fallback if medium unavailable. Quality>2 retains high in that branch. These are hysteresis thresholds, not a single cutoff. B20>=2 and visibility/mode branches have additional slot3/slot5 behavior, so this is not an unconditional rule for all objects.

PE-section-mapped readonly float extraction from the original binary: 0066E188=20, 0066E210=3, 0066E61C=25, 0066E1B0=4, 0066E244=50, 0066E630=5, 0066EA24=40. Helper 005DD880 explicitly sums three squared vector components then FSQRT (`E:/logh7-build/model-lod-distance-helper-20260913.txt`): thresholds apply to vector length, not squared distance. 004F3730 is an indexed quality-value getter; UI setting labels and world-unit scale are not established here. The reference position comes from 004F3140()+48; camera identity should be joined rather than inferred solely from distance-like behavior.

### Follow-up: reference position is frame-managed, not attack-target distance

Fresh exports under `E:/logh7-build/`: `model-lod-reference-20260913.txt`, `model-view-manager-refs-20260913.txt`, `model-view-frame-20260913.txt`, `model-view-init-20260913.txt`, `model-lod-camera-consumers-20260913.txt`.

004F2020 passes ECX=00C6EAC8 to 004F3140. Getter returns manager[+20 + selectedIndex*4], gated by +70 and indexed by +60. Main frame 004E96F0 calls 004F3010 on this manager before FieldMove. 004F3010 interpolates manager +88 toward +7C using frame counters +68/+64, writes the selected object's +48 position, separately interpolates +A0 toward +94 into the +40 table object, then calls 004B0330 with the corresponding base-table and +20-table entries. Thus LOD distance is measured against this frame-interpolated reference object; it is not computed from the shot target or NPC attack distance in this path. Camera/view interpretation is supported by its paired reference objects and rendering consumers, but final camera binding 004B0330 and zoom input writers remain to be joined.

### Follow-up: normal activation versus optional transform override

004B0330 is **not** the normal unconditional camera-binding operation. Fresh `E:/logh7-build/model-camera-binding-20260913.txt` shows the entire transform override gated by byte0076E1C0; when disabled it returns without change. It writes supplied transform +18 and clears the base object's +8 target only when enabled. Do not make normal-view conclusions from this optional path.

Normal initialization instead calls 005DE9A0 -> 005DE550(type4,size54), attaches that object to the +20 reference with 005DF340(...,4), sets its +8 to the +40 reference, and activates it with 005DEF90. Fresh `model-camera-constructor-20260913.txt` and `model-active-camera-20260913.txt` show activation sets +10 on the matching object in list0222995C and clears other entries; 005DEF30 retrieves the active entry. Its callers include 005DDE90, the main-loop rbTransformWorld_End stage, plus rendering/effect consumers (`model-active-camera-callers-20260913.txt`). These joins distinguish the normal active view object and paired position/target references from the optional override. Full render matrix calculation and zoom writers have not been exported in this unit.

### Follow-up: emitter names are mesh records linked to transform nodes

Fresh `E:/logh7-build/model-instance-build-20260913.txt` and `model-instance-nodes-20260913.txt`: 005DE8E0 allocates type1 through 005DE550, which calls 005DF940. That constructor builds runtime +100/+104 from serialized 0xE8-byte transform-node records, and runtime +108/+10C from serialized mesh records at data+28/count+2C, stride0x180. Each mesh record's signed +80 selects a transform node from +100; if nonnegative the constructor attaches that mesh to the selected node with 005DF340(...,2). Negative indices remain unbound until attachment fallback. Transform-node parent indices come from node+90. Node+9C/+A0/+A4 feed a rotation-related helper in swapped first-two order; they must not be interpreted as a ready world-space firing position.

Existing `preview-model-container-transform-v24.txt` 005E0350 confirms each runtime mesh +4 points to its corresponding 0x180-byte serialized mesh record. Therefore the BEAM/GUN/MISSILE selector searches **mesh names**, then obtains that mesh's linked transform-node +58. The two identical raw strings in the earlier inventory are not two emitters. A transform node named BEAM without a matching mesh name is insufficient for this particular lookup.

### Follow-up: actual medium-model mesh/node joins

Read-only PowerShell parse applied the existing MDX block walk (88-byte header; node232, index4, material136, auxiliary144 plus counted ushort tails, animation28, then mesh384). Checked mesh-table file bounds and each selected node index/parent chain for bounds and cycles. Names read from first64 bytes; mesh node index signed+80, node parent signed+90, node table at file88.

| File | Nodes/meshes | Weapon mesh index -> transform node -> root |
| --- | --- | --- |
| GE/em012.mdx | 21/21 | BEAM_01 4->4->0; GUN_01 17->17->0; GUN_02 18->18->0; MISSILE_01 20->20->0 |
| GE/em018.mdx | 8/8 | No matching weapon mesh |
| FP/fm003.mdx | 19/19 | BEAM_01 14->14->0; GUN_01 15->15->0; GUN_02 16->16->0; MISSILE_01 18->18->0 |
| FP/fm014.mdx | 12/12 | BEAM_01 1->1->6; MISSILE_01 9->9->6; GUN_01 11->11->6 |

All eleven selected weapon mesh links reached a negative-parent terminator without invalid indices or cycles. F014's root is node6, not0: a generic root0 assumption would be wrong. This proves structural emitter joins for these original installed medium files, not evaluated animation/world-space positions or guest resource parity. No binary, model or server modifications.

### Follow-up: animation evaluation is distinct from world transform

Fresh exports `E:/logh7-build/model-node-transforms-20260913.txt`, `model-node-animation-20260913.txt`, `model-node-evaluate-20260913.txt`: constructor tail 005E0780 initializes per-node animation time through 005E0740. That routine sets node animation-object+108 and calls 005DF1D0. When animation time +108 differs from cached +10C, 005DF1D0 calls 005ECC30 to write node **+18 local matrix**, optionally combines node+98 while preserving translation at +48, and marks node+DC dirty. This is not the +58 matrix consumed by firing: parent/world propagation must occur separately. 005DF670/005DF7B0 concern per-mesh auxiliary allocations rather than this matrix evaluation; do not continue treating them as the direct transform writer. 005E0610 also samples mesh opacity/light/camera properties, not a replacement for node hierarchy propagation.

### Follow-up: +58 world transform writer located

Fresh `E:/logh7-build/model-animation-evaluator-20260913.txt`, `model-world-propagation-20260913.txt`, and `model-world-recursion-20260913.txt` close the writer join. Main-loop 005DDE70 traverses list0222A1F8 via 005DF2A0. That routine supplies an external parent's +58 matrix or identity for a root. Recursive 005DF250 always calls 005DD4B0(node+58,parentMatrix,node+18), then traverses children at node+8, advancing siblings from node+0. Although dirty flags are cleared/propagated, this recovered function does **not** conditionally skip multiplication when clean. Do not implement a dirty-only shortcut based on the earlier wording. 005DD4B0 forwards to thunk005A5BB1 with the second and third inputs reversed; matrix convention/order still needs verification before porting.

005ECC30 caches animation sampling: first call classifies channels by key counts and evaluates through 005EC5D0; later static channels or unchanged times can skip evaluation. This cache is distinct from unconditional hierarchy multiplication. The weapon path 004DC8C0 now joins directly to a known writer for its node+58 source, rather than an inferred field meaning.

### Follow-up: whole Ship corpus structural check

Applied the same read-only MDX table walk to all 261 installed Ship MDX files, checking header, auxiliary-table and mesh-table bounds, then each uppercase BEAM/GUN/MISSILE mesh's signed node index and ancestor chain. Result: 261 parsed, 153 files with weapon meshes, **564 weapon mesh records**, zero negative/unbound weapon-node indices, zero out-of-range node indices or ancestor cycles, zero parse exceptions. This resolves raw-marker duplication into actual mesh records across the scanned corpus; 564 includes separate LOD files and must not be presented as unique weapon positions across ship types. The other 108 files have no matching weapon mesh, not necessarily malformed assets. File identities remain in the earlier per-file SHA256 inventory. Geometry tail, animation-channel validity and evaluated transforms were not covered by this structural check; parser robustness against synthetic malformed inputs remains untested.

### Follow-up: two channel-evaluation branches

Fresh `E:/logh7-build/model-channel-evaluation-20260913.txt` exports 005EC5D0. It branches on signed channel-map+14. Negative uses grouped translation indices +4/+8/+C, quaternion-like rotation sampling through 005EC590 and conversion helpers, and scale/oriented-scale channels starting at map+1C. Nonnegative evaluates nine scalar channels at map+4 through +24, defaulting the first six to0 and final three to1, with a first-two rotation component reorder before 005ED2B0. Translation lands in matrix+30/+34/+38. Without translation channels, the scalar branch copies only the three basis vectors and preserves existing translation rather than resetting the entire matrix.

Important timing distinction confirmed in assembly: negative branch loads animation+108, calls __ftol at005FF374, then FILD converts the integer back to float before channel sampling (e.g.005EC62F..655). The nine-scalar branch passes animation+108 directly. A universal floating-time sampler would therefore not reproduce both branches. Channel interpolation itself (005EC290/005EC590), quaternion conventions and matrix multiplication remain unresolved; this evidence does not authorize guessed coordinates.

### Follow-up: key formats and interpolation boundaries

Fresh `E:/logh7-build/model-keyframe-sampling-20260913.txt` and `model-keyframe-branches-20260913.txt`: channel header+8 sign chooses full36-byte versus compact8-byte key records. Scalar wrappers005EC290 and quaternion wrapper005EC590 convert time through __ftol for the compact branch. Full keys contain value+0/time(float)+4/interpolation-tag+8; compact keys contain value(float)+0/time(u16)+4/tag(u16)+6. This matches the existing parser's high-bit stride decision but supplies consumer meaning.

Scalar005EBA90 supports pre/post behavior fields header+0C/+10: zero, endpoint hold, repeat, alternating repeat, offset repeat, and linear extrapolation branches. Full segment tags0/1/2 use cubic basis with tension/continuity/bias or tangents;3 is linear;4 holds the preceding value. The tag belongs to the **next** key in a segment. Compact005EBFE0 tags0/1/2 use the cubic basis with zero tangent terms;3 linear;4 step. Do not copy the full-key tangent semantics onto compact keys. Single-key scalar curves return that value directly.

Quaternion readers005EC310/005EC450 gather four adjacent channel records (header stride1C), find neighboring times, and call005ED410. End-at/after-last returns the last four values directly. Before-first and equal-time behavior must be checked carefully: clamping both indices can yield equal times, and these functions do not contain a visible generic divide-by-zero guard. This is a malformed/boundary test requirement, not a reason to silently change original arithmetic. 005ED410's interpolation convention remains to inspect before claiming quaternion parity.

### Follow-up: quaternion interpolation is sign-corrected lerp, not slerp

Fresh `E:/logh7-build/model-quaternion-interpolation-20260913.txt` and `model-quaternion-normalization-20260913.txt`: 005ED410 computes the four-component dot product. If negative, it negates the second input quaternion in place (the keyframe caller supplies temporary arrays). It then writes each component as first+(second-first)*t. No trigonometric/spherical interpolation or normalization occurs inside this helper. The caller 005EC5D0 subsequently invokes 005ED4A0, which divides by the four-component Euclidean norm, before converting to a matrix. Thus that caller chain performs hemisphere-corrected normalized linear interpolation, not SLERP. A generic engine Quaternion.Slerp substitution would change intermediate orientations.

005ED4A0 has no explicit zero-norm guard in the recovered body. Any offline validator should report zero/invalid input rather than silently fabricate identity and call it original parity. These functions remain original-file static evidence; no live memory parity or evaluated mesh coordinates measured here.

### Follow-up: current medium models contain only single-key channels

Fresh readonly header/table scan at the recovered animation-table offset: E012 has189 channels, E018 has72, F003 has171, F014 has108. **All540 channels are full-format, exactly one key each; zero compact, empty or multi-key channels.** This checks actual current candidate medium files, not all LODs/corpus or runtime procedural transforms. An initial PowerShell foreach-to-pipeline syntax error executed no scan; corrected assignment-to-rows command exited0 and produced these counts.

This materially narrows the immediate task: generic interpolation reconstruction is not required to evaluate the stored channels of these four files. Prioritize extracting each one-key value and joining its node channel map, then applying the verified local/parent transforms. Do not keep blocking current-model firing-origin work on unrelated multi-key curve completeness. Runtime actor pose, LOD and procedural changes still require native observation.

### Follow-up: extracted local weapon-channel values

Read-only byte walk follows the existing parser through fixed tables, node172-byte channel-map blocks and their counted tails, then animation keys. For each weapon mesh's referenced node, sampled the first channel-map block's nine indices at+4..+24. Checked single-key counts, key bounds and referenced channel upper bounds. All listed maps select the nine-scalar branch; all scale components are1. These are **local stored channel values**, not final world positions. First-block selection is an explicit inspection choice, not proof of the live active animation block.

| Model / mesh | Stored translation XYZ | Stored rotation triple |
| --- | --- | --- |
| E012 BEAM_01 | 0, .0125, -.1665 | 3.141593, 0, 0 |
| E012 GUN_01 | -.0275, .04, .025 | 3.141593, 0, 0 |
| E012 GUN_02 | .0275, .04, .025 | 3.141593, 0, 0 |
| E012 MISSILE_01 | 0, -.015, .07 | 3.141593, 0, 0 |
| F003 BEAM_01 | 0, 0, -.29 | 0, 0, 0 |
| F003 GUN_01 | -.006, -.01565786, -.2885 | 0, 0, 0 |
| F003 GUN_02 | .006, -.01565786, -.2885 | 0, 0, 0 |
| F003 MISSILE_01 | 0, 0, -.11 | 0, 0, 0 |
| F014 BEAM_01 | 0, -.002, -.052 | -3.141593, 0, 0 |
| F014 MISSILE_01 | 0, -.001, -.015 | 0, 0, 0 |
| F014 GUN_01 | 0, -.0055, -.052 | 3.141593, 0, 0 |

Do not label the rotation tuple as XYZ Euler without the 005ED2B0 convention: the consumer reorders its first two components. Distinct per-family positions exist; a single universal hull-center origin loses these offsets. E018 remains without weapon meshes. Parent/root rotation, actor pose, active block and LOD must still be applied/observed.

### Follow-up: first-block selection verified for these stored models

Fresh `E:/logh7-build/model-animation-block-init-20260913.txt` and `model-animation-state-init-20260913.txt`: 005DEA20 constructs type7 through005DE550; 005EC2D0 sets animation+100 directly to the passed channel-map block, initial time0 and multiplier1. Earlier005DF940 passes serialized node+88, the first block pointer. A fresh node+8C count scan found **exactly one block for every node** of E012(21), E018(8), F003(19), F014(12). Thus the prior first-block extraction is not choosing arbitrarily among stored alternatives in these four medium models and matches initialization. Later procedural/runtime changes or other models/LODs are not covered.

### Follow-up: matrix operation is runtime-dispatched

Fresh `E:/logh7-build/model-matrix-conventions-20260913.txt`, `model-rotation-wrappers-20260913.txt`, `model-matrix-dispatch-refs-20260913.txt`: 005A5BB1 initializes through005B4996(1) then jumps through0078EC54; the direct thunk also jumps through that slot. Thus the wrapper alone does not establish element-order arithmetic. Cached direct references to the slot contain two indirect jumps, not the bulk initialization writer; absence of a direct write reference is not evidence the slot is immutable.

005ED2B0 applies005DD5E0 with input[2], then005DD610 with input[0], then005DD640 with input[1]. These wrappers construct rotations via005A6742/005A65F3/005A669B respectively and multiply through the same dispatch. Combined with the earlier input reorder, stored rotation channels are consumed in third, second, first order through these wrappers. Axis names and multiplication layout should be recovered from the constructor bodies/selected arithmetic implementation, not inferred from address ordering.

### Follow-up: baseline arithmetic and rotation axes recovered

Fresh `E:/logh7-build/model-math-initialization-20260913.txt`: 005B4996 copies57 pointers from0078ED30 into0078EC48, initializes baseline, then may select3DNow/SSE2/SSE according to CPU/config. No configuration changed. PE-section mapped reads identify source slots0078ED3C->005A5BC8,0078EDA8->005A6617,0078EDAC->005A66BF,0078EDB0->005A6766. Fresh `model-base-matrix-arithmetic-20260913.txt` exports those bodies.

005A5BC8 computes row-major out=A*B, preserving input aliasing via a temporary matrix. Translation resides at indices12..14; 005DD4B0 reverses its two inputs, so node world propagation is **world=local*parentWorld** under row-vector convention. Axis constructors005A6617/005A66BF/005A6766 are X/Y/Z rotations in radians: X m12=sin,m21=-sin; Y m02=-sin,m20=sin; Z m01=sin,m10=-sin. Therefore the scalar-channel rotation chain is Rz(storedThird)*Rx(storedSecond)*Ry(storedFirst). This establishes baseline mathematical convention, not bit-for-bit parity with the guest's currently selected SIMD path. Initial constant007B30B4 still needs identity-byte verification before omitting it from reconstruction.

### Follow-up: identity seed and node pre-rotation checked

Fresh PE-section-mapped64-byte read at007B30B4 confirms exact float identity matrix (diagonal1, all other entries0). No extra seed rotation is hidden there in the original on-disk binary. Read node+9C/+A0/+A4 for all60 nodes of E012/E018/F003/F014: every triple is zero. Consequently the constructor's optional pre-rotation branch is not activated by these files' stored triples. This removes that extra transform from the current static reconstruction scope, but does not establish parent channel transforms are identity or exclude later runtime overrides. No source/model/runtime mutation.

### Follow-up: root transform is not identity

Fresh full-key walk and parent-negative-node scan: E012 root0, F003 root0, F014 root6 each has channel tuple `(0,0,0, 3.141593,0,0, 1,1,1)` in PowerShell's displayed precision. Each has exactly one root and one channel block per node. Under the verified reorder/axis chain this is approximately a half-turn about Y, not an identity root. All eleven weapon nodes previously inspected are direct children of these roots.

Consequently root-applied model-space translations approximately negate local X/Z while preserving Y (exact arithmetic must retain the binary float and trigonometric result). For example E012 BEAM local `(0,.0125,-.1665)` becomes approximately `(0,.0125,.1665)`; F003 BEAM `(0,0,-.29)` becomes approximately `(0,0,.29)`; F014 BEAM `(0,-.002,-.052)` becomes approximately `(0,-.002,.052)`. These are inferred model-space values from static original data, not measured guest/world firing coordinates. Do not use the prior local-coordinate table directly as world positions or round the half-turn to exact sign flips in parity tests.

### Implementation: reusable mesh/node-chain diagnostics

Extended existing `scripts/mdx_geometry_v114.py` extraction result with `launchMeshes` (mesh index, name, nodeChain). Resolves matching mesh names only, follows signed node parents with bounds and cycle checks; negative/unbound mesh indices yield an empty chain rather than fabricated root0. Existing bounded table/geometry walk remains in use. Does not evaluate transforms or modify assets.

TDD: three new hand-built-fixture tests failed first (missing output; bad index/cycle not rejected), then all8 tests passed including five existing geometry tests. The valid fixture uses root1 and duplicates BEAM_01 in node and mesh names, detecting root0 assumptions and double counting. Command: `python -m unittest test_mdx_geometry_v114 -v` in case scripts. Synthetic malformed parser coverage is scoped, not exhaustive; this is not a server test or native gameplay result.

### Implementation: node single-key channel diagnostics

Extended the same parser with `nodeChannelValues`: first-block raw slots1..9 resolved through animation records into single-key float values. Nodes without blocks return null; absent or non-single-key curves return null per slot. This is not a nine-scalar pose claim for quaternion/grouped mappings and not an animation evaluator. Index bounds checked; nonfinite single-key values rejected. Two hand-built fixture tests first failed (missing values and accepted bad index), then all10 tests passed. Fresh full261-file parse succeeded and retained564 weapon mesh records. No coordinate matrices, gameplay server or native runtime changed. Follow-up tests should cover compact keys, multi-key/null distinction and nonfinite rejection explicitly.

### Verification: parser boundary fixtures

Added four characterization tests for existing parser behavior: compact8-byte single key consumes exactly its record, multi-key curves are not mislabeled as their first static value, truncated animation payload is rejected, and NaN/+Inf/-Inf single-key values are rejected. All14 tests pass. These tests passed immediately against the existing implementation; they are additional regression coverage, not a new RED/GREEN production change. No model/server changes this unit. Multi-key and absent slots are both null in this diagnostic output; downstream coordinate code must require complete supported data rather than treating null as a zero/default transform.

### Implementation: static scalar chain origins

Added `static_scalar_chain_position(channel_values,node_chain)` to the existing parser module. Explicit preconditions: caller verifies nine-scalar mode, single block, zero pre-rotation; no actor/procedural transform or native arithmetic-parity claim. Child-to-root calculation applies scale, Rz/Rx/Ry, then translation at each node. Rejects incomplete/nonfinite values, empty chains, invalid/repeated indices. Uses decoded binary float values with Python math, not rounded displayed angles.

TDD three new tests failed first for missing evaluator; all17 now pass. Literal expectations test the half-turn parent correction, scale-before-rotation plus parent translation, and rejection of incomplete channels. Evaluated all11 weapon chains of previously verified E012/F003/F014 medium files. Example resulting model-space BEAM origins: E012(-2.514080087e-8,.012500000186,.166500002146), F003(-4.378878498e-8,0,.290000021458), F014(-7.851781715e-9,-.002000000095,.052000001073). Small residual X values retain non-exact stored half-turns. These are offline diagnostic results, not measured guest firing coordinates. Tests/native actor transform/SIMD parity remain distinct.

### Fresh native observation

2026-09-13 01:50 KST: existing VMware CLI display-wake command completed and fresh screenshot `E:/logh7-build/model-origin-live-20260913-015022.png` shows unlocked Windows and G7MTClient with empty ID/password LOGIN. No tactical entry or firing observed; no inputs, credential changes or restart. Native firing-origin comparison cannot proceed from this screen without login. This observation is not new combat progress. Server implementation and offline validation remain available independently; do not mark the whole goal blocked solely on this UI boundary.

Next: compare native firing origins after login to these diagnostics and address actual armed E018 missing-marker behavior. Gun/missile authority capabilities, atomic ammunition debit, native input-to-damage and full battle lifecycle remain incomplete. Latest deployment remains the NPC fire retry build. Full goal ACTIVE.
