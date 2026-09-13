# VM black capture: display idle reset restored the frame

## Verified live result

At 2026-09-12T14:27:00Z, a one-shot `SetThreadExecutionState(ES_DISPLAY_REQUIRED=2)` in guest session1 restored VMware capture from black to the original game's LOGIN screen. No game/server restart, mouse input workaround, authentication input, security change, persistent power-policy change or continuous wake request was made. Windows authentication was not the observed screen after restoration; the game authentication screen is now visible and input stopped there under computer-use guidance.

- Before: `E:/logh7-build/vm-current-output-20260913-a.png` black.
- Fresh read-only receipt: `E:/logh7-build/display-state-20260913-a.json`, actual UTC2026-09-12T14:24:15Z. Filename's 20260913 is a label error, not the event date.
- Guest console1 Active, explorer/dwm/game responding, no LogonUI/LockApp returned. VMware SVGA3D statusOK, resolution1024x768.
- Display timeout AC300s/DC180s.
- Game PID5968/start11:05:14.5230519Z; server PID1724/start13:21:21.6725319Z, actor-guards deployment. No game TCP connection. Server listens47900 on both expected addresses.
- Host sky click on freshly observed VMware view69 failed `coordinate input geometry is unavailable`; do NOT claim click sent.
- Host sky Shift_L call succeeded but two subsequent guest captures remained black; guest delivery is not proven by host call success.
- CLI power request receipt: `E:/logh7-build/reset-display-idle-20260912-a.json`, flags2, previousState2147483648, success true, guest session1.
- After: `E:/logh7-build/vm-after-display-reset-20260912-a.png`, visually inspected: desktop and LOGH7 login screen restored.

## Reproduction / scope

Scripts in work/20260904-warp-state-reverse/scripts:
- guest-display-state-20260913-a.ps1: read-only process/session/network/video/power-policy receipt.
- guest-reset-display-idle-20260912-a.ps1: one-shot power API call plus receipt; refuses an existing receipt. Both copied to the established guest root using host-vix51.ps1.

Wake script was run with host-vix51.ps1 Run -Interactive -Wait, making the hidden helper run in guest session1. Afterwards Capture and CopyFrom completed exit0. Do not reuse a consumed fixed receipt path or infer success without a fresh image. The existing 5-minute AC display timeout remains; this is a verified wake recovery, not permanent prevention. No authentication automation should be attempted through another mechanism.

Microsoft semantics: https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-setthreadexecutionstate — flag2 turns on the display by resetting its idle timer; no ES_CONTINUOUS flag was used.

Source server latest still UNDEPLOYED: participant-merit-full.trx1012PASS. This UI recovery does not prove login, tactical import or playability. Main next server work remains chief selection/mission authorization, with full rank+achievement tie policy awaiting the user's answer.
