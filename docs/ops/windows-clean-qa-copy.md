# 정확 SHA Windows QA 사본 준비

작성: 최병호

E:\logh7은 사용자 dirty 원본이다. 이 절차는 원본에 fetch/reset/clean/checkout,
파일 쓰기, untracked 코드 복사·실행을 하지 않는다. 제품 QA는 NOT_RUN이다.

## 사본 생성 계약

1. 승인한 Linux task worktree에서 커밋한 뒤 `git bundle create <새 파일> HEAD`로
   완전 bundle을 만든다. HEAD는 실제 존재하는 lowercase 40자리 commit SHA여야 한다.
   `sha256sum <bundle>` 값을 함께 전달한다. bundle과 준비 스크립트는 새 task 전용
   Windows 전송 폴더에 놓고 기존 파일을 덮어쓰지 않는다.
2. `hermes-windows exec --project logh`로 Windows Python 3.11에서 아래 스크립트를
   실행한다. GUI/게임 실행 및 공식 `qa` 호출은 하지 않는다.

       python -I -B prepare-windows-qa-copy.py --head <SHA> --windows-repo E:/HermesQA/logh/<SHA> --bundle <bundle> --bundle-sha256 <SHA256> --report <새 외부 JSON 경로>

   기본 `--original E:/logh7`, `--qa-root E:/HermesQA/logh`를 유지한다.
   SHA 형식·bundle digest·bundle의 HEAD commit·사본 clean 상태를 검사한다.
   원본/중첩 경로, 다른 이름, 기존 디렉터리(비어 있어도)를 거부한다.
   git global/system config, hooks, fsmonitor, external diff/textconv와 자동 CRLF 변환을
   비활성화하며 submodule/LFS/설치·런타임 코드를 실행하지 않는다.
   실패한 사본은 삭제하지 않고 남기며 재사용하지 않는다.
3. JSON `COPY_READY` 및 정확한 `copyHead`, 빈 `copyStatus`,
   `originalUnchanged=true`, `userProcessesPreserved=true`를 확인한다.
   원본 HEAD/porcelain 원바이트(statusHex)/dirty 파일 SHA256을 전후 기록한다.
   비추적 파일은 해시만 읽는다. 삭제 파일은 null로 남긴다.
   Steam 계열/HOI4/G7/LOGH 기존 프로세스의 PID·생성시각을 전후 비교하며
   자연 종료도 보존 확인 실패로 보수적으로 처리한다. 이 스크립트는 어떤
   프로세스도 종료하지 않으며 화면/입력/오디오 API를 호출하지 않는다.
   임의의 모든 사용자 프로세스 생존을 증명하는 일반 OS 감시기는 아니다.

## 어댑터 인계

기존 `tools/client/windows-qa-preflight.py`를 재사용한다. 새 경로를 명시한다.

    python -I -B <검증한 어댑터> --windows-repo E:/HermesQA/logh/<SHA> --head <SHA> --source-exe <읽기 전용 원본 exe> --inspect-only --out E:/HermesQA/logh/<SHA>/work/<새 case>

`--windows-repo`는 `--repo`의 별칭이다. `--inspect-only`는 기존 고정
E:/logh7 audio_guard import나 wrapper를 전혀 실행하지 않는다. 종료 코드 3과
productQA/platform NOT_RUN, isolation/silent/cleanup=false가 정상 진단 결과다.
검사한 어댑터를 외부 전송 경로에서 실행할 때에도 코드 SHA256을 먼저 대조한다.

기존 `--owned-wrapper` 모드는 wrapper와 전이 audio_guard의 기존 고정 해시
검사를 유지하며 dirty/다른 SHA checkout이면 wrapper를 실행하지 않는다.
이 모드는 원본 비추적 파일 import에 의존하므로 새 clean-copy 인계에는 사용하지 않는다.

격리 실행기 소유자는 별도 execution-support이다. 통합 후 실행기는 명시적인
`--repo <새 사본> --report <새 보고서>`와 검토된 manifest/전이 코드 해시를
fail-closed 검사해야 한다. 본 어댑터는 미검토 실행기의 경로나 해시를 임의로
신뢰하지 않는다. 실행기 통합·독립 리뷰 전에는 fixture도 본 어댑터로 실행하지 않는다.
OpenInputDesktop 실패 우회, 공유 mutex/무음 보호 완화, 게임 실행은 금지한다.

COPY_READY나 inspect-only 결과는 실제 상호운용, 무음·격리·정리 제품 검증,
공식 Windows QA 영수증 또는 인수·병합 승인이 아니다. 최종 통합 SHA가 바뀌면
그 SHA로 새로운 사본과 실제 제품 QA가 필요하며 과거 PR46 근거를 전용하지 않는다.
