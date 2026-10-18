# Project-private prestart silence runtime

Implementation tasks: t_2ff2d248, t_faccee24. Independent exact-head review is mandatory.
This implements the suspended target / silence guard / helper topology bridge,
not original-client QA or product acceptance. Both runners' `--product-logh`
switch now route to the SAME prestart supervisor/controller, never the old
fixture-only runner. Do not invoke product mode before its exact-head review.
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
- Create suspended direct base Python controller (`-I -S -B -X pycache_prefix=<owned-run>/pyc`, no venv redirector)
  and assign its outer kill-on-close / active-limit-5 Job before resuming it.
- Parent observes membership after opening retained handles (PID-reuse check),
  retains PID+creation records and kills/waits the entire outer Job on all exits.

Controller in outer Job (UI defaults untouched):

- Explicit, inventoried site-package paths and pywin32_bootstrap replace site
  initialization. No .pth is executed by this `-S` controller. Supervisor's
  dedicated venv interpreter, .pth/.py/.pyd/.dll/.exe, base stdlib/interpreter and
  pyvenv.cfg are inventoried, as are all executing project source files.
- R-PYC-1: inventory includes .pyc, including stdlib __pycache__. Controller
  additionally uses an exclusively created empty pycache prefix and -B, so it
  cannot load an in-tree timestamp-matching cached payload. Supervisor commands
  below also require -B and a newly created empty prefix (never reuse caches).
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

    python -I -B -X pycache_prefix=<new-empty-supervisor-cache> <copy>/tools/client/qa_prestart_runner.py --repo <copy> --fixture-dir <fixture-dir> --temp-parent <private-parent> --manifest <new-manifest.json> --report <unused-path> --record-manifest

Pin its SHA256 externally, then run:

    python -I -B -X pycache_prefix=<another-new-empty-supervisor-cache> <copy>/tools/client/qa_prestart_runner.py --repo <copy> --fixture-dir <fixture-dir> --temp-parent <private-parent> --manifest <manifest.json> --manifest-sha256 <external-64hex> --report <new-report.json> --mode success --timeout 5 --reference-pid <existing-reference-pid>

Modes: success, failure, timeout, exception, cancel, guard-failure, detach.
`--cancel-file` existing before creation prevents any new process. Cancel mode
sets an owned cancellation file after 3 seconds to exercise running cleanup.
Exit codes: 0 fixture PASS, 3 WAIT, 2 other results. Existing reports are preserved.
`--controller` is an internal integrity-checked entry, not an alternate QA command.
It validates the pinned executable and app closure before product process creation.

## 제품 진입과 전체 app-tree pin

The copied guard accepts explicit `app_root` and independently pinned
`expected_runtime` for the eventual reviewed adapter. The closure includes ALL
regular app files, including sibling data/, executable, wrappers/configs and
injected payloads, with no mutable-file exclusions. Symlink/reparse entries and
ambiguous case-folded names fail. Unknown manifest is never approval. The known
G7 executable hash remains fixed. `--product-logh --app-root <full-private-app>
--executable <full-private-app>/exe/G7MTClient.exe --port <local-stub-port>` selects
the non-fixture AudioGuard with explicit expected_runtime, app_root, injected
files and retained-handle on_failure. No synthetic RPC runs in this mode.

Before product execution, prepare/pin the actual full app tree + wrappers +
configuration + injected helpers against the final copy. Record with the same
product arguments and --record-manifest (does not create a game/controller),
then externally pin the resulting SHA256. Recording is NOT runtime approval.
The inventory includes all regular app files without mutable-output exclusions,
seven runner/guard/payload sources as injected entries, Python closure and the
fixed loopback-only launch arguments `127.0.0.1 <port> ginei00 1 dummy`. The
generated controller config is separately SHA-pinned before controller launch.
Any app write means the next invocation requires re-recording and review; never
silently approve a changed tree. Exe outside app_root, nonprivate roots, wrong
G7 hash, missing app root or changed external manifest SHA fail closed.

Product mode waits with zero created processes if existing Steam/Steam helper,
HOI4 or known G7 client processes are present. It does not stop user processes.
Cancellation, timeout, exception and guard failure retain the same Job cleanup.
Normal product exit is PRODUCT_OBSERVED, NOT PASS (CLI exit 2). A timeout remains
TIMEOUT, not proof of audio coverage or product acceptance. `t_78eb1710`
remains the ONLY official game QA card; acceptance
`t_a1c38302` must not treat this fixture evidence as a product QA receipt. Any
activation change requires its own exact-head review and manifest regeneration.
Session-0 D3D9Ex, audio/API target coverage and interactive broker static audit
remain product-stage risks, NOT_RUN here. Hashing is integrity checking, not a
hostile-code sandbox; concurrent same-user mutation has validation/use races.

## 실제 오디오 대상 커버리지 증거 설계

작성: 최병호. 아래는 시험 설계이며 이번 구현에서 제품/API 시험을 실행했다는 뜻이 아니다.

- 독립 리뷰 뒤 t_78eb1710에서만 exact SHA, 외부 manifest SHA, 원본 exe SHA,
  실제 DLL 경로/바이트 SHA, API 주소와 호출자 모듈/RVA를 실행 로그에 연결한다.
  앱 초기화/로그인/재시도/전략·전술·종료 등 실제 제품 시나리오별로 관찰한다.
  현재 guard의 product_audio_observation은 실제 모듈·카운터 원자료일 뿐
  호출자 및 전체 시나리오 커버리지를 대신하지 않는다.
- DirectSound: 원본 코드가 호출한 DirectSoundCreate/Create8의 실제 HRESULT,
  실제 장치 CreateSoundBuffer/DuplicateSoundBuffer와 buffer QI 경로를 추적하고,
  실제 버퍼의 첫·반복 Play가 원본 재생 함수를 호출하지 않고 차단됐음을
  주소·횟수로 증명한다. 실제 장치 생성 실패는 해당 경로 NOT_RUN이지 PASS가 아니다.
- WASAPI: 정책상 CoCreateInstance/CoGetClassObject 등 COM 활성화는 원본 호출
  전에 거부하며 ActivateAudioInterfaceAsync도 거부한다. 실제 클라이언트의
  활성화 시도와 거부 HRESULT를 입증한다. IAudioClient.Start까지 도달하지
  않는 정책을 Start 실행 성공으로 쓰지 않는다. 관찰되지 않은 API는 NOT_OBSERVED.
- winmm: 실제 대상이 호출한 waveOutOpen/Write, PlaySound/sndPlaySound,
  MIDI/MCI export 및 forwarder의 주소·차단 반환·횟수를 확인한다. 로드만 된
  모듈이나 설치된 hook 수는 호출 커버리지가 아니다. 미사용 판단은 import/
  동적 로딩·GetProcAddress와 실제 모든 시나리오 대조 근거를 함께 남긴다.
- 합성 callback/vtable selftest는 연결 회귀일 뿐 위 세 API 계열의 대체 증거가
  아니다. 서비스 세션에 장치가 없어 실제 경로가 막히면 NOT_RUN으로 남기고
  사용자 장치/볼륨 변경이나 활성 세션 실행으로 우회하지 않는다.
- 새 출력 모듈/예외/누락은 fail-closed. endpoint/session mute나 호스트 오디오
  재생 probe는 금지한다. 소유 대상만 중지한 뒤 계측을 분리하고 inner/outer
  Job 0 및 보존 PID 부재를 확인한다. 비간섭·무음·정리·제품 프로토콜 근거를
  모두 갖춘 공식 hermes-windows qa 영수증만 제품 PASS 근거로 사용한다.

## Verification

제품 QA 보고 진입과 남은 collector 결함은 [product-qa-entry.md](product-qa-entry.md)를
따른다. 새 `windows-product-qa.ps1`은 비실행 보고 연결이며 런타임 관찰/fixture
성공을 제품 PASS로 바꾸지 않는다. 실제 제품 assertion collector는 아직 없다.

Portable focused tests: `python -m unittest discover -s tools/client -p 'test_qa*py' -v`.
Windows fixture matrix and final-head readback are recorded in
`/srv/hermes-workspace/projects/logh/results/t_2ff2d248-RESULT.json` and
`/srv/hermes-workspace/projects/logh/evidence/t_2ff2d248-*`.
All previous failed probes, earlier fixture evidence and source provenance remain.
