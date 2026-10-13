# LOGH-19 업데이터 TCP 계약 검사 — 독립 리뷰 인계

## 결론

**필수 지적 없음.** 다음 담당: `logh-verify`.

- 리뷰 대상: `8074503a765bdf5a3d206242a0c468ba6b204762` (기준 main `dcf0c578e4248fb86011009202e57db34feb292a`)
- 변경 파일: `HANDOFF.md`(LOGH-19 섹션), `server/gateway/src/test/kotlin/org/logh7/gateway/UpdatePortTest.kt`(신규, 검사 8개). 제품 코드 변경 없음.
- 리뷰 범위는 합성 TCP 계약이다. 원본 업데이터→게임 기동은 NOT_RUN, GUI는 WAITING_FOR_BACKGROUND_QA다. **LOGH-19 Done 불가.**

## 인계 정보

| 항목 | 값 |
|---|---|
| taskId | logh-19-update-tcp-review-20261013 |
| project / stage | logh / review |
| repository | https://github.com/peppone-choi/logh7 |
| 리뷰 branch | buzz/logh-19-update-tcp-review-20261013/logh-review |
| 구현 branch | buzz/logh-19-update-tcp-contract-20261013/logh-implement (원격 head `8074503` 확인) |
| cwd | /srv/openclaw-dev/company/agents/logh-review/worktrees/logh-19-update-tcp-review-20261013 |
| 리뷰 commit | 8074503a765bdf5a3d206242a0c468ba6b204762 |
| 산출물 | 이 문서 `REVIEW-HANDOFF.md` |
| 다음 담당 | logh-verify |
| blocker | 구현 담당의 PR 생성·Linear In Progress 갱신이 자동 승인 심사에서 denied → PR 없음, Linear Todo. 이 리뷰에서는 재시도·우회하지 않았다. |

## 검증 근거

- **서버 소스 동일성:** `git diff --quiet 8f430c5 8074503 -- server` → 동일. 두 커밋의 차이는 `HANDOFF.md` 1행뿐이다.
- **기존 CI 재사용:** [run 38353296927](https://github.com/peppone-choi/logh7/actions/runs/38353296927)의 job `build`(115150355966) 로그를 확인했다. checkout 커밋은 `8f430c58a07182785d257bccd2d3f7f6c33b7fdd`이고, `bash ./gradlew build --no-daemon`이 성공했다. `:gateway:test`는 실제로 실행됐으며(UP-TO-DATE 아님) `BUILD SUCCESSFUL`, `27 actionable tasks: 27 executed`로 끝났다. 신규 파일에 `@Disabled`/assume가 없어 8개 모두 실행 대상이다. 다만 테스트별 통과 수는 로그에 출력되지 않는다(리포트 artifact 업로드 없음).
- **로컬:** `git diff --check dcf0c57 8074503` 통과. VPS 정책에 따라 Gradle 빌드·테스트는 NOT_RUN.
- 소스 대조: `Gateway.kt:68-125`(update 포트 분기 `:96-113`, `exceptionCaught` `:115-118`), `UpdateExchange.kt:12-31`, `load/SyntheticClient.kt:17-52`, 기획 커밋 `0fe997a`의 구현 항목 1~6.

## 요청 항목별 판정

| 항목 | 판정 | 근거 (UpdatePortTest.kt 행) |
|---|---|---|
| 정확한 평문 응답 | 충족 | `:20-23`의 고정 바이트를 `readNBytes`+`assertContentEquals`로 비교한다(`:62-64`). 조기 EOF로 바이트가 짧아도 실패한다. update 분기는 `Frames`/`SecureWriter`를 거치지 않는다(`Gateway.kt:107-109`). |
| EOF와 타임아웃/reset 구분 | 충족 | `:65-68`에서 `read() == -1`만 성공으로 본다. `soTimeout=5000`(`:29`)이 지나면 `SocketTimeoutException`, reset이면 `SocketException`이 발생해 실패한다. |
| fail-closed 뒤 게임 리스너 생존 | 충족 | `:35-38`에서 `SyntheticClient`가 0x34→0x35 검증→0x36까지 실제 애플리케이션 핸드셰이크를 수행한다. 단순 connect보다 강한 검사다. 잘못된 첫 opcode, opcode 누락, 버전 길이 0/3/5, 완료 후 요청 사례마다 호출한다(`:108-155`). |
| 자원 정리 | 충족 | 소켓·Fixture는 `use`, 예약 `ServerSocket`은 `finally`에서 닫는다(`:43-47`). bind 실패 시 `gateway.close()`(`:52`)를 호출하고, `Gateway.close`는 중복 호출에 안전하다(`closed` CAS). capture는 attempt별 `@TempDir` 하위 경로를 쓴다. |
| bind 경합 | 충족 | `BindException`이 cause 체인에 있을 때만 최대 5회 재시도하고, 그 밖의 오류는 즉시 다시 던진다(`:53`). 기획 항목 1과 같은 방식이다. |
| 분할 가정 | 충족 | 1바이트 쓰기가 수신 chunk 분할을 보장하지 않는다는 점을 주석(`:88`)과 HANDOFF에 명시했다. |
| 기존 capture/부하 계약 | 무회귀 | 제품 코드 변경이 없다. `LoadFixture`/`LoadHarnessTest`/`GatewayTest`도 수정하지 않았다. CI에서 `:gateway:test` 전체가 성공했다. |
| 기획 범위 준수 | 충족 | 허용 범위(검사 파일 1개 + HANDOFF)만 변경했다. 금지 대상(`GameExchange`·암호·포트 정책·`connections()`·오류 응답 송신)은 건드리지 않았다. |

## 비필수 참고 (수정 요구 아님)

1. **`requestQueuedAfterCompletion…`(`:144-155`) 경합 여지.** 이 검사는 서버가 `version+identification` 14바이트를 한 번의 read로 받는다고 가정한다. 이때 두 번째 프레임이 `error("already complete")` → `exceptionCaught` → `close` 경로를 탄다. 두 세그먼트로 나뉘어 도착하면 첫 응답 뒤 `CLOSE` 리스너가 먼저 닫는다. 그 사이 미수신 데이터가 커널에 남아 있으면 서버가 RST를 보내 클라이언트 쪽에서 reset이 날 수 있다. 루프백에서 TCP_NODELAY 단일 14바이트 쓰기라 실질 위험은 매우 낮다. 실패하더라도 reset을 성공으로 보지 않는 쪽(false negative 없음)이다. flaky가 관측되면 이 검사부터 본다.
2. 2번째 단계에서 **opcode 오류**(예: 길이 6인 0x6810) 사례는 없다. 기획 항목 5의 요구(길이≠4)는 충족한다.
3. 정상/빈 blob/분할/합침 검사(`:71-106`) 중 첫 검사만 게임 리스너 생존을 확인한다. 기획상 생존 확인은 오류 사례에 대한 요구이므로 충분하다.
4. CI 로그에 테스트별 결과가 없다. verify 단계에서 개별 통과 수가 필요하면 기존 workflow로 확인 가능한 범위를 명시한다.

## 다음 담당(logh-verify)에게

- 서버 소스는 `8f430c5`와 동일하다. 기존 run 38353296927의 결과를 재사용할 수 있다. VPS 빌드는 금지한다.
- PR 생성·Linear 갱신 거절 blocker는 그대로 남아 있다. 병합·Done 판정은 이 리뷰 범위가 아니다. 원본 업데이터→`G7MTClient.exe` 기동 QA는 NOT_RUN이며 WAITING_FOR_BACKGROUND_QA 상태다.
