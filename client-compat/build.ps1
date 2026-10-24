param([Parameter(Mandatory=$true)][string]$OutDir)
$ErrorActionPreference = 'Stop'
$destination = [IO.Path]::GetFullPath($OutDir)
$work = [IO.Path]::GetFullPath((Join-Path (Split-Path $PSScriptRoot) 'work')) + '\'
if (!$destination.StartsWith($work, [StringComparison]::OrdinalIgnoreCase)) { throw 'Build output must be under repository work/.' }
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
$installation = & $vswhere -latest -property installationPath
$vcvars = Join-Path $installation 'VC\Auxiliary\Build\vcvars32.bat'
if (!(Test-Path -LiteralPath $vcvars)) { throw 'Existing x86 C++ build tools are required.' }
$source = Join-Path $PSScriptRoot 'planet_layout.cpp'
$testSource = Join-Path $PSScriptRoot 'guard_test.cpp'
$env:TEMP = $destination
$env:TMP = $destination
Push-Location -LiteralPath $destination
try {
    $batch = Join-Path $destination 'build-compat.cmd'
    @("@echo off", "call `"$vcvars`" >nul", 'if errorlevel 1 exit /b 1',
      "cl /nologo /LD /O2 /W4 /WX /MT /EHsc /utf-8 `"$source`" /link /OUT:dinput8.dll /INCREMENTAL:NO",
      'if errorlevel 1 exit /b 1',
      "cl /nologo /O2 /W4 /WX /MT /EHsc /utf-8 `"$testSource`" /Fe:guard-test.exe /link /DYNAMICBASE:NO /BASE:0x400000",
      'if errorlevel 1 exit /b 1',
      "guard-test.exe `"$(Join-Path $destination 'dinput8.dll')`"") |
        Set-Content -LiteralPath $batch -Encoding ascii
    & $env:ComSpec /d /c $batch
    if ($LASTEXITCODE -ne 0) { throw 'Compatibility module build failed.' }
} finally { Pop-Location }
