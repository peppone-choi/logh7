# 세션 2 Linear 반영 기록

작성: 최병호 · 실행일: 2026-09-27 · 파일명은 사용자 지정 세션 식별자 유지.

Linear MCP가 연결되어 사이클 1·2 이슈를 읽고 아래 변경을 실제 저장했다. 결정 출처는 사용자 세션 2 요청과 AGENTS.md이며 설계 결정 태그는 `evidence:guess`다.

| 항목 | 반영 내용 | 상태 |
|---|---|---|
| LOGH-5 | D-1 VirtualBox 확정, VBoxManage 경로 부재로 G-4·G-5 보류 | 저장 완료 |
| LOGH-17 | protocol/engine/gateway/ops-api/persistence/app, 내장 Ktor, Spring 제외 | 저장 완료 |
| LOGH-45 | 내장 Ktor + Next.js, 상태 변경은 엔진 큐 | 저장 완료 |
| LOGH-26 | Rust 1순위, argv[3]은 계정이 아닌 세션명 | 저장 완료 |
| LOGH-38 | D-4 일본어 직역 + 나무위키 URL, 이타카 대조 보류 | 저장 완료 |
| LOGH-41 | D-2 GCP 전제·로컬 연습·과금 금지, 자택 회선 선행조건 제거 | 저장 완료 |
| P6 운영 | GCP 전제·Ktor·Next.js로 설명 및 요약 수정 | 저장 완료 |
| M6.1/M6.2 | 로컬 GCP 연습과 GCP 공개 주소 설계로 마일스톤 설명 수정 | 저장 완료 |

Kotlin 유지에 대한 사용자 응답을 LOGH-17에 기록했다. D-3 reverse-skill 로컬 main만 커밋·push 금지는 이 세션 운영 제약으로 보고서에 기록한다. 이 표는 계획의 대체 원천이 아닌 변경 영수증이다. 구현 완료 상태는 실제 검증 후 따로 갱신한다.

## 실행 후 상태 갱신

- Done: LOGH-17(원격 CI 성공), LOGH-12(22/22 왕복), LOGH-15(전 페이지 대조·원자료 잘림 명시), LOGH-58(지정 33 URL 요약), LOGH-11(정적 후보 명세).
- In Progress: LOGH-14(지도 형식만 복원), LOGH-18·19(합성 TCP 완료·원본 미검증), LOGH-38(현행 표기 재조회 남음), LOGH-61(암호·골격 완료·실교환 미검증).
- LOGH-5: 사용자 추가 승인으로 E:\VirtualBox 재설치·VM 골격까지 진행. 현재 장애는 Microsoft 공식 ISO 미확보다.

상태는 각 이슈의 실제 완료 조건에 맞추었으며, 정적 이슈 Done을 전체 클라이언트 상호운용 완료로 해석하지 않는다. `evidence:client`/`evidence:guess`.

- 최종 운영 반영: LOGH-41·42·43은 In Progress. GCP 설계·Ubuntu 복제본 Compose 리허설은 완료했으나 실제 게임 이미지·WAL 복원·공식 SKU 견적은 남았다.
- LOGH-6·20·22에 현재 ISO 장애와 원본 동적 미검증 상태를 기록하고 blocked 라벨을 추가했다.
- 통합 PR: https://github.com/peppone-choi/logh7/pull/5

## 후속 실행 반영 (2026-09-27)

- LOGH-5: 공식 ISO·일본어 Windows·한국어 기능·host-only·자동 로그인·`clean`/`installed` 완료로 Done. VirtualBox 무인 설치가 빈 비밀번호를 거부해 AGENTS.md 허용 범위의 간단한 비밀번호를 사용했다.
- LOGH-6: 설치 전후 목록과 레지스트리, 설치본 2,194개 대 정적 추출본 2,194개 SHA-256 완전 일치를 기록했다. [PR #12](https://github.com/peppone-choi/logh7/pull/12) 병합 후 Done으로 갱신했다.
- LOGH-20: 원본 클라이언트 세 차례 기동했으나 3D off/on 모두 TCP 이전에 응답 중지. VMware 대안 선택을 기다리며 In Progress.
- LOGH-22: 실제 첫 0x34가 없어 Backlog와 candidate 유지. 합성 테스트를 동적 증거로 승격하지 않는다.

근거: [VM 후속 보고서](2026-09-28-vm-followup.md), 동적 케이스 E-103~108. `evidence:client`
