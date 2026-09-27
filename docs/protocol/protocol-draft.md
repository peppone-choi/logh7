# 프로토콜 초안 (트랙 B, 정적 분석)

- 작성자: 최병호
- 작성일: 2026-09-27
- 상태: 전 항목 **candidate** (동적 관찰이 없어 validated 승격하지 않음).
- 근거: Evidence E-014, E-016, E-017, E-018, E-023, E-024. 태그 `evidence:client`(관찰)/`evidence:guess`(추정).
- 미들웨어 이름 규칙: `mt*` = 저수준(소켓/스트림/암호), `mps*` = 메시지/연결. 게임 프로토콜은 `mpsClientConnection` 위에 얹혀 있다.

## 1. 프레이밍 가설

두 개의 계층이 겹쳐 있다. `evidence:client`, 신뢰도 high(구조), 바이트 오프셋 high.

### 1.1 소켓 프레임 (mtTCPModule / SendBuffer·RecvBuffer)
```
[ u16 length (big-endian) ][ payload : length 바이트 ]
```
- 송신 `FUN_006157d0`: 페이로드 바로 앞 2바이트에 `htons(payload_len)`을 써서 `len+2`를 보낸다.
- 수신 `FUN_00615ad0`: 먼저 2바이트를 `ntohs`로 읽어 길이를 얻고, 그만큼 모일 때까지 기다렸다가 한 메시지를 돌려준다.
- 최대 프레임 `0x0000F000`(61440). 초과 시 `MPS_PACKET_MAXSIZE over!!` (`0x0076f25c`). 송신 링버퍼 `0x1f4000`, 수신 `0x3e8000`.

### 1.2 mps 메시지/암호 프레임 (mpsClientConnection)
소켓 페이로드의 첫 u16(big-endian)이 **프레임 종류**다. `FUN_006130a0`.
- `0x0030` : 암호화된 응용 메시지(대부분). 이어지는 바이트가 암호 봉투.
- `0x0031` : 세션키 재교환(rekey). 페이로드를 현재 세션키로 복호 후 새 키 적용, 그 뒤에 또 다른 0x30 프레임이 이어질 수 있음(재귀 처리).
- `0x0034 / 0x0035 / 0x0036` : 초기 키 교환 4단계(아래 3장).

### 1.3 암호 봉투 (mpsCipherManager::encipher/decipher_message, FUN_00645ce0/FUN_00645db0)
복호 후(또는 암호화 전) 평문 메시지 앞에 붙는 헤더. `evidence:client`, 필드 존재 high, 순서/폭 candidate.
```
[ u16 checksum(BE) ][ u32 sequence(BE) ][ u16 body_len(BE) ][ body : body_len ]
```
- checksum = 봉투의 나머지 전체를 u32 단위로 XOR 후 상·하위 16비트를 XOR한 16비트값(`FUN_00645180` 등 공통 루틴). 불일치 시 `broken data`.
- sequence는 단조 증가. 수신 seq ≤ 직전 seq면 `bad sequence number`로 폐기(`0x007c09b8`). 초기값은 키 교환 때 받은 값 -1.
- body는 Blowfish로 암호화되어 있고, 복호 후 그 안에서 다시 **응용 메시지 헤더**(1.4)가 나온다.

### 1.4 응용 메시지 헤더
클라이언트 안의 두 계열이 관찰됨. `evidence:client`, medium.
- `mpsClientMessage32`(게임): 헤더가 `u32 + u16`로 시작(`FUN_00404210`). +6 위치에 u16 msgtype(opcode).
- `mpsClientLobbyMessage`(로비/로그인): 헤더 `3×u16`(`FUN_004021e0`). 역시 +6에 msgtype.
- 본문 필드는 NetStream 규칙(2장)으로 직렬화.

## 2. 직렬화 규칙 (mtNetStream) `evidence:client`, high

| 타입 | 인코딩 | 함수 |
|---|---|---|
| u8/i8 | 1바이트 | vtbl +0x28 |
| u16/i16 | `htons`/`ntohs` (big-endian) | 0x00611530 / 0x00611a10 |
| u32/i32 | `htonl`/`ntohl` (big-endian) | 0x006117f0 / … |
| float/double | 바이트 그대로(4/8) | vtbl +0x20 계열 |
| 문자열(가변) | NUL 종료 cp932 바이트열, 길이 접두 없음 | 0x00611880(out)/0x00610830(in) |
| 이름(고정 상한) | `u8 count` + `count × u16`(UTF-16LE 코드) | 예: `Output_CommandGenerateCharacterCharge` 0x00405a50 |

- 정수는 **big-endian**(네트워크 바이트 오더). 문자열 본문 인코딩은 Shift_JIS(cp932); 캐릭터 이름 등 일부 필드는 u16 배열(UTF-16 코드 포인트, 상한 13자 등)로 보냄.

## 3. 키 교환·암호 (E-015, E-016) — 대체 서버 구현 핵심

- 알고리즘: **Blowfish 변형**. `evidence:client`, high.
  - 초기 P/S 박스는 표준 Blowfish(π 소수부) 상수의 **모든 바이트에 +1**을 한 값이며, 파일에는 다시 **XOR 0x91**로 가려져 있다. `bf_verify.py` 결과: 1042워드 전부 `bytewise_plus1` 일치, 표준값과는 0개 일치. 재현: `python scripts\bf_verify.py ghidra\bin\G7MTClient.exe`.
  - 데이터 워드를 x86 네이티브 **리틀엔디안 dword**로 읽어 들인다(참조 Blowfish의 big-endian 로딩과 다름). 블록 암호는 8바이트 ECB, 부족분 제로 패딩.
  - 사용자 키는 저장 시 **XOR 0x17**로 가려진다(`FUN_00614810`/`FUN_006148a0`).
- 정적 핸드셰이크 키: 문자열 `{A4C13748-0159-4c54-AEB3-1D68575761B3}` (0x26바이트, `0x0076bbf0`). 초기 키 교환(0x34~0x36)에서 이 키로 세션키를 감싼다. `evidence:client`, high.
- 세션키: 16바이트, CRT `rand()`로 생성(`srand(GetTickCount())`, `FUN_006127d0`). `evidence:client`, medium.
- 4단계 교환(`exchange_key_phase1..4`, 0x00645180/2f0/660/7e8)의 각 단계 페이로드는 `[u16 세션식별?][u16 len][data][u32 seq]` + checksum 구조로 보임. 정확한 필드 의미는 **동적 확인 필요**. candidate.
- 결론: 대체 서버는 이 Blowfish 변형과 봉투/시퀀스/체크섬, 그리고 정적 GUID 키를 그대로 구현해야 로그인까지 도달한다. 압축은 게임 프로토콜에서 확인되지 않음(zlib는 이미지 디코딩용). 업데이터는 파일을 압축 전송하지만 게임 스트림과 무관.

## 4. opcode 후보 표

msgtype은 `그룹<<8 | 인덱스` 형태의 u16. 표는 (1) 수신 디스패처 `FUN_004ba2b0`의 200개 case(방향 S→C 확정, 신뢰도 high)와 (2) `.data`의 이름 포인터 테이블 15개에서 그룹 베이스가 디스패처와 일치하는 항목을 결합해 도출. 전체 목록: `work\logh7-client-triage\notes\msg_ids.tsv`, 재현: `dispatch_ids.py` + `msgname_tables.py`.

방향 표기: S→C = 서버가 클라이언트로(디스패처에서 관찰), C→S = 클라이언트가 서버로(이름이 Command/Request/…, 송신). 이름 규칙상 Request/Command는 C→S, Response/Notify는 S→C가 일반적(`evidence:guess`).

### 4.1 대표 opcode (근거 주소 = 디스패처 case 또는 이름 테이블 슬롯)

| opcode | 방향 | 추정 이름 | 근거 | 확신도 |
|---|---|---|---|---|
| 0x0010 | C→S | Login (mpsClientLobbyMessage, "GIN7") | FUN_004ac070 | medium |
| 0x0020 | C→S | 세션서버 토큰 제출 | FUN_004ac670 (헤더 0x20) | medium |
| 0x0201 | S→C | SSLoginOK | 디스패처 0x0076f... case 0x201 | high |
| 0x0202 | S→C | SSLoginNG | case 0x202 | high |
| 0x0203 | C→S | SSCharacterIDRequest | 이름표 0x00766ed0 base 0x0200 | medium |
| 0x0204 | S→C | SSCharacterIDResponce | case 0x204 | high |
| 0x0205 | C→S | SSGameLoginRequest | 이름표 base 0x0200 | medium |
| 0x0206 | S→C | SSGameLoginOK | case 0x206 | high |
| 0x0207 | S→C | GlobalChat | case 0x207 | high |
| 0x0301 | S→C | ResponseTime | case 0x301 | high |
| 0x0300~0x033f | S→C | ResponseStaticInformation*/ResponseInformation*/ResponseTactics* | 이름표 0x0075f95c base 0x0300 (91개) | high |
| 0x0356~0x035a | S→C | NotifyInformationCharacter/Outfit/ChangeFlagShip/Ending | case 0x356.. | high |
| 0x0400~0x0442 | 혼합 | CommandMoveShip … / NotifyMovedShip … | 이름표 0x00768fb4 base 0x0400 (67개) + 디스패처 | high |
| 0x0500/0x0501 | S→C | NotifyInvalidMessage / NotifyError | case 0x500/0x501 | high |
| 0x0704~0x070b | 혼합 | CommandRankUp / NotifyCardLoss 등 | 이름표 0x007651b8 base 0x0700 | high |
| 0x0900~0x0908 | 혼합 | CommandMakePlan / NotifyCreateOutfit* | 이름표 0x007686e0 base 0x0900 | high |
| 0x0b00~0x0b0d | 혼합 | CommandMoveBase / NotifyMovedGrid 등 | 이름표 0x0076658c base 0x0b00 | high |
| 0x0c00~0x0c0c | 혼합 | CommandCompletenessRepair / Reorganization 등 | 이름표 0x00788e04 base 0x0c00 | high |
| 0x0d?? | 혼합 | CommandChangeTaxRate / CommandInstitution* (15개) | 이름표 0x00764a10 (베이스 미확정) | low |
| 0x0e00 | C→S | CommandMoveInstitutionSpot | case 0x0e00 | medium |
| 0x0f00~0x0f1f | 혼합 | World/Grid Initialize, Mail/Messenger, Chat, SetOption 등 | 이름표 0x00767220 base 0x0f00 (32개) | high |
| 0x1000~0x1008 | 혼합 | RequestInformationAccount / CharacterCharge 커맨드 | 이름표 0x0075ec90 base 0x1000 | high |
| 0x1200~0x120f | S→C | TransactionSimpleData* / NotifySimpleInformation* | 이름표 0x0078a8b8 base 0x1200 | high |
| 0x2000~0x200b | 혼합 | LobbyLoginRequest/OK/NG, LobbySessionLogin*, LobbyInformation* | 이름표 0x00765c88 base 0x2000 | high |
| 0x7001 | S→C | LGLoginOK (로그인 성공, 세션서버 주소+토큰) | case 0x7001, FUN_004ac700 | high |
| 0x7002 | S→C | LGLoginNG (로그인 실패) | case 0x7002 | high |

- NPC 관련 5개(NPCDockingRequest/OK/NG, CommandNPCInitializeComplete, NotifyNPCReInitialize)는 이름표 `0x00766420`에 있으나 그룹 베이스를 디스패처로 확정하지 못함. candidate(low).

### 4.2 업데이터(47902) opcode (E-023)

| opcode | 방향 | 추정 이름 | 근거 | 확신도 |
|---|---|---|---|---|
| 0x6810 | C→S | 버전/갱신정보 요청 | FUN_0041ea60 (헤더+6=0x6810) | medium |
| 0x6820 | C→S | 후속 요청 | FUN_0041eae0 | medium |
| 0x6830 | C→S | 후속 요청 | FUN_0041eb50 | low |
| 0x8000 | S→C | 응답(상태 2) | FUN_0041e910 | medium |
| 0x8001 | S→C | 응답(상태 3) | FUN_0041e910 | medium |
| 0x8010 | S→C | 응답(상태 4) | FUN_0041e910 | medium |
| 0x8020 | S→C | 응답(상태 5) | FUN_0041e910 | medium |

- 실제 파일 전송은 별도 HTTP(`Range: bytes=%d-`, `Multiterm Http Library ver.1.0`)로 이뤄진다.

## 5. 암호화/압축 판단 요약

- 게임(47900): **암호화 있음**(Blowfish 변형 + seq/checksum 봉투 + 키교환). 압축은 확인 안 됨. candidate이나 근거는 강함.
- 업데이트(47902): 제어 메시지는 **평문**(암호 문자열 없음), 파일은 HTTP로 받아 **압축 해제**(`圧縮ファイルの展開に失敗`, CRC32 검증). `evidence:guess`, medium.
- 인코딩: 문자열은 Shift_JIS(cp932), 정수는 big-endian.

## 6. SBOL/MPS 대조 (트랙 C 가설 검증)

트랙 C가 공유한 가설: LOGH7은 MultiTerm의 통신 미들웨어 **MPS(MassplayerSystem)**를 사용하며, 같은 MPS를 쓴 首都高バトルOnline(SBOL)의 공개 자료로 프레이밍·암호·구성을 유추할 수 있다. 아래는 이 가설을 트랙 B의 정적 관찰과 대조한 결과다(외부 코드는 참고만, 복사 없음). 판정: 일치 / 부분 일치 / 불일치 / 미확인.

| # | SBOL/MPS 가설 (evidence:web) | LOGH7 정적 관찰 (evidence:client) | 판정 |
|---|---|---|---|
| 0 | 미들웨어가 MPS(MassplayerSystem) | 클라이언트 클래스명 `mpsClientConnection` `mpsClientLobbyMessage` `mpsGameMsgParseSystem` `mpsCTMsg32ParseSystem` `mpsCipherManager` `mtBlowfishCipherModule` `mtTCPModule_win32` `mtNetStreamInput/OutputBuffer`, 업데이터 `mpsUpdateClientProcessor`·`Multiterm Http Library ver.1.0` | **일치** (high) |
| 1 | 패킷은 빅엔디언 | 정수 직렬화가 `htons/htonl`(BE), 프레임 길이도 `htons` (E-014/E-024) | **일치** (high) |
| 2 | C→S 헤더 `[size u16][type u16]`, size = 전체길이-2 | 소켓 프레임 `[u16 length BE][payload]`; length는 길이필드(2B) 제외 = 전체-2. payload 첫 u16 = 프레임 type(0x30/0x31/0x34…) (E-014, `FUN_006157d0`/`FUN_00615ad0`/`FUN_006130a0`) | **일치** (구조 high; type 값 집합은 게임별로 다름) |
| 3 | S→C 헤더에 `[subtype u16]` 추가 | 로비 메시지(`FUN_004021e0`)는 헤더에서 u16을 3개(+6, +0x50, +0x52) 읽고, msg32(`FUN_00404210`)는 `u32+u16`로 비대칭. 다만 디스패처(`FUN_004ba2b0`)는 단일 msgtype으로 분기 | **미확인** (S→C 전용 subtype 필드로 단정 못함; 헤더 비대칭은 관찰됨) |
| 4 | Blowfish 기반 암호화 | Blowfish 변형 확정: π상수 바이트+1, 파일 XOR 0x91, LE dword 로딩, 8바이트 ECB, 정적 GUID 키, 키 저장 XOR 0x17 (E-015) | **일치** (high) |
| 5 | 복호 결과 앞 8바이트 `[원본크기 u32][체크섬 u32]`, 본문 8바이트 패딩 | 암호 봉투도 8바이트 헤더 + 체크섬 + 길이 + 8바이트 ECB 패딩. 단 필드 배치가 다름: `[u16 checksum][u32 sequence][u16 body_len]` (`FUN_00645ce0`/`FUN_00645db0`, E-016). 재생 방지 seq와 0x34~0x36 키교환·0x31 rekey가 추가 | **부분 일치** (8B 헤더+체크섬+ECB 패딩 일치; 필드 폭/순서·seq·키교환은 LOGH7 확장, candidate) |
| 6 | `SERVER.INI`가 `[SERVERxx]` 섹션에 `TYPE/ADDR/PORT/SIDE` | 업데이터가 `%sSERVER.INI` 섹션명을 열거하며 각 섹션의 `TYPE`(==1)·`ADDR`·`PORT`를 읽음(`FUN_00405310`, E-012). `SIDE` 키와 `[SERVERxx]` 명명은 클라이언트/업데이터 문자열에 없음(섹션명은 런타임 열거라 존재 가능) | **부분 일치** (TYPE/ADDR/PORT 일치; SIDE·섹션명 미확인) |
| 7 | MPS 구성 = 로비 서버(로그인·게임서버 선정) + 릴레이(중계·암호·세션) + 자동 업데이트 | 로그인 서버(47900)→`LGLoginOK`(0x7001)가 세션서버 주소+토큰 반환→재접속. 클래스 `LobbyLogin*`(로비)·`LobbySessionLogin*`/`SSLogin*`(세션/릴레이)·`LGLogin*`(로그인)·업데이트 서버(47902) (E-018) | **일치** (구조 high) |
| 8 | LOGH7 세션명 = iserlohn/potoro/mercury + 별도 로그인 서버 | robot usage 문자열 `>robot <login-server address> <login-server port> <session-server name>`(0x0076be64)로 argv[3]=세션서버 이름 확정. 클라이언트 기본 argv[3]=`ginei00`. iserlohn/potoro/mercury 리터럴은 클라이언트에 없음(서버측/SERVER.INI 소관) | **일치** (구조·개념); 구체 세션명은 **미확인** |
| 9 | type `0x0000` KeepAlive / `0x0100` 인증 / `0x0A00` Ping | LOGH7 opcode 체계는 `0x00NN`~`0x7002`로 상이. KeepAlive 상당은 `RequestTime`/`ResponseTime`(0x0301)으로 추정 | **불일치** (게임별 opcode 상이); KeepAlive 대응은 guess |

요지: **MPS 채택·빅엔디언·소켓 프레이밍·Blowfish·3계층 구성·로그인/세션 분리**는 SBOL/MPS 가설과 일치한다. 차이는 **암호 봉투의 세부 필드 배치**(LOGH7은 u16 체크섬+u32 seq+u16 len이며 명시적 키교환/재키 단계가 있음)와 **게임별 opcode 값**이다. SBOL 자료는 LOGH7 봉투/키교환의 세부 확정에 그대로 쓸 수 없고, 트랙 B가 도출한 봉투 구조(1.3장)를 스텁 서버로 검증하는 편이 정확하다.

## 7. 다음 단계 (동적 확인이 필요한 항목)

1. Blowfish 변형 자체 검증: 알려진 평문/키로 우리 구현 결과와 클라이언트가 만든 프레임(스텁 서버로 받은 첫 0x34 블록)을 대조.
2. 암호 봉투 필드 순서/체크섬 정의 확정(4단계 키교환 phase1 블록의 바이트 맵).
3. 로그인 메시지(0x0010/0x7001) 본문 필드 오프셋 확정 — 특히 LGLoginOK가 주는 세션서버 host/port/token의 바이트 배치.
4. 각 그룹 메시지 본문 구조를 `Input_*`/`Output_*` 함수(예: `FUN_00405a50`)에서 필드 단위로 추출(표는 opcode까지만).
5. 업데이터 0x68xx/0x80xx 본문과 갱신정보 포맷.
