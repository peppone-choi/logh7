# 작성자: 최병호. evidence:guess — 리드 전용 배포 도구, VM 실행 검증 전.
[CmdletBinding()]
param(
    [ValidateSet('Build','Deploy','Start','Stop')][string]$Action = 'Build',
    [string]$VmName,
    [string]$GuestUser,
    [string]$PasswordFile,
    [string]$GuestDirectory = 'C:\Users\logh7\Documents\logh7-session3\server',
    [string]$GuestJavaHome,
    [string]$JavaHome = 'E:\Tools\jdk-25.0.4.1+1',
    [string]$VBoxManage = 'E:\VirtualBox\VBoxManage.exe',
    [string]$AccountsFile,
    [ValidateRange(1,65535)][int]$SessionPort = 47903,
    [string]$SessionAddress = '127.0.0.1',
    [ValidateRange(1,5)][int]$GuestControlAttempts = 3,
    [ValidateRange(1,30)][int]$RetryDelaySeconds = 3,
    [switch]$IncludeRuntime
)
$ErrorActionPreference = 'Stop'
$serverRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\server'))
$stage = Join-Path $serverRoot 'app\build\install\app'
$taskName = 'LOGH7-Server-Temporary'
function Invoke-CheckedVBox([string[]]$Arguments) {
    for ($attempt = 1; $attempt -le $GuestControlAttempts; $attempt++) {
        # Native stderr is captured rather than promoted to a PowerShell exception.
        $oldPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $output = & $VBoxManage @Arguments 2>&1
            $exitCode = $LASTEXITCODE
        } finally { $ErrorActionPreference = $oldPreference }
        if ($exitCode -eq 0) { $output | Write-Output; return }
        $transient = ($output | Out-String) -match 'VERR_(DUPLICATE|TIMEOUT)'
        if (!$transient -or $attempt -eq $GuestControlAttempts) {
            # Do not print arguments, encoded scripts, usernames, or password file contents.
            throw "guestcontrol failed (exit $exitCode, attempt $attempt/$GuestControlAttempts, transient=$transient). Inspect Guest Additions; VM restart is a lead decision."
        }
        Write-Warning "guestcontrol transient failure (exit $exitCode); retry $($attempt + 1)/$GuestControlAttempts after $RetryDelaySeconds seconds."
        Start-Sleep -Seconds $RetryDelaySeconds
    }
}
function Invoke-GuestScript([string]$Script) {
    $wrapped = "`$ErrorActionPreference='Stop'; try {`n$Script`nexit 0`n} catch { [Console]::Error.WriteLine(`$_.Exception.Message); exit 1 }"
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($wrapped))
    Invoke-CheckedVBox @('guestcontrol',$VmName,'run','--username',$GuestUser,'--passwordfile',$PasswordFile,
        '--exe','C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe','--timeout','60000','--wait-stdout','--wait-stderr',
        '--','-NoProfile','-NonInteractive','-EncodedCommand',$encoded)
}
function Quote-Literal([string]$Value) { return "'" + $Value.Replace("'", "''") + "'" }
if ($Action -in @('Build','Deploy')) {
    $env:JAVA_HOME = $JavaHome
    $env:GRADLE_USER_HOME = 'E:\Tools\gradle-home'
    $env:TEMP = 'E:\Tools\tmp'; $env:TMP = $env:TEMP
    $env:JAVA_TOOL_OPTIONS = '-Djava.io.tmpdir=E:\Tools\tmp -Duser.home=E:\Tools'
    Push-Location $serverRoot
    try {
        & .\gradlew.bat :app:installDist --offline --no-daemon
        if ($LASTEXITCODE -ne 0) { throw 'installDist failed' }
    } finally { Pop-Location }
    if ($IncludeRuntime) {
        $runtime = Join-Path $stage 'runtime'
        if (Test-Path -LiteralPath $runtime) { throw 'Runtime already exists; use the existing runtime or choose a fresh build directory.' }
        $appJars = @(Get-ChildItem -LiteralPath (Join-Path $stage 'lib') -Filter 'app-*.jar')
        if ($appJars.Count -ne 1) { throw 'Expected one application jar for runtime dependency analysis.' }
        $moduleOutput = & (Join-Path $JavaHome 'bin\jdeps.exe') --ignore-missing-deps --multi-release 25 --print-module-deps --class-path (Join-Path $stage 'lib\*') $appJars[0].FullName
        if ($LASTEXITCODE -ne 0) { throw 'jdeps failed; use an existing GuestJavaHome instead.' }
        $moduleLines = @($moduleOutput | Where-Object { $_ -match '^java\.base(,[a-zA-Z0-9.]+)*$' })
        if ($moduleLines.Count -ne 1) { throw 'Could not determine Java runtime modules.' }
        # Include the dynamically selected EC provider, which static jdeps cannot discover.
        $modules = (($moduleLines[0].Split(',') + @('jdk.crypto.ec')) | Sort-Object -Unique) -join ','
        & (Join-Path $JavaHome 'bin\jlink.exe') --add-modules $modules --output $runtime --strip-debug --no-header-files --no-man-pages
        if ($LASTEXITCODE -ne 0) { throw 'jlink failed' }
    }
    if ($Action -eq 'Build') { Write-Output $stage; return }
}
if (!$VmName -or !$GuestUser -or !(Test-Path -LiteralPath $PasswordFile -PathType Leaf)) { throw 'VmName, GuestUser and an existing PasswordFile are required.' }
if ($GuestDirectory -notmatch '^[A-Za-z]:\\[^\r\n";]+$' -or $GuestDirectory.Contains("'")) { throw 'GuestDirectory must be an absolute guest drive path without quotes.' }
$guestRootLiteral = Quote-Literal $GuestDirectory
if ($Action -eq 'Deploy') {
    if (!$AccountsFile -or !(Test-Path -LiteralPath $AccountsFile -PathType Leaf)) { throw 'Deploy requires an explicit AccountsFile.' }
    Invoke-GuestScript "`$ErrorActionPreference='Stop'; New-Item -ItemType Directory -Force -Path $guestRootLiteral | Out-Null"
    # Explicit destination file avoids VirtualBox directory-target copy failures.
    $archive = Join-Path $serverRoot 'app\build\logh7-deploy.zip'
    $entries = @(Get-ChildItem -LiteralPath $stage | Select-Object -ExpandProperty FullName)
    if ($entries.Count -eq 0) { throw 'Distribution directory is empty.' }
    Compress-Archive -LiteralPath $entries -DestinationPath $archive -Force
    $guestArchive = Join-Path $GuestDirectory 'logh7-deploy.zip'
    Invoke-CheckedVBox @('guestcontrol',$VmName,'copyto','--username',$GuestUser,'--passwordfile',$PasswordFile,
        $archive, $guestArchive)
    $guestArchiveLiteral = Quote-Literal $guestArchive
    # Retain the archive so an ambiguous guestcontrol timeout can safely retry extraction.
    Invoke-GuestScript "Expand-Archive -LiteralPath $guestArchiveLiteral -DestinationPath $guestRootLiteral -Force"
    Invoke-CheckedVBox @('guestcontrol',$VmName,'copyto','--username',$GuestUser,'--passwordfile',$PasswordFile,
        $AccountsFile, (Join-Path $GuestDirectory 'test-accounts.properties'))
    Write-Output 'Deployment copied. Use -Action Start to launch.'
    return
}
if ($Action -eq 'Stop') {
    Invoke-GuestScript "`$ErrorActionPreference='Stop'; if (Get-ScheduledTask -TaskName '$taskName' -ErrorAction SilentlyContinue) { Stop-ScheduledTask -TaskName '$taskName'; Unregister-ScheduledTask -TaskName '$taskName' -Confirm:`$false }"
    return
}
if (!$GuestJavaHome) { $GuestJavaHome = Join-Path $GuestDirectory 'runtime' }
$guestJavaLiteral = Quote-Literal (Join-Path $GuestJavaHome 'bin\java.exe')
$userLiteral = Quote-Literal $GuestUser
$sessionLiteral = Quote-Literal $SessionAddress
$launch = @"
`$ErrorActionPreference = 'Stop'
`$root = $guestRootLiteral
`$java = $guestJavaLiteral
if (!(Test-Path -LiteralPath `$java)) { throw 'Guest Java runtime is missing.' }
`$owners = @(Get-CimInstance Win32_Process -Filter "Name='explorer.exe' AND SessionId=1" | ForEach-Object { (Invoke-CimMethod -InputObject `$_ -MethodName GetOwner).User })
if ($userLiteral.Split('\')[-1] -notin `$owners) { throw 'Requested user must already have an interactive session 1.' }
`$env:LOGH7_ACCOUNTS_FILE = Join-Path `$root 'test-accounts.properties'
`$env:LOGH7_CAPTURE_DIR = Join-Path `$root 'captures'
`$env:LOGH7_BIND_ADDRESS = '127.0.0.1'
`$env:LOGH7_SESSION_ADDRESS = $sessionLiteral
`$env:LOGH7_SESSION_PORT = '$SessionPort'
`$env:TEMP = Join-Path `$root 'tmp'; `$env:TMP = `$env:TEMP
New-Item -ItemType Directory -Force -Path `$env:TEMP | Out-Null
& `$java "-Djava.io.tmpdir=`$env:TEMP" "-Duser.home=`$root" -cp "`$root\lib\*" org.logh7.app.MainKt *> (Join-Path `$root 'server.log')
exit `$LASTEXITCODE
"@
$launchEncoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($launch))
$operationId = [Guid]::NewGuid().ToString()
$register = @"
`$ErrorActionPreference='Stop'
`$existing = Get-ScheduledTask -TaskName '$taskName' -ErrorAction SilentlyContinue
if (`$existing -and `$existing.Description -ne '$operationId') { throw 'Temporary task already exists; stop it before starting.' }
if (`$existing -and `$existing.State -eq 'Running') { return }
if (`$existing -and (Get-ScheduledTaskInfo -TaskName '$taskName').LastRunTime -gt [datetime]'2000-01-01') { throw 'Task already ran and exited; inspect server.log before a new Start.' }
`$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument '-NoProfile -NonInteractive -WindowStyle Hidden -EncodedCommand $launchEncoded'
`$principal = New-ScheduledTaskPrincipal -UserId $userLiteral -LogonType Interactive -RunLevel Limited
`$settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew
if (!`$existing) { Register-ScheduledTask -TaskName '$taskName' -Description '$operationId' -Action `$action -Principal `$principal -Settings `$settings | Out-Null }
Start-ScheduledTask -TaskName '$taskName'
"@
Invoke-GuestScript $register
Write-Output 'Temporary interactive task registered; inspect guest server.log. Stop removes the task.'
