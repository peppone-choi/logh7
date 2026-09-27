# 텍스트 리소스·폰트·한국어화 (트랙 B)

- 작성자: 최병호
- 작성일: 2026-09-27
- 근거: Evidence E-019, E-020, E-021, E-026, E-027. 태그 `evidence:client`/`evidence:guess`.

## 1. 텍스트 리소스 위치와 비중 (E-021)

게임 대사·메뉴 텍스트는 대부분 **외부 파일 `data\MsgDat\*.dat`**에 있고, exe 안에는 디버그 메시지와 일부 UI 조각만 있다.

| 소스 | 문자열 수 | 바이트(대략) | 성격 |
|---|---|---|---|
| `data\MsgDat\*.dat` (HFWR 21 + GFWR 1) | 비어있지 않은 것 4,833개 | 약 248 KB | 게임 대사·시스템 메시지·금칙어. 한국어화 주 대상 |
| G7MTClient.exe `.data/.rdata` SJIS | UI 후보 ~191, 디버그류 ~278 | UI 약 4.9 KB | UI 조각은 소수, 대부분 개발자 디버그 로그 |
| G7MTClient.exe RT_STRING | 128 (일본어 124) | 소량 | 대부분 MFC 기본 문자열 + 문서 타입명 |
| Gin7UpdateClient.exe RT_STRING | 104 (일본어 98) | 소량 | 업데이터 UI(진행/오류 메시지) |
| 이미지(.bmp/.tga/.jpg) | 미측정 | — | 버튼·배경에 일본어가 그림으로 박혀 있을 수 있음 |

- 추정 비율: 텍스트 분량 기준으로 **exe 내부 UI ≈ 2% 미만, MsgDat ≈ 98%**. `evidence:guess`(휴리스틱 분류), 재현 `python scripts\ui_text_ratio.py notes\strings_G7MTClient.txt <ROOT>\data\MsgDat`.
- 한국어화 1차 대상은 `MsgDat\*.dat`. 2차로 업데이터/클라이언트 RT_STRING과 이미지 내 텍스트, 소수의 exe 내부 UI 문자열.

## 2. MsgDat 포맷 (E-019) — 검증됨(정적)

### 2.1 HFWR (constmsg.dat, messages_*.dat) — Shift_JIS 문자열 테이블
```
+0x00  char[4]  magic = "HFWR" (0x48 0x46 0x57 0x52, dword 0x52574648)
+0x04  u32      예약(0)
+0x08  u32      N = 총 문자열 개수
+0x0c  u32      G = 그룹 개수
+0x10  u32[ (G+3) & ~3 ]  그룹 시작 인덱스(누적, 첫 값 0, 마지막 값 = N). 4개 단위로 0 패딩
+...   N개의 NUL 종료 cp932 문자열이 연속
```
- 로더(`FUN_00522060`/`FUN_00522310`)가 인덱스 테이블을 `(G+3)&~3` dword만큼 읽는다(4개 정렬 패딩). 이 정렬을 반영하면 21개 파일 모두 꼬리 바이트 0으로 정확히 파싱된다(재현 `python scripts\hfwr_dump.py <ROOT>\data\MsgDat`).
- 압축·암호화 없음. 문자열은 cp932 평문(전 파일 디코드 오류 0).
- 빈 문자열이 많다(파일별 번역/버전 차이로 일부 그룹만 채움). `messages_0`은 기준 텍스트, `messages_1..8`은 상황별 변형으로 보임.

### 2.2 GFWR (g7sw.dat) — UTF-16LE 금칙어 목록
```
+0x00  "GFWR"(dword 0x52574647)  +0x04 u32 0  +0x08 u32 seed/hash(예: 0x6ab57d4d)  +0x0c u32 N(=14)
+0x10  N × { u32 len(UTF-16 코드 유닛 수) ; UTF-16LE 코드 유닛 len개 }
```
- `sw` = stop words. 채팅 필터용으로 추정. 14개 단어(예: 짧은 일본어 낱말들). `evidence:client`(구조)/`evidence:guess`(용도).

### 2.3 치환 토큰
문자열에 `$xname$`, `$r10$`, `$xcommand$`, `$ydate$` 같은 `$…$` 토큰이 들어 있다(구분자 `$`=0x24, `FUN_00521c10`에서 설정). 런타임에 값으로 치환되는 플레이스홀더다. 한국어화 시 토큰은 보존해야 한다. 상위 토큰: `$r10$`(1218), `$xdate$`, `$xtitlepriortyb$`, `$xplanet$`, `$yname$`, `$xexecutor$`, `$xcommand$` 등.

## 3. 폰트·문자 렌더링 (E-020, E-026) — 한국어 표시 전략의 핵심

- 로케일: 문자열을 그릴 때 `setlocale(LC_CTYPE,"Japanese")` 후 `mbstowcs`로 cp932→UTF-16 변환(`FUN_004eac60`). `evidence:client`, high.
- 폰트 생성(`FUN_004b07c0`/`FUN_004b0960`, `FUN_004aec70`):
  ```
  CreateFontA(height, 0,0,0, weight(400/700), italic, 0,0,
              fdwCharSet = 1 (DEFAULT_CHARSET),
              OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS,
              fdwQuality = 4 (ANTIALIASED_QUALITY),
              fdwPitchAndFamily = 1 (FIXED_PITCH),
              lpszFace = "ＭＳ ゴシック")   ; 0x0076e240, cp932 바이트 82 6c 82 72 20 83 53 83 56 83 62 83 4e
  ```
  - **charset이 `DEFAULT_CHARSET`(1)**이라는 점이 중요하다. 특정 SHIFTJIS_CHARSET(128)로 고정돼 있지 않아, 시스템/폰트가 한국어를 지원하면 charset 강제 없이 다른 문자셋도 그릴 여지가 있다. 다만 폰트명이 `ＭＳ ゴシック`로 하드코딩(0x0076e240)돼 있어 한글 글리프가 없다.
- 글리프 캐시: 렌더한 글자를 D3D 텍스처 아틀라스에 캐싱하며 **키가 UTF-16 코드포인트**다(캐시 테이블 0x10000 엔트리, `FUN_004b0960`). 문자 단위로 `ExtTextOutA`로 GDI 렌더 후 텍스처에 복사한다. 즉 내부 파이프라인은 유니코드 코드포인트 기반이라, 입력 텍스트가 한글이어도 폰트만 있으면 렌더 경로 자체는 동작할 수 있다. `evidence:client`(구조)/`evidence:guess`(한글 동작 여부는 미검증).

## 4. 한국어화 방식 후보 (기술적 시사점)

우선순위와 근거:

1. **MsgDat 재작성(주 경로)**: `HFWR` 포맷 그대로 한국어 문자열로 다시 만든다. 두 가지 인코딩 선택:
   - (a) cp932 유지 불가 — 한글은 cp932에 없음. 그러므로 (b)가 필요.
   - (b) **cp949(EUC-KR)로 바이트 교체 + 렌더 경로의 코드변환을 cp949로 전환**: `FUN_004eac60`의 `setlocale("Japanese")`/`mbstowcs`가 cp932를 가정하므로, 한글을 넣으려면 이 변환을 cp949(또는 UTF-8→UTF-16)로 바꾸는 **바이너리 패치**가 함께 필요하다. 자기검증·패커가 없어(E-022) 패치는 가능. `evidence:guess`, medium.
2. **폰트 교체**: 폰트명 `ＭＳ ゴシック`(0x0076e240)를 한글 지원 폰트(예: `굴림`/`맑은 고딕`의 cp949 바이트열)로 문자열 패치. charset은 이미 DEFAULT_CHARSET이라 별도 강제 불필요. 문자열 길이가 원본(≤13바이트 슬롯) 안이면 인플레이스 교체 가능. `evidence:client`(위치)/`evidence:guess`(효과).
3. **렌더 경로 확장(정석, 난이도 높음)**: mbstowcs 호출을 UTF-8/UTF-16 직접 처리로 후킹하고 MsgDat도 UTF-16로 확장. 글리프 캐시가 이미 UTF-16 키라 상위 파이프라인 변경은 적다. 단 로더/직렬화(문자열은 NUL 종료 바이트열, `op_string`)가 멀티바이트를 가정하므로 대공사. `evidence:guess`.
4. **이미지 내 텍스트**: 버튼·배경의 일본어는 .tga/.bmp 재작업 필요(포맷 표준이라 편집 가능). 별도 작업 항목.

권장: 서버를 새로 만드는 프로젝트이므로 **(1b) MsgDat를 cp949로 재작성 + 렌더 변환 cp949 패치 + (2) 폰트명 패치** 조합이 현실적. 검증은 실제 렌더 확인(동적)이 필요하므로 candidate.

## 5. 미해결

- `.tcf` 얼굴 이미지 묶음의 압축/인코딩(고엔트로피 본문)과 `.mdx/.mds` 모델 포맷 — 텍스트는 아니지만 이미지 현지화 시 필요. 미해결.
- MsgDat 그룹 인덱스가 게임 내 어떤 문맥과 대응되는지(대사 트리거) — 서버·게임로직 분석 영역.
- 한글 렌더가 실제로 표시되는지 — 동적 검증 필요. 로캘·글꼴 문자열 패치 위치는 아래 정적 검증으로 확인.

## 6. T3 도구·정적 검증 추가 (2026-09-27)

작성자: 최병호. `evidence:client`(work/l10n E-400~E-402).

- [도구 사용법](../../tools/l10n/README.md): HFWR/GFWR 추출·재생성, 토큰 검사, CP949 출력, PE 사본 패치.
- 원본 MsgDat 22개 모두 JSON 왕복 SHA256 동일. 원본 불변 확인. 결과는 `work/l10n/roundtrip/roundtrip.json`.
- `Japanese` VA `0x0076e3fc` → `Korean`, 글꼴 VA `0x0076e240` → `굴림`; 원바이트 검사를 통과한 사본만 생성.
- 원본 SHA256: `bd19263c10decc3d58373165a82d42a9267868400d407da87d5f4f4109ab6e16`.
- 사본 SHA256: `2a901619878512f43982c9199f2ba654af9f203ea27308aea483bcf84a50979a`.
- 합성 테스트 7개 통과. case-review의 해시 검증·strict 결과는 `work/l10n/case-review.txt`.
- Finding은 candidate 유지: 화면 렌더링·시스템 코드페이지·폰트 동작을 VM에서 확인하지 않았다. `evidence:guess`.
- reverse-skill 기록 통합 제안: CP932 중복 Unicode 매핑의 원바이트 보존, GFWR UTF-16 코드 유닛 길이, JSON 왕복 해시 검증을 재사용 사례로 기록. 로컬 main 반영은 리드 담당. `evidence:guess`.
