# LOGH 제품 QA Run/Report 진입 (fail-closed)

기본 `-Mode Report`는 제품을 실행하지 않고 기존 증거만 보고한다.
`-Mode Run`은 기존 격리 시험기를 호출하고 같은 실행의 증거를 보고한다.
프로토콜·오디오·렌더·API check collector가 구현되어 있으며, 어느 모드에도 제품 PASS/exit 0 경로는 없다.
이 연결만으로 공식 제품 QA를 재개하거나 PASS 영수증을 만들 수 없다.

## 조사와 재사용

기준: db692c3df03264c86a5fce39b740cbc9cf0cac62.

- `windows-qa-preflight.py`: 비실행 사전검사, 항상 NOT_RUN/exit 3. 재사용한다.
- `qa_isolated_runner.py --product-logh`: `qa_prestart_runner.py`로 위임한다.
- `qa_prestart_runner.py`: 공유 mutex/격리 데스크톱/음소거 계측/소유 Job 정리를
  담당한다. 제품 정상 종료는 PRODUCT_OBSERVED/exit 2이고 제품 PASS가 아니다.
- `docs/ops/prestart-runtime.md`: 실제 API·DirectSound/WASAPI/winmm·렌더·프로토콜
  관찰 설계와 collector를 구분한다. 최종 PASS 통합은 아직 없다.
- 보존 증거 `results/t_e7260e40-RESULT.json`, `results/t_a1c38302-RESULT.json` 및
  `evidence/t_a1c38302/readback.json`은 준비/fixture 결과다. evidence/의 기존
  PowerShell에서 `platforms`/`hermes-windows qa` 보고 진입은 발견되지 않았다.
  다른 PC 경로의 존재 여부를 새로 확인한 결과는 아니다.
- `WAIT_USER_GAME_OR_STEAM`은 이전 보존 관찰이다. 현재 PC 상태는 확인하지 않았다.

## 호출 계약

`tools/client/windows-product-qa.ps1`은 Windows 전용 Run/Report 진입이다.
필수 PowerShell 매개변수: `-Python`, `-Repo`, `-Head`(40자리 소문자 SHA),
`-Out`(새 파일). 선택: `-RuntimeReport`와 `-RuntimeReportSha256`(64자리 SHA256)을
함께 제공한다. 출력 부모는 미리 준비된 시험 전용 경로여야 한다.

PowerShell은 전용 Python을 `-I -S -B -X pycache_prefix=<새 전용 캐시>`로 호출한다.
`qa_product_report.py`는 외부 증거의 동일 바이트를 해시/파싱하며 중복 JSON 키,
알 수 없는 status, dirty checkout, SHA 불일치, 기존 출력 덮어쓰기를 거부한다.
Report 모드의 프로세스 생성은 git 조회뿐이며 게임·fixture를 실행하지 않는다.
보고의 `runtimeExecutedByAdapter=false`는 런타임 증거에 게임 실행이 없었다는
주장이 아니다. 원자료를 조사할 때 `games_executed`와 controller 정보를 따로 본다.

stdout은 Hermes 계약의 JSON 보고 하나다. exit 3은 NOT_RUN, exit 2는 FAIL이다.
PASS/exit 0 경로는 없다. fixture PASS나 임의 입력의 product_pass=true도 제품
PASS로 승격하지 않는다. 검증된 same-run 증거가 없는 제품 checks는 NOT_RUN이다. Report 모드의
안전 증명과 `runtimeHeadProvenanceVerified`는 false다. Run 모드는 아래의
같은 실행 바인딩을 통과한 뒤에만 안전 증거를 계산한다. false는 안전성 실패의
관측일 수도, 증명 미제출일 수도 있으므로 원자료를 함께 확인한다.
공식 영수증 필드는 null이다. 보고 연결을 제품 영수증 발급으로 혼동하지 않는다.

호출 예시(경로/SHA를 최종 인계 값으로 치환한 시험 전용 서버 스크립트에서 호출):

    & '<exact-copy>/tools/client/windows-product-qa.ps1' -Python '<private-python.exe>' -Repo '<exact-copy>' -Head '<final-40hex>' -Out '<new-private-report.json>' -RuntimeReport '<runtime-report.json>' -RuntimeReportSha256 '<external-64hex>'

현재 매개변수 없는 `hermes-windows qa --script`에는 위 호출을 담은 시험 전용
서버 driver를 전달한다. qa 도구에는 동일 final head/windows-repo를 전달한다.
이 어댑터의 NOT_RUN/FAIL은 거부 결과 확인용이지 제품 PASS 영수증이 아니다.
이전 manifest나 이전 SHA의 Windows 사본에 새 어댑터만 섞지 않는다.

## 다음 담당의 실행 조건과 미구현 범위

### Run 모드와 server driver

추가 필수 인자: `-Manifest`, `-ManifestSha256`, `-AppRoot`, `-TempParent`.
선택: `-Port`(1024..65535, 기본 20000), `-ObserveSeconds`(1..25, 기본 5),
`-Timeout`(2..30, 기본 30). ObserveSeconds는 Timeout보다 작아야 한다.
Out 부모는 사전에 준비한 전용 출력 경로이며 checkout 밖에 둔다.

1. 어댑터 `--check-only`로 사전 HEAD/clean을 검사한다(제품 실행 없음).
2. 새 `run-<uuid>`와 빈 캐시를 생성한다. 전용 runspace가 Python을 직접 호출한다:
   `-I -S -B -X pycache_prefix=<new> qa_isolated_runner.py --product-logh ...`.
   runner stdout/stderr는 `runner.stdout`/`runner.stderr`로만 리다이렉트한다.
   supervisor는 명시한 python.exe의 private venv prefix를 복원하고 manifest를
   먼저 검증한 뒤 고정된 site-packages/win32 경로만 추가한다. .pth/site 초기화는 하지 않는다.
3. 대기 상한은 timeout+40초다. 초과하면 cancel 파일을 쓰고 실행 파이프라인을
   중단 요청한다. FAIL과 cleanupVerified=false로 보고하며, 중단 요청 자체는
   소유 트리 종료 증명이 아니다. 증거를 보존하고 실제 종료를 별도로 확인한다.
4. `runtime.json` SHA256, 사후 HEAD/clean, `invocation.json`을 기록한다.
   runner exit 2/3만 받는다. 제품 runner exit 0은 FAIL이다.
5. 어댑터 `--same-run <run>`에는 그 디렉터리의 runtime.json과 외부 manifest pin을
   넘긴다. `<run>/product-report.json`과 호출자의 새 Out에 같은 보고를 보존하며
   어댑터 JSON 하나만 stdout으로 출력한다.

`tools/client/make-logh-qa-driver.py`는 Linux 표준 라이브러리만 사용해 한 줄짜리
리터럴 호출 driver를 만든다. `--head --copy --python --manifest --manifest-sha256
--app-root --temp-parent --out-root --output`을 받는다. port/observe/timeout은 선택이다.
Windows 경로는 E:/HermesQA 하위만 허용하며 따옴표·제어문자·경로 이탈을 거부한다.
output은 /srv/hermes-workspace/projects/logh 아래의 새 .ps1이며 덮어쓰지 않는다.
생성은 실행 승인이 아니다. 이 driver를 기존 `hermes-windows qa --script`에 전달한다.
I2에서 선택 `--upstream-port`/`-UpstreamPort`를 연결했다. 서버를 기동하지 않으며
기존 loopback upstream에만 연결한다. 생략하면 응답을 만들지 않고 NOT_RUN으로 남긴다.

### I2 초기 로그인 프로토콜 collector

`qa_protocol.py`의 stdlib thread는 supervisor(대상 Job 밖)에서 target controller를
Resume하기 전에 `127.0.0.1:<manifest target port>`에만 bind한다. 다른 bind 주소와
동일한 recorder/upstream 포트는 거부한다. upstream도 127.0.0.1로만 연결한다.
수락 연결은 한 개뿐이며 두 번째 연결은 닫고 FAIL로 기록한다. 수신 바이트만 그대로
전달하며 응답 생성·키 교환 구현·복호화·서버 기동은 하지 않는다.

owned_temp/evidence/에 다음 파일을 보존한다:

- protocol-c2s.bin, protocol-s2c.bin: 각각의 방향에서 받은 원바이트.
- protocol.jsonl: 수신 청크의 t_mono/dir(c2s 또는 s2c)/offset/len. offset은 방향별이다.
- protocol.json: run_id/manifest_sha256/config_sha256/target 바인딩, recorder/upstream
  포트, 연결 수, 오류, 클라이언트 EOF 여부(client_closed), 세 companion SHA256, 판정과 관찰 프레임 목록.

모든 파일을 cleanup 후 evidence_files에 등록한다. 바이너리와 JSONL은 원자료이므로
바인딩을 삽입하지 않고 protocol.json의 companion 해시 및 evidence_files로 결합한다.
어댑터는 기존 same-run 검증을 통과한 뒤 companion 해시와 target 인자의 고정 포트를
재검사하고 원바이트/타임라인에서 판정을 다시 계산한다. 저장된 판정도 같아야 한다.
누락·해시/바인딩/판정 불일치는 FAIL이다. same-user 동시 변조 저항은 주장하지 않는다.

초기 로그인은 session-login.md의 length:BE16(자신 제외), type:BE16만 사용한다.
clear_header는 없다. TCP 청크 분할/병합과 무관하게 완전한 프레임의 관찰 순서를 본다.

- PASS: 한 연결에서 C2S 0x34 → S2C 0x35 → C2S 0x36 → C2S 외부 type 0x30 ≥1.
  0x30 본문 복호·0x7000·계정 로그인 성공은 주장하지 않는다.
- NOT_RUN: upstream 없음, 연결 없음 또는 종료되지 않은 정상 부분 교환(첫 0x34 포함).
  첫 0x34만 관찰한 뒤 클라이언트가 종료해도 NOT_RUN이다.
- FAIL: 손상/잘린 길이, 순서·type 위반, 추가 연결, upstream 연결 실패/단절,
  upstream이 있는 상태에서 0x35 이후 첫 C2S 0x30 전에 클라이언트 단절,
  capture 한도 초과 또는 전달 실패. target의 선행 연결 종료 뒤에는 upstream도 닫는다.
  첫 C2S 0x30 이후 클라이언트 정상 종료는 protocol PASS를 유지한다.
  client_closed도 원자료 재계산에 사용하며 저장 판정과 일치해야 한다.
  한계(R-UPEOF-1): upstream EOF는 stage 4 이후에도 FAIL이다. redirect 직후 서버가
  먼저 닫는 정상 흐름인지 여부는 실제 제품 시험에서 확인해야 하며 여기서는 변경하지 않는다.

전체 원바이트 16 MiB, 청크 16,384개, 프레임 4,096개를 넘으면 FAIL이다.
각 소켓 연결/전송 timeout은 0.2초이며 부하/역압으로 전달하지 못해도 FAIL이다.
소유 트리 정리 뒤 thread를 멈추며 이미 읽을 수 있는 바이트는 소진한다.
protocol check의 PASS만 보고해도 platforms.windows는 NOT_RUN/FAIL이며 exit 0은 없다.
I6 통합은 후속 단계다. Windows native 실행은 이번 단계 NOT_RUN이다.

### 원본 렌더 collector (I4)

`qa_render_x86.js`는 silence guard와 같은 Frida session의 별도 script다.
guard ready 확인 뒤, 마지막 동기 guard 확인/ResumeThread 전에 설치한다.
Direct3DCreate9/Ex → CreateDevice/Ex → Present/PresentEx를 관찰한다.
Present 직전에 대상 내부 GetBackBuffer → GetRenderTargetData → system-memory
surface LockRect로 읽는다. 활성 화면·창 캡처, 포커스 가장, 입력, Reset 변경은 없다.
장치/Present의 실패 HRESULT와 readback/hook/script 예외는 FAIL이다.
D3D9 경로가 없거나 Present 미도달이면 NOT_RUN이며 D3D8에 대체 hook을 설치하지 않는다.

첫 유효 프레임과 관찰 종료 직전 마지막 backbuffer를 대상 script의 두 슬롯에
보관한다. 종료 RPC는 신규 수집을 닫고 진행 중 Present를 최대 1초 기다린 뒤,
최대 두 raw 메시지와 완료 marker를 전송한다. controller는 최대 3초 drain 확인 후
소유 프로세스 정지 전에 `owned_temp/evidence/`에 다음을 저장한다.

- render-first.bgrx / render-first.json: 첫 유효 프레임이 있을 때만.
- render-last.bgrx / render-last.json: 지원 BGRX backbuffer가 있을 때만(균일/작은 프레임도 보존).
- render.json: 관찰 횟수·오류·슬롯 메타. 미도달이어도 생성하며 실패 RPC는 FAIL 근거다.

JSON은 run_id/manifest_sha256/config_sha256/target과 format,width,height,pitch,
interface(D3D9/D3D9Ex),present_hr,frame_no,raw_sha256을 결합한다. 기존 supervisor가
cleanup 뒤 모든 파일을 evidence_files에 등록한다. 어댑터는 same-run 바인딩과
전체 해시를 확인한 후 raw 바이트로 판정을 다시 계산한다. 누락/불일치는 FAIL이다.
신규 config의 render_collector=true인데 render.json이 없어도 FAIL이다.

순수 함수 `judge_render`의 PASS 기준: format 21/22, 400x300~4096x4096,
160x90 축소 RGB 채널 중 최대 모집단 표준편차 >3인 프레임 ≥1과 실패 Present 0건.
추가 런타임 Pillow 의존성 없이 script/controller 모두 run-background의 RGB
Pillow 기본 BICUBIC 축소를 재현한다: a=-0.5, 픽셀 중심 매핑, 축소 배율로
넓힌 support, 경계에서 정규화한 signed 22-bit 계수, 가로→세로 2-pass의
각 pass 뒤 반올림·0..255 clamp를 적용한 8bit 출력의 통계다.
Linux 회귀는 Pillow를 기준으로 잡음·1px 패턴·임계값 양쪽·색상/행 padding을
Python과 Node에서 비교한다(|표준편차 차이|<0.05, >3 판정 일치).
X/alpha와 행 padding은 통계에서 제외한다. 균일/작은 프레임만 있으면 NOT_RUN이다.
지원 raw layout은 format 21/22, 1..4096 크기, width*4..32768 pitch이다.
미지원 포맷/크기는 raw 슬롯을 만들지 않는다. 첫 유효 프레임 뒤 마지막 raw를
얻지 못하면 과거 프레임을 마지막인 것처럼 재사용하지 않고 FAIL로 판정한다.
multisample resolve는 구현하지 않았고
GetRenderTargetData 실패를 숨기지 않는다. D2(session 0/D3D9Ex·dxwrapper)는 실측 미정이다.
렌더 check PASS는 플랫폼 PASS/exit 0이나 공식 영수증을 만들지 않는다.

Linux 합성·정적 시험(실제 DLL/게임을 로드하지 않음):

    python3 -B -m unittest discover -s tools/client -p 'test_qa_render.py' -v
    python3 -B -m unittest discover -s tools/client -p 'test_qa_render_criterion.py' -v
    node tools/client/test_qa_render.js

### 외부 영향 API collector (I5)

`qa_api_x86.js`는 같은 guarded Frida session의 별도 관찰 script이며 Resume 전에
모든 export hook을 설치한다. 원본 API 호출·인자·반환을 바꾸거나 대신 호출하지 않는다.
차단은 기존 inner Job의 UI255/active limit 1과 silence guard가 담당한다.
관찰 목록(19개)은 다음으로 고정한다.

- ws2_32: connect, WSAConnect. sockaddr의 family/address/port와 실제 반환 기록.
- kernel32: CreateProcessA/W.
- advapi32: CreateProcessAsUserA/W, CreateProcessWithLogonW, CreateProcessWithTokenW.
- shell32: ShellExecuteA/W, ShellExecuteExA/W.
- user32: SwitchDesktop, SetForegroundWindow, BlockInput, SendInput,
  keybd_event, mouse_event, SetCursorPos.

각 호출은 호출자 module/RVA/모듈 크기, 시도와 완료 여부, 실제 반환을 기록한다.
hook별 api/module/export address/installed 및 오류를 함께 보존한다. 모듈을 추가로
로드하지 않으며 시작 시 모듈·export 부재/설치 실패는 FAIL이다. target 내부의
script RPC로만 수집하며 호스트 UI/입력 API를 호출하지 않는다.

관찰 창 종료에서 렌더 drain 뒤, 소유 handle 정지 직전에 `apistatus()`를 읽고
`owned_temp/evidence/api.json`에 run_id/manifest_sha256/config_sha256/target을
결합한다. cleanup 뒤 기존 supervisor가 evidence_files에 파일/SHA를 등록한다.
어댑터는 같은 실행의 전체 목록/해시/바인딩을 확인한 뒤 controller observation과
비교하고 `judge_api`로 판정한다. config의 protocol_port는 pinned target_args와 같아야 한다.
신규 config api_collector=true인데 파일이 없으면 FAIL이다.

- PASS: 전체 hook 설치, 관찰 창 완료, 모든 연결 시도가 정확히 127.0.0.1:<pin>,
  프로세스/상호작용 성공 반환 0건. API 호출 0건도 PASS와 양립한다.
- FAIL: 연결 실패 반환이어도 다른 endpoint로 시도, CreateProcess*/ShellExecuteEx*의
  nonzero, ShellExecuteA/W >32, BOOL 상호작용 nonzero 또는 SendInput >0,
  설치/읽기/script 오류, 미완료 호출, 불명 호출자, 8192개 기록 한도 초과.
- keybd_event/mouse_event는 void여서 성공/실패 반환이 없다. 임의 EAX를 실패로
  해석하지 않고 return_value=null로 기록하며 호출되면 FAIL(거부 증명 불가)이다.
- NOT_RUN: 제품 관찰 창 미완료. retained handle로 조기 종료를 확인했고 최종 RPC가
  불가능하면 설치 시 부분 receipt에 incomplete_reason을 붙인다. 이는 마지막 호출의
  증거가 아니며 절대 PASS로 승격하지 않는다. 설치/script 오류는 계속 FAIL이다.

이는 기능 정확성·커널 내부/직접 syscall·열거하지 않은 모든 API의 검증이 아니다.
마지막 snapshot 이후 정리 구간의 활동을 증명하지 않으며 Job/guard는 대상 종료까지
유지한다. 모듈 가용성·Frida native hook 동작·실제 제품 행동은 Windows NOT_RUN이다.
manifest inventory OWN_CODE에 qa_api.py/qa_api_x86.js를 포함한다. 독립 리뷰의 final
SHA로 Windows 사본·manifest·외부 pin을 다시 생성해야 하며 여기서는 생성/실행하지 않는다.
API check PASS도 플랫폼 PASS/exit 0이나 공식 영수증을 만들지 않는다(I6).

Linux 합성/정적 시험:

    python3 -B -m unittest discover -s tools/client -p 'test_qa_api.py' -v
    node tools/client/test_qa_api.js

### 같은 실행 바인딩과 안전 계산

runtime 파일 위치, 호출 전후 clean HEAD, manifest SHA, 실제 controller-config SHA와
내용, config의 runtime 보고 경로, 원본 exe 해시, owned_temp UUID, evidence_files의
전체 목록/해시를 대조한다. JSON 증거의 run_id/manifest/config/target도 일치해야 한다.
누락/불일치는 FAIL이고 provenance=false다. WAIT_*는 대상 생성 전 정상 대기이므로
NOT_RUN이며 provenance/안전 필드는 false다. 해시는 같은 사용자 권한의 동시 변조를
막는 인증이 아니다. 원자료가 없는 과거 Report 입력은 실행 head 증명이 아니다.

- isolationVerified: before/after 격리 세션·window station 일치, 세션 집합 불변,
  모든 세션(0 포함)의 legacy mutex 획득, private desktop 일치, 모든 UI255 receipt,
  protected_processes 비어 있음, reference 보존, 실제 Resume 근거가 모두 필요하다.
- silentVerified: ready receipt와 Resume 직전 동기 receipt, 관찰 동안 1초 이하 간격,
  guard error/audio-fatal 없음, originals_called=0, 정지 후 detach 시각이 필요하다.
  I3 오디오 collector의 originals_called 관측을 사용하며 관측 누락을 0으로 채우지 않는다.
- cleanupVerified: inner/outer Job active=0, 양쪽 cleanup, 소유 PID+생성시각의 retained
  handle signalled, 잔존 PID 없음, protected_processes 사후 재조회 일치가 필요하다.

제품 전용 `--observe-seconds N`은 N초까지 살아 있으면 소유 handle로 정지하고
PRODUCT_WINDOW_COMPLETE/exit 2를 기록한다. 조기 종료는 exit code와 COMMAND_FAILED다.
이 상태는 PASS도 FAILURES도 아니다. 관찰 인자 없는 기존 fixture/제품 경로는 유지한다.
owned_temp/evidence/와 cleanup 후 evidence_files[{path,sha256}]를 보존한다.

독립 리뷰는 새 정확 SHA에서 한다. Windows 제품 QA 카드는 기존 `t_78eb1710`
하나만 유지한다. 환경 카드 `t_a1c38302`도 자동 해제하지 않는다.

1. 먼저 안전한 읽기 전용 재확인으로 사용자 Steam/게임 부재, 공유 mutex,
   격리/무음/소유 프로세스 정리 보장을 확인한다. 사용자 프로세스는 중지하지 않는다.
2. 새 final SHA 전용 별도 사본과 전용 Python/전체 app-tree manifest를 준비하고
   새 해시를 외부 pin한다. 옛 db692c3 사본/manifest는 새 SHA의 증거가 아니다.
3. 실행 오케스트레이션과 프로토콜 collector는 있으나 실제 제품 실행 증거는 없다.
   collector 독립 리뷰와 최종 통합 구현·독립 리뷰 뒤에만
   이 보고 어댑터의 PASS 불가 정책을
   바꿀 수 있다. 제품을 띄워 관찰만 하는 것을 완성된 QA로 제출하지 않는다.
4. API/오디오/렌더/프로토콜 및 isolation/silent/cleanup의 실제 근거가 모두
   있을 때만 hermes-windows qa로 공식 exact-SHA 영수증을 확보한다.
   검증 완료·인수·병합은 그때까지 NOT_PERFORMED다.

기존 인계 참고(이번 단계에서 Windows 확인/실행 안 함):

- copy: E:/HermesQA/logh/db692c3df03264c86a5fce39b740cbc9cf0cac62
- Python: E:/HermesQA/runners/t_2ff2d248-v1/venv/Scripts/python.exe
- manifest: E:/HermesQA/transfers/t_a1c38302/product-manifest.json
- manifest SHA256: 93bf17b4fdb10b9cac086199a371436f9d9f6dd40e76edf4a5aac0b2703b9d69
- app: E:/HermesQA/transfers/t_faccee24/product-app

Linux 회귀는 합성 보고 어댑터 시험일 뿐 제품 검증이 아니다:

    python3 -B -m unittest discover -s tools/client -p 'test_qa_product_report.py' -v

PowerShell native 실행, Windows 제품 QA, 공식 영수증, 인수·병합: NOT_RUN.
