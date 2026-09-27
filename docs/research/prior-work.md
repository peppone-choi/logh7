# 선행 커뮤니티 작업 조사 — 『銀河英雄伝説VII』(LOGH7)

- 작성자: 최병호
- 작성일: 2026-09-27
- 모든 URL의 확인 날짜: 2026-09-27 (별도 표기가 없으면 동일)
- 근거 태그: `evidence:manual` = 공식 사이트(gineiden.com 아카이브)·매뉴얼·공식 보도자료, `evidence:web` = 제3자 자료, `evidence:guess` = 추정

---

## 0. 한눈에 보기

| 항목 | 결론 |
|---|---|
| LOGH7 전용 팬 서버/서버 에뮬레이터 | **찾지 못함** (일·영·한 검색, GitHub/GitLab/Internet Archive 검색 모두 결과 없음) |
| LOGH7 프로토콜 분석 공개 자료 | **찾지 못함** |
| 가장 가까운 선행 사례 | 같은 통신 미들웨어 **MultiTerm MPS(MassplayerSystem)** 를 쓴 『首都高バトルOnline』(SBOL) 서버 에뮬레이터·MPS 복호화 도구(GitHub 공개) |
| 보존 자료 | Internet Archive `logh-7`(클라이언트 디스크 이미지), `gin7manual`(매뉴얼 PDF), Wayback의 공식 사이트·패치 파일 `G7UPD040514.exe` |
| 한국 관련 | 넷마블 「은하영웅전설 온라인」 CBT(2004-05-28~06-07 또는 06-05)만 확인, 상용 서비스 확인 안 됨 |
| 종료 경위 | 라이선스(らいとすたっふ) 계약 해지 → 2005-04-13 지원 중단, 서버는 "당분간 플레이 가능"으로 공지 후 2005년 5월경 종료 |

---

## 1. 게임 기본 사실

| 사실 | 근거 | 출처 |
|---|---|---|
| 2004-05-14 패키지 발매와 동시에 오픈 베타 성격의 서비스 시작. 품번 WR-04155-1, 월 과금 1,500엔(세금 포함 1,575엔) 예정 | evidence:manual | http://www.gineiden.com/release.html (Wayback 20040429161841) |
| 실제 과금 체계: 30일 티켓 1,890엔, 90일 4,725엔, 또는 ソフトシティ 월회비 315엔 + 게임 월 1,260엔. ソフトシティ 1계정당 LOGH7 계정 3개 | evidence:manual | http://www.gineiden.com/registration.html (Wayback 20040528153202) |
| 동작 환경: Windows 2000/XP, Pentium III 800MHz, RAM 128MB, 1024×768 16bit, VRAM 32MB DirectX 8.1, ADSL 1.5M 이상 | evidence:manual | http://www.gineiden.com/platform.html (Wayback 20040114015521), https://www.4gamer.net/games/010/G001079/ |
| 저작권 표기에 BOTHTEC와 함께 **MicroVision**(개발 협력사로 추정)이 병기됨 | evidence:manual(표기) / evidence:guess(역할) | Wayback 공식 사이트 각 페이지 하단, 예: http://www.gineiden.com/bin/index.cgi (20040528152257) |
| 공식 사이트 홈에 MultiTerm(MPS) 로고와 링크가 있음 | evidence:manual | 위 index.cgi HTML의 `logo_mps.gif`, `http://www.multiterm.co.jp/` 링크 |
| 게임 서버 구성: 1세션=고성능 서버 2대, 3세션 합계 6대 (2004-10 설명회 보고) | evidence:manual | http://www.gineiden.com/ev041015.html (Wayback 20041017221738) |
| 세션(월드) 이름: iserlohn / potoro / mercury, 별도 "ログインサーバー" 존재 | evidence:manual | index.cgi(20040528152257, 20050404052132), 점검 공지 목록 `bin/backnum.cgi?ct=mente` |
| 첫 설치 후 공식 업데이터(G7UPD040514.exe, 10.4MB) 설치 필요, 이후 업데이트는 게임 기동 시 자동 | evidence:manual | http://www.gineiden.com/update.html (20040528152716), `bin/update.cgi?num=4` (20040604032641) |
| 2004-12-16 업데이트로 **자동 업데이터 자체가 교체**됨. 이후 신규 설치자는 G7UPD050125.exe(11.4MB)를 받아야 자동 업데이트 가능 | evidence:manual | http://www.gineiden.com/update.html (20050308143828), `bin/news.cgi?num=4` (20050308165225) |
| "글로벌 IP 주소는 필요 없다"는 공식 FAQ 답변 → 클라이언트가 서버로만 접속하는 구조로 추정 | evidence:manual(답변) / evidence:guess(구조 해석) | http://www.gineiden.com/qa_bn2.html (20040408063723) |
| 오프라인 단독 플레이 불가(네트워크 전용) | evidence:manual | http://www.gineiden.com/qa.html (20040114020757) |
| 게임 내 시간은 실시간의 24배 | evidence:manual | 같은 qa.html |

---

## 2. 선행 커뮤니티 작업 검색 결과

### 2.1 LOGH7 직접 대상 (팬 서버·에뮬레이터·프로토콜 분석)

**결과: 찾지 못함.**

시도한 검색어(일본어):
- `銀河英雄伝説VII オンライン ボーステック サービス終了`
- `"銀英伝VII" OR "銀河英雄伝説VII" プレイ日記 ブログ 2004`
- `銀河英雄伝説VII エミュレータ サーバー 解析 個人`
- `銀英伝VII 2ch スレ ボーステック 銀河英雄伝説7 オンライン 攻略 wiki`
- `"銀河英雄伝説VII" OR "銀英伝7" 鯖 エミュ 解析 クライアント 保存 2020..2026`
- `銀河英雄伝説VII プレイ動画 youtube OR niconico ボーステック 2004 オンライン`

시도한 검색어(영어):
- `"Legend of Galactic Heroes VII" online game Bothtec 2004 server`
- `"Legend of Galactic Heroes VII" OR "LOGH VII" OR "LOGH7" private server emulator github`
- `"Ginga Eiyuu Densetsu VII" online`
- `gineipaedia "Legend of Galactic Heroes VII"`

시도한 검색어(한국어):
- `은하영웅전설 7 온라인 보스텍 2004`
- `은하영웅전설 온라인 넷마블 CBT 후기 은영전7`
- `은하영웅전설 7 프리서버 OR 에뮬 OR 사설서버 보스텍 온라인 복원`

GitHub 저장소 검색(`gh search repos`): `gineiden`, `LOGH7`, `logh vii`, `galactic heroes online`, `ginei7`, `gin7`, `銀河英雄伝説`, `ginga eiyuu densetsu`, `legend of galactic heroes`, `bothtec`
- 관련 결과 없음. 이름이 겹치는 무관 저장소만 있음(예: FC판 銀河英雄伝説 시뮬레이터 `taotao54321/LoghTravel` 등, 2008년 반다이남코판 모드툴 `derplayer/LoGHTools`). evidence:web
- `peppone-choi/logh7`(2026-09-27 갱신)이 검색되나, 로컬 Windows 사용자명과 같아 **사용자 본인 저장소로 추정**되어 선행 작업에서 제외. evidence:guess
- GitLab: 웹 검색 결과에 LOGH7 관련 GitLab 프로젝트 없음(별도 GitLab API 검색은 하지 않음). evidence:web

Internet Archive 검색(`advancedsearch.php`): `gineiden`, `"galactic heroes VII"`, `logh-7`, `gin7`, `bothtec`, `subject:(ginga eiyuu densetsu)`, `title:(ginga eiyuu densetsu)`, `G7UPD`
- LOGH7 관련 아이템은 `logh-7`, `gin7manual` 두 개뿐. 패치 파일 아이템은 없음. evidence:web
- 일본어 제목 쿼리(`title:(銀河英雄伝説)`)는 IA 쪽 오류(QUERY_NOT_READY)로 실패.

### 2.2 보존 자료

| 자료 | 내용 | 근거 | URL |
|---|---|---|---|
| IA `logh-7` | 클라이언트 CD 이미지(Logh7.bin/cue), 2023-11-26 업로드, "서버가 없어 보존 목적" 설명 | evidence:web | https://archive.org/details/logh-7 |
| IA `gin7manual` | 공식 매뉴얼 PDF(101쪽) | evidence:web | https://archive.org/details/gin7manual |
| Wayback `G7UPD040514.exe` | 2004-05-14판 공식 업데이터. 2004-06-25 캡처, 10,594,762바이트, `application/octet-stream`. **이번 조사에서는 내려받지 않음** | evidence:manual(공식 배포물) | https://web.archive.org/web/20040625193252/http://gineiden.com:80/G7UPD040514.exe |
| Wayback `G7UPD050125.exe` | 2005-01-25판 업데이터(업데이터 교체 포함). **CDX에 캡처 없음 → 찾지 못함** | — | (검색: CDX `url=gineiden.com/G7UPD*`) |
| IA `gineipaedia` | 영어 팬 위키 Gineipaedia 전체 아카이브(2025-05-12). LOGH7 문서는 원래부터 없음(빨간 링크) | evidence:web | https://archive.org/details/gineipaedia , https://gineipaedia.com/wiki/Legend_of_Galactic_Heroes_(BOTHTEC_game) |

### 2.3 플레이 기록·공략·위키·회고

| 자료 | 요약 | 근거 | URL |
|---|---|---|---|
| 銀河英雄伝説シリーズ PCゲーム板@ウィキ 「銀河英雄伝説7」 | 2009-01-29 최종 수정. 반쯤 미완성 상태로 공개되어 부담이 컸고 권리 문제로 개발 도중 버려졌다는 짧은 평. 상세 공략 없음 | evidence:web | https://w.atwiki.jp/gineipc/pages/37.html |
| ゲヱム日々是徒然(하테나 블로그) | 2005-04-17 종료 소식 논평. 작성자는 직접 플레이하지 않음 | evidence:web | https://unfreeman.hatenablog.com/entry/20050417/1213012010 |
| Melog | 2005-04 종료 소식 블로그 | evidence:web | https://melog.info/archives/2005/04/16222304.html |
| 루리웹 「은하영웅전설 7 개발하다 망한 MMORPG 이야기 하나」 | 2022-10-13 게시. 한국 CBT 대기 팬의 회고. 연도·출시 여부 등 사실 오류가 섞여 있어 1차 근거로 쓰기 어려움 | evidence:web | https://bbs.ruliweb.com/community/board/300781/read/58901153 |
| 나무위키 「은하영웅전설 7」 | 개발·CBT 일정·종료 경위 요약, 1차 CBT 체험기 링크 언급 | evidence:web | https://namu.wiki/w/%EC%9D%80%ED%95%98%EC%98%81%EC%9B%85%EC%A0%84%EC%84%A4%207 |
| 4Gamer 기사 목록 | 2003-09 발표회부터 2005-04 종료까지 기사 11건, TGS2003 영상·플레이 영상 | evidence:web | https://www.4gamer.net/games/010/G001079/ |
| Game Watch 스크린샷 | 2004-02 개발 중 스크린샷 | evidence:web | https://game.watch.impress.co.jp/docs/20040216/ginei02.htm |
| 공식 사이트 스크린샷·리뷰 | `screen/*.html`, `bigpict/*.html`, `update/01a~05f.html`(업데이트 리뷰 이미지 페이지) | evidence:manual | wayback-inventory.md 참조 |
| 플레이 동영상 | YouTube/니코동에서 LOGH7 플레이 영상은 **찾지 못함**(공식 WMV 무비 페이지 `movie.html`만 확인, 영상 파일 자체의 캡처는 CDX에 없음) | — | 검색어는 2.1 참조 |
| NeoGAF 스레드 "Legend of Galactic Heroes VII (massive scale space RTS)" | 존재는 확인했으나 403으로 본문 확인 못함 | evidence:web | https://www.neogaf.com/threads/legend-of-galactic-heroes-vii-massive-scale-space-rts.371430/ |

### 2.4 개발자 인터뷰

- 개발자 개인 인터뷰: **찾지 못함.** (검색어: 위 2.1 일본어 검색어 전부, `ボーステック 八巻 インタビュー` 계열은 4Gamer/GameWatch 목록 검토로 대체)
- 가장 가까운 1차 자료: 2004-10-11 사용자 설명회 보고문(BOTHTEC 대표 八巻龍一 명의). 서버 구성(3세션×2대), 서버 다운 원인 설명(데이터 이상 시 전체를 바로 내리는 설계), 지연 원인은 서버 프로그램 쪽이라는 설명, 장기 베타 상태에 대한 사과. evidence:manual — http://www.gineiden.com/ev041015.html (Wayback 20041017221738)
- 2003-09-24 제품 발표회 기사: https://game.watch.impress.co.jp/docs/20030924/ginga.htm , https://dengekionline.com/data/news/2003/9/24/0ea846f4b20344fc87b141c10121e0b3.html (evidence:web)

---

## 3. 한국 서비스(넷마블 「은하영웅전설 온라인」)

| 사실 | 근거 | 출처 |
|---|---|---|
| 2002-10 넷마블·보스텍 온라인게임 공동 개발 보도 | evidence:web | https://m.etnews.com/200210290076 |
| 2004-01-28 넷마블이 국내 서비스를 독자 추진, 2월 CBT 예정 보도 | evidence:web | https://www.gamemeca.com/game.php?rts=gmview&gmid=g0001192 |
| 2004-05-25 보도자료: CBT 2004-05-28~06-05, 테스터 999명 | evidence:web | https://www.newswire.co.kr/newsRead.php?no=1739 |
| 나무위키는 CBT 기간을 05-28~06-07로 기재(보도자료와 이틀 차이) | evidence:web | 나무위키 「은하영웅전설 7」 |
| 보도에 쓰인 한국어 용어: 은하제국, 자유행성동맹, 라인하르트, 양 웬리(기자 문장 기준, 클라이언트 표기인지는 확인 필요) | evidence:web | https://star.ohmynews.com/NWS_Web/OhmyStar/at_pg.aspx?CNTN_CD=A0000188848 |
| 한국 상용 서비스 개시 증거 없음 | evidence:web | 나무위키, 게임메카 |
| 2005 종료 기사: 무단으로 중국·한국 업체에 권리를 준 것이 해지 사유 중 하나로 언급 | evidence:web | https://game.watch.impress.co.jp/docs/20050414/ginga.htm |
| 2003-05-27 중국 盛大(Shanda)가 BOTHTEC에 출자하고 LOGH7 중국 독점 라이선스 확보 보도 | evidence:web | https://dengekionline.com/data/news/2003/5/27/b8da29993f08dc3f714fec076d2ce30f.html |

→ **한국어판 클라이언트가 CBT용으로 존재했을 가능성**이 있으나, 파일이나 스크린샷 보존본은 **찾지 못함**(넷마블 서브도메인 `ginga/gin/logh/eunha/ginei.netmarble.net` CDX 조회 결과 없음). 한국어화 용어 참고용으로 가치가 크므로 추가 수소문 대상. evidence:guess

---

## 4. 종료 경위

| 시점 | 사건 | 근거 | 출처 |
|---|---|---|---|
| 2005-03-22 | 판권사 らいとすたっふ가 라이선스 계약 해지 통보(무단 상표 등록 시도, 해외 업체 무단 허락 등 계약 위반 사유) | evidence:web | https://game.watch.impress.co.jp/docs/20050414/ginga.htm , https://ja.wikipedia.org/wiki/銀河英雄伝説_(ゲーム) |
| 2005-04-13 | 사용자 지원·정기 점검 중단. 게임은 "당분간 플레이 가능", 서버 정지일은 별도 공지 예정. GM·정치가 캐릭터를 유저에게 개방, 과금액 환불 | evidence:manual | http://www.gineiden.com/sv_end.html (Wayback 20050416000557) |
| 2005-04-14 | 언론 보도 | evidence:web | https://www.4gamer.net/games/010/G001079/20050414184946/ , https://dengekionline.com/data/news/2005/4/14/c3aeb4c68662ca43e661b052026561d7.html |
| 2005-05(추정) | 서버 종료 | evidence:web | 나무위키, 넷게임 북마크 https://netgamebm.com/calendars/view/50 |
| 2005 | BOTHTEC 파산. 직원들이 먼저 D4エンタープライズ를 세워 레트로 사업(Project EGG) 이어감 | evidence:web | https://note.com/kawag2011/n/n3b7f614824e7 |
| 2025-07-18 | D4エンタープライズ가 『銀河英雄伝説Ultimate Collection』(I~V 수록, 2026년 내 발매, 19,800엔) 발표. **VII은 수록 목록에 없음** | evidence:web | https://prtimes.jp/main/html/rd/p/000000424.000069696.html , https://ascii.jp/elem/000/004/302/4302381/ , https://www.gamespark.jp/article/2025/07/18/155156.html |

참고: 종료 공지 당시 서버 정지일 공지 페이지는 Wayback에서 **찾지 못함**(`sv_end.html` 이후 캡처 없음, `bin/news.cgi` 2005-08 캡처는 모두 같은 digest로 내용 미확인).

---

## 5. 통신 미들웨어: MultiTerm MPS(MassplayerSystem)

LOGH7은 MultiTerm의 네트워크 미들웨어 MPS를 채택한 것으로 확인됨.

| 사실 | 근거 | 출처 |
|---|---|---|
| 일본어 위키백과 「マルチターム」의 MPS 채택 타이틀(서비스 종료) 목록에 『銀河英雄伝説VII』 포함 | evidence:web | https://ja.wikipedia.org/wiki/マルチターム |
| 공식 사이트 하단에 MPS 로고와 multiterm.co.jp 링크 | evidence:manual | Wayback index.cgi(20040528152257) |
| MultiTerm의 2003-12 MPS 전시용 이미지 `mps_img/ginei_01.gif` 캡처 존재 | evidence:web | https://web.archive.org/web/20031222222005/http://www.multiterm.co.jp:80/mps_img/ginei_01.gif |
| MultiTerm은 2006-12 NHN Japan 자회사화, 2007-09-01 흡수합병으로 소멸 | evidence:web | https://ja.wikipedia.org/wiki/マルチターム , https://game.watch.impress.co.jp/docs/20061226/nhn.htm |

MPS 제품 설명(아카이브된 MultiTerm 제품 페이지 요약, 모두 evidence:web, LOGH7 적용 여부는 evidence:guess):
- 구성 요소: **로비 서버**(로그인, 게임 서버 선정), **릴레이 서버**(클라이언트↔게임 서버 중계, 송수신·암호화·세션 관리 담당), **시스템 컨트롤러**(서버 호스트 ID 할당·서버 간 접속 관리), **게임 서버 라이브러리**, 클라이언트 라이브러리(Windows 95~XP, PS2, GC). 서버는 Red Hat Linux 7.2J/AS3 또는 Windows 2000/XP.
  - https://web.archive.org/web/20050313195727/http://www.multiterm.co.jp:80/mps/function/enterprise.html
- 기능: 통신 자동 암호화, 생존 확인(keepalive, 다중 로그인 판정), 서버 간 이동 시 재접속 없음, TCP/HTTP 지원(에디션별 차이), 프로토콜 스키마 자동 생성 도구, **클라이언트 자동 업데이트 시스템(업데이트 서버·업데이트 클라이언트·등록 도구)**, DB 인터페이스(MySQL/PostgreSQL/Oracle/Sybase).
  - https://web.archive.org/web/20060823224452/http://www.multiterm.co.jp:80/mps/function/detail.html
  - https://web.archive.org/web/20050313200102/http://www.multiterm.co.jp:80/mps/function/index.html
  - https://web.archive.org/web/20011213034615/http://www.multiterm.co.jp:80/mps/
- 수탁 실적 페이지에 "MMO シミュレーションゲーム(서버 프로그래밍, 네트워크 구성 설계·운영 툴)"이 있으나 제목은 미기재 → LOGH7일 가능성. evidence:guess — https://web.archive.org/web/20050313200631/http://www.multiterm.co.jp:80/game/result.html

→ 시사점(evidence:guess): LOGH7 클라이언트의 로그인·세션 이동·자동 업데이트·암호화는 MPS 공통 계층일 가능성이 높다. 서버 재구현은 "MPS 계층(프레이밍·암호화·keepalive·로비/릴레이 흐름)"과 "LOGH7 게임 로직"을 분리해 접근하는 것이 유리하다.

---

## 6. 유사 사례(참고)

### 6.1 같은 MPS를 쓴 『首都高バトルOnline』(SBOL, 元気, 2003~2005)

| 자료 | 요약 | 근거 | URL |
|---|---|---|---|
| tofuman0/SBOL-Server | 클라이언트만으로 프로토콜을 역분석해 만든 서버 환경(Battle Server + DB Server + Blowfish 라이브러리 서브모듈). 2024-07 생성, 라이선스 표기 없음 | evidence:web | https://github.com/tofuman0/SBOL-Server |
| tofuman0/SBOL-Battle-Server / SBOL-DB-Server / BlowFishSBOL | 위 서브모듈. Battle Server 2025-06 갱신 | evidence:web | https://github.com/tofuman0/SBOL-Battle-Server , https://github.com/tofuman0/SBOL-DB-Server , https://github.com/tofuman0/BlowFishSBOL |
| tofuman0/MPS-Blowfish | "MPS 라이브러리를 쓰는 게임의 파일을 (복)암호화하는 CLI". 2026-03-25 생성. 소스 요약: 키를 인자로 받아 Blowfish로 처리, 복호화 결과 앞 8바이트가 [원본 크기 4바이트][체크섬 4바이트], 본문은 8바이트 단위 패딩 | evidence:web | https://github.com/tofuman0/MPS-Blowfish |
| sortaloc/SBOL | 2019년 초기 서버(계정 생성·차량 선택·주행까지) | evidence:web | https://github.com/sortaloc/SBOL |
| SBOL 프로젝트 위키 Protocol 문서(2019-05-13 수정, Wayback) | MPS는 **빅엔디언**을 사용. 패킷 헤더: 클라이언트→서버 [크기 u16][타입 u16][데이터], 서버→클라이언트 [크기 u16][타입 u16][서브타입 u16][데이터]. 크기 값은 전체 길이−2. 타입 0x0000=Keep Alive, 0x0100=인증, 0x0A00=Ping 등 | evidence:web | https://web.archive.org/web/20200814080543/http://wiki.sb-online.net/index.php?title=Protocol&printable=yes |
| SBOL 클라이언트의 `SERVER.INI` | `[SERVERxx]` 섹션에 TYPE/ADDR/PORT/SIDE 키로 접속 서버 지정(SBOL 기본 포트 47701) | evidence:web | tofuman0/SBOL-Server README |
| sb-online.net | 현재는 온라인 서비스 중단, 오프라인 플레이용 배포만 | evidence:web | https://sb-online.net/ |
| 한국어/일본어 기사 | 해외 팬 사설 서버 소개 | evidence:web | https://socom.yokohama/games/pc-games/28292/ |
| RaGEZONE 개발 스레드 | 존재 확인, 403으로 본문 미확인 | evidence:web | https://forum.ragezone.com/threads/sbol-%E9%A6%96%E9%83%BD%E9%AB%98%E3%83%90%E3%83%88%E3%83%ABonline-shutoko-battle-online-server-development.1116576/ |

→ LOGH7 적용 가설(evidence:guess, 메인 에이전트의 바이너리 분석으로 검증 필요):
1. LOGH7 클라이언트에도 SBOL의 `SERVER.INI`와 비슷한 접속 서버 설정(파일 또는 레지스트리)이 있을 수 있다.
2. MPS 프레이밍(빅엔디언 u16 크기/타입)과 Blowfish 기반 암호화가 LOGH7에도 쓰였을 수 있다. 다만 SBOL(MPS 초기판)과 LOGH7(2004, Enterprise 계열 추정)은 에디션·버전이 다를 수 있다.
3. 자동 업데이트는 MPS 업데이트 서버 방식일 수 있어, 대체 서버에서 업데이트 단계를 흉내 내거나 우회해야 할 수 있다.

### 6.2 MPS를 쓴 다른 타이틀(참고)
- ファンタジーアース ゼロ(Square Enix), ときめきメモリアル ONLINE(Konami, 2007-07-31 종료), 首都高バトルOnline 등. evidence:web — https://ja.wikipedia.org/wiki/マルチターム , https://ameblo.jp/soralog/entry-10010754161.html , https://game.watch.impress.co.jp/docs/20050706/multi.htm
- ときめきメモリアルONLINE 서버 에뮬레이터: **찾지 못함**(검색어: `ときめきメモリアルオンライン サーバーエミュレータ 有志 復活 プライベートサーバー`).

### 6.3 동시대·기타 서버 에뮬레이터(구조 참고용)

| 프로젝트 | 대상 | 언어/라이선스 | URL | 근거 |
|---|---|---|---|---|
| newserv | Phantasy Star Online(세가, 일본 PC/콘솔 온라인) 서버·프록시·역분석 도구 | C++ / MIT | https://github.com/fuzziqersoftware/newserv | evidence:web |
| SWGEmu Core3 | Star Wars Galaxies | Lua·C++ / AGPL-3.0 | https://github.com/swgemu/Core3 | evidence:web |
| MHServerEmu | Marvel Heroes | C# / AGPL-3.0 | https://github.com/Crypto137/MHServerEmu | evidence:web |

BOTHTEC의 다른 온라인 타이틀: 『銀河英雄伝説VS』(2003-03-27 발매, 발매일부터 1년 한정 온라인 대전 서비스), 『ストーンエイジ』 운영. 이들의 서버 에뮬레이터는 **찾지 못함**. evidence:web — https://www.4gamer.net/games/006/G000666/ , http://www.gineiden.com/gamemaster.html (Wayback 20040401154550, evidence:manual)

---

## 7. 법적·권리 맥락 메모(조사 범위 밖, 참고만)
- 원작 판권 관리사는 らいとすたっふ, 2005년 BOTHTEC와의 계약 해지 이력이 있음(위 4절). evidence:web
- BOTHTEC은 소멸(2005 파산), 후신 격인 D4エンタープライズ의 2026 컬렉션에도 VII은 없음. evidence:web
- 개인 대체 서버·한국어화의 권리 검토는 별도 필요(본 문서는 사실 조사만 함). evidence:guess
