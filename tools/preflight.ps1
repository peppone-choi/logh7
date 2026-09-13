Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Resolve-Executable {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Name,
        [string[]] $Candidates = @()
    )

    foreach ($candidate in $Candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            return [IO.Path]::GetFullPath($candidate)
        }
    }

    $command = Get-Command -Name $Name -CommandType Application -ErrorAction SilentlyContinue
    if ($command -and $command.Source -and (Test-Path -LiteralPath $command.Source -PathType Leaf)) {
        return [IO.Path]::GetFullPath($command.Source)
    }

    throw "missing $Name executable; install it or add it to PATH"
}

function Invoke-Executable {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,
        [string[]] $Arguments = @()
    )

    $lines = @(& $Path @Arguments 2>&1 | ForEach-Object { $_.ToString() })
    if ($LASTEXITCODE -ne 0) {
        $details = (($lines -join ' ') -replace '\s+', ' ').Trim()
        throw "executable failed: $Path ($details)"
    }
    return ($lines -join "`n").Trim()
}

function Require-ExactVersion {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Tool,
        [Parameter(Mandatory = $true)]
        [string] $Actual,
        [Parameter(Mandatory = $true)]
        [string] $Expected
    )

    if ($Actual -ne $Expected) {
        throw "$Tool requires version $Expected; found $Actual"
    }
}

function Find-VisualStudioDevCmd {
    $vswhereCandidates = @(
        'C:\Program Files (x86)\Microsoft Visual Studio\Installer\vswhere.exe'
    )
    $vswhere = Resolve-Executable -Name 'vswhere.exe' -Candidates $vswhereCandidates
    $installationPath = Invoke-Executable -Path $vswhere -Arguments @(
        '-latest',
        '-products', 'Microsoft.VisualStudio.Product.Community',
        '-requires', 'Microsoft.VisualStudio.Component.VC.Tools.x86.x64',
        '-property', 'installationPath'
    )
    if (-not $installationPath) {
        throw 'missing Visual Studio installation with the C++ toolset'
    }

    $devCmd = Join-Path ($installationPath -split "`n" | Select-Object -First 1) 'Common7\Tools\VsDevCmd.bat'
    if (-not (Test-Path -LiteralPath $devCmd -PathType Leaf)) {
        throw "missing Visual Studio developer command file: $devCmd"
    }
    return [IO.Path]::GetFullPath($devCmd)
}

try {
    $cmakePath = Resolve-Executable -Name 'cmake.exe' -Candidates @(
        'C:\Program Files\CMake\bin\cmake.exe'
    )
    $ninjaPath = Resolve-Executable -Name 'ninja.exe' -Candidates @(
        (Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Links\ninja.exe')
    )
    $dotnetPath = Resolve-Executable -Name 'dotnet.exe'
    $dockerPath = Resolve-Executable -Name 'docker.exe' -Candidates @(
        'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
    )
    $gitCandidates = @()
    if ($env:CODEX_GIT_PATH) {
        $gitCandidates += $env:CODEX_GIT_PATH
    }
    $gitCandidates += Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\native\git\cmd\git.exe'
    $gitPath = Resolve-Executable -Name 'git.exe' -Candidates $gitCandidates

    $powershellPath = (Get-Process -Id $PID).Path
    if (-not $powershellPath) {
        throw 'missing current PowerShell process path'
    }
    $powershellPath = [IO.Path]::GetFullPath($powershellPath)

    $cmakeOutput = Invoke-Executable -Path $cmakePath -Arguments @('--version')
    $ninjaOutput = Invoke-Executable -Path $ninjaPath -Arguments @('--version')
    $dotnetOutput = Invoke-Executable -Path $dotnetPath -Arguments @('--version')
    $dockerOutput = Invoke-Executable -Path $dockerPath -Arguments @('version', '--format', '{{.Server.Version}}')
    $gitOutput = Invoke-Executable -Path $gitPath -Arguments @('--version')

    if ($cmakeOutput -notmatch '(?m)^cmake version\s+([0-9]+\.[0-9]+\.[0-9]+)') {
        throw "unable to read CMake version from $cmakePath"
    }
    $cmakeVersion = $Matches[1]
    $ninjaVersion = ($ninjaOutput -split "`n" | Select-Object -First 1).Trim()
    $dotnetVersion = ($dotnetOutput -split "`n" | Select-Object -First 1).Trim()
    $dockerVersion = ($dockerOutput -split "`n" | Select-Object -First 1).Trim()
    if ($gitOutput -notmatch 'git version\s+([^\s]+)') {
        throw "unable to read Git version from $gitPath"
    }
    $gitVersion = $Matches[1]

    Require-ExactVersion -Tool 'CMake' -Actual $cmakeVersion -Expected '4.4.2'
    Require-ExactVersion -Tool 'Ninja' -Actual $ninjaVersion -Expected '1.13.2'
    Require-ExactVersion -Tool '.NET SDK' -Actual $dotnetVersion -Expected '10.0.301'
    if ($dockerVersion -notmatch '^([0-9]+)\.([0-9]+)\.([0-9]+)') {
        throw "unable to read Docker Server version from $dockerPath"
    }
    $dockerSemanticVersion = [version]::new([int]$Matches[1], [int]$Matches[2], [int]$Matches[3])
    if (($dockerSemanticVersion.Major -ne 28) -or ($dockerSemanticVersion -lt [version]'28.3.0')) {
        throw "Docker Server requires major version 28 at least 28.3.0; found $dockerVersion"
    }
    if ($PSVersionTable.PSVersion.Major -lt 7) {
        throw "PowerShell requires major version 7 or newer; found $($PSVersionTable.PSVersion)"
    }

    $devCmdPath = Find-VisualStudioDevCmd
    $msvcCommand = 'call "' + $devCmdPath + '" -arch=x64 -host_arch=x64 >nul && where cl && cl'
    $msvcOutput = Invoke-Executable -Path 'cmd.exe' -Arguments @('/d', '/s', '/c', $msvcCommand)
    if ($msvcOutput -notmatch '(?m)^(?<clpath>[A-Za-z]:\\.*\\cl\.exe)\s*$') {
        throw 'unable to resolve cl.exe after invoking Visual Studio VsDevCmd.bat'
    }
    $clPath = [IO.Path]::GetFullPath($Matches['clpath'])
    if ($msvcOutput -notmatch 'Compiler Version\s+(?<version>[0-9]+\.[0-9]+\.[0-9]+)') {
        throw "unable to read MSVC version from $clPath"
    }
    $msvcVersion = $Matches['version']
    if (-not $msvcVersion.StartsWith('19.51.')) {
        throw "MSVC requires version 19.51; found $msvcVersion"
    }

    $result = [ordered]@{
        cmake      = [ordered]@{ path = $cmakePath; version = $cmakeVersion }
        ninja      = [ordered]@{ path = $ninjaPath; version = $ninjaVersion }
        dotnet     = [ordered]@{ path = $dotnetPath; version = $dotnetVersion }
        docker     = [ordered]@{ path = $dockerPath; version = $dockerVersion }
        powershell = [ordered]@{ path = $powershellPath; version = $PSVersionTable.PSVersion.ToString() }
        git        = [ordered]@{ path = $gitPath; version = $gitVersion }
        msvc       = [ordered]@{ path = $clPath; version = $msvcVersion }
    }
    [Console]::Out.WriteLine(($result | ConvertTo-Json -Compress))
    exit 0
}
catch {
    $message = ($_.Exception.Message -replace '\s+', ' ').Trim()
    [Console]::Error.WriteLine("preflight failed: $message")
    exit 1
}
