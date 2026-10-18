# LOGH 제품 QA 보고 진입 (fail-closed)

이 진입은 제품 QA 실행기가 아니라 기존 실행 증거를 Hermes 보고 형식으로
연결하는 비실행 어댑터다. 제품 실행/수집/판정 구현이 끝났다는 뜻이 아니다.
이번 연결만으로 공식 제품 QA를 재개하거나 PASS 영수증을 만들 수 없다.

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

`tools/client/windows-product-qa.ps1`은 Windows 전용, 제품 비실행 보고 진입이다.
필수 PowerShell 매개변수: `-Python`, `-Repo`, `-Head`(40자리 소문자 SHA),
`-Out`(새 파일). 선택: `-RuntimeReport`와 `-RuntimeReportSha256`(64자리 SHA256)을
함께 제공한다. 출력 부모는 미리 준비된 시험 전용 경로여야 한다.

PowerShell은 전용 Python을 `-I -B -X pycache_prefix=<새 전용 캐시>`로 호출한다.
`qa_product_report.py`는 외부 증거의 동일 바이트를 해시/파싱하며 중복 JSON 키,
알 수 없는 status, dirty checkout, SHA 불일치, 기존 출력 덮어쓰기를 거부한다.
프로세스 생성은 git 조회뿐이며 게임·fixture·기존 wrapper를 실행하지 않는다.
보고의 `runtimeExecutedByAdapter=false`는 런타임 증거에 게임 실행이 없었다는
주장이 아니다. 원자료를 조사할 때 `games_executed`와 controller 정보를 따로 본다.

stdout은 Hermes 계약의 JSON 보고 하나다. exit 3은 NOT_RUN, exit 2는 FAIL이다.
PASS/exit 0 경로는 없다. fixture PASS나 임의 입력의 product_pass=true도 제품
PASS로 승격하지 않는다. 네 제품 checks는 모두 NOT_RUN, 세 제품 안전 증명은
false다. 이 false는 안전성 실패의 관측이 아니라 제품 수준 증명 미제출이다.
checkout SHA와 runtime 파일 해시가 맞아도 그 실행의 head/run provenance가
증명되지 않으므로 `runtimeHeadProvenanceVerified=false`를 유지한다.
공식 영수증 필드는 null이다. 보고 연결을 제품 영수증 발급으로 혼동하지 않는다.

호출 예시(경로/SHA를 최종 인계 값으로 치환한 시험 전용 서버 스크립트에서 호출):

    & '<exact-copy>/tools/client/windows-product-qa.ps1' -Python '<private-python.exe>' -Repo '<exact-copy>' -Head '<final-40hex>' -Out '<new-private-report.json>' -RuntimeReport '<runtime-report.json>' -RuntimeReportSha256 '<external-64hex>'

현재 매개변수 없는 `hermes-windows qa --script`에는 위 호출을 담은 시험 전용
서버 driver를 전달한다. qa 도구에는 동일 final head/windows-repo를 전달한다.
이 어댑터의 NOT_RUN/FAIL은 거부 결과 확인용이지 제품 PASS 영수증이 아니다.
이전 manifest나 이전 SHA의 Windows 사본에 새 어댑터만 섞지 않는다.

## 다음 담당의 실행 조건과 미구현 범위

독립 리뷰는 새 정확 SHA에서 한다. Windows 제품 QA 카드는 기존 `t_78eb1710`
하나만 유지한다. 환경 카드 `t_a1c38302`도 자동 해제하지 않는다.

1. 먼저 안전한 읽기 전용 재확인으로 사용자 Steam/게임 부재, 공유 mutex,
   격리/무음/소유 프로세스 정리 보장을 확인한다. 사용자 프로세스는 중지하지 않는다.
2. 새 final SHA 전용 별도 사본과 전용 Python/전체 app-tree manifest를 준비하고
   새 해시를 외부 pin한다. 옛 db692c3 사본/manifest는 새 SHA의 증거가 아니다.
3. 실제 제품 check collector와 실행 오케스트레이션은 아직 없다. 런타임 호출과
   제품 API/audio/render/protocol의 실제 assertion, head/run/원본 hash 연결,
   안전 증거 검증을 구현·독립 리뷰한 뒤에만 이 보고 어댑터의 PASS 불가 정책을
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
