# LOGH7 modern Windows fallback oracle VM

Status: PREPARED / modern VM booted / original client runtime UNSEEN.

This file defines the Windows 11 fallback execution oracle for the original `G7MTClient.exe`. Use it when XP is unavailable or when the immediate goal is runtime dumps, packet traces, file/registry/process observations, or compatibility errors. It complements the XP oracle; it does not replace XP for original framebuffer, Direct3D 8, DirectInput, font, timing, or OS fidelity.

## VM contract

- Hypervisor: VMware Workstation.
- Host VMware tools observed during prep:
  - `C:\Program Files (x86)\VMware\VMware Workstation\vmrun.exe`
  - `C:\Program Files (x86)\VMware\VMware Workstation\vmware-vdiskmanager.exe`
- VM path: `E:\logh7-vms\oracle-win11`.
- Do not use or modify `D:\Virtual Machines\Windows 11 x64\Windows 11 x64.vmx`; that may contain personal state.
- Guest OS: Windows 11 Enterprise Evaluation 25H2 x64, English (United States).
- CPU: 4 vCPU.
- Memory: 8 GB.
- Disk: 60 GB sparse, stored on `E:`.
- Display: 1920x1080.
- Network: host-only only for oracle runs.
- Shared clipboard/folders: disabled by default.
- Internet: allowed only for initial Windows/tool setup, then disabled before client runs.
- Snapshot names: `clean-os`, `tools-installed`, `pre-oracle-run`.

Prep and live observation:

- `E:\logh7-vms\oracle-win11\oracle-win11.vmx` already exists and is labeled `LOGH7 Modern Compatibility Oracle`.
- `vmrun list` reported the VM running during modern fallback prep.
- The VMX originally mounted the rejected ISO at `E:\logh7-vm-media\Win11_Enterprise_Eval_25H2_en-us_x64.iso`; it was updated to `E:\logh7-vm-media\verified\26200.6584.250915-1905.25h2_ge_release_svc_refresh_CLIENTENTERPRISEEVAL_OEMRET_x64FRE_en-us.iso` after hash verification.
- `vmware.log` contains a previous `vmware-vmx` access violation at `2026-08-23T20:55:28.361Z`.
- After `vmrun list` and `tasklist` showed no running VM process, stale `.lck` folders and the prior `564d823a-f3dc-aac9-50a6-c1e75b11836a.vmem` crash-memory file were removed; `vmware-vmx.dmp` remains preserved for failure analysis.
- The official ISO was downloaded to `E:\logh7-vm-media\verified\26200.6584.250915-1905.25h2_ge_release_svc_refresh_CLIENTENTERPRISEEVAL_OEMRET_x64FRE_en-us.iso` and verified with SHA-256 `A61ADEAB895EF5A4DB436E0A7011C92A2FF17BB0357F58B13BBC4062E535E7B9`.
- Because VMware persisted the original ISO path in VMX rewrites, `E:\logh7-vm-media\Win11_Enterprise_Eval_25H2_en-us_x64.iso` was replaced with a verified-copy alias whose SHA-256 also matches `A61ADEAB895EF5A4DB436E0A7011C92A2FF17BB0357F58B13BBC4062E535E7B9`.
- Headless boot via `vmrun ... start ... nogui` reached Windows setup over local VNC on `127.0.0.1:5999`.
- Captures `E:\logh7-vms\oracle-win11\evidence-prep\vnc-boot-0005.png`, `vnc-boot-0006.png`, `vnc-boot-0007.png`, and `vnc-boot-0010.png` show Windows setup progress through desktop arrival.
- Capture `E:\logh7-vms\oracle-win11\evidence-prep\vnc-get-volume-simple-064413.png` shows mounted VMware Tools media at `D:` and `F:`, autounattend media at `E:`, and the Windows volume at `C:`.
- Capture `E:\logh7-vms\oracle-win11\evidence-prep\vnc-tools-drive-064604.png` shows the VMware Tools installer media; the installer application is `setup`, not `setup64.exe`.
- Capture `E:\logh7-vms\oracle-win11\evidence-prep\vnc-tools-setup-start-064621.png` shows the VMware installation launcher reaching UAC.
- Capture `E:\logh7-vms\oracle-win11\evidence-prep\vnc-uac-details-064648.png` shows the current blocker: UAC requests administrator credentials and offers only `No`, so VMware Tools installation and `vmrun` guest operations are not yet available.
- Therefore this VM currently proves modern OS boot/provisioning, media/tool presence, and VNC observability only. It does not yet prove guest-ops control, original client launch, packet capture, dumps, or LOGH7 runtime behavior.

## Official install media

Primary source:

- Microsoft Evaluation Center: `https://www.microsoft.com/en-us/evalcenter/evaluate-windows-11-enterprise`.
- Shortcut currently published by Microsoft: `https://aka.ms/Win11E-ISO-25H2-en-us`.

Expected redirected media:

- Filename: `26200.6584.250915-1905.25h2_ge_release_svc_refresh_CLIENTENTERPRISEEVAL_OEMRET_x64FRE_en-us.iso`.
- Size: `7092807680` bytes.
- SHA-256: `A61ADEAB895EF5A4DB436E0A7011C92A2FF17BB0357F58B13BBC4062E535E7B9`.

Local preflight found a rejected candidate at `E:\logh7-vm-media\Win11_Enterprise_Eval_25H2_en-us_x64.iso` with size `3648909312` and SHA-256 `918263043df01222c56067dd32b5754844d8720f23dbe2acfadc6218d6aa423d`. It does not match the expected official 25H2 EN-US evaluation media and must not be used for trusted oracle provisioning.

## Tool bundle pins

Use tools from `E:\logh7-tools` or another explicitly journaled `E:` directory. Do not install large tool bundles on `C:`.

- Installed current official snapshot path: `E:\logh7-tools\x64dbg-modern`.
- Installed local modern snapshot path: `E:\logh7-tools\x64dbg-2026-05-27`.
- Installed XP-compatible snapshot path: `E:\logh7-tools\x64dbg-xp-2020-12-14`.
- Installed Procmon path: `E:\logh7-tools\procmon`.
- Current official GitHub `snapshot` release fetched during prep: `snapshot_2025-03-15_15-57.zip`.
- Current official archive SHA-256: `490A428D209C0ED87ED050DB6E47B5F626AE98A7F69917C9F87F14A7C53AFCA0`.
- Current official `x32dbg.exe` SHA-256: `EF1CF002EABB2CA6D2DE27C3B5D7F58730A9924627294D6A6D5F01F96BD68312`.
- Local modern x64dbg/x32dbg archive already present and verified: `E:\logh7-tools\debuggers\snapshot_2026-05-27_12-11.zip`.
- Local modern archive SHA-256: `D41966DFC5B435A372798245300CA0AB7BB8E48BDBF48512C6FB20FCCA427697`.
- Local modern `x32dbg.exe` SHA-256: `822028F0755DBA773E445EAF57FDB3DBA84C9550AC7BDAD2AFA449912B5FBA41`.
- XP-compatible x32dbg archive SHA-256: `5516CC0F6CAF2723D5EBC627FD6B4AE052806F50C0230BC9A2423B0BAADA9132`.
- XP-compatible `x32dbg.exe` SHA-256: `42CF419B3549332AF44A8500E99085A0C590547CAE6950623FE592EA885711C6`.
- Procmon: Microsoft Sysinternals Process Monitor from Microsoft Learn/Sysinternals; downloaded archive SHA-256 `4FF309FE52C56599377896B7863CB77B6C601D9F2522E52DA7A182EAC593E8E1`.
- Wireshark: install only inside the analysis VM or capture on the host-only adapter from the host.

## Evidence labeling

Every receipt from this VM must include:

- `guestOs=Windows 11 Enterprise Evaluation 25H2`.
- `oracleFidelity=modern-compatibility`.
- `evidenceState=SEALED_ORACLE`, `EXPLORATORY_REDIRECT`, or `PATCHED_EXPLORATORY`.
- Original EXE SHA-256.
- VM path and snapshot.
- Tool archive/executable SHA-256 values.

Modern fallback evidence can promote implementation hypotheses for launch, packet framing, lobby, character-slot, world-entry, file, registry, module, crash, and memory layout work. It cannot promote original XP visual-fidelity claims without a matching XP run.

For any modern fallback receipt, host-only networking means isolation only. If the run uses an IP/DNS/route override, fake endpoint, local controlled endpoint, or host-only remapping of the hardcoded service address, classify it as `EXPLORATORY_REDIRECT`. A `SEALED_ORACLE` receipt must also record a clean resource manifest and `debuggerMemoryWrites=false`; debugger breakpoints, logs, and dumps are allowed only when they are read-only observations.

## Required run path

Capture each reached boundary with screenshot, packets, x32dbg log, Procmon slice, and receipt entries:

1. Startup/window creation.
2. Initial connect.
3. `LobbyLoginRequest`.
4. Login `OK` or `NG`.
5. Server/session list.
6. Session select or server change.
7. `LobbySessionLogin*`.
8. Character charge/account state.
9. Character slot list, including empty slots and visible `NO DATA`.
10. Character create.
11. Character delete, if safely reachable.
12. Character select.
13. World-entry transition/loading.
14. First strategy/world snapshot or explicit failure.
15. Tactical transition through `NotifyChangeMode`, `NotifyTacticsChiefCommander`, `NotifyTactics`, field import, unit creation, and battle sequence entry if reachable.

If a step fails, stop promotion at that boundary and record the exact visible state, last packet, last breakpoint, and missing input/state collection.
