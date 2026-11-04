# 현재 호스트의 로컬 플레이 실행

작성: 최병호 · 2026-11-05

현재 로컬 시작 경로는 `E:\logh7\work\playable-20261025\start.ps1`이다.
이 파일은 `E:\logh7\work\playable-main-refresh-20261104\start.ps1`을 호출한다.
서버는 `127.0.0.1`에만 바인딩하며 게임 47900, 운영 47901, 업데이트 47902,
세션 47903 포트를 사용한다. 사용자 직접 플레이의 시작 순서는 다음과 같다.

1. PowerShell에서 서버를 실행한다. 이미 실행 중이면 다시 실행하지 않는다.

   ```powershell
   & E:\logh7\work\playable-20261025\start.ps1
   ```

2. 원하는 진영의 런처를 실행한다. 각 폴더의 `launcher.ini`가 기본값으로 읽힌다.

   | 진영 | 런처 | 시험 계정 | 저장 캐릭터·위치 |
   |---|---|---|---|
   | 제국 | `E:\logh7\work\playable-20261025\empire\logh7-launcher.exe` | `qa1024` | Nova · 발할라 |
   | 동맹 | `E:\logh7\work\playable-20261025\alliance\logh7-launcher.exe` | `play2` | 새봄 · 바라트 |

   두 로컬 시험 계정의 인증 문자열은 `dummy`이며 게임의 로그인 창에 입력한다.
   게임 시작 뒤 첫 번째 저장 캐릭터를 선택한다. 별도 `--config` 인자는 필요 없다.
   `--check`를 붙이면 게임을 실행하지 않고 설정·경로·오디오 출력만 확인한다.

3. 서버 종료는 서버를 실행한 PowerShell에서 Ctrl+C로 한다.

## 실행 파일과 저장 위치

서버는 현재 main `1de20d38`에 병합한 PR #242의 배포본
`E:\logh7\work\playable-main-refresh-20261104\releases\main-pr242-e271ed2c`를 사용한다.
시작할 때 `server_release.py classpath`가 릴리스의 80개 파일을 검증한다.
두 클라이언트는 같은 작업 디렉터리의 `client-empire`와 `client-alliance` 사본이며,
한글 생성·세션 종료 안내와 원본 시각 배율의 분모 절삭 보완을 포함한 호환 DLL을
적용했다. 두 런처의 `--check`를 다시 통과했다. 원본 게임 파일은 보존한다.

현재 저장은 그 디렉터리의 `state\characters.properties`, `state\world`,
`state\session`에 이어 쓴다. 원래 저장 파일은 기존 위치에 보존했고,
원래 시작 스크립트는 `source-config\start.ps1`에 남겼다.
새 구성의 오프라인 백업 `backup-local-entry`는 10개 파일의 검증을 통과했다.
세션 규칙은 원래 저장의 24배속을 유지한다.

## 확인한 실행 범위

두 런처를 각각 인자 없이 소유 비활성 데스크톱에서 실행해 기본 설정 읽기,
자식 게임 생성, 로그인, 제국 Nova·발할라와 동맹 새봄·바라트의 전략 화면을
확인했다(`launcher-empire-01`, `launcher-alliance-01`). 두 실행 모두 메모리 전용
무음, 호스트 포커스·커서 보존, 원본 EXE 해시 보존과 종료 후 Job0을 확인했다.
이어서 기존 시작 스크립트를 통해 재가동해 `/health`의 `200 ok`와 네 포트의
루프백 바인딩을 확인했다(`server-07.log`, `local-entry-health.json`).

한 판 완주·전체 명령/화면·물리 IME·온라인 운영의 남은 범위는
[실제 플레이 체크리스트](../IMPLEMENTATION-CHECKLIST.md)에서 계속 추적한다.
