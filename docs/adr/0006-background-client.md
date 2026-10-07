# ADR-0006: 비활성 Windows 데스크톱에서 클라이언트 실행

작성: 최병호 · 2026-10-07 · 상태: 채택

## 맥락과 결정

사용자가 HOI4 Breaking Point 팀의 백그라운드 게임 실행 방법을 받아 LOGH7에도 쓰고 VM을 생략하도록 지시했다. `AGENTS.md`의 D-1/§4에 있던 VM 전용 실행 제한을 이 결정으로 갱신한다. VM을 사용할 때의 D-6 헤드리스 CLI 규칙은 유지한다. `evidence:guess` (사용자 결정)

Breaking Point 「채팅 오케스트레이션 시작」(채팅 ID `01a11582-5ac7-7292-9a55-07bd9233e496`)에서 `BP_GUI_TEST`의 게임 PID에 대한 CLI 실행·캡처 기록과 `tools/bp_background_game.py`, `tools/bp_virtual_input.py`, `docs/branding/hud/BACKGROUND-GAME-CHECK.md`를 읽었다. 비활성 데스크톱, 사본 실행, 대상 창 `PrintWindow`, 데스크톱 핸들 유지 방식을 재사용한다. HOI4 전용 입력·사용자 문서 리다이렉션 DLL은 LOGH7에 이식하지 않는다. `evidence:client`

## 적용 범위

- 기본 실행: `work/<case>/`의 사본 → 고유 비활성 데스크톱 → `exe/` 작업 디렉터리 → localhost 인자 접속 → 대상 창 캡처 → 생성한 프로세스 종료. `evidence:guess`
- 원본 수정, 활성 데스크톱 전환, 전역 입력, 호스트 로캘·레지스트리·방화벽 설정 변경은 허용하지 않는다. `evidence:guess`
- 이 방식은 창과 입력을 분리한다. VM은 멀티플레이 시험에 필요할 때만 사용한다(사용자 추가 결정). 단일 클라이언트는 호스트 실행을 유지한다. `evidence:guess`

## 근거와 재검토

Windows는 [CreateDesktop](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-createdesktopw)으로 데스크톱을 만들고 [STARTUPINFO.lpDesktop](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/ns-processthreadsapi-startupinfow)으로 새 프로세스의 데스크톱을 지정한다. 입력 데스크톱으로 전환하는 함수는 호출하지 않는다. `evidence:client` (Windows API 및 재사용 코드)

실행 도구와 LOGH7 관찰 결과는 [백그라운드 실행](../ops/background-client.md)에 기록한다. 캡처가 검거나 초기화가 실패하면 성공으로 처리하지 않고 해당 원인을 확인한다. 이 실행 방식의 성공과 로그인·게임 기능의 성공은 따로 판정한다. `evidence:guess`
