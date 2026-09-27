# 자택 서버 공개용 TCP 노출 방식 비교

- 작성자: 최병호
- 작성일: 2026-09-27
- 모든 URL 확인 날짜: 2026-09-27
- 근거 태그: `evidence:manual`(LOGH7 공식 사이트), `evidence:web`(각 서비스 공식 문서 등 제3자 자료), `evidence:guess`(추정)

---

## 0. 전제: LOGH7 클라이언트 쪽 조건

| 조건 | 근거 |
|---|---|
| 공식 FAQ: 플레이어 쪽에 글로벌 IP 주소가 필요 없다 → 클라이언트가 서버로 나가는 연결만 쓰는 구조로 보임 | evidence:manual (http://www.gineiden.com/qa_bn2.html, Wayback 20040408063723) / 해석은 evidence:guess |
| 통신 미들웨어 MPS는 TCP·HTTP를 지원하고, 로비 서버→릴레이 서버→게임 서버 구조, 자동 업데이트 서버를 둘 수 있음 | evidence:web (prior-work.md 5절) |
| 따라서 공개해야 할 포트는 **TCP 여러 개**(로비/로그인, 릴레이·게임, 업데이트)일 가능성이 있고, UDP 필요 여부는 미확인 | evidence:guess |
| 클라이언트가 접속 주소를 설정 파일에서 읽는다면 **고정 호스트명·고정 포트**가 있는 방식이 유리(같은 MPS 게임 SBOL은 `SERVER.INI`의 ADDR/PORT 사용) | evidence:web(SBOL) / evidence:guess(LOGH7) |
| 서버가 클라이언트에게 "다음 서버 주소"(로비→게임 서버)를 내려주는 구조라면, 서버가 알려주는 주소도 외부에서 닿는 주소(터널 주소)여야 함 | evidence:guess |

---

## 1. 비교 요약

| 방식 | 플레이어 쪽 추가 설치 | 임의 TCP | 고정 주소/포트 | 비용(2026-09-27 기준) | LOGH7 적합도 |
|---|---|---|---|---|---|
| ngrok TCP | 없음 | 가능 | 무료는 매번 무작위, 유료는 예약 주소 | Free $0(카드 등록·신원 확인 필요), Hobbyist $10/월, Pay-as-you-go $20/월+사용량 | 중 (예약 주소 필요, 5분 유휴 끊김 주의) |
| Cloudflare Tunnel | **있음**(cloudflared 또는 WARP) | 클라이언트 에이전트 경유 시 가능 | 호스트명 고정 | Tunnel 자체 무료, 공개 임의 TCP(Spectrum)는 **Enterprise+유료 애드온** | 낮음 (공개용), 지인용으로만 |
| Tailscale Funnel | 없음 | **TLS+SNI만 사실상 가능**, 평문 TCP 불가 | 443/8443/10000 고정 | Funnel은 전 플랜 제공(KB 기준) | 매우 낮음 |
| Tailscale(일반 tailnet) | **있음**(Tailscale) | 가능 | 100.x IP/MagicDNS 고정 | Personal 무료(사용자 6명까지, 기기 무제한) | 지인용으로 높음 |
| playit.gg | 없음 | 가능(custom TCP/UDP) | 할당 주소 고정 | 무료(포트 4개, Global Anycast), Premium $3/월(포트 16개, 지역 터널) | **높음** |
| 공유기 포트포워딩 | 없음 | 가능 | 공인 IP(+DDNS) | 무료(회선 조건 충족 시) | 높음(단, 보안·ISP 조건) |

---

## 2. 방식별 상세

### 2.1 ngrok TCP 터널

| 사실 | 근거 | 출처 |
|---|---|---|
| 요금제: Free $0 / Hobbyist $10/월 / Pay-as-you-go $20/월+사용량 | evidence:web | https://ngrok.com/pricing |
| Free도 TCP 가능하나 **유효한 결제 수단 등록(카드 확인) 필요**, 대시보드에서 신원 확인 필요 | evidence:web | https://ngrok.com/pricing , https://ngrok.com/docs/universal-gateway/tcp/ |
| 예약 TCP 주소: Free 0개, Hobbyist 1개, Pay-as-you-go 100개 | evidence:web | https://ngrok.com/pricing |
| 데이터 전송: Free 1 GB, Hobbyist 5 GB, Pay-as-you-go 5 GB 포함(초과 과금) | evidence:web | https://ngrok.com/pricing |
| 온라인 엔드포인트: Free·Hobbyist 3개, Pay-as-you-go 무제한 | evidence:web | https://ngrok.com/pricing |
| 주소 형식 `tcp://1.tcp.ngrok.io:12345`처럼 무작위 할당. 공개 엔드포인트의 호스트명·포트를 직접 고를 수 없음(고정하려면 TCP Address 예약) | evidence:web | https://ngrok.com/docs/universal-gateway/tcp/ |
| **양방향 5분 유휴 타임아웃**, 트래픽 인스펙터 미지원, 인증은 IP 제한·mTLS 정도 | evidence:web | https://ngrok.com/docs/universal-gateway/tcp/ |

LOGH7 관점(evidence:guess):
- 포트가 여러 개면 엔드포인트 3개 제한과 예약 주소 1개(Hobbyist) 제한에 걸릴 수 있다.
- MPS에는 생존 확인(keepalive) 기능이 있으므로 5분 유휴 끊김은 대개 문제없겠지만, 전략 화면 대기 중 트래픽이 없으면 끊길 위험이 있어 확인 필요.
- 무작위 포트를 쓰면 매번 클라이언트 설정을 바꿔야 한다.

### 2.2 Cloudflare Tunnel / Spectrum

| 사실 | 근거 | 출처 |
|---|---|---|
| HTTP가 아닌 TCP 서비스(SSH 등)를 Tunnel로 쓸 때 **접속하는 쪽 PC에 cloudflared 설치 필요**(`cloudflared access` 계열) | evidence:web | https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/use-cases/ssh/ssh-cloudflared-authentication/ |
| 공개 TCP/UDP로 사설 원본을 노출하려면 Spectrum 사용을 안내 | evidence:web | https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/use-cases/ |
| Spectrum은 유료 플랜용이며 "**Custom TCP/UDP 애플리케이션은 Enterprise 플랜 + Spectrum 유료 애드온 필요**" | evidence:web | https://developers.cloudflare.com/spectrum/ |
| Pro/Business에서 허용되는 프로토콜 목록 문서(`/spectrum/reference/protocols-per-plan/`)는 조회 시 404 → **확인 필요** | — | https://developers.cloudflare.com/spectrum/reference/protocols-per-plan/ |

LOGH7 관점(evidence:guess): 일반 공개용으로는 부적합. 지인 몇 명이라면 각자 `cloudflared access tcp`로 로컬 포트를 열고 클라이언트를 `127.0.0.1`로 향하게 하는 방식은 가능하나, 서버가 "다음 서버 주소"를 내려주는 구조라면 포트별로 모두 매핑해야 해서 번거롭다.

### 2.3 Tailscale Funnel / 일반 Tailscale

| 사실 | 근거 | 출처 |
|---|---|---|
| Funnel은 **443, 8443, 10000 포트만** 수신 가능 | evidence:web | https://tailscale.com/kb/1223/funnel |
| 요구: Tailscale v1.38.3+, MagicDNS, HTTPS 인증서, 정책 파일의 funnel 속성. "모든 플랜에서 사용 가능". 대역폭 제한(수치 비공개) 있음 | evidence:web | https://tailscale.com/kb/1223/funnel |
| CLI에 `--tcp`(원시 TCP 포워더), `--tls-terminated-tcp` 옵션 존재 | evidence:web | https://tailscale.com/kb/1311/tailscale-funnel |
| 그러나 Funnel 입구는 **TLS의 SNI 이름으로 목적지를 가림** → TLS가 아닌 평문 TCP는 지원되지 않으며, 이를 요청하는 기능 요청 이슈 #14240이 **열린 상태**(최종 갱신 2026-02-23) | evidence:web | https://github.com/tailscale/tailscale/issues/14240 |
| Personal 플랜 무료: 사용자 6명까지, 기기 무제한 | evidence:web | https://tailscale.com/pricing |
| 가격표의 Funnel 행이 플랜별로 어떻게 표시되는지는 텍스트 추출로 확인 못함(KB는 전 플랜 제공이라 명시) | — | https://tailscale.com/pricing |

LOGH7 관점(evidence:guess): 2004년 클라이언트는 TLS+SNI로 접속하지 않으므로 **Funnel은 사실상 불가**. 대신 플레이어가 Tailscale에 가입해 같은 tailnet에 들어오면(무료 6명) 100.x 주소나 MagicDNS 이름으로 모든 포트를 그대로 쓸 수 있어, 소규모 비공개 테스트에 가장 간단하다.

### 2.4 playit.gg

| 사실 | 근거 | 출처 |
|---|---|---|
| 호스트만 에이전트 실행, 플레이어는 그냥 접속("No port forwarding") | evidence:web | https://playit.gg/ |
| 인기 게임 프리셋 외 **custom TCP/UDP** 지원 | evidence:web | https://playit.gg/ |
| Premium **$3/월**: 지역 터널(Regional), `.playit.plus` 도메인 3개, 외부 도메인 연결, 할당 포트 **4개→16개**, 방화벽 규칙·에이전트 추가 | evidence:web | https://playit.gg/support/playit-premium/ |
| 무료 터널은 "Global Anycast"라 경로가 최적이 아닐 수 있음(예: 북미 사용자가 싱가포르 경유) | evidence:web | https://playit.gg/support/playit-premium/ |
| 데이터센터: 북미·유럽·아시아(싱가포르, 일본, 인도, 호주)·남미. 한국 표기는 없음 | evidence:web | https://playit.gg/ |
| 방화벽 규칙으로 허용 IP만 통과시키는 예시 제공 | evidence:web | https://playit.gg/support/playit-premium/ |
| 무료 계정의 터널 수(TCP 4·UDP 4 등)는 제3자 블로그 수치만 있음 → **확인 필요** | evidence:web(2차) | https://instatunnel.substack.com/p/ngrok-vs-playitgg-bypassing-cgnat |

LOGH7 관점(evidence:guess): 플레이어 쪽 설치가 없고 임의 TCP를 지원하며 월 $3로 포트 16개·지역 터널(일본)을 쓸 수 있어 **공개 테스트용 1순위 후보**. 할당된 공개 포트가 로컬 포트와 다를 수 있으므로, 서버가 클라이언트에 알려주는 주소·포트를 "외부 주소" 기준으로 설정할 수 있게 만들어야 한다.

### 2.5 공유기 포트포워딩(직접 공개)

유의점(대부분 일반 관행이라 evidence:guess, 공식 근거가 있는 항목만 별도 표기):
1. **CGNAT 여부 확인**: 공유기 WAN 주소가 공인 IP와 다르면(예: 100.64.0.0/10 대역) 포트포워딩이 밖에서 안 닿는다. 이 경우 playit.gg·ngrok 같은 터널이 필요.
2. **동적 IP**: 가정용 회선은 IP가 바뀔 수 있으므로 DDNS 이름을 쓰고, 클라이언트 설정에는 IP 대신 호스트명을 넣는다(클라이언트가 호스트명을 받는지 확인 필요).
3. **ISP 약관**: 가정용 회선의 서버 운영 제한 조항 여부는 가입 통신사 약관을 직접 확인해야 한다(이번 조사에서 한국 통신사 약관 원문은 확인하지 않음 → 확인 필요).
4. **최소 개방**: 게임에 필요한 TCP 포트만 연다. 관리 화면·DB(PostgreSQL 5432, Redis 6379 등)는 `127.0.0.1`에만 바인딩. 공유기 UPnP 자동 개방은 끈다.
5. **격리**: 서버는 Docker 컨테이너나 VM 안에서 돌리고, Windows 방화벽에 해당 포트 인바운드 규칙만 추가.
6. **2004년 프로토콜의 약점**: 원래 프로토콜은 현대 기준 보안이 약할 수 있으므로(평문 비밀번호, 약한 암호화 가능성) 실제 비밀번호를 쓰지 말도록 안내하고, 서버 측에서 접속 속도 제한·패킷 길이 검증·비정상 패킷 차단을 넣는다.
7. **로그·백업**: 접속 로그와 DB 백업을 자동화.

---

## 3. 권장 단계(evidence:guess)
1. 개발·검증 단계: 같은 PC 또는 LAN(`127.0.0.1`/사설 IP) → 필요하면 Tailscale tailnet으로 지인 2~5명.
2. 공개 테스트: playit.gg Premium(지역 터널: 일본) 또는 CGNAT가 아니면 공유기 포트포워딩 + DDNS.
3. 모든 경우에 서버가 클라이언트에게 알려주는 "외부 호스트/포트"를 설정값으로 분리해 둔다.
