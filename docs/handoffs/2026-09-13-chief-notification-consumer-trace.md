# Chief notification consumer trace — 2026-09-13

Status: partial static evidence. No implementation or deployment in this unit. Tie policy remains unanswered; an automatic goal continuation is not approval.

Read-only Ghidra Unit10Input / g7mtclient.exe exports under `work/20260904-warp-state-reverse/evidence/`:

- `chief-cache43334c-20260913.txt` and `chief-cache433350-20260913.txt`: each immediate offset has one instruction use, the dispatcher store. The 0x0431 branch writes the expanded eight-byte object at session+0x43334C/+0x433350; there is no direct UI callback in that branch. This scan does NOT prove absence of indirect consumers.
- `chief-self-notification-20260913.txt`: `004BE350`, used for self-addressed decoded notifications, bounds a general notification queue at100 entries and appends a12-byte item with two helper-produced values. This is not a chief election routine or proof of a star-label update.
- `chief-type431-20260913.txt`: only immediate scalar0x431 use found was `004F3F0F MOV CL,[EAX+0x431]`; it is a structure displacement, not proof of a message consumer. Message switch tables need not contain the wire type as an instruction immediate.

Next: trace the notification queue consumer (session+0x3579D0 count and +0x3579D4 entries), and the per-side chief storage/label selection if present. Confirm consumers in the live client's on-disk ranges before claiming 0x0431 enables mission authority or renders a star. Offline direction already showed that a valid protocol reader is not sufficient UI implementation evidence.

## Queue branch resolved, not the chief UI

Fresh exports `chief-notification-queue-count-20260913.txt`, `chief-notification-count-callers-20260913.txt`, `chief-notification-lookup-20260913.txt`, and `chief-notification-queue-consumer-20260913.txt` close this branch:

- `004BE3F0` is not a count getter: it scans backward through12-byte records and returns the latest matching notification type.
- Its single direct caller is `0058FF2C` in `0058FEF0`.
- That function uses the records' timing field for command wait/remaining-time calculations, including type table0x0B01/0x0B02/0x0B00. It does not contain a chief0x0431 branch or star-label update.
- Exclude this direct consumer path from chief UI implementation evidence. This is not an exhaustive proof that no indirect consumer exists.

Next useful anchor is the character-name/star rendering path or the actual command-palette chief permission field, not this timing queue. No source changes, deployment, or native input occurred in this follow-up.

## Overhead renderer checked

Fresh `chief-overhead-label-renderer-20260913.txt` exports both `004C8020` and `004C80A0`. After renderer/session/entity visibility guards, each passes entity+0x6BC directly to `004E8AF0`. Neither function tests chief identity, prefixes a star, or reads the0x0431 cache. This rules out these two rendering wrappers as the star-construction site; it does not prove stars never appear elsewhere or that the original server prefixes names.

Re-read existing E069 and main-handoff E133: importer `004C32A0` copies first-parentage display_name into this buffer; flagship_name is a separate wire field. E133's scoped ship-label client patch is still marked approval-pending, and blindly swapping two offsets is insufficient because parentage-less and reused shorter names require handling. Do not globally modify character display_name to force a ship label or chief star.

The constmsg star search found no literal star marker; occurrences of highest commander include strategic job titles and are not evidence for tactical chief selection. Next trace the name-buffer producers and palette permission fields. No new original chief tie rule was recovered.

## Direct name-buffer reference classification

`chief-name-buffer6bc-20260913.txt` found eight direct scalar references. `chief-name-other-consumers-20260913.txt` resolves the two remaining consumers: `004BEBA0` reads the name into a formatted occupation notification after updating entity affiliation; `004C7AA0` retrieves an entity name, with a type/state-dependent alternative lookup. Neither writes a chief prefix into entity+0x6BC.

`chief-name-import-20260913.txt` confirms the ship-name-buffer write block at004C3C2C..004C3C59: it checks parentage count, then copies raw+B8 length / raw+BA display_name directly. The missing-character branch copies a separate fallback and explicitly terminates it. No chief comparison or prefix insertion occurs in these blocks. The importer also has another +0x6BC write at004C345F for a different entity branch; do not globally patch all uses.

This closes the direct render/name-consumer branch as a chief-prefix source. Indirect writes, different UI labels and sender-side name formatting remain possibilities, not findings. Next prioritize the mission palette permission predicate rather than repeating these same name-buffer scans. No client patch, server change or runtime input was made.

## Mission palette selection gate

Current `offline-main-window-dispatch-20260913.txt` and existing `tactical-palette-item-map-v382.txt` agree: main palette item0x0D maps to005117D6, which opens page3 / items0x25..0x2A. The block sets both item-range flags to1 and does not compare a chief character ID. At00511821..005118BA the six choices write mission0..5 and command state0x19.

Fresh `chief-palette-gate-wrapper-20260913.txt` resolves00519BF0: it gets window0x34 and, for selection mode5 with no param4 or mode2/3 with no param5, disables the entire basic range6..0x1D using00508890 and resets its state using005088E0. Caller00510C55..00510C7A passes selection pointers0221666C/02216660 alongside mode0077A8F8. This is a shared selection-availability gate, not chief-only authorization.

Input loop00510CF0..00510D3C first checks part existence, then event9 via005015F0, then dispatches the palette item. These examined blocks do not establish any chief permission. Do not claim either that all client gates were exhausted or that an accepted0421 proves the sender was chief. Server chief authorization remains required and unimplemented; tie policy remains unanswered.

The proposed persistent mission-AI design and fully tied chief fallback (incumbent, then lowest character ID) have not been approved. Do not apply that fallback silently. Live baseline remains the NPC weapon-range deployment from `2026-09-13-npc-weapon-range-selection.md`; full game completion is unproven.
