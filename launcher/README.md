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

적용 실패 복구(2026-11-06): 적용 도중 한 파일이라도 보관·쓰기에 실패하면 그때까지 바꾼 파일을 바로 원래대로 되돌리고 실패를 알린다(첫 파일에서 실패하면 바꾼 파일이 없다고 알린다). 파일마다 원래 파일 보관 → 상태에 `pending` 표시와 함께 기록 → 복사 → 표시 제거 순서로 진행한다. 복사 중 전원이 꺼지는 등 프로세스가 중단되면 상태 끝에 `pending`이 남고, 다음 `--revert-patch`는 쓰다 만 그 파일 하나만 해시 확인 없이 원래대로 돌린다(다른 파일은 그대로 해시를 확인한다). 상태 파일은 임시 파일에 쓴 뒤 바꿔 넣으므로 기록 도중 중단돼도 직전 기록이 남는다. 되돌리기는 이미 원래 내용(보관본과 같은 해시, 또는 새로 더한 파일이 없음)으로 돌아간 항목을 건너뛰므로, 자동 되돌리기가 중간에 실패해도 원인을 없앤 뒤 `--revert-patch`로 다시 시도할 수 있다. 단위 검사 `a_failure_while_applying_restores_the_files_already_changed`, `an_interrupted_copy_is_reverted_without_a_hash_match`, `a_failed_automatic_rollback_can_be_retried`. 실제 클라이언트 사본에서 중간 실패를 일으키는 시험은 아직 하지 않았다.

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


## 내려받기 이어받기 (2026-11-06)

`--download-patch`는 이전 시도가 남긴 `<디렉터리>.partial`에서 확인된 파일을 다시 쓰고 나머지만 받는다(체크리스트 14장 다운로드 중단/재개).
- 매 시도는 빈 새 디렉터리(`<디렉터리>.partial-next`)에서 시작한다. 이전 `.partial`에서 이번 목록(서명·고정값 확인을 거친 목록)의 SHA-256과 같은 내용만 목록 철자 그대로 옮겨 쓴다.
- 그래서 대소문자만 다른 옛 이름(Windows), 옛 디렉터리와 같은 이름의 새 파일, 목록에 없는 잔여 파일이 남지 않는다.
- 옛 `.partial`은 표준 `fs::remove_dir_all`로 지운다. 이 함수는 심볼릭 링크·정션을 따라가지 않는다(Rust 1.58.1 이후, CVE-2022-21658). 링크를 통해 읽은 내용도 목록 해시와 같을 때만 쓴다.
- 받은 파일의 해시가 틀리면 쓰지 않고 실패한다. 확인된 파일은 다음 시도를 위해 남는다.
- 다시 쓴 파일 수는 표준 오류에 `이어받기: …`로 알린다.

확인(단위 검사):
- `an_interrupted_download_resumes_from_verified_files_and_drops_leftovers`: 첫 시도에서 두 번째 파일이 404면 실패하고 첫 파일은 남는다. 두 번째 시도는 서버가 첫 파일을 주지 않아도 완성되고, 손상본은 다시 받고, 다른 패치의 잔여는 들어가지 않는다.
- `a_resumed_file_is_written_with_the_listed_spelling`: 옛 `exe/A.dll`이 있어도 최종 이름은 목록의 `exe/a.dll` 하나다.
- `an_old_directory_does_not_block_a_listed_file_of_the_same_name`: 옛 디렉터리 `exe/item/`이 새 파일 `exe/item`을 막지 않는다.

런처 단위 검사 13개가 통과했다. 심볼릭 링크 시험은 Windows CI에서 권한이 필요해 두지 않았다(표준 라이브러리 동작에 의존). 실제 패치 서버에서 연결을 끊는 시험은 아직 하지 않았다. HTTP 범위 요청(Range)으로 큰 파일 하나를 중간부터 받는 기능은 없고, 이어받기는 파일 단위다.

## 실제 런처 업데이트 리허설 (2026-11-06)

`work/update-rehearsal-20261106`(게임 자산 포함, 커밋하지 않음)에서 release 런처로 실행했다. 클라이언트는 기존 시험 사본(`work/tactical-tabs-launcher-20261105/client`)을 복사해 쓰고, 그 사본에 남아 있던 이전 패치(20개 파일)를 먼저 `--revert-patch`로 되돌려 원본 상태(파일 2202개)를 기준으로 삼았다. 원본 게임 실행·입력·캡처는 하지 않았다.

| 단계 | 결과 |
|---|---|
| 내려받기 1회차: 시험 서버가 7개 파일 뒤 8번째 본문을 절반만 보내고 끊음 | 실패, `.partial`에 확인된 7개 유지, 최종 디렉터리 없음 |
| 내려받기 2회차(정상 서버) | `이어받기: 이미 받은 파일 7개…`, 서버 파일 요청 13개, 받은 20개가 원본 패치와 해시 동일, `.partial` 없음 |
| 다른 클라이언트용 목록(client 해시 변경) 적용 | 거부, 클라이언트 파일 변경 0 |
| 마지막 대상 파일을 읽기 전용으로 두고 적용 | 19개를 바꾼 뒤 실패하고 자동으로 되돌림, 상태·보관 폴더 없음, 원본과 차이 0 |
| 정상 적용 → 다시 적용 → 되돌리기 | 20개 해시 일치, 재적용 거부, 되돌린 뒤 원본과 차이 0 |
| 1.5GB 시험 파일 복사 중 런처 강제 종료 | 상태 끝에 `pending` 남음, `--revert-patch`가 21개를 되돌려 원본과 차이 0 |
| 다른 프로세스가 상태 파일을 계속 읽는 중 적용 | 적용·되돌리기 정상(아래 수정 뒤) |

리허설에서 찾은 문제: 다른 프로세스가 상태 파일을 연 순간에는 Windows에서 상태 파일 바꿔 넣기가 실패해 적용이 중단되고 `logh7-patch.state.next`가 남았다(자동 되돌리기는 정상). 백신·색인기에서도 생길 수 있어, 바꿔 넣기를 25ms 간격으로 최대 40회 다시 시도하고, 실패·되돌리기 때 남은 임시 파일을 지우게 했다. `make_patch.py`도 이 임시 파일을 패치에 넣지 않는다. 강제 종료 시험에서 Windows 복사는 대상 크기를 먼저 잡으므로, 끊긴 파일이 실제로 덜 쓰였는지는 크기로 확인하지 못했다. 서명 목록(`--trust-key`)과 HTTPS 경로는 이 리허설에 포함하지 않았다(서명은 단위 검사만).