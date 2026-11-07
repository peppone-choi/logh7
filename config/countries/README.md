# 국가 정의의 저장 위치

작성: Peppone Choi ([peppone-choi](https://github.com/peppone-choi)) · 2026-11-06

이 폴더의 CSV는 기본 자료와 **국가 정의 DB를 설정하지 않은 서버**의 정의다. `LOGH7_DEFINITIONS_JDBC_URL`을 설정하면 PostgreSQL의 `nation_country`·`nation_family`·`nation_calendar`와 국가별 재정의가 실행 중 원천이 된다. CSV를 고쳐도 설정된 DB를 덮어쓰지 않는다.

DB 설정에는 V1/V2/V3 이행과 필수 국가·참조 자료가 필요하며 서버가 자동 이행하거나 가져오지 않는다. 웹 `/admin/countries/definitions`에서 국가·계열·역법을 추가/수정하고 `/admin/countries`에서 색상을 편집한다. 전체 정의 버전과 함께 게시하며 게임 요청은 메모리의 마지막 유효 스냅샷만 읽는다. 시작 전 DB가 유효하지 않으면 세계 저장을 쓰기 전에 시작을 거절한다.

DB를 사용하지 않는 서버는 `currencies.csv`에서 제국·신제국 계열의 제국마르크와 동맹 계열의 디나르를 읽는다. 정의 DB를 사용하는 경우 계열 자료를 가져온 뒤 `V7__imperial_mark.sql`과 `V8__alliance_dinar.sql`을 순서대로 적용한다. 이행은 기본 통화가 초기 `원본 자금`인 해당 계열에 새 기본값을 지정한다. 국가별 재정의와 운영자가 이미 선택하거나 수정한 통화는 우선한다.

여러 세션은 이 정의 DB를 공유하고, 참가자·영토·국고·인물·명령은 각 월드 저장에 남는다. 국가를 정의에 추가하는 것만으로 가입·건국을 허용하지 않는다. 파일 세계 백업은 이 DB를 포함하지 않으므로 별도 DB 백업이 필요하다. 지원 필드·원본 표시 제약·이행 및 실기 범위는 [국가 정의 문서](../../docs/server/country-catalog.md)를 확인한다.
