# D1 owned loopback upstream

이 도구는 환경 준비이며 제품 승인이나 독립 리뷰를 대신하지 않는다. 새 exact head를 독립 리뷰한 뒤에만 실제 JVM/게임을 실행한다. 기본 사용자 E:/logh7 JAR 및 계정/상태는 사용하지 않는다.

## Distribution / runtime integrity

`.github/workflows/qa-distribution.yml`은 GitHub-hosted Ubuntu에서 해당 실행의 github.sha로 `server/gradlew :app:installDist --no-daemon`을 수행한다. artifact `logh-server-<exact SHA>`는 distribution-provenance.json(head/run/attempt/repository/build 및 전체 배포 파일 SHA256)을 포함한다. 외부 GitHub run head/conclusion 및 artifact ID/digest를 별도로 확인한다. 새 소스 커밋마다 재빌드한다.

무료 Temurin25 Windows x64 JDK ZIP의 공개 release URL/digest와 GPL-2.0-only WITH Classpath-exception-2.0, 실제 LICENSE 파일 해시를 기록한다. Windows에서 빌드하지 않는다. JDK는 `E:/HermesQA/logh-runtime/t_10a6812e/jdk`, 배포 artifact는 같은 runtime의 distribution.zip 및 distribution/에 둔다. 기존 경로 덮어쓰기를 거부한다.

`tools/client/prepare-upstream-runtime.py`는 JDK release major25, 두 ZIP 외부 SHA, provenance의 exact head/run/repository/build 및 실제 배포 파일 전체를 검증하여 새 runtime-manifest.json을 생성한다. JVM은 실행하지 않는다. 별도의 SHA256 pin을 driver에 넣는다. 최종 인계에 생성된 절대 명령과 실제 head/run/artifact/hash를 제공한다. client manifest도 새 OWN_CODE 및 실제 Windows 바이트로 재생성한다.

## Lifetime and noninterference

`qa_upstream.run`은 검증된 client supervisor에서 같은 스레드로 호출한다. 이 스레드가 기존 `qa_session_mutex.SessionMutex`로 모든 기존 세션의 legacy mutex를 먼저 획득한다. client가 같은 mutex를 재진입하고 자신이 획득한 횟수만 해제하므로 중첩 프로세스의 deadlock이 없다. 세션 집합 변경/사용자 Steam 또는 게임/가용 RAM 2GiB 미만/활성 입력 세션 또는 WinSta0이면 WAIT한다.

47900(login)/47901(ops)/47902(update)/47903(session) 및 recorder20000의 IPv4 loopback 바인드가 모두 가능해야 한다. 포트 확인 후 JVM 자체 바인드까지의 경쟁은 피할 수 없으므로 실제 TCP listener 소유 PID가 retained JVM의 PID와 일치해야 준비로 인정한다. 사용자 listener를 종료하지 않고, 연결 probe나 합성 wire도 보내지 않는다. 기존 recorder는 20000→47900 원바이트 중계만 한다.

JVM은 java.exe 직접 MainKt, suspended 생성 → KILL_ON_JOB_CLOSE/active-process-limit1/UI255 Job 할당·읽기 확인 → resume 순서다. 파일 stdin/stdout/stderr만 상속하고, Job handle은 비상속·익명이다. PID/생성시각/retained handle 및 Job 정책을 보고한다. `-Djava.awt.headless=true`, `-Xmx256m`, processor2를 사용하며 사용자 GUI/오디오 API를 호출하지 않는다. 이 설계만으로 실제 isolation/silence PASS를 주장하지 않는다.

환경은 Windows bootstrap 변수와 전용 TEMP/TMP/LOGH7_CAPTURE_DIR/loopback 세 변수만 전달한다. 사용자 JAVA_TOOL_OPTIONS/JDK_JAVA_OPTIONS/CLASSPATH, LOGH7_ACCOUNTS_FILE/CHARACTERS_FILE/SESSION_RULES/GENERATION/STORE를 상속하지 않는다. 전용 runs/<uuid> 아래 cwd/capture/temp/state와 user.home을 지정한다. 기본 capture 경로로 쓰지 않는다.

정상/실패/취소/예외/시작timeout 모두 finally에서 소유 Job만 종료한다. retained root가 미할당 상태이면 그 suspended handle만 종료한다. ActiveProcesses=0과 root retained handle signalled 모두 확인해야 upstream cleanup_verified=true다. 실패하면 전체 client cleanup_verified=false 및 CLEANUP_FAILED다. Java 프로세스 이름/PID 기반 종료를 사용하지 않는다.

65초 watchdog은 소유 Job을 종료하고 client cancellation을 요청한다. SSH가 끊겨도 supervisor가 유지되면 이 상한을 적용하고, supervisor가 사라지면 비상속 Job의 마지막 handle 닫힘으로 JVM이 종료된다. 이 강제 연결단절 경로의 실제 관측은 독립 리뷰 후 무해 lifetime fixture 단계에서 별도로 시험해야 한다. 소스/합성 테스트를 그 증거로 대체하지 않는다.

## Driver arguments

기존 windows-product-qa.ps1 및 make-logh-qa-driver.py에 선택 인자 `UpstreamManifest`/`UpstreamManifestSha256`를 연결했다. 두 값은 함께 필요하며 owned D1은 recorder20000/upstream47900만 허용한다. 새 qa_upstream.py는 client manifest OWN_CODE에 포함된다. runtime.json에 upstream 소유/cleanup 증거가 함께 기록되고 기존 제품 PASS gate는 그대로 유지된다. 선택 인자가 없는 기존 실행 방식은 바꾸지 않는다.

## Verification boundary

Linux unittest는 환경 allowlist, closure tamper/Java250 거부, mocked suspended/assign/resume/cleanup 및 실패 경로를 검사한다. 실제 Windows Job·JVM·게임 시험이 아니다. 실제 success/failure/cancel/exception/timeout/disconnect lifecycle fixture는 독립 리뷰 후 담당이 수행한다. Steam/메모리/격리 조건을 먼저 확인하고, 기존 t_78eb1710/t_a1c38302는 director가 재개하기 전 blocked를 유지한다. D2/D3/D5 실측 미정, 제품/서버 기동 NOT_RUN, receipt=null, environment_ready=false다.
