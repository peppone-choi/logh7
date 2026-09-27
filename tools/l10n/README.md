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
