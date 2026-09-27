# 바이너리 인벤토리 (트랙 B: 클라이언트 정적 분석)

- 작성자: 최병호
- 작성일: 2026-09-27
- 케이스: `E:\logh7\work\logh7-client-triage` (Evidence E-010, E-022, E-025)
- 방법: 정적 분석만 수행(실행·네트워크 접속 없음). 원본은 읽기 전용이며, 분석용 사본은 `work\logh7-client-triage\ghidra\bin\`에 두었다.
- 재현: `python work\logh7-client-triage\scripts\pe_inventory.py <pe...>`, `python ...\scripts\sigscan.py <pe...>`, Ghidra `E:\logh7\tools\ghidra\run_logh7_export.ps1`
- 근거 태그: `evidence:client` = 바이너리에서 직접 관찰, `evidence:guess` = 추정

## 1. 요약

| 파일 | 역할 | 크기(B) | 빌드 시각(UTC, PE TimeDateStamp) | 컴파일러/링커 | 보호 |
|---|---|---|---|---|---|
| `ROOT\exe\G7MTClient.exe` | 게임 클라이언트 (MFC SDI + Direct3D8) | 3,956,736 | 2004-04-10 07:14:00 | MSVC 6.0 링커(6.0), VC6 컴파일러(Utc12) 위주 + VC7(Utc13) 객체 일부 | 없음 |
| `ROOT\Gin7UpdateClient.exe` | 업데이터 (MFC 다이얼로그) | 1,060,864 | 2004-04-11 06:49:44 | MSVC 6.0 (Utc12, Linker 6.0) | 없음 |
| `ROOT\BootFirst.exe` | 부트 런처 (업데이터 자기 교체 + 실행) | 40,960 | 2004-02-13 06:19:56 | MSVC 6.0 (CRT만, MFC 없음) | 없음 |
| `ISO\G7Start.exe` | CD autorun 메뉴 (설치/제거/PDF/DirectX) | 434,176 | 2004-04-11 11:55:12 | MSVC 6.0 (MFC) | 없음 |

`ROOT` = `E:\logh7-original\extracted\install\ｱﾌﾟﾘｹｰｼｮﾝ実行可能ﾌｧｲﾙ\`, `ISO` = `E:\logh7-original\extracted\iso\`.

## 2. 해시 (SHA256) `evidence:client`

| 파일 | SHA256 |
|---|---|
| G7MTClient.exe | `bd19263c10decc3d58373165a82d42a9267868400d407da87d5f4f4109ab6e16` |
| Gin7UpdateClient.exe | `ea196e6eaa17be36715132a7919c5470ff45f614e19d9e7e70cbb2c46ba0429d` |
| BootFirst.exe | `23d01278caabe2af2c0bc240ef62742b506c1db9484a2b380e9bd63bca411096` |
| G7Start.exe | `1023c4a045f184bf76ca84ab603e0c03db989799f02b701bf8dd89b21ea78f93` |

## 3. 파일별 상세

### 3.1 G7MTClient.exe `evidence:client`

- x86 PE32, GUI(subsystem 2), ImageBase `0x00400000`, EntryPoint `0x00601fbc`, OS 4.0, DllCharacteristics 0 (ASLR/NX 없음), 재배치 없음, TLS 없음, 오버레이 0, PE CheckSum 필드 0.
- Rich 헤더: Utc12_C/CPP(VC6, build 8168/8966) 다수, Masm 6.12, Linker 5.12/6.0, Utc13_C/CPP(VC 2002, build 9178) 85개, Implib 7.0 → VC6로 링크했지만 VC7으로 빌드한 정적 라이브러리(D3DX8, 추정)가 섞임.
- Ghidra 함수 수: 11,593.

| 섹션 | VA | VirtualSize | RawSize | 엔트로피 | 비고 |
|---|---|---|---|---|---|
| .text | 0x00401000 | 0x269cd5 | 0x26a000 | 6.68 | 일반 코드 수준, 패킹 흔적 없음 |
| .rdata | 0x0066b000 | 0x0f25e8 | 0x0f3000 | 7.01 | JPEG/PNG/zlib/D3DX 테이블과 SJIS 변환 테이블 때문에 높음 |
| .data | 0x0075e000 | 0x2bf4fa8 | 0x063000 | 4.43 | 가상 크기 약 46MB(정적 게임 상태·수신 버퍼 배열). 게임 문자열 대부분이 여기 있음 |
| .data1 | 0x03353000 | 0x0008e0 | 0x001000 | 2.50 | |
| .rsrc | 0x03354000 | 0x003c30 | 0x004000 | 4.96 | 언어 1041(일본어) |

- 임포트 요약: WS2_32(18: `WSAStartup socket connect gethostbyname inet_addr htons/htonl/ntohs/ntohl ioctlsocket setsockopt getsockopt send recv shutdown closesocket WSACleanup WSAGetLastError`), d3d8(`Direct3DCreate8`), DINPUT8, DSOUND(ord 11), WINMM, MSACM32, GDI32(`CreateFontA GetGlyphOutlineA ExtTextOutA GetTextExtentPoint32A` 등), USER32, KERNEL32(160), ADVAPI32(레지스트리, MFC 프로필용), IMM32(IME 입력), ole32/OLEAUT32/oledlg/OLEPRO32(MFC), comdlg32, SHELL32, WINSPOOL.
- 리소스: RT_STRING 22블록(128개, 대부분 MFC 기본 문자열), RT_DIALOG 2, RT_MENU 1, RT_ACCELERATOR 1, 아이콘·커서·비트맵, RT_VERSION.
- 정적 링크된 라이브러리(문자열 근거): MFC 4.2, zlib 1.1.3, libjpeg, libpng, D3DX8(셰이더 어셈블러 포함), `mps*`/`mt*` 네트워크 미들웨어(메시지 팩토리·스트림·TCP·Blowfish·암호 관리자). 미들웨어 이름 단서: 업데이터의 `Multiterm Http Library ver.1.0` (`evidence:client`), 제작사 추정 Multiterm (`evidence:guess`).
- MFC 레지스트리 키: `SetRegistryKey("MICROVISION")` (InitInstance `FUN_004010a0`) → `HKCU\Software\MICROVISION\...` 프로필 사용 (`evidence:client`).

### 3.2 Gin7UpdateClient.exe `evidence:client`

- ImageBase `0x00400000`, EntryPoint `0x00409a2e`, 함수 2,167개.
- 섹션: .text(엔트로피 6.60), .rdata(4.38), .data(3.46), .rsrc(6.56, 비트맵 9개 포함으로 큼).
- 임포트: WSOCK32(21: `bind listen accept`까지 포함. 다만 listen/accept 코드는 `mtTCPModule_win32::listen` 라이브러리 경로이고 업데이터 흐름에서 쓰는지는 확인되지 않음), KERNEL32(`CreateProcessA CreateMutexA GetPrivateProfile*` `WritePrivateProfileStringA MoveFileA DeleteFileA` 등), USER32(`FindWindowA`), ADVAPI32(`RegOpenKeyExA RegQueryValueExA`: IE 프록시 설정 조회 `Software\Microsoft\Windows\CurrentVersion\Internet Settings`).
- 문자열: `mpsUniMessageFactory`, `mpsClientConnection`, `mpsUpdateClientProcessor`, `mtNetStreamInput/OutputBuffer`, `mtTCPModule_win32`, HTTP 헤더(`Range bytes=%d-`, `User-Agent`, `Multiterm Http Library ver.1.0`), `update.ini`, `%sSERVER.INI`, `UPDATE.LOG`, `UpdateClient.err`, `%supd_temp_%08d`.
- `mtBlowfish`/`mpsCipherManager` 문자열이 없음 → 업데이터 프로토콜은 암호화하지 않는 것으로 추정 (`evidence:guess`, 신뢰도 medium).
- 리소스 문자열: `%supdate.ini`, `サーバーは現在メンテナンス中です。`, `圧縮ファイルの展開に失敗しました。` 등(업데이트 파일은 압축 형식. CRC32 테이블 `0x00442c3c` 존재).

### 3.3 BootFirst.exe `evidence:client`

- 함수 72개, 임포트는 KERNEL32 + `MessageBoxA`뿐.
- 동작(`FUN_00401000`): `.\Gin7UpdateClient.new`가 있으면 기존 `.exe`를 `.old`로 옮기고 `.new`를 `.exe`로 바꾼 뒤 `.\Gin7UpdateClient.exe`를 인자 없이 `CreateProcessA`로 실행하고 기다린다. 종료 코드가 1이면(업데이터가 자기 자신을 갱신한 경우) 최대 4회까지 다시 실행한다 (E-013).

### 3.4 G7Start.exe `evidence:client`

- CD autorun(`autorun.inf: open = G7Start.exe`). 메뉴: 인스톨 / 언인스톨 / PDF 매뉴얼 / 종료 (RT_STRING 102~105).
- `HKLM\SOFTWARE\BOTHTEC\銀河英雄伝説VII\1.0` 값 `Install`을 읽어 설치 여부와 `<Install>exe\G7MTClient.exe` 존재를 확인한다 (`0x00402db0`, `0x004029f5`). `WinExec("SETUP.EXE")`, `ShellExecuteA("open", 매뉴얼 PDF)`, `\DirectX9` 설치(DSETUP) 경로가 있다.
- 게임 실행 체인(BootFirst → Updater → Client)에는 관여하지 않는다 (`evidence:client`, 신뢰도 high). 설치 레지스트리는 클라이언트가 읽지 않는다(클라이언트 문자열에 `BOTHTEC` 없음).

## 4. 보호·자기검증 여부 (E-022)

| 항목 | 결과 | 근거 |
|---|---|---|
| 패커/암호화 섹션 | 없음. 섹션명 표준, .text 엔트로피 6.5~6.7, EntryPoint가 .text 안의 CRT 시작 코드 | `evidence:client` |
| 안티디버그 | `IsDebuggerPresent`는 D3DX 어설션 도우미 `FUN_005a8f3c`에서만 쓰임(디버거가 붙어 있으면 "Invoke the debugger?" 대화상자). 탐지 후 종료나 우회 동작은 없음 | `evidence:client` |
| 자기 체크섬/무결성 검사 | PE CheckSum 필드 0. 자기 파일을 열어 해시하는 코드는 찾지 못함. CRC32 테이블(`0x0067c5e0`)은 zlib/libpng용 | `evidence:client`(찾지 못함 = 정적 범위 한정), 신뢰도 medium |
| 코드 난독화 | 없음. 다만 네트워크 암호 모듈의 초기 테이블과 키 저장값은 XOR로 가려져 있음(프로토콜 문서 참조) | `evidence:client` |
| 결론 | 바이너리 문자열·상수 패치나 코드 후킹을 막는 장치는 보이지 않는다. 패치가 필요할 때 기술적 장애는 낮다 | `evidence:guess`(동적 미확인), 신뢰도 medium |

## 5. 기타 자산 시그니처 (E-025, E-027)

| 확장자 | 개수 | 시그니처/관찰 | 용도(추정) |
|---|---|---|---|
| `.dat` (MsgDat) | 22 | `HFWR` 21개, `GFWR` 1개 | 게임 텍스트(Shift_JIS), 금칙어(UTF-16). `docs\l10n\text-format.md` 참조 |
| `.tcf` | 7 | 파일 앞부분이 cp932 농담 문구, 이후 고엔트로피(7.5) | 얼굴 이미지 묶음(압축 또는 인코딩). `tcf.hed`가 (offset,size) 목록. 형식 미해결 |
| `.hed` | 1 | 0으로 시작하는 u32 쌍 목록 | `.tcf` 인덱스 |
| `.mdx` / `.mds` | 406 / 12 | 첫 dword들이 `0x01d0xxxx` 같은 메모리 주소 형태 | 자체 3D 모델(포인터 테이블을 직렬화한 형식), 클라이언트는 `.MDS` 문자열과 D3DX X-file 템플릿을 가짐. 형식 미해결 |
| `.tga` / `.bmp` / `.jpg` / `.png` | 661 / 995 / 45 / 16 | 표준 헤더 | UI·배경 이미지(일본어가 이미지에 박혀 있을 수 있음, 미측정) |
| `.ogg` / `.wav` | 7 / 13 | `OggS`, `RIFF` | BGM(Vorbis), 효과음 |
| `.VIX` | 1 | `ViX ` + 개발 경로 `W:\Gin7\...` | 이미지 뷰어 카탈로그(개발 잔재) |
| `.db` | 1 | OLE CF(`D0 CF 11 E0`) | Windows `Thumbs.db` (무관) |

## 6. 산출물 위치

- Ghidra 프로젝트: `work\logh7-client-triage\ghidra\proj\logh7.gpr` (`/bin` 폴더에 4개 프로그램)
- 내보내기: `work\logh7-client-triage\ghidra\export\` (`*.functions.tsv`, `*.imports_xref.tsv`, `*.string_xref.tsv`, `*.decomp.txt`)
- 후처리 스크립트: `E:\logh7\tools\ghidra\LoghExport.java`, 실행 스크립트 `E:\logh7\tools\ghidra\run_logh7_export.ps1`
- 보조 파이썬 스크립트: `work\logh7-client-triage\scripts\*.py`
- 참고: 같은 폴더의 `E:\logh7\tools\ghidra\ExportNetTriage.java`는 파일이 중간에서 끊겨 있어 컴파일 오류가 난다(`reached end of file while parsing`). headless 실행 로그에 오류가 찍히지만 `LoghExport.java` 실행에는 영향이 없다.
