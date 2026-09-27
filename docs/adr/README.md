# 아키텍처 결정 기록 (ADR)

작성: 최병호 · 형식: 맥락 → 선택지 → 트레이드오프 → 결정 → 결과 → 재검토 조건

| ADR | 제목 | 상태 |
|---|---|---|
| [0001](0001-server-stack.md) | 서버 스택(Kotlin/JVM 유지·Spring 제외·구성요소별 언어) | 채택(개정 2) |
| [0002](0002-protocol-compatibility.md) | 프로토콜 호환 전략(명령행 인자 실행 + 세션 주소 서버 반환, MPS 암호 계층 구현) | 채택 |
| [0003](0003-server-logic-reconstruction.md) | 서버 로직 재구현 원칙 | 채택 |
| [0004](0004-localization-method.md) | 한국어화 방식(로캘 전환 CP949 우선, 사설 매핑 대체) | 제안(PoC 후 확정) |
| [0005](0005-operations.md) | 운영 구조(계정·영속성·관리·관측·배포) | 채택(공개 노출 방식은 사용자 네트워크 정보 필요) |

버전 표기는 [docs/research/tools-and-versions.md](../research/tools-and-versions.md)(2026-09-27 웹 확인) 기준이다.
