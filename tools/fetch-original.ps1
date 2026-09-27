<#
.SYNOPSIS
  archive.org의 LOGH7 CD 이미지를 받아 무결성을 검증하고 ISO·설치본까지 추출한다.

.DESCRIPTION
  원본은 저장소에 넣지 않는다. 이 스크립트가 재현 경로다.
    1) archive.org/details/logh-7 에서 Logh7.bin / Logh7.cue / logh-7_files.xml 다운로드(이미 있으면 건너뜀)
    2) logh-7_files.xml 의 MD5·SHA1 과 대조
    3) tools/bin2iso.py 로 MODE2/2352 → ISO
    4) 7-Zip 으로 ISO 해제
    5) tools/isextract.py 로 InstallShield 7 캐비닛 해제(항목별 MD5 검증, _manifest.csv)

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\fetch-original.ps1 -Root E:\logh7-original
#>
param(
    [string] $Root = 'E:\logh7-original',
    [string] $Python = 'C:\Users\user\AppData\Local\Programs\Python\Python311\python.exe',
    [string] $SevenZip = 'C:\Program Files\7-Zip\7z.exe'
)
$ErrorActionPreference = 'Stop'
$archive = Join-Path $Root 'archive'
$extracted = Join-Path $Root 'extracted'
New-Item -ItemType Directory -Force $archive, $extracted | Out-Null

$base = 'https://archive.org/download/logh-7'
foreach ($f in 'logh-7_files.xml', 'Logh7.cue', 'Logh7.bin') {
    $dst = Join-Path $archive $f
    if (-not (Test-Path $dst)) {
        Write-Host "download $f"
        curl.exe -L --retry 5 --retry-delay 5 -C - "$base/$f" -o $dst
        if ($LASTEXITCODE -ne 0) { throw "download failed: $f" }
    }
}

[xml] $xml = Get-Content (Join-Path $archive 'logh-7_files.xml') -Raw
foreach ($f in 'Logh7.bin', 'Logh7.cue') {
    $node = $xml.files.file | Where-Object { $_.name -eq $f }
    $path = Join-Path $archive $f
    $md5 = (Get-FileHash $path -Algorithm MD5).Hash.ToLower()
    $sha1 = (Get-FileHash $path -Algorithm SHA1).Hash.ToLower()
    if ($md5 -ne $node.md5 -or $sha1 -ne $node.sha1) { throw "hash mismatch: $f" }
    Write-Host "verified $f md5=$md5 sha1=$sha1"
}

$iso = Join-Path $extracted 'Logh7.iso'
if (-not (Test-Path $iso)) {
    & $Python (Join-Path $PSScriptRoot 'bin2iso.py') (Join-Path $archive 'Logh7.bin') $iso
    if ($LASTEXITCODE -ne 0) { throw 'bin2iso failed' }
}
$isoDir = Join-Path $extracted 'iso'
if (-not (Test-Path (Join-Path $isoDir 'data1.hdr'))) {
    & $SevenZip x $iso "-o$isoDir" -y | Out-Null
    if ($LASTEXITCODE -ne 0) { throw '7z extract failed' }
}
$install = Join-Path $extracted 'install'
& $Python (Join-Path $PSScriptRoot 'isextract.py') (Join-Path $isoDir 'data1.hdr') $install
if ($LASTEXITCODE -ne 0) { throw 'isextract reported failures (see _manifest.csv)' }
Write-Host "done: $install"
