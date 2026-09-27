# 전략 지도 데이터 위치·형식

작성자: 최병호 · 2026-09-27 · LOGH-14 지원 · 상태: **candidate**, 동적 미검증.
근거: `evidence:client`, E-304. 주소는 CD판 `G7MTClient.exe` VA.

## 확인 결과와 복원 경계

그리드 종류·그리드 배열·거점 목록은 각각 서버 응답 **0x0313/0x0315/0x031d**로 들어오는 경로를 확인했다. 설치본의 은하·행성 모델 파일은 시각 자산이며 이 응답의 원본 데이터가 모두 들어 있다고 확인한 것은 아니다. 이번 작업은 파서와 소비 경로를 복원했고, 원본 서버의 전체 지도·성계/행성별 좌표값은 확보하지 못했다. `evidence:client`

## 정적 그리드 0x0315

파서 `0x004134e0`, 필드명 덤프 `0x00413870`, RLE 해제 `0x004abbb0`. `evidence:client`

| 본문 offset | 타입 | 필드 |
|---|---|---|
| 0 | u8 | width |
| 1 | u8 | height |
| 2 | u16 BE | compressed_size, 최대 5000 |
| 4 | compressed_size 바이트 | `(count:u8, value:u8)` 반복 |

해제기는 각 value를 count번 반복하여 일차원 배열에 쓰며 누적 길이가 width×height를 넘으면 실패, 마지막 길이가 정확히 일치하면 성공한다. 원본 함수는 size=0을 거부하며 홀수 마지막 바이트 처리도 느슨하므로 서버 생성기는 짝수 size와 정확한 합계를 요구하는 것이 안전한 후보 구현이다. `evidence:client`

수신 디스패처 `0x004ba2b0`은 0x0315 객체를 게임 상태 객체 상대 `+0x3f4448`에 복사하고 **+0x3f444c**부터 RLE 해제 결과를 쓴다. 이 오프셋은 VA가 아니라 런타임 객체 상대값이다. width/height 뒤의 5000바이트 저장 영역을 재사용한다. `evidence:client`

그리드 index→(x,y)를 `x=index%width`, `y=index/width`로 쓰는 것은 자연스러운 후보지만 소비자의 순회·원점·축 방향을 아직 증명하지 못했다. 원작 좌표로 확정해서 데이터베이스에 넣지 않는다. `evidence:guess`

## 그리드 종류·항성 0x0313

파서 `0x00413050`, 덤프 `0x004133f0`에서 필드명을 확인했다. `evidence:client`

| 본문 offset | 타입 | 필드 |
|---|---|---|
| 0 | u8 N | 항목 수, 최대 100 |
| 1+3i | u8 | kind |
| 2+3i | u8 | type |
| 3+3i | u8 | fixedstar |

파싱 객체 크기는 301바이트이며 디스패처가 상태 객체 `+0x3f57d4`에 복사한다. `fixedstar`는 원본 필드명으로 확인했지만 번호별 항성 이름/모델 매핑은 추가 분석 대상이다. 원작 성계 이름 목록이 이 메시지에 문자열로 들어오는 구조는 아니다. `evidence:client`

## 행성·거점 정적 정보 0x031d

파서 `0x004142e0`, 필드명 덤프 `0x00414a30`. 본문 첫 BE16은 항목 수(최대 350). 각 항목은 다음 순서로 이어진다. 항목 시작을 offset 0, 이름 코드 단위 수를 N이라 한다. `evidence:client`

| wire offset | 타입 | 필드명 | 메모리 항목 offset |
|---|---|---|---|
| 0 | u32 BE | ID | +0 |
| 4 | u16 BE | grid | +4 |
| 6 | u16 BE | model_file | +6 |
| 8 | u16 BE | kind | +8 |
| 10 | u8 | N, 최대 13 | +10 |
| 11 | N×u16 BE | name | +12 |
| 11+2N | u8 | class_ | +38 |
| 12+2N | float32 BE 비트패턴 | revolution_radius | +40 |
| 16+2N | u32 BE | revolution_cycle | +44 |
| 20+2N | u8 | revolution_direction | +48 |
| 21+2N | float32 BE 비트패턴 | revolution_init_angle | +52 |
| 25+2N | float32 BE 비트패턴 | diameter | +56 |

항목 wire 크기는 `29+2N`, 메모리 크기는 정렬/고정 이름 배열 때문에 60바이트다. 응답 객체는 4바이트 정렬 헤더 + 최대 350×60 = **21004(0x520c)바이트**이며 상태 객체 `+0x3f5ae8`에 복사된다. `evidence:client`

float 입력 `0x00611bc0`은 u32에 **ntohl을 적용한 뒤 float로 해석**한다. 기존 초안의 “float raw little-endian”을 이 경로에 적용하면 잘못된다. 공전 반경·초기 각도·방향·주기와 소속 grid가 존재하므로 행성 위치를 재현할 재료는 있으나, 단위·각도 기준·시간 기준·class 값별 모델 관계는 아직 미검증이다. `evidence:client`

## 클라이언트 시각 자산

원본 읽기 전용 루트 `E:\logh7-original\extracted\install\ｱﾌﾟﾘｹｰｼｮﾝ実行可能ﾌｧｲﾙ\` 아래에 다음 파일이 있다. 경로 리터럴과 실제 파일 존재를 대조했고, 해시·32바이트 prefix는 케이스 `notes/t2-map-assets.json`에 남겼다. 게임 자산은 저장소로 복사하지 않았다. `evidence:client`

| 상대 경로 | 클라이언트 경로 문자열 VA | 용도 판단 |
|---|---|---|
| `data/model/strategy/galaxy.mdx` | 0x00772174 | 은하 시각 모델 후보 |
| `data/model/strategy/grid.mdx` | 0x00772130 | 그리드 시각 모델 후보 |
| `data/model/strategy/Null_galaxy.mdx` | 이번 조사 미확인 | 별도 모델 자산 |
| `data/model/Planets/fs000.mdx` 등 | 0x00772b10 등 | 항성/행성 렌더링 모델 후보 |

MDX 내부 정점 포맷과 지도 셀의 관계는 이번 분석에서 해제하지 않았다. 파일 이름만으로 영구적인 게임 좌표 데이터라고 간주하지 않는다. `evidence:client`

## 다음 단계

위 세 메시지의 합성 데이터를 로컬 서버에서 보내 UI에 표시되는 좌표/모델을 관찰해야 한다. 원본 성계·행성별 데이터 값은 매뉴얼/공식 사이트의 위치 자료를 우선 재서술하고, 없는 값은 명시적인 추정 데이터로 관리한다. **원본 전체 지도 복원 완료로 LOGH-14를 닫을 근거는 아직 없다.** `evidence:guess`
