# 격리 실행 환경

작성: 최병호 · 2026-09-27 · LOGH-5·6·20

## 현재 상태

VirtualBox 7.2.20을 `E:\VirtualBox`에 설치했고 `logh7-win` VM 골격을 만들었다. Windows OS·원본 게임은 아직 설치하지 않았다. `evidence:client` (동적 케이스 E-101).

| 항목 | 확인 결과 |
|---|---|
| 실행 파일 | `E:\VirtualBox\VBoxManage.exe`, `7.2.20r175154` |
| Windows VM | `E:\VM\logh7-win\logh7-win.vbox`, UUID `742099e5-d79b-40fb-90e6-a10f12235525` |
| 자원 | 4 GiB RAM, 2 CPU, 64 GiB 동적 VDI, BIOS, VBoxSVGA·3D 켜짐 |
| 네트워크 | NIC1 host-only, `VirtualBox Host-Only Ethernet Adapter`, NIC2 없음 |
| 공유·계정·로캘 | OS 미설치로 미설정. 자동 로그인·빈 비밀번호·ja-JP 시스템 로캘과 ko-KR 입력·글꼴 구성 예정 |
| 스냅샷 | `clean`/`installed` 미생성. 빈 VM을 설치 완료 스냅샷으로 표시하지 않음 |
| ISO | Microsoft 공식 일본어 x64 링크 요청이 SentinelReject. 판본 선택도 요청 처리 오류. 미확보 |

표의 환경 관찰은 `evidence:client` (E-101·E-102), 예정 설정은 `evidence:guess`다.

## G: 조사와 재설치

G:의 과거 MBR 식별자는 `46BE25E5`, 파티션 오프셋은 1 MiB였다. 현재 열거된 물리 디스크 3개(C: Samsung 250 GB, D: Hitachi 500 GB, E: SK Hynix 2 TB) 중 이에 해당하는 디스크가 없었다. 문자 없는 볼륨은 EFI·복구 파티션이었다. 따라서 다른 볼륨에 G:를 부여하지 않았다. `evidence:client` (세션 2 리드 실행 기록).

사용자가 재설치와 `E:\VirtualBox` 예외 경로를 승인했다. 첫 설치는 E:\Tools 상위 폴더 권한 요건으로, 두 번째는 기존 7.0.18 제거 중 `Invalid Drive: G:\`로 실패했다. 빈 전용 폴더 `E:\Tools\virtualbox-legacy-drive`를 관리자 설치 과정 동안만 G:로 연결하여 이전 제품 제거를 끝내고 새 버전을 설치했다. 임시 연결은 finally에서 해제했다. 기존 Ubuntu VDI·설정은 보존했다. `evidence:client`

설치 파일은 [Oracle 7.2.20 공식 배포](https://download.virtualbox.org/virtualbox/7.2.20/)에서 받았다. SHA256 `a81777d2b36380ce042a29e9c554cf032eb46a793f62e3cc82e7411e535c2c26`이 공식 목록과 일치하고 Oracle America 전자서명은 Valid였다. [설치 폴더 요건](https://docs.oracle.com/en/virtualization/virtualbox/7.2/user/installation.html), [subst](https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/subst). 설치·임시 경로는 E:이며 Windows 드라이버·Installer 시스템 등록 파일은 OS 관리 위치에 설치된다. `evidence:client`

## ISO 확보 후 재개

1. [Microsoft 공식 Windows 10 다운로드](https://www.microsoft.com/en-us/software-download/windows10ISO)에서 일본어 x64 ISO를 E:에 확보하고 해시를 기록한다. 직접 링크 API는 거부됐고, ISO 페이지에서 Windows 10 판본 선택 후에도 요청 처리 오류가 났다. Windows 브라우저에서는 미디어 생성 도구 안내로 이동한다. 다른 배포처의 ISO로 대체하지 않았다. `evidence:client` (E-102 및 후속 확인).
2. 기존 `logh7-win`에 일본어 Windows 10 x64 ISO를 연결하고 자동 로그인·빈 비밀번호를 구성한다. OS·언어 설치에 필요한 기간에만 NAT를 허용한다. 아래 게스트 로캘 절차를 마친 뒤 재부팅·검증한다. `evidence:guess`
3. 게임 실행 전에 NIC1 host-only·NIC2 없음과 게스트 외부 인터넷 차단을 확인한다. 원본 공유는 읽기 전용, 결과 반출 폴더만 쓰기 가능하게 설정하고 `clean` 스냅샷을 만든다. `evidence:guess`
4. 파일 해시·레지스트리를 설치 전후 수집하고 원본 설치를 VM 안에서만 수행한다. 설치본 비교 후 `installed` 스냅샷을 만든다. `evidence:guess`
5. 호스트 전용 IP에 스텁을 바인딩한 뒤 VM에서 `exe\G7MTClient.exe <스텁 host> 47900 <세션명> 1 dummy`를 실행한다. 첫 0x34 캡처와 정적 근거를 대조하기 전에는 프레이밍을 validated로 승격하지 않는다. `evidence:client` (기존 E-014·E-028), `evidence:guess` (실행 계획).

DirectX 8·3D 때문에 클라이언트 기동이 실패하면 그 실패를 기록하고 VMware 등 대안을 사용자에게 묻는다. 현재는 OS 설치 전이라 그래픽 호환성 실패를 관찰한 상태가 아니다. `evidence:guess`

[Microsoft 공식 미디어 생성 도구](https://www.microsoft.com/en-us/software-download/windows10)는 `E:\Tools\WindowsMedia\MediaCreationTool_22H2.exe`에 받았다(SHA256 `690C8A63769D444FAD47B7DDECEE7F24C9333AA735D0BD46587D0DF5CF15CDE5`, Microsoft Corporation 서명 `Valid`). 도구가 ISO 생성 중 시스템 드라이브에 임시 파일을 둘 수 있어, C:에 캐시를 두지 않는 저장소 규칙을 보장할 방법을 확인하기 전에는 실행하지 않았다. ISO 파일은 여전히 없다. [Microsoft 안내](https://www.microsoft.com/en-us/software-download/windows10)는 미디어 생성 도구로 ISO를 만드는 경로를 설명한다. `evidence:manual` (공식 도구 경로), `evidence:client` (다운로드·서명·페이지 오류), `evidence:guess` (실행 보류 판단).

## 일본어·한국어 로캘 준비

시스템 로캘은 한 번에 하나만 지정한다. 원본 클라이언트의 Shift_JIS/CP932 경로 때문에 기본값을 `ja-JP`로 두고, 같은 Windows 사용자에게 `ko-KR` 언어·입력기와 한국어 글꼴을 추가한다. CP949 패치본 검증 때 시스템 로캘을 `ko-KR`로 바꿀 필요가 있는지는 별도 복제 스냅샷에서 실험한다. 현재 결과는 설치 전 계획이며 한글 표시를 검증했다는 뜻이 아니다. `evidence:client` (ADR-0004의 E-020·E-026), `evidence:guess` (구성·실험 계획).

1. 일본어 Windows 10 x64를 VM에 설치한다. Windows Update 접근이 필요한 OS·언어 기능 설치 중에만 NAT를 켜고, 원본 게임은 이 단계에서 실행하지 않는다. 로그인 계정은 자동 로그인·빈 비밀번호로 구성한다. `evidence:guess`
2. Guest Additions 설치 후 저장소의 `tools/vm/prepare-win10-locales.ps1`을 읽기 전용 공유 폴더 또는 사본으로 게스트에 전달한다. 게스트 **관리자 PowerShell**에서 `powershell -NoProfile -ExecutionPolicy Bypass -File .\prepare-win10-locales.ps1 -Mode Prepare`를 실행한다. 스크립트는 VirtualBox의 Windows 10 x64 모델이 아니면 종료하며, 일본어·한국어 기본 언어 기능과 보조 글꼴을 설치한다. 현재 사용자 언어 목록은 일본어→한국어 순서로 설정한다. `evidence:manual` ([Windows 언어 기능](https://learn.microsoft.com/en-gb/windows-hardware/manufacture/desktop/features-on-demand-language-fod?view=windows-10), [사용자 언어 목록](https://learn.microsoft.com/en-us/powershell/module/international/set-winuserlanguagelist?view=windowsserver2025-ps)).
3. 게스트를 재부팅한 뒤 같은 관리자 PowerShell에서 `powershell -NoProfile -ExecutionPolicy Bypass -File .\prepare-win10-locales.ps1 -Mode Verify`를 실행하고 JSON 결과를 `E:\logh7\work\logh7-dynamic-p2\`에 반출한다. `SystemLocale=ja-JP`, `UserInterfaceCulture=ja-JP`, 두 `UserLanguages`, 네 기능의 `Installed`, 양쪽 입력기와 글꼴 파일을 확인한다. 스크립트의 `Verify`는 이 조건을 검사하고 JSON을 출력한다. 게스트 화면에서 일본어와 한국어 입력 전환 및 한글 글리프 표시도 직접 확인한다. `evidence:manual` ([시스템 로캘 변경 후 재부팅](https://learn.microsoft.com/en-us/powershell/module/international/set-winsystemlocale?view=windowsserver2025-ps), [입력기 목록 조회](https://learn.microsoft.com/en-us/powershell/module/international/get-winuserlanguagelist?view=windowsserver2025-ps)), `evidence:guess` (검증 절차).
4. 성공 결과를 저장한 뒤 NAT를 끄고 NIC1을 호스트 전용망으로 되돌린 상태를 `VBoxManage showvminfo logh7-win --details`로 확인한다. 그 후 `clean` 스냅샷을 만든다. CP949 PoC는 원본이 아닌 사본에서 수행하며, 필요한 경우 `clean`에서 분기한 시험 스냅샷의 시스템 로캘만 한국어로 바꾸고 재부팅해 비교한다. `evidence:guess`

언어 설치가 Windows Update에서 실패하면 기능 상태와 오류를 기록하고 NAT·Windows Update 접근을 확인한다. 영어 ISO에 일본어 기본 기능만 더해서 일본어 UI 전체가 설치됐다고 간주하지 않는다. 일본어 이미지의 실제 UI와 클라이언트 표시까지 게스트에서 확인한다. `evidence:guess`
