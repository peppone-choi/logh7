# 서버 골격 구현 메모

작성자: 최병호 · 2026-09-27 · T1 (LOGH-17/61/18/19)

## 범위와 근거

- `evidence:guess` ADR-0001의 6개 모듈(protocol, engine, gateway, ops-api, persistence, app)을 Gradle Kotlin DSL로 구성했다. Spring은 사용하지 않는다.
- `evidence:client` 프레이밍·암호의 출발점은 `protocol-draft.md` §1~3이다. T2 벡터는 정적 계산 후보이며 실제 클라이언트 실행 검증을 뜻하지 않는다.
- `evidence:guess` 메시지 YAML은 name/opcode 쌍만 지원하는 제한된 생성 골격이다. 생성 코덱은 opcode 2바이트만 처리하며 본문 스키마 생성은 후속 범위다.
- `evidence:client` 소켓 길이는 BE u16, 최대 payload 0xF000, frame type은 0x30/31/34/35/36이다. TCP 분할·병합은 Netty length decoder로 처리한다.
- `evidence:client` Blowfish 초기 상수는 π에서 직접 계산한 1042워드에 바이트별 +1을 적용한다. 게임 파일을 포함하지 않는다. 데이터 LE32, 키 BE 순환, ECB 8바이트, 제로 패딩, 저장 키 XOR 0x17, GUID 래핑 키를 구현했다.
- `evidence:client` T2 정적 벡터에 맞춰 checksum은 패딩 전 데이터의 LE32 XOR와 마지막 1~3바이트 각각의 XOR 뒤 상하 16비트 XOR다. 봉투 길이/체크섬/재전송 sequence를 검증한다.
- `evidence:guess` 월드 상태는 engine의 단일 coroutine 소비자만 변경한다. 외부에는 CommandSink와 불변 WorldSnapshot만 노출한다. 영속화는 SnapshotStore 인터페이스만 있으며 DB 구현은 미완료다.
- `evidence:guess` 운영 API는 127.0.0.1:47901에 health/snapshot/probe 명령을 제공한다. probe POST도 엔진 큐로 전달한다.
- `evidence:guess` 게임/업데이트 스텁은 기본 127.0.0.1:47900/47902로 제한한다. VM에서 접속할 때 `LOGH7_BIND_ADDRESS`에 활성 VirtualBox Host-Only 어댑터의 로컬 IPv4를 지정할 수 있다. 사설망 주소와 APIPA 링크 로컬 주소를 모두 허용하되 해당 어댑터의 소유 주소인지 검사한다. 운영 API는 항상 127.0.0.1이다.
- `evidence:guess` 모든 수신/송신 TCP 청크를 연결별 TSV(UTC 시각, 방향, hex)와 방향별 `.bin` 원바이트 스트림에 기록한다. 기본 경로는 `E:/logh7/work/logh7-dynamic-p2/captures`, 환경변수 `LOGH7_CAPTURE_DIR`로 변경할 수 있다.
- `evidence:guess` 47900은 최초 0x34 관찰까지만 진행하는 키 교환 상태기계 골격이다. 세션 인증/로그인 응답은 구현하지 않았다.
- `evidence:client` T2 `update-protocol.md`의 정적 후보에 맞춰 47902에서 `0x6810`에 `00 02 68 11`, 이어 `0x6820`(BE32 버전)에 `00 02 68 22`를 응답하고 연결을 닫는다. 원본 업데이터의 수용 여부는 VM에서 확인해야 한다.
- `evidence:client` T2 `login-messages.md`가 기존 초안의 로그인 요청 opcode를 0x7000으로 정정했으므로 YAML 스키마에서도 `LGLoginRequest=0x7000`을 사용한다.

## 공식 배포 확인

아래는 2026-09-27 공식 배포와 Maven 아티팩트에 접근하여 확인한 빌드 선택이다. 게임 근거가 아닌 도구 선택이라는 의미로 `evidence:guess`를 쓴다.

| 구성 | 고정 버전 | 출처 |
|---|---|---|
| Kotlin | 2.4.20 | https://kotlinlang.org/docs/whatsnew2420.html |
| Ktor | 3.6.0 | https://ktor.io/docs/whats-new-360.html |
| Netty | 4.2.18.Final | https://repo.maven.apache.org/maven2/io/netty/netty-all/4.2.18.Final/netty-all-4.2.18.Final.pom |
| kotlinx.coroutines | 1.11.0 | https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core/maven-metadata.xml |
| Temurin JDK | 25.0.4.1+1 | https://api.adoptium.net/v3/assets/latest/25/hotspot?architecture=x64&image_type=jdk&os=windows |
| Gradle | 9.8.0 | https://services.gradle.org/versions/current |

- `evidence:guess` JDK ZIP SHA256: `00c847d804f4a78e9f04f2683faf14fed898535b177b7fc704486cb0284e9283`; Gradle ZIP SHA256: `bafd5ce9cfaea0fbccfdc8439a1ac42fbd4cd9c89dc9a988228d8a2639a58e6c`. 다운로드 후 일치 검증했다. 설치는 E:/Tools이며 시스템 설정 변경은 없다.

## 로컬 실행

`evidence:guess` 다음은 Windows PowerShell용이며 도구 캐시·임시 디렉터리를 E:에 둔다.

```powershell
$env:JAVA_HOME='E:\Tools\jdk-25.0.4.1+1'
$env:GRADLE_USER_HOME='E:\Tools\gradle-home'
$env:TEMP='E:\Tools\tmp'
$env:TMP='E:\Tools\tmp'
$env:JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=E:\Tools\tmp -Duser.home=E:\Tools'
Set-Location E:\logh7\server
.\gradlew.bat build --no-daemon
.\gradlew.bat :app:run
```

## 검증 기록

- `evidence:guess` 후속 `gradlew.bat build :app:installDist --no-daemon` 실행 성공. JUnit 13개, 실패 0: protocol 7개(정적 벡터 총 22건 포함), gateway 5개, engine 1개(동시 생산자 10개·명령 1,000개).
- `evidence:guess` 배포 디렉터리에서 JVM 앱 기동 후 GET /health=ok, POST /commands/probe=queued, GET /snapshot의 revision=1/acceptedCommands=1을 확인했다. TCP 47900/47902 합성 입력이 시각·방향·원본 hex로 기록됨을 확인했다. `netstat`에서 세 포트 모두 127.0.0.1 바인딩임을 확인한 뒤 프로세스를 종료했고 LISTENING 잔존 없음도 확인했다.
- `evidence:guess` smoke 산출물은 `server/build/smoke-captures/`(git 제외), JUnit XML은 각 모듈 `build/test-results/test/`이다. GitHub Actions 정의는 작성했으나 원격 CI는 실행하지 않았다.
- `evidence:guess` 합성 TCP 연결에서 업데이트 요청 `00026810`, `0006682000000083`에 각각 `00026811`, `00026822`가 반환됨을 확인했다. 방향별 `.bin`에 원본 byte stream이 정확히 남았다. 이것은 실제 업데이터 실행 관찰이 아니다.
- `evidence:guess` 활성 VirtualBox Host-Only Ethernet Adapter의 APIPA 주소 `169.254.44.17/16`을 `LOGH7_BIND_ADDRESS`로 지정한 뒤 합성 TCP 요청을 보냈다. 게임·업데이트 포트는 해당 주소에만, 운영 API는 `127.0.0.1`에만 LISTENING 상태였고 업데이트 응답 및 방향별 `.bin`이 일치했다. 프로세스 종료 후 세 포트의 LISTENING 잔존이 없음을 확인했다. VM 게스트에서의 접속은 아직 검증하지 않았다.
- `evidence:client` T2 `kex-envelope.md`, `update-protocol.md`, `login-messages.md`의 정적 분석을 반영했다. 47902 응답은 구현됐지만 VM에서 원본 업데이터로 검증해야 한다.
- `evidence:client` 실제 게임 클라이언트는 실행하지 않았다. 게스트 OS와 클라이언트가 준비되지 않아 로그인·키 교환 실동작 검증은 미달성이다.
- `evidence:guess` reverse-skill 기록은 리드 통합 대상이다. 이 트랙은 바이너리 분석 대신 기존 문서와 T2 산출물을 구현 입력으로 읽었으며 신규 대상 ACT와 Evidence append를 실행하지 않았다.
