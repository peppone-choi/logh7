---
title: 격리 실행 환경 계획
author: 최병호
created: 2026-09-27
status: 사용자 결정 대기 (Linear LOGH-5)
---

# 격리 실행 환경 계획

원본 설치·클라이언트 실행은 호스트에서 하지 않는다(CLAUDE.md §4). 우선순위는 VirtualBox VM → Windows Sandbox → (불가 시 확인).

## 1. 현황 (2026-09-27 확인)

| 후보 | 상태 | 근거 |
|---|---|---|
| VirtualBox 7.0.18 | **사용 불가** — 레지스트리 `HKLM\SOFTWARE\Oracle\VirtualBox` InstallDir=`G:\VBox\` 인데 G: 드라이브 없음 | `Get-ItemProperty HKLM:\SOFTWARE\Oracle\VirtualBox` |
| 기존 VM | `E:\VM\myUbuntu`(VirtualBox, Ubuntu) 1개. Windows 게스트 없음 | `Get-ChildItem E:\VM` |
| VMware Workstation 17.6.4 | **사용 가능** (`C:\Program Files (x86)\VMware\VMware Workstation\vmrun.exe`, 실행 중 VM 0) | 설치 목록, `vmrun list` |
| Windows Sandbox | Win10 Pro라 지원 가능, **기능 꺼짐**(`WindowsSandbox.exe` 없음). 켜려면 관리자 권한 시스템 설정 변경 필요 | `Test-Path $env:windir\System32\WindowsSandbox.exe` |
| Windows 게스트 ISO | E: 드라이브에서 발견 안 됨 | `Get-ChildItem E:\ -Recurse -Depth 3 -Filter *.iso` |

## 2. 게스트 공통 규칙

- **자동 로그인 + 빈 비밀번호**(또는 매우 단순한 값). 사용자에게 로그인·비밀번호를 요구하지 않는다. 설정한 값은 이 문서에 기록한다.
- 시스템 로캘 **일본어(ja-JP)** — 원작 요구 사양이 Windows 2000/XP 일본어판(W p.6).
- 네트워크: **호스트 전용**. 외부 인터넷 차단. 스텁 서버는 호스트(또는 같은 호스트 전용망의 서버 VM)에서.
- 공유 폴더: 호스트 `E:\logh7-original\extracted\` 읽기 전용, `E:\logh7\work\vm-share\` 쓰기(결과 반출).
- 스냅샷: 설치 전 `clean`, 설치 후 `installed`.
- DirectX 8.1 이상 필요(W p.6). CD의 `DirectX9\` 재배포본 사용 가능.

## 3. 선택지별 절차 초안

### A. VirtualBox (G: 복구 시)
1. G: 드라이브 연결 확인 → `G:\VBox\VBoxManage.exe --version`
2. Windows 게스트 ISO 필요. `VBoxManage unattended install` 로 사용자 계정 + 빈 비밀번호 + 자동 로그온 구성
3. 호스트 전용 어댑터, 공유 폴더, 스냅샷

### A'. VMware Workstation (대안)
1. Windows 게스트 ISO 필요. 간이 설치(Easy Install)로 계정·자동 로그온 구성
2. 네트워크 "Host-only(VMnet1)", 공유 폴더, 스냅샷 — `vmrun` 으로 스크립트화

### B. Windows Sandbox (기능 켜기 후)
- `.wsb` 설정: `<Networking>Disable</Networking>`(첫 실행) → 스텁 연결 단계에서는 호스트 전용 대체 필요, `<MappedFolder>` 로 추출본·반출 폴더 연결, `<LogonCommand>` 로 설치 스크립트 실행.
- 창을 닫으면 초기화되므로 반복 디버깅에는 불리. 설치 결과 비교(LOGH-6)에는 충분.

## 4. 스텁 서버 접속 (LOGH-20)

- 원본 무수정: 게스트에서 `exe\G7MTClient.exe <스텁 host> 47900 <세션명> 1 dummy` 로 직접 실행한다(ADR-0002). 세션 서버 주소는 스텁이 LGLoginOK(0x7001)로 돌려준다.
- `202.8.80.179` 는 IP 리터럴이라 hosts 파일로는 바뀌지 않는다. 원본 체인(BootFirst→업데이터)을 그대로 관찰해야 할 때만 NAT 리다이렉트를 쓴다.
- 업데이터는 `update.ini [UPDATE] SERVER_ADDRESS/SERVER_PORT` 로 업데이트 서버를 스텁에 지정할 수 있다(docs/re/connection-flow.md).

## 5. 결정이 필요한 것 (사용자)

1. G: 드라이브를 다시 연결할 수 있는지(VirtualBox 복구)
2. 아니면 VMware를 쓸지, 그 경우 Windows ISO(버전·경로)
3. 또는 Windows Sandbox 기능을 켤지

