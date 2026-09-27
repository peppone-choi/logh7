# logh7

서비스가 종료된 PC 온라인 전략 게임 『銀河英雄伝説 VII』(BOTHTEC, 2004–2005)의 **대체 서버 · 한국어화 · 서버 로직 복원** 프로젝트.

> 공식 서버는 2005년에 사라졌고 실 서비스 패킷 캡처는 남아 있지 않다. 프로토콜은 클라이언트 정적 분석과, 우리가 만든 스텁 서버에 클라이언트를 붙여 얻는 로컬 트래픽으로 복원한다.

## 구성

| 경로 | 내용 |
|---|---|
| `docs/00-overview.md` | 게임·프로젝트 개요, 출처 |
| `docs/PLAN.md` | 계획 요약(단일 원천은 Linear) |
| `docs/adr/` | 아키텍처 결정 기록 |
| `docs/re/` | 바이너리 인벤토리·함수 맵·접속 흐름 |
| `docs/protocol/` | 프로토콜 명세 초안 |
| `docs/manual/` | 매뉴얼 기반 게임 규칙 지식베이스 |
| `docs/l10n/` | 한국어화: 텍스트 포맷·인코딩·용어집 |
| `docs/ops/` | 운영·배포·모니터링 |
| `docs/research/` | 선행 작업·도구·외부 조사 |
| `tools/` | 원본 복원·추출·분석 스크립트 |
| `server/` | 대체 서버 (예정) |
| `l10n/` | 번역 파이프라인 (예정) |

## 게임 원본

게임 원본은 저장소에 포함하지 않는다. 아래 보관본을 받아 스크립트로 검증·추출한다.

| 자료 | 링크 | 비고 |
|---|---|---|
| 클라이언트 CD 이미지 | **https://archive.org/details/logh-7** (`Logh7.bin` + `Logh7.cue`) | 무결성 기준: `logh-7_files.xml` |
| 공식 매뉴얼(웹판, 2004-10-07, 101p) | https://archive.org/details/gin7manual | CD 동봉판(2004-04, 69p)은 CD 이미지 안에 있음 |
| 공식 추가 데이터 업데이트 `G7UPD040514.exe` | https://web.archive.org/web/20040625193252/http://gineiden.com:80/G7UPD040514.exe | 텍스처·모델만 포함(실행 파일 변경 없음) |
| 공식 사이트(보관본) | https://web.archive.org/web/2004*/gineiden.com | 패치노트·공지·FAQ |

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\fetch-original.ps1 -Root E:\logh7-original
```

| 파일 | 크기 | MD5 | SHA1 |
|---|---|---|---|
| `Logh7.bin` | 229,070,688 | `bf87c6a8cb068f05625737377a07b09d` | `80e261e9d84c81bca622c99d9cbdc47a2154c1a8` |
| `Logh7.cue` | 71 | `878418e704a913f7baac67b38b10e680` | `9bff4ea17ca6ff7b088440bd7f8a5206cd2dfe81` |
| `G7UPD040514.exe` | 10,913,837 | — | SHA256 `0bd0cd52eca4050e8045cf9e469788f222333e0509b8259f64ce93736a2e489c` |

작성: 최병호
