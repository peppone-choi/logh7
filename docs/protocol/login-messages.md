# 로그인 메시지 본문

작성자: 최병호 · 2026-09-27 · 상태: **candidate**, 동적 미검증.
근거: `evidence:client`, E-302. 대상 `G7MTClient.exe`, 모든 코드 주소는 VA.

## 기존 초안 정정: 요청은 0x7000

실제 `LoginProcessor` 생성자 `0x004abdf0`은 로비 파서(`0x004026c0`)를 생성한다. 연결 완료 콜백 `0x004ac430`의 `0x004ac453`은 **0x7000**을 인자로 송신 가상함수에 넘긴다. 파서 vtable `0x0066bf2c+0x14`는 `0x00402880`이고, 해당 함수는 인자를 그대로 메시지 opcode에 넣는다. `0x00447270`도 출력 코덱으로 0x7000만 허용한다. 따라서 기존 `protocol-draft.md`의 “Login=0x0010, 근거 0x004ac070”은 이 경로의 opcode 증거로 사용할 수 없다. `0x0010` 로그인 본문은 이번 조사에서 확인되지 않았다. `evidence:client`

로비 **입력(S→C)** 헤더는 `[opcode:u16][field_50:u16][field_52:u16]`의 6바이트(`0x00402e30`)다. 코덱을 쓰는 **출력(C→S)** `0x00402ed0`은 opcode BE16만 쓴 뒤 본문 writer를 호출한다. 객체의 `+6`은 opcode 저장 위치이며 wire offset이 아니다. 두 수신 보조 헤더 값의 의미는 미확정이다. 아래 표는 각 방향 헤더를 제외한 본문 기준이다. `evidence:client`

## LGLoginRequest 0x7000

직렬화 `0x00446d90`, 초기화 `0x004ac070`. A/B는 각 문자열의 u16 개수다. `evidence:client`

| wire offset | 폭 | 내용 | 초기화 객체의 상대 위치 |
|---|---:|---|---|
| 0 | 4 | ASCII `GIN7`, raw bytes | +0x48 |
| 4 | 2 | BE16 = 1 | +0x4c |
| 6 | 2 | BE16 = 0 | +0x4e |
| 8 | 1 | 값 0, 의미 미확정 | +0x50 |
| 9 | 1 | A, 최대 31 | +0x51 |
| 10 | 2A | A개 BE16 문자열 코드 단위 | +0x52 |
| 10+2A | 1 | B, 최대 11 | +0x90 |
| 11+2A | 2B | B개 BE16 문자열 코드 단위 | +0x92 |

초기화는 첫 문자열을 최대 30, 둘째를 최대 10 코드 단위로 복사하고 각각 길이+1을 기록한다. 따라서 정상 경로에는 종료 NUL 코드 단위도 개수에 포함된다. 함수 인자 param3/param4가 이 두 문자열이며 계정/인증 문자열로 해석하되 실제 사용자 입력 연결은 추가 대조 대상이다. Shift_JIS 원시 문자열로 보내면 이 코덱과 맞지 않는다. `evidence:client`

실제 writer 합계는 `11+2(A+B)`다. 크기 예측 함수 `0x00446d70`은 `10+2(A+B)`를 반환하는 불일치가 있으므로 예측값을 wire body 길이로 복사하지 않는다. 실제 스트림 cursor와 캡처로 검증해야 한다. `evidence:client`

## LGLoginOK 0x7001

입력 코덱 `0x00447030`은 u16/u32/u16/u32 순서로 읽는다. 소비자 `0x004ac700`은 객체 +4를 IPv4 문자열로 바꾸고 +8을 접속 포트, +0xc를 후속 토큰으로 사용한다. `evidence:client`

| wire offset | 폭 | 의미 | 파싱 객체 offset |
|---|---:|---|---|
| 0 | 2 | 미확정 u16 | +0 |
| 2 | 4 | IPv4 숫자 BE32 | +4 |
| 6 | 2 | session port BE16 | +8 |
| 8 | 4 | session token BE32 | +0xc |

본문은 **12바이트**, 메모리 객체는 정렬 때문에 16바이트다. IPv4 문자열 변환 `0x0060fcc0`은 숫자의 하위 바이트부터 점으로 연결한다. 따라서 `127.0.0.1`에 해당하는 숫자는 `0x0100007f`, BE32 wire는 **`01 00 00 7f`**다. 일반 IPv4 네트워크 4바이트 `7f 00 00 01`을 그대로 넣으면 반대 주소가 된다. 실제 loopback 접속은 VM에서 확인해야 한다. `evidence:client`

## LGLoginNG 0x7002

입력 코덱 `0x00447070`, 소비자 `0x004ac700`. `evidence:client`

| wire offset | 폭 | 내용 |
|---|---:|---|
| 0 | 2 | 미확정 BE16 |
| 2 | 1 | 오류 코드: 소비자가 전역 결과값에 기록 |
| 3 | 2 | 메시지 코드 단위 수 N, BE16, 최대 128 |
| 5 | 2N | N개 BE16 코드 단위 |

코드별 사용자 표시 의미는 아직 매뉴얼·실행 관찰로 매핑하지 않았다. 빈 문자열은 N=0으로 표현할 수 있다는 파서 수준의 관찰이며, 원본 서버가 실제 사용한 응답값이라는 뜻은 아니다. `evidence:client`

## 세션 접속 0x0020

`0x004ac670`은 opcode=0x20을 설정하고 `OutputBuffer vtable+0x20 = 0x006115b0`을 호출한다. 본문 offset 0에 LGLoginOK에서 보관한 token을 **BE32 4바이트**로 쓴다. 이후 `0x006123d0`으로 송신한다. 송신 writer `0x006100a0`은 opcode BE16+본문만 써서 총 6바이트다. 일반 메시지 **수신** 계열은 `[u32 field_50][u16 opcode]`의 6바이트 헤더이므로 로그인 서버 로비 수신 헤더와 순서가 다르다(`0x006126b0`). 방향별 헤더를 동일하다고 가정하면 안 된다. `evidence:client`

## 재현·남은 검증

Ghidra 기존 프로젝트를 `-readOnly -noanalysis`로 열어 `LoghExport.java`의 `decomp:`로 위 주소를 내보냈다. 원시 자료는 케이스 `ghidra/export/G7MTClient.exe.t2login2.decomp.txt`, `notes/t2-login.asm`이다. `0x7000` 요청, `0x7001` 응답, 재접속 후 `0x0020`을 같은 VM 세션에서 캡처해야 상호운용 확인이 된다. `evidence:client`
