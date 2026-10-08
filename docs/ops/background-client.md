# 비활성 데스크톱 클라이언트 실행

작성: 최병호 · 2026-10-07 · 관련: ADR-0006 / D-7

## 받은 방법과 적용

Breaking Point 「채팅 오케스트레이션 시작」 채팅에서 `BP_GUI_TEST` 실행·캡처 기록과 `E:\hoi4-mods\Breaking-Point-New\tools\bp_background_game.py`, `bp_virtual_input.py`, `docs\branding\hud\BACKGROUND-GAME-CHECK.md`를 확인했다. 핵심은 `CreateDesktop`, `STARTUPINFO.lpDesktop`, 대상 창 `PrintWindow`, 자식 종료까지 데스크톱 핸들 유지다. HOI4 전용 DLL·설정은 가져오지 않았다. `evidence:client` (외부 채팅 기록 및 로컬 소스 읽음)

`tools/client/run-background.py`는 `work/` 사본을 고유 `LOGH7_TEST_*` 데스크톱에서 실행한다. 작업 디렉터리는 `exe/`, 인자는 `127.0.0.1 <임시 포트> ginei00 1 dummy`다. dxwrapper의 D3D8→D3D9Ex 변환과 창 모드를 적용한다. Frida는 소유 프로세스의 포커스 조회에 자기 창을 반환하고 렌더링 버퍼만 읽는다. 실제 입력 데스크톱이나 호스트 포커스는 전환하지 않는다. 기본 15초 뒤 생성한 프로세스·자식·핸들을 정리한다.

게임 화면 PNG·실행 사본·원바이트는 모두 Git 제외 `work/`에 남긴다. 실행기가 시스템 로캘·방화벽·원본·설치 레지스트리를 변경하지 않는다. 같은 사용자 권한으로 실행하므로 게임 자체의 파일·레지스트리 접근까지 차단하는 샌드박스는 아니다. `evidence:client` (실행기), `evidence:guess` (게임의 미관찰 접근 범위)

## 실행

기존 Python과 설치된 pywin32/Pillow/Frida를 사용한다. [dxwrapper v1.8.8600.25 공식 릴리스](https://github.com/elishacloud/dxwrapper/releases/tag/v1.8.8600.25)는 `E:\Tools\dxwrapper\v1.8.8600.25\unpacked`에 있으며 릴리스 digest와 대조했다. DLL은 실행 사본에만 적용하고 Git에 넣지 않는다.

```powershell
& 'C:\Users\user\AppData\Local\Programs\Python\Python311\python.exe' -B `
  E:\logh7\tools\client\run-background.py `
  --source-root 'E:\logh7-original\extracted\install\ｱﾌﾟﾘｹｰｼｮﾝ実行可能ﾌｧｲﾙ' `
  --instance-root E:\logh7\work\logh7-background-20261007\run-03\client `
  --dxwrapper-dir E:\Tools\dxwrapper\v1.8.8600.25\unpacked `
  --out E:\logh7\work\logh7-background-20261007\run-new `
  --seconds 15 --render-capture
```

사본이 없으면 `--instance-root`를 생략해 최초 한 번 복사한다. 이후에는 같은 사본을 재사용하고 결과 경로만 새로 지정한다. 실행 중인 localhost 스텁과 실제 교환하려면 `--upstream-port 47900`을 추가한다. 종료 코드 0은 게임 크기의 유효한 렌더링 캡처·완전한 첫 `0x34`·시험 종료 전 프로세스 생존·생성한 프로세스 정리·원본 해시 보존을 함께 확인한 경우다.

## 소리를 들리지 않게 실행

2026-10-08 사용자 결정에 따라 기본 실행은 게임 PID의 Windows 오디오 세션만 음소거한다. `audio_session.py`가 활성 출력 장치에서 소유 PID와 정확히 일치하는 단일 프로세스 세션을 찾아 [ISimpleAudioVolume.SetMute](https://learn.microsoft.com/en-us/windows/win32/api/audioclient/nf-audioclient-isimpleaudiovolume-setmute)를 적용한다. 게임의 재생·오디오 버퍼 처리와 출력 장치는 유지한다. 다른 앱·스피커 전체 볼륨은 변경하지 않는다. 세션 생성 뒤 발견하는 방식이므로 최초 생성과 음소거 적용 사이의 짧은 간격까지 제거하는 기능은 아니다.

게임 종료 후 해당 세션의 기존 음소거 상태를 복원한다. `--audible`을 명시하면 실행기의 음소거 제어를 생략한다. 기본 모드의 종료 코드 0에는 **음소거 상태에서 활성 오디오 세션 관측 및 기존 상태 복원**도 포함된다. 추가 Python 패키지 설치 없이 Windows Core Audio COM API를 호출한다.

실제 원본 사본 시험에서 소유 PID의 세션 1개를 음소거했고, 활성 상태와 양수 오디오 레벨(최대 약 0.40)을 관측했다. 렌더링·첫 0x34 송신·프로세스 정리도 통과했다. 필요한 결과는 `work/logh23-host-20261008/audio-01/run.json`에 있다. `evidence:client`

## 확인 결과

### 일반 로그인 UI 시험

localhost 스텁에 합성 계정 `ginei00=dummy`를 등록한 뒤 위 실행 명령에 `--upstream-port 47900 --ui-login`을 추가하면 정상 UI에 계정·인증 문자열을 입력한다. 실패 대조에는 `--test-credential wrong`을 더한다. `--test-account`/`--test-credential`은 공백 없는 ASCII 합성 값만 지원하며 실제 계정용 런처 기능이 아니다.

UI 모드는 소유한 `AfxFrameOrView42s` 창에만 문자 메시지를 보내고, Frida가 소유 프로세스의 `GetAsyncKeyState`/`GetKeyState`에 가상 Tab·Enter 상태를 제공한다. 실제 키보드 입력이나 데스크톱을 전환하지 않는다. 마지막 렌더링 화면을 같은 `render-frame.png`에 저장하고 원래 오류 UI 콜백에서 받은 코드를 `login_failure_code`로 기록한다. 2026-10-08 일반 UI의 `wrong` 입력·실제 0x7002/code1·오류창·음소거 상태의 활성 오디오 관측을 함께 통과했다. `evidence:client`

2026-10-07 호스트 시험에서 실제 게임 렌더링과 localhost 접속을 확인했다. 기존 Kotlin 스텁과 `0x34→0x35→0x36` 키 교환 및 첫 로그인 요청 전달도 성공했다. run-16/18의 종료 코드는 0이며 원본 클라이언트 해시를 보존하고 시험 프로세스를 종료했다. 결과와 필요한 화면은 `work/logh7-background-20261007/run-16`, `run-18`에 있다. **전체 로그인·캐릭터 생성·플레이 완료는 아니다.** 스텁에 시험 계정이 등록되지 않아 로그인 거절 응답을 받았다. `evidence:client`

일반 D3D9는 비활성 데스크톱에서 장치 기능 조회를 거부했고, D3D9Ex로 바꾸자 통과했다. `PrintWindow`는 게임의 Direct3D 픽셀을 가져오지 못해 렌더링 버퍼를 직접 캡처했다. 창 캡처 실패와 게임 렌더링 실패를 구분한다.

2026-10-08 로비 전환 시험에서 dxwrapper v1.8.8600.25의 창 모드 Reset이 `D3DERR_INVALIDCALL`로 실패해 이전 크기의 버퍼가 남는 문제를 확인했다. 해당 [릴리스 소스](https://github.com/elishacloud/dxwrapper/blob/v1.8.8600.25/d3d9/IDirect3DDevice9Ex.cpp)의 Reset 경로는 창 모드에도 전체화면 구조 포인터를 넘긴다. 소유 프로세스의 네이티브 `ResetEx` 호출에서 창 모드일 때만 이 인자를 NULL로 바꾼다. [Microsoft 계약](https://learn.microsoft.com/en-us/windows/win32/api/d3d9/nf-d3d9-idirect3ddevice9ex-resetex)에 맞추자 재설정 반환값 0과 1024×768 로비 렌더링을 확인했다. 이 보정은 고정 릴리스의 32비트 객체 배치·모듈·D3D9Ex 인터페이스를 확인한 뒤 적용한다. `evidence:client`

화면은 UI 합성이 끝난 Present 직전에 캡처한다. EndScene 직후에는 같은 프레임의 배경만 잡힐 수 있다. 가상 클릭에는 `--ui-click 25:120:250`처럼 실행 후 초·창 내부 x·y를 지정하며 반복해서 쓸 수 있다. 실제 커서 이동 없이 소유 창 메시지·프로세스 내부 커서 조회·DirectInput 마우스 버튼 상태를 제공한다.

### CD판 생성 메뉴 호환 패치

CD판 로비 초기화 `0x0051ab35..0x0051ab58`은 새 캐릭터 생성·원작 캐릭터 추첨 버튼을 항상 비활성화한다. 계정·세션 응답을 바꾸는 것으로 이 두 상수를 바꿀 수 없다. `--enable-creation-menu`를 명시하면 `enable-creation-menu.js`가 원래 명령 바이트를 대조한 뒤 두 enable 즉시값만 실행 프로세스 메모리에서 0→1로 바꾼다. 파일은 수정하지 않으며 이 옵션을 생략한 실행은 기본 CD 동작을 유지한다. `evidence:client`

이는 포커스·렌더링 보정과 구분하는 **애플리케이션 동작 호환 패치**다. 적용 여부는 `run.json`의 `application_patch`와 실제 설치 이벤트에 남긴다. 이 옵션을 쓴 결과를 무수정 클라이언트의 생성 메뉴 동작으로 보고하지 않는다. 주소·포트·인증·메시지 처리와 UI 상태 전환 코드는 바꾸지 않는다.

단일 클라이언트 시험은 이 호스트 경로를 사용한다. VM은 멀티플레이 시험에 필요할 때만 사용한다. 다음 서버 작업은 Linear의 최신 상태·의존을 확인한 뒤 이어간다.
