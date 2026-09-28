# LOGH7 원본 클라이언트 기동 정지 원인과 첫 프레임

작성: 최병호 · 실행일: 2026-09-28(KST) · 범위: LOGH-20·22. 게임 설치·클라이언트 실행은 VirtualBox 격리 VM에서만 수행했다. 호스트 데스크톱·방화벽·어댑터·원본 게임 파일은 건드리지 않았다. `evidence:client`

## 1. 읽음과 실행함

**읽음:** `AGENTS.md`, 사용자 작업 지시, 동적·정적 케이스의 승인 범위와 `case-guard`, `docs/protocol/kex-envelope.md`, 원본 업데이터의 `WORK_DIR` 정적 자료, VirtualBox `guestcontrol`·`modifyvm`·`snapshot` CLI 도움말을 확인했다. 읽은 사실을 실행 결과로 간주하지 않았다. `evidence:client`

**실행함:** 두 케이스의 `case-guard`를 통과했다. `disas.py`·`callers.py`로 사운드 이벤트 대기와 업데이터 기본 작업 디렉터리를 재현했다. VirtualBox를 헤드리스로 부팅해 게스트 세션 1의 임시 예약 작업으로 localhost 수신기·DBWIN 수신기·원본 클라이언트를 순서대로 실행했다. 기준 시험 후 정상 종료·`pre-audio` 오프라인 스냅샷을 만들고 HDA 출력을 null 호스트 드라이버로 활성화했다. 같은 방식으로 재시험하고 원래 업데이터의 `./exe/` 작업 디렉터리로 수정해 첫 프레임을 받았다. 임시 작업은 모두 삭제했고 VirtualBox·VMware 실행 VM은 0개다. `evidence:client` (정적 E-305·306, 동적 E-113~122).

## 2. 가설 판정

| 가설·단계 | 관찰과 판정 |
|---|---|
| 사운드 스레드 실패 시 호출자가 무한 대기 | 정적 0x616b93 `WaitForMultipleObjects(INFINITE)`, 실패 분기 0x616bc7은 `SetEvent` 없이 반환. 정적 E-305로 확인. `evidence:client` |
| 현 VM에 오디오 출력이 없어서 사운드 초기화 실패 | 변경 전 `audio_out=off`, 장치·엔드포인트 없음. DBWIN에 클라이언트 PID의 `IKSound System: Init failed `, 2분 뒤 창·TCP 없음. 동적 E-113~115. `evidence:client` |
| 오디오 출력 제공 시 같은 지점 통과 | `pre-audio` 스냅샷 후 HDA 출력 활성화. 게스트 장치·스피커 엔드포인트 OK, `Init failed` 소멸, 다음 리소스 초기화까지 진행. 동적 E-116·117. 이번 VirtualBox 정지의 **직접 유발 조건 확정**. `evidence:client` |
| 설치 루트가 올바른 직접 실행 작업 디렉터리 | **기각.** 설치 루트 실행은 `../data`를 잘못 찾고 종료했다. 업데이터 기본 `WORK_DIR=./exe/`(정적 E-306)로 바로잡자 창·TCP가 생성됐다(동적 E-118~121). `evidence:client` |

메인 스레드 덤프·복귀 주소의 직접 측정은 수행하지 않았다. 실패 출력, 정적 무한 대기 분기, 오디오만 바꾼 전후 차이와 정상 진행을 함께 근거로 판정한다. VMware 복제본의 오디오 장치 상태는 직접 측정하지 않았으므로 동일 원인이라고 확정하지 않는다. `evidence:client` (측정 범위), `evidence:guess` (VMware 일반화).

## 3. 첫 프레임과 검증

게스트 localhost `127.0.0.1:47900`에 원본 클라이언트가 접속해 **28바이트 첫 프레임**을 보냈다. 길이 필드 `0x001a`는 type 2바이트와 암호문 24바이트의 합이고 type은 `0x0034`다. 정적 wrapping key로 복호하면 A 키 길이 16, 초기 sequence 1, wire checksum `0x287e`가 재계산 값 `0x287e`와 일치하며 추가 패딩은 없다. 원바이트 파일 SHA-256은 `d872b1c8c9d20f758180351891edc93d2efd285e2375d26508f9c4aed50c3063`이고 Git 제외 `work/logh7-dynamic-p2/captures/audio-triage/first-frame-cwd.bin`에 있다. 게임 창은 CLI 스크린샷과 창 핸들 131762로 확인했다. `evidence:client` (E-119~121).

동적 케이스 `review_case.py --verify-hashes --strict`는 Evidence 23·Finding 7·Path 1, 오류·경고 0으로 PASS했다. 첫 프레임 한 개의 구조만 확인했으며 `0x35`/`0x36`, 골든 프레임 3개, 로그인 성공은 아직 미검증이다. 따라서 LOGH-20의 첫 프레임 목표는 달성했지만 LOGH-22 전체 완료 조건은 진행 중이다. `evidence:client` (E-119·120, 케이스 검토).

## 4. 리스크와 다음 작업

1. 오디오 출력 장치가 없는 플레이어 PC에서도 같은 영구 대기가 날 가능성이 있다. 실제 PC 일반화는 미검증이며 런처의 오디오 사전 점검을 백로그 후보로 둔다. `evidence:client` (원본 제어 흐름), `evidence:guess` (다른 PC 영향).
2. 호스트 전용 IP `169.254.44.17/16`은 APIPA이고 기존 게스트→호스트 TCP 시간 초과의 원인은 미확정이다. 호스트 java 인바운드 규칙 조회는 접근 거부였고 설정은 변경하지 않았다. localhost 키 교환 실험은 이 경로와 독립적으로 진행할 수 있다. `evidence:client`.
3. 다음 세션은 첫 원바이트를 기준으로 서버 `0x35` 응답·클라이언트 `0x36` 수신, 골든 프레임 3개, 로그인 교환 순서로 확인한다. `evidence:guess` (실행 순서).

## 5. 사용자 미결

호스트 전용 IP·방화벽 변경은 승인되지 않아 수행하지 않았다. 추가 외부 디버거 다운로드도 승인 범위 밖이며 이번 확인에는 필요하지 않았다. 정적 케이스는 허용된 `notes/`·`evidence/`에만 신규 기록을 남겼다. 그 케이스의 새 Evidence 두 개는 보고서·타임라인 수정 제한 때문에 strict 검토에서 연결 경고 두 개가 남고, 동적 케이스는 strict PASS다. `evidence:client`

## 6. 출처

- 정적 케이스 `work/logh7-client-triage/evidence/E-305.md`, `E-306.md`와 디스어셈블 원자료(모두 Git 제외).
- 동적 케이스 `work/logh7-dynamic-p2/evidence/E-113.md`~`E-122.md`, `captures/audio-triage/`, `report/case-review.md`(모두 Git 제외).
- [키 교환·암호 봉투 명세](../protocol/kex-envelope.md), [접속 흐름](../re/connection-flow.md), [격리 VM 절차](../ops/isolation-vm.md).
