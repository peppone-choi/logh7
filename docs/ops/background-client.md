# 비활성 데스크톱 클라이언트 실행

작성: 최병호 · 2026-10-07 · 관련: ADR-0006 / D-7

## 받은 방법과 적용

Breaking Point 「채팅 오케스트레이션 시작」 채팅에서 `BP_GUI_TEST` 실행·캡처 기록과 `E:\hoi4-mods\Breaking-Point-New\tools\bp_background_game.py`, `bp_virtual_input.py`, `docs\branding\hud\BACKGROUND-GAME-CHECK.md`를 확인했다. 핵심은 `CreateDesktop`, `STARTUPINFO.lpDesktop`, 대상 창 `PrintWindow`, 자식 종료까지 데스크톱 핸들 유지다. HOI4 전용 DLL·설정은 가져오지 않았다. `evidence:client` (외부 채팅 기록 및 로컬 소스 읽음)

`tools/client/run-background.py`는 설치 추출본 전체를 새 `work/` 하위 디렉터리에 복사한 뒤 고유 `LOGH7_TEST_*` 데스크톱에서 실행한다. 클라이언트 작업 디렉터리는 `exe/`, 인자는 `127.0.0.1 <임시 포트> ginei00 1 dummy`다. 수신기는 loopback에만 바인딩하고 응답은 보내지 않는다. 생성한 Job에는 종료 시 자식 정리 옵션을 설정한다. 기본 15초 후 대상 창을 캡처하고 생성한 프로세스·핸들을 정리한다. `evidence:client` (실행기 및 실행 결과)

게임 화면 PNG·실행 사본·원바이트는 모두 Git 제외 `work/`에 남긴다. 실행기가 시스템 로캘·방화벽·원본·설치 레지스트리를 변경하지 않는다. 같은 사용자 권한으로 실행하므로 게임 자체의 파일·레지스트리 접근까지 차단하는 샌드박스는 아니다. `evidence:client` (실행기), `evidence:guess` (게임의 미관찰 접근 범위)

## 실행

기존 `C:\Users\user\AppData\Local\Programs\Python\Python311\python.exe`와 설치된 pywin32/Pillow를 사용했다. 추가 Python 설치는 하지 않았다. `evidence:client`

```powershell
& 'C:\Users\user\AppData\Local\Programs\Python\Python311\python.exe' -B `
  E:\logh7\tools\client\run-background.py `
  --source-root 'E:\logh7-original\extracted\install\ｱﾌﾟﾘｹｰｼｮﾝ実行可能ﾌｧｲﾙ' `
  --out E:\logh7\work\logh7-background-20261007\run-new `
  --seconds 15
```

기존 출력 경로에는 덮어쓰지 않는다. 결과 `run.json`과 대상 창 PNG를 확인한다. 종료 코드 0은 유효한 게임 크기 창 캡처와 완전한 첫 `0x34` 프레임·프로세스 정리·원본 클라이언트 해시 보존이 함께 확인된 경우다. 오류 대화상자 캡처만으로 성공을 판정하지 않는다. 로그인·키 교환 완료는 별도 시험이다. `evidence:client` (실행기 판정)

## 확인 결과

2026-10-07 시험에서 백그라운드 오류 창의 실행·캡처는 성공했지만 정상 게임 화면과 접속은 실패했다. run-02는 호환 가능한 Direct3D 장치를 찾지 못했다는 오류와 수신 0바이트를 남겼다. run-03에 [d3d8to9 공식 v1.16.0](https://github.com/crosire/d3d8to9/releases/tag/v1.16.0)을 사본에 추가해도 동일했다. 두 프로세스는 종료했고 원본 클라이언트 해시는 보존됐다. 결과와 필요한 캡처는 `work/logh7-background-20261007/run-02`, `run-03`에 있다. `evidence:client`

실험 DLL은 공식 릴리스의 digest를 확인해 `E:/Tools/d3d8to9/v1.16.0/d3d8.dll`로 받았다. `--d3d8-wrapper <경로>`로 실행 사본에만 추가하며 기본 의존성으로 지정하지 않는다.

다음 작업은 Direct3D 장치 열거·모드 선택의 실패 호출 확인이다. 원인은 아직 미확정이며 성공 전까지 기존 VM을 보존한다. 일본어 시스템 로캘이 필요한 시험에도 기존 VM을 사용할 수 있다.
