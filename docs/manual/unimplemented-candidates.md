---
title: 미구현·미완성 기능 후보 (단기 범위)
author: Peppone Choi
created: 2026-09-27
source: gin7manual.pdf(웹판), 銀英伝７マニュアル.pdf(CD판), wayback ns-update·update01~05
---

# 미구현·미완성 기능 후보

범위 정의(AGENTS.md §5): 원작 매뉴얼·공식 사이트·패치노트에 **존재했거나 "준비 중/예정"으로 예고된 기능** + 서버 소멸로 사라진 서버측 로직 전체. 원작에 없던 신규 아이디어는 여기에 넣지 않는다(백로그).

## 분류 기준

| 등급 | 뜻 |
|---|---|
| **A 명시 미구현** | 매뉴얼 본문이 "現在未実装"이라고 적었거나 공지가 연기를 발표 |
| **B 패치 도입** | 서비스 중 패치로 도입. 보유 클라이언트(버전 131, 2004-04 빌드)에 UI가 없을 수 있음 |
| **C 규칙 부재** | 커맨드·용어는 있으나 동작 규칙·수치가 매뉴얼에 없음 |
| **D 잠정 사양** | 매뉴얼이 "暫定"이라 적은 규칙 |

"클라이언트 흔적" 열: 트랙 B가 추출한 클라이언트 메시지 이름표 307개(`work/logh7-client-triage/notes/msg_ids.tsv`, 15개 이름 테이블)에서 찾은 결과(`evidence:client`, 정적). `미확인` = 이름표에서 못 찾음. 전략 커맨드 다수는 공통 커맨드 메시지로 보내질 수 있어 **이름표에 없다고 클라이언트에 없다는 뜻은 아니다**.

## 후보 목록

| ID | 기능 | 등급 | 근거 | 원작에서의 상태 | 클라이언트 흔적 | 제안 배치 |
|---|---|---|---|---|---|---|
| U-01 | AI 대행(오프라인 캐릭터 전술, 미선택 원작 캐릭터) | A | W p.8, p.11 "AI 機能は現在未実装"; ns-update 2004-11 전술 AI 예정; update04 "AI ON 오프라인 캐릭터가 임무 수행" | 2005-02 이전 도입된 것으로 보임 | 있음(부분): `NotifyAIOperation`(0x0357), NPC 도킹 계열 | P5 |
| U-02 | 경제(징세·예산·납입률·관세·분배·무역·개척) | A | W p.9 "経済関連は現在未実装"; ns-update 무역·개척 연기 | 미도입 | 있음: `CommandChangeTaxRate`, `CommandChangeBudgetRate`, `…PowerDistribution`(0x0308/0309). 커맨드 opcode 미확정(protocol-draft.md 0x0d??), 추적표 참조 | P5 |
| U-03 | 공격 시 물자 소비(건·미사일·공전) | A | W p.50 "物資の減少は現在未実装"; ns-update 2004-10 예정 | 도입 예정 발표 | 미확인 | P3(전투 밸런스와 묶음) |
| U-04 | 공전(전투정·뇌격정) | A | W p.50 "戦闘艇は現在未実装"; ns-update 2004-10 예정 | 도입 예정 발표 | 있음: `CommandAirBattle`(0x040e), `NotifyAirBattle`(0x0428), `…Fighters`(0x030e/030f) | P3/P5 |
| U-05 | 서훈(叙勲)과 래더 제3법칙 | A | W p.35 "叙勲は未実装" | 미도입 | 미확인 | P5 |
| U-06 | 채널 채팅 8종 | B | ns-update 2004-12; update02 | 도입 | 부분: `GlobalChat`(0x0207), `CommandGridChat`/`SpotChat`/`SpotUnicastChat`(0x0f1c~1e). 8채널 UI는 미확인 | P5 (클라이언트 패치 필요 가능) |
| U-07 | 페잔(중립·침범 페널티·점령·외교·회랑 통행권) | A | W p.41 "フェザーン占領は現在未実装"; CD p.39 규칙; ns-update 외교·첩보 연기 | 미도입 | 미확인 | P5 |
| U-08 | 생산계 커맨드(시설·함선 건조) | A | W p.41 자동 생산만; ns-update "기함 구매 다음" 예정 | 미도입(확인 안 됨) | 있음: `CommandInstitutionBuild`/`Pause`/`ReOperation`/`Destroy`, `…Institution`(0x0302/0320). 커맨드 opcode 미확정(protocol-draft.md 0x0d??), 추적표 참조 | P5 |
| U-09 | 행성 타입(가스 행성 등 지상전 제약) | A | W p.51 "惑星タイプは現在未実装" | 미도입 | 미확인 | P5 |
| U-10 | 쿠데타(叛意·謀議·説得·叛乱·参加·査閲) | C | W p.12 승리 조건에 쿠데타 언급, 커맨드 표 p.70·74 | 불명 | 미확인 | P5 |
| U-11 | 전사(戦死) | A | W p.52 "戦死は現在未実装" | 미도입 | 미확인 | P5 |
| U-12 | 최고사령관 임무·소속 명령 | B | CD p.55; update04 | 도입 설명 확인(2005-02-04 캡처 이전, 정확한 날짜 미확정) | 있음: `CommandMission`(0x0421), `NotifyTacticsChiefCommander`(0x0431), `NotifyMissionResult`(0x043c) | P3(전술 핵심) |
| U-13 | 경비대(警備隊, 무인 경비함 구매·해산) | B | ns-update; update01·03(26종 가격·계급) | 도입(2004-12~2005-02) | 미확인 | P5 |
| U-14 | 기함 구매·격침 시 구축함 복귀 | B | update05; W p.70 커맨드 표 | 도입(2005-04 이전) | 부분: `NotifyChangeFlagShip`(0x0358) — 구매 커맨드는 미발견 | P5 |
| U-15 | 전략 수송(輸送計画·輸送中止·搬出入) | A | ns-update "기함 구매 다음" | 미도입(확인 안 됨) | 있음: `…Package`(0x0328/0329), `…OutfitTransport`(0x0332/0333), `CommandCarryingInOut`(0x0c08) 등 | P5 |
| U-16 | 제안 메일 처리 대행(부재 시) | A | ns-update ~2004-12 | 불명 | 있음: `CommandOrderSuggestMail`(0x0f13), `CommandReplyOrderSuggestMail`(0x0f14) | P5 |
| U-17 | 첩보 공작(潜入·脱出·情報·破壊·煽動·侵入·帰還工作) | C | 커맨드 표 p.74; ns-update 페잔 첩보 연기 | 불명 | 미확인 | P5 |
| U-18 | 사교·영향력·우호도(夜会·狩猟·会談·談話·演説·会見) | C | 커맨드 표 p.69·72–73; 래더 제4법칙 "影響力" | 불명 | 미확인 | P5 |
| U-19 | 선거·신임(資金投入 박스, 의장 선출, 황제 승계) | C | 커맨드 표 p.70; CD p.38 | 불명 | 미확인 | P5 |
| U-20 | 작위·봉토(叙爵·封土授与·封土直轄, 封土 카드) | C | 커맨드 표 p.72; W p.36·51 | 불명 | 미확인 | P5 |
| U-21 | 치안·지지율(警戒出動·武力鎮圧·分列行進·徴発·特別警備) | C | 커맨드 표 p.68–69 | 불명 | 미확인 | P5 |
| U-22 | 훈련·스킬·교육(航宙/陸戦/空戦訓練, 戦術訓練, 受講·講義·兵棋演習) | C | 커맨드 표 p.68–71 | 불명 | 미확인 | P5 |
| U-23 | 철수 방향 인접 그리드화 | B | update05 | 도입 | 미확인 | P3 |
| U-24 | 성장·기함 변경 "잠정 사양" 확정 | D | W p.15, p.37 | 잠정 | 미확인 | P3 |
| U-25 | 체포·처단·망명(逮捕許可·執行命令·逮捕命令·一斉捜索·監視·襲撃·処断·亡命) | C | 커맨드 표 p.69·73–74; W p.8 오프라인 체포 | 불명 | 미확인 | P5 |
| U-26 | 이동 요새 이동 | C | CD p.54 | 불명 | 미확인 | P5 |
| U-27 | 세션 생애주기(종료 판정·재시작·캐릭터 이월·명성 포인트·원작 캐릭터 추첨) | C | W p.10–12, p.34 | 서버 로직(소실) | 해당 없음 | P3/P6 |
| U-28 | 정치가 래더 특칙(동맹 강제 전신, 제국 능력 저하) | C | CD p.38 | 불명(웹판 미기재) | 해당 없음 | P5 |
| U-29 | 귀환 행성 선택·완전보급·워프 연료 소비 | B | `evidence:manual` 09 P02 (2004-08-04, 08-05 예정) | 신규 공개 예고: 항속 100 미만 워프 불가, 워프 CP 40 | 기존 메시지와 대응 추가 확인 | P3 |
| U-30 | 행성총독/방위부장의 수비대 지휘관 임명 | A | `evidence:manual` 09 P04→P03 (2004-07-22 수정 보류) | 임명 명령 전체 미도입 아님; 해당 경로의 수정 보류, 후속 해소일 미확인 | 미확인 | P3/P5 |
| U-31 | 궤도 대지 공격·전술 수리 | B/C | `evidence:manual` 09 P13 예고; P02 작업함 자신 수리 오류 수정 | 수리 기능은 08월 수정 대상에 존재. 대지 공격 완료일은 이 공지 묶음에서 미확인 | 미확인 | P3 |
| U-32 | 해적의 이동·반격 AI | D | `evidence:manual` 09 P14 (2004-05-28) | 당시 해적은 이동·반격 없는 잠정 도입. 후속 완성 여부 미확인 | 미확인 | P5 |

### 패치 대조 보충 (2026-09-27)

모든 공지 근거는 [09 패치 이력](09-patch-history.md)의 캡처 URL로 연결한다. `evidence:manual`

- U-08: 자동 생산은 2004-06-24 군수물자 생산과 PDF p.76–78 품목으로 확인되지만 수동 시설·함선 건조의 도입 완료와는 구분한다.
- U-12: 2004-06-02 계획과 2005-02-04 이전의 설명 공개를 확인했다. 캡처 월을 출시 월로 확정하지 않는다.
- U-16: 06-11 명령·제안 도입은 부재 시 처리 대행 완료 증거가 아니다.
- U-23: 07-01 철수는 warp 50 및 원주 바깥 조건. update05의 방향별 인접 그리드 도입과는 별도 변경이다.
- U-24: 05-20 성장과 06-11 계급별 격침 경험치 페널티, 저연령 시간 성장을 확인. 미정 연령 경계·확률까지 확정된 것은 아니다.
- U-27: 추첨 12→3시간, 평가 포인트 고정 2,000 오류 수정, 로그인 잔류 타임아웃 300초를 추가 근거로 확보했다.
- p.100의 근위병·척탄병 교도·장미의 기사 비생산 주석은 생산 복원 범위 검토에 포함한다. 후속 생산 가능 시점은 미확인이다.

## 클라이언트 흔적 추적표 (LOGH-47, 정적)

기준: main `000877467665207e6bd61070f6c45c0eec4e8642`. 근거 `path:line`은 이 커밋 기준이다. 확신도는 low/medium만 쓴다(동적 증거 없음). medium은 opcode와 이름이 같은 저장소 행에 함께 있는 경우다. `(msg_ids.tsv, VPS 미검증)`은 기존 표에만 있고 저장소에서 재확인하지 못한 개별값이다. 흔적 미확인은 미구현·부재 증거가 아니다. 제안은 조사 제안이며 최종 개발 채택은 책임자가 정한다.

| U-ID | 원작 출처 | client string/command | opcode | handler VA | 근거 파일/행 | 확신도 | 미확인 이유 | 제안 |
|---|---|---|---|---|---|---|---|---|
| U-01 | W p.8, p.11; ns-update 2004-11; update04 | NotifyAIOperation, NPC 도킹 계열 | 0x0357 (msg_ids.tsv, VPS 미검증); 0x0356~0x035a 범위 내 | 미확인 (디스패처 0x004ba2b0, docs/re/function-map.md:88) | docs/protocol/protocol-draft.md:89, docs/protocol/protocol-draft.md:105, docs/manual/09-patch-history.md:52 | low | docs/protocol/protocol-draft.md:89 이름 목록에 AIOperation 없음, NPC 그룹 베이스 미확정(docs/protocol/protocol-draft.md:105). 개별 이름↔opcode는 msg_ids.tsv(Windows work/)에만 있음 | 구현후보: update04 원작 동작 근거와 부분 흔적이 있음 |
| U-02 | W p.9; ns-update 무역·개척 연기 | CommandChangeTaxRate, CommandChangeBudgetRate, …PowerDistribution | 커맨드: 0x0d?? 그룹 베이스 미확정; …PowerDistribution 0x0308/0309 (msg_ids.tsv, VPS 미검증), 0x0300~0x033f 범위 내 | 0x0308 파서 00410370 (server/protocol/src/main/kotlin/org/logh7/protocol/BootstrapMessages.kt:72) | docs/protocol/protocol-draft.md:96, docs/protocol/protocol-draft.md:88, server/protocol/src/main/kotlin/org/logh7/protocol/BootstrapMessages.kt:72 | low | 커맨드 opcode 미확정(그룹 베이스 미확정, docs/protocol/protocol-draft.md:96). 0x0308 파서 주석 power-direction tables와 PowerDistribution 이름 대응은 개별 이름↔opcode는 msg_ids.tsv(Windows work/)에만 있음 | 구현후보: 원작 미구현 명시이나 커맨드 흔적이 있음. opcode 귀속은 분리해 재확인 |
| U-03 | W p.50; ns-update 2004-10 | — | — | 미확인 | docs/manual/unimplemented-candidates.md:29 | 미확인 | 이름표 검색 미발견. UI 문자열(MsgDat/constmsg.dat)·커맨드 ID 테이블 미조사 | 보류: 흔적 미확인, 전투 밸런스 결정과 묶어 판단 |
| U-04 | W p.50; ns-update 2004-10 | CommandAirBattle, NotifyAirBattle, …Fighters | 0x040e/0x0428 (msg_ids.tsv, VPS 미검증), 0x0400~0x0442 범위 내; 0x030e/030f (msg_ids.tsv, VPS 미검증), 0x0300~0x033f 범위 내 | 0x030e 파서 00412980 (server/protocol/src/main/kotlin/org/logh7/protocol/BootstrapMessages.kt:70) | docs/protocol/protocol-draft.md:90, docs/protocol/protocol-draft.md:88, server/protocol/src/main/kotlin/org/logh7/protocol/BootstrapMessages.kt:70 | low | 0x030e 파서와 Fighters 이름 대응 및 AirBattle 개별값은 개별 이름↔opcode는 msg_ids.tsv(Windows work/)에만 있음 | 구현후보: 원작 예정 발표와 커맨드·통지 흔적이 있음 |
| U-05 | W p.35 | — | — | 미확인 | docs/manual/unimplemented-candidates.md:31 | 미확인 | 이름표 검색 미발견. UI 문자열(MsgDat/constmsg.dat)·커맨드 ID 테이블 미조사 | 보류: 흔적 미확인, 등급 A |
| U-06 | ns-update 2004-12; update02 | GlobalChat; CommandGridChat/SpotChat/SpotUnicastChat | 0x0207; 0x0f1c~1e (msg_ids.tsv, VPS 미검증), 0x0f00~0x0f1f 범위 내 | 0x0207 송신 0x004adeb0 (docs/re/function-map.md:23, low) | docs/protocol/protocol-draft.md:86, docs/protocol/protocol-draft.md:98, docs/re/function-map.md:23 | medium(0x0207) / low(나머지) | Grid/Spot chat 개별값은 개별 이름↔opcode는 msg_ids.tsv(Windows work/)에만 있음. 8채널 UI 미조사 | 클라이언트패치후보: 등급 B이고 8채널 UI 미확인. 패치 확정이 아닌 스파이크 대상 |
| U-07 | W p.41; CD p.39; ns-update 외교·첩보 연기 | — | — | 미확인 | docs/manual/unimplemented-candidates.md:33 | 미확인 | 이름표 검색 미발견. UI 문자열(MsgDat/constmsg.dat)·커맨드 ID 테이블 미조사 | 보류: 흔적 미확인, 등급 A |
| U-08 | W p.41; ns-update 기함 구매 다음 | CommandInstitutionBuild/Pause/ReOperation/Destroy, …Institution | 커맨드: 0x0d?? 그룹 베이스 미확정; …Institution 0x0302/0320 (msg_ids.tsv, VPS 미검증), 0x0300~0x033f 범위 내; CommandMoveInstitutionSpot 0x0e00 | 미확인 | docs/protocol/protocol-draft.md:96, docs/protocol/protocol-draft.md:88, docs/protocol/protocol-draft.md:97 | low | 커맨드 opcode 미확정(그룹 베이스 미확정, docs/protocol/protocol-draft.md:96). …Institution 개별값은 개별 이름↔opcode는 msg_ids.tsv(Windows work/)에만 있음 | 구현후보: 시설 커맨드 흔적이 있음. opcode 귀속은 분리해 재확인 |
| U-09 | W p.51 | — | — | 미확인 | docs/manual/unimplemented-candidates.md:35 | 미확인 | 이름표 검색 미발견. UI 문자열(MsgDat/constmsg.dat)·커맨드 ID 테이블 미조사 | 보류: 흔적 미확인, 등급 A |
| U-10 | W p.12, 커맨드 표 p.70·74 | — | — | 미확인 | docs/manual/07-command-table.md:48 | 미확인 | 이름표 검색 미발견. UI 문자열(MsgDat/constmsg.dat)·커맨드 ID 테이블 미조사 | 보류: 흔적 미확인, 등급 C |
| U-11 | W p.52 | — | — | 미확인 | docs/manual/unimplemented-candidates.md:37 | 미확인 | 이름표 검색 미발견. UI 문자열(MsgDat/constmsg.dat)·커맨드 ID 테이블 미조사 | 보류: 흔적 미확인, 등급 A |
| U-12 | CD p.55; update04 | CommandMission, NotifyTacticsChiefCommander, NotifyMissionResult | 0x0421/0x0431/0x043c (msg_ids.tsv, VPS 미검증), 0x0400~0x0442 범위 내 | 미확인 | docs/protocol/protocol-draft.md:90, docs/manual/09-patch-history.md:25, docs/manual/09-patch-history.md:43, docs/manual/09-patch-history.md:52 | low | 개별값은 개별 이름↔opcode는 msg_ids.tsv(Windows work/)에만 있음 | 구현후보: 원작 도입 근거와 커맨드·통지 흔적이 있음 |
| U-13 | ns-update; update01·03 | — | — | 미확인 | docs/l10n/glossary.md:52 | 미확인 | 이름표 검색 미발견. UI 문자열(MsgDat/constmsg.dat)·커맨드 ID 테이블 미조사 | 클라이언트패치후보: 등급 B이고 흔적 미확인. 패치 확정이 아닌 스파이크 대상 |
| U-14 | update05; W p.70 커맨드 표 | NotifyChangeFlagShip; 구매 커맨드 미발견 | 0x0358 (msg_ids.tsv, VPS 미검증), 0x0356~0x035a 범위 내 | 미확인 (디스패처 0x004ba2b0, docs/re/function-map.md:88) | docs/protocol/protocol-draft.md:89, docs/manual/09-patch-history.md:53 | medium(통지 범위) | ChangeFlagShip은 범위와 같은 행이나 개별값은 개별 이름↔opcode는 msg_ids.tsv(Windows work/)에만 있음. 구매 커맨드 미확인 | 클라이언트패치후보: 등급 B, 구매 커맨드 미확인. 패치 확정이 아닌 스파이크 대상 |
| U-15 | ns-update 기함 구매 다음 | …Package, …OutfitTransport, CommandCarryingInOut | 0x0328/0329, 0x0332/0333 (msg_ids.tsv, VPS 미검증), 0x0300~0x033f 범위 내; 0x0c08 (msg_ids.tsv, VPS 미검증), 0x0c00~0x0c0c 범위 내 | 미확인 | docs/protocol/protocol-draft.md:88, docs/protocol/protocol-draft.md:95 | low | 개별값은 개별 이름↔opcode는 msg_ids.tsv(Windows work/)에만 있음 | 구현후보: 수송 관련 흔적이 있음 |
| U-16 | ns-update ~2004-12 | CommandOrderSuggestMail, CommandReplyOrderSuggestMail | 0x0f13/0x0f14 (msg_ids.tsv, VPS 미검증), 0x0f00~0x0f1f 범위 내 | 미확인 | docs/protocol/protocol-draft.md:98, docs/manual/09-patch-history.md:46 | low | 개별값은 개별 이름↔opcode는 msg_ids.tsv(Windows work/)에만 있음. 메일 흔적은 부재 시 대행 증거가 아님(docs/manual/09-patch-history.md:46) | 구현후보: 서버측 대행 로직. 메일 흔적은 대행 증거 아님 |
| U-17 | 커맨드 표 p.74; ns-update 페잔 첩보 연기 | — | — | 미확인 | docs/manual/07-command-table.md:123 | 미확인 | 이름표 검색 미발견. UI 문자열(MsgDat/constmsg.dat)·커맨드 ID 테이블 미조사 | 보류: 흔적 미확인, 등급 C |
| U-18 | 커맨드 표 p.69·72–73; 래더 제4법칙 | — | — | 미확인 | docs/manual/07-command-table.md:99 | 미확인 | 이름표 검색 미발견. UI 문자열(MsgDat/constmsg.dat)·커맨드 ID 테이블 미조사 | 보류: 흔적 미확인, 등급 C |
| U-19 | 커맨드 표 p.70; CD p.38 | — | — | 미확인 | docs/manual/07-command-table.md:53 | 미확인 | 이름표 검색 미발견. UI 문자열(MsgDat/constmsg.dat)·커맨드 ID 테이블 미조사 | 보류: 흔적 미확인, 등급 C |
| U-20 | 커맨드 표 p.72; W p.36·51 | — | — | 미확인 | docs/manual/07-command-table.md:87 | 미확인 | 이름표 검색 미발견. UI 문자열(MsgDat/constmsg.dat)·커맨드 ID 테이블 미조사 | 보류: 흔적 미확인, 등급 C |
| U-21 | 커맨드 표 p.68–69 | — | — | 미확인 | docs/manual/07-command-table.md:28 | 미확인 | 이름표 검색 미발견. UI 문자열(MsgDat/constmsg.dat)·커맨드 ID 테이블 미조사 | 보류: 흔적 미확인, 등급 C |
| U-22 | 커맨드 표 p.68–71 | — | — | 미확인 | docs/manual/07-command-table.md:23 | 미확인 | 이름표 검색 미발견. UI 문자열(MsgDat/constmsg.dat)·커맨드 ID 테이블 미조사 | 보류: 흔적 미확인, 등급 C |
| U-23 | update05 | — | — | 미확인 | docs/manual/09-patch-history.md:29, docs/manual/09-patch-history.md:53 | 미확인 | 이름표 검색 미발견. UI 문자열(MsgDat/constmsg.dat)·커맨드 ID 테이블 미조사 | 클라이언트패치후보: 등급 B이고 흔적 미확인. 패치 확정이 아닌 스파이크 대상 |
| U-24 | W p.15, p.37 | — | — | 미확인 | docs/manual/09-patch-history.md:23, docs/manual/09-patch-history.md:24, docs/manual/09-patch-history.md:26, docs/manual/09-patch-history.md:28 | 미확인 | 이름표 검색 미발견. UI 문자열(MsgDat/constmsg.dat)·커맨드 ID 테이블 미조사 | 보류: 등급 D 잠정 사양, 흔적 미확인 |
| U-25 | 커맨드 표 p.69·73–74; W p.8 | — | — | 미확인 | docs/manual/07-command-table.md:117 | 미확인 | 이름표 검색 미발견. UI 문자열(MsgDat/constmsg.dat)·커맨드 ID 테이블 미조사 | 보류: 흔적 미확인, 등급 C |
| U-26 | CD p.54 | — | — | 미확인 | docs/manual/unimplemented-candidates.md:52 | 미확인 | 이름표 검색 미발견. UI 문자열(MsgDat/constmsg.dat)·커맨드 ID 테이블 미조사 | 보류: 흔적 미확인, CD판 단일 근거 |
| U-27 | W p.10–12, p.34 | — | — | — | docs/manual/09-patch-history.md:27, docs/manual/09-patch-history.md:30, docs/manual/09-patch-history.md:33 | — | 흔적 해당 없음(서버 전용 로직) | 구현후보: 서버 전용, 수치는 evidence:guess 필요 |
| U-28 | CD p.38 | — | — | — | docs/manual/unimplemented-candidates.md:54 | — | 흔적 해당 없음. 웹판 미기재 | 보류: CD판에만 있어 판본 범위 판단 필요 |

- U-29~U-32는 LOGH-47 범위 밖이므로 이 추적표에 넣지 않는다.
- P5 에픽 우선순위 갱신은 Linear 변경이므로 이 문서 범위 밖이며 책임자가 판단한다.

## 수치 미기재 목록 (설계 추정 `evidence:guess` 필요)

| 항목 | 필요한 값 | 근거 위치 |
|---|---|---|
| CP | 최대치, 회복량 공식(政治·運営), PCP/MCP 커맨드 귀속 | W p.27, p.68–74 |
| 워프 | 워프 가능 거리, 오차 확률, 航続 최대·소비·회복 | W p.32, CD p.31 |
| 성장 | 연령 경계, 월간 변화 확률·폭·상하한, CP 누적 단위 | W p.15 |
| 평가·명성 | 계급별 지급액, 사망 시 고정값, 명성 환산율, 원작 캐릭터 소비량, 이월 기준 | W p.11, p.34 |
| 승패 | 평가 등급별 공적 포인트 | W p.12 |
| 강등 | 대령 이하 자동 강등 조건 | W p.36 |
| 작전 | 보너스 기준값, 발동 시기별 CP | W p.38–40, p.70 |
| 전술 | CRC 최대·확대율 공식, 색적 정밀도, 사기 감소, 명중·피해 공식, 워프 준비 | W p.47–55 |
| 생산 | 함선·병원별 생산 주기·수량(품목은 W p.76–78 확인, 군수물자 1G일 주기는 패치 P11) | W p.41, p.76–78; 09 P11 |

## 다음 단계

1. 트랙 B 결과로 "클라이언트 흔적" 열을 채운다(UI 문자열 `MsgDat`·`constmsg.dat`, 커맨드 ID 테이블). 정적 흔적 추적은 "클라이언트 흔적 추적표 (LOGH-47, 정적)" 절 참조.
2. 흔적 `있음` + 등급 A/C → P5 우선순위 상, 흔적 `없음` + 등급 B → 클라이언트 패치 필요 여부 스파이크.
3. 트랙 C(커뮤니티 기록)로 "원작에서의 상태"를 보강.

## 2026-10-31 범위 결정 (사용자)

- U-26(이동 요새 이동)과 U-28(정치가 래더 특칙)은 CD판 매뉴얼(p.54, p.38)에만 있는 규칙이지만 구현 범위에 포함한다. 근거는 `evidence:manual`(CD판)으로 표시한다.
