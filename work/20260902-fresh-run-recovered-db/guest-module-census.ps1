param([Parameter(Mandatory=$true)][int]$ExpectedPid,[Parameter(Mandatory=$true)][string]$ReceiptPath)
$ErrorActionPreference='Continue'
$ps32='C:\Windows\SysWOW64\WindowsPowerShell\v1.0\powershell.exe'
$cmd="`$p=Get-Process -Id $ExpectedPid; `$p.Modules | ForEach-Object { `$_.ModuleName + '|' + `$_.FileName } "
$mods=@(& $ps32 -NoProfile -NonInteractive -Command $cmd 2>&1 | ForEach-Object { [string]$_ })
$tl=@(tasklist /m /fi "PID eq $ExpectedPid" 2>&1 | ForEach-Object { [string]$_ })
$dwm=$null; try { Add-Type -TypeDefinition 'using System;using System.Runtime.InteropServices;public static class DwmQ{[DllImport("dwmapi.dll")]public static extern int DwmIsCompositionEnabled(out bool e);}' -ErrorAction SilentlyContinue; $e=$false; [void][DwmQ]::DwmIsCompositionEnabled([ref]$e); $dwm=$e } catch {}
$mon=@(Get-CimInstance Win32_DesktopMonitor | ForEach-Object { [ordered]@{ name=$_.Name; status=$_.Status; avail=$_.Availability; w=$_.ScreenWidth; h=$_.ScreenHeight } })
$pw=@(powercfg /requests 2>&1 | ForEach-Object { [string]$_ } | Select-Object -First 30)
$disp=@(Get-CimInstance -Namespace root\wmi -ClassName WmiMonitorBasicDisplayParams -ErrorAction SilentlyContinue | ForEach-Object { [ordered]@{ active=$_.Active; inst=$_.InstanceName } })
$env32=@(& $ps32 -NoProfile -NonInteractive -Command "(Get-CimInstance Win32_Process -Filter 'ProcessId=$ExpectedPid').CommandLine" 2>&1 | ForEach-Object { [string]$_ })
$res=[ordered]@{ modules32=@($mods); tasklist=@($tl); dwmComposition=$dwm; monitors=@($mon); wmiMonitorActive=@($disp); powerRequests=@($pw); commandLine=@($env32) }
[IO.File]::WriteAllText($ReceiptPath,($res|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false)); exit 0
