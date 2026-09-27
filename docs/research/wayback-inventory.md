# Wayback Machine 보존 URL 목록 — gineiden.com 및 관련 도메인

- 작성자: 최병호
- 작성일: 2026-09-27
- 모든 URL·CDX 조회의 확인 날짜: 2026-09-27
- 근거 태그: `evidence:manual`(공식 사이트 아카이브 본문), `evidence:web`(제3자), `evidence:guess`(추정)
- 이번 조사에서 **파일(exe/pdf/이미지)은 내려받지 않았다.** HTML 본문은 조사용으로 텍스트만 읽었다(scratchpad 임시 보관, 프로젝트에는 저장하지 않음).

---

## 0. 조사 방법

| 조회 | 결과 |
|---|---|
| `http://web.archive.org/cdx/search/cdx?url=gineiden.com/*&output=json&fl=timestamp,original,statuscode,mimetype&collapse=urlkey&limit=5000` | 고유 URL 1,078개: text/html 863, image/gif 131, image/jpeg 74, text/css 5, application/pdf 2, image/png 1, application/octet-stream 1 |
| 같은 조회에 `matchType=domain`(서브도메인) | `web.gineiden.com`, `help.gineiden.com`, `mail.gineiden.com` 발견 |
| 주요 URL을 `collapse=digest`로 재조회 | 같은 URL의 내용이 바뀐 캡처 시점 확인(아래 표의 "다른 캡처") |
| 관련 도메인 | `bothtec.co.jp`(개발·운영사), `multiterm.co.jp`(MPS 미들웨어), `microvision.co.jp`(저작권 표기 협력사) |
| 한계 | 조사 후반 Internet Archive가 일시 오프라인/504를 반환하여 일부 재조회(`gin7manual.pdf` digest 비교, `bothtec.co.jp/mobile/v/ginei/*`)는 **미완료** |

CDX 결과를 그대로 재현하려면 위 쿼리를 다시 실행하면 된다(`collapse=urlkey`는 URL별 첫 캡처만 보여 주므로, 특정 URL의 전체 이력은 `url=<정확한 URL>&collapse=digest`로 조회).

---

## 1. 이미 보유(로컬 `E:\manual-variants\wayback`)

| 경로 | 보유 캡처 | 다른 캡처(참고) | 상태 |
|---|---|---|---|
| /manual.html | 20040624034757, 20041009191503 | 20040611023113 | 보유 |
| /ns_update.html | 20041016212823 | — | 보유 |
| /st_tactics.html | 20040407015054 | — | 보유 |
| /st_tactics2.html | 20040504060412 | — | 보유 |
| /st_tactics3.html | 20040508215646 | — | 보유 |
| /update.html | 20040528152716 | **20050308143828**(내용 다름, 아래 2.1 참조) | 보유(후기 캡처는 신규 후보) |
| /update01.html | 20050207230754 | 20041016144950 | 보유 |
| /update02.html | 20050207231730 | — | 보유 |
| /update03.html | 20050207232539 | 20041229192819 | 보유 |
| /update04.html | 20050204043916 | — | 보유 |
| /update05.html | 20050418003249 | — | 보유 |
| /gin7manual.pdf | 20050504172714 | 20050117215152(동일 파일 여부 미확인) | 보유 |
| /disposition.pdf | 20040503044718 | — | 보유 |

---

## 2. 신규 후보

### 2.1 최우선: 접속·패치·요구사항·계정 (서버 재구현에 직접 관련)

| 경로(캡처) | 분류 | 내용 요약 | 근거 |
|---|---|---|---|
| /G7UPD040514.exe (20040625193252) | 패치 바이너리 | 공식 업데이터 v040514. 10,594,762바이트, `application/octet-stream`, CDX digest `CYE6GQNL7V3N3IN52KWGBZSDAPMRXDED`. 원본 받기용 URL: `https://web.archive.org/web/20040625193252id_/http://gineiden.com:80/G7UPD040514.exe` (**내려받기는 사용자 승인 후**) | evidence:manual |
| /G7UPD050125.exe | 패치 바이너리 | 2005-01-25판 업데이터(11.4MB). **CDX에 없음 → 찾지 못함** | — |
| /update.html (20050308143828) | 패치 안내 | 신규 설치자는 G7UPD050125.exe 필요. 2004-12-16 업데이트 미적용 클라이언트는 자동 업데이트가 정상 동작하지 않음 | evidence:manual |
| /bin/news.cgi?num=3 (20050308164656) | 설치 절차 | 2005-02-15 「【重要】ゲームクライアントインストール手順」: ① CD에서 설치 ② 공식 사이트 업데이트 파일 실행. ②를 안 하면 자동 업데이트 불가 | evidence:manual |
| /bin/news.cgi?num=4 (20050308165225) | 업데이터 교체 | 2005-02-15 「【重要】アップデータ更新」: 2004-12-16 업데이트로 자동 업데이트용 업데이터가 바뀜 | evidence:manual |
| /bin/update.cgi?num=4 (20040604032641) | 업데이트 방식 | 2004-05-14: 첫 설치 후 다운로드 페이지의 업데이트 프로그램 설치, 이후 업데이트는 게임 기동 시 자동 | evidence:manual |
| /platform.html (20040114015521) | 시스템 요구사항 | Win2000/XP, P3 800MHz, 128MB, 1024×768 16bit, VRAM 32MB·DX8.1, ADSL 1.5M 이상(개발 중 예상치) | evidence:manual |
| /beginner.html (20040528151118, 20040610181908) | 시작 가이드 | 요구사항, 회원 등록(패키지의 「登録用ライセンスキー」+「チケットナンバー」), 설치→업데이터→플레이 순서 | evidence:manual |
| /qa_bn2.html (20040408063723) | FAQ | **글로벌 IP 주소 불필요**, ADSL 1.5M은 실측이 아니라 계약 대역 기준 | evidence:manual |
| /qa.html (20040114020757), /qa_bn.html (20040206101757) | FAQ | 네트워크 전용(오프라인 불가), 시간 24배속, 캐릭터 2명(같은 세션 불가), 로그오프 중에도 AI로 캐릭터 존속, 업데이트는 네트워크 패치 | evidence:manual |
| /bin/index.cgi (20040528152257 외 7개 digest, 최종 20050404052132) | 홈/서버 상태 | 서버 목록 **iserlohn / potoro / mercury** 표시. 2005-03-31 iserlohn 장애, 전 서버 임시 점검 공지 | evidence:manual |
| /ev041015.html (20041017221738) | 설명회 보고 | **1세션=서버 2대, 3세션 총 6대**. 데이터 이상 시 서버 전체를 즉시 내리는 설계, 지연 원인은 서버 프로그램 | evidence:manual |
| /ev041011.html (20041011140754) | 설명회 공지 | 2004-10-11 사용자 설명회 개최 공지 | evidence:manual |
| /registration.html (20040528153202) | 계정·과금 | 과금 체계(티켓/월정액), ソフトシティ 계정당 LOGH7 계정 3개, 계정 CGI는 `web.gineiden.com/cgi/gin7*.cgi` | evidence:manual |
| /release.html (20040429161841) | 패키지 정보 | 2004-05-14 발매, 품번 WR-04155-1, JAN 4988609011558, 구성(설치 가이드·플레이 가이드·CD 1장·PDF 매뉴얼·라이선스 키·β 티켓·피규어) | evidence:manual |
| /sv_end.html (20050416000557) | 종료 공지 | 2005-04-13부터 지원·점검 중단, 당분간 플레이 가능, 서버 정지일 추후 공지, GM·정치가 캐릭터 개방, 과금 환불 | evidence:manual |
| /apology.htm (20031204102029) | 장애 공지 | 2003-11-14~16 BOTHTEC·銀河英雄伝説·ストーンエイジ 사이트 서버 하드웨어 장애 | evidence:manual |
| web.gineiden.com/cgi/gin7newmember.cgi (20040529190953), gin7acdata.cgi (20040615200330), gin7delete.cgi (20040615200619), gin7upparam.cgi (20040615200938), gin7dateadd.cgi (20041225055243) | 계정 관리 CGI | 회원 등록·정보 확인·탈퇴·정보 변경·기간 추가 페이지. 폼 필드 구성은 계정 DB 설계 참고용(내용 미열람) | evidence:manual(존재) |
| web.gineiden.com/ (20040529190204), /index.htm, /info.html, /ginei7.html, /jutsu01~03.html, /bigpict/*, /picture/top_image.swf | 미러/구 페이지 | 2004-06 무렵 web 서브도메인 쪽 구 사이트 사본 | evidence:manual(존재) |
| help.gineiden.com/ (20010422001803), mail.gineiden.com/ (20010422002641) | 기타 | 2001년 캡처, 게임 이전 도메인 용도로 추정(무관) | evidence:guess |

접속 서버 호스트명·포트·방화벽 안내: 공식 사이트 아카이브 전체(FAQ·비기너 가이드·동작 환경·공지 목록)에서 **찾지 못함.** "포트/ポート/ファイアウォール/ルータ/TCP/UDP" 문자열 검색 결과 관련 문장 없음. evidence:manual(부재 확인)
→ 접속 정보는 클라이언트 파일(설정 파일·레지스트리·실행 파일 문자열)에서 찾아야 한다. 같은 MPS를 쓴 SBOL은 `SERVER.INI`에 ADDR/PORT를 둔다(prior-work.md 6.1). evidence:guess

### 2.2 공지 아카이브(대량, 일괄 텍스트화 권장)

| 경로 패턴 | 개수(CDX 고유 URL) | 내용 | 근거 |
|---|---|---|---|
| /bin/backview.cgi?ct=update&page=N&num=N | 33 | 업데이트(패치 노트) 본문. 2004-05~2005-02 | evidence:manual |
| /bin/backview.cgi?ct=mente&page=N&num=N | 374 | 점검·장애 공지. 서버명(iserlohn/ポトロ/マーキュリー), 로그인 서버 장애, 세션 재개 일정 | evidence:manual |
| /bin/backview.cgi?ct=news&page=N&num=N (및 `?num=N`) | 98 + 96 | 일반 공지(オリジナルキャラクター 추첨·해제, 서버 데이터 초기화, 이벤트) | evidence:manual |
| /bin/backnum.cgi?ct=mente/news/update&page=N | 27 | 위 공지들의 제목 목록 페이지 | evidence:manual |
| /bin/update.cgi?num=0..6, /bin/news.cgi?num=0..16, /bin/mente.cgi?num=0..8 | 수십(캡처 시점별 내용 다름) | 홈에 걸린 최신 공지. 2004-12~2005-08 캡처 다수 | evidence:manual |

패치 노트에서 확인한 주요 업데이트 일자(요약): 2004-05-14(업데이터 배포), 05-18, 05-20, 05-28, 06-11, 06-24, 07-22, 08-26, 12-16(업데이터 교체), 2005-01-20, 02-24, 03-17. evidence:manual

게임 규칙 관련으로 확인한 사실 예(모두 evidence:manual):
- 2004-06-11 패치: 기함 격침 시 경험치 감소가 계급별로 다름(皇帝/元帥 −50, 上級大将~准将 −30, 大佐~少佐 −10, 大尉~少尉 −5, 曹長~二等兵 −1).
- 2004-05-20 패치: 경험치 100마다 해당 파라미터 +1, 51 이상 파라미터는 고령일수록 감소 가능.
- 2004-06-24 패치: 1G일(게임 내 1일)마다 군수물자 생산, 오리지널 캐릭터 추첨 간격 12시간→3시간.

### 2.3 게임 규칙·콘텐츠 설명 페이지

| 경로(첫 캡처) | 내용 | 근거 |
|---|---|---|
| /gameinfo.html (20040528143026) | 게임 개요: MMO 시뮬레이션, 최대 2,000명, 세션 개념, 전략/전술 모드, 계급·직무 | evidence:manual |
| /strategy.html (20040115071339), /st_index.html (20040114092216) | 게임 내용 소개 목차 | evidence:manual |
| /st_char.html (20040115095030) | 원작 캐릭터와 게임 내 직무 예(ラインハルト=帝国軍最高司令官, ミッターマイヤー=宇宙艦隊司令長官, ヤン=艦隊司令官, シェーンコップ=要塞守備隊指揮官, トリューニヒト=最高評議会議長 등) | evidence:manual |
| /st_cmake.html (20040129020146) | 캐릭터 메이킹: ジェネレートキャラクター / オリジナルキャラクター | evidence:manual |
| /st_command.html (20040212032027) | 모든 명령은 「職務権限カード」로 실행, 기본 카드는 「個人」「艦長」 | evidence:manual |
| /st_job.html (20040327040220), /ref_job.html (20040528145528) | 직무·직무권한카드 전체 목록(皇宮·内閣·軍務省·統帥本部·宇宙艦隊司令部·憲兵本部 등) | evidence:manual |
| /st_pj.html (20040327043446), /ref_pj.html (20040528151032) | 부대 종류(단독 기함~함대 등) | evidence:manual |
| /st_ship.html (20040225211202), /ref_ship.html (20040528151613) | 함선 유닛(예: SS75型標準戦艦) | evidence:manual |
| /ref_tactics.html (20040528130125) | 전술 모드 명령 팔레트 | evidence:manual |
| /reference.html (20040528153802) | 레퍼런스 목차 | evidence:manual |
| /jutsu01.html (20031109020241), /jutsu02.html (20031109022202), /jutsu03.html (20031221210738) | 2003년 전술 모드 소개(실시간 명령·실행 시간) | evidence:manual |
| /update/01a~05f.html (2004-10~2005-04) | 업데이트 리뷰용 스크린샷 페이지 | evidence:manual |
| /screen.html, /screen_back.html, /screen_back2.html, /screen/*.html(40여 개), /sc1~6.html, /st1~4.html, /bigpict/*.html | 개발 중 스크린샷 | evidence:manual |
| /movie.html (20040401160431) | 스페셜 무비(WMV) 안내. 영상 파일 캡처는 CDX에 없음 | evidence:manual |
| /manual.html | (보유) 매뉴얼 PDF 안내 | evidence:manual |

### 2.4 약관·정책·기타

| 경로(첫 캡처) | 내용 | 근거 |
|---|---|---|
| /kiyaku.html (20040701042506), /rules.html (20040528153724), /rulessh.html (20040625203105) | 이용약관(BOTHTEC 운영 서비스 규정) | evidence:manual |
| /beta_kiyaku.html (20040129183324), /betatestkiyaku.html (20040129020032) | 베타 테스트 약관 | evidence:manual |
| /betatest1.html, /betatest3.html, /betatestform.html (2004-01-29) | CBT 모집(2004-01-23~31 모집, 2~3월 실시) | evidence:manual |
| /beta_start.html (20040401151804), /bin/beta_top.cgi, /bin/beta_backnum.cgi | CBT 로그인 페이지(ID/PASSWORD) | evidence:manual |
| /policy.htm, /policy.html | 사이트 정책 | evidence:manual |
| /contact.html, /conthx.html, /qa_form.html | 문의 폼 | evidence:manual |
| /gamemaster.html (20040401154550) | GM 모집(업무: 서버 감시, 인게임 대응 등). 근무지 세타가야 | evidence:manual |
| /index.htm (20031118201656), /info.html, /ginei7.html (20031109020742), /?NL000254-125, /?NL000254-130 | 발표기(2003-11~2004-04) 톱 페이지 | evidence:manual |
| / (20010302104614), /robots.txt (20011207010534) | 2001년 캡처, 게임 이전 다른 사이트로 추정 | evidence:guess |
| /picture*/…, /update/*.jpg (2013 캡처는 전부 404) | 이미지 | — |

---

## 3. 관련 도메인

| URL(캡처) | 내용 | 근거 |
|---|---|---|
| http://www.bothtec.co.jp/image/banner_gin7.gif (20031027103331), /image/gin7_pk3.jpg (20050823170555) | BOTHTEC 사이트의 LOGH7 배너·패키지 이미지 | evidence:web |
| http://www.bothtec.co.jp/mobile/v/ginei/ginei.htm | 공식 사이트에서 링크된 모바일 페이지. **CDX 재조회 실패(IA 일시 오프라인) — 재시도 필요** | evidence:manual(링크 존재) |
| http://www.bothtec.co.jp/download/ 이하 | 다른 BOTHTEC 타이틀 패치(gin3upd.exe, gin6_up120.exe 등)는 있으나 **LOGH7 패치는 없음** | evidence:web |
| http://www.multiterm.co.jp/mps/ (20011213034615) 외 `mps/function/*.html` | MPS 구조·기능 설명(prior-work.md 5절) | evidence:web |
| http://www.multiterm.co.jp/mps_img/ginei_01.gif (20031222222005) | MultiTerm 측 LOGH7 관련 이미지 | evidence:web |
| http://www.microvision.co.jp/top/images/ginga7.gif (20050831123355), /products/images/pd_ginga7.gif (20070327141319) | MicroVision 사이트의 LOGH7 제품 이미지(개발 관여 정황) | evidence:web |
| 넷마블 서브도메인(`ginga/gin/logh/eunha/ginei.netmarble.net`) | CDX 결과 없음 → **찾지 못함** | — |

---

## 4. 다음 단계 제안(메인 에이전트 판단용)
1. `G7UPD040514.exe` 원본(id_) 확보 여부를 사용자에게 확인(약 10.6MB). 설치 CD 대비 2004-05-14 시점 차분 파일로 추정. evidence:guess
2. `backview.cgi` 약 500건을 일괄 텍스트화해 패치 노트·점검 공지를 시간순 DB로 정리(서버 동작 재현 시 규칙 근거).
3. 클라이언트 설정 파일에서 접속 호스트·포트를 찾고, MPS 로비/릴레이 구조 가설(prior-work.md 5·6절)과 대조.
