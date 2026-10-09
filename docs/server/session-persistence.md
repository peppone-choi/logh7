# 세션 checkpoint·journal 복구

작성: 최병호 · 2026-10-09 · LOGH-43

## 구현과 적용 범위

`FileSessionStore`는 현재 `SessionSimulation`의 전체 상태와 엔진의 elapsed, restartAt, revision, accepted/rejected 카운터를 보존한다. generation, 밀리초 나머지를 포함한 gameMillis, 전략/CP 카운터, 참가자의 세력·원본 여부·접속 상태, 제외 기록, 전투별 시작 시각과 tick, 종료 이유·대상·시각을 포함한다. 개수만 담은 `WorldSnapshot`은 복구 입력으로 사용하지 않는다. RNG나 전략 작업·조직·지도·재고는 현재 이 시뮬레이션에 없으며 이번 저장 형식에 추가했다고 주장하지 않는다.

공유 경계의 구현 담당은 `WorldEngine` 한 곳이다. 엔진 소유 `SessionDurability` 인터페이스로 persistence의 순환 의존을 피하고, `transitionSession`을 실시간 후보 계산과 저널 검증에 함께 쓴다. 기존 메모리/세대 marker 경로는 유지한다. 저장 경로에서는 세대 marker를 따로 advance하지 않고 전체 상태 저널을 세대의 단일 기준으로 사용한다.

서버 시작 시 `LOGH7_SESSION_STORE_DIR`를 저장소 밖의 로컬 디렉터리로 지정하면 실제 `app → WorldEngine → EngineGameAdmission → GameExchange` 입장 경로가 연결된다. 상위 디렉터리는 미리 존재해야 한다. 이 변수를 지정하지 않은 기본 실행에는 전체 세션 내구성이 없다. 새 저장소의 최초 generation은 기존 세대 marker에서 가져오며, 기존 저장소에서는 저널의 generation을 사용한다. marker와 캐릭터 파일은 전체 세션으로 자동 변환하지 않는다. 저장 경로를 끄고 오래된 marker로 되돌아가는 운영 전환에는 별도 마이그레이션이 필요하다.

## 기록·응답 순서

1. 이전 상태에서 분리된 후보를 계산한다. 잘못된 도메인 명령은 부분 변경을 버리고 거절 카운터만 기록한다.
2. 순번, 규칙 fingerprint, 명령, 전체 결과 상태, 입장 판정, 이벤트를 하나의 체크섬 frame으로 append한다.
3. 파일 `force(true)`가 반환하고 필요한 checkpoint 교체까지 성공한 다음 후보 상태를 게시한다.
4. 실제 입장 future를 완료한다. 그 후 gateway가 성공 `0x0206` ACK를 만들 수 있다. 이벤트 callback은 게시 후 호출한다.

append/force/checkpoint 오류 시 엔진 상태와 성공 ACK, 이벤트를 게시하지 않는다. 해당 엔진의 후속 변경도 차단한다. 파일에 쓰였지만 응답하지 못한 명령이 다음 부팅에서 복원될 수 있다. 성공 응답이 없다는 이유로 같은 프로세스에서 다시 append하지 않는다. 이 구현은 클라이언트 업무 명령의 멱등 키나 응답 재전송을 제공하지 않는다.

## 형식과 복원

schema 1, 모든 `SessionRules` 필드의 SHA-256 fingerprint, 길이 제한 8 MiB, frame payload SHA-256을 사용한다. Java 객체 역직렬화는 사용하지 않는다. checkpoint는 genesis, cutSeq와 해당 전체 상태를 담는다. 256개 기록마다 임시 파일을 force하고 atomic rename한 뒤 디렉터리를 force한다. 단일 writer 파일 lock을 유지한다.

저널은 시작 순번 1부터 엄격하게 읽는다. 인접한 동일 순번·동일 바이트의 중복은 한 번만 적용한다. 다른 중복, 순서 역전, 누락, 부분 tail, checksum/schema/rules 불일치, 재계산한 상태·입장 판정·이벤트 불일치는 부팅을 중단한다. 손상된 tail을 자동으로 자르지 않는다. checksum은 우발적 손상 검사이며 인증 서명이 아니다.

현재 버전은 저널을 삭제·압축하지 않는다. 부팅 시 전체 저널을 스트리밍 검증하고 checkpoint의 cut 상태를 대조한 뒤 후속 명령을 적용하므로, 기록 수에 비례한 부팅 시간이 든다. checkpoint가 있다고 이전 구간 검증을 건너뛰는 최적화는 아직 없다. 실제 운영의 보관/compaction 정책과 DB 마이그레이션은 후속 작업이다.

복구 과정에서는 참가자와 제외 기록을 유지하고 online=false, connection=null 변경을 별도 `RecoverConnections` 기록으로 강제 저장한다. 원래 전투 이벤트 순서를 위해 map 삽입 순서도 보존한다. 복구된 elapsed를 ticker의 새 원점에 더하고 restartAt도 복원한다. 서버 정지 시간은 게임 시간을 자동 진행시키지 않는다. 정상 종료는 actor queue를 비운 다음 파일 lock을 닫는다.

## 검사와 남은 범위

전체 상태와 후속 advance 이벤트 동등성, 종료/세대 재시작, 입장 규칙, checkpoint cut, 동일/다른 중복, 순번 누락/역전, checksum/schema/rules 불일치, 잘못된 결과 상태/입장/이벤트, 부분 tail, writer 중복과 실제 checkpoint 경로 오류를 검사한다. 실제 `EngineGameAdmission` future가 append와 force 사이에서 미완료임을 확인하고, `GameExchange`의 암호화된 성공 ACK가 force 뒤에만 생성되는 것과 저장 오류의 성공 ACK 0건을 검사한다. 이 검사는 합성 프로토콜 교환이며 원본 클라이언트 실행/새 TCP 연결 시험은 아니다.

별도로 생성한 JVM을 다음 다섯 지점에서 `destroyForcibly`로 종료하고 새로운 JVM을 실행했다: append 전, append 후 force 전, force 후 상태 게시 전, checkpoint force 후 rename 전, checkpoint rename 후. 새 프로세스에서 전체 상태 hash와 접속 정리·재입장·후속 advance 후 전체 상태 hash를 비교했다. append 후 force 전 기록이 살아남은 것은 이번 프로세스 종료 관측이며 전원 장애 내구성 보증으로 확대하지 않는다.

로컬 Linux 파일/디렉터리 force와 atomic move를 사용한다. 해당 기능을 제공하지 않는 파일시스템에서는 오류를 숨기지 않고 시작/쓰기를 중단한다. Windows/다른 파일시스템 실행, 실제 전원 장애, 외부 백업·PostgreSQL/WAL/PITR, RPO ≤300초와 RTO, 이벤트 구독자의 부작용/outbox 복구는 미검증 또는 미구현이다. LOGH-43 전체 완료로 처리하지 않는다.
