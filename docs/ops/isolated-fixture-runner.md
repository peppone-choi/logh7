# LOGH isolated fixture runner — partial implementation, runtime acceptance BLOCKED

Task: t_8ce14871. Base: 82d955b1a787f495c4a97fb5dcac755f0adfc644.
This is not product QA. No LOGH executable is launched. No official QA receipt exists.

## Contract

`python -I -B tools/client/qa_isolated_runner.py --repo <explicit root> --report <new JSON> --manifest <pinned inventory> --fixture <root>/tools/client/qa_silent_fixture.py [--mode success|failure|tree|hang|desktop-denial] [--timeout seconds] [--cancel-file file] [--test-exception-after seconds] [--reference-pid PID]`

Exit codes: 0 fixture-only PASS, 2 FAIL, 3 WAIT. Existing reports are never overwritten.
The explicit repo must contain the runner; only its adjacent bundled fixture is allowed.
No arbitrary command or project import is supported. `--product-logh` does not launch anything;
it reports WAIT_PRESTART_SILENCE_INTEGRATION. Do not remove this product boundary until
separately implemented, independently reviewed prestart silence and target coverage exist.

The inventory function emits version 1 plus exact relative own-code hashes, Windows Python
stdlib, interpreter DLLs, venv Python/extension/DLL/executable hashes. Windows v8 measured
3055 entries. Create a candidate JSON by invoking inventory(root) in the dedicated interpreter;
recording this candidate is NOT independent review approval. Pin and review it before reuse.
System Windows DLLs are trusted platform dependencies, not a sandboxed closure. Inventory
validation has a validation/use race under concurrent modification; do not treat it as a hostile
code sandbox. Current code has not passed independent review. No existing shared runner,
user checkout, service, audio device, registry setting or process is changed.

The runner requires Windows pywin32. Test-only venv: E:/HermesQA/runners/t_8ce14871-run15/venv,
pywin32 312, no modification of user's global Python installation. It owns a UUID TEMP/TMP
folder per run and checks 128 MiB free before creation; retained folders contain evidence.
This threshold is fixture-specific and must NOT be reused for Frida/product (>=1.25 GiB in
legacy audio_guard). Shared mutex bytes are exactly Local + backslash +
OpenClaw_GUI_TEST_SHARED_PEPPONE_CHOI_PC; contention creates no fixture. This is the legacy
session-local mutex, not a claim of cross-session global serialization.

Root starts suspended on a new desktop within the runner's nonvisible window station.
Session differs from all WTSActive/WTSConnected sessions and active console. Job kill-on-close,
no breakaway flag, UI flags 255 are set and queried before ResumeThread. Descendant membership
is sampled and session checked. PID+creation times and retained process handles prevent cleanup
from targeting PID reuse. TerminateJobObject, ActiveProcesses==0 and retained-handle waits verify
cleanup; only an unassigned, held suspended root may be terminated individually. Sampling does
not provide a complete audit of every short-lived descendant. This limitation needs review.
The silent fixture has no playback APIs and records absence of four known audio modules.
This is fixture-specific code evidence, not audio-device measurement or LOGH silence proof.

## Actual Windows evidence

Server evidence: /srv/hermes-workspace/projects/logh/evidence/t_8ce14871-matrix-v8.json.
Normal PASS, nonzero exit 7, tree with child+grandchild (six PIDs including venv launchers),
timeout, synthetic exception, cancellation: all cleanup_verified=true, remaining_owned_pids=[].
Mutex contention returns WAIT_GUI_TEST_MUTEX; bad manifest fails before any fixture creation.
Steam 15324 creation time unchanged, WTS/window station before/after equal.
Final independent readback found no earlier owned PIDs still present; clean-copy HEAD remains
7e1704443015341164177de4edb84f9739bbac59 and git status empty. This clean copy is not the runner's
source SHA and was not modified. Runner files are deployed separately, not a git product checkout.

CRITICAL B7 FAILURE: fixture CreateDesktopW succeeds even though its own IsProcessInJob on the
named runner job is true and QueryInformationJobObject UI flags are 255. Fixture exits 9,
desktop_creation_denied=false. The created desktop is in the nonvisible service window station
and is closed without switching or input. We did NOT attempt SwitchDesktop, SendInput or
SetForegroundWindow. They remain NOT_RUN; do not infer they are blocked from flag readback.
Microsoft documentation says UILIMIT_DESKTOP prevents desktop creation/switching:
https://learn.microsoft.com/en-us/windows/win32/api/winnt/ns-winnt-jobobject_basic_ui_restrictions
Thus the design's B7 effective-enforcement requirement is NOT met. Runtime acceptance and product
execution remain blocked, not PASS. A specialist must resolve the measured contradiction (including
nested SSH Job semantics) or deliver an independently reviewed equally strong isolation proof.
No safety condition is waived by this implementation.

Earlier failed probes and v6 cleanup failures are preserved. Cleanup was corrected to distinguish
terminated process objects retained by handles from running processes, avoid a second termination
while Job termination is pending, and retain descendant handles. v7/v8 verify the corrected paths.

## Concrete silence/product continuation (existing logh-fix t_5f006c90, then logh-review/verify)

Read-only source inspected, not imported:
/srv/openclaw-dev/company/agents/logh-review/reports/audio-f1-review-20261013/logh-audio/audio_guard.py
- readiness lines 91-105 cannot approve product: APPROVED_RUNTIME_MANIFEST=None (line 6).
- AudioGuard lines 211-275 validates target image, exact executable hash, Frida 17.11.0,
  exact helper identity/topology and ready handshake before ResumeThread; detachment/fatal
  callback must terminate only retained owned handles. Existing injected silence-x86.js is
  transitive execution code and must be pinned with copied adapter/audio_session and payload.
- strict_owned_job lines 107-117 limits target to one process. Controller/helper Job needs
  separate accounting. Existing parent topology assumes controller+target+three helpers.
- OWNED_TEMP_PARENT line 296 is hardcoded E:/logh7 and must become an explicit project-owned
  path in a NEW copy; never import or overwrite the user's live audio_guard.
- run-background.py currently makes its own desktop and uses SessionMute after creation;
  docs/ops/background-client.md lines 70-74 explicitly admit the initial-audio gap. That is
  insufficient for prestart silence, even if rendering/network evidence historically passed.

Next implementation: copy reviewed audio guard/payload into dedicated clean runtime, parameterize
repo/temp, pin effective app tree/wrappers/config/injection/helper hashes, implement suspended
owned target + guarded helper topology + pre-Resume assert_ready and full target coverage.
Resolve who creates desktop and where inner/outer Job UI restrictions apply; do not simply nest
legacy run-background under this fixture Job. Validate harmless x86 fixtures/failure paths first,
then exact-head independent review, then original-client tests via hermes-windows qa only.
Session-0 D3D9Ex rendering and real DirectSound/WASAPI coverage are unverified product risks.
Do not use import-time legacy logh_prestart_readiness.py (it queries host audio).
