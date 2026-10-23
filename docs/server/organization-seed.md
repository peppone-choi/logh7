---
title: 검증된 조직·초기 카드 시드 정의
author: 최병호
created: 2026-10-09
---

# 검증된 조직·초기 카드 시드 정의

LOGH-28의 작은 구현 범위다. `OrganizationSeedLoader.load()`는 기존 `docs/manual/data/org-posts.csv` 121행과 `initial-card-holders.csv` 75행을 엔진 리소스에서 읽어 변경 불가 스냅샷을 반환한다. Gradle `processResources`가 두 원본 CSV만 포함하므로 별도 전사본은 없다. 검증 범위는 [매뉴얼 부록](../manual/08-organization-units-data.md)의 대조 결과와 제한을 따른다. `evidence:manual`

직책은 진영·부서·직책, 초기 카드는 진영·부서·부대·직책 문맥에서 중복을 검사한다. 각 정의는 원본 파일명, PDF 1기준 페이지, 헤더를 포함한 CSV 논리 레코드 번호를 보존한다. 정원은 자리 수의 양의 정수이고, 최소·최대 계급의 `/` 표현과 `-`, 임명권자의 공란, 부대 공란은 그대로 둔다. 카드 보유자 이름에서 계급이나 인물 식별자를 분리하지 않는다. 임명권자는 기존 CSV의 해설 추출값이며 모든 권한을 수기로 확정한 것으로 취급하지 않는다.

잘못된 UTF-8, 헤더·열 수 변경, 잘못된 인용부호, 빈 레코드, 미지원 진영, 해당 진영의 검증된 표 밖 페이지, 빠진 필수 값, 잘못된 정원과 중복 문맥은 로드를 실패시킨다. 정상 CSV의 BOM, CRLF/LF, 인용된 쉼표·큰따옴표·줄바꿈은 지원한다. 실패 시 부분 스냅샷은 반환하지 않는다.

이 정의 공급기는 `WorldEngine`이나 네트워크 메시지에 아직 연결하지 않았다. 초기 카드와 직책 간 연결, 현재 보유권, 임명·파면 정책, 지도 적재·표시, 초기 배치, 자동 생산, 함선·병원 유닛은 후속 범위다. p.92 함선 이름 잘림, 미복원 지형·시설·인구·재고를 추정하지 않는다. LOGH-28 전체 완료를 의미하지 않는다.

Docker 이미지의 빌드 context는 `server/`이므로 저장소 루트의 `docs/manual/data/`는 context 밖에 있다. 현재 이미지에는 이 로더의 `worldseed/`뿐 아니라 기존 `strategy/` CSV도 포함되지 않는다. Gradle `from`은 없는 디렉터리를 조용히 무시한다. 현재 app은 기동 시 두 로더를 호출하지 않으므로 기동에는 영향이 없지만, 향후 연결 전에 별도 작업으로 리소스 포함 방식을 결정해야 한다. 이번 범위에서는 Docker 설정을 변경하지 않는다.

검사 명령은 저장소의 `server/`에서 `bash ./gradlew build --no-daemon`이며 GitHub-hosted `server.yml`에서 실행한다. 원본 CSV 스냅샷과 합성 정상·실패 입력 및 기존 모듈의 회귀를 검사하며 클라이언트 실행은 수반하지 않는다. `container.yml`은 기존 이미지 빌드·기동 계약을 별도로 검사한다. VPS의 Gradle/JDK 빌드는 실행하지 않는다. 실제 실행 결과와 정확한 SHA·CI run URL은 구현 작업의 `RESULT.json`에 기록한다. 제품 QA는 `productQA=NOT_RUN`, `receipt=null`이며 구현·CI 완료는 LOGH-28 전체 완료나 제품 검증·인수·병합 승인이 아니다.
