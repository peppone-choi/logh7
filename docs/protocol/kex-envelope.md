# 키 교환·암호 봉투 정적 명세

작성자: 최병호 · 2026-10-08 갱신 · 상태: 초기 키 교환·첫 로그인 봉투 **validated**, 재키 교환 미구현·미검증.
근거: `evidence:client`, E-300/E-301. 주소는 CD판 `G7MTClient.exe` VA이며 SHA256은 벡터 JSON에 기록한다.

> 2026-10-07 후속: [호스트 백그라운드 실행](../ops/background-client.md)에서 기존 Kotlin 스텁과 실제 클라이언트의 `0x34→0x35→0x36` 및 첫 암호화 로그인 요청 복호를 확인했다. 아래 9월 28일의 미검증 표기는 당시 상태다. 세션 로그인·재키 교환·전체 플레이는 이 시험으로 확인하지 않았다. `evidence:client`

## 실제 교환 골든 검증 (2026-10-08, LOGH-22)

보관된 독립 접속 5회의 양방향 기록을 Kotlin `GameExchangeTest.capturedLoginStreamsWhenAvailable`로 검사했다. 각 접속에서 C→S `0x34/0x36/0x30`, S→C `0x35/0x30`의 길이 경계와 프레임 코덱 왕복이 일치했다. 실제 `0x35`의 키·초기 sequence로 서버 상태를 재구성하자 응답 바이트와 `0x36` 키 반향 검증도 일치했다. 첫 C→S 봉투의 checksum·sequence·패딩을 검증하고 `0x7000` 본문을 복호했으며, 합성 계정 `ginei00`·인증 문자열 `dummy`를 확인했다. S→C 로그인 거절 봉투도 복호·재인코딩이 일치했다. `evidence:client`

원바이트는 `work/logh7-background-20261007/gateway/`의 방향별 `.bin` 10개에 있고, 복호 평문은 로컬 Gradle 테스트 XML의 `system-out`에 남는다. 기존 정적 함수 대조와 이 동적 검증을 합쳐 **초기 소켓 프레이밍·키 교환·첫 로그인 봉투**를 validated로 판단한다. 재키 교환·후속 로비 메시지까지 확대하지 않는다.

```powershell
$env:JAVA_HOME = 'E:\Tools\jdk-25.0.4.1+1'
$env:GRADLE_USER_HOME = 'E:\Tools\gradle-home'
$env:LOGH7_CAPTURED_LOGIN_DIR = 'E:\logh7\work\logh7-background-20261007\gateway'
Set-Location E:\logh7\server
.\gradlew.bat :gateway:test --tests org.logh7.gateway.GameExchangeTest --offline
```

원바이트를 저장소에 넣지 않으므로 이 선택적 테스트는 환경변수가 없는 CI에서 건너뛴다. 캡처 경로를 지정하면 파일 누락·3회 미만·바이트 불일치는 실패한다. 실제 기록 5회 검증은 로컬에서 통과했다.

## 암호와 재현

`0x00613ad0`은 P 18워드(VA `0x007b6ae4`), S 4×256워드(VA `0x007b6ba8`)를 파일 바이트 XOR `0x91` 후 LE32로 읽는다. 표준 π 상수의 각 바이트에 1을 더한 값이다. 키 스케줄은 원래 키 바이트를 순환하며 **BE32**로 묶어 P와 XOR한다. 저장용 XOR `0x17`은 `0x00614810/0x006148a0` 사이의 메모리 난독화이며 키 스케줄에 추가 적용하지 않는다. `evidence:client`

16라운드 F 함수는 `((S0[a]+S1[b]) XOR S2[c])+S3[d]`(u32 wrap)이며 a는 숫자 워드의 최상위 바이트다(`0x00613f20`). 데이터 입출력만 **LE32 두 워드**로 8바이트 블록을 구성한다. `0x00614100`은 ECB, 8바이트 배수까지 0으로 패딩하며 이미 정렬되면 블록을 추가하지 않는다. `evidence:client`

```powershell
python -B docs/protocol/generate_vectors.py work/logh7-client-triage/ghidra/bin/G7MTClient.exe server/protocol/src/test/resources/vectors
```

생성기는 표준 라이브러리만 사용한다. `mps-tables.json`은 복원된 숫자 상수, `mps-blowfish.json`은 알려진 키/평문 9건, `mps-envelope.json`은 본문 길이 0~9의 체크섬·봉투, `mps-kex.json`은 합성 키 교환 3개 프레임이다. 암복호 왕복 9건에 더해 실제 클라이언트의 첫 `0x34` 프레임 1개가 아래 구조와 일치했다. 자체 왕복만으로는 호환성 증명이 아니며 나머지 교환은 미검증이다. `evidence:client` (동적 E-119·120).

## 체크섬의 정확한 범위와 잔여 바이트 처리

`0x00645ce0`, `0x00645db0`, 키 교환 각 루틴의 계산은 아래와 같다. `data`는 checksum 필드 **직후**부터 논리 메시지 끝까지다. 암호 패딩은 제외한다. `evidence:client`

```python
acc = 0
end = len(data) // 4 * 4
for i in range(0, end, 4):
    acc ^= int.from_bytes(data[i:i+4], 'little')
for b in data[end:]:
    acc ^= b                    # 잔여 바이트를 시프트하지 않음
checksum = ((acc >> 16) ^ acc) & 0xffff
wire_checksum = checksum.to_bytes(2, 'big')
```

## 응용 봉투

평문 봉투 전체를 송신 방향의 세션키로 암호화한다. 본문만 암호화하는 구조가 아니다. `evidence:client`

| 평문 오프셋 | 폭 | 필드 |
|---|---:|---|
| 0 | 2 | checksum BE16: `[2,8+N)` 계산 |
| 2 | 4 | sequence BE32 |
| 6 | 2 | N = body length BE16 |
| 8 | N | 응용 메시지 |
| 8+N | 0~7 | 0 패딩, checksum 범위 밖 |

송신은 저장한 sequence를 쓴 뒤 1 증가시킨다. 수신은 `sequence > previous`만 수용하고 성공 후 previous를 갱신한다. 교환에서 받은 초기 sequence의 `-1`을 previous로 저장한다. 송신의 이전 값이 `0x7fffffff`보다 클 때 rekey 플래그를 세운다. 서버에서 순환을 임의 허용하지 않는다. `evidence:client`

## 초기 키 교환

정적 wrapping key는 ASCII `{A4C13748-0159-4c54-AEB3-1D68575761B3}`의 38바이트다. NUL은 키에 포함하지 않는다. A/B는 각 피어의 송신키이며 보통 길이 16이다. 서로 다른 양방향 키를 사용한다. 모든 표의 정수는 BE, 전체 블록은 위 암호로 wrapping한다. `evidence:client`

| 처리 | VA | 입력/출력 |
|---|---|---|
| phase1 | `0x00645180` | A측이 첫 블록 생성 |
| phase2 | `0x006452f0` | B측이 A 블록 검증·A 수신키 설정 후 응답 생성 |
| phase3 | `0x00645660` | A측이 반향 A 키 비교·B 수신키 설정 후 확인 생성 |
| phase4 | **`0x00645a80`** | B측이 반향 B 키 비교 후 교환 완료 |

기존 `0x006457e8` 표기는 phase3 내부 주소다. `notes/t2-kex.asm`에서 phase3은 `0x00645a74 ret 0x10`까지, phase4는 `0x00645a80 sub esp,0x14`부터 확인했다. Ghidra의 간접 호출 stack 분석 일부가 틀려 필드 순서는 x86 디스어셈블리와 대조했다. `evidence:client`

| 블록 | 평문 필드 순서 | 논리 길이 | checksum 입력 길이 |
|---|---|---:|---:|
| `0x0034` | checksum:u16, A_len:u16, A[A_len], A_initial_seq:u32 | A_len+8 | A_len+6 |
| `0x0035` | checksum:u16, A_len:u16, A[A_len], B_len:u16, B[B_len], B_initial_seq:u32 | A_len+B_len+10 | A_len+B_len+8 |
| `0x0036` | checksum:u16, B_len:u16, B[B_len] | B_len+4 | B_len+2 |

phase1/2는 송신 초기 sequence가 0이면 1로 바꾼다. phase3의 잔여 sequence 검사에는 4바이트를 읽으면서 2바이트 이상만 확인하는 원본 조건이 관찰되므로, 서버 구현은 최소 4바이트를 요구해야 한다(상호운용 포맷과 불완전한 원본 길이 검사를 구분). `evidence:client`

바깥 소켓 프레임은 `[u16 BE payload_len][u16 BE frame_type][ciphertext]`, payload_len은 type 2바이트와 암호문 길이의 합이다. 합성 교환 벡터는 A=`00..0f`, B=`f0..ff`, 초기 sequence A=1/B=2를 사용한다. `evidence:client`

## 실제 첫 프레임 관찰 (2026-09-28)

격리 게스트 localhost 수신기에서 28바이트 원바이트 프레임을 받았다. `[u16 payload_len=26][u16 type=0x0034][ciphertext 24바이트]`이고 위 정적 wrapping key로 복호한 결과 A 키 길이 16, 초기 sequence 1, checksum `0x287e` 일치, 추가 패딩 0바이트다. 캡처 SHA-256은 `d872b1c8c9d20f758180351891edc93d2efd285e2375d26508f9c4aed50c3063`; 게임 원바이트는 Git 제외 동적 케이스에 둔다. [기동·캡처 보고서](../sessions/2026-09-28-audio-startup-report.md). `evidence:client` (E-119·120).

## 남은 검증

첫 `0x0034`의 복호·키 길이·초기 sequence·checksum 대조는 완료했다. 서버가 `0x0035`를 응답하고 원본 클라이언트의 `0x0036`을 기록해 골든 프레임 3개를 확보해야 전체 키 교환을 검증할 수 있다. 재키 `0x0031`은 `0x0030` 복호 본문 안에서 인식되는 제어값이므로 외부 평문 type으로 단순 구현하면 안 된다(`0x006130a0`). 현재 벡터는 초기 교환과 일반 봉투만 다룬다. `evidence:client` (첫 프레임), `evidence:guess` (후속 검증 경로).

## phase3 수용 조건·실패 처리 (T2 심화, candidate)

작성자: 최병호 · 2026-09-28. 근거 `evidence:client`, E-310. 디스어셈블리 `notes/t2deep-phase3.asm`을 Ghidra 출력과 대조했다. 아래 순서를 서버 체크리스트로 사용한다.

- [ ] 외부 type=0x35, wrapping ciphertext는 8바이트 배수. `0x00612d80`은 다른 type을 받으면 연결을 닫는다.
- [ ] 복호 성공 후 checksum 필드 2바이트, A_len 2바이트, A[A_len]가 존재한다.
- [ ] A_len은 클라이언트가 보낸 키 길이와 같고 A의 모든 바이트가 원래 키와 일치한다. **반향 키를 서버 송신키 B로 바꾸면 실패**한다.
- [ ] B_len 필드 2바이트와 B[B_len]가 존재한다. 정상 합성 벡터는 B_len=16이다.
- [ ] checksum은 A_len부터 B_initial_seq까지 정확히 A_len+B_len+8바이트를 계산한다. zero padding은 제외한다.
- [ ] B 수신키 설정 성공, B_initial_seq 네 바이트를 제공한다. 원본의 남은 길이 비교는 2지만 실제 읽기는 4다. checksum도 앞서 sequence까지 읽으므로 잘린 입력의 안전한 거부를 원본에서 기대하지 않는다.
- [ ] 0x36은 checksum + B_len + B를 wrapping key로 암호화한 결과다. B_len=16일 때 논리 20바이트, 암호문 24바이트, 외부 프레임 `00 1a 00 36 ...`이다. A를 반향하거나 세션키 B로 wrapping하지 않는다.

체크리스트 전체 `evidence:client`. 입력 검사·키 설정·할당·출력 암호화 실패는 phase3에서 false로 돌아간다. 호출자 `0x00612d80`은 false면 `0x00614b30 → 0x006151d0 → 0x00615d70(closesocket)`로 닫으며 **이 함수 안에서 같은 0x35를 재시도하지 않는다**. 이후 연결 폴러는 연결 상태 0을 오류 콜백으로 보고한다. 상위 UI/명령행 재진입에 따른 새 연결 시도와 이 실패 처리를 구분한다. 성공한 0x36 송신 뒤 연결 완료 콜백이 0x7000 또는 0x0020을 보낸다. `evidence:client` E-310/E-311.

### 실패 문자열과 관측 경로

아래는 공통 접두사 `[mpsCipherManager] exchange_key_phase3: ` 뒤의 문자열이다. 원본 영어 오타·명칭을 보존한다. 모두 `0x00402290(&DAT_03350ce0,...)` 스트림 출력 호출로 연결되며 **DBWIN/OutputDebugString으로 전달되는지는 미검증**이다. 수집 실패를 검사 통과 증거로 쓰지 않는다. `evidence:client` E-310.

| VA | 접미 문자열 | 검사 |
|---|---|---|
| 0x007c074c | `illegal param2` | wrapping 복호 실패 |
| 0x007c06fc | `param2 length is illegal(checksum)` | checksum 필드 부족 |
| 0x007c06b0 | `param2 length is illegal(cipher)` | A_len 필드 부족 |
| 0x007c0660 | `param2 length is illegal(encipher)` | A 바이트 부족 |
| 0x007c0614 | `disagree with encipher key length` | A 길이 불일치 |
| 0x007c0578 | `disagree with encipher key data` | A 내용 불일치 |
| 0x007c05c4 | `param2 length is illegal(cipher len)` | B_len 필드 부족 |
| 0x007c0524 | `param2 length is illegal(decipher len)` | B 바이트 부족 |
| 0x007c04ec | `broken data` | checksum 불일치 |
| 0x007c049c | `param2 length is illegal(sequence)` | sequence 잔여 길이 부족 |
| 0x007c0464 | `out of memory` | 0x36 평문 할당 실패 |

별도 게임 상태 로그 호출 `0x005923a0`은 CD판에서 **ret만 수행**한다. `InputFromCommandLine`, `ACCOUNT_ERROR`, `CONNECT_SS_OK` 등의 문자열은 정적 추적 표식으로 쓸 수 있지만 DBWIN 출력 기대값으로 삼으면 안 된다. `evidence:client` E-311.

리드에게 요청: 정상 0x35와 A 반향 불일치·checksum 불일치 벡터를 각각 **별도 새 연결**에 적용해 0x36 존재/부재, TCP 종료를 기록한다. 스트림 출력 수집은 추가 관측이며 네트워크 결과를 우선 확인한다. 길이 부족 벡터는 원본의 경계 검사 결함 때문에 VM 스냅샷을 확보한 별도 시험으로 분리한다. `evidence:guess` 검증 계획.
