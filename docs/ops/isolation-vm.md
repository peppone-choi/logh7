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
| 공유·계정·로캘 | OS 미설치로 미설정. 자동 로그인·빈 비밀번호·ja-JP로 구성 예정 |
| 스냅샷 | `clean`/`installed` 미생성. 빈 VM을 설치 완료 스냅샷으로 표시하지 않음 |
| ISO | Microsoft 공식 일본어 x64 링크 요청이 SentinelReject. 미확보 |

표의 환경 관찰은 `evidence:client` (E-101·E-102), 예정 설정은 `evidence:guess`다.

## G: 조사와 재설치

G:의 과거 MBR 식별자는 `46BE25E5`, 파티션 오프셋은 1 MiB였다. 현재 열거된 물리 디스크 3개(C: Samsung 250 GB, D: Hitachi 500 GB, E: SK Hynix 2 TB) 중 이에 해당하는 디스크가 없었다. 문자 없는 볼륨은 EFI·복구 파티션이었다. 따라서 다른 볼륨에 G:를 부여하지 않았다. `evidence:client` (세션 2 리드 실행 기록).

사용자가 재설치와 `E:\VirtualBox` 예외 경로를 승인했다. 첫 설치는 E:\Tools 상위 폴더 권한 요건으로, 두 번째는 기존 7.0.18 제거 중 `Invalid Drive: G:\`로 실패했다. 빈 전용 폴더 `E:\Tools\virtualbox-legacy-drive`를 관리자 설치 과정 동안만 G:로 연결하여 이전 제품 제거를 끝내고 새 버전을 설치했다. 임시 연결은 finally에서 해제했다. 기존 Ubuntu VDI·설정은 보존했다. `evidence:client`

설치 파일은 [Oracle 7.2.20 공식 배포](https://download.virtualbox.org/virtualbox/7.2.20/)에서 받았다. SHA256 `a81777d2b36380ce042a29e9c554cf032eb46a793f62e3cc82e7411e535c2c26`이 공식 목록과 일치하고 Oracle America 전자서명은 Valid였다. [설치 폴더 요건](https://docs.oracle.com/en/virtualization/virtualbox/7.2/user/installation.html), [subst](https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/subst). 설치·임시 경로는 E:이며 Windows 드라이버·Installer 시스템 등록 파일은 OS 관리 위치에 설치된다. `evidence:client`

## ISO 확보 후 재개

1. [Microsoft 공식 Windows 10 다운로드](https://www.microsoft.com/en-us/software-download/windows10ISO)에서 x64 ISO를 E:에 확보하고 공식 해시와 대조한다. 직접 링크 API는 이번에 거부됐고 Windows Chrome은 미디어 생성 도구 안내로 이동했다. 다른 배포처의 ISO로 대체하지 않았다. `evidence:client` (E-102).
2. 기존 `logh7-win`에 ISO를 연결하고 일본어 시스템 로캘·자동 로그인·빈 비밀번호를 구성한다. OS/언어 설치에 필요한 기간에만 NAT를 허용한다. `evidence:guess`
3. 게임 실행 전에 NIC1 host-only·NIC2 없음과 게스트 외부 인터넷 차단을 확인한다. 원본 공유는 읽기 전용, 결과 반출 폴더만 쓰기 가능하게 설정하고 `clean` 스냅샷을 만든다. `evidence:guess`
4. 파일 해시·레지스트리를 설치 전후 수집하고 원본 설치를 VM 안에서만 수행한다. 설치본 비교 후 `installed` 스냅샷을 만든다. `evidence:guess`
5. 호스트 전용 IP에 스텁을 바인딩한 뒤 VM에서 `exe\G7MTClient.exe <스텁 host> 47900 <세션명> 1 dummy`를 실행한다. 첫 0x34 캡처와 정적 근거를 대조하기 전에는 프레이밍을 validated로 승격하지 않는다. `evidence:client` (기존 E-014·E-028), `evidence:guess` (실행 계획).

DirectX 8·3D 때문에 클라이언트 기동이 실패하면 그 실패를 기록하고 VMware 등 대안을 사용자에게 묻는다. 현재는 OS 설치 전이라 그래픽 호환성 실패를 관찰한 상태가 아니다. `evidence:guess`
