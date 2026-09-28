# 세션 2 후속 실행: VM 설치·동적 접속

> 이 문서는 첫 프레임 미확보 당시의 중간 상태다. 오디오 초기화 정지 원인과 첫 `0x34` 확보 결과는 [기동·캡처 보고서](2026-09-28-audio-startup-report.md)를 우선한다. `evidence:client` (동적 E-113~122).

작성: 최병호 · 실제 실행일: 2026-09-27~28(KST). [세션 2 보고서](2026-09-28-report.md)의 ISO 미확보 시점 이후에 실행한 내용을 기록한다.

## 1. 가정과 범위

사용자가 VirtualBox `E:\VirtualBox` 설치, 공식 일본어 Windows ISO 준비, 게스트의 일본어·한국어 로캘 준비, VM 안 게임 설치와 UAC 승인을 허용했다. 게임 원본은 읽기 전용으로 두고 VM 안에서만 설치·실행했다. GCP 리소스·호스트 방화벽 설정·외부 공개는 허용 범위에 없다. `evidence:client` (동적 케이스 E-103~108)

## 2. 읽음과 실행함

**읽음:** AGENTS.md의 격리·증거 규칙, `next-session-orchestration.md`의 G-4·G-5와 T0 절차, 기존 정적 키 교환·접속 흐름, reverse-skill 케이스 계약 및 원본 설치본 목록. VM 설정·게스트 검증 로그·Linear LOGH-5·6·20·22도 조회했다. 읽음은 원본 클라이언트와의 상호운용 성공을 뜻하지 않는다.

**실행함:** Microsoft 서명 유효한 공식 Media Creation Tool로 일본어 Windows 10 x64 ISO를 E:에 생성하고 SHA-256을 검증했다. Windows 10 10.0.19045.3803 설치, 자동 로그인, ja-JP 시스템·UI, ja/ko 사용자 언어·입력기와 글꼴 설치 및 `RESULT=PASS` 로그 확인을 끝냈다. NAT는 언어 기능 설치 때만 사용했고, 이후 NIC1 host-only·NIC2 none으로 복귀했다. `clean` 스냅샷 후 사용자 원본 ISO를 VM에 읽기 전용으로 마운트해 InstallShield 설치를 완료했다. 빈 비밀번호는 VirtualBox 무인 설치가 거부하여 AGENTS.md가 허용한 간단한 비밀번호를 사용했다. `evidence:client` (E-103·104)

설치 전후 파일·레지스트리를 수집했다. 지정 루트의 새 파일은 2,231개, 사라진 파일 6개, 크기·수정 시각이 바뀐 파일은 16개다. 게임 설치 디렉터리 2,194개는 정적 추출본 2,194개와 상대 경로 및 SHA-256이 모두 일치한다. 설치 경로 레지스트리, 제거 등록, InstallShield 런타임·바로가기도 관찰했다. 자세한 범위와 한계는 [설치 비교](../re/install-diff.md)에 있다. `evidence:client` (E-105~107)

라이브 `installed` 스냅샷은 VirtualBox 상태 오류로 실패했다. VM 프로세스를 복구한 뒤 오프라인 `installed`를 만들고 게스트 부팅을 확인했다. 클라이언트 시험 후 Windows를 정상 종료하고 `installed-verified`를 추가했다. VM의 현재 상태는 poweroff다. `evidence:client` (E-108)

호스트 전용 IP의 Kotlin 스텁은 기동했으나 게스트 TCP 연결이 시간 초과됐다. 호스트 설정 변경 없이 VM 내부 localhost 캡처 스텁으로 전환해 원본 `G7MTClient.exe 127.0.0.1 47900 ginei00 1 dummy`를 세 차례 실행했다. 3D off 두 번과 on 한 번 모두 프로세스가 응답하지 않았고 첫 90초 동안 TCP 연결이 없었다. VirtualBox 로그의 D3D 기능 조회만으로 DirectX를 원인으로 확정하지 않는다. `evidence:client` (E-108)

| 목표 | 현재 판정 | 이유·조치 |
|---|---|---|
| G-4 VM·원본 설치·비교 | 달성(빈 비밀번호 대체 기록) | 일본어/한국어 기능, host-only, 원본 설치, `clean`/`installed`, 해시·레지스트리 비교 완료. 자동 로그인은 간단한 비밀번호를 사용 |
| G-5 첫 0x34·프레이밍 validated | 미달성 | VirtualBox와 VMware 모두 창·TCP 이전에 정지. 실제 0x34 원자료 없음. 원인은 미확정 |
| G-9 기록·검증 | 동적 케이스·PR 완료, 재시험 기록 갱신 | `review_case.py --verify-hashes --strict` PASS: Evidence 13, Finding 4, Path 1, 오류·경고 0. 게임 저작물은 Git 제외. [PR #12](https://github.com/peppone-choi/logh7/pull/12) 병합 |

## 3. 못 한 것과 다음 조치

첫 0x34 캡처, 0x35/0x36 응답, 실제 로그인, 게임 내 한글 표시·IME 왕복은 수행하지 못했다. 클라이언트가 화면이나 TCP 연결을 만들기 전에 멈추기 때문이다. VirtualBox의 3D 설정 변경과 사용자 승인 VMware 복제본의 대화형 예약 작업 실행까지 비교했지만 결과가 같았다. 호스트 전용 어댑터의 TCP 시간 초과 원인은 별도로 확정하지 않았고, 게스트 localhost 수신기는 LISTENING이었다. 프레이밍 Finding은 candidate다. `evidence:client` (E-108·111·112); 초기화 원인은 `evidence:guess`.

## 4. 리스크 3개와 다음 작업 3개

| 리스크 | 영향 |
|---|---|
| 클라이언트 기동 장애의 원인 미확정 | DirectX·VirtualBox·보호 모듈·기타 초기화 중 무엇인지 아직 구분하지 못함 |
| 호스트 전용 IP TCP 시간 초과 | localhost 실험 외 호스트 Kotlin 스텁 연결까지 별도 확인 필요. 호스트 방화벽 변경은 미승인 |
| 정적 프로토콜 명세의 실제 호환성 미확인 | 합성 벡터와 설치본 해시 일치만으로 첫 키 교환을 증명할 수 없음 |

1. VMware와 VirtualBox 공통의 네트워크 이전 초기화 정지 지점을 진단.
2. 정상 기동되면 VM localhost에서 첫 원바이트 프레임을 캡처하고 길이·0x34·checksum을 정적 근거와 대조.
3. 독립 증거가 맞으면 0x35/0x36과 로그인 교환을 구현·검증하고 한글 표시를 확인.

## 5. 사용자 미결

VMware 재시험은 사용자가 승인했고 수행했다. Windows XP 등 새 게스트 이미지나 새로운 가상화 대안은 결정되지 않았다. GCP 생성·과금·외부 공개는 별도 승인 범위다. `evidence:client` (재시험), `evidence:guess` (후속 대안).

## 6. 출처와 검증 범위

- `work/logh7-dynamic-p2/evidence/E-103.md`~`E-112.md`, `report/report.md`, `report/case-review.md` (Git 제외): VM·언어·설치·실행 시도 원자료와 SHA-256.
- [격리 VM 절차](../ops/isolation-vm.md), [설치 비교](../re/install-diff.md), [정적 키 교환 명세](../protocol/kex-envelope.md).
- [Microsoft Windows 10 공식 다운로드](https://www.microsoft.com/en-us/software-download/windows10), [Oracle VirtualBox 7.2 공식 배포](https://download.virtualbox.org/virtualbox/7.2.20/).
- Linear [LOGH-5](https://linear.app/peppone-choi/issue/LOGH-5), [LOGH-6](https://linear.app/peppone-choi/issue/LOGH-6), [LOGH-20](https://linear.app/peppone-choi/issue/LOGH-20), [LOGH-22](https://linear.app/peppone-choi/issue/LOGH-22).

reverse-skill의 field-journal·참고 문서·색인을 익명화해 로컬 main `76bcc94`와 후속 `5d79236`에만 커밋했다. `refresh-tool-index.ps1`을 실행해 42개 도구를 재탐색했으며 산출 파일의 내용 변경은 없었다. reverse-skill은 push하지 않았다. `evidence:client`

ISO SHA-256은 로컬 생성물의 해시이며 Microsoft가 게시한 공식 해시와 대조한 값은 아니다. 위 strict PASS는 케이스 증거 그래프·해시 무결성 검사로, 클라이언트 접속 성공 판정이 아니다. `evidence:client`

## 7. 2026-09-28 VMware CLI 재시험

VMware Tools를 설치한 격리 복제본에서 게스트 화면과 사용자 세션을 확인했다. `G7MTClient.exe 127.0.0.1 47900 ginei00 1 dummy`를 Windows 셸 및 대화형 예약 작업으로 실행했다. 예약 작업의 클라이언트 PID는 세션 1에서 2분 이상 살아 있었지만 `MainWindowHandle=0`이고 localhost 수신기 외 TCP 연결이 없었다. 첫 프레임 파일도 없다. 시험 프로세스와 예약 작업을 제거하고 VM을 정상 종료했다. 원본 게임 파일과 호스트 설정은 변경하지 않았다. `evidence:client` (E-111·112).

Linear [LOGH-20](https://linear.app/peppone-choi/issue/LOGH-20)의 설명·댓글과 [LOGH-22](https://linear.app/peppone-choi/issue/LOGH-22) 댓글을 갱신했다. 다음 단계는 그래픽 장애를 단정하기 전에 네트워크 이전 초기화가 멈추는 지점을 독립 증거로 확인하는 것이다. `evidence:client` (현 상태), `evidence:guess` (진단 방향).
