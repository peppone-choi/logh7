# Scope: original-client interactive desktop HWND route

## Question

Can one reproducible, no-input guest-operation route execute a fresh identity helper in the actual logged-on interactive Windows desktop and produce a PID/hash/module/HWND/owner/visibility/client-rectangle receipt twice without changing VM lifecycle, game state, server, protocol, or database state?

## Included

- Read-only VMware Tools, Windows session, token, desktop, process, and VIX log diagnosis.
- Read-only host inspection of the existing VMware Workstation window and VNC endpoint.
- TDD for a session/desktop-bound helper launcher, external launch-binding manifest, and receipt evaluator.
- Launching only a no-input observation helper in the already logged-on guest session if its token/desktop binding is proven.
- Fresh owned-HWND observation, independent review, verification, handoff, and bounded commit.

## Excluded

- Game click, keyboard input, automatic input, or retry of a physical activation.
- Guest foreground manipulation, x32dbg attach/command, breakpoint installation, or process-memory access.
- VM power/suspend/reset/snapshot/revert operations.
- Client patching, process-memory writes, or server/protocol/database changes.
- Permit issuance or consumption of the one remaining WARP activation.

## Acceptance

- The prelaunch envelope proves the active console session, same-user VMware Tools interactive agent, exact collector hash, and exact `-interactive` route before helper execution; the helper independently proves the actual session, window station, and desktop after entry.
- The helper records its own session ID and desktop name, and stores two complete A/B snapshots of exactly one canonical client and one canonical debugger plus their owned visible HWNDs and stable client rectangles.
- A live claim is ineligible unless a separate host binding recomputes and binds the raw receipt, collector, prelaunch-session, broker, started-marker, and diagnostic hashes plus the exact one-attempt VMware route.
- A session-0 or noninteractive receipt cannot promote itself.
- All state-changing counters remain zero; helper-process creation and guest file receipt writes are counted separately.
- Independent review approves the exact route and receipt before the next prelaunch unit uses it.
