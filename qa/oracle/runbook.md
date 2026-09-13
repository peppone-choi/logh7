# LOGH7 original client oracle live-run runbook

Status: PREPARED. The modern Windows fallback VM has booted to desktop, but original client runtime remains `UNSEEN`.

XP is preferred for original visual and input fidelity. If XP cannot be provisioned in time, use the dedicated modern Windows 11 fallback in `qa/oracle/modern-win11-vm.md` for execution, dump, packet, file, registry, and crash evidence. Mark that evidence as `oracleFidelity=modern-compatibility`; do not promote it as XP framebuffer proof.

## Preconditions

- XP oracle VM exists from a clean snapshot.
- Original client EXE hash matches `bd19263c10decc3d58373165a82d42a9267868400d407da87d5f4f4109ab6e16`.
- VM network is host-only or NAT-controlled. Public internet access is blocked for XP.
- Evidence folder exists for the run.
- `.debug-journal-template.md` has been copied to the run folder and filled in before starting capture.
- For modern fallback runs, the official Windows 11 Enterprise Evaluation ISO hash and size match the values in `modern-win11-vm.md`.
- The VM is dedicated to LOGH7 oracle work and is not the user's existing personal Windows VM.

## Evidence states

`SEALED_ORACLE`: hash-clean EXE, clean resource hash manifest, no endpoint patch, no modified resource files, no debugger memory writes, and no endpoint redirection. Host-only networking is allowed only as isolation; any IP/DNS/route override, hosts-file mapping, fake server IP, or local controlled endpoint is `EXPLORATORY_REDIRECT`, not sealed.

`EXPLORATORY_REDIRECT`: hash-clean EXE, but networking has been redirected through any IP/DNS/route override, fake server, local controlled endpoint, or host-only remapping of the hardcoded service address. Useful for sequencing and packet shape. Not sufficient alone for original service behavior.

`PATCHED_EXPLORATORY`: disposable modified copy of EXE/resources or any run where the debugger writes to process memory. Useful for branch testing only. Never mix with sealed evidence.

## Launch sequence

1. Restore VM snapshot.
2. Record system time, VM name, snapshot, guest IP, and module hash in the journal.
3. Start Wireshark capture on the host-only interface.
4. Start Procmon with the exported `.PMC` configuration and backing file.
5. Open `G7MTClient.exe` in x32dbg.
6. Confirm module base is `0x00400000`.
7. Load `x32dbg-logh7-oracle.dd32`; the script starts the process with `run` so callback labels remain available.
8. Reproduce the stage under test.
9. At each breakpoint, save x32dbg log and allow the scripted `:memdump:` files to accumulate.
10. Capture screenshots at every boundary listed below, including failures, disabled controls, visible `NO DATA`, and crash/assert dialogs.

## Mandatory lobby and world-entry capture path

Login success is not lobby proof. Capture screenshots, packet slices, Procmon events, x32dbg log lines, and receipt entries for each boundary that is reached:

1. Startup and first window creation.
2. Initial connect attempt to the observed or redirected endpoint.
3. `LobbyLoginRequest`.
4. Login `OK` or `NG`, including any visible error dialog or message.
5. Server/session list request and response.
6. Session select or server-change request.
7. `LobbySessionLogin*` request and result.
8. Character charge/account state request.
9. Character slot list, including empty slots and any `NO DATA` labels.
10. Character create request/result.
11. Character delete request/result, if reachable without destroying a required test account.
12. Character select request/result.
13. World-entry transition and loading screen.
14. First strategy/world snapshot or explicit failure boundary.

If a boundary cannot be reached, record the last confirmed boundary, visible state, packet state, and blocker. Do not infer later lobby, character, or world behavior from earlier login evidence.

## Tactical entry observation path

After the lobby/world-entry path, capture these checkpoints in one continuous run if reachable:

1. Startup and network connect attempt.
2. Login-server protocol banner or failed connect.
3. Lobby/server change if reachable.
4. Session/world entry if reachable.
5. `NotifyChangeMode` equivalent.
6. `NotifyTacticsChiefCommander` equivalent.
7. `NotifyTactics` equivalent.
8. `NotifyEnterGridBegin` and `NotifyEnterGridEnd` equivalents.
9. `WorldIn_TacticsFieldImport`.
10. `MakeTacticsUnit`.
11. `battle_sequence_entry`.

## Cleanup

1. Stop the client from x32dbg.
2. Terminate Procmon capture.
3. Stop Wireshark capture.
4. Hash all artifacts.
5. Fill `run-receipt.schema.json` conforming receipt.
6. Copy evidence from guest to host `E:` only after capture stops.
7. Restore or snapshot the VM according to the evidence-state rule.

## Promotion rule

Only a run with `evidenceState=SEALED_ORACLE`, matching original hash, clean resource manifest, no debugger memory writes, host-only isolation without endpoint redirection, raw packet capture, Procmon log, x32dbg log, and memory dumps may be used as original behavior evidence. Redirected or patched runs are useful engineering tools, but their findings must be rechecked against a sealed run before implementation claims.
