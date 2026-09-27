#Requires -Version 5.1
<#
.SYNOPSIS
  Prepare the dedicated LOGH7 Windows 10 VirtualBox guest for Japanese and Korean text.
.DESCRIPTION
  Run inside the Windows guest as its auto-login user with an elevated PowerShell.
  Prepare requires temporary NAT for Windows language capabilities. Verify is offline.
  Author: 최병호. Source: Microsoft Learn (links in docs/ops/isolation-vm.md).
#>
[CmdletBinding()]
param(
    [ValidateSet('Prepare', 'Verify')]
    [string]$Mode = 'Verify'
)

$ErrorActionPreference = 'Stop'

$computer = Get-CimInstance -ClassName Win32_ComputerSystem
$operatingSystem = Get-CimInstance -ClassName Win32_OperatingSystem
if ($computer.Model -notmatch 'VirtualBox' -or
    $operatingSystem.Caption -notmatch 'Windows 10' -or
    $operatingSystem.OSArchitecture -notmatch '64') {
    throw 'Run this script only inside the dedicated 64-bit Windows 10 VirtualBox guest.'
}

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this script from an elevated PowerShell inside the guest.'
}

$requiredCapabilities = @(
    'Language.Basic~~~ja-JP~0.0.1.0',
    'Language.Basic~~~ko-KR~0.0.1.0',
    'Language.Fonts.Jpan~~~und-JPAN~0.0.1.0',
    'Language.Fonts.Kore~~~und-KORE~0.0.1.0'
)

function Get-RequiredCapabilities {
    $available = @(Get-WindowsCapability -Online)
    foreach ($name in $requiredCapabilities) {
        $item = $available | Where-Object { $_.Name -eq $name } | Select-Object -First 1
        if ($null -eq $item) {
            throw "Windows image does not expose required capability: $name"
        }
        $item
    }
}

if ($Mode -eq 'Prepare') {
    foreach ($capability in Get-RequiredCapabilities) {
        if ($capability.State -ne 'Installed') {
            Write-Host "Installing $($capability.Name)"
            Add-WindowsCapability -Online -Name $capability.Name | Out-Null
        }
    }

    $languages = New-WinUserLanguageList -Language 'ja-JP'
    $languages.Add('ko-KR')
    Set-WinUserLanguageList -LanguageList $languages -Force
    Set-WinSystemLocale -SystemLocale 'ja-JP'
    Set-WinUILanguageOverride -Language 'ja-JP'
    Set-Culture -CultureInfo 'ja-JP'
    Write-Warning 'Restart the guest, then run this script with -Mode Verify. Disconnect NAT before game execution.'
    return
}

$capabilities = @(Get-RequiredCapabilities)
$languageList = Get-WinUserLanguageList
$languages = @(for ($i = 0; $i -lt $languageList.Count; $i++) {
    $languageList[$i]
})
$systemLocale = (Get-WinSystemLocale).Name
$languageTags = @($languages | ForEach-Object { $_.LanguageTag })
$japaneseInputReady = @($languages | Where-Object {
    $_.LanguageTag -in @('ja', 'ja-JP') -and $_.InputMethodTips.Count -gt 0
}).Count -gt 0
$koreanInputReady = @($languages | Where-Object {
    $_.LanguageTag -in @('ko', 'ko-KR') -and $_.InputMethodTips.Count -gt 0
}).Count -gt 0
$inputMethods = @($languages | ForEach-Object {
    [pscustomobject]@{
        LanguageTag = $_.LanguageTag
        InputMethodTips = @($_.InputMethodTips)
    }
})
$result = [pscustomobject]@{
    ComputerName = $env:COMPUTERNAME
    SystemLocale = $systemLocale
    UserInterfaceCulture = (Get-UICulture).Name
    UserCulture = (Get-Culture).Name
    UserLanguages = $languageTags
    InputMethods = $inputMethods
    Capabilities = @($capabilities | ForEach-Object {
        [pscustomobject]@{ Name = $_.Name; State = [string]$_.State }
    })
    JapaneseFontPresent = Test-Path "$env:WINDIR\Fonts\msgothic.ttc"
    KoreanFontPresent = Test-Path "$env:WINDIR\Fonts\gulim.ttc"
}
$result | ConvertTo-Json -Depth 5

if ($systemLocale -ne 'ja-JP' -or
    $result.UserInterfaceCulture -ne 'ja-JP' -or
    -not $japaneseInputReady -or
    -not $koreanInputReady -or
    -not $result.JapaneseFontPresent -or
    -not $result.KoreanFontPresent -or
    @($capabilities | Where-Object { $_.State -ne 'Installed' }).Count -gt 0) {
    throw 'Japanese/Korean guest locale verification failed. Review the JSON above.'
}
