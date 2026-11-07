# 플레이어 가입과 운영 계정 관리

작성: Peppone Choi ([peppone-choi](https://github.com/peppone-choi))

가입 페이지에서 만든 아이디와 비밀번호를 게임 클라이언트의 로그인 화면에 입력한다. 런처에서 시험 서버 주소를 `127.0.0.1`, 로그인 포트를 `51140`으로 설정한다.

## Windows에서 실행

JDK 25와 Node.js 20.9 이상을 사용한다. 아래 명령은 저장소 루트에서 각각 별도 PowerShell로 실행한다. 시험용 포트는 게임 로그인 `51140`, 운영 `51141`, 업데이트 `51142`, 세션 `51143`, 가입 내부 API `51144`, 웹 `48174`다.

첫 번째 PowerShell에서 새 시험 저장소와 운영 토큰을 준비하고 게임 서버를 실행한다.

```powershell
$state = Join-Path (Get-Location).Path 'work\registration-local'
New-Item -ItemType Directory -Force $state | Out-Null
$env:JAVA_HOME = 'E:\Tools\jdk-25.0.4.1+1'
$env:GRADLE_USER_HOME = 'E:\Tools\gradle-home'
$env:TEMP = 'E:\Tools\tmp'
$env:TMP = $env:TEMP
$env:LOGH7_ENV_FILE = Join-Path $state 'server.env'
$env:LOGH7_ACCOUNTS_FILE = Join-Path $state 'accounts.properties'
$env:LOGH7_OPS_TOKEN_FILE = Join-Path $state 'ops-token.txt'
$env:LOGH7_OPS_AUDIT_FILE = Join-Path $state 'ops-audit.jsonl'
if (!(Test-Path $env:LOGH7_ACCOUNTS_FILE)) {
    New-Item -ItemType File $env:LOGH7_ACCOUNTS_FILE | Out-Null
}
if (!(Test-Path $env:LOGH7_OPS_TOKEN_FILE)) {
    [IO.File]::WriteAllText($env:LOGH7_OPS_TOKEN_FILE, [guid]::NewGuid().ToString('N'))
}
$env:LOGH7_BIND_ADDRESS = '127.0.0.1'
$env:LOGH7_OPS_BIND = '127.0.0.1'
$env:LOGH7_GAME_PORT = '51140'
$env:LOGH7_OPS_PORT = '51141'
$env:LOGH7_UPDATE_PORT = '51142'
$env:LOGH7_SESSION_PORT = '51143'
$env:LOGH7_REGISTRATION_PORT = '51144'
Set-Location server
.\gradlew.bat :app:installDist
if ($LASTEXITCODE -eq 0) { .\app\build\install\app\bin\app.bat }
```

두 번째 PowerShell에서 가입 웹을 실행한다.

```powershell
$env:npm_config_cache = 'E:\Tools\npm-cache'
$env:TEMP = 'E:\Tools\tmp'
$env:TMP = $env:TEMP
$env:NEXT_TELEMETRY_DISABLED = '1'
$env:LOGH7_REGISTRATION_PORT = '51144'
Set-Location web
npm.cmd ci
if ($LASTEXITCODE -eq 0) { npm.cmd run dev }
```

- 플레이어 가입은 [http://127.0.0.1:48174/](http://127.0.0.1:48174/)에서 연다.
- 운영 계정 관리는 [http://127.0.0.1:51141/admin](http://127.0.0.1:51141/admin)의 「계정」에서 한다. 토큰으로 접속할 때는 Basic 인증의 사용자명에 임의의 값, 비밀번호에 `work\registration-local\ops-token.txt`의 값을 입력한다. 변경 폼의 운영자 이름은 감사 기록에 남는다.
- 종료할 때는 두 PowerShell에서 각각 `Ctrl+C`를 누른다.

## 계정 처리

가입과 운영 계정 관리는 같은 게임 서버 프로세스의 계정 파일을 공유한다. 가입 직후 재시작 없이 게임에 로그인하고, 인증된 운영자는 기존 화면에서 새 게임 계정을 조회·재설정·삭제할 수 있다. 비밀번호는 기존 방식인 PBKDF2 해시로 저장한다. 등록 요청이 중복되면 먼저 저장한 계정이 유지된다.

가입 기능은 `LOGH7_REGISTRATION_PORT`를 설정하면 켜진다. 게임 계정 파일, 운영 인증과 감사 파일이 필요하다. 운영 토큰·운영자 자격 증명은 별도 파일로 관리하며 웹 프로세스에는 가입 API 포트만 전달한다. 계정 파일 하나당 게임 서버 프로세스 하나를 사용한다.

가입을 켠 상태에서 운영자가 삭제한 ID는 예약된다. 목록과 로그인에서 사라지며, 같은 이름을 되살릴 수 있는 경로는 인증된 운영자의 계정 설정이다. 예약 기록은 가입을 켠 뒤의 삭제부터 쌓이므로, 과거에 지운 ID와 캐릭터가 남아 있는 파일 대신 위 절차의 새 시험 저장소를 사용한다.

## 확인 결과

헤드리스 Chrome에서 키보드 이동·포커스, 비밀번호 표시, 320px 및 글자 200%, 빈 값·형식 오류·중복·제출 대기·성공을 확인했다. 초기·오류·성공 화면의 axe 검사에서 해당 WCAG 규칙 위반은 0건이었다. 실제 격리 서버에서 웹 가입 직후 기존 게임 게이트웨이의 암호화 요청이 성공(`0x7001`)했고, 틀린 비밀번호는 거절(`0x7002`)됐다. 운영 화면의 무인증 차단과 인증 후 조회·재설정·삭제, 삭제한 ID의 재가입 거절도 확인했다. 원본 클라이언트 UI와 실제 스크린리더 실기는 미확인이다.
