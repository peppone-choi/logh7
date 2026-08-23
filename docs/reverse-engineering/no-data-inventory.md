# `NO DATA` inventory

Status: `PARTIAL` static evidence only. Dynamic oracle-VM classification remains `UNSEEN`.

Source binary: `G7MTClient.exe`, SHA-256 `bd19263c10decc3d58373165a82d42a9267868400d407da87d5f4f4109ab6e16`.

## Visible fallback string

The ASCII string `NO DATA` is stored at file offset `0x00270910`, image address `0x00670910`, and has three data references in the analyzed program.

| Function | Address | Observed static behavior | Current classification |
| --- | ---: | --- | --- |
| `FUN_00522010` | `0x00522010` | Validates a table index, returns `NO TABLE` when the table is absent, then validates an element index and returns `NO DATA` when it is outside that table's range. | typed table/element lookup fallback; not proof of an empty UI slot |
| `FUN_005229d0` | `0x005229d0` | Returns a pointer to one of 15 fixed records of `0x20` bytes for indices `0..14`; returns `NO DATA` above index 14. | fixed-capacity record accessor; likely slot-like, exact domain unknown |
| `FUN_005229f0` | `0x005229f0` | Selects among several table groups, validates a record and element range, and constructs a result from the selected value; uses `NO TABLE` or `NO DATA` fallbacks on invalid ranges. | multi-table formatted lookup; exact screen/domain unknown |

This disproves the blanket assumption that every `NO DATA` occurrence is merely a harmless empty slot. One accessor is fixed-capacity and may back slots, while the other two are generic lookup bounds.

## Non-visible underflow diagnostics

The binary also contains `no data to input` diagnostics for scalar and string reads in `mtStreamInputBuffer` and `mtNetStreamInputBuffer`. The first uint8 paths are referenced at `0x006105b0` and `0x006119c0`; both compare read position with buffer size before consuming a byte. These are parser/network underflow errors, not UI empty states, and the new protocol decoder must fail closed rather than render them as empty slots.

## Next oracle checks

1. Break on `0x00522010`, `0x005229d0`, and `0x005229f0` in the isolated XP oracle VM.
2. For each caller, capture the owning object pointer, indices, call stack, active screen, rendered label, recent packets, and file reads under one run ID.
3. Identify the 15-record domain behind `0x005229d0` and name its slot type only after the owning screen or data structure is observed.
4. Record each result as `empty-slot`, `server-not-received`, `beta-unimplemented`, or `load-failure`; unresolved cases remain `UNKNOWN`.
