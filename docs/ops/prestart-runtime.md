# Project-private prestart silence runtime

Implementation task: t_2ff2d248. Independent exact-head review is mandatory.
This implements the suspended target / silence guard / helper topology bridge,
not original-client QA or product acceptance. No game is launched by its CLI.
Both `qa_isolated_runner.py --product-logh` and the new runner's product switch
remain `WAIT_PRESTART_SILENCE_INTEGRATION` with zero created processes.
`product_pass=false`, `product_qa=NOT_RUN`, `environment_ready=false` remain true
contract requirements even when the harmless fixture succeeds.

## Source and isolation topology

`qa_prestart_audio.py` and `qa_prestart_silence_x86.js` are NEW project-owned
copies of the inspected audio-f1-review-20261013 guard/payload. The original
user checkout, shared runner and global Python are not modified or imported.
In particular, never import `logh_prestart_readiness.py`: its top-level legacy
readiness code queries host audio. This implementation neither uses SessionMute
nor writes endpoint/session volume. Synthetic audio tests operate on allocated
native callbacks/vtables, not audio devices. Real DirectSound/WASAPI coverage is
NOT_RUN and must not be inferred from a synthetic callback test.

Supervisor, outside the target Job:

- Verify externally pinned SHA256 of the inventory and compare exact closure.
- Require service-session/nonvisible-window-station isolation, a stable WTS
  session set, explicit private E:/HermesQA repo/temp/fixture paths and >=1.25 GiB
  free before creating a target/controller. Create a private desktop here.
- Hold the legacy GUI mutex in EVERY existing WTS session (plus session 0).
- Create suspended direct base Python controller (`-I -S -B`, no venv redirector)
  and assign its outer kill-on-close / active-limit-5 Job before resuming it.
- Parent observes membership after opening retained handles (PID-reuse check),
  retains PID+creation records and kills/waits the entire outer Job on all exits.

Controller in outer Job (UI defaults untouched):

- Explicit, inventoried site-package paths and pywin32_bootstrap replace site
  initialization. No .pth is executed by this `-S` controller. Supervisor's
  dedicated venv interpreter, .pth/.py/.pyd/.dll/.exe, base stdlib/interpreter and
  pyvenv.cfg are inventoried, as are all executing project source files.
- Create the target suspended DIRECTLY, assign active-limit-1 inner Job, then
  set UI255. Never run a venv launcher between controller and target.
- Attach pinned Frida 17.11.0. Verify the three helper hashes, canonical private
  TEMP extraction directory, parent topology and complete outer membership.
  Frida helper/conhost accounting is distinct from the target-only inner Job.
- Require handshake AND synchronous `assert_ready` immediately before Resume.
  The payload queries `QueryInformationJobObject(NULL, BasicUIRestrictions)`
  INSIDE the actual x86 target, before Resume and on every guard poll. Only 255
  is accepted; named ancestor Job flags are not substituted for effective UI.
- On detachment/fatal callbacks stop only the retained owned target handle.
  Terminate/wait target before detaching hooks, then supervisor kills/waits
  controller/helpers. Missing evidence/cleanup is never PASS.

On Windows 19045, setting UI=0 on BOTH parent and child Job objects produced
`AssignProcessToJobObject` error 50 even with zero restriction flags. The
standalone suspended-fixture diagnostic reproduced: neither/UI0-parent-only/
UI0-child-only assign successfully; both-UI0 fails. Do not materialize an outer
Win32k UI node. Set target UI255 AFTER suspended assignment. The payload's actual
UI query is still the acceptance condition; no restriction was waived.
Evidence: evidence/t_2ff2d248-nested-diag*-execution.json in the project workspace.

A normal target exit can race helper identity queries. Only a signalled retained
target handle with fixture exit 0/7 and no audio-fatal/script error may finish that
race. A live process, unknown exit code or guard stop (0xA001) cannot use it.

## Mutex serialization across sessions

The old `Local\OpenClaw_GUI_TEST_SHARED_PEPPONE_CHOI_PC` is session-local. A new
Global-only mutex would NOT serialize unchanged old runners. `qa_session_mutex`
instead creates/opens the SAME legacy object in each existing session namespace:

- session 0: `\BaseNamedObjects\OpenClaw_GUI_TEST_SHARED_PEPPONE_CHOI_PC`
- session N>0: `\Sessions\N\BaseNamedObjects\OpenClaw_GUI_TEST_SHARED_PEPPONE_CHOI_PC`

Use NtCreateMutant OPENIF, nonblocking waits in sorted order, and NtQueryObject
readback of the actual kernel object name. Failure/access denial/contention means
WAIT with zero target/controller creation, releasing already acquired locks.
Names are held for the whole invocation, including cleanup. If WTS session IDs
change, stop the owned Job and fail closed. No broker/task/service or privilege
adjustment is used; no legacy runner is overwritten. This is evidence about
kernel-object serialization and session boundaries, NOT an active-screen capture
or a guarantee against arbitrary code that ignores the shared mutex.

References:
https://learn.microsoft.com/en-us/windows/win32/termserv/kernel-object-namespaces
https://learn.microsoft.com/en-us/windows/win32/procthread/nested-jobs
https://learn.microsoft.com/en-us/windows/win32/api/jobapi2/nf-jobapi2-assignprocesstojobobject

## Harmless fixture contract

`qa_prestart_fixture.image(mode)` generates deterministic 1024-byte x86 Windows
PEs using only stdlib. Their ONLY imports are kernel32 Sleep and ExitProcess;
no audio, child creation, UI, input, COM or network APIs. Exact generated bytes
are compared before target creation, not merely a caller-provided filename.
Success sleeps 350 ms then exits 0; failure exits 7; hang sleeps 60 seconds.
Native fixture executable files live in a separate exclusive fixture directory,
not the user checkout. All are part of the app manifest. Extraction TEMP is
UUID-owned and retained as evidence, never swept from shared TEMP.

Generate all three images into a NEW explicit fixture directory, using the new
module in the exact-SHA Windows copy. In the dedicated pywin32 312 / Frida
17.11.0 venv, record an inventory (recording is integrity, not review approval):

    python -I -B <copy>/tools/client/qa_prestart_runner.py --repo <copy> --fixture-dir <fixture-dir> --temp-parent <private-parent> --manifest <new-manifest.json> --report <unused-path> --record-manifest

Pin its SHA256 externally, then run:

    python -I -B <copy>/tools/client/qa_prestart_runner.py --repo <copy> --fixture-dir <fixture-dir> --temp-parent <private-parent> --manifest <manifest.json> --manifest-sha256 <external-64hex> --report <new-report.json> --mode success --timeout 5 --reference-pid <existing-reference-pid>

Modes: success, failure, timeout, exception, cancel, guard-failure, detach.
`--cancel-file` existing before creation prevents any new process. Cancel mode
sets an owned cancellation file after 3 seconds to exercise running cleanup.
Exit codes: 0 fixture PASS, 3 WAIT, 2 other results. Existing reports are preserved.
`--controller` is an internal integrity-checked entry and STILL only permits
exact generated harmless images; it is not a product bypass.

## Product continuation boundary

The copied guard accepts explicit `app_root` and independently pinned
`expected_runtime` for the eventual reviewed adapter. The closure includes ALL
regular app files, including sibling data/, executable, wrappers/configs and
injected payloads, with no mutable-file exclusions. Symlink/reparse entries and
ambiguous case-folded names fail. Unknown manifest is never approval. The known
G7 executable hash remains fixed. No CLI route supplies or launches that target.

After exact-head independent review, the product QA owner must prepare/pin the
actual full app tree + wrappers + configuration + injected helpers against the
final copy, and establish original-client API coverage before enabling a product
entry point. `t_78eb1710` remains the ONLY official game QA card; acceptance
`t_a1c38302` must not treat this fixture evidence as a product QA receipt. Any
activation change requires its own exact-head review and manifest regeneration.
Session-0 D3D9Ex, audio/API target coverage and interactive broker static audit
remain product-stage risks, NOT_RUN here. Hashing is integrity checking, not a
hostile-code sandbox; concurrent same-user mutation has validation/use races.

## Verification

Portable focused tests: `python -m unittest discover -s tools/client -p 'test_qa*py' -v`.
Windows fixture matrix and final-head readback are recorded in
`/srv/hermes-workspace/projects/logh/results/t_2ff2d248-RESULT.json` and
`/srv/hermes-workspace/projects/logh/evidence/t_2ff2d248-*`.
All previous failed probes, earlier fixture evidence and source provenance remain.
