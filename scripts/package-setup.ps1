[CmdletBinding()]
param(
    [string]$SourceDirectory,
    [string]$BundleRoot,
    [string]$Output
)
$ErrorActionPreference = 'Stop'
if (!$SourceDirectory) { $SourceDirectory = Split-Path $PSScriptRoot }
$repo = [IO.Path]::GetFullPath($SourceDirectory)
if (!$BundleRoot) { $BundleRoot = Join-Path $repo 'build\windows-node' }
if (!$Output) { $Output = Join-Path $repo 'Setup.exe' }
. (Join-Path $repo 'deployment\node\node-runtime.ps1')
Assert-NodeFiles $BundleRoot | Out-Null
if (!(Test-Path -LiteralPath (Join-Path $BundleRoot 'installer\setup-settings.ps1'))) {
    throw '설치 마법사 파일이 없습니다. prepareWindowsNode로 배포 폴더를 다시 생성하세요.'
}
$version = '3.13'
$hash = 'ba63dffc4410ee89193e1cb5a41989991bd77c61068da17e3156d136b7b0b3d8'
$tools = Join-Path $repo 'build\setup-tools'
$archive = Join-Path $tools 'nsis.zip'
$compilerRoot = Join-Path $tools ('nsis-' + $version)
$compiler = Join-Path $compilerRoot 'makensis.exe'
New-Item -ItemType Directory -Path $tools,(Split-Path $Output) -Force | Out-Null
if (!(Test-Path -LiteralPath $archive)) {
    Write-Host '설치 EXE 제작 도구를 준비합니다. 설치 대상 PC에서는 다운로드하지 않습니다.'
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $download = $archive + '.download'
    try {
        Invoke-WebRequest ('https://downloads.sourceforge.net/project/nsis/NSIS%203/' + $version + '/nsis-' + $version + '.zip') -OutFile $download -UseBasicParsing
        if ((Get-FileHash -LiteralPath $download).Hash -ine $hash) { throw 'NSIS 제작 도구의 SHA-256이 고정된 버전과 다릅니다.' }
        Move-Item -LiteralPath $download -Destination $archive -Force
    } finally { if (Test-Path -LiteralPath $download) { Remove-Item -LiteralPath $download } }
}
if ((Get-FileHash -LiteralPath $archive).Hash -ine $hash) { throw '캐시된 NSIS 제작 도구가 변경되었습니다.' }
# Refresh the compiler from the verified archive, rather than trusting an old extracted copy.
Expand-Archive -LiteralPath $archive -DestinationPath $tools -Force
$pending = $Output + '.pending.exe'
try {
    & $compiler /INPUTCHARSET UTF8 (('/DBUNDLE=' + [IO.Path]::GetFullPath($BundleRoot))) (('/DOUTPUT=' + [IO.Path]::GetFullPath($pending))) (Join-Path $repo 'deployment\node\setup.nsi')
    if ($LASTEXITCODE -ne 0 -or !(Test-Path -LiteralPath $pending)) { throw 'Setup.exe 생성 실패' }
    if (Test-Path -LiteralPath $Output) {
        $previous = $Output + '.previous-' + [Guid]::NewGuid().ToString('N') + '.exe'
        [IO.File]::Replace($pending, $Output, $previous)
        Remove-Item -LiteralPath $previous
    } else {
        [IO.File]::Move($pending, $Output)
    }
    Write-Host ("Setup.exe 생성 완료: " + $Output)
    Write-Host ("SHA-256: " + (Get-FileHash -LiteralPath $Output).Hash)
} finally { if (Test-Path -LiteralPath $pending) { Remove-Item -LiteralPath $pending } }
