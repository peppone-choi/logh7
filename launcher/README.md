# LOGH7 Windows 런처

작성자: Peppone Choi · 2026-10-08 · LOGH-26

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

## 패치 적용·되돌리기 (2026-11-03)

`--apply-patch <패치 디렉터리>`는 패치 디렉터리의 `patch.manifest`(첫 줄 `LOGH7-PATCH-1`, `client<TAB>exe/G7MTClient.exe SHA-256`, `file<TAB>상대 경로<TAB>SHA-256` 줄들)를 읽는다. 클라이언트 실행 파일 해시와 모든 패치 파일 해시를 먼저 확인하고, 하나라도 다르면 아무 파일도 바꾸지 않는다. 클라이언트 루트 밖 경로와 원본 실행 파일 자체는 거부한다. 바꿀 원래 파일은 `logh7-patch-backup/`에 보관하고, 설치 목록은 파일마다 `logh7-patch.state`에 기록한다. 이미 적용된 상태에서 다시 적용하면 거부한다. `--revert-patch`는 설치한 파일이 그대로일 때만 원래 파일을 복원하거나 새로 더한 파일을 지운다. 하나라도 바뀌었으면 아무것도 되돌리지 않는다. SHA-256은 외부 크레이트 없이 구현했다(표준 시험 벡터 검사).

로컬 확인(`work/patch-20261103`, 단위 검사 5개 통과): release 런처로 클라이언트 `exe` 사본의 이전 호환 DLL(`975091ce…`)을 새 DLL(`0206917d…`)로 바꾸고, 재적용 거부와 되돌리기 뒤 원래 해시 복귀·상태 파일 삭제를 확인했다. 패치 목록은 `tools/ops/make_patch.py --original <원본 설치본> --prepared <준비된 사본> --out work/<케이스>/patch`로 만든다. 사본에서 새로 생기거나 달라진 파일을 담고, 원본 실행 파일·로그·`GraphicConfig.txt`·클라이언트가 실행 중 쓰는 `exe/String.txt`(실행 기록)·`exe/window*.dat`(창 위치)는 넣지 않는다. 산출물(게임 자산 포함)은 커밋하지 않는다.

전 과정 확인(`work/patch-20261103` run-01·02): 시험 사본(`work/warp-20261030/copy-0`)에서 한국어 이미지 33개·호환 DLL·dxwrapper·블랙홀 투명 텍스처 등 40개 파일 패치(15MB)를 만들고, 원본 설치본의 새 사본에 release 런처로 적용했다. 그 사본으로 원본 클라이언트가 로그인해 한국어 전략 화면을 표시했다. 종료 뒤 되돌리기에서 원본과 달라진 파일은 클라이언트가 실행 중 쓰는 세 파일(로그·`String.txt`·`window3.dat`)뿐이었다. 첫 시도(run-01)는 `String.txt`를 패치에 넣었다가, 실행 뒤 파일이 바뀌어 되돌리기가 의도대로 거부됐다. 그래서 목록에서 제외했다. 내려받기는 아래에 따로 정리했다.

`--download-patch <http 주소> <새 디렉터리>`는 그 주소의 `patch.manifest`와 목록 파일을 받아 SHA-256을 대조한다. 모두 맞을 때만 임시 `.partial` 디렉터리를 최종 이름으로 바꾸며, 하나라도 다르면 받은 것을 지운다. 기존 디렉터리는 덮어쓰지 않는다. 외부 크레이트 없이 HTTP/1.0만 쓰므로 TLS가 없고, 목록 자체의 진위는 확인하지 않는다. 그래서 목록 고정값을 더했다(2026-11-03): `--download-patch <주소> <디렉터리> --expect-manifest <SHA-256>`은 받은 `patch.manifest`의 SHA-256이 고정값과 같을 때만 진행하고, 목록의 파일 해시가 나머지를 묶는다. 고정값은 `tools/ops/make_patch.py`가 출력하며, 패치 서버와 다른 경로(배포 공지·런처 배포본)로 전해야 의미가 있다. 실제 한국어 패치에서 맞는 값은 40개 파일을 받았고, 틀린 값은 아무것도 받지 않고 거부했다. 공개키 서명은 아래에 더했고, HTTPS는 아직 없다. 로컬 확인: `python -m http.server`로 연 `work/patch-20261103`에서 한국어 패치 40개 파일을 받아 원본 패치 디렉터리와 같음을 확인했고, 없는 경로는 404로 실패했다. 런처 단위 검사 7개는 8회 반복해 모두 통과했다.

## 빌드·배포

외부 Rust 크레이트 없이 표준 라이브러리와 Windows Core Audio를 사용한다. [Rust 1.99.0](https://doc.rust-lang.org/releases.html#version-1990-2026-10-01) MSVC 도구 체인과 Windows C++ 빌드 도구가 필요하다. 로컬에서는 기존 도구 체인을 재사용하며 빌드·캐시를 E:에 둔다.

```powershell
$env:CARGO_HOME='E:\Tools\cargo-home'
$env:CARGO_TARGET_DIR='E:\logh7\launcher\target'
cargo fmt --check
cargo test --locked
cargo build --release --locked
```

출력은 `target/release/logh7-launcher.exe`다. GitHub의 launcher CI는 Windows에서 검사·빌드하고 실행 파일과 설정 예제를 artifact로 보관한다. 게임 자산·dxwrapper·실제 계정 설정은 포함하지 않는다. 배포용 런처의 기능은 직접 인자 실행이며, 업데이터 주소 문자열 패치와 패치 내려받기는 후속 통합 대상으로 남긴다(로컬 패치 적용·되돌리기는 위 절).

## 호스트 시험

개발 중 게임은 [비활성 데스크톱 실행 정책](../docs/ops/background-client.md)을 따른다. 런처를 소유한 비활성 데스크톱에 띄우면 그 자식 게임도 부모의 데스크톱을 사용한다([Windows 데스크톱 연결 규칙](https://learn.microsoft.com/en-us/windows/win32/winstation/thread-connection-to-a-desktop)). 시험에서만 자식 생성 시점에 렌더링 캡처·포커스 조회 보정을 붙이고 게임 PID의 오디오 세션을 음소거한다. 제품 런처에 Frida·Python 의존성이나 데스크톱 전환 기능을 넣지 않는다.

`work/logh26-host-20261008/run-03`에서 release 런처 → 실제 게임 사본 → localhost 로그인 → 저장 캐릭터 전략 화면(1024×768)을 확인했다. 원본·사본 exe 해시, 활성 오디오 출력 유지 중 해당 PID 음소거, 호스트 포커스·커서 보존, 소유 프로세스 종료가 통과했다. `gateway-01`에는 같은 ID=10000의 월드 초기화 두 단계가 있다. 오디오 장치가 없는 실제 호스트 시험과 공개 서버 배포는 실행하지 않았다.

## 패치 목록 공개키 서명 (2026-11-04)

`tools/ops/patch_sign.py keygen --out <개인키.pem>`이 ECDSA P-256 키를 만들고 공개키(X||Y 128자리 16진수)를 출력한다. 개인키는 패치 서버에 두지 않는다. `patch_sign.py sign --key <개인키.pem> --patch <패치 디렉터리>`는 `patch.manifest` 옆에 `patch.manifest.sig`(r||s 128자리 16진수)를 쓴다. 런처는 `--download-patch <주소> <디렉터리> --trust-key <공개키>`로 서명을 먼저 받아 Windows CNG(`bcrypt.dll`, 외부 크레이트 없음)로 확인하고, 맞을 때만 목록의 파일을 받는다. 파일마다 SHA-256 대조는 그대로 한다. 공개키는 런처 배포본에 한 번만 넣으면 되므로 패치마다 목록 고정값을 알릴 필요가 없다. `--expect-manifest`와 함께 쓸 수도 있다.

로컬 확인(`work/patch-20261103`): 실제 한국어 패치 40개 파일에 서명해 `python -m http.server`로 열었다. 맞는 키로는 40개를 받았고 원래 패치와 같았다. 다른 키, 목록에 한 줄 덧붙인 변조, 서명 파일 없음(404)은 모두 아무것도 받지 않고 실패했다. 런처 단위 검사 10개가 통과했다(서명 고정 벡터와 위조 목록 거부 포함).

