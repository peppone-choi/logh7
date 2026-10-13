# CLOSED readiness 다음 검증 준비

작성: 최병호 · taskId: logh-readiness-preparation-20261013-1015

## 현재 판정

readiness **CLOSED**. 제품·게임·실제 오디오·런처 전체 **NOT_RUN**.
기준 commit `8074503a765bdf5a3d206242a0c468ba6b204762`;
원격 HEAD 조회값 `dcf0c578e4248fb86011009202e57db34feb292a`.
Windows runtime은 미커밋 상태이므로 저장소 코드와 같다고 주장하지 않는다.
이 변경은 승인 manifest도, gate 개방도, Windows 실행기도 아니다.
F1 44/44 및 이전 127은 과거 근거만 보존하며 수정·재실행하지 않는다.

## coverage와 두 번째 attach

[준비 계약](../../tools/qa/readiness-preparation.json)은 정확 원본 및 runtime7
hash와 무해 x86 delayed-load/COM/dependency·두 번째 attach 사례의 기대값을 고정한다.
원본 대상 hash는 `bd19263c10decc3d58373165a82d42a9267868400d407da87d5f4f4109ab6e16`.
게임 hash에 연결된 검증 계획이지 무해 fixture 결과로 게임 coverage를 승인하는 규칙은 아니다.

- delayed-load: handshake 때 DSOUND 미로드를 관찰한 뒤 소유 무해 fixture에서
  늦은 로드를 유발하고 최초/반복 synthetic Play 원본 호출 0을 확인한다.
- COM: 실제 endpoint/COM 서버를 열지 않고 activation 거부 및 synthetic vtable
  Start 원본 호출 0을 확인한다. 실제 DirectSound/WASAPI 증거와 구분한다.
- dependency: 무해 의존 모듈의 unsupported-load가 fatal/소유 handle stop을
  유발해야 한다. 실패 뒤 resume·capture fallback은 금지한다.
- 두 번째 attach: 동일 PID+생성시각에 audio session을 유지한 상태로
  [무해 payload](../../tools/qa/second-attach-fixture.js)를 두 번째 session에 로드한다.
  정상 marker, RPC 예외, audio session 예상외 detach를 별도 사례로 관찰한다.
  `capture-frame.js`는 원본 전용 offset 때문에 무해 fixture에 사용하지 않는다.
  이 payload는 render-capture 동등성이나 게임 렌더링을 입증하지 않는다.

향후 Windows 담당은 먼저 독립 리뷰된 `harmless-second-attach.exe` 생성기/driver와
실행 파일 hash를 준비해야 한다. 현재 변경은 실행 파일이나 driver를 제공하지 않으며
자동 attach도 하지 않는다. 기존 `logh_audio_adapter.py`는 최상위 실행 부작용 때문에
import 재사용하지 않는다. legacy `logh_prestart_readiness.py` 무인자 import 금지.

향후 실행 전 공유 GUI mutex, 기존 Steam/게임 guard, 검증된 비활성 desktop,
소유 E: TEMP, outer Job 5/inner Job 1, helper hash+부모 topology를 확인한다.
두 번째 attach가 helper 구성을 바꾸면 허용목록을 자동 확장하지 않고 CLOSED로 종료한다.
finally/watchdog는 소유 PID+생성시각/Job만 정리하고 프로세스·TEMP 부재를 확인한다.
cleanup 실패·timeout·예외는 PASS가 아니다. Steam15324와 사용자 desktop/foreground는
보존한다. 실제 Windows 실행 및 native coverage 증거 수집은 이번에 하지 않았다.

## QA 전용 수송

허용 경로는 기존 전용 계정의 `upload-qa-evidence` → `WINDOWS-QA-BUNDLE.zip`뿐이다.
`upload-resources` 및 `WINDOWS-BUNDLE.zip` 변경은 금지한다.
원본 `f1-upload.zip`은 74469 bytes, SHA256
`f68e06a3f5364770f9d4ed3a1552211992048165dea6665b94942fa265e05d4c`.
인증 파일·키·토큰을 복사하거나 명령행/로그에 출력하지 않는다.
전송 단계는 기존 인증 경로를 담당자가 사용하며 이 도구는 SSH/전송을 수행하지 않는다.

수신 직후 자신의 작업실에 QA ZIP만 복사하고 아래를 실행한다.

```sh
python3 tools/qa/verify_qa_bundle.py /absolute/own/workspace/WINDOWS-QA-BUNDLE.zip
python3 -m unittest discover -s tools/qa -p 'test_verify_qa_bundle.py' -v
```

검증기는 read-only이며 extraction/import를 하지 않는다. 전체 ZIP pin, 정확 16개
manifest 항목의 크기/SHA256, manifest 동등성, 추가/중복/누락 member를 검증한다.
정확 allowlist 및 바이트 고정은 인증/state/로그 등 추가 파일 유입을 거부한다.
이는 과거 검토된 바이트와의 동일성이지 임의 내용의 비밀 탐지 보증은 아니다.
MATCH여도 readiness CLOSED, upload/game/audio/launcher NOT_RUN을 출력한다.
수신 검증 결과만으로 현재 task의 end-to-end upload 성공을 주장하지 않는다.

## 이번 근거 및 인계

기존 verify README·원장·runtime/수송 관측 로그를 읽었다.
로컬 기존 QA ZIP은 위 크기/hash 및 manifest16과 MATCH했다. 새 upload는 NOT_RUN.
경량 수송 checker 회귀검사 8개 통과, JS `node --check` 및 `git diff --check` exit 0.
F1/127은 재실행하지 않았다.
빌드/무거운 시험·REA open·Windows 실행·Steam 조작·병합/배포는 NOT_RUN.
다음 담당 `logh-review`: 준비 계약/payload/수송 checker를 독립 검토한다.
blocker: 승인 manifest 없음, 실제 coverage/두 번째 attach driver와 Windows 증거 없음,
Steam 실행(이전 관측), C: 여유 부족(이전 관측), 새 QA upload 미실행.
