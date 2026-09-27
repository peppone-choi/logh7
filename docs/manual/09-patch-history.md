---
title: 공식 패치 이력과 복원 규칙
author: 최병호
created: 2026-09-27
---

# 공식 패치 이력과 복원 규칙

## 1. 조사 범위 `evidence:manual`

Wayback 목록 §2.2의 `ct=update` 고유 URL 33개를 2026-09-27에 모두 열람했다. **33개 URL은 33개 서로 다른 공지가 아니다.** 최초 캡처 본문을 비교하면 18개 공지이며, 게시일은 2004-05-14~08-26이다. 2004-12·2005-02 캡처에도 이전 글이 반복된다. 페이지 번호와 `num`은 시점에 따라 달라지므로 아래 캡처 URL까지 함께 식별자로 사용한다. 2004-09 이후 이력 전체를 읽었다는 뜻은 아니다.

CGI 본문은 EUC-JP 계열이며 Shift_JIS로 읽으면 깨진다. 일부 확장 로마숫자는 디코딩이 불완전하므로 추정하여 수치를 옮기지 않았다. 공지의 예정·보류·적용을 분리하고, 캡처 날짜를 도입일로 쓰지 않는다. 원문 HTML·장문 텍스트·이미지는 산출물에 포함하지 않았다.

## 2. 고유 공지 요약 `evidence:manual`

문서 번호는 이 폴더의 01~08 문서, U 번호는 [미구현 후보](unimplemented-candidates.md)를 가리킨다. 아래의 “예정” 항목은 적용 완료를 보증하지 않는다.

| 게시/적용 시점 | 한국어 요약 | 관련 문서 | U 번호 | 출처 |
|---|---|---|---|---|
| 2004-05-14 | 최초 설치 뒤 다운로드 업데이터를 별도로 설치하고, 이후 게임 시작 시 자동 업데이트. | 00 | — | [P17](https://web.archive.org/web/20041024204857id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=1&num=17) |
| 2004-05-18 | 로그인 버튼 인증 오류 수정. PDF에 전술 단축키와 은하 지도 부록 추가. | 00, 06 | — | [P16](https://web.archive.org/web/20041024203648id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=1&num=16) |
| 2004-05-20 | 경험치 100마다 해당 능력 +1, 능력 51 이상은 고령일수록 감소 가능. 명령으로 경험치 획득, 당시 격침 시 경험치 0(06-11 규칙으로 후속 변경). 일부 ID 로그인 오류 수정. | 02 | U-24 | [P15](https://web.archive.org/web/20041024202809id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=1&num=15) |
| 2004-05-28 | 해적 잠정 도입(이동·반격 없음), 요새사령관 요새포 사용, 승진 기함 변경. 아군 유닛/행성/요새가 있는 그리드에 단함 진입 허용, 카메라·메일·명령 서버 다운 수정. | 04, 06 | U-24, U-32 | [P14](https://web.archive.org/web/20041024202140id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=1&num=14) |
| 2004-06-02 (계획) | 6월 명령/제안·삭제·전술 진입/철수·최고사령관 임무/소속 명령 계획. 이후 대지 공격·전술 수리·완전수리 수정·귀환지 선택 계획. 완료 증거와 분리. | 03, 05, 06 | U-12, U-16, U-29, U-31 | [P13](https://web.archive.org/web/20041024200515id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=1&num=13) |
| 2004-06-11 | 발탁·강등·임명·파면·사임·할당 명령/제안 도입. 어린 캐릭터 시간 성장과 노화 면제. 기함 격침 경험치 감소: 황제/원수 50, 상급대장~준장 30, 대령~소령 10, 대위~소위 5, 조장~이등병 1. 경험치 0인 능력은 감소 가능. 완전수리는 재개하나 일부 오류 잔존. | 02, 03, 05 | U-16, U-24 | [P12](https://web.archive.org/web/20041024193825id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=1&num=12) |
| 2004-06-24 | 대령 이하 생성 캐릭터 삭제 도입. 군수물자는 1G일마다 생산, 원작 캐릭터 추첨 간격 12→3시간. 추첨 취소 계정 잠김·수리·재편성 수정. 전술 평행 이동은 일반 속도의 1/2. | 01, 02, 05, 06 | U-08, U-27 | [P11](https://web.archive.org/web/20041026081604id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=1&num=11) |
| 2004-06-25 (06-24 적용) | 공격보다 방어가 높을 때 방어의 명중률 영향 완화, 부대의 단함 공격 강화, 전투 경험치 하향, 행성 주둔 유닛의 차폐 효과 제거. 계수는 미기재. | 02, 06 | U-24 | [P10](https://web.archive.org/web/20041026080805id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=1&num=10) |
| 2004-07-01 17:45 | 전술 진입 시 유닛 겹침 완화. 철수에는 warp 에너지 50 필요, 레이더 원주 바깥에서 실행, 철수 시간은 종전 절반 이하. 출현 그리드 오류 및 제국 구축함 레일건 각도·일부 유닛 성능 수정. | 06 | U-23 | [P09](https://web.archive.org/web/20041024222932id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=1&num=9) |
| 2004-07-01 21:00 | 평가 포인트가 일률 2,000 지급되던 오류를 수정하여 공적에 따라 가변 지급. 환산 공식은 미기재. | 01, 02 | U-27 | [P08](https://web.archive.org/web/20041024221712id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=1&num=8) |
| 2004-07-12 | 7월에는 신규 기능보다 서버·기존 기능 오류 수정을 우선하고, 안정화 후 기능 추가 방침. 도입 완료 공지가 아님. | 09 §2 | — | [P07](https://web.archive.org/web/20041024220701id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=1&num=7) |
| 2004-07-14 | 제국 육전대 투하 시 방어력 100%에서도 즉시 점령되던 문제와 동맹 방어력 감소 불가 문제 수정. | 06 | — | [P06](https://web.archive.org/web/20041024220020id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=1&num=6) |
| 2004-07-20 | 전술 3D 초기화 중 끊김을 줄이기 위해 타임아웃 120→300초. 비정상 단절 뒤 로그인 정보가 남으면 재접속까지 300초가 걸릴 수 있음. | 01 | U-27 | [P05](https://web.archive.org/web/20041024215109id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=1&num=5) |
| 2004-07-20 → 07-22 예정 | 방위작전 발령 목록, 로그아웃 후 명함 승인, 보충·재편성·완전수리, 생일 나이·점령 상태 수정. 전술 소속 변경의 무제한 거리 허용 제거. 귀환 성계가 전술 중이면 다른 성계 복귀. 적 부대만 있고 아군 부대/행성/요새가 없는 그리드의 단함 진입 제한. 임명 항목은 다음 공지에서 보류. | 02, 03, 04, 05, 06 | U-12, U-24, U-30 | [P04](https://web.archive.org/web/20041024214138id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=1&num=4) |
| 2004-07-22 | 07-20 예고 중 동맹 방위부장·제국 행성총독의 수비대 지휘관 임명 수정은 당일 적용 보류. 다른 항목까지 취소했다고 확대 해석하지 않음. | 05 | U-30 | [P03](https://web.archive.org/web/20041024213235id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=1&num=3) |
| 2004-08-04 → 08-05 예정 | 귀환 행성 선택·완전보급 도입 예고. 전략 워프 연료 소비 및 항속 100 미만 실행 금지, CP 80→40(도움말은 구값). 지속 명령 중복 금지, 색적·수리·지상부대 편성·손상함 표시 수정. | 04, 05, 06 | U-29 | [P02](https://web.archive.org/web/20041024205307id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=1&num=2) |
| 2004-08-26 08:00 | 당일 점검 예정: 승진 후 권한 재임명, 요새 점령 후 임명, 주둔 완전수리, 만충전 연료 보급, 주둔 적의 공격, 원격 육전대 회수 오류 수정. 재점령·수도 점령 서버 다운, 워프 시 보급 취소, 메일·일력 표시 수정. | 05, 06 | U-27 | [P01](https://web.archive.org/web/20041026080156id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=1&num=1) |
| 2004-08-26 18:20 | 적용: 동맹 정찰순항함 속도 30,000→28,000, 구축함 기함 28,000→30,000. 새 세션: 제국 순항함 필요 인원 -1, 양 진영 생산 품목 추가, 연습장 제거. 세부 로마숫자는 인코딩 손상이 있어 이 표에서 확정하지 않음. | 08 §2·3 | U-08, U-22 | [P00](https://web.archive.org/web/20041026074624id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=1&num=0) |

## 3. 복원에 반영할 판정 `evidence:manual`

- 성장·격침 페널티는 05-20과 06-11을 동시에 적용하지 않는다. 후자는 경험치 전부 초기화를 계급별 차감으로 바꾼다. 나이 경계·확률은 여전히 미확정이다.
- 06-02의 임무 명령 예고만으로 U-12를 6월 도입으로 올리지 않는다. 후속 update04 설명은 2005-02-04 캡처 시점에 이미 공개되어 있었다. 기존 “2005-02 도입” 단정은 “해당 날짜 이전 설명 확인”으로 정정한다.
- 자동 생산 품목은 누락되지 않았다. 웹판 PDF p.76–78에 있으며, 군수물자 생산 주기는 06-24 공지에서 1G일로 확인된다(함선·병원 각각의 생산 주기까지 확정하지 않음). 경제·시설 건조 전체의 구현 완료를 뜻하지 않는다.
- 08-26의 새 세션 적용 조정은 기존 세션에 즉시 소급된다고 해석하지 않는다. PDF 표와 공지 간 차이는 버전별로 관리한다.
- 명령/제안 도입은 U-16의 **부재 시 자동 처리 대행** 도입 증거가 아니다.

## 4. 후기 설명 페이지 보충 `evidence:manual`

| 확인 범위 | 변경 규칙 | 관련 문서 / U | 출처 |
|---|---|---|---|
| 2005-02-04 캡처 이전에 설명 공개 | AI ON 오프라인 캐릭터의 임무 수행. 방위는 24G분마다 유지 보상, 요격 목표 격파 공적 3배, 정찰은 24G분마다 목표 1,200만 km 이내 판정, 철수 임무는 4G시간 이내 철수 보상. | 06 / U-01·12 | [update04](https://web.archive.org/web/20050204043916/http://gineiden.com/update04.html) |
| 2005-04-18 캡처 이전에 설명 공개 | 수도 기함공창에서 평가 포인트로 기함 구매, 계급 제한. 격침 복귀 기함은 계급과 무관하게 구축함. 철수 위치 방향의 인접 그리드로 이동. 정확한 도입일은 이 페이지에서 미확정. | 06 / U-14·23 | [update05](https://web.archive.org/web/20050418003249/http://gineiden.com/update05.html) |

## 5. 운영 구성·장애 패턴 `evidence:manual`

[2004-10-11 설명회 보고](https://web.archive.org/web/20041017221738/http://gineiden.com/ev041015.html)를 직접 열람했다. 당시 1세션 서버 2대, 3세션 총 6대라고 설명하며, 지연 원인을 하드웨어 성능보다 서버 프로그램 로직으로 설명한다. 데이터 이상 확산 방지와 원인 조사를 위해 서버 전체를 즉시 내리는 당시 동작도 명시한다. 이는 운영사의 당시 설명이며 두 서버의 역할 분담이나 DB 구성은 미기재다.

패치 공지에는 수도 점령·재점령에 따른 서버 다운, 타임아웃 후 로그인 상태 잔류, 로그아웃한 상대의 명함 승인 시 단절이 나온다. 상태 전이와 세션 정리의 회귀 검증 대상으로 삼을 수 있다(`evidence:guess`, 복원 설계 제안). `ct=mente` 374건 전체 분석은 수행하지 않았으며 추가 CDX 조회는 타임아웃으로 끝났다.

## 6. 33 URL 열람 대조표 `evidence:manual`

본문 동등성은 날짜와 규칙 내용으로 대조했다. 중복 URL은 별도 패치로 세지 않는다.

| URL num | 본문 날짜 | 대응 요약 | 캡처 원문 |
|---|---|---|---|
| 0 | 2004.8.26 [18:20] | P00 | [열람](https://web.archive.org/web/20041026074624id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=1&num=0) |
| 1 | 2004.8.26 [08:00] | P01 | [열람](https://web.archive.org/web/20041026080156id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=1&num=1) |
| 2 | 2004.8.4 [08:18] | P02 | [열람](https://web.archive.org/web/20041024205307id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=1&num=2) |
| 3 | 2004.7.22 [17:15] | P03 | [열람](https://web.archive.org/web/20041024213235id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=1&num=3) |
| 4 | 2004.7.20 [20:00] | P04 | [열람](https://web.archive.org/web/20041024214138id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=1&num=4) |
| 5 | 2004.7.20 [17:00] | P05 | [열람](https://web.archive.org/web/20041024215109id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=1&num=5) |
| 6 | 2004.7.14 [03:38] | P06 | [열람](https://web.archive.org/web/20041024220020id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=1&num=6) |
| 7 | 2004.7.12 [20:31] | P07 | [열람](https://web.archive.org/web/20041024220701id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=1&num=7) |
| 8 | 2004.7.1 [21:00] | P08 | [열람](https://web.archive.org/web/20041024221712id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=1&num=8) |
| 9 | 2004.7.1 [17:45] | P09 | [열람](https://web.archive.org/web/20041024222932id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=1&num=9) |
| 10 | 2004.6.25 [20:00] | P10 | [열람](https://web.archive.org/web/20041026080805id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=1&num=10) |
| 11 | 2004.6.24 [09:40] | P11 | [열람](https://web.archive.org/web/20041026081604id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=1&num=11) |
| 12 | 2004.6.11 [12:50] | P12 | [열람](https://web.archive.org/web/20041024193825id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=1&num=12) |
| 13 | 2004.6.2 [17:15] | P13 | [열람](https://web.archive.org/web/20041024200515id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=1&num=13) |
| 14 | 2004.5.28 [16:30] | P14 | [열람](https://web.archive.org/web/20041024202140id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=1&num=14) |
| 15 | 2004.5.20 [15:30] | P15 | [열람](https://web.archive.org/web/20041024202809id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=1&num=15) |
| 16 | 2004.5.18 [05:10] | P16 | [열람](https://web.archive.org/web/20041024203648id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=1&num=16) |
| 17 | 2004.5.14 [13:58] | P17 | [열람](https://web.archive.org/web/20041024204857id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=1&num=17) |
| 18 | 2004.6.25 [20:00] | P10 | [열람](https://web.archive.org/web/20041207141533id_/http://www.gineiden.com:80/bin/backview.cgi?ct=update&page=1&num=18) |
| 19 | 2004.6.24 [09:40] | P11 | [열람](https://web.archive.org/web/20041207142111id_/http://www.gineiden.com:80/bin/backview.cgi?ct=update&page=1&num=19) |
| 20 | 2004.6.11 [12:50] | P12 | [열람](https://web.archive.org/web/20041212220610id_/http://www.gineiden.com:80/bin/backview.cgi?ct=update&page=2&num=20) |
| 21 | 2004.6.2 [17:15] | P13 | [열람](https://web.archive.org/web/20041212215658id_/http://www.gineiden.com:80/bin/backview.cgi?ct=update&page=2&num=21) |
| 22 | 2004.5.28 [16:30] | P14 | [열람](https://web.archive.org/web/20041212220515id_/http://www.gineiden.com:80/bin/backview.cgi?ct=update&page=2&num=22) |
| 23 | 2004.5.20 [15:30] | P15 | [열람](https://web.archive.org/web/20041212215445id_/http://www.gineiden.com:80/bin/backview.cgi?ct=update&page=2&num=23) |
| 24 | 2004.5.18 [05:10] | P16 | [열람](https://web.archive.org/web/20041212220429id_/http://www.gineiden.com:80/bin/backview.cgi?ct=update&page=2&num=24) |
| 25 | 2004.5.14 [13:58] | P17 | [열람](https://web.archive.org/web/20041212215713id_/http://www.gineiden.com:80/bin/backview.cgi?ct=update&page=2&num=25) |
| 26 | 2004.6.24 [09:40] | P11 | [열람](https://web.archive.org/web/20050212065453id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=2&num=26) |
| 27 | 2004.6.11 [12:50] | P12 | [열람](https://web.archive.org/web/20050212070054id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=2&num=27) |
| 28 | 2004.6.2 [17:15] | P13 | [열람](https://web.archive.org/web/20050212065841id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=2&num=28) |
| 29 | 2004.5.28 [16:30] | P14 | [열람](https://web.archive.org/web/20050212070527id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=2&num=29) |
| 30 | 2004.5.20 [15:30] | P15 | [열람](https://web.archive.org/web/20050212065915id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=2&num=30) |
| 31 | 2004.5.18 [05:10] | P16 | [열람](https://web.archive.org/web/20050212070532id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=2&num=31) |
| 32 | 2004.5.14 [13:58] | P17 | [열람](https://web.archive.org/web/20050212065857id_/http://gineiden.com:80/bin/backview.cgi?ct=update&page=2&num=32) |

후속 조사: 2004-09 이후는 동일 CGI의 다른 캡처와 최신 공지 `update.cgi`를 추가 조회해야 한다. 현재 33개 최초 캡처만으로 서비스 종료까지의 패치 전체를 복원할 수 없다. `evidence:manual`
