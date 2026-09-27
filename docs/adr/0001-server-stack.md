# ADR-0001 서버 스택

- 상태: 채택 (2026-09-27)
- 작성: 최병호
- 관련: ADR-0002, ADR-0003, ADR-0005

## 맥락

- 원본 클라이언트는 x86 Win32(2004-04 빌드)이고 WS2_32의 **원시 TCP 소켓**으로 게임 서버(기본 `202.8.80.179:47900`)와 업데이트 서버(`:47902`)에 붙는다. `evidence:client` (E-004~E-006)
- 게임은 서버가 모든 커맨드를 처리하는 구조(W p.10)이고, 게임 시간은 실시간 ×24로 쉬지 않고 흐르며 전략과 여러 전술 전투가 동시에 진행된다(W p.10, p.12). `evidence:manual`
- 요구: 동시접속 **50명 이상**(설계 한도는 매뉴얼상 세션당 2,000명), 자택 서버 우선, **가능한 한 빠른 오픈**, 관리 툴·모니터링 필요.
- 사용자 숙련 스택: Kotlin/Spring Boot, TypeScript/Next.js. 기존 OpenSAM 계열 경험: Kotlin + 서버 권위 tick 엔진 + PostgreSQL/Redis.

## 선택지

| | A. Kotlin(JVM) + Netty 게이트웨이 + 인메모리 tick 엔진 + Spring Boot 관리 API + PostgreSQL | B. TypeScript(Node) + `net` 모듈 + tick 엔진 + Next.js 관리 + PostgreSQL | C. A와 같되 Redis를 세션 상태 저장소로 사용하는 분산형 |
|---|---|---|---|
| 바이너리 프로토콜 | Netty `ByteBuf`·`LengthFieldBasedFrameDecoder` 등 성숙, 엔디안·Shift_JIS 처리 용이 | `Buffer`로 충분하나 코덱 구조화는 직접 | A와 동일 |
| 실시간 시뮬레이션 | JVM 성능·예측 가능한 GC(ZGC), 코루틴으로 틱 루프 | 단일 스레드 이벤트 루프라 전술 여러 개 동시 계산 시 워커 분리 필요 | 분산 락·직렬화 비용 |
| 사용자 숙련도 | 높음 | 높음 | 중간 |
| 관리 UI | Spring Boot API + Next.js 프런트 분리 | 한 언어로 통일 | A와 동일 |
| 50명 규모 | 과잉 없이 충분 | 충분 | 과잉(복잡도만 증가) |
| 빠른 오픈 | 좋음 | 좋음 | 나쁨 |

## 결정

**A를 채택한다.**

- 게임 서버: **Kotlin + JDK LTS**, 네트워크 계층 **Netty**(TCP 47900 게임 / 47902 업데이트 스텁), 월드 상태는 **단일 프로세스 인메모리**(서버 권위), 전역 게임 시계로 구동되는 **tick 엔진**(전략 틱 + 전술 인스턴스 틱).
- 영속성: **PostgreSQL** — 주기적 월드 스냅샷 + 도메인 이벤트 로그(append-only). 게임 플레이 경로는 DB를 동기 호출하지 않는다.
- 관리: 같은 JVM 안에 **Spring Boot 관리 API**(내부 포트, 인증 필수), 프런트는 **Next.js**(TypeScript).
- Redis는 **도입하지 않는다**(단일 노드 50~수백 명 규모에서 불필요). 필요해지면 재검토.
- 빌드: Gradle(Kotlin DSL), 모듈: `server/protocol`(코덱·메시지 정의) · `server/engine`(규칙·틱) · `server/gateway`(Netty) · `server/admin`(Spring Boot) · `server/persistence`.

### 버전 기준 (2026-09-27 웹 확인, docs/research/tools-and-versions.md)

| 구성 | 채택 버전 | 비고 |
|---|---|---|
| JDK | **25 LTS** (Temurin 25.0.4.1+1) | 설치 위치 `E:\Tools\`. 기설치 JDK 21은 Ghidra용으로 유지 |
| Kotlin | **2.4.20** | 2.5.0 정식(2026-12 예정) 이후 올림 검토 |
| Netty | **4.2.18.Final** | 4.1 라인 대신 4.2 |
| Spring Boot | **4.1.1** | Java 17+ 요구, Gradle 8.14+/9.x |
| PostgreSQL | **18.6** | |
| Node.js (관리 화면) | **24 LTS** (v24.21.0) | 26은 2026-10-28 LTS 전환 예정 |
| TypeScript | 7.0.x | Next.js 호환 확인 후 확정 |
| Docker Desktop | 4.92.0 | 이 PC(Win10 Pro 22H2) 요구 조건 충족 |

## 결과

- 프로토콜 명세(`docs/protocol/`)를 Kotlin 메시지 클래스·코덱과 1:1로 대응시켜 테스트 가능하게 만든다(골든 바이트 테스트).
- 단일 프로세스라 장애 시 전체 중단 → 스냅샷 주기와 재기동 복구 절차가 운영 핵심(ADR-0005).

## 재검토 조건

- 동시접속이 단일 JVM 한계(수천 명)에 가까워질 때 → 세션(서버) 단위 수평 분할.
- 프로토콜 분석 결과 원 서버가 HTTP 기반 등 다른 전송을 쓰는 부분이 발견될 때.
