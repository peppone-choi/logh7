# 함수 맵 (트랙 B)

- 작성자: 최병호
- 작성일: 2026-09-27
- 표기: VA(ImageBase 0x00400000 기준) → 제안 이름 → 역할 → 근거. 근거 태그 `evidence:client`(디컴파일 관찰) 기본. 신뢰도는 제안 이름에 대한 것(high=문자열/시그니처로 확정, medium=구조로 추정, low=주변 정황).
- 디컴파일 원문: `work\logh7-client-triage\ghidra\export\*.decomp.txt` (툴 `LoghExport.java`).

## 1. G7MTClient.exe — 부팅·로그인

| VA | 제안 이름 | 역할 | 신뢰도 |
|---|---|---|---|
| 0x00601fbc | `entry` | CRT 시작. `GetCommandLineA` | high |
| 0x004010a0 | `CG7MTClientApp::InitInstance` | `SetRegistryKey("MICROVISION")`, m_lpCmdLine를 공백으로 잘라 `0x007c0b4c`에 저장, MFC 문서/뷰/프레임 생성 | high |
| 0x004b6480 | `SetupDefaultLogin` | 기본 서버 포인터 초기화(`202.8.80.179`), `FUN_004ad120(5, argv테이블)` 호출, `Try Login Account=%s ginei00` 로그 | high |
| 0x004ad120 | `mpsClientBaseSystem::create_instance` | argc<5면 usage 출력. `argv[1]`→서버(0x7c2490), `atoi(argv[2])`→포트(0x7c2494), `strncpy(argv[3]→0x7c24a8,16)` 계정, 파서 시스템 15종 등록, 로그인 접속 객체 생성 | high |
| 0x004ad780 | `mpsClientBaseSystem::create_connection` | 로그인용 연결 생성. `FUN_00612030(...)`로 암호 연결 팩토리 호출, 키 `ginei00`/`dummy` 전달(핸드셰이크용, medium) | high |
| 0x004ad710 | `alloc+create_connection` | 0x48바이트 할당 후 create_connection | medium |
| 0x004ac070 | `LoginRequest::build` | 로그인 요청 구성: 태그 `GIN7`, u16 1, 계정(≤30), 비번(≤10). 암호 연결(0x7c247c) 생성 | high |
| 0x004ac700 | `LoginProcessor::on_login_result` | 0x7001(성공): token=+0xc 저장, 세션서버 host(+4)/port(+8)로 재접속 준비. 0x7002(실패): 연결 정리 | high |
| 0x004ac4f0 | `LoginProcessor::send_char_id` (추정) | 로그인 후 후속 커맨드 디스패치(param 0/1/4 분기) | low |
| 0x004ac670 | `send_msg_0x0020` | msgtype 3 만들어 헤더 0x20 세팅 후 전송(`FUN_006123d0`) | medium |
| 0x004ac6c0 | `LoginProcessorImp::handle_message` | msgtype 0x14면 0으로, 그 외 +4. vtbl+0x18 호출 | medium |
| 0x004adeb0 | `send_chat_or_status_0x207` | `DAT_007c25f8`가 서면 0x207 전송, 아니면 "now connecting" | low |
| 0x004adf60 | `on_connection_error` | 에러코드 1~4를 일본어 메시지(0x0076bfc8 등)로 매핑 | high |

## 2. G7MTClient.exe — 암호 연결·핸드셰이크 (`mpsClientConnection`)

| VA | 제안 이름 | 역할 | 신뢰도 |
|---|---|---|---|
| 0x00612030 | `CipherConnection::create` | 파라미터 검사 후 `FUN_00611f90`(TCP 계층)+`FUN_006127d0`(암호 관리자) 결합 | high |
| 0x006127d0 | `mpsCipherManager::ctor` | cipher 모듈 3개(0x14B each) + 세션키 관리 트리 생성, `srand(GetTickCount)` | high |
| 0x00612100 | `mpsClientConnection::init` | 연결 콜백 등록. 문자열 `mpsClientConnection::mpsMessage...` | high |
| 0x006122c0 | `Connection::pump_recv` | 수신 프레임을 뽑아(0x6130a0) 파서로 넘기는 루프 | medium |
| 0x006123d0 | `Connection::send_message` | 메시지 길이 검사(`size over [%d] (max msg size:[%d])`) 후 0x30 프레임으로 송신 | high |
| 0x006130a0 | `Connection::read_frame` | 외부 프레임 파싱. 첫 u16==0x30이면 암호 페이로드, 0x31이면 재키 처리(재귀) | high |
| 0x00612f20 | `Connection::dispatch_0x30` | 0x30 데이터를 복호화(vtbl+0x14)하고 상위로 전달, 0x31 재키 경로 | medium |
| 0x00612cb0 | `KeyExchange::phase_0x34` | 16바이트 난수 세션키 만들어 상대 공개키로 암호화(0x34) 전송 | medium |
| 0x00612d80 | `KeyExchange::phase_0x35_36` | 0x35 수신→검증→0x36 응답 | medium |
| 0x00612e50 | `KeyExchange::rekey_0x31` | 난수 새 키를 현재 세션키로 암호화(0x31) 전송 | medium |
| 0x006450e0 | `mpsCipherManager::ctor2` | GUID 정적키 세팅(+10바이트 여유) | medium |
| 0x00645180 | `mpsCipherManager::exchange_key_phase1` | 공개키 요청 블록 생성(checksum+seq), 문자열 `exchange_key_phase1` | high |
| 0x006452f0 | `mpsCipherManager::exchange_key_phase2` | phase1 응답 검증·복호, phase3 블록 생성 | high |
| 0x00645660 | `mpsCipherManager::exchange_key_phase3` | 키 데이터 일치 검사(`disagree with encipher key`) | high |
| 0x006457e8 | `mpsCipherManager::exchange_key_phase4` | 최종 키 확정 | high |
| 0x00645ce0 | `mpsCipherManager::encipher_message` | 평문에 헤더(seq/checksum) 붙여 Blowfish 암호화, seq++ | high |
| 0x00645db0 | `mpsCipherManager::decipher_message` | 수신 복호·seq 검사(`bad sequence number`, `broken data`) | high |

## 3. G7MTClient.exe — Blowfish (`mtBlowfishCipherModule`)

| VA | 제안 이름 | 역할 | 신뢰도 |
|---|---|---|---|
| 0x00613ad0 | `Blowfish::set_key` | P/S 초기화(테이블 XOR 0x91 해제, 최초 1회), 키로 스케줄 | high |
| 0x00613f20 | `Blowfish::F` | F 함수 `((S0[b3]+S1[b2])^S2[b1])+S3[b0]` | high |
| 0x00613f60 | `Blowfish::round` | 라운드 헬퍼 | high |
| 0x00614100 | `Blowfish::encipher` | 8바이트 블록 ECB 암호화, 제로 패딩, `encipher: buffer is short` | high |
| 0x00614460 | `Blowfish::decipher` | 복호화, `decipher: buffer is short` | high |
| 0x00614810 | `CipherModule::set_key` | 키 바이트를 XOR 0x17로 저장 | high |
| 0x006148a0 | `CipherModule::get_key` | 저장 키를 XOR 0x17로 복원 | high |
| 0x007b6ae4 | `blowfish_P_init` (데이터) | 18개 P (pi 상수 바이트+1, XOR 0x91) | high |
| 0x007b6ba8 | `blowfish_S_init` (데이터) | 4×256 S박스 | high |
| 0x0076bbf0 | `g_static_guid_key` (데이터) | `{A4C13748-...}` 0x26바이트 핸드셰이크 키 | high |

## 4. G7MTClient.exe — TCP 계층 (`mtTCPModule_win32`)

| VA | 제안 이름 | 역할 | 신뢰도 |
|---|---|---|---|
| 0x00615c80 | `WSA::startup` | `WSAStartup(0x101)` refcount | high |
| 0x00614f50 | `Socket::connect_start` | `socket(AF_INET,SOCK_STREAM,IPPROTO_TCP)`, `TCP_NODELAY`+`SO_KEEPALIVE`, 송수신 버퍼 확대, 워커 스레드 `LAB_006151c0` 생성 | high |
| 0x00615460 | `Socket::do_connect` | `gethostbyname`→실패 시 `inet_addr`, `connect`, `FIONBIO` | high |
| 0x006152b0 | `Socket::recv_pump` | `recv` 링버퍼 채우기 | high |
| 0x006153b0 | `Socket::send_pump` | `send` 링버퍼 비우기 | high |
| 0x006157d0 | `SendBuffer::frame` | 페이로드 앞 2바이트에 `htons(len)` 기록, len+2 전송 | high |
| 0x00615ad0 | `RecvBuffer::degrame` | u16 BE 길이 읽고 그만큼 모이면 메시지 반환 | high |
| 0x00614ea0 | `Socket::alloc_buffers` | 링버퍼(param1 send, param2 recv) + max frame(param3) 할당 | high |
| 0x00615640 / 0x006159e0 | `SendBuffer::alloc` / `RecvBuffer::alloc` | 각 버퍼 malloc | high |

## 5. G7MTClient.exe — 스트림 직렬화·메시지

| VA | 제안 이름 | 역할 | 신뢰도 |
|---|---|---|---|
| 0x00681ef0.. | `NetStreamOutput_vtbl` (데이터) | +0x20 `<<u32`(htonl), +0x24 `<<u16`(htons), +0x28 `<<u8`, +0x18 문자열 | high |
| 0x00681f1c.. | `NetStreamInput_vtbl` (데이터) | 대응 `>>` | high |
| 0x006117f0 | `NetOut::op_u32` | `htonl` 후 4바이트 기록 | high |
| 0x00611530 | `NetOut::op_u16` | `htons` 후 2바이트 | high |
| 0x00611a10 | `NetIn::op_u16` | `ntohs` | high |
| 0x00611880 | `NetOut::op_string` | (len+1)바이트 문자열 기록(NUL 포함) | high |
| 0x00610830 / 0x00611c90 | `Stream::op_string_in` | NUL까지 읽어 CString 구성 | medium |
| 0x004ba2b0 | `GameMessageDispatch` | 수신 msgtype 대형 switch(200개 case). 로그 `<name> OK` | high |
| 0x004b8b00.. | `mpsMsgParseSystem::produce` | opcode→메시지 객체 팩토리(정의외 시 `定義外のＭＰＳメッセージ`) | medium |
| 0x00404210 | `mpsClientMessage32::input` | 헤더 u32+u16 파싱 후 페이로드 분리 | high |
| 0x004021e0 | `mpsClientLobbyMessage::input` | 로비 메시지 헤더(3×u16) 파싱 | high |
| 0x00405a50 | `Output_CommandGenerateCharacterCharge` | 캐릭터 생성 커맨드 직렬화(이름들 u8 count+u16[]) | high |
| 0x00407670 / 0x0044e4b0 / 0x00403ae0 | 파서 등록자(msg32/lobby/…) | vtbl로 opcode 핸들러 테이블 구성 | medium |

## 6. G7MTClient.exe — 텍스트·폰트·리소스

| VA | 제안 이름 | 역할 | 신뢰도 |
|---|---|---|---|
| 0x00521c10 | `MsgMgr::ctor` | 경로 `..\Data\MsgDat\`, `g7sw.dat` 설정, 토큰 구분자 `$`(0x24) | high |
| 0x00521dc0 | `MsgMgr::load_all` | `constmsg.dat`+`messages_%d/_com_%d/_tac_%d.dat`+`g7sw.dat` 로드 | high |
| 0x00522060 / 0x00522310 | `HFWR::load` | `HFWR`(0x52574648) 검사, N/G 헤더, 인덱스+문자열 로드 | high |
| 0x005232d0 | `GFWR::load` | `GFWR`(0x52574647) 금칙어 로드 | high |
| 0x004eac60 | `mbcs_to_wcs` | `setlocale(LC_CTYPE,"Japanese")` 후 `mbstowcs` | high |
| 0x004b07c0 | `GlyphCache::init` | `CreateFontA` 폰트명 `ＭＳ ゴシック`, 글리프 텍스처 초기화 | high |
| 0x004b0960 | `GlyphCache::render` | UTF-16 코드로 `ExtTextOutA` 렌더 후 캐시(0x10000 엔트리 LRU) | high |
| 0x004aec70 | `AsciiFontAtlas::build` | ASCII용 폰트 아틀라스(`CreateFontA`, MulDiv로 포인트 크기) | medium |
| 0x005a8f3c | `D3DX_assert` | 어설션 대화상자(`IsDebuggerPresent` 사용처) | high |

## 7. Gin7UpdateClient.exe

| VA | 제안 이름 | 역할 | 신뢰도 |
|---|---|---|---|
| 0x00404a80 | `CUpdateApp::InitInstance` | Mutex 생성(중복 실행 방지), 모듈 경로, `update.ini`·`SERVER.INI` 로드 | high |
| 0x00404dc0 | `Config::load_update_ini` | `[UPDATE]` VERSION/SERVER_ADDRESS(기본 202.8.80.179)/SERVER_PORT(기본 47902)/PROXY_*/BASE_DIR/TEMP_DIR/STARTUP_APPNAME/WORK_DIR | high |
| 0x00405310 | `Config::load_server_ini` | `%sSERVER.INI` 섹션 순회, `TYPE==1`이면 ADDR/PORT 목록화 | high |
| 0x00404c30 | `CUpdateApp::ExitInstance` | Mutex 있으면 기존 창(#32770) 전면화, 없으면 update.ini에 LAST_ERROR 기록 | high |
| 0x00406830 | `on_update_done → LaunchClient` | KillTimer 후 `LaunchApp(STARTUP_APPNAME)` | high |
| 0x00407260 | `LaunchApp` | `CreateProcessA(NULL, STARTUP_APPNAME, ..., 0x20, WORK_DIR, SW_SHOW)` — 인자 없음 | high |
| 0x00407300 | `write_update_log` | UPDATE.LOG에 처리한 파일 목록 기록 | medium |
| 0x0041e910 | `map_server_reply` | 0x8000→2, 0x8001→3, 0x8010→4, 0x8020→5 상태 전이 | high |
| 0x0041ea60 / 0x0041eae0 / 0x0041eb50 | `send_0x6810 / 0x6820 / 0x6830` | 업데이트 요청 메시지 전송(헤더+6에 opcode) | high |
| 0x00420260 | `mpsUpdateClientProcessor::on_update_info` | 갱신 정보 파싱(`bad update info`), 다운로드 목록 구성 | medium |
| 0x00429530 | `HttpClient::request` (추정) | HTTP 헤더 조립(`Multiterm Http Library ver.1.0`, Range) | medium |

## 8. BootFirst.exe / G7Start.exe

| VA | 제안 이름 | 역할 | 신뢰도 |
|---|---|---|---|
| BootFirst 0x00401000 | `swap_and_run_updater` | `.new`↔`.exe`↔`.old` 교체 후 `.\Gin7UpdateClient.exe` 실행, exit=1이면 최대 4회 재시도 | high |
| BootFirst 0x00401150 | `entry` | CRT, `FUN_00401000` 호출 | high |
| G7Start 0x00402db0 | `check_install_registry` | `HKLM\SOFTWARE\BOTHTEC\銀河英雄伝説VII\1.0` `Install` 읽기 | high |
| G7Start 0x004037d0 | `run_setup` | `WinExec("SETUP.EXE")` | high |
| G7Start 0x00403860 | `open_pdf_manual` | `ShellExecuteA("open", 매뉴얼.pdf)` | high |
| G7Start 0x00403970 | `launch_game` | `CreateProcessA(<Install>exe\G7MTClient.exe, cwd)` (설치 확인 메뉴 경로) | medium |

## 9. 재현 참고

- 특정 함수 디컴파일 보기: `python work\logh7-client-triage\scripts\showfn.py <decomp.txt> <VA>`
- 디스어셈블: `python ...\scripts\disas.py <exe> <start_va> <end_va>` (문자열/IAT 주석 포함)
- 바이트/포인터 확인: `python ...\scripts\peek.py <exe> <va> [--dwords N --str]`
