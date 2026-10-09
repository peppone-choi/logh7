# LOGH-14 재현 도구 인계

작성: 최병호 · 2026-10-08

## 현재 결과

매뉴얼 지도에서 **80성계·287거점의 이름과 성계 소속**을 읽었고, 그중 79성계를 `Null_galaxy`의 항성 위치와 대응했다. 287개 거점 각각의 성계 내부 공전 위치를 복원한 것은 아니다. 매뉴얼에 있는 Forseti는 항성 모델 위치에 없고, 문자열표에만 있는 6개 성계는 매뉴얼 좌표가 없다. `evidence:manual`, `evidence:client`

사용자가 여섯 요새의 이름을 모두 확정했다: `y001` 이제르론, `y002` 가이에스부르크, `y003` 가르미슈, `y004` 렌텐베르크, `y005` 다얀 한, `y009` 루드밀라. 모델 번호와 위치·이름의 복원 범위는 [지도 분석](docs/re/galaxy-map.md)을 따른다.

기존 Windows 시험에서 비활성 데스크톱 실행·음소거 중 활성 오디오 출력·1024×768 로비 렌더링을 확인했다. 고정 dxwrapper D3D9Ex의 창 모드 `ResetEx` 인자 보정을 사용한다. 원래 로더에서 읽은 8모델의 10개 버텍스·인덱스 쌍은 오프라인 추출과 일치했다. `y005`는 오프라인 추출 성공, 네이티브 로더 실패이며 원인은 미상이다. 참조된 `f005_in.tga`가 보유 이미지에 없지만 원인으로 확정하지 않았다. `y006`–`y008` 파일도 없다. `evidence:client`

**Wine 및 CPU 렌더링은 미검증**이다. 아래 일반화한 새 진입점도 게임으로 재실행하지 않았으며, 기존 관측과 이번 합성 검사를 구분한다. 전체 기능 완료나 배포 준비를 의미하지 않는다.

## 재현 방법

Python 3.11, 미리보기에는 Pillow가 필요하다. 기존 매뉴얼 추출은 PyMuPDF·NumPy·SciPy, 모델 표 추출은 pefile을 사용한다. Windows 네이티브 시험에는 pywin32·Frida와 기존 dxwrapper v1.8.8600.25 설정이 필요하다. 새 도구는 추가 설치를 수행하지 않는다.

기존 `manual_map.py`, `galaxy_map.py`, `planet_models.py`, `mdx_geometry.py`가 매뉴얼·좌표·모델 추출을 담당한다. 임시 매뉴얼 분석 6개와 구형 스트림 스캐너는 중복 추가하지 않았다. 새 `asset_repro.py`는 정식 `geometry.json`·버퍼를 사용하며, 고정 드라이브·글꼴·모델 목록 없이 형상/텍스처 비교와 네이티브 버퍼 대조를 제공한다. PNG 출력은 입력 디렉터리 밖의 새 파일만 허용한다.

저장소 루트에서 실행한다. `$client`, `$manual`, `$instance`에는 사용자가 보유한 로컬 경로를 지정한다. 원본·파생 버퍼·이미지·PDF·프로토콜 원바이트는 Git에 추가하지 않는다.

```powershell
# 아래 경로 변수는 이 환경의 실제 로컬 경로로 지정한다.
$client = '<보유 원본 설치 루트>'
$manual = '<gin7manual.pdf 경로>'
$instance = '<저장소 work 아래의 기존 실행 사본>'
python -X utf8 -B tools/assets/manual_map.py $manual $client work/map/manual-world.json
python -X utf8 -B tools/assets/planet_models.py $client work/map/planet-models.json
python -X utf8 -B tools/assets/mdx_geometry.py "$client/data/model/Planets" work/map/geometry-new --buffers --obj
python -X utf8 -B tools/assets/asset_repro.py geometry work/map/geometry-new/geometry.json work/map/fortresses-new.png --model y001 --model y002 --model y003 --model y004 --model y005 --model y009
python -X utf8 -B tools/assets/asset_repro.py textures "$client/data/model/images/Hi" work/map/textures-new.png

# 게임 실행을 수반한다. 별도 승인된 실행 차례에서만 수행한다.
# 기존 사본/원본 분리, CD 실행 파일 해시, 새 출력 경로를 검사한다.
# 이 진입점은 게임이나 DLL을 복사하지 않는다. 사본 준비는 기존 실행 문서 참조.
python -X utf8 -B tools/client/probe-model-loader.py --source-root $client --instance-root $instance --out work/probe-new --seconds 20 --model y001 --model y002
python -X utf8 -B tools/assets/asset_repro.py compare work/map/geometry-new/geometry.json work/probe-new/model-probe/model-events.json

# 게임 없이 합성 입력만 검사한다.
python -X utf8 -B -m unittest discover -s tools/assets -v
python -X utf8 -B -m unittest discover -s tools/client -p test_model_probe.py -v
```

MDX 추출 출력도 새 디렉터리명을 사용한다(기존 추출기의 덮어쓰기 동작은 이번에 변경하지 않았다). 네이티브 종료 코드 0은 실행기 검사와 요청한 모델의 로드 완료를 함께 요구한다. 비교 명령의 종료 코드 0은 **관측한 쌍**의 일치만 의미하며 미관측 스트림·모델까지 보증하지 않는다. 로더 오류 또는 미완료 기록은 성공 처리하지 않는다. 실행기는 자체 `run.json`, 캡처, 첫 패킷을 별도로 남기므로 `work/` 전체를 공개하지 않는다. [실행·오디오·ResetEx 세부 방법](docs/ops/background-client.md).

## 남은 작업과 검사 상태

- LOGH-28: 복원된 성계 이름·좌표·거점 소속을 초기 월드에 적재하고 표시한다. 물리 class·개별 모델 대응·공전값·시설·인구·재고는 미복원으로 구분한다.
- LOGH-61: 기존 키 교환·로그인 관측과 미완료 rekey를 구분하고, 해당 이슈의 최신 의존·완료 조건에 따라 후속 작업을 진행한다.
- `y005` 로더 실패 원인 조사와 누락 자산의 확보 여부 확인. 이를 Wine 결함으로 분류하지 않는다.
- 일반화한 네이티브 진입점의 실제 Windows 재현 및 Wine/CPU 렌더링은 미실행이다. VM은 멀티플레이에 필요할 때만 사용한다.
- 이번 변경의 합성 검사 25개가 로컬에서 통과했다(기존 14개 포함). Python 구문·캡처 스크립트와 결합한 JavaScript 구문·diff 공백 검사도 통과했다. Pillow가 없는 CI에서는 이미지 테스트 2개만 건너뛴다. 게임·서버 전체 빌드·멀티플레이 시험은 실행하지 않았다.

이번 변경에는 도구 소스·합성 테스트·이 문서만 포함한다. 원본 게임·실행 사본·DLL·데이터·형상·이미지·매뉴얼·인계 ZIP/manifest·내부 오케스트레이션 기록·프로토콜 캡처·인증 자료는 추가하지 않는다.

## LOGH-28 클라우드 WIP 인계 (2026-10-09)

작성: 최병호

검증된 기존 CSV의 직책121행·초기 카드75행을 strict UTF-8로 읽는 변경 불가 시드 스냅샷 공급기를 추가했다. engine processResources는 이 두 기존 CSV만 포함하며 원본 필드·빈 값·범위·출처와 부서/부대 문맥을 보존한다. 카드 권한이나 조직 조인 규칙을 만들지 않았고 WorldEngine·wire는 연결하지 않았다. 합성/실제 행 검사를 담은 Kotlin11개는 빌드 슬롯 보류로 미실행이다. EOF·공백·CSV 형태 검사는 통과했다. 전체 LOGH-28 완료가 아니다.

후속 담당은 사용자에게 보이는 앱 별도 채팅에서 `server` cwd의 `gradle --offline :engine:test --tests 'org.logh7.engine.worldseed.OrganizationSeedTest'`로 컴파일·표적 실행을 확인한다. 공개 source와 WIP 브랜치만 인계하며 기존 worktree는 유지한다. 필요한 후속 실제 클라이언트 검사는 부모가 Windows 담당과 연결한다. 별도 Windows 담당의 로그인→로비→합성 캐릭터→전략 월드 진입 보고(2026-10-09 05:02:44–05:05:20 UTC)는 실행 경로 확인이며 이 신규 시드의 수용 검사나 전체 규칙 복원을 대신하지 않는다. 원본 클라이언트·installer·wrapper를 이 환경에서 실행하지 않았다.

Claude Opus 리뷰·실제 셀렉션 API 라우팅·이 WIP의 CI/main 병합은 미실행이다. 배치·함종·승조원·지도·실제 월드 연결과 미확정 규칙은 후속으로 남는다.
