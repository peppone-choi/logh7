# MsgDat 변환·사본 패치 도구

작성자: 최병호 · 2026-09-27

- Python 표준 라이브러리만 사용한다. 게임 실행 없이 파일을 읽고 새 출력만 만든다. 기존 출력은 덮어쓰지 않으며 `E:\logh7-original` 아래 쓰기를 거부한다. `evidence:client`(도구 테스트).
- HFWR의 그룹 인덱스·패딩·빈 문자열을 보존한다. CP932의 중복 Unicode 매핑은 원바이트를 보존하므로 무편집 JSON 왕복도 동일하다. GFWR 길이는 UTF-16 코드 유닛 단위다. `evidence:client`(E-400, 합성 테스트).
- build는 원본 참조 파일의 구조·해시·문자열 수와 문자열별 `$…$` 토큰 다중집합을 검사한다. 토큰 순서는 번역 어순에 따라 바꿀 수 있지만 이름과 출현 횟수, `$` 개수는 보존한다. 모든 인코딩은 strict이므로 미지원 문자를 물음표로 대체하지 않는다. `evidence:guess`(도구 정책).
- 추출 JSON과 재생성 DAT·EXE는 `work/l10n`에만 보관한다. 게임 텍스트·자산은 커밋하지 않는다. `evidence:guess`(AGENTS.md 정책).

```powershell
# 실제 설치본의 MsgDat 디렉터리 선택
$msg = (Get-ChildItem E:\logh7-original\extracted\install -Recurse -Directory -Filter MsgDat).FullName
$client = (Get-ChildItem E:\logh7-original\extracted\install -Recurse -Filter G7MTClient.exe).FullName
$python = 'C:\Users\user\AppData\Local\Programs\Python\Python311\python.exe'
# 출력 경로는 매번 새 경로를 사용한다.
& $python -B tools\l10n\msgdat.py roundtrip $msg work\l10n\check-new
& $python -B tools\l10n\msgdat.py extract "$msg\constmsg.dat" work\l10n\constmsg.json
# JSON의 strings만 번역한다. 원본 참조를 유지해야 토큰을 검증할 수 있다.
& $python -B tools\l10n\msgdat.py build work\l10n\constmsg.json "$msg\constmsg.dat" work\l10n\korean\constmsg.dat --encoding cp949
& $python -B tools\l10n\msgdat.py patch $client work\l10n\patch-new\G7MTClient.exe --font 굴림 --expected-sha256 bd19263c10decc3d58373165a82d42a9267868400d407da87d5f4f4109ab6e16
& $python -B -m unittest discover -s tools\l10n -p test_*.py -v
```

## 패치 범위와 검증 한계

- PE32 섹션 표로 VA를 파일 오프셋으로 바꾸고 해당 위치의 원본 문자열을 먼저 확인한다. Japanese(0x76e3fc)는 Korean 및 NUL 패딩으로, 글꼴 슬롯(0x76e240)은 CP949 글꼴명(기본 굴림)으로 바꾼다. 슬롯 크기·파일 크기를 유지한다. `evidence:client`(E-401).
- `<출력>.patch.json`에 원본·사본 SHA256, 변경 오프셋·전후 바이트, 원본 불변 여부를 기록한다. 게임 원본은 읽기만 한다. `evidence:client`.
- 이 도구는 승인된 설치본 정적 패치 실험용이다. 배포 런처/패치 적용기는 ADR-0001의 Rust 우선 결정에 따라 별도 구현한다. `evidence:guess`.
- CP949 재인코딩에서 남은 일본어가 표현 불가능하면 build가 실패한다. 파일 전체 문자열의 번역·변환 정책이 필요하며, 이 도구는 자동 번역을 하지 않는다. `evidence:guess`.
- 실제 한글 표시는 검증하지 않았다. `setlocale`, `wctomb`, `ExtTextOutA` 및 VM의 시스템 ANSI 코드페이지·폰트가 함께 작동하는지는 리드의 VM 확인 대상이다. 패치 성공을 화면 표시 성공으로 취급하지 않는다. `evidence:guess`.

## 검수 이미지의 백업·적용·복구 (2026-10-29)

작성자: 최병호. `image_patch.py`는 Python 표준 라이브러리만 사용하며, 검수한 TGA를 재인코딩 없이 복사한다. PNG 시안의 번역·폰트 보정·TGA 변환을 수행하지 않는다. 실제 화면 확인은 [이미지 한국어화](../../docs/l10n/image-localization.md)의 절차를 따른다.

1. `source_root`는 원본 설치본의 `data/image`, `replacement_root`는 검수 TGA의 루트로 지정한다. 두 루트에 같은 상대 경로가 있어야 한다.
2. `inspect`로 각 원본/교체 파일의 SHA256과 TGA 메타데이터를 읽는다. 선택 JSON에는 적용할 자산만 넣는다. 해시는 실제 `inspect` 결과의 소문자 64자리 값으로 채운다.
3. `prepare`는 모든 입력 해시·형식을 대조한 뒤 **새 패키지**에 원본 `backup/`, 교체 `payload/`, `manifest.json`을 만든다. 기존 패키지는 덮어쓰지 않는다.
4. 실행 담당이 클라이언트를 종료한 상태에서 `apply`/`restore`를 실행한다. 대상 루트는 **실행 사본의 `data/image`**다. 쓰기는 `E:/logh7/work` 아래만 허용하며 원본과 입력 파일 자체는 거부한다. 신규 자산을 만들지 않고 존재하는 파일만 교체한다.

선택 JSON 예시(해시 자리표시자는 실제 값으로 교체):

```json
[
  {
    "asset": "icon_normal/kojin.tga",
    "original_sha256": "원본 inspect의 sha256",
    "replacement_sha256": "검수 TGA inspect의 sha256"
  }
]
```

```powershell
$python = 'C:/Users/user/AppData/Local/Programs/Python/Python311/python.exe'
$tool = 'E:/logh7/work/character-office-tab-wt/tools/l10n/image_patch.py'
$case = 'E:/logh7/work/l10n-images-20261027'
# 실제 설치본의 data/image를 지정한다.
$source = (Get-ChildItem E:/logh7-original/extracted/install -Recurse -Directory -Filter image |
    Where-Object { Test-Path (Join-Path $_.FullName 'icon_normal/kojin.tga') }).FullName
# reviewed-tga는 검수·변환을 마친 후 만드는 경로이며 기존 PNG 시안 폴더가 아니다.
& $python -B $tool inspect "$source/icon_normal/kojin.tga"
& $python -B $tool inspect "$case/reviewed-tga/icon_normal/kojin.tga"
& $python -B $tool prepare $source "$case/reviewed-tga" "$case/selection.json" "$case/package-new"
& $python -B $tool verify "$case/package-new"
# 아래 대상은 실행 담당이 준비한 실제 사본 경로로 바꾼다.
$target = 'E:/logh7/work/<실행 케이스>/client/data/image'
& $python -B $tool apply "$case/package-new" $target
& $python -B $tool restore "$case/package-new" $target
& $python -B -m unittest discover -s tools/l10n -p test_image_patch.py -v
```

- 조사한 CD 자산에 해당하는 비압축 팔레트(8비트 인덱스, 24/32비트 팔레트)·24/32비트 true-color를 지원한다. RLE와 확장/개발자 영역은 거부한다. 크기·원점·픽셀/팔레트 깊이·팔레트 시작/개수·descriptor·ID·빈 v2 푸터 유무가 원본과 같아야 한다. 구조 해석 근거는 [Truevision TGA 2.0 명세](https://www.ludorg.net/amnesia/TGA_File_Format_Spec.html)다.
- 알파의 저장 형식은 보존하고 픽셀별 알파 최솟값/최댓값·완전 투명/불투명 수를 기록한다. 글자 변경으로 알파 내용은 달라질 수 있으므로 배경 픽셀·마스크·기준선·동일 글꼴·스프라이트 좌표의 시각 검수는 별도다.
- 적용·복구 전 모든 패키지 파일과 대상 파일을 검사한다. 대상이 원본 또는 같은 패키지의 교체본과 다르면 다른 변경을 덮어쓰지 않고 거부한다. 파일별 원자 교체, 처리 중 오류 시 이전 변경 복구, 중단 뒤 혼합 상태 재실행을 지원한다. 프로세스 강제 종료나 전원 장애 시 전체 묶음의 원자성은 보장하지 않는다. 재실행 전 `verify`하고 필요하면 `restore`한다.
- `display_validated`는 항상 `false`다. 단위검사·백업 성공·복구 성공을 이미지 번역이나 실제 클라이언트 확인 완료로 취급하지 않는다.
