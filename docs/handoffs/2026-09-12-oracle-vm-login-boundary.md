# 2026-09-12 Oracle VM resumed; Windows login boundary

Previous unit verified warp supply/charge preservation with928tests. This unit moved toward native validation and freshly checked the environment instead of reusing old handles.

- Windows Computer Use initialized successfully; host app inventory exposed no game/VM window. No host-app input was sent.
- Initial VIX process query failed. Read-only `vmrun list` established `Total running VMs: 0`; exact VMX existed.
- Started the existing `E:/logh7-vms/oracle-win11-hd-re/oracle-win11-hd-re.vmx` with `vmrun -T ws start ... nogui`, exit0. No new VM, snapshot restore or reset.
- Next `vmrun list` showed exactly that VM running.
- Guest process query ran under command handle96185; polled the same handle to exit0. Filtered output: LogonUI.exe PID1044 (SYSTEM), vmtoolsd.exe PID3228 (SYSTEM). No G7MTClient/Logh7.Server/postgres in the filtered result. Do not infer an active game or live server from historical receipts.

Windows Computer Use skill requires stopping at locked/login desktop and forbids automating user authentication dialogs. No guest UI, credential, game click, deployment, database reset or process stop was performed. User must log into Windows inside oracle-win11-hd-re before native UI validation can proceed. VM is left running.

After login: query fresh processes and window/session ownership again; do not reuse September8/9 PID/HWND data or consumed launch/input scripts. Identify current installed authority version and preserved database before starting server/client. This is the first newly verified external login boundary, not grounds to mark the entire active goal blocked. Safe source work remains possible.

## Follow-up: distinguish management authentication from desktop login

User correctly challenged a blanket claim that login is impossible. VIX guest management authentication actually succeeds: CopyTo/Run/CopyFrom all returned exit0 using the existing secret without exposing it. This does not create an interactive Windows desktop login.

Fresh receipt `work/20260904-warp-state-reverse/evidence/logh7-session-check-20260912.json` at2026-09-12T10:32:10Z: session query printed console1 Conn with empty username, services0 Disc; process inspection found only LogonUI PID1044/session1 among explorer/LogonUI/LockApp/game/server/Postgres names. query.exe returned1, so preserve that caveat rather than calling the command successful. The combined observations support no logged-in desktop, not an inability to authenticate through VIX. No UI or credential input was performed.

One anonymous vmrun captureScreen attempt was rejected for missing guest authentication; it produced no usable screenshot and was not retried. Do not describe a login screenshot as observed. The Windows Computer Use authentication restriction still applies to desktop input; do not change autologon/security settings to get around it. Game-login automation history and desktop login are distinct claims.
