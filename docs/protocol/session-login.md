# 세션·로비 로그인 정적 명세

작성자: 최병호 · 2026-09-28 · 상태: **candidate**, 동적 검증은 리드 담당.
근거: `evidence:client`, E-310~E-313. CD판 G7MTClient.exe 기준이며 VA와 메모리 offset은 wire offset과 구분한다.

## 첫 암호 응용 요청의 시점

`0x004ab6a0` 연결 폴러는 TCP 상태 2에서 `0x00612530 → 0x00612cb0`으로 새 송신키와 초기 sequence=1을 준비하고 0x34를 보낸다. 다음 폴링에서 `0x00612550 → 0x00612d80`이 0x35를 검증하고 0x36 송신 성공을 반환하면 완료 콜백을 호출한다. 로그인 연결의 콜백 `0x004ac430`은 즉시 **0x7000**을 보낸다. 키 교환 완료 뒤 별도의 UI 입력을 기다리는 단계는 이 콜백에 없다. 첫 정상 응용 프레임은 외부 type 0x30, 복호 본문은 `70 00`으로 시작한다. `evidence:client` E-310/E-311.

그러나 **연결 시작 전** UI 입력 여부는 기동 인자에 달려 있다. `0x0051a370`의 상태 0은 명령행 모드 플래그가 참이면 0x6e(InputFromCommandLine, 내부 위치 0x0051b935)로, 거짓이면 로그인 UI 상태 1로 간다. `0x007c0b40..43`의 첫 네 인자 존재 플래그 중 하나라도 0이면 명령행 모드를 해제한다. UI 상태 1은 두 입력칸이 비어 있지 않을 때 `0x0051bc20`으로 복사한 뒤 상태 4에서 연결한다. 명령행 상태 0x6e는 `0x0051bd70 → 0x004b6480`을 바로 호출한다. 인자는 공백 분할이므로 인자 안 공백 지원을 가정하지 않는다. `evidence:client` E-311.

| 인자 | 사용처·해석 후보 |
|---|---|
| argv[1], argv[2] | 접속 host, atoi(port) |
| argv[3] | 기본 `ginei00`, 전역 포인터 0x0076ee10. 0x004ad780이 0x7000의 첫 문자열로 전달한다. UI 계정 입력도 이 포인터를 갱신하고 0x02216bd2에 같은 문자열을 넣는다. **계정 식별 문자열로 실제 사용**되며 세션명 전용이라는 기존 해석은 정정 대상이다. |
| argv[4] | 기본 `1`. 상태 0x70에서 atoi → 0x02216c3c, 이후 하위 u16을 0x2009 세션 선택 요청에 전달한다. 선택 세션 ID 후보. 0x004ad120은 별도로 문자열 16바이트를 0x007c24a8에 보관한다. |
| argv[5] | 기본 `dummy`. 0x004ad780 → 0x004ac070의 둘째 인증 문자열, 최대 10 코드 단위. UI 둘째 입력도 0x0076ee18을 갱신한다. 비밀번호 용도 후보. |

표 전체 `evidence:client` E-311. 오래된 robot usage 문자열은 argv[3]을 session-server name이라고 부르지만, 실제 게임 UI 및 로그인 코덱의 데이터 흐름과 구분해야 한다. `ginei00`는 고정 wrapping key가 아니며, 별도 고정 키는 [키 교환 명세](kex-envelope.md)의 GUID다.

합성 계정 `A`, 인증 문자열 `B`(각 NUL 포함 2개 코드 단위)의 첫 평문 응용 메시지는 아래 21바이트다. `evidence:client`(필드 배치), `evidence:guess`(합성 값), E-314.

```text
70 00                         opcode
47 49 4e 37 00 01 00 00 00   signature/version/flag
02 00 41 00 00               A count + UTF-16BE units
02 00 42 00 00               B count + UTF-16BE units
```

이를 `checksum:BE16, sequence:BE32=1, body_len:BE16=21, body`로 감싸고 A 송신키로 전체 암호화한다. 외부 프레임은 `00 22 00 30`과 암호문 32바이트다. 일반 C→S opcode 헤더는 2바이트, 로그인 서버 S→C는 opcode+보조 u16 두 개, 재접속한 일반 연결 S→C는 보조 u32+opcode다. 보조 필드 의미·정상 값은 미확정이며 벡터의 0은 합성 선택이다. `evidence:client` E-302/E-314.

## 0x7001 이후: 두 번의 연결 전환

1. `0x004ac700`은 기존 로그인 연결을 닫고 0x7001의 주소·포트·토큰을 사용한다. 미리 만든 일반 암호 연결(0x004ad780의 0x00612030 호출, header offset=4)을 연결 폴러에 넘긴다. 폴러 인스턴스의 교환 플래그는 0으로 초기화되므로 **새 연결에서도 0x34/35/36을 반복**한다. `evidence:client` E-310/E-312.
2. 완료 콜백 `0x004ac4f0`은 `0x004ac670`에서 `00 20 + token:BE32`를 보내고 성공을 앱에 통지한다. 이 단계는 0x0020에 대한 별도 응답 opcode를 기다리지 않는다. UI 상태 6은 `0x0051bde0 → 0x004b78a0(1,5,...)`로 **0x2000 LobbyLoginRequest**를 보낸다. 다음 기대 응답은 0x2001 또는 0x2002다. `evidence:client` E-312.
3. 로비에서 0x2003/2004 캐릭터 정보와 **0x2005/2006 세션 목록**을 이용하고, 선택 ID:u16으로 0x2009를 보낸다. 0x200a 성공 본문은 host:u32, port:u16, token:u32(10바이트), 실패 0x200b는 아래 오류 구조다. `evidence:client` E-312.
4. `0x0051bee0 → 0x004adbe0`는 0x200a의 주소·포트·토큰으로 다시 연결한다. 이때도 새 연결 폴러가 키 교환을 수행한다. 그 뒤 UI 상태 0x34의 `0x0051bf40`이 **0x0200 SSLoginRequest**를 보내며, 0x0201/0202가 게임 세션 인증 응답이다. 로비 연결 직후 곧바로 0x0201을 보내는 서버는 이 흐름과 맞지 않는다. `evidence:client` E-312.

### 성공·실패 본문

모든 정수는 BE, u8은 단일 바이트다. `evidence:client` E-312.

| opcode | 코덱 VA | 본문 순서 | 길이 |
|---|---|---|---:|
| 0x2001 | 0x0043f830 | result:u8, opaque:u16 | 3 |
| 0x2002 | 0x0043f960 | error:u8, N:u8, code_unit:u16[N], N≤128 | 2+2N |
| 0x200a | 0x00446480 | host:u32, port:u16, token:u32 | 10 |
| 0x200b | 0x004465f0 | error:u8, N:u8, code_unit:u16[N], N≤128 | 2+2N |
| 0x0201 | 0x0044ea40 | result:u8 | 1 |
| 0x0202 | 0x0044eae0 | error:u8, N:u8, code_unit:u16[N], N≤128 | 2+2N |

0x2001/0201 수신 핸들러는 수신 자체로 완료 플래그를 설정한다. result의 원 서버 의미는 미확정이다. 이 오류 구조는 **0x7002의 u16 선두·u16 문자열 길이 구조와 다르다**. IPv4 숫자는 `0x0060fcc0`이 하위 바이트부터 문자열로 바꾸므로 localhost는 BE wire `01 00 00 7f`다. `evidence:client` E-302/E-312.

## LOGH-24 세션 목록 0x2006 후보

입력 코덱 `0x00444900`, 로비 등록 `0x0043f130`, 입력 코덱 선택 `0x00446ab0`으로 연결했다. 메모리 크기 0x5304(0x004ba2b0의 복사 크기)는 wire 길이가 아니다. 첫 필드는 result:u8, 다음은 count:u8≤64, 이후 가변 길이 세션 항목이다. `evidence:client` E-312.

각 항목은 아래 순서다. 의미를 증명하지 못한 필드는 opaque로 남긴다. `evidence:client` E-312.

| 순서 | wire 필드 | 상한·주석 |
|---|---|---|
| 1 | session_id:u16, opaque:u8 | 선택 요청의 ID와 대조 필요 |
| 2 | name_count:u8, name:u16[name_count] | ≤13, 진단명 session_name_size |
| 3 | date_count:u8, date:u16[date_count] | ≤65, 진단명 begin_day_size |
| 4 | opaque:u32 | 메모리 항목 +0xa4 |
| 5 | power 구조 ×2 | 진영별 후보, 다음 문단 |
| 6 | ending_count:u8, ending[] | ≤1; 원소 u16,u16,u32,u32,u32 |

power 구조: `opaque:u8, opaque:u32 ×3, ending_count:u8(≤1), ending[]`.
power ending 구조: `name_count:u8(≤13), name:u16[N], opaque:u16, opaque:u8 ×5, opaque:u16 ×2, opaque:u32 ×3`. 진단명은 super_man_size다. 세션 항목의 메모리 stride는 0x14c, power stride는 0x48이지만 둘 다 wire padding이 아니다. 두 power를 제국/동맹 중 어느 순서로 해석하는지는 미확정이다. `evidence:client` E-312.

name/date/ending이 모두 비어 있는 항목의 길이는 38바이트, 이름 `S1`+NUL 세 코드 단위만 넣으면 44바이트다. 해당 1개 목록 본문은 46바이트(선두 result/count 포함). 빈 목록과 1개 목록 벡터는 파서 입력 후보이며 UI가 표시 가능한 최소 데이터라는 보장은 없다. `evidence:client`(폭), `evidence:guess`(합성 데이터), E-314.

## 0x7002 오류 표시 위치

`0x004ac700`은 본문 객체 +2의 u8을 0x0076bbe4에 저장한다. 정상 UI 경로 상태 5가 `0x0051c930(code)`를 부르면 `0x00522010(0x6c,code)`로 constmsg 테이블을 조회한다. 서버가 보낸 문자열 본문을 이 경로의 표시 문자열로 직접 쓰지는 않는다. 명령행 상태 0x6f 실패는 상태 0으로 돌아가며 같은 오류 다이얼로그 호출이 없다. `evidence:client` E-311/E-313.

| code | 표시 후보(짧은 원문) | 위치 |
|---|---|---|
| 0 | 성공(`成功`) | constmsg.dat 그룹108 인덱스0, 전역 index3102, file offset 0x1bcbc |
| 1 | 실패(`失敗`) | constmsg.dat 그룹108 인덱스1, 전역 index3103, file offset 0x1bcc1 |
| 2~255 | `NO DATA` | 범위 밖 반환, exe VA 0x00670910 |
| 공통 제목 | 오류(`エラー`) | exe VA 0x00786ab4, 호출 0x0051c95a |

표는 CD판 MsgDat를 기준으로 한 정적 매핑이다. code0이 LGLoginNG에서도 성공 문구를 조회한다는 것은 코드 흐름이지 정상 서버 응답 권고가 아니다. 업데이트본 테이블·실제 화면은 별도 검증한다. `evidence:client` E-313.

## 리드에게 요청

- 올바른 0x35 → 0x36 → 첫 0x30을 기록하고, 첫 본문 opcode=0x7000 및 2바이트 헤더를 확인한다.
- argv[1..5]를 합성 host/port/계정/세션ID/인증문자열로 설정해 UI 입력 없이 요청되는지 확인한다. argv[1..3]만 제공한 대조 실행에서는 UI 대기 여부를 기록한다.
- 0x7001 redirect 후 새 0x34, 키 교환, 0x0020 및 이어지는 0x2000을 같은 타임라인으로 확인한다.
- 0x2006 빈 목록/합성 한 항목, 0x2009 → 0x200a → 게임 서버 연결 → 0x0200/0201을 검증한다. 두 power 순서와 opaque 의미는 캡처·UI로 확정한다.
- 정상 UI 로그인에서 0x7002 code1/2와 별도 합성 서버 문자열을 보내 실패/NO DATA 표시 및 서버 문자열 무시 여부를 확인한다.

이 요청들은 `evidence:guess` 검증 계획이며, 본 트랙은 VM 조작·클라이언트 실행을 하지 않았다.
