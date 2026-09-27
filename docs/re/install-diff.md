# 원본 VM 설치와 정적 추출본 비교

작성: 최병호 · 2026-09-27 · LOGH-6. 근거는 `evidence:client` (동적 케이스 E-103~107)이다.

일본어 Windows 10 x64 격리 VM `logh7-win`에서 InstallShield를 실행했다. 게임 ISO는 사용자 소유 원본을 읽기 전용 광학 드라이브로 연결했고, 설치 전후에 파일 경로·크기·수정 시각과 레지스트리를 수집했다. 설치 후 게임 디렉터리의 모든 파일을 SHA-256으로 해시하여 오프라인 InstallShield 추출본과 상대 경로로 대조했다. 원본 파일은 수정하지 않았다. `evidence:client`

| 비교 | 결과 |
|---|---:|
| 수집 범위의 설치 전 파일 | 20,080개 |
| 수집 범위의 설치 후 파일 | 22,305개 |
| 새 파일 / 사라진 파일 / 크기 또는 수정 시각이 바뀐 파일 | 2,231 / 6 / 16개 |
| 설치 디렉터리의 파일 | 2,194개 |
| 정적 추출본과 상대 경로·SHA-256 불일치 | **0 / 2,194개** |

설치 디렉터리는 `C:\Program Files (x86)\BOTHTEC\銀河英雄伝説VII`이다. 그 밖의 새 파일에는 InstallShield 런타임·제거 정보, 시작 메뉴와 바탕화면 바로가기, Windows 검색 캐시가 있다. `HKLM\SOFTWARE\WOW6432Node\BOTHTEC\銀河英雄伝説VII\1.0`의 `install` 값은 위 경로를 가리킨다. 32비트 제거 등록에는 제품 GUID `{F5AB5818-713B-4ADF-8A93-789218A47251}`, 버전 `1.00.000`이 추가됐다. 레지스트리 덤프와 전체 차이 목록은 Git에서 제외한 `work/logh7-dynamic-p2/captures/`에 보존했다. `evidence:client` (E-105)

| 실행 파일 | 설치본 SHA-256 |
|---|---|
| `BootFirst.exe` | `23D01278CAABE2AF2C0BC240EF62742B506C1DB9484A2B380E9BD63BCA411096` |
| `exe\G7MTClient.exe` | `BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16` |
| `Gin7UpdateClient.exe` | `EA196E6EAA17BE36715132A7919C5470FF45F614E19D9E7E70CBB2C46BA0429D` |

이 비교는 **설치 디렉터리의 파일 동일성**을 확인한다. Windows 시스템 파일·레지스트리 전체를 해시 비교한 것은 아니며, 변경된 파일의 원인이 모두 게임 설치라고 단정할 수 없다. 수집 루트는 Program Files, ProgramData, System32/SysWOW64, 사용자 AppData와 바탕화면이다. 설치 후 클라이언트의 실제 실행·통신 성공도 별개다. `evidence:client` (E-105~108)

VM 스냅샷 `clean`은 게임 설치 전, `installed`는 설치 후다. 첫 라이브 스냅샷은 VirtualBox 상태 오류로 실패해 VM을 복구한 뒤 오프라인으로 `installed`를 만들었다. 이후 게스트 부팅과 실행 파일 존재를 확인했고, 정상 종료 후 `installed-verified`를 추가했다. `evidence:client` (E-108)
