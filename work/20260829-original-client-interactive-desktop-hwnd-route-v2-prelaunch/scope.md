# Scope: corrected interactive desktop HWND route v2 prelaunch

## Question

Can the corrected interactive-canary route be sealed offline so a future bounded continuation can prove every launch prerequisite before its one allowed guest call, without executing any VM or guest operation in this unit?

## Included

- TDD for a fresh noninteractive preflight receipt and external host binding.
- A read-only future collector for active-console client/debugger identity, absolute PowerShell identity, and direct session-1 VMware Tools owner.
- A fail-closed evaluator that emits the exact corrected interactive executable, ordered arguments, run ID, unique guest paths, and collector hash.
- Static capability checks, independent review, aggregate verification, handoff, and bounded commit.

## Excluded

- Any vmrun, VNC, VMware UI, guest helper, process, debugger, memory, input, capture, or lifecycle operation.
- Issuing or consuming a permit or physical activation.
- Server, protocol, database, client binary, or process-memory changes.

## Acceptance

- Synthetic or unbound-live input cannot become a route launch candidate.
- A structurally valid externally bound live preflight remains unreviewed and execution-unauthorized.
- The exact program is `C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe`; bare program lookup is impossible.
- The ordered interactive argument vector binds `runId`, unique source/started/raw/diagnostic paths, and the current collector hash.
- Exactly one canonical client, debugger, and directly owned same-user session-1 vmtoolsd agent are required in the active console session.
- All target/game state-changing counters remain zero.
