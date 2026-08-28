# Timeline

- phase: triage
- decision_delta: prior parallel static audit disproved manager `+0x7C/+0x80` as origin; actual origin is the recursive `FUN_00507090` result from `uiContext=manager+0x08`.
- carry_forward_refs: [docs/handoffs/2026-08-28-original-client-destination-hit-region-owner.md, work/20260828-textdialog-coordinate-frame-owner/textdialog-coordinate-frame.txt, work/20260828-original-client-destination-confirm-collector/src/collect-confirm-stage-state.ps1]
- correction_surface: [uiContext id/parent/origin/registry chain, manager cache +0xDBC/+0xDC0, widget +0x08/+0x0A/+0x0C/+0x10/+0x14/+0x15/+0x18/+0x20/+0x24/+0x2C/+0x30, scale 0x00772E2C/30, owned HWND client rect]
- red: tests failed because collector/resolver did not exist.
- green: collector 11 cases/28 assertions; resolver 5 cases/17 assertions.
- review_red: initial independent review rejected missing module-base and pre/post HWND surface binding; both were added with engine-rect equality tests.
- parallel_review: parent-chain/cache semantics and x87 scale boundary math reconciled.
- live_or_vm_operations: 0
- game_inputs: 0
- state: STATIC_COORDINATE_FRAME_AND_OFFLINE_RESOLVER_PASS / PARTIAL_LIVE_SNAPSHOT_UNSEEN
- next: independent prelaunch audit; no live input from this unit.
