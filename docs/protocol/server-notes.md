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
- `evidence:guess` 최초 골격의 0x34 관찰 전용 상태기계는 2026-09-28 아래 T1 구현으로 대체했다. 로그인 47900, 업데이트 47902, 세션 47903을 분리한다.
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
- `evidence:guess` smoke 산출물은 `server/build/smoke-captures/`(git 제외), JUnit XML은 각 모듈 `build/test-results/test/`이다. 리드가 push 후 [GitHub Actions 성공](https://github.com/peppone-choi/logh7/actions/runs/36303360614)을 확인했다.
- `evidence:guess` 합성 TCP 연결에서 업데이트 요청 `00026810`, `0006682000000083`에 각각 `00026811`, `00026822`가 반환됨을 확인했다. 방향별 `.bin`에 원본 byte stream이 정확히 남았다. 이것은 실제 업데이터 실행 관찰이 아니다.
- `evidence:guess` 활성 VirtualBox Host-Only Ethernet Adapter의 APIPA 주소 `169.254.44.17/16`을 `LOGH7_BIND_ADDRESS`로 지정한 뒤 합성 TCP 요청을 보냈다. 게임·업데이트 포트는 해당 주소에만, 운영 API는 `127.0.0.1`에만 LISTENING 상태였고 업데이트 응답 및 방향별 `.bin`이 일치했다. 프로세스 종료 후 세 포트의 LISTENING 잔존이 없음을 확인했다. VM 게스트에서의 접속은 아직 검증하지 않았다.
- `evidence:client` T2 `kex-envelope.md`, `update-protocol.md`, `login-messages.md`의 정적 분석을 반영했다. 47902 응답은 구현됐지만 VM에서 원본 업데이터로 검증해야 한다.
- `evidence:client` 이 구현 트랙에서는 실제 게임 클라이언트를 실행하지 않았다. 위 기록은 이전 골격 단계의 검증이며, 최신 서버와 원본 클라이언트의 상호운용 검증은 리드의 VM 실행 대상이다.
- `evidence:guess` reverse-skill 기록은 리드 통합 대상이다. 이 트랙은 바이너리 분석 대신 기존 문서와 T2 산출물을 구현 입력으로 읽었으며 신규 대상 ACT와 Evidence append를 실행하지 않았다.


## T1 서버 역할 구현 (2026-09-28)

작성자: 최병호. 아래 기록은 이 절의 빌드 시점을 기준으로 한다.

- `evidence:client` `Handshake`는 `kex-envelope.md`의 B 역할을 구현한다. 0x34를 정적 wrapping key로 복호하여 키 길이·checksum·초기 sequence·정렬과 0 패딩을 검증하고, 난수 16바이트 B 키와 초기 sequence로 0x35를 만든다. 0x36의 checksum과 B 키 반향이 일치해야 ESTABLISHED로 바뀐다. sequence 필드를 읽기 전 최소 4바이트를 요구한다. 합성 응답은 `mps-kex.json`과 바이트 단위로 일치한다.
- `evidence:client` A 키는 수신, B 키는 송신에 사용한다. 0x30은 봉투 전체를 복호한 다음 checksum·본문 길이·sequence 단조 증가를 검증한다. 수신 초기 previous는 A initial sequence−1이다. 송신 sequence는 사용 후 증가하며 0x7fffffff를 넘으면 명시적 rekey 필요 오류를 낸다. 복호 본문 시작의 0x0031은 미구현 rekey 오류로 처리한다. 외부 평문 0x31을 rekey로 수용하지 않는다.
- `evidence:client` `LoginMessages`는 0x7000의 GIN7/버전/예약 필드, NUL을 포함한 A/B BE16 코드 단위와 실제 writer 길이를 읽는다. 끝이 잘렸거나 추가 바이트가 있으면 거부한다. 0x7001/0x7002는 로비 S→C 6바이트 헤더, 0x0020 요청은 opcode+BE32 token의 6바이트로 처리한다. 일반 세션 송신 헤더는 field50:u32+opcode:u16인 별도 코덱으로 둔다. loopback 주소는 `01 00 00 7f`로 직렬화한다.
- `evidence:guess` `GameExchange`는 로그인/세션 역할별로 KEY_EXCHANGE→AWAITING_AUTH→AUTHENTICATED를 전이한다. 로그인 실패는 REJECTED 응답 후 소켓 종료다. 세션 인증 성공 이후 메시지와 서버 응답은 근거가 확보되지 않아 구현하지 않았다. 미확정 응답 보조 필드는 0, 로그인 실패 코드는 1로 두며 원본 서버 의미가 확정됐다는 뜻은 아니다.
- `evidence:guess` 토큰은 난수 u32(0 제외), 60초 유효, 한 번만 사용한다. 공유 저장소는 만료 항목 정리 후 최대 4096개로 제한한다. 테스트 계정은 외부 UTF-8 properties에서 읽고 미설정이면 모두 거부한다. 실제 계정 체계·DB·재접속 정책은 후속 범위다.

### 설정과 배포

`evidence:guess` 예시 시드는 `server/config/test-accounts.example.properties`에만 두었다. 계정 값은 문서·로그로 출력하지 않는다. 실행 시 다음 환경변수를 사용한다.

| 변수 | 의미 / 기본값 |
|---|---|
| `LOGH7_ACCOUNTS_FILE` | 외부 테스트 계정 properties 경로; 미설정 시 인증 거부 |
| `LOGH7_SESSION_PORT` | 세션 수신 포트, 기본 47903 |
| `LOGH7_SESSION_ADDRESS` | 로그인 응답의 세션 IPv4, 기본 bind 주소 |
| `LOGH7_BIND_ADDRESS` | 기존 localhost 또는 검증된 호스트 전용 어댑터 주소 |
| `LOGH7_CAPTURE_DIR` | 연결별 암호화 원바이트 캡처 경로; Git 제외 경로 사용 |

- `evidence:guess` `tools/vm/deploy-server.ps1`은 기본 `-Action Build`로 installDist만 만든다. 선택 `-IncludeRuntime`은 지정 JDK의 jlink로 배포 폴더에 런타임을 생성한다. 기존 runtime 폴더를 자동 삭제하지 않는다.
- `evidence:guess` 리드는 `-Action Deploy -VmName <이름> -GuestUser <계정> -PasswordFile <파일> -AccountsFile <파일> -GuestDirectory <게스트 절대경로>`로 배치한다. `guestcontrol copyto --passwordfile`을 쓰며 비밀번호 내용은 읽거나 출력하지 않는다. VM 부팅·네트워크 변경은 이 스크립트의 범위가 아니다.
- `evidence:guess` `-Action Start`는 같은 VM/계정/passwordfile/guestdirectory와 필요 시 `-GuestJavaHome`을 받아 임시 예약 작업 `LOGH7-Server-Temporary`를 Interactive/Limited로 등록한다. 지정 계정의 explorer가 세션 1에 있는지 게스트에서 확인하고 Java를 실행한다. 창은 숨긴다. 기본 Java 경로는 배포 폴더의 runtime이다. 게스트 바인딩은 loopback이며, 로그는 배포 폴더의 server.log, 캡처는 captures 아래다.
- `evidence:guess` `-Action Stop`은 임시 작업을 중지·삭제한다. 실행 중 작업이 이미 있으면 Start는 덮어쓰지 않는다. 배포 스크립트의 PowerShell 구문 검사만 수행했고 guestcontrol·예약 작업·jlink는 실행하지 않았다. UAC나 계정 권한 문제는 리드가 CLI 결과를 확인해야 한다.

### 검증과 리드 요청

- `evidence:guess` `gradlew.bat build :app:installDist --no-daemon` 성공. JUnit 23개 중 통과 21, skip 2, 실패/오류 0. 합성 키 교환, 잘린 sequence, checksum과 키 반향 실패, 방향별 암호화, 봉투 재전송, 내부 rekey, 송신 sequence 경계, 로그인 성공/실패, 토큰 재사용/만료, 문자열 writer 길이, 기존 TCP 프레이밍·캡처·업데이트 검사를 포함한다.
- `evidence:guess` `LOGH7_GOLDEN_DIR`이 없으면 실제 캡처 테스트는 skip한다. 디렉터리에는 외부 u16 길이를 포함한 **단일 전체 소켓 프레임**을 `initial-0034.bin`, `reply-0035.bin`, `confirm-0036.bin`으로 둔다. 첫 파일만 있으면 초기 수신 검증을 수행하며, 세 파일이 모두 있으면 캡처 0x35에서 B 키/sequence를 추출해 서버 응답 재생성과 0x36 완료를 대조한다. 원바이트는 Git에 넣지 않는다.
- `evidence:guess` 리드에게 요청: 격리 VM에서 예시 시드로 로그인하고 0x34→0x35→0x36, 암호 0x7000→0x7001, loopback:47903 재접속과 두 번째 kex, 0x0020 token 수용을 확인한다. 기대 상태는 로그인/세션 각각 AUTHENTICATED이며 세션 이후 게임 진입 응답은 아직 없다. 잘못된 인증 값은 0x7002 후 연결 종료가 기대값이다.
- `evidence:client` re-deep의 추가 결과는 현재 입력 문서에 아직 반영되지 않았다. LGLoginNG 오류 의미·예약 헤더 의미·세션 이후 응답은 확정되지 않았다. 신규 바이너리 분석, Evidence append, git, VM 조작은 수행하지 않았다.

### T0 guestcontrol 피드백 반영

- `evidence:guess` `deploy-server.ps1`은 VERR_DUPLICATE/VERR_TIMEOUT과 비정상 종료 코드를 함께 확인하며 기본 3회, 3초 간격으로 재시도한다. `GuestControlAttempts`/`RetryDelaySeconds`로 제한을 조정한다. 다른 오류는 즉시 실패하며 소진 시 VM 재부팅 판단을 리드에게 남긴다. 비밀번호·호출 인자·인코딩 스크립트를 오류 로그에 출력하지 않는다.
- `evidence:guess` 게스트 PowerShell은 예외 시 exit 1, 정상 시 exit 0을 명시하고 guestcontrol run은 60초 timeout/--wait-stdout/--wait-stderr를 사용한다. Start 재시도는 이번 호출의 operation ID와 예약 작업 Description을 비교해 이미 실행 중인 동일 작업을 중복 기동하지 않는다. 다른 호출의 기존 작업은 보존하고 오류로 처리한다.
- `evidence:guess` 실제 VBoxManage 대신 메모리 mock 함수로 일시 오류 후 성공·영구 오류 즉시 실패·재시도 소진을 확인했다. PowerShell 구문 검사 통과. VM·guestcontrol 실제 호출은 하지 않았다. 서버 배포 산출물은 `server/app/build/install/app/`에 준비되어 있다.


- `evidence:guess` T0 교차검증에 따라 게스트 기본 배포 경로를 `C:\Users\logh7\Documents\logh7-session3\server`로 변경했다. 경로 검증은 게스트 C:를 포함한 드라이브 절대경로를 허용한다. 호스트 빌드·캐시·임시 파일은 계속 E:에 둔다.
- `evidence:guess` 설치된 `E:\VirtualBox\VBoxManage.exe help guestcontrol`에서 copyto의 `--recursive`는 호스트 디렉터리 재귀 복사, `--target-directory`는 게스트 목적 디렉터리임을 확인했다. 옵션을 원본 경로 앞에 배치했다. 도움말에 없는 `--wait-exit`은 제거하고 문서화된 wait-stdout/wait-stderr를 사용한다. `--` 뒤에는 실행파일 이름을 중복 전달하지 않고 PowerShell 인자부터 전달한다.
- `evidence:guess` 변경 후 호스트·게스트 wrapper 구문, C:/E: 절대경로 허용과 상대경로 거부, run 인자 배열, 재시도 성공·영구 실패·소진 mock 검증이 통과했다. CLI 도움말만 실행했으며 VM 복사·기동은 수행하지 않았다.

- `evidence:guess` 세션 3 다운로드 제한에 따라 배포 스크립트의 installDist는 `--offline --no-daemon`으로 실행한다. 캐시 미충족 시 실패하며 온라인으로 재시도하지 않는다. 기존 캐시 빌드 확인은 T0 담당이다.
- `evidence:guess` 선택 `-IncludeRuntime`은 ALL-MODULE-PATH 대신 app JAR과 배포 lib classpath를 jdeps로 분석한 모듈에 동적 EC provider(`jdk.crypto.ec`)만 추가해 jlink한다. 로컬 jdeps 결과는 java.base/java.desktop/java.instrument/java.logging/java.management/java.naming/jdk.jfr/jdk.unsupported였다. 구문·모듈 선택 검사는 통과했으며 jlink 생성·게스트 실행은 미검증이다. `-GuestJavaHome`으로 기존 게스트 Java를 사용하는 선택지도 유지한다.

- `evidence:guess` T0의 실제 copyto 디렉터리 대상 실패 보고를 반영해 배포 복사는 ZIP 단일 파일 방식으로 변경했다. 호스트 `server/app/build/logh7-deploy.zip`을 게스트 배포 폴더의 명시적 `logh7-deploy.zip` 경로로 copyto하고, 게스트 PowerShell Expand-Archive -Force로 전개한다. 계정 파일도 기존처럼 명시적 파일 경로로 복사한다. `--target-directory`/`--recursive`는 더 이상 배포에 사용하지 않는다.
- `evidence:guess` timeout 이후 전개 재시도를 위해 게스트 ZIP을 보존한다. 배포 폴더에는 전개본과 ZIP 양쪽의 디스크 공간이 필요하다. ZIP 내용에는 bin/lib 및 선택 runtime이 포함되며 계정 파일은 별도로 복사한다.
- `evidence:guess` 실제 Deploy 분기를 mock 함수로 실행해 두 copyto의 명시적 파일 목적지, 디렉터리 옵션 부재, 게스트 명령 구문을 검증했다. 합성 bin/lib 파일을 실제 압축·전개하고 같은 ZIP을 두 번 전개해 폴더 구조와 재시도 가능성을 확인했다. 산출물은 `server/build/deploy-mock-*`이며 VM 호출은 하지 않았다.
