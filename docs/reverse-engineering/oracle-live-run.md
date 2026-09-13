# LOGH7 original client oracle live-run package

Status: PREPARED / modern VM booted / original client runtime UNSEEN.

This package prepares reversible oracle runs for `G7MTClient.exe`. The XP VM remains the highest-fidelity oracle for original framebuffer, Direct3D 8, DirectInput, and legacy OS behavior. A modern Windows 11 VM is also allowed as a fallback execution oracle when XP is unavailable or too slow to provision; it may provide compatibility, socket, file, registry, debugger, and dump evidence, but it cannot by itself prove XP-era visual fidelity.

This package does not edit the original EXE, implement server protocol, or reuse a personal VM. Its purpose is to make future live-run capture repeatable and to keep original evidence separate from redirected or patched experiments. The modern Windows VM has reached desktop over VNC, but VMware Tools guest operations are still blocked by a guest UAC/admin-credential prompt, so original client launch and dynamic dumps remain `UNSEEN`.

## Artifacts

| Path | Purpose |
| --- | --- |
| `qa/oracle/.debug-journal-template.md` | Journal to copy into each run folder before creating evidence artifacts. |
| `qa/oracle/x32dbg-logh7-oracle.dd32` | x32dbg breakpoint and dump script for tactical-entry and `NO DATA` probes. |
| `qa/oracle/procmon-logh7-oracle.md` | Exact Procmon filter/export recipe and capture commands. |
| `qa/oracle/wireshark-logh7-oracle.md` | Capture and display filters for TCP `47900` and the observed endpoint. |
| `qa/oracle/run-receipt.schema.json` | JSON Schema for per-run evidence receipts. |
| `qa/oracle/memory-dump-map.md` | Breakpoint, manager-offset, and dump-region map. |
| `qa/oracle/runbook.md` | Launch, observation, cleanup, and promotion rules. |
| `qa/oracle/modern-win11-vm.md` | Windows 11 VMware fallback oracle provisioning and capture rules. |

## Static evidence carried into the run

- `G7MTClient.exe` SHA-256: `bd19263c10decc3d58373165a82d42a9267868400d407da87d5f4f4109ab6e16`.
- Static endpoint/port candidate: `202.8.80.179:47900`.
- `NotifyChangeMode` opcode: canonical `0x042f`, payload `0x298`.
- `NotifyTacticsChiefCommander` opcode: canonical `0x0431`, payload `0x8`.
- `NotifyTactics` opcode: canonical `0x0f1f`, payload `0x8`.
- Tactical entry functions: resolver `0x004b8b00`, packet body `0x004ba2b0`, update gate `0x004b68f0`, field import `0x004c32a0`, presentation build `0x004b64c0`, battle sequence `0x0050d230`.
- `NO DATA` classifiers: table accessor `0x00522010`, fixed 15-slot accessor `0x005229d0`, multigroup accessor `0x005229f0`.

## Corrected tactical-region assumption

The runtime target is not `participantReady=manager+0x126718`. The current reconciled model is:

```text
manager+0x126710  tactical runtime header
manager+0x126711  mode kind byte; observed static branches include 0 and 2
manager+0x126714  active or target id
manager+0x126718  tactical runtime region base
                  byte 0: initialized/active gate
manager+0x12671c  primary tactical pool, 600 records, stride 0x9ec
```

The x32dbg package therefore dumps `ecx+0x126718` as the region header and `ecx+0x12671c` as the first primary pool record.

## Evidence-state rule

| State | Meaning | Promotion |
| --- | --- | --- |
| `SEALED_ORACLE` | Hash-clean original EXE and resource manifest, no executable/resource patch, read-only debugger observation only, host-only isolation without endpoint redirection. | May become original-behavior evidence when receipt, resource manifest, hashes, PCAP, Procmon, x32dbg log, dumps, and screenshots are present. |
| `EXPLORATORY_REDIRECT` | Hash-clean EXE with any IP/DNS/route override, fake endpoint, local controlled endpoint, or host-only redirection of the hardcoded service address. | Useful for sequencing, but must be rechecked in `SEALED_ORACLE` before parity claims. |
| `PATCHED_EXPLORATORY` | Disposable copy with any executable byte patch, resource modification, endpoint byte modification, or debugger memory write. | Never promotes to original behavior; use only for branch and hypothesis testing. |

## Modern Windows fallback oracle

Use the modern fallback when XP cannot be created quickly or when the immediate need is packet, file, registry, process, module, or crash evidence rather than XP-specific visual parity.

Required VM boundary:

- New VMware VM only; do not reuse the existing personal `D:\Virtual Machines\Windows 11 x64\Windows 11 x64.vmx`.
- Location: `E:\logh7-vms\oracle-win11`.
- Guest: Windows 11 Enterprise Evaluation 25H2 x64, English (United States), 90-day evaluation.
- VM shape: 4 vCPU, 8 GB RAM, 60 GB sparse disk, 1920x1080 display, host-only networking.
- Shared clipboard/folders disabled unless a specific evidence-copy step is journaled.
- Internet disabled after tools and Windows prerequisites are installed; client traffic is limited to the controlled server/capture network.

Official ISO source and expected verification:

- Microsoft Evaluation Center page: `https://www.microsoft.com/en-us/evalcenter/evaluate-windows-11-enterprise`.
- Current direct shortcut: `https://aka.ms/Win11E-ISO-25H2-en-us`.
- Expected redirected file: `26200.6584.250915-1905.25h2_ge_release_svc_refresh_CLIENTENTERPRISEEVAL_OEMRET_x64FRE_en-us.iso`.
- Expected size: `7092807680` bytes.
- Expected SHA-256: `A61ADEAB895EF5A4DB436E0A7011C92A2FF17BB0357F58B13BBC4062E535E7B9`.
- Local rejected candidate observed during prep: `E:\logh7-vm-media\Win11_Enterprise_Eval_25H2_en-us_x64.iso`, size `3648909312`, SHA-256 `918263043df01222c56067dd32b5754844d8720f23dbe2acfadc6218d6aa423d`. Do not use it as a trusted installer.

Debugger/tool pins:

- Current official GitHub `snapshot` release fetched during prep: `snapshot_2025-03-15_15-57.zip`, archive SHA-256 `490A428D209C0ED87ED050DB6E47B5F626AE98A7F69917C9F87F14A7C53AFCA0`; contained `x32dbg.exe` SHA-256 `EF1CF002EABB2CA6D2DE27C3B5D7F58730A9924627294D6A6D5F01F96BD68312`.
- Local modern x32dbg archive already present and verified: `E:\logh7-tools\debuggers\snapshot_2026-05-27_12-11.zip`, archive SHA-256 `D41966DFC5B435A372798245300CA0AB7BB8E48BDBF48512C6FB20FCCA427697`; extracted `x32dbg.exe` SHA-256 `822028F0755DBA773E445EAF57FDB3DBA84C9550AC7BDAD2AFA449912B5FBA41`.
- XP-compatible x32dbg archive SHA-256: `5516CC0F6CAF2723D5EBC627FD6B4AE052806F50C0230BC9A2423B0BAADA9132`.
- XP-compatible `x32dbg.exe` SHA-256: `42CF419B3549332AF44A8500E99085A0C590547CAE6950623FE592EA885711C6`.

The same `SEALED_ORACLE`, `EXPLORATORY_REDIRECT`, and `PATCHED_EXPLORATORY` labels apply on modern Windows. Record `guestOs=Windows 11 Enterprise Evaluation 25H2` and `oracleFidelity=modern-compatibility` in receipts so later implementation work cannot confuse this with XP fidelity evidence. A modern sealed run still forbids route/DNS/IP overrides, fake endpoints, modified resources, and debugger memory writes; host-only means isolation only, not redirection.

## Tool documentation basis

x64dbg command syntax separates command name and comma-separated arguments, supports quoted paths, and represents integer constants as hexadecimal. `bp`/`SetBPX` sets software breakpoints, `SetBreakpointLog` logs on hits, `SetBreakpointCommand` runs commands on software breakpoint hits, `scriptcmd` runs a command in script context, and `savedata` saves memory regions. The x64dbg `scriptcmd` documentation also warns that breakpoint callback scripts must remain loaded; therefore `x32dbg-logh7-oracle.dd32` ends with `run`. These official behaviors are the only x32dbg script features relied on here.

Procmon capture is recipe-based because `.PMC` is the documented imported configuration mechanism but not a stable public text format. The package records the exact filters to export rather than fabricating a binary config.

## Local validation note

The package was statically validated against official x64dbg command documentation and the known LOGH7 static analysis addresses. The current host search did not locate an installed `x32dbg.exe` in the active work tools, `E:\logh7-tooling`, Downloads, or Program Files paths. Ghidra's bundled `Debugger-agent-x64dbg` launcher files are present, but they are not the x32dbg executable. Therefore actual script loading remains `UNSEEN` until x32dbg is installed or made available in the XP oracle VM.

## Runtime checks still UNSEEN

- Whether the original module loads at `0x00400000` in XP.
- Whether ECX is the expected manager pointer at every scripted breakpoint.
- Whether all breakpoint commands run without x32dbg expression/parser adjustment.
- Real packet framing, byte order, string length prefix, compression/checksum/encryption.
- Real tactical packet order and minimum viable snapshot fields.
- Real visual state for each `NO DATA` instance and disabled control.

## Pass criteria for future oracle run

A promoted run must include:

- filled debug journal;
- receipt conforming to `qa/oracle/run-receipt.schema.json`;
- matching EXE hash;
- clean resource hash manifest;
- raw `pcapng`;
- Procmon `pml`;
- x32dbg log;
- memory dumps tied to breakpoint timestamps;
- screenshots/video for visible state;
- artifact hash manifest;
- evidence state `SEALED_ORACLE`.

Redirected or patched runs may answer engineering questions, but they remain exploratory until a sealed oracle run confirms the same behavior.
