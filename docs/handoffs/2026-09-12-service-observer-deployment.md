# 수리·보급 및 관전자 동기화 배포 핸드오프

> 후속 공적 구현 최신 회귀: 전체988/988,0skip,57초,exit0 (`E:/logh7-build/test-results/mission-result-20260912/rank-achievement-family-full.trx`). 일반 승진0/강등100을 계급 변경과 동일 트랜잭션에서 적용하고, 재전송은 현재 계급·공적을 반환한다.0704/0705/0706 응답 및 자기 캐릭터0323 갱신도 DB 공적을 사용한다. 특별승진은 미회수 정책을 만들지 않고 기존 공적 보존. 이 변경과migration0044는 **미배포**다. 타 대상 갱신·직무 카드 상실·전체 인사 권한·자동 승진·이벤트 재생은 별도 미완료.

> 최신 미배포 변경: migration0044로 캐릭터 공적(u32 범위, 기본0)을 독립 저장하고 ListCharacters→RestoreCharacter→0323 공개 패킷까지 연결했다. 임의 보상 계산은 추가하지 않았다. 실제DB2개 사례 및 전체983/983,0skip,46초,exit0 확인. 영수증 `E:/logh7-build/test-results/mission-result-20260912/character-achievement-full.trx`. 배포 PID1724는 여전히 migration43 기준이며 이 변경은 포함하지 않는다. 다음 배포에서44개 마이그레이션과 캐릭터 행 기본값/기존값 보존도 검증할 것.

> 최신 임무 조사: 보관된 공식 업데이트 안내에서 최고사령관 선출(계급→공적), 아군 전체 임무 지시창, 온라인 자율 수행, AI ON 오프라인 캐릭터의 자동 수행,6종 임무 보상 조건을 찾았다. 앞서 제안한 개인 휘하 함대 전용 요격 AI는 원본 전체 설계가 아니므로 **대체됨/미구현**. `../../work/20260904-warp-state-reverse/evidence/mission-effect-gap-20260912.md` 맨 위 절을 먼저 읽을 것. 배포된 대상별 임무 릴레이는 여전히 기능 불완전이다.

## 최신 배포: 요청자 가드 포함 후보로 교체

아래의 기존 PID2976 및 미배포 표기는 과거 단계다. 최신 서버는 **PID1724**, 시작UTC `2026-09-12T13:21:21.6725319Z`, 게스트 departure-v124 루트의 `server-mission-actor-guards-20260912/Logh7.Server.exe`.

- DLL SHA256 `49F0B8A883A3597D0C1CCC1D24EAE07C2C98B28F6C1FB71DBA84B723C3DC3D5C`, 빌드 후보와 일치.
- 두47900 리스너 확인, 마이그레이션43→43, 전체 original_grid_unit/original_fleet_unit JSON 행 교체 전후 동일.
- 배포 영수증 `E:/logh7-build/server-mission-actor-guards-attempt2-20260912.json`, 상태 `SERVER_RUNNING_UNIT_FLEET_STATE_PRESERVED`.
- 백업 `actor-guards-backup-20260912.dump`는 기존 게스트 루트에 보존. 137973바이트, SHA256 `E11498498292EB0BFEA618926E3D3653AEAA312C29374DB4EA70BBAD67A4F34A`, pg_restore 목록221행. 호스트 영수증 `E:/logh7-build/actor-guards-backup-20260912.json`. 별도 복원 시험은 하지 않음.
- 최초 시도는 ZIP 전송이 진행 중인 파일 잠금 때문에 해시 검사 단계(line23)에서 종료됐다. 서버 종료/압축 해제 전에 실패했으며 `server-mission-actor-guards-attempt1-20260912.json`에 보존. 전송 세션59972의 exit0을 확인한 뒤 새 attempt2 영수증으로 재시도했다. 재시도는 기존 서버 경로/시작 시각과 무접속을 재검사하고 PID2976만 종료했다.
- 기존 서버 디렉터리와 모든 백업/시도 기록을 보존. 게임 종료 명령은 실행하지 않았다. 로그인·실제 전투·수리 화면 성공은 여전히 미검증.

이번 배포에는 post-deployment 지원 효과 관전자 갱신/격침 보급 거절, 임무 대상 종류 검사, 임무·지원함 Order 검사,043C 인코더가 포함된다. **NPC 임무 수행,043C 실제 완료 발생,지원 작업 완료·취소 스케줄은 구현되지 않았다.** 배포와981개 테스트를 전체 게임 완성으로 간주하지 말 것.

### 배포 후 실제 상태 재확인

`E:/logh7-build/actor-guards-postflight-20260912.json`, UTC13:23:00.0217647: 서버1724와 기존 게임5968이 동일 경로/시작 시각으로 responding=true. 서버의 두47900 리스너 및 PostgreSQL55432로 향하는 Established 연결이 관찰됐고 게임 프로세스의 TCP 연결은 없다. DB 연결을 게임 클라이언트 접속으로 세지 말 것(기존 배포 스크립트는 모든 Established를 보수적으로 거절하므로 향후 DB 연결에도 교체가 거절될 수 있다).

VMware CLI 캡처 `actor-guards-postdeploy-screen-20260912.png`는 검은 프레임. Computer Use로 반환된 유일한 VMware 창을 활성화하고 접근성 상태를 확인한 뒤 `actor-guards-after-activate-20260912.png`를 다시 캡처했으나 여전히 검은 프레임이다. 활성화만으로 캡처가 복구되지 않았음. 로그인/클릭/게임 재시작 입력은 보내지 않았다. 프로세스 응답과 정상 영상/로그인은 별개이며 네이티브 플레이 확인은 여전히 미완료다.

## 확인된 배포

- VMware CLI로 기존 서버 PID 980의 경로/시작 시각과 접속 없음 재확인 후 서버만 교체. 게임은 종료하지 않음.
- 신규 서버 PID 2976, 시작 UTC `2026-09-12T12:32:41.1183776Z`.
- 게스트 departure-v124 루트의 `server-service-observers-20260912/Logh7.Server.exe`.
- DLL SHA256 `CE3A33345952D207FFEDCD46E1C5CC38B5C39007CA0BA964B294F5709CC0873B`.
- 두 수신 대기 포트 정상. 마이그레이션 43→43. 재시작 전후 전체 original_grid_unit/original_fleet_unit 행 동일.
- 호스트 영수증 `E:/logh7-build/server-service-observers-20260912.json`.
- 이전 서버 디렉터리 및 DB 백업 보존. 백업은 pg_restore 목록 검증까지이며 별도 DB 복원 시험은 하지 않음.

## 이번 구현

- 원본의 지원함 거리 조건: 3D 거리 < 1. 기존 55개 각도 구간 해석은 오류이며 0x37은 레코드 stride.
- 실제 수행 지원함에 incarnation별 실행 잠금, 중복 명령 거절.
- NPC도 실행 잠금 중 이동·사격 정지, 만료 후 정상 재개. 1800틱 대기 후 이동 누적 없음 검증.
- 명령 응답에 서버 시작 시각과 1800틱 기간 반영. 요청자의 명령별 대기는 별도 유지.
- 다른 플레이어에게도 시작 응답 전송. 이미 대상 함선을 불러온 관전자에게 수리 후 0325 갱신 누락 수정.
- 042D/042E 완료 통지 인코더 추가. **실제 완료 스케줄에는 아직 연결하지 않음.**

전체 테스트 962/962, 0 skip. 영수증 `E:/logh7-build/test-results/service-completion-20260912/service-observer-full.trx`. Release win-x64 자체 포함 빌드 성공. 테스트/배포는 실제 플레이 성공 증거가 아님.

## 다음 작업: 완료·취소를 실제로 연결

### 배포 이후 추가된 소스 변경

현재 PID2976/해시CE3A3334에는 다음 수정이 포함되지 않는다: 자기 기함 보급의 관전자0325 갱신, 기지 수리의 관전자0325 갱신, 저장 단계의 격침 기함 보급 거절. 각각 실제DB15개/기지지원18개 테스트로 확인했으며 별도 전체 회귀 결과는 상세 영수증에 기록한다. 새 배포 전 후보를 다시 빌드해야 한다.

관련 소스 파일은 현재 Git에서 untracked 상태이므로 `git diff`만으로 수정 유무를 판단하지 않는다. 이번 작업에서 커밋/푸시는 하지 않았다. 파일·테스트 결과·배포 DLL 해시를 함께 확인한다.

### 임무 조사 이후 전체 회귀 확인

누적 소스 상태로 전체 `Logh7.Server.ProtocolTests`를 다시 실행: **973/973 통과, 0 skip**, exit0, 38초. 실제 격리 PostgreSQL(호스트55439)을 사용했다. 영수증: `E:/logh7-build/test-results/mission-result-20260912/mission-and-support-full.trx`.

배포 이후 위 지원함 수정 외에 `043C NotifyMissionResult`의 원본 packed14바이트 인코더와 `0421` 임무별 TargetKind 검사가 추가됐다. 결과 통지는 아직 실제 임무 완료에 연결하지 않았다. 원본 조사·RED/GREEN 테스트·미확인 항목은 [임무 실행 공백 조사](../../work/20260904-warp-state-reverse/evidence/mission-effect-gap-20260912.md) 참조.

이 전체 회귀는 새 배포나 네이티브 플레이 성공을 의미하지 않는다. 요격 임무의 지속 저장/수동·임무·자율 제어 구분 설계는 제안 후 사용자 답변 대기 상태이며 아직 구현하지 않았다. 기존 서버/DB는 이 검증에서 재시작하거나 변경하지 않았다.

후속 수정: 임무0421 및 지원함 수리·보급0413/0414의 Order를 세션 캐릭터 ID와 대조하도록 보완. 잘못된 요청자0/999의 수락을 RED로 재현한 뒤 수정했으며, 정상 실행 및 거절 시 부작용 없음 검사 포함 **전체981/981,0skip,43초,exit0**. 최신 영수증 `E:/logh7-build/test-results/mission-result-20260912/actor-guards-full.trx`. 이 가드들도 미배포다. 플레이어 기함은 마이그레이션0021에서 unit_id=character_id를 강제하므로, 임무의 ID 구분 테스트는 별도 수신 함대900과 명령자 캐릭터를 사용했다. DB 제약은 변경하지 않았다.

### 배포 후보 빌드 및 새 VM 사전 확인

`dotnet publish -c Release -r win-x64 --self-contained true --no-restore` 성공. 호스트 후보 `E:/logh7-build/server-mission-actor-guards-20260912`, 마이그레이션43개.

- DLL SHA256 `49F0B8A883A3597D0C1CCC1D24EAE07C2C98B28F6C1FB71DBA84B723C3DC3D5C`.
- EXE SHA256 `C525060083F0F0CF7860D482B3C16E3E392B29BF9CFB5678DAB7551BFE3F99A4`.
- ZIP `E:/logh7-build/server-mission-actor-guards-20260912.zip`, SHA256 `EB3A96BE17DF7C43EF3BA36C128866867FC8E3AD9480DDFF356A183AAC849E2E`.

VMware CLI로 새 읽기 전용 사전 확인 스크립트를 전송·실행하고 영수증을 회수했다: `E:/logh7-build/actor-guards-preflight-20260912.json`, UTC13:17:46.8353809. 서버2976은 기존 경로/시작 시각 그대로, 게임5968도 기존 경로/시작 시각 그대로이며 둘 다 responding=true. 서버47900 리스너2개만 있고 해당 프로세스의 Established 연결은 없다. 이는 그 시점의 관찰이며 실제 교체 직전에 재확인해야 한다.

**이 후보는 아직 게스트 배포하지 않았다.** 기존 백업·배포 스크립트의 보호 절차를 읽었으며, 새 백업 영수증/덤프 이름 및 새 ZIP 해시로 다음 배포를 준비해야 한다. 예전 백업 영수증을 새 것으로 간주하거나 기존 출력 파일을 덮어쓰지 말 것. 서버·게임 종료/재시작은 이번 준비 단계에서 하지 않았다.

서버 중단 중에도 작업 시간을 계산할지는 사용자에게 질문한 상태다. 현재 틱은 프로세스별epoch이므로 틱만영속저장하면안된다. UTC완료시각은중단시간도계산하는정책이고, 남은시간checkpoint는중단시정지하는정책이다. 원본규칙은미회수이며응답전선택하지말것.

현재 회복 효과는 즉시 적용되는 임시 설계다. 실행 잠금에는 발행자/대상/완료 콜백이 없고, Stop/StopFleet은 자기 기함만 처리한다. 발행자·수행함·대상 각각의 식별/세대, 시작·완료 시각, 취소 상태를 가진 서비스 작업이 필요하다. 그리드 잠금 아래 예약·완료·취소를 처리하고, NPC나 접속자 유무와 무관하게 완료를 진행해야 한다.

검증: 두 발행자의 동일 지원함 경합, 완료 직전/정각, 타인 취소 거절, 정당한 취소, 대상/수행함 격침·세대 변경, 재접속·재시작, 관전자 및 무NPC 그리드.

원본 042D 소비자는 수행함 ID를 두 번 조회하는 불일치가 있다. 대상 피해를 잘못 덮지 않도록 실제 소비 동작과 후속 권위 상태 갱신을 검증해야 한다. 원본 수치와 임시 회복 정책을 혼합하지 말 것.

## 화면 상태

앞서 로그인 화면 캡처는 성공했으나, 배포 후 `E:/logh7-build/service-postdeploy-20260912.png`는 다시 검은 프레임이다. 캡처 명령 성공과 정상 화면은 별개이며, 이것만으로 게임 종료/충돌이라고 판단하지 않는다. 로그인·HUD·수리 효과의 실제 화면 검증은 미완료.

상세 함수·파서·필드·테스트·배포 추적은 `../../work/20260904-warp-state-reverse/evidence/service-command-timing-20260912.md` 참조. 기존 PID는 사용 직전 다시 확인한다. 전체 목표 ACTIVE.
