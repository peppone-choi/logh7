# 비활성 데스크톱 클라이언트 실행

작성: Peppone Choi · 2026-10-07 · 관련: ADR-0006 / D-7

## 받은 방법과 적용

Breaking Point 「채팅 오케스트레이션 시작」 채팅에서 `BP_GUI_TEST` 실행·캡처 기록과 `E:\hoi4-mods\Breaking-Point-New\tools\bp_background_game.py`, `bp_virtual_input.py`, `docs\branding\hud\BACKGROUND-GAME-CHECK.md`를 확인했다. 핵심은 `CreateDesktop`, `STARTUPINFO.lpDesktop`, 대상 창 `PrintWindow`, 자식 종료까지 데스크톱 핸들 유지다. HOI4 전용 DLL·설정은 가져오지 않았다. `evidence:client` (외부 채팅 기록 및 로컬 소스 읽음)

`tools/client/run-background.py`는 `work/` 사본을 고유 `LOGH7_TEST_*` 데스크톱에서 실행한다. 작업 디렉터리는 `exe/`, 인자는 `127.0.0.1 <임시 포트> ginei00 1 dummy`다. dxwrapper의 D3D8→D3D9Ex 변환과 창 모드를 적용한다. Frida는 소유 프로세스의 포커스 조회에 자기 창을 반환하고 렌더링 버퍼만 읽는다. 실제 입력 데스크톱이나 호스트 포커스는 전환하지 않는다. 기본 15초 뒤 생성한 프로세스·자식·핸들을 정리한다.

게임 화면 PNG·실행 사본·원바이트는 모두 Git 제외 `work/`에 남긴다. 실행기가 시스템 로캘·방화벽·원본·설치 레지스트리를 변경하지 않는다. 같은 사용자 권한으로 실행하므로 게임 자체의 파일·레지스트리 접근까지 차단하는 샌드박스는 아니다. `evidence:client` (실행기), `evidence:guess` (게임의 미관찰 접근 범위)

## 실행

현재 Hermes Windows QA에서는 아래 과거 직접 실행 예제를 사용하지 않는다.
공식 정책 `/srv/hermes-workspace/WINDOWS-EXECUTION.md`의 격리·공유 mutex·시작 전
무음·소유 트리 정리 조건이 먼저 충족되어야 한다. 세션 생성 뒤 음소거는 시작 전
무음 보장이 아니다. 원본 상호운용·공식 제품 QA는 현재 NOT_RUN이다.

### 실행 없는 Windows 사전검사 (LOGH-61)

`tools/client/windows-qa-preflight.py`는 원본 SHA256, Windows checkout의 전후 SHA와
dirty 상태를 수집한다. 지정한 새 작업 전용 `--out`에만 증거를 쓴다. 원본·작업 사본을
수정하거나 fetch/reset/checkout하지 않는다. 기존 dirty Windows 변경을 보존한다.
검토한 SHA256의 `qa_owned_process.py`에만 `--kind logh --project logh --report`를
전달하며 launcher 인자나 게임 실행 경로를 전달하지 않는다. 이 wrapper의 LOGH 경로는
프로세스 생성 전에 무조건 대기한다. wrapper 바이트가 달라지면 실행도 거부한다.
wrapper가 고정 경로 `E:/logh7/tools/client/audio_guard.py`에서 import하는 파일도
실행 전에 검토된 SHA256과 대조하고 경로·해시를 `readiness.audioGuard`에 기록한다.
`--repo`로 import 경로를 바꾸지 않는다. 파일 누락·읽기 실패·해시 불일치는
`UNREVIEWED_AUDIO_GUARD`로 기록하고 wrapper를 실행하지 않는다.

Windows Python 3.11로 아래 인자를 전달하고 `hermes-windows exec --project logh`로
실행한다. 어댑터와 출력은 `E:\logh7\work\<task>\` 같은 새 작업 전용 경로에 둔다.

    python -B windows-qa-preflight.py --repo E:\logh7 --head <정확한 40자리 SHA> --source-exe <읽기전용 원본 G7MTClient.exe> --owned-wrapper C:\Users\user\AppData\Local\OpenClawNode\hostinger-windows-20261012\qa\qa_owned_process.py --out <새 증거 디렉터리>

stdout은 JSON 하나, 종료 코드는 3(NOT_RUN)이다. readiness 성공을 제품 성공으로
변환하지 않으며 isolationVerified/silentVerified/cleanupVerified는 모두 false다.
`hermes-windows qa`에 이 사전검사를 제품 PASS 스크립트로 제출하지 않는다.
SSH 세션에서 `OpenInputDesktop`가 `[WinError 1]`로 실패할 수 있다. 이 경우
wrapper의 stderr/종료 코드와 report 부재를 그대로 보존하며 활성 데스크톱 전환,
노드 재기동, 일반 SSH 게임 실행으로 우회하지 않는다.

다음 제품 시험은 별도 검증 담당이 시작 전 무음 전체 대상 검증과 승인 runtime
manifest, SSH에서 접근 가능한 문서화된 격리 실행 경로를 먼저 확보해야 한다.
그 뒤 기존 `--upstream-port` 전달 기능과 Kotlin MPS 서버를 재사용한다.
기존 `run.json`/송수신 원바이트/서버 로그로 연결, `0x34→0x35→0x36`, 암호 봉투를
대조하고 정확한 소스 SHA 및 원본 해시와 연결한다. 첫 `0x34`만으로 전체 교환·암호
봉투·로그인 성공을 주장하지 않는다. 이번 어댑터는 패킷을 합성하거나 과거 로그를
새 실행으로 재분류하지 않는다. 공식 영수증은 실제 제품 실행 후 `hermes-windows qa`로만
발급하며 PR #46이나 다른 SHA에 전용하지 않는다.

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

### 여러 화면을 이어서 조작

`--seconds 300 --ui-control`은 최대 5분 동안 같은 소유 프로세스를 유지한다. 생성된 `OUT/ui-commands.jsonl`에 JSON 한 줄씩 추가하면 `{"click":[760,580]}`, `{"drag":[797,91,750,91]}`, `{"text":"Alpha"}`, `{"key":9}` 또는 `{"stop":true}`를 처리한다. 드래그는 시작/끝 좌표 네 개를 받고 소유 창 안에서 가상 버튼을 누른 채 이동한 뒤 해제한다. 활성 데스크톱의 커서나 전역 입력을 사용하지 않는다. 클릭도 소유 창 내부, 문자는 짧은 합성 ASCII, 키는 Tab(9)·Enter(13)·Escape(27)만 지원한다. 파일은 추가만 허용하며 잘못된 명령은 실행기를 실패 처리하고 정리한다. 화면을 확인하며 필요한 입력만 보내고, 끝나면 stop으로 프로세스·오디오 상태를 정리한다. 자동 판단·반복 실행 기능은 없다.

단일 클라이언트 시험은 이 호스트 경로를 사용한다. VM은 멀티플레이 시험에 필요할 때만 사용한다. 다음 서버 작업은 Linear의 최신 상태·의존을 확인한 뒤 이어간다.

## 2026-10-24 실제 접속: 메모리 전용 DirectSound

작성자: Peppone Choi

사용자가 요청한 실제 로컬 접속 시험에서는 `--silent-device`를 명시한다. 과거
오디오 승인 manifest를 충족했다고 간주하지 않고, `silent-dsound.js`가 정지 상태의
소유 프로세스에 메모리 전용 DirectSound 장치·버퍼를 설치한다. 실제
DirectSoundCreate(8)는 호출하지 않는다. PCM 쓰기·재생 상태·커서는 메모리에서만
처리하고 WinMM 출력·COM 활성화·자식 생성은 거부한다. 예상하지 못한 출력 모듈,
script 오류·detach는 소유 프로세스를 종료한다. 다른 클라이언트·DLL 조합이나
모든 Windows 출력 API의 일반적 무음 보장을 검증한 것은 아니다.

대상은 SHA-256 `bd19263c10decc3d58373165a82d42a9267868400d407da87d5f4f4109ab6e16`의
CD판 EXE에 한정한다. 실행 직전 EXE 디렉터리와 주입 script를
다시 해시 대조하며, 이것은 같은 실행의 파일 일관성 검사다. 원격 승인이나 전체
게임 기능 통과를 뜻하지 않는다. 기본 실행과 `--audible`은 계속 생성 전에 거부한다.
호스트·사용자 오디오 세션의 볼륨 또는 음소거 설정은 변경하지 않는다.

`TEMP`와 `TMP`는 실행 전에 작업 드라이브의 전용 폴더로 지정한다. 아래는
이미 준비한 localhost 서버와 `work/` 사본을 사용하는 예다. 원본 복사본 준비,
서버 계정·캐릭터 파일 설정은 위 절차를 따른다.

```powershell
$env:TEMP = 'E:\logh7\work\client-login-20261024\tmp'
$env:TMP = $env:TEMP
python -B tools/client/run-background.py --source-root $sourceRoot `
  --instance-root E:\logh7\work\client-login-20261024\run-01\client `
  --out E:\logh7\work\client-login-20261024\next-run `
  --dxwrapper-dir E:\Tools\dxwrapper\v1.8.8600.25\unpacked `
  --seconds 300 --render-capture --upstream-port 47900 `
  --ui-login --ui-control --silent-device --enable-creation-menu `
  --test-account qa1024 --test-credential dummy
```

공유 GUI mutex와 활성 프로세스 1개 제한·kill-on-close Job을 유지한다. 캡처와 입력은
소유 비활성 데스크톱에만 적용한다. 무해 x86 PE 검사에서는 DirectSound의 메모리
버퍼 생성·분할 Lock·Play/Stop·복제와 WinMM/COM 거부를 실제 export 호출로 확인한다.

```powershell
$env:LOGH7_SILENT_AUDIO_TEST = '1'
python -B -m unittest discover -s tools/client -p 'test_silent_audio*.py' -v
```

`work/client-login-20261024/run-02`에서 일반 로그인 UI → 신규 생성 → 등록·파일 저장 →
1024×768 전략 화면을 확인했다. 생성 메뉴는 앞 절의 두 바이트 메모리 호환 패치를
사용했다. 서버 재시작 뒤 `run-04`에서는 이 패치 없이 계속하기로 같은 캐릭터에
재접속했다. 두 실행 모두 원본 해시 보존·소유 프로세스 종료·Job 활성 수 0을 확인했다.
서버 등록 ID는 10000, 합성 이름은 Nova/Pilot, 기함명은 NovaShip이다. `evidence:client`

`run.json.success`는 실행기·첫 패킷·캡처·정리의 성공이며, `full_login_verified`는
자동 판정하지 않으므로 false로 남는다. 위 흐름의 확인은 저장된 전략 화면,
서버의 등록·월드 초기화 로그와 캐릭터 파일을 함께 대조한 결과다. 일본어 글자
깨짐과 일부 NO DATA 표시, 전투·실서비스 데이터 완성은 이번 통과 범위에 포함하지 않는다.

## 2026-10-25 전략 모드 접속자 공유

작성자: Peppone Choi

월드·이동 저장소를 사용하는 Gateway는 같은 세대·세력·그리드의 온라인 캐릭터를
초기 목록에 포함하고 입장·워프·장소 이동·종료를 다른 접속자에게 전달한다.
이 가시성 범위는 원작의 정찰·정보 공개 조건을 확정하기 전의 로컬 정책이다.
스폿 대화 권한이나 적 함대 탐지까지 구현한 것은 아니다. `evidence:guess`

CD판의 `0356` 수신기는 이미 존재하는 슬롯만 갱신한다. 새 캐릭터는
`0b09(0)` → 다른 캐릭터의 `0323` → `0b0a(0)`으로 반영하고,
퇴장은 `0b08`의 유닛 ID 목록으로 제거한다. 수신자 본인은 재전송하지 않아
대기 중인 타인 알림이 자신의 최신 CP를 덮어쓰지 않도록 한다. 각 연결의 이벤트
루프에서 암호화 순서를 정하고, 실행 중인 비동기 응답 뒤에 알림을 보낸다.
`evidence:client`

`MultiplayerPresenceTest`는 실제 암호화 TCP 두 연결에서 초기 목록, 서로의 워프,
종료·재접속·중복 로그인 거절과 독립된 시퀀스를 검사한다. 기존 50개 합성 접속
수명주기 검사도 통과했다. 호스트 비활성 데스크톱의 원본 클라이언트와 합성 상대를
연결한 시험에서는 캐릭터 슬롯에 상대 ID 10001·유닛 ID 1010001이 등록되고,
상대가 다른 그리드로 워프하면 제거되는 것을 읽기 전용 메모리 검사로 확인했다.
`work/multiplayer-20261025/host-run-01`은 원본 해시 보존·프로세스 종료·Job 활성 0으로
정리됐다. 상대의 내장 함선 구조체는 비어 있었으므로 상대 함선 모델 표시 통과로
해석하지 않는다. `evidence:client`

VM 멀티플레이 시험을 위해 `LOGH7_HOST_ONLY_ADDRESS`를 지정하면 같은 Gateway가
기본 loopback과 해당 VirtualBox host-only 주소에 함께 바인딩한다. 각 주소로 들어온
로그인은 자신이 접속한 주소로 세션 서버를 안내하며 계정·티켓·월드는 공유한다.
기존의 literal IPv4·활성 host-only 어댑터 검사와 wildcard 금지는 유지한다.
이번 VM에서는 게스트→호스트 TCP 접속이 성립하지 않아 **원본 클라이언트 두 개의
동시 접속은 미확인**이다. 이를 성공한 멀티플레이 시험으로 기록하지 않는다.

## 두 원본의 같은 호스트 동시 실행 (2026-10-28)

작성: Peppone Choi

`tools/client/run-multiplayer.py`는 두 개의 서로 다른 `work/` 사본과 계정을 받는다. `GuiTestGroup`이 기존 GUI 시험 잠금을 한 번 보유한 채 두 비활성 데스크톱을 관리한다. 외부 시험 프로세스의 잠금 획득, 세 번째 사본, 동일 사본 재사용, 다른 프로세스/작업 스레드의 잠금 해제는 거부한다. 한 실행이 실패하면 다른 실행에도 중지를 알리고, 각 소유 Job과 무음 장치 정리가 끝난 뒤 잠금을 해제한다. GUI 전환·전역 입력·호스트 설정 변경은 사용하지 않는다.

각 클라이언트는 자체 `ui-commands.jsonl`로 수동 로그인·입력을 받는다. Frida 장치 관리자는 작업 스레드를 시작하기 전에 초기화한다. 첫 동시 실행에서 발생한 장치 초기화 경쟁은 실패로 기록하고, 수정 후 `run-02`와 `run-04`에서 두 프로세스의 정상 접속·정리를 확인했다. 두 실행의 일반 성공 플래그만으로 게임 기능 전체를 완료로 판정하지 않는다.

이번 시험의 서버 월드는 `work/native-multiplayer-20261028/`, 원본 사본·동시 실행 기록은 `work/character-office-tab-wt/work/native-multiplayer-20261028/`에 보존한다. 일반 월드로 시험 계정·재고를 복사하지 않는다. `run-04`에서 원본 두 개의 동일 그리드 전략 화면 진입, 양쪽 캐릭터/함선 ID와 그리드, ASCII 전체 채팅 왕복, 한쪽 종료 시 상대 목록 제거를 확인했다. 두 원본의 이동·명령 충돌·전투·재접속과 한국어 입력은 미완료다. 지도상의 상대 함선 모델 표시도 이번 기록으로 완료 처리하지 않는다. `evidence:client`

VM은 헤드리스 부팅과 자동 로그인을 확인했으나 Guest Control 세션 생성이 시간 초과됐다. 호스트 전용 TCP 수신은 확인했고 게스트→호스트 접속 여부는 미확인이다. VM을 정상 종료했고 호스트 방화벽은 변경하지 않았다. 같은 호스트의 두 원본 시험은 이 VM 경로를 통과한 것으로 기록하지 않는다.

### 큰 이미지 그리기 관찰 (2026-10-28)

작성: Peppone Choi

소유 실행의 `ui-commands.jsonl`에 `{"traceQuads":true}`를 추가하면 기존 캡처 세션이 세 번의 Present 동안 D3D8의 2D 사각형을 읽는다. 가로·세로가 각각 100 이상인 서로 다른 사각형만 최대 64개 보관하고 화면 경계·네 꼭짓점/UV·호출 주소를 해당 실행의 `render-trace.jsonl`에 기록한다. 게임 상태·그리기 인자·그리기 결과는 변경하지 않는다. 마우스나 텍스트 입력과 같은 소유 실행 제어 채널을 사용하며 별도 Frida 세션을 열지 않는다. `run-35`의 두 원본에서 반복 관찰 후 정상 종료를 확인했다. 이 도구의 관찰 성공은 전술 기능 완료를 뜻하지 않는다.

### 전술 카메라 회전 입력 (2026-10-28)

작성: Peppone Choi

웹 매뉴얼 p.23은 오른쪽 드래그로 카메라를 회전한다. 소유 실행의 제어 파일에 `{"rightDrag":[350,350,950,350]}`을 추가하면 오른쪽 버튼을 누른 채 소유 창 안에서 이동하고 해제한다. Win32 메시지와 해당 프로세스의 가상 키/DirectInput 버튼·상대 이동을 일치시키며 호스트 커서나 전역 입력은 사용하지 않는다. 기존 `drag`는 왼쪽 선택이다. Frida의 기존 세 인자 호출에 붙는 null 인자를 왼쪽 버튼으로 처리한다. 최초 run-82는 이 호환 누락으로 실패했고 두 소유 프로세스 정리를 확인한 뒤 수정했다.

run-83에서 실제 오른쪽 드래그로 후면→측면→정면 회전을 확인했다. 두 소유 프로세스의 종료·잔류 Job 0·원본 해시 보존도 확인했다. 캡처는 `work/native-multiplayer-20261028/camera83-oblique.png`, `camera83-front.png`다.

### 상대 접속을 유지한 한쪽 재실행 (2026-10-28)

작성: Peppone Choi

`run-multiplayer.py`의 기존 두 사본 실행에 `--reconnect-client 0` 또는 `1`을 지정하면 해당 사본만 재접속 시험 대상으로 허용한다. 기본 재실행 한도는 1회이며 `--max-reconnects 1`~`3`으로 제한한다. 지정하지 않으면 기존처럼 종료된 사본도 재사용하지 못한다.

지정한 소유 실행의 `ui-commands.jsonl`에 `{"reconnect":true}`를 추가한다. 현재 실행을 정상 정리하고 `run.json`의 성공·프로세스 종료·정수형 Job 잔류 0·원본 해시 보존을 확인한 뒤 같은 사본을 다시 시작한다. 상대 실행과 그룹 잠금은 유지한다. 결과는 기존 `client-0` 등을 덮어쓰지 않고 `client-0-reconnect-1`처럼 새 디렉터리에 남긴다. 로그인 입력도 새 제어 파일에 전달한다. 실패는 자동 재시도하지 않으며 상대도 그룹 종료 경로로 정리한다. `success`는 실행/정리의 성공이며 이동이나 전투 기능 통과와 구분한다.

`{"hover":[700,388]}`는 소유 창의 가상 마우스 위치만 옮긴다. `{"ctrlClick":[700,388]}`는 버튼 해제 프레임까지 Ctrl을 유지한 뒤 가상 키를 해제한다. 호스트 전역 키/마우스 입력은 사용하지 않는다. run-135~138에서 상대를 유지한 한쪽 재실행과 정리를 확인했다. 이동 복원의 실패/보완 범위는 [전술 기록](../protocol/tactical-entry.md#이동-중-재접속의-초기-경로-누락과-남은-연속-경유지-오차-2026-10-28)에 남긴다.

### 게임 시간 대기 명령의 빠른 시험 (2026-10-30)

24G시간 대기 명령은 실제 배속(24배)에서 1시간이 걸린다. 시험 사본에서는 `rules/session.properties`를 복사해 `clock.speed`만 올린 파일을 `LOGH7_SESSION_RULES`로 지정한다. 저장된 세션은 다른 규칙을 거부하므로 `LOGH7_SESSION_STORE_DIR`도 새 디렉터리로 둔다. 월드 상태는 그대로 쓸 수 있다. 서버가 도는 동안 월드 저장 디렉터리(`CURRENT`, `slot-*/stocks.tsv`)를 주기적으로 읽지 않는다. Windows에서 읽기 핸들이 원자 교체를 막아 서버가 저장 실패(`Could not commit world state`)로 멈춘다. 결과는 서버를 멈춘 뒤 읽거나, 정해진 시간만 기다린 뒤 한 번 읽는다.
