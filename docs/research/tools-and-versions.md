# 도구·서버 스택 최신 버전과 설치 방법 (Windows 10 x64 기준)

- 작성자: 최병호
- 작성일: 2026-09-27
- 버전·URL 확인 날짜: 2026-09-27 (GitHub Releases API, 공식 다운로드 페이지, Adoptium API, nodejs.org dist 인덱스, postgresql.org versions.json 조회)
- 근거 태그: `evidence:web`(공식 배포처라도 게임과 무관한 제3자 자료이므로 web으로 표기), `evidence:guess`(추정·일반 관행)
- 설치 위치 기본안: `E:\Tools\<도구명>-<버전>\` (포터블 zip은 그 폴더에 압축 해제)
- 이 문서는 조사 결과만 담는다. **실제 다운로드·설치는 하지 않았다.**

---

## 1. 분석 도구 (항목 3)

| 도구 | 최신 버전(공개일) | 공식 다운로드 | 포터블 / `E:\Tools` 설치 | 라이선스 | 근거 |
|---|---|---|---|---|---|
| Ghidra | **12.1.4** (2026-09-21), 파일 `ghidra_12.1.4_PUBLIC_20260921.zip` | https://github.com/NationalSecurityAgency/ghidra/releases | 설치 프로그램 없음, zip을 원하는 곳에 풀면 됨 → `E:\Tools\ghidra_12.1.4_PUBLIC` 가능. 기존 설치 위에 덮어 풀지 말 것 | Apache-2.0 | evidence:web |
| ↳ Ghidra 요구사항 | JDK **21 이상 64-bit**(JDK 25 LTS 사용 가능), 디버거·PyGhidra용 Python 3.9~3.14 | GettingStarted.md(12.1.4 태그) https://github.com/NationalSecurityAgency/ghidra/blob/Ghidra_12.1.4_build/GhidraDocs/GettingStarted.md | `JAVA_HOME`이 PATH보다 우선 | — | evidence:web |
| x64dbg (x32dbg 포함) | **2026.05.27** (스냅샷 `snapshot_2026-05-27_12-11.zip`) | https://github.com/x64dbg/x64dbg/releases | zip 포터블 → `E:\Tools\x64dbg` 가능. 2004년 클라이언트는 32비트일 가능성이 높아 `release\x32\x32dbg.exe` 사용 예상 | GPL-3.0(일부 수정된 문구) | evidence:web / 32비트 여부 evidence:guess |
| Wireshark (tshark 포함) | **4.6.9** (git 태그 2026-09-23). 개발판 4.7.4rc0 별도 | https://www.wireshark.org/download.html | Windows x64 설치 관리자(tshark 포함) 또는 **Windows x64 PortableApps** 판 제공 → 포터블판은 `E:\Tools\WiresharkPortable` 가능. 설치판 경로 지정은 설치 마법사에서 가능할 것으로 추정 | GPL-2.0 | evidence:web / 설치 경로 evidence:guess |
| Npcap | **1.89** (2026-09-12) | https://npcap.com/#download | 드라이버라 포터블 불가. 설치 경로 변경은 확인 못함(기본 `C:\Program Files\Npcap`) | 무료판: 최대 5대 사용, Nmap·Wireshark 전용이면 대수 무제한, **재배포 불가**(OEM 별도) | evidence:web / 경로 evidence:guess |
| Detect It Easy (DIE) | **3.21** (2026-04-21) | https://github.com/horsicq/DIE-engine/releases | `die_win64_portable_3.21_x64.zip`(32비트 `die_win32_portable_3.21_x86.zip`도 있음) → `E:\Tools\die_3.21` 가능 | MIT | evidence:web |
| 7-Zip | **26.03** (2026-09-03) | https://www.7-zip.org/download.html (GitHub 미러 https://github.com/ip7z/7zip/releases) | x64 `.exe`/`.msi` 설치판(경로 선택 가능) 또는 **7-Zip Extra**(독립 콘솔 7za) 포터블 | 대부분 GNU LGPL, 일부 BSD 3/2-clause, 일부 unRAR 제한 | evidence:web |
| unshield (InstallShield CAB 추출) | **1.6.2** (2025-04-03) | https://github.com/twogood/unshield/releases | GitHub 릴리스에 **Windows 바이너리 없음**(소스만). 방법: ① WSL Ubuntu에서 패키지 설치 ② CMake+zlib로 직접 빌드 ③ UniExtract2에 포함된 unshield.exe(1.4대) 사용. InstallShield **v5 이상** CAB 지원 | MIT | evidence:web / ① evidence:guess |
| i6comp | 0.2 (fOSSiL, Morlac 작) — **공식 배포처 찾지 못함**(원래 FTP 배포, UniExtract2 도움 도구 목록에 출처 기재) | UniExtract2 `docs/helper_binaries_info.txt` https://github.com/Bioruebe/UniExtract2/blob/master/docs/helper_binaries_info.txt | UniExtract2에 동봉 | "Open Source"로 기재 | evidence:web |
| ↳ 대체: Universal Extractor 2 | **2.0.0-rc.3** (2020-08-24, 이후 정식판 없음) | https://github.com/Bioruebe/UniExtract2/releases | 설치 불필요형 배포(일부 추출기는 흔적 남김). InstallShield `.cab/.exe` 지원, 내부에 i6comp·IsXunpack·unshield 포함 | GPL-2.0 | evidence:web |
| Resource Hacker | **5.2.8** (2025-03-06) | https://www.angusj.com/resourcehacker/ | ZIP 설치판(3.3MB) 제공 → 포터블 가능 | 프리웨어. 사전 승인 없는 재배포 금지, 불법 수정 용도 금지 조건 | evidence:web |
| ImHex | **1.38.1** (2025-12-21, 이후 정식판 없음·나이틀리만) | https://github.com/WerWolv/ImHex/releases | `imhex-1.38.1-Windows-Portable-x86_64.zip`(GPU 문제 시 `-NoGPU-` 판) → `E:\Tools\ImHex` 가능 | GPL-2.0 | evidence:web |
| Frida | **17.19.0** (2026-09-25) | https://github.com/frida/frida/releases , CLI는 PyPI `frida-tools` | Python 가상환경을 `E:\Tools\frida-venv`에 만들고 `pip install frida-tools` 하는 방식이면 경로 지정 가능. Windows용 gadget/devkit도 릴리스 자산으로 제공 | wxWindows Library Licence 3.1 | evidence:web / venv 방식 evidence:guess |
| VirtualBox | **7.2.20** (2026-09-22) | https://www.virtualbox.org/wiki/Downloads | 설치형(MSI 기반). VM 저장 폴더는 `E:\Tools\` 또는 별도 드라이브로 지정 가능 | 본체 GPLv3, Extension Pack은 PUEL(개인·교육 무료) | evidence:web / 경로 evidence:guess |
| ↳ VirtualBox 호스트/게스트 | 호스트: "Windows 10 with x86_64 processors" 지원. 게스트: Windows 10 32/64-bit 정식 지원(Premier) | https://www.virtualbox.org/manual/topics/Introduction.html , https://www.virtualbox.org/manual/topics/installation.html | Hyper-V(Windows Sandbox·WSL2·Docker)가 켜져 있으면 성능 저하 가능 | — | evidence:web / 성능 evidence:guess |
| Windows Sandbox | Windows 기능(별도 버전 없음) | https://learn.microsoft.com/en-us/windows/security/application-security/application-isolation/windows-sandbox/windows-sandbox-install | Windows 10 **1903 이상** Pro/Enterprise/Education(Home 불가), AMD64, BIOS 가상화, RAM 4GB(권장 8GB), 디스크 1GB, 2코어(권장 4코어). 활성화: `Enable-WindowsOptionalFeature -FeatureName "Containers-DisposableClientVM" -All -Online`. 네트워크 기본 켜짐(.wsb로 끌 수 있음), 동시에 1개만 실행, 닫으면 전부 삭제 | Windows Pro 라이선스에 포함 | evidence:web |

참고(환경): 이 PC는 Windows 10 Pro 10.0.19045(22H2)라 Windows Sandbox·Docker Desktop 요구 조건을 충족한다. PATH에 Ghidra 12.1.2, DIE(winget), x64dbg, Tailscale 흔적이 이미 있다(내용은 열람하지 않음). evidence:guess(환경 변수 관찰)

---

## 2. 서버 스택 (항목 4)

| 구성 요소 | 최신 안정 버전(공개일) | 비고 | 공식 URL | 근거 |
|---|---|---|---|---|
| Kotlin | **2.4.20** (2026-09-07). 2.5.0-Beta1(2026-09-23), 2.5.0 정식은 2026-12 예정 | Apache-2.0 | https://kotlinlang.org/docs/releases.html , https://github.com/JetBrains/kotlin/releases | evidence:web |
| Spring Boot | **4.1.1** (2026-08-20). 유지 라인 4.0.8(2026-08-21), 3.5.16(2026-06-25). 4.2.0-M2(2026-09-24) | 4.1.1은 Java 17 이상, Java 26까지 호환, Spring Framework 7.0.9+, Gradle 8.14+/9.x | https://docs.spring.io/spring-boot/system-requirements.html , https://github.com/spring-projects/spring-boot/releases | evidence:web |
| Ktor | **3.6.0** (2026-09-18) | Apache-2.0 | https://github.com/ktorio/ktor/releases | evidence:web |
| Netty | **4.2.18.Final** (2026-09-09). 구 라인 4.1.138.Final(같은 날) | Apache-2.0 | https://github.com/netty/netty/releases | evidence:web |
| JDK LTS | **25** (최신 LTS). Temurin 25.0.4.1+1 (2026-08-21). 최신 기능판은 27(Temurin 27+35, 2026-09-25, 비LTS) | Adoptium API `most_recent_lts: 25` | https://api.adoptium.net/v3/info/available_releases , https://adoptium.net/temurin/releases/ | evidence:web |
| Node.js LTS | **24.x "Krypton"** Active LTS, 최신 v24.21.0 (2026-09-07). 22.x "Jod"는 유지보수 LTS(v22.23.3, 2026-09-23). 26.x(v26.10.0)는 2026-10-28 LTS 전환 예정 | | https://nodejs.org/dist/index.json , https://github.com/nodejs/Release/blob/main/schedule.json | evidence:web |
| TypeScript | **7.0.2** (2026-08-20, 네이티브 컴파일러 세대). 6.x 마지막 6.0.3 (2026-04-16) | Apache-2.0 | https://github.com/microsoft/TypeScript/releases | evidence:web |
| PostgreSQL | **18.6** (2026-08-13, 현행 메이저). 19는 Beta 4(2026-09-24) 단계. 14는 2026-11-12 지원 종료 | | https://www.postgresql.org/ , https://www.postgresql.org/versions.json | evidence:web |
| Redis | **8.10.2** (2026-09-17) | Redis 8부터 RSALv2 / SSPLv1 / **AGPLv3** 중 선택하는 3중 라이선스 | https://github.com/redis/redis/releases | evidence:web |
| Valkey (Redis 대안) | **9.1.2** (2026-09-01). 9.0.6, 8.1.10 유지. 9.2.0-rc1 | BSD-3-Clause | https://github.com/valkey-io/valkey/releases | evidence:web |
| Docker Desktop | **4.92.0** (2026-09-21) | Windows 10 64-bit Pro/Enterprise/Education **22H2(19045)**, WSL 2.1.5 이상, RAM 8GB, BIOS 가상화. 개인·소규모(직원 250명 미만 **그리고** 매출 1천만 달러 미만)·교육·비상업 오픈소스는 무료. 설치 경로 `--installation-dir=<경로>`, WSL 데이터 `--wsl-default-data-root=<경로>` 지정 가능 | https://docs.docker.com/desktop/release-notes/ , https://docs.docker.com/desktop/setup/install/windows-install/ | evidence:web |

---

## 3. GitHub 용량 제한 (항목 4)

| 항목 | 값 | 출처 | 근거 |
|---|---|---|---|
| 일반 파일 경고 | 50 MiB 초과 시 경고 | https://docs.github.com/en/repositories/working-with-files/managing-large-files/about-large-files-on-github | evidence:web |
| 일반 파일 차단 | **100 MiB 초과 파일은 push 차단** | 같은 문서 | evidence:web |
| 브라우저 업로드 | 25 MiB 이하 | 같은 문서 | evidence:web |
| 저장소 권장 크기 | 1 GB 미만 권장, 5 GB 미만 강력 권장 | 같은 문서 | evidence:web |
| Git LFS 무료 한도(개인 Free/Pro) | 저장 **10 GiB**, 대역폭 **10 GiB**/월. 예산 $0이면 초과 시 그 달 남은 기간 사용 차단 | https://docs.github.com/en/billing/concepts/product-billing/git-lfs | evidence:web |
| Git LFS 파일당 최대 | Free·Pro 2 GB, Team 4 GB, Enterprise Cloud 5 GB | https://docs.github.com/en/repositories/working-with-files/managing-large-files/about-git-large-file-storage | evidence:web |
| Release 자산 | 파일당 **2 GiB 미만**, 릴리스당 자산 1,000개까지, 릴리스 전체 크기·대역폭 제한 없음 | https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases | evidence:web |

프로젝트 적용 메모(evidence:guess):
- 원본 클라이언트(CD 이미지·추출물)와 `G7UPD*.exe` 같은 저작물은 공개 저장소에 올리지 않는 편이 안전하다. 해시·경로 목록만 커밋.
- 100 MiB를 넘는 분석 산출물(Ghidra 프로젝트 등)은 저장소 밖(`E:\Tools`, 별도 드라이브)에 둔다.

---

## 4. 설치 순서 제안 (evidence:guess)
1. JDK 25(Temurin zip) → `E:\Tools\jdk-25.0.4.1+1`, `JAVA_HOME` 지정.
2. Ghidra 12.1.4 zip → `E:\Tools\ghidra_12.1.4_PUBLIC`.
3. x64dbg, DIE, ImHex, Resource Hacker, 7-Zip Extra → 각각 `E:\Tools\<이름>` 포터블.
4. Wireshark(포터블 또는 설치판) + Npcap(설치판, 드라이버).
5. InstallShield 추출: UniExtract2(포터블) 또는 WSL의 unshield.
6. 동적 분석 격리: Windows Sandbox(가볍게) 또는 VirtualBox 7.2.20(스냅샷 필요 시). 두 가지를 같이 쓰면 Hyper-V 공존 문제 확인.
