# LOGH7 track-B static export driver (reproduces work\logh7-client-triage\ghidra\export\*).
# Static analysis only: targets are imported into a Ghidra project; nothing is executed.
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File E:\logh7\tools\ghidra\run_logh7_export.ps1 [-SkipImport]
# NOTE: analyzeHeadless.bat (cmd) splits arguments on '=', ',', ';' -> LoghExport.java uses 'key:value' and '+'.
param([switch]$SkipImport)
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:\Users\user\.jdks\openjdk-21.0.2'
$G    = 'C:\Users\user\AppData\Local\Programs\Ghidra\ghidra_12.1.2_PUBLIC\support\analyzeHeadless.bat'
$Case = 'E:\logh7\work\logh7-client-triage'
$Proj = "$Case\ghidra\proj"
$Bin  = "$Case\ghidra\bin"
$Out  = "$Case\ghidra\export"
$Scr  = 'E:\logh7\tools\ghidra'
New-Item -ItemType Directory -Force $Bin, $Out, $Proj | Out-Null

if (-not $SkipImport) {
    # copies (read-only use of originals; half-width katakana path avoided for cmd)
    $root = (Get-ChildItem E:\logh7-original\extracted\install -Directory | Where-Object Name -notlike '_*').FullName
    Copy-Item -LiteralPath "$root\exe\G7MTClient.exe", "$root\Gin7UpdateClient.exe", "$root\BootFirst.exe" -Destination $Bin -Force
    Copy-Item -LiteralPath 'E:\logh7-original\extracted\iso\G7Start.exe' -Destination $Bin -Force
    & $G $Proj logh7 -import $Bin -recursive -overwrite -analysisTimeoutPerFile 3600 -log "$Case\ghidra\import.log"
}

function Invoke-Export([string]$Prog, [string[]]$ScriptArgs, [string]$Log) {
    & $G $Proj logh7/bin -process $Prog -noanalysis -readOnly -scriptPath $Scr -postScript LoghExport.java $Out @ScriptArgs *> "$Case\ghidra\$Log"
}

# (a)-(d): function list, socket/API call sites, string xrefs, decompile seeds + 2 caller levels
Invoke-Export G7MTClient.exe @("targets:$Case\ghidra\targets_client.txt", 'depth:2', 'max:900',
    'apis:CreateFontA+GetGlyphOutlineA+GetPrivateProfileStringA+GetPrivateProfileIntA+WritePrivateProfileStringA+RegOpenKeyExA+RegQueryValueExA+RegCreateKeyExA+GetCommandLineA') export_client.log
Invoke-Export Gin7UpdateClient.exe @("targets:$Case\ghidra\targets_updater.txt", 'depth:2', 'max:600',
    'apis:CreateProcessA+GetPrivateProfileStringA+GetPrivateProfileIntA+WritePrivateProfileStringA+CreateMutexA+FindWindowA+GetCommandLineA+RegQueryValueExA+RegOpenKeyExA+MoveFileA+DeleteFileA') export_updater.log
Invoke-Export BootFirst.exe @('depth:1', 'max:100', 'range:0x00401000-0x00401400') export_bootfirst.log
Invoke-Export G7Start.exe @('depth:2', 'max:200', 'apis:CreateProcessA+WinExec+ShellExecuteA+RegOpenKeyExA+RegQueryValueExA+RegSetValueExA+RegCreateKeyExA+GetCommandLineA') export_g7start.log

# focused client dumps (whole address ranges / vtable methods Ghidra left un-functioned -> mkfunc, in-memory only)
Invoke-Export G7MTClient.exe @('nolists', 'tag:mps', 'depth:0', 'max:3000', 'range:0x00401000-0x00406000', 'range:0x00610000-0x00616000', 'range:0x004ab000-0x004c0000') export_client_mps.log
Invoke-Export G7MTClient.exe @('nolists', 'tag:cipher', 'depth:1', 'max:200', 'mkfunc', "targets:$Case\ghidra\targets_cipher.txt", 'range:0x00645000-0x00646400', 'decomp:0x006457e8+0x006459e8+0x00645a80+0x00645ce0+0x00645db0+0x00645f70') export_client_cipher.log
Invoke-Export G7MTClient.exe @('nolists', 'tag:codec', 'depth:0', 'max:100', 'mkfunc', 'decomp:0x006450e0+0x00645130+0x00645150+0x00645180+0x006452f0+0x00645660+0x006457e8+0x006459e8+0x00645a80+0x00645ce0+0x00645db0+0x00645f70') export_client_codec.log
Invoke-Export G7MTClient.exe @('nolists', 'tag:blowfish', 'depth:0', 'max:200', 'mkfunc', 'decomp:0x00613fc0+0x00614000+0x00614040+0x006140c0+0x00614100+0x00614460+0x006147c0+0x00614810+0x006148a0+0x00613ad0+0x00613f20+0x00613f60+0x00614060') export_client_bf.log
Invoke-Export G7MTClient.exe @('nolists', 'tag:login', 'depth:0', 'max:200', 'mkfunc', 'decomp:0x004ac6c0+0x004ac700+0x004adeb0+0x004adf60+0x004ae000') export_client_login.log
Invoke-Export G7MTClient.exe @('nolists', 'tag:ser', 'depth:0', 'max:200', 'mkfunc', 'decomp:0x00402880+0x004029f0+0x00402b60+0x00403c60+0x00403e30+0x00403f70+0x00404800+0x004ac070+0x004ae050+0x004021e0+0x00404210+0x00405a50+0x00407920+0x004066f0+0x0040a0f0+0x00611bc0+0x00611c20') export_client_ser.log
Invoke-Export G7MTClient.exe @('nolists', 'tag:msgreg', 'depth:0', 'max:200', 'decomp:0x0044e440+0x0040a700+0x004a49c0+0x00439130+0x00481d40+0x0044d8f0+0x0044b170+0x00491e10+0x0055a800+0x0055b790+0x0043e590+0x0043f0c0+0x00407600+0x00437e00+0x00447400+0x00403a80+0x004aa990+0x004abd90') export_client_msgreg.log
Invoke-Export G7MTClient.exe @('nolists', 'tag:msgreg2', 'depth:0', 'max:200', 'decomp:0x00407670+0x0044e4b0+0x00403ae0+0x00404800+0x00405100+0x004047d0') export_client_msgreg2.log
Invoke-Export G7MTClient.exe @('nolists', 'tag:msghdr', 'depth:0', 'max:100', 'mkfunc', 'decomp:0x00404120+0x00404180+0x004041a0+0x00404200+0x004042a0+0x00402160+0x00402190+0x004021b0+0x004021c0+0x00402250+0x00404610+0x00610d70+0x00610de0+0x00612510+0x00611f90') export_client_msghdr.log
Invoke-Export G7MTClient.exe @('nolists', 'tag:msghdr2', 'depth:0', 'max:100', 'mkfunc', 'decomp:0x004045d0+0x00404610+0x004021e0+0x00404980+0x004044f0+0x00404530+0x0060fe80+0x00612640') export_client_msghdr2.log
Invoke-Export G7MTClient.exe @('nolists', 'tag:msgdat', 'depth:0', 'max:100', 'mkfunc', 'decomp:0x00522060+0x00522310+0x005232d0+0x00521f80+0x004e9bb0') export_client_msgdat.log
# updater focused dumps
Invoke-Export Gin7UpdateClient.exe @('nolists', 'tag:launch', 'depth:0', 'max:100', 'mkfunc', 'decomp:0x00404a80+0x00406ed0+0x00407300+0x00404c30+0x00405550+0x00405030+0x00405060') export_updater_launch.log
Invoke-Export Gin7UpdateClient.exe @('nolists', 'tag:upd', 'depth:0', 'max:600', 'range:0x0041c000-0x00422000') export_updater_upd.log
Write-Host "done -> $Out"
