# MsgDat 변환·사본 패치 도구

작성자: Peppone Choi · 2026-09-27

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

작성자: Peppone Choi. `image_patch.py`는 Python 표준 라이브러리만 사용하며, 검수한 TGA를 재인코딩 없이 복사한다. PNG 시안의 번역·폰트 보정·TGA 변환을 수행하지 않는다. 실제 화면 확인은 [이미지 한국어화](../../docs/l10n/image-localization.md)의 절차를 따른다.

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

## 전체 카탈로그·한국어 TGA 재생성 및 인계 (2026-10-29)

작성: Peppone Choi. 아래 명령은 전담 작업 폴더에서 실행하며 새 출력 폴더만 사용한다. Python/Pillow는 기존 설치를 사용한다. 원본 이미지·게임 파일은 저장소에 넣지 않는다. 번역 구조와 미확인 범위는 [텍스트 문서](../../docs/l10n/text-format.md#7-전체-번역-카탈로그와-네이티브-연결-후보-2026-10-29), [이미지 문서](../../docs/l10n/image-localization.md#원래-저장-형식의-한국어-tga-후보-29개-2026-10-29)에 있다.

```powershell
Set-Location 'E:/logh7/work/full-korean-localization-wt'
$python = 'C:/Users/user/AppData/Local/Programs/Python/Python311/python.exe'
$msg = (Get-ChildItem 'E:/logh7-original/extracted/install' -Recurse -File -Filter constmsg.dat).FullName
$source = Join-Path (Split-Path (Split-Path $msg)) 'image'
# 기존 work/catalog-14의 결과는 4,822개, 누락 0. 아래는 새 폴더로 재현한다.
& $python -X utf8 -B tools/l10n/catalog.py $msg work/catalog-recheck --header client-compat/korean_catalog_data.h
& $python -X utf8 -B -m unittest discover -s tools/l10n -p 'test_*.py' -v
# 이미지 생성: 팔레트와 편집 상자 밖 원바이트를 유지한다.
& $python -X utf8 -B tools/l10n/render_buttons.py $source work/korean-buttons-recheck
& $python -X utf8 -B tools/l10n/render_atlases.py $source work/korean-atlases-recheck
& $python -X utf8 -B tools/l10n/render_titles.py $source work/korean-titles-recheck
& $python -X utf8 -B tools/l10n/bundle_images.py $source work/korean-images-recheck work/korean-buttons-recheck/package work/korean-atlases-recheck/package work/korean-titles-recheck/package
& $python -X utf8 -B tools/l10n/inventory_images.py $source work/image-inventory-recheck --all
```

현재 인계용 패키지는 `work/korean-images-01/package`다. 버튼 `01`, 아틀라스 `03`, 타이틀 `02`를 묶었다. 생성기를 다시 실행하면 개선된 중복 팔레트 인덱스 보존 때문에 글자 영역 내부 바이트가 기존 후보와 달라질 수 있으며 새 패키지의 해시를 사용해야 한다. PNG 미리보기는 검토용이고 적용 입력은 TGA다.

```powershell
# 실행 담당: 클라이언트를 종료하고 본인이 소유한 사본에만 적용한다.
$package = 'E:/logh7/work/full-korean-localization-wt/work/korean-images-01/package'
$target = 'E:/logh7/work/<실행 케이스>/client/data/image'
& $python -X utf8 -B tools/l10n/image_patch.py verify $package
& $python -X utf8 -B tools/l10n/image_patch.py apply $package $target
# D-7 CLI로 원래 해상도와 다섯 버튼 상태를 확인한 뒤 종료한다.
& $python -X utf8 -B tools/l10n/image_patch.py restore $package $target
```

이 작업에서 `work/korean-image-roundtrip-29/data/image`에 원본 29개를 복사해 적용/복구와 모든 바이트를 확인했다. 실제 게임·서버·DLL은 실행하지 않았다. 통합 담당만 다음 검사를 수행한다(메인 작업 폴더에 병합했다면 경로를 바꾼다).

```powershell
& ./client-compat/build.ps1 -OutDir 'E:/logh7/work/full-korean-localization-wt/work/korean-build-review'
$env:GRADLE_USER_HOME = 'E:/Tools/gradle-home'
Push-Location server
try { & ./gradlew.bat :gateway:test --tests org.logh7.gateway.KoreanLocalizationTest } finally { Pop-Location }
```

`build.ps1`은 DLL과 guard를 함께 빌드/실행한다. 생성 문자열 전부의 Unicode 복원, 원문 불일치 시 거부, Windows 리소스의 작은 버퍼/DBCS 경계를 guard에서 확인한다. Gradle 검사는 실제 생성 단계·코덱·파일 재개방을 사용하며 실제 IME나 두 클라이언트 검증을 대신하지 않는다.

두 번째 묶음(작성: Peppone Choi, 2026-10-29)은 `E:/logh7/work/korean-stage2-wt`에 있다. 위 명령의 작업 경로/출력 경로만 새 작업 폴더로 바꾸고 새 출력 디렉터리를 쓴다. 현재 `work/catalog-stage2-02/coverage.json`은 고정 UI 137개를 포함한 4,843개·누락 0이다. Python 30개가 통과했으며 두 검사가 실제 CD의 표시 전용 CALL과 문자열 인자 전용 서식을 추가 대조한다. x86 guard의 새 이름 복원 검사는 아직 실행하지 않았다. 이미지 후보는 첫 묶음을 유지하며 이 작업에서 변경하지 않았다.

```powershell
Set-Location 'E:/logh7/work/korean-stage2-wt'
& 'C:/Users/user/AppData/Local/Programs/Python/Python311/python.exe' -X utf8 -B -m unittest discover -s tools/l10n -p 'test_*.py' -v
# 아래 빌드/실행은 메인 담당만 수행한다.
& ./client-compat/build.ps1 -OutDir 'E:/logh7/work/korean-stage2-wt/work/korean-stage2-review'
```

미선정 정적 후보 원문/VA는 이 작업 폴더의 `work/remaining-static-stage2-01.json`이다. 원본과 일치하는 기존 후보 372개 중 이번 고정 UI에 없는 281개를 보존했으며 개발자 진단을 포함한다. 실제 화면/직접 오류창과 UTF-16 리터럴의 전체 누락 수를 뜻하지 않는다. 저작물 원문을 담은 이 파일은 `work/` 밖으로 옮기거나 커밋하지 않는다.

## Windows 오류창·추가 제목판의 세 번째 묶음 (2026-10-29)

작성: Peppone Choi. 작업 위치는 `E:/logh7/work/korean-stage3-wt`, 기준 `11f71ec`이다. Python 33개가 통과했다. `work/catalog-stage3-01/coverage.json`은 고정 UI 138개를 포함한 4,844개·누락 0이며 헤더 재생성이 일치했다. `test_display_hooks.py`는 MessageBoxA의 실제 IAT 이름/네 CALL, 화면 경고의 세 push와 복사 허용 목록도 검사한다. guard의 오류창 본문/제목·기존 번역 복원·미등록/변경 거부·ACP 짝 검사는 창을 열지 않으며 아직 실행하지 않았다.

```powershell
Set-Location 'E:/logh7/work/korean-stage3-wt'
$python = 'C:/Users/user/AppData/Local/Programs/Python/Python311/python.exe'
& $python -X utf8 -B -m unittest discover -s tools/l10n -p 'test_*.py' -v
# 아래 DLL/guard 빌드는 메인 담당만 수행한다.
& ./client-compat/build.ps1 -OutDir 'E:/logh7/work/korean-stage3-wt/work/korean-stage3-review'
```

추가 이미지 후보는 `work/korean-prototype-headers-02/package`의 TGA 두 개다. 기존 29개를 자동으로 바꾸지 않는다. 원본 백업·후보·manifest·`render.json`과 원본/후보 비교 `preview.png`를 포함한다. 원본 글꼴과 실제 표시 완료를 주장하지 않는다. `status_window.tga`는 정적 생성 참조를 확인했으며 다른 제목판의 실제 사용은 미확인이다. [상세](../../docs/l10n/image-localization.md#추가-제목판-후보-2개와-정적-참조-2026-10-29)를 따른다.

```powershell
$msg = (Get-ChildItem 'E:/logh7-original/extracted/install' -Recurse -File -Filter constmsg.dat).FullName
$source = Join-Path (Split-Path (Split-Path $msg)) 'image'
# 재생성 출력은 새 폴더여야 한다.
& $python -X utf8 -B tools/l10n/render_prototypes.py $source work/korean-prototype-recheck
& $python -X utf8 -B tools/l10n/image_patch.py verify work/korean-prototype-headers-02/package
# apply/restore는 실행 담당의 중지한 소유 사본에만 기존 도구로 수행한다.
```

이 작업에서는 `work/prototype-roundtrip-02/data/image`에 두 원본을 복사해 실제 파일 바이트 적용/복구를 대조했다. 클라이언트에는 적용하지 않았다. 281개 분류 원문과 참조는 `work/static-stage3-classification-02.json`, 추가 이미지 완전 파일명 검색은 `work/prototype-image-references-01.json`에 있다. 원문/게임 자산을 담은 `work/` 파일은 커밋하지 않는다.

## UTF-16 고정 UI·CString 및 전체 이미지 후보 보완 (2026-10-29)

작성: Peppone Choi. 최신 작업 사본은 `E:/logh7/work/korean-completion-wt`다. 카탈로그는 4,967개(별도 UTF-16 UI 121개 포함)이며 아래 Python46개는 이 사본에서 통과했다. 새 DLL/guard·실제 게임은 실행 담당만 확인한다. 기존 카탈로그 원본과 새 `l10n/exe-wide-ui.ko.json`, `static_resources.py`, 생성기를 함께 통합해야 한다. 헤더를 이전 글리프 표와 섞지 않는다.

```powershell
Set-Location 'E:/logh7/work/korean-completion-wt'
$python = 'C:/Users/user/AppData/Local/Programs/Python/Python311/python.exe'
$source = 'E:/logh7-original/extracted/install/ｱﾌﾟﾘｹｰｼｮﾝ実行可能ﾌｧｲﾙ/data'
# output은 실행할 때마다 새 폴더를 지정한다.
& $python -X utf8 -B tools/l10n/catalog.py "$source/MsgDat/constmsg.dat" work/catalog-recheck-new --header client-compat/korean_catalog_data.h
& $python -X utf8 -B -m unittest discover -s tools/l10n -p 'test_*.py' -v
& $python -X utf8 -B tools/l10n/render_mockups.py "$source/image" work/korean-mockups-new
& $python -X utf8 -B tools/l10n/image_patch.py verify work/korean-all-candidates-01/package
```

현재 미리보기는 `work/korean-mockups-02/{strategy,underpanel}-korean.png`, 새 두 장의 독립 패키지는 `work/korean-mockups-02/package`다. 기존 29개와 제목판 2개를 합친 33개 선택 후보는 `work/korean-all-candidates-01/package`이며 실제 사용 여부·원본 글꼴·최종 기준선 검수 전이다. 파일 사본의 33개 적용/복구 바이트 동일은 `work/image-roundtrip-33/result.json`에 있다. [이미지 범위와 한계](../../docs/l10n/image-localization.md#전략hud-시안의-한국어-후보와-png-복구-2026-10-29)를 따른다.

`image_patch.py inspect/prepare/verify/apply/restore`는 TGA와 RGBA8 비인터레이스 PNG를 처리한다. PNG는 CRC·chunk 순서·원래 크기/색상 정보/투명도를 검사하며 IDAT만 재압축한 후보를 사용한다. PNG의 ancillary chunk나 원본 알파를 버리지 않는다. 확장자만 PNG로 바꾼 TGA도 거부한다. 설치/복구는 실행 담당이 중지된 소유 사본에서 기존 명령으로 수행한다.

실행 담당은 메인 통합본에서 `client-compat/build.ps1`의 x86 DLL/guard를 실행하고, 로비의 일/개월/명·세션 설명, 편지함의 열 제목, 명령 조건 창·취소 로그·번호형 함대 이름과 채팅 세 버튼/한글 송신을 확인한다. 개인 편지 배달·주소록·거절 초안 보존은 이 사본에 없는 `korean-stage5-wt` 변경을 별도 통합해야 한다. [텍스트 검증](../../docs/l10n/text-format.md#12-utf-16-고정-ui와-cstring-복사-경로-보완-2026-10-29), [입력 의존성](../../docs/l10n/native-input.md#전체-보완-작업의-입력-경계와-stage5-의존성-2026-10-29)을 함께 따른다.
