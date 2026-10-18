# LOGH 제품 QA Run/Report 진입 (fail-closed)

기본 `-Mode Report`는 제품을 실행하지 않고 기존 증거만 보고한다.
`-Mode Run`은 기존 격리 시험기를 호출하고 같은 실행의 증거를 보고한다.
제품 check collector는 아직 없으며, 어느 모드에도 PASS/exit 0 경로는 없다.
이 연결만으로 공식 제품 QA를 재개하거나 PASS 영수증을 만들 수 없다.

## 조사와 재사용

기준: db692c3df03264c86a5fce39b740cbc9cf0cac62.

- `windows-qa-preflight.py`: 비실행 사전검사, 항상 NOT_RUN/exit 3. 재사용한다.
- `qa_isolated_runner.py --product-logh`: `qa_prestart_runner.py`로 위임한다.
- `qa_prestart_runner.py`: 공유 mutex/격리 데스크톱/음소거 계측/소유 Job 정리를
  담당한다. 제품 정상 종료는 PRODUCT_OBSERVED/exit 2이고 제품 PASS가 아니다.
- `docs/ops/prestart-runtime.md`: 실제 API·DirectSound/WASAPI/winmm·렌더·프로토콜
  관찰 설계만 존재한다. 실행 가능한 제품 assertion collector는 없다.
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
PASS로 승격하지 않는다. 네 제품 checks는 모두 NOT_RUN이다. Report 모드의
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
upstream 서버 시작/인자는 I1에 포함하지 않는다(프로토콜 단계에서 연결).

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
  현재 payload에는 originals_called 관측 필드가 없어 실제 silentVerified는 false로
  남는다. 오디오 collector가 이를 제공하기 전 추정해서 0을 채우지 않는다.
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
3. 실행 오케스트레이션과 공통 증거 연결은 있으나 실제 제품 check collector는
   아직 없다. 제품 API/audio/render/protocol assertion을 구현·독립 리뷰한 뒤에만
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
