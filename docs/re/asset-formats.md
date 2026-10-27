---
title: 클라이언트 자산 포맷 목록 (LOGH-13)
author: 최병호
created: 2026-10-18
evidence: E-019, E-025, E-027, docs/re/galaxy-map.md, tools/assets/
status: 부분 문서. UNKNOWN 항목은 원본 근거가 생기기 전까지 확정하지 않는다.
---

# 클라이언트 자산 포맷 목록

CD판 버전 131 클라이언트 `data\` 아래 자산을 확장자별로 정리한다. 이 문서는 새 분석이 아니라 저장소에 이미 있는 문서·도구·시험을 근거로 묶은 색인이다. 원본 자산·추출 텍스트는 저장소에 넣지 않는다. 파생 산출물은 `work/` 아래에 둔다.

## 표기

- 근거 `path:line`은 base main `0008774` 기준 저장소 경로다.
- 확신도: high = 로더 코드나 원래 로더 호출로 확인, medium = 정적 시그니처 관찰만 있음, low = 용도 추정.
- UNKNOWN = 저장소 근거 없음. 값·VA를 추정해서 채우지 않는다.
- NOT_RUN = 이번 작업에서 원본 파일이나 클라이언트를 실행·조회하지 않았음.
- 번역 필요: 한국어화(ADR 0004) 범위에 텍스트가 직접 들어 있는지 여부.

## 요약 표

| 확장자 | 개수 | 매직/시그니처 | 용도 | 로더 VA | 번역 필요 | 근거 | 확신도 |
|---|---:|---|---|---|---|---|---|
| `.dat` (MsgDat, HFWR) | 21 | `HFWR` (dword 0x52574648) | 게임 텍스트, cp932 문자열 테이블 | `0x00522060` / `0x00522310` (HFWR::load), `0x00521dc0` (MsgMgr::load_all) | 예 (1차 대상) | docs/l10n/text-format.md:24, docs/re/function-map.md:100-101 | high |
| `.dat` (g7sw.dat, GFWR) | 1 | `GFWR` (dword 0x52574647) | 금칙어, UTF-16LE | `0x005232d0` (GFWR::load) | 검토 필요 (금칙어 목록) | docs/l10n/text-format.md:37, docs/re/function-map.md:102 | high |
| `.mdx` | 406 | 첫 dword가 과거 힙 주소 형태 (`0x01d0xxxx` 등) | 3D 모델 (직렬화 포인터 포함) | `0x004d15f0` (모델 로드), `0x004d16e0` (해제), 재배치 `0x005f6230` / `0x005f61d0` | 아니오 (텍스처 참조 문자열만 관찰) | docs/re/binary-inventory.md:88, docs/re/galaxy-map.md:141, tools/client/probe-model-loader.js:8-9 | high (관찰된 행성·요새·Null_galaxy 레이아웃 한정) |
| `.mds` | 12 | `.mdx`와 같은 주소형 첫 dword | 모델 (비압축 mesh 배열, MDX 렌더 스트림과 다름) | UNKNOWN (클라이언트에 `.MDS` 문자열만 확인) | 아니오 (UNKNOWN) | docs/re/binary-inventory.md:88, docs/re/galaxy-map.md:141 | medium |
| `.tcf` | 7 | 50바이트 머리말 + 비압축 팔레트 TGA 연속 | 얼굴 이미지 묶음 | `0x005924c0`, ID 선택 `0x00592c30` | 이름·능력 수치표는 확인되지 않음 | [NPC 얼굴 분석](npc-character-catalogue.md), tools/assets/npc_catalogue.py | high (CD 원작 계열 3개 추출·7개 TGA 레코드 조회) |
| `.hed` (`tcf.hed`) | 1 | 원작 계열은 LE u32 offset/size 쌍, 제너레이트 계열은 나이 변형 배열 | `.tcf` 인덱스 | `0x005924c0` | 아니오 | [NPC 얼굴 분석](npc-character-catalogue.md) | high (원작 계열 450슬롯) |
| `.tga` | 661 | 표준 TGA 헤더 | UI·배경·모델 텍스처 | UNKNOWN | 일부 가능 (이미지 내 일본어, 미측정) | docs/re/binary-inventory.md:89, docs/l10n/text-format.md:17, docs/l10n/text-format.md:71 | medium |
| `.bmp` | 995 | 표준 BMP 헤더 | UI·배경, 행성 텍스처 `pNNN.bmp` | UNKNOWN | 일부 가능 (미측정) | docs/re/binary-inventory.md:89, tools/assets/asset_repro.py:142 | medium |
| `.jpg` / `.png` | 45 / 16 | 표준 헤더 | 이미지 | UNKNOWN | 미측정 | docs/re/binary-inventory.md:89 | medium |
| `.ogg` / `.wav` | 7 / 13 | `OggS` / `RIFF` | BGM(Vorbis) / 효과음 | UNKNOWN | 아니오 | docs/re/binary-inventory.md:90 | medium |
| `.VIX` | 1 | `ViX ` + 개발 경로 `W:\Gin7\...` | 이미지 뷰어 카탈로그 (개발 잔재) | 해당 없음으로 보임 (클라이언트 참조 근거 없음, UNKNOWN) | 아니오 | docs/re/binary-inventory.md:91 | low |
| `.db` | 1 | OLE CF `D0 CF 11 E0` | Windows `Thumbs.db` (게임과 무관) | 해당 없음 | 아니오 | docs/re/binary-inventory.md:92 | medium |

개수는 E-025 시그니처 조사(`scripts/asset_sigs.py`, 원래 케이스 작업 폴더의 스크립트로 저장소에는 없음; 조사·스크립트 근거: docs/re/2026-09-27_reverse-logh7-client-triage-report.md:60) 결과이며 docs/re/binary-inventory.md:85-92에 기록돼 있다. 공식 추가 데이터 G7UPD040514를 적용하면 `data\model\images\{hi,mid,lo}`, `data\model\strategy`에 신규 108·변경 48개가 생긴다(docs/re/update-040514.md:28-33). 위 개수에 이 변화가 반영됐는지는 UNKNOWN이다.

## 포맷별 상세

### HFWR / GFWR (MsgDat)

정적 분석으로 레이아웃이 확정됐다. 헤더, 4개 단위 정렬 그룹 인덱스, NUL 종료 cp932 문자열 순서이며 압축·암호화는 없다. GFWR은 u32 길이 + UTF-16LE 코드 유닛 레코드다. 상세 레이아웃과 재생성 도구는 [text-format.md](../l10n/text-format.md) 2장(docs/l10n/text-format.md:22-45)과 [tools/l10n/README.md](../../tools/l10n/README.md)를 따른다.

### MDX

범용 포맷 정의는 없다. 저장소에서 확인된 것은 관찰한 두 가지 레이아웃뿐이다.

1. 렌더 스트림(행성·요새): 36바이트 레코드 배열 뒤에 버텍스(수×stride), 인덱스(u16, 수×2), 리맵(수×2)이 이어진다. 확인된 플래그/stride는 `0x10102/24`, `0x10112/36`, `0x12112/72`다(docs/re/galaxy-map.md:143-151, tools/assets/mdx_geometry.py:17). 원래 로더 `0x004d15f0` 호출 결과 10개 버텍스·인덱스 쌍과 오프라인 추출이 바이트 단위로 일치했다(docs/re/galaxy-map.md:170). 이 검증은 2026-10-08 기존 Windows 시험 기록이며 이번 작업에서 다시 실행하지 않았다(NOT_RUN).
2. 노드·키(Null_galaxy.mdx): offset 0 포인터에서 `0x50`을 뺀 값을 기준 주소로 삼아 노드(0xe8바이트)·채널·키 포인터를 파일 offset으로 바꾼다(docs/re/galaxy-map.md:176, tools/assets/galaxy_map.py:89-115).

모델 경로 표는 VA `0x00775108`(상세), `0x00775310`(저해상도), 각 130슬롯이다(docs/re/galaxy-map.md:133, tools/assets/planet_models.py:55). 재질·애니메이션·노드 변환 해석은 UNKNOWN이다. y005는 네이티브 로더 호출에서 오류가 났고 참조 텍스처 `f005_in.tga`를 찾지 못했다(docs/re/galaxy-map.md:170).

도구와 합성 시험:

| 도구 | 용도 | 시험 |
|---|---|---|
| tools/assets/mdx_geometry.py | 렌더 스트림 후보 추출, OBJ 미리보기 | tools/assets/test_mdx_geometry.py |
| tools/assets/galaxy_map.py | Null_galaxy 앵커, 지도 페이로드 | tools/assets/test_galaxy_map.py |
| tools/assets/planet_models.py | 모델 번호 표 읽기 | tools/assets/test_planet_models.py |
| tools/assets/asset_repro.py | 형상·텍스처 미리보기, 네이티브 버퍼 비교 | tools/assets/test_asset_repro.py |
| tools/assets/manual_map.py | 매뉴얼 지도 주석 추출 | tools/assets/test_manual_map.py |
| tools/client/probe-model-loader.py / .js | 원래 로더 관찰 (Windows, opt-in) | tools/client/test_model_probe.py (설정·기록 검사만, 클라이언트 실행 없음) |

### MDS

MDX 렌더 스트림과 섞으면 안 되는 비압축 mesh 배열이라는 기록만 있다(docs/re/galaxy-map.md:141). 헤더·레코드 레이아웃·로더 VA는 UNKNOWN이다.

### TCF / HED

2026-10-27에 기존 로더 내보내기와 CD 원본을 대조해, 50바이트 머리말 뒤 비압축 팔레트 TGA가 연속됨을 확인했다. `00592c30`이 얼굴 ID를 계열·슬롯으로 나누고 `005924c0`이 HED의 offset/size를 읽는다. 원작 계열 `oem/oam/o` 291장의 추출·파일 끝까지 일치 검사를 통과했다. 공용·나이 변형이 있어 이미지 수를 NPC 인원수로 취급하지 않는다. 제너레이트 HED는 나이별 offset/size 배열이므로 전체를 원작의 단순 쌍 인덱스로 해석하지 않는다. 형식·번호·도구와 한계는 [NPC 얼굴 분석](npc-character-catalogue.md)을 따른다. 이전 미해결 기록은 당시 조사 범위다. `evidence:client`

### TGA / BMP / JPG / PNG

표준 헤더라 일반 도구로 읽고 편집할 수 있다. 이미지 안 일본어 텍스트 규모는 미측정이며 한국어화는 별도 작업이다(docs/adr/0004-localization-method.md:40). 행성 텍스처 `pNNN.bmp` 미리보기는 asset_repro.py가 다룬다(tools/assets/asset_repro.py:142-144). 이미지별 로더 VA와 클라이언트 경로 문자열 대응은 UNKNOWN이다.

### VIX / DB

게임 실행에 쓰이는 근거가 없다. `.VIX`는 개발 잔재, `.db`는 Windows 썸네일 캐시로 분류한다.

## 남은 UNKNOWN

- `.mds` 레이아웃과 로더 VA
- 얼굴 ID↔이름↔원래 능력치의 전체 대응표와 제너레이트 HED 전체 추출 도구
- MDX 재질·애니메이션·노드 변환, 관찰 범위 밖 MDX 변형
- TGA/BMP/JPG/PNG 로더 VA와 이미지 내 일본어 규모
- 요약 표 개수가 G7UPD040514 적용 전후 중 어느 기준인지
- 최초 색인은 원본 파일·클라이언트를 조회하거나 실행하지 않았다(NOT_RUN). TCF/HED 항목만 2026-10-27에 원본 파일을 읽어 갱신했으며 게임은 실행하지 않았다.
