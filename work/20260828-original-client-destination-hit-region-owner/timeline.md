# Timeline

- phase: triage
- decision_delta: prior evidence already exposes mouse client coordinates `DAT_022143DC/E0`, screen-ray owner `FUN_004B25A0`, world-to-grid owner `FUN_004D3580`, hover fields controller `+0x24/+0x28`, and click promotion to selected fields `+0x18/+0x1C`.
- carry_forward_refs: [docs/handoffs/2026-08-28-original-client-destination-confirm-stage-collectors.md, work/20260827-original-client-planet-scene-mode-coordinate-writers/evidence/planet-scene-mode-coordinate-writers-v3.txt]
- hypothesis: exact grid hit regions are runtime projections derived from camera/view/projection/viewport state rather than fixed rectangles.
- next: export the conversion functions, globals, callers, and instruction windows from the hash-bound read-only Ghidra project.
- phase: static
- decision_delta: `FUN_004D6B70` owns mode-0x101 render, client-pixel unprojection, hover-grid calculation, validity filtering, and left/right press-edge promotion. `0x022142DB/DC` are synthesized VK_LBUTTON/VK_RBUTTON state bytes, not previous/current samples.
- matrix_binding: [view 0x009D1368, projection 0x009D13A8, world 0x009D13E8, viewport 0x009D1428]
- implementation_delta: added a read-only projection snapshot collector and an offline resolver that enumerates the exact same-grid client-pixel preimage and requires a 3x3-safe interior point.
- independent_review: APPROVE after REVISE for noncommuting matrix-order coverage and rejection of self-promoted LIVE_READONLY claims.
- result: STATIC_HIT_REGION_OWNER_AND_OFFLINE_RESOLVER_PASS / PARTIAL_LIVE_SNAPSHOT_UNSEEN
- first_missing_boundary: FRESH_DESTINATION_PROJECTION_SNAPSHOT
- next_offline_unit: TEXTDIALOG_COORDINATE_FRAME_COLLECTOR_CORRECTION
