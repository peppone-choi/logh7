# 서버 개발 종료 및 로컬 이관 기준점

2026-10-23 사용자의 명시적 요청에 따라 미완료 작업도 보존하기 위해 main에 통합합니다.
제품 검증 완료·릴리스·배포 승인을 뜻하지 않습니다. 기존 실패·미실행 시험 기록은 유지합니다.
이관 과정에서는 새 제품 시험, Windows 게임 실행, GCP 기동, 배포 또는 게임 초기화를 실행하지 않았습니다.

## 포함한 작업 원본

- 61b22b527996c6e1607f89ed5b12bfcffce032f5 logh/t_1cac7d32, origin/logh/t_1cac7d32
- 7fa2e1d3909a5352867693cf2fa90c7b615a539b logh/t_1fdc9614, logh/t_457362fe, origin/logh/t_457362fe
- dad51e53b7e4b19066c490182daf2c28cd0f7970 logh/t_26e5784d, origin/logh/t_26e5784d
- fc013ff9242484f816b0dabe31ef5b40dcaff869 logh/t_27bf3cdd, origin/logh/t_27bf3cdd
- ff77eaf82ec4a5faab76a2125e8661a9de091c92 logh/t_42ac6014, logh/t_d5cb69e2, logh/t_f2f9edba, origin/logh/t_42ac6014
- c164b840eee3509980d30f397c8830ed308832d0 logh/t_475fe94c, origin/logh/t_475fe94c
- 1302f752848915d235384ddc1251c54123943765 logh/t_575840f5, origin/logh/t_575840f5
- be92fd7ac8ed26e2007d18e93a46d7481466655b logh/t_5c281208, origin/logh/t_5c281208
- 290f7dfcdab15c73458779fb07af165227556bb3 logh/t_69bb83b9, origin/logh/t_69bb83b9
- 12a8e27736751a95fdb6490e922c821db6712e25 logh/t_6a6f55e7, origin/logh/t_6a6f55e7
- 03ebffd3fbc968a4fb12b80e05271c73c026b228 logh/t_6cd0726f, logh/t_99e5c709, origin/logh/t_6cd0726f
- f4ac9c1a940cd9137f6afa0ce773f3d8787a19e3 logh/t_7c0ebd0e, origin/logh/t_7c0ebd0e
- 1ede39083043b1f8bc9912d8307c83fc63b5ca66 logh/t_815cdec6, origin/logh/t_815cdec6
- e33eaf1a6b0fc8f0290a3136fc4a794af6646504 logh/t_855ffe1c, pr64, origin/logh/t_855ffe1c
- 05fe02425edfc9845203e0aac60d5a42194269bf logh/t_85d8ed69-strategy-tactical-lock, origin/logh/t_85d8ed69-strategy-tactical-lock
- b9514ccda349aefcc54587043c8de63e39d33264 logh/t_89d1b400, origin/logh/t_89d1b400
- 4e7e20fc9f297a41d45dc9fb93a1730a2df78a36 logh/t_8f86ffbc, origin/logh/t_8f86ffbc
- 5d47377d3a5428c311d1f3a31293e7b59f405676 logh/t_9e4e4fdd, pr71, origin/logh/t_9e4e4fdd
- 0cf9a425a7dc7c0cfb50d9594d03c6a4a1a9bc7b logh/t_a9838996, origin/logh/t_a9838996
- e9d4f39efd0d17e26d50250d9e85b0d1252ed95d logh/t_ba9356da, origin/logh/t_ba9356da
- 3cddd88d02b59336c8a22122a36aab1c54858dd0 logh/t_c324805e, origin/logh/t_c324805e
- 8e98a48eda5a36feb9b843cf5d748adfab337f2c logh/t_d0f94e04, origin/logh/t_d0f94e04
- fa2c5573d146d1452934a8620e54e3056eb102be logh/t_da5d08a1, origin/logh/t_da5d08a1
- 9044c60fbc3231badad69029291b90cfc7635049 logh/t_e3468230, origin/logh/t_e3468230
- c9287bd8a0992dd7e771f069291116f285a1bc9b logh/t_e8ea56d6, origin/logh/t_e8ea56d6
- c2694be690f3b091051b9d23949dffcd96d21605 logh/t_e92fde44-original-surname, origin/logh/t_e92fde44-original-surname, origin/pr79
- f36e847ff4f75e23f26c56b21ca21e9bec049090 logh/t_f13f53c5, review/t_42a7593c, origin/logh/t_f13f53c5, origin/pr/69
- f6887b457c7d3c1d6def9cead47f7df5c1e77f31 PR67:logh/t_c7b99d52
- 6c14b11cee981022986b9c75de22dc69b8b945a1 PR65:logh/t_340b1505
- 79299a50c367e34ffb1e6080bdb73ee5685b9993 PR61:logh/t_7d092afa
- d7cebbfc39e10a85075baa24199a888dfe4586ba PR59:logh/t_468f6ac5
- 56369d5168e4a8c42ac39ce619ab013f3b6e67c6 PR55:logh/t_4ce5a923
- 6c07d8a6ea00f8191951aba7431edcf2f521aaaa PR53:logh/t_bb939fa9
- 419e031d8e8d4ab60816908b94f83c8272a4f51f PR52:logh/t_392eb595
- 208a29a3bb99970545fa11d01ccad7fa7988e563 PR51:logh/t_e225ee07
- a35fe17601e7c68a5a7781512d2150114de70af2 PR50:logh/t_89cb4940

과거 대체 브랜치와 squash 병합으로 이미 반영된 계열은 회귀를 피하기 위해 재적용하지 않았으며, 전체 Git refs와 객체를 별도 오프라인 보존합니다.
