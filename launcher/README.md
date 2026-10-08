# LOGH7 Windows 런처

작성자: 최병호 · 2026-10-08 · LOGH-26

`logh7-launcher.exe`와 `launcher.ini`로 게임 클라이언트를 대체 서버에 연결한다. 실행 전에 기본 오디오 출력 장치의 활성 상태를 확인하고, `exe/G7MTClient.exe`를 작업 디렉터리 `exe/`에서 실행한다. 게임 파일·주소 문자열·레지스트리·시스템 설정을 수정하지 않는다.

## 사용

1. 게임 설치본의 `exe/`와 `data/`를 같은 루트 아래 둔다.
2. `launcher.ini.example`을 런처 옆 `launcher.ini`로 복사하고 `client_root`, `host`, `port`, `account`를 지정한다. 파일은 UTF-8이며 경로의 공백은 그대로 쓴다. 상대 경로는 설정 파일 위치 기준이다.
3. 런처를 실행한다. 기본 인자는 `<host> <port> <account> <session> <credential>`이다. `login_ui=true`이면 앞의 세 인자만 전달하고 게임의 로그인 UI에서 인증 문자열을 입력한다. 현재 서버의 로컬 시험 계정은 별도로 등록해야 한다.

```powershell
.\logh7-launcher.exe --config E:\Games\LOGH7\launcher.ini --check
.\logh7-launcher.exe --config E:\Games\LOGH7\launcher.ini
```

`--check`는 설정·게임 경로·오디오만 점검한다. 설정을 생략하면 런처 실행 파일 옆 `launcher.ini`를 읽는다. `--help`, `--version`도 지원한다. 인증 문자열은 런처 출력에 쓰지 않는다. 계정과 인증 문자열은 원본 인자 경로에서 확인한 짧은 ASCII 범위를 사용한다. 서버 주소는 IPv4, 포트와 세션 ID는 1~65535이다.

기본 오디오 출력이 없거나 비활성이면 원인을 표시하고 게임을 실행하지 않는다. 점검은 [GetDefaultAudioEndpoint](https://learn.microsoft.com/en-us/windows/win32/api/mmdeviceapi/nf-mmdeviceapi-immdeviceenumerator-getdefaultaudioendpoint)와 [GetState](https://learn.microsoft.com/en-us/windows/win32/api/mmdeviceapi/nf-mmdeviceapi-immdevice-getstate)를 사용하며 볼륨·음소거·장치를 바꾸지 않는다. 이 점검은 실제 게임 오디오 초기화 성공을 보장하지 않으므로 실행 관찰과 구분한다.

## 빌드·배포

외부 Rust 크레이트 없이 표준 라이브러리와 Windows Core Audio를 사용한다. [Rust 1.99.0](https://doc.rust-lang.org/releases.html#version-1990-2026-10-01) MSVC 도구 체인과 Windows C++ 빌드 도구가 필요하다. 로컬에서는 기존 도구 체인을 재사용하며 빌드·캐시를 E:에 둔다.

```powershell
$env:CARGO_HOME='E:\Tools\cargo-home'
$env:CARGO_TARGET_DIR='E:\logh7\launcher\target'
cargo fmt --check
cargo test --locked
cargo build --release --locked
```

출력은 `target/release/logh7-launcher.exe`다. GitHub의 launcher CI는 Windows에서 검사·빌드하고 실행 파일과 설정 예제를 artifact로 보관한다. 게임 자산·dxwrapper·실제 계정 설정은 포함하지 않는다. 배포용 런처의 기능은 직접 인자 실행이며, 업데이터 주소 문자열 패치와 한국어 패치 적용기는 후속 통합 대상으로 남긴다.

## 호스트 시험

개발 중 게임은 [비활성 데스크톱 실행 정책](../docs/ops/background-client.md)을 따른다. 런처를 소유한 비활성 데스크톱에 띄우면 그 자식 게임도 부모의 데스크톱을 사용한다([Windows 데스크톱 연결 규칙](https://learn.microsoft.com/en-us/windows/win32/winstation/thread-connection-to-a-desktop)). 시험에서만 자식 생성 시점에 렌더링 캡처·포커스 조회 보정을 붙이고 게임 PID의 오디오 세션을 음소거한다. 제품 런처에 Frida·Python 의존성이나 데스크톱 전환 기능을 넣지 않는다.

`work/logh26-host-20261008/run-03`에서 release 런처 → 실제 게임 사본 → localhost 로그인 → 저장 캐릭터 전략 화면(1024×768)을 확인했다. 원본·사본 exe 해시, 활성 오디오 출력 유지 중 해당 PID 음소거, 호스트 포커스·커서 보존, 소유 프로세스 종료가 통과했다. `gateway-01`에는 같은 ID=10000의 월드 초기화 두 단계가 있다. 오디오 장치가 없는 실제 호스트 시험과 공개 서버 배포는 실행하지 않았다.
