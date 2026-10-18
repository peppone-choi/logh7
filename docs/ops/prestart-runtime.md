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
all OWN_CODE runner/guard/payload/collector sources as injected entries, Python closure and the
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
  I3 guard는 각 hook의 api, caller_module, caller_path, caller_rva,
  return_value, disposition, source, count를 집계한다. caller는 this.returnAddress를
  Process.findModuleByAddress로 찾으며 제품 호출자 해석 실패는 fatal이다.
  product_audio_observation은 관찰 창의 원자료이며 전체 시나리오 커버리지를 대신하지 않는다.
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

I3 collector 계약 (구현, Windows/제품 실행 NOT_RUN):

- controller는 소유 대상 정지 직전 마지막 audiostatus()를
  owned_temp/evidence/audio.json에 저장한다. run_id, manifest_sha256,
  config_sha256, target(PID/생성시각/이미지 SHA 포함)를 바인딩하고 supervisor가
  정리 후 evidence_files에 파일 SHA를 등록한다. 실패/취소에도 snapshot을 시도하되,
  RPC 실패가 Job/handle 정리를 건너뛰지 않는다. 이미 죽은 대상의 마지막 증거가 없으면 FAIL이다.
- qa_audio_coverage.py의 순수 판정은 guard ready/UI255 연속성(간격 ≤1초),
  error/audio-fatal 0, originals_called==0 및 모듈/RVA에 귀속된 유효 시도 ≥1을 요구한다.
  deny 교체 함수는 원본 재생 NativeFunction/trampoline을 만들거나 호출하지 않는다.
  originals_called는 이 원본 재생 호출 경로의 카운터(현재 제품 경로 없음)이며
  허용된 초기화/QI 원본 호출 횟수를 의미하지 않는다. 합성 selftest의 원본 재생
  callback도 카운터를 증가시키지만 source=fixture이므로 제품 근거가 될 수 없다.
- suppress 반환은 API별 고정 반환값과 비교한다. DirectSound 초기화/버퍼 생성의
  성공 반환은 하위 vtable 보호 설치·flush 뒤 guarded-init으로 기록한다.
  초기화 실패는 장치 부재일 수 있어 PASS 근거에서 제외한다. 일반 COM 거부/QI/자식
  생성 거부는 기록하되 오디오 의도가 확인되지 않으므로 유효 오디오 시도에 넣지 않는다.
  WASAPI는 ActivateAudioInterfaceAsync 거부 또는 보호된 인터페이스 시도가 있어야 관찰로 센다.
- 시도 0건은 NOT_RUN, originals>0/미차단 반환/fatal/새 출력 모듈/잘못된 RVA는 FAIL.
  미관찰 API 계열은 NOT_OBSERVED 목록이며 fixture/selftest source는 PASS 근거에서 제외한다.
  고유 호출 집계 상한 4096 초과는 fatal이다. guard_receipts는 작은 readiness 요약을
  보관하고 전체 호출 표는 마지막 observation에만 남긴다(초기 handshake 제외).
- same-run 어댑터는 envelope와 runtime 최종 observation 일치·기존 SHA 바인딩을 확인한
  뒤 재판정한다. 개별 audio PASS도 플랫폼 PASS/exit0를 만들지 않는다(I6 범위).
  Linux 합성 JSON/정적 시험은 제품 증거가 아니다. D3/D5와 실제 native callback·RVA
  동작 검증은 t_78eb1710에 남아 있다. 새 manifest와 외부 pin은 최종 exact SHA에서 재생성한다.

## Verification

제품 QA 보고 진입은 [product-qa-entry.md](product-qa-entry.md)를 따른다.
`windows-product-qa.ps1 -Mode Report`는 비실행 보고이며 항상 제품 PASS 불가다.
`-Mode Run`은 기존 격리 시험기에 위임한다. I6 집계는 `--same-run` 바인딩과
최종 exact clean HEAD 검사 통과 뒤에만 수행한다. runtimeHeadProvenanceVerified와
isolationVerified/silentVerified/cleanupVerified가 모두 boolean true이고,
네 collector(API/audio/render/protocol)가 실제 증거 경로와 함께 모두 PASS이며,
runtime status가 정확히 PRODUCT_WINDOW_COMPLETE인 경우에만 플랫폼 PASS,
product_pass=true, productQA=PASS, exit 0을 허용한다. PowerShell도 같은 조건을
재검사한다. 하나라도 결손이면 기존 FAIL/NOT_RUN·exit 2/3을 유지한다.
runtime 관찰/fixture 성공이나 입력 product_pass=true는 이 조건을 대신하지 않는다.
windows_qa_receipt는 PASS 보고도 null이며 공식 발급은 hermes-windows qa만 한다.

미정 D1~D5는 실측 전까지 보존한다: D1 upstream 위치/기동과 Windows JVM,
D2 session 0 D3D9Ex와 app-tree dxwrapper, D3 관찰 창 내 오디오/Present/0x34 도달,
D4 0x30 본문 70 00의 복호 서버 로그(집계 PASS 조건 아님), D5 UI255의 추가 script
로드 영향. 실측은 기존 t_78eb1710만 수행한다. 이번 Linux 합성 판정 시험은 실제
제품 QA가 아니며 Windows/제품/fixture NOT_RUN, receipt null, 검증 완료·인수·병합
NOT_PERFORMED다. t_a1c38302를 fixture 근거로 자동 해제하지 않는다.

Portable focused tests: `python -m unittest discover -s tools/client -p 'test_qa*py' -v`.
Windows fixture matrix and final-head readback are recorded in
`/srv/hermes-workspace/projects/logh/results/t_2ff2d248-RESULT.json` and
`/srv/hermes-workspace/projects/logh/evidence/t_2ff2d248-*`.
All previous failed probes, earlier fixture evidence and source provenance remain.
