# 격리 실행 환경

작성: 최병호 · 2026-09-28 갱신 · LOGH-5·6·20·22

## 현재 상태

VirtualBox 7.2.20을 `E:\VirtualBox`에 설치했고 `logh7-win` VM에 일본어 Windows 10 x64(10.0.19045.3803)와 원본 게임을 설치했다. 설치 전후 비교는 [설치 비교](../re/install-diff.md)에 기록했다. 사용자 승인 VMware Workstation 17.6.4 복제본의 초기 시험은 창·TCP 이전에 멈췄다. 이후 VirtualBox에서 **오디오 출력 부재에 따른 IKSound 초기화 실패**를 확인하고 HDA 출력 엔드포인트를 제공했다. 원래 업데이터의 `./exe/` 작업 디렉터리로 직접 실행하자 원본 클라이언트 창과 localhost 첫 `0x34` 프레임을 확보했다. VM 두 개는 모두 정상 종료 상태다. `evidence:client` (동적 케이스 E-101·E-103~122; 정적 케이스 사운드·업데이터 분석).

| 항목 | 확인 결과 |
|---|---|
| 실행 파일 | `E:\VirtualBox\VBoxManage.exe`, `7.2.20r175154` |
| Windows VM | `E:\VM\logh7-win\logh7-win.vbox`, UUID `742099e5-d79b-40fb-90e6-a10f12235525` |
| 자원 | 8 GiB RAM, 4 CPU, 64 GiB 동적 VDI, BIOS, VBoxSVGA·3D 켜짐(클라이언트 재시험 후) |
| 네트워크 | NIC1 host-only, `VirtualBox Host-Only Ethernet Adapter`, NIC2 없음. NAT는 언어 기능 설치 중에만 사용 |
| 공유·계정·로캘 | 자동 로그인·간단한 비밀번호. 시스템·UI 로캘 `ja-JP`, 사용자 언어 `ja`·`ko`, 각 입력기 및 양쪽 글꼴 확인 |
| 스냅샷 | `os-ja-base` UUID `58031c9b-485b-43a3-8feb-9ef805c19bec`, 게임 설치 전 `clean` UUID `75804d62-d331-45a4-ac83-3bc37858cd71`, 설치 후 `installed` UUID `68f5a67e-c39f-4f8d-bc80-c84d6bd113cc`, 정상 종료 후 `installed-verified` UUID `b8ac6179-38f0-40ee-9f55-0682f227b57d` |
| 오디오 변경 전 스냅샷 | `pre-audio` UUID `c8616824-9be4-437c-b746-e363fd983959`(오프라인) |
| 현재 오디오 | HDA, 출력 on, 호스트 드라이버 null. 게스트 사운드 장치·스피커 엔드포인트 OK |
| ISO | `E:\iso\Windows.iso`(일본어 x64, 4,895,932,416바이트), SHA256 `F47A3ECF5DD4AB407746D42516D1219E9B2D1CBCD542956CF67E7F804EF1E5DB` |

현재 VM은 정상 종료한 `poweroff` 상태다. 표의 환경 관찰은 `evidence:client` (E-101·E-103~122)다.

## G: 조사와 재설치

G:의 과거 MBR 식별자는 `46BE25E5`, 파티션 오프셋은 1 MiB였다. 현재 열거된 물리 디스크 3개(C: Samsung 250 GB, D: Hitachi 500 GB, E: SK Hynix 2 TB) 중 이에 해당하는 디스크가 없었다. 문자 없는 볼륨은 EFI·복구 파티션이었다. 따라서 다른 볼륨에 G:를 부여하지 않았다. `evidence:client` (세션 2 리드 실행 기록).

사용자가 재설치와 `E:\VirtualBox` 예외 경로를 승인했다. 첫 설치는 E:\Tools 상위 폴더 권한 요건으로, 두 번째는 기존 7.0.18 제거 중 `Invalid Drive: G:\`로 실패했다. 빈 전용 폴더 `E:\Tools\virtualbox-legacy-drive`를 관리자 설치 과정 동안만 G:로 연결하여 이전 제품 제거를 끝내고 새 버전을 설치했다. 임시 연결은 finally에서 해제했다. 기존 Ubuntu VDI·설정은 보존했다. `evidence:client`

설치 파일은 [Oracle 7.2.20 공식 배포](https://download.virtualbox.org/virtualbox/7.2.20/)에서 받았다. SHA256 `a81777d2b36380ce042a29e9c554cf032eb46a793f62e3cc82e7411e535c2c26`이 공식 목록과 일치하고 Oracle America 전자서명은 Valid였다. [설치 폴더 요건](https://docs.oracle.com/en/virtualization/virtualbox/7.2/user/installation.html), [subst](https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/subst). 설치·임시 경로는 E:이며 Windows 드라이버·Installer 시스템 등록 파일은 OS 관리 위치에 설치된다. `evidence:client`

## ISO 확보부터 설치·접속 시험까지

1. [Microsoft 공식 Windows 10 다운로드](https://www.microsoft.com/en-us/software-download/windows10)의 미디어 생성 도구로 일본어 x64 ISO를 E:에 확보했다. 직접 링크 API와 ISO 판본 선택은 거부됐으므로 이 경로를 사용했다. ISO의 `sources\lang.ini`는 `ja-jp`, `efi\boot\bootx64.efi`가 있고 7-Zip UDF 검사는 통과했다. 위 해시는 로컬 파일 해시이며 Microsoft 게시 해시와 대조한 값은 아니다. `evidence:client` (후속 실행).
2. 기존 `logh7-win`에 일본어 Windows 10을 설치했다. 첫 시도는 2 CPU·4 GiB RAM에서 설치 진행이 멈춰 전원을 껐고, 4 CPU·8 GiB RAM·3D 꺼짐으로 재시도해 일본어 바탕화면과 Guest Additions 7.2.20을 확인했다. VirtualBox 무인 설치는 빈 비밀번호를 거부해 간단한 비밀번호로 자동 로그인을 구성했다. 게스트 기본 시스템·UI 로캘과 한국어 언어 기능을 확인했다. `evidence:client` (후속 VM 실행 및 `locale-verify.log`).
3. 게임 실행 전에 NIC1 host-only·NIC2 없음과 게스트 외부 인터넷 차단을 확인하고 `clean` 스냅샷을 만들었다. ISO는 읽기 전용 광학 드라이브로 연결했다. `evidence:client` (E-103)
4. 파일 목록·레지스트리를 설치 전후 수집하고 원본 게임을 VM 안에서만 설치했다. 설치 파일 2,194개는 정적 추출본과 SHA-256이 모두 일치했다. `installed`와 `installed-verified` 스냅샷을 만들었다. `evidence:client` (E-105~107)
5. 2026-09-27 당시 호스트 전용 IP의 Kotlin 스텁은 기동했으나 게스트에서 해당 IP의 47900 포트로 TCP 연결이 시간 초과됐다. 호스트 방화벽 설정은 변경하지 않았다. VM 내부 `127.0.0.1:47900`에 캡처 스텁을 띄워 원본 `exe\G7MTClient.exe 127.0.0.1 47900 ginei00 1 dummy`를 세 차례 실행했다. 3D를 끄고 켠 상태 모두에서 창과 TCP 연결이 없었다. 그 시점에는 첫 0x34를 확보하지 못했다. 아래 2026-09-28 재시험에서 원인을 구분하고 첫 프레임을 받았다. `evidence:client` (E-108·113~122)

앞선 VirtualBox·VMware 기동 실패의 원인을 DirectX 8이나 3D 설정으로 단정하지 않는다. VMware 복제본은 오디오 장치를 같은 방식으로 직접 확인하지 않았으므로 VirtualBox에서 확정한 유발 조건을 자동 적용하지 않는다. VMware 임시 작업과 VM은 정상 종료했다. `evidence:client` (E-108·111·112), `evidence:guess` (VMware 원인).

## 오디오 초기화와 첫 프레임 재현 (2026-09-28)

오디오 출력이 꺼진 VirtualBox 게스트에는 `Win32_SoundDevice`와 현재 `AudioEndpoint`가 없었다. 클라이언트와 같은 세션 1에서 먼저 시작한 DBWIN 수신기는 `IKSound System: Init failed `를 기록했고, 2분 후에도 창 핸들 0·TCP 연결 없음이었다. 정적 클라이언트는 사운드 스레드 실패 시 `SetEvent`를 호출하지 않고 반환하는데, 호출자는 무기한 이벤트를 기다린다. 변경 전 `pre-audio` 스냅샷을 만들었다. `evidence:client` (동적 E-113~115, 정적 사운드 경로).

전원을 끈 상태에서 `VBoxManage modifyvm logh7-win --audio-enabled on --audio-out on --audio-controller hda --audio-driver null`을 적용했다. 게스트의 HDA 장치·스피커 엔드포인트가 OK가 된 뒤 동일 시험에서 사운드 실패 메시지는 사라졌다. 설치 루트를 작업 디렉터리로 두면 리소스 상대 경로가 어긋나므로, 원래 업데이터의 기본 `WORK_DIR=./exe/`를 적용했다. 이때 창 핸들 131762와 첫 원바이트 프레임 28바이트를 받았다. `[00 1A][00 34][암호문 24바이트]`의 복호 결과는 키 길이 16, 초기 sequence 1, checksum `0x287e` 일치다. `evidence:client` (동적 E-116~121, 정적 업데이터 경로).

재현은 호스트 화면을 열지 않고 `VBoxManage startvm logh7-win --type headless`로 시작한다. 게스트 명령·파일 복사는 `guestcontrol`의 `--passwordfile`로 수행하며 비밀번호 값은 기록하지 않는다. 게스트의 임시 예약 작업은 `LogonType=Interactive`, `RunLevel=Limited`로 localhost 수신기 → DBWIN 수신기 → 원본 클라이언트 순서로 띄운다. 클라이언트 인자는 `127.0.0.1 47900 ginei00 1 dummy`, 작업 디렉터리는 설치 루트의 `exe`다. `controlvm screenshotpng`와 `guestcontrol copyfrom`으로 산출물을 `work/logh7-dynamic-p2/captures/audio-triage/`에 보관하고, 임시 작업을 삭제한 뒤 `controlvm logh7-win acpipowerbutton`으로 정상 종료한다. 원바이트 파일·게임 화면은 Git에 넣지 않는다. `evidence:client` (E-113~122).

첫 `0x34` 하나의 길이·checksum은 일치했지만 `0x35`/`0x36`, 골든 프레임 3개, 로그인은 아직 검증하지 않았다. 호스트 전용 IP는 `169.254.44.17/16`(APIPA)였으며 호스트 방화벽 규칙 조회가 접근 거부라 시간 초과 원인은 미확정이다. 호스트 설정은 바꾸지 않았다. `evidence:client` (첫 프레임·IP 관찰), `evidence:guess` (호스트 IP 장애 원인).

[Microsoft 공식 미디어 생성 도구](https://www.microsoft.com/en-us/software-download/windows10)는 `E:\Tools\WindowsMedia\MediaCreationTool_22H2.exe`에 받았다(SHA256 `690C8A63769D444FAD47B7DDECEE7F24C9333AA735D0BD46587D0DF5CF15CDE5`, Microsoft Corporation 서명 `Valid`). 사용자의 후속 실행 지시에 따라 도구를 실행했고, 실제로 C:에 임시 다운로드 파일이 생성됐다가 도구 처리 중 상당 부분 회수됐다. ISO 최종 저장은 E:였다. `evidence:manual` (공식 도구 경로), `evidence:client` (다운로드·서명·파일·공간 변화).

## 일본어·한국어 로캘 준비

시스템 로캘은 한 번에 하나만 지정한다. 원본 클라이언트의 Shift_JIS/CP932 경로 때문에 기본값을 `ja-JP`로 두고, 같은 Windows 사용자에게 한국어 언어·입력기와 글꼴을 추가했다. Windows 10은 사용자 언어 태그를 `ja`·`ko`로 정규화해 반환한다. CP949 패치본 검증 때 시스템 로캘을 `ko-KR`로 바꿀 필요가 있는지는 별도 복제 스냅샷에서 실험한다. 한국어 입력기의 등록과 글꼴 파일은 확인했지만 게임 안의 한글 표시는 아직 검증하지 않았다. `evidence:client` (ADR-0004의 E-020·E-026, VM의 `locale-verify.log`), `evidence:guess` (CP949 실험 계획).

1. 일본어 Windows 10 x64를 VM에 설치한다. Windows Update 접근이 필요한 OS·언어 기능 설치 중에만 NAT를 켜고, 원본 게임은 이 단계에서 실행하지 않는다. 로그인 계정은 자동 로그인·빈 비밀번호로 구성한다. `evidence:guess`
2. Guest Additions 설치 후 저장소의 `tools/vm/prepare-win10-locales.ps1`을 읽기 전용 공유 폴더 또는 사본으로 게스트에 전달한다. 게스트 **관리자 PowerShell**에서 `powershell -NoProfile -ExecutionPolicy Bypass -File .\prepare-win10-locales.ps1 -Mode Prepare`를 실행한다. 스크립트는 VirtualBox의 Windows 10 x64 모델이 아니면 종료하며, 일본어·한국어 기본 언어 기능과 보조 글꼴을 설치한다. 현재 사용자 언어 목록은 일본어→한국어 순서로 설정한다. `evidence:manual` ([Windows 언어 기능](https://learn.microsoft.com/en-gb/windows-hardware/manufacture/desktop/features-on-demand-language-fod?view=windows-10), [사용자 언어 목록](https://learn.microsoft.com/en-us/powershell/module/international/set-winuserlanguagelist?view=windowsserver2025-ps)).
3. 게스트를 재부팅한 뒤 같은 관리자 PowerShell에서 `powershell -NoProfile -ExecutionPolicy Bypass -File .\prepare-win10-locales.ps1 -Mode Verify`를 실행했다. `E:\VM\logh7-win\locale-verify.log`의 `RESULT=PASS`와 JSON에서 `SystemLocale=ja-JP`, `UserInterfaceCulture=ja-JP`, 사용자 언어 `ja`·`ko`, 양쪽 입력기, 네 기능의 `Installed`, 양쪽 글꼴 파일을 확인했다. 입력기에서 실제 일본어·한국어 타이핑과 게임 화면의 한글 표시는 후속 검증으로 남긴다. `evidence:manual` ([시스템 로캘 변경 후 재부팅](https://learn.microsoft.com/en-us/powershell/module/international/set-winsystemlocale?view=windowsserver2025-ps), [입력기 목록 조회](https://learn.microsoft.com/en-us/powershell/module/international/get-winuserlanguagelist?view=windowsserver2025-ps)), `evidence:client` (VM 검증 로그).
4. 성공 결과를 저장한 뒤 NAT를 끄고 NIC1 host-only·NIC2 none을 확인해 `clean` 스냅샷을 만들었다. CP949 PoC는 원본이 아닌 사본에서 수행하며, 필요한 경우 `clean`에서 분기한 시험 스냅샷의 시스템 로캘만 한국어로 바꾸고 재부팅해 비교한다. `evidence:client` (VM 설정·스냅샷), `evidence:guess` (PoC 계획).

언어 설치가 Windows Update에서 실패하면 기능 상태와 오류를 기록하고 NAT·Windows Update 접근을 확인한다. 영어 ISO에 일본어 기본 기능만 더해서 일본어 UI 전체가 설치됐다고 간주하지 않는다. 일본어 이미지의 실제 UI와 클라이언트 표시까지 게스트에서 확인한다. `evidence:guess`

게스트에 `prepare-win10-locales.ps1`을 복사하고, NIC1을 일시적으로 NAT로 전환해 한국어 기본 언어 기능과 보조 글꼴을 설치했다. 설치와 재부팅 후 검증은 각각 UAC 승인 아래 실행했으며, 로그 두 개가 모두 `RESULT=PASS`다. NIC1은 host-only로 복귀했고 VM은 정상 종료한 `poweroff` 상태다. 빈 비밀번호 대신 VirtualBox 무인 설치가 허용하는 간단한 비밀번호를 쓰며 자동 로그인한다. `evidence:client` (게스트 로그·VM 설정·화면 관찰).
