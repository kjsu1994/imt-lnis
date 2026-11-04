[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$SourceDirectory,
    [string]$Distribution = 'Ubuntu',
    [string]$JavaHome,
    [switch]$AssetsOnly,
    [switch]$NativeOnly
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$OutputEncoding = [Text.UTF8Encoding]::new($false)
$repo = [IO.Path]::GetFullPath($SourceDirectory)
. (Join-Path $repo 'deployment\node\node-runtime.ps1')
$assets = Join-Path $repo 'build\offline-assets'
$package = Join-Path $repo 'build\windows-node'
New-Item -ItemType Directory -Path $assets -Force | Out-Null

function Invoke-PackageDocker([string[]]$Arguments) {
    $result = & wsl.exe -d $Distribution --cd / -- docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw "패키징 Docker 명령 실패: $($Arguments[0])" }
    return $result
}


if ($NativeOnly) {
    $temporary = Join-Path ([IO.Path]::GetTempPath()) ('lnis-native-' + [Guid]::NewGuid().ToString('N'))
    $context = Join-Path $temporary 'native'
    $output = Join-Path $temporary 'out'
    New-Item -ItemType Directory -Path $context,$output -Force | Out-Null
    try {
        Get-ChildItem -LiteralPath (Join-Path $repo 'native') | Where-Object Name -ne 'bin' |
            Copy-Item -Destination $context -Recurse
        Invoke-PackageDocker @('build','--platform','linux/amd64','--output',('type=local,dest=' + (ConvertTo-NodeLinuxPath $output)),
            '-f',(ConvertTo-NodeLinuxPath (Join-Path $context 'Dockerfile')),(ConvertTo-NodeLinuxPath $context)) | Out-Host
        $target = Join-Path $repo 'build\native-output'
        New-Item -ItemType Directory -Path $target -Force | Out-Null
        foreach ($name in @('native-linux','native-pvt','iq')) {
            Copy-Item -LiteralPath (Join-Path $output $name) -Destination $target -Recurse -Force
        }
    } finally {
        $base = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
        if ([IO.Path]::GetFullPath($temporary).StartsWith($base) -and (Split-Path $temporary -Leaf).StartsWith('lnis-native-')) {
            Remove-Item -LiteralPath $temporary -Recurse -Force
        }
    }
    return
}

$msi = Join-Path $assets 'wsl.msi'
$metadataFile = Join-Path $assets 'assets.json'
$dockerStage = Join-Path ([IO.Path]::GetTempPath()) ('lnis-offline-package-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $dockerStage -Force | Out-Null
try {
    $metadata = if (Test-Path $metadataFile) { Get-Content -Raw $metadataFile | ConvertFrom-Json } else { $null }
    if (!(Test-Path -LiteralPath $msi) -or !$metadata) {
        Write-Host 'Downloading the official x64 WSL offline installer.'
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        $release = Invoke-RestMethod 'https://api.github.com/repos/microsoft/WSL/releases/latest' -Headers @{'User-Agent'='imtplus-offline-package'}
        $asset = $release.assets | Where-Object name -Match '^wsl\..*\.x64\.msi$' | Select-Object -First 1
        if (!$asset) { throw '공식 WSL 릴리스에서 x64 MSI를 찾을 수 없습니다.' }
        if (!(Test-Path -LiteralPath $msi)) { Invoke-WebRequest $asset.browser_download_url -OutFile $msi -UseBasicParsing }
        $digest = (Get-FileHash -LiteralPath $msi).Hash.ToLowerInvariant()
        if ($asset.digest -and $asset.digest -ne ('sha256:' + $digest)) { throw 'WSL MSI 해시가 공식 릴리스와 다릅니다.' }
        $metadata = [pscustomobject]@{wslVersion=$release.tag_name; wslSha256=$digest; rootfsSha256=''}
    }
    if (!$metadata -or (Get-FileHash -LiteralPath $msi).Hash -ine $metadata.wslSha256) { throw '캐시된 WSL MSI의 무결성을 확인할 수 없습니다.' }
    $metadata | ConvertTo-Json | Set-Content -LiteralPath $metadataFile -Encoding UTF8
    $signature = Get-AuthenticodeSignature -LiteralPath $msi
    if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch 'Microsoft') { throw 'WSL MSI의 Microsoft 서명을 확인하지 못했습니다.' }

    $rootfs = Join-Path $assets 'ubuntu-runtime.tar'
    if (!(Test-Path -LiteralPath $rootfs)) {
        $context = Join-Path $dockerStage 'rootfs-build'
        New-Item -ItemType Directory -Path $context -Force | Out-Null
        $dockerfile = @'
FROM ubuntu:24.04
ENV DEBIAN_FRONTEND=noninteractive
RUN printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d && chmod 755 /usr/sbin/policy-rc.d
RUN apt-get update && apt-get install -y --no-install-recommends systemd systemd-sysv dbus ca-certificates curl iproute2 sudo iptables && install -m 0755 -d /etc/apt/keyrings
RUN curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc && chmod a+r /etc/apt/keyrings/docker.asc && echo "deb [arch=amd64 signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu noble stable" > /etc/apt/sources.list.d/docker.list
RUN apt-get update && apt-get install -y --no-install-recommends docker-ce docker-ce-cli containerd.io docker-compose-plugin && systemctl enable docker containerd && rm -rf /var/lib/apt/lists/* /usr/sbin/policy-rc.d
RUN useradd -m -s /bin/bash lnis && usermod -aG docker lnis && printf 'lnis ALL=(root) NOPASSWD: /usr/bin/systemctl start docker\n' > /etc/sudoers.d/lnis-docker && chmod 440 /etc/sudoers.d/lnis-docker && printf '[boot]\nsystemd=true\n[user]\ndefault=lnis\n' > /etc/wsl.conf
'@
        [IO.File]::WriteAllText((Join-Path $context 'Dockerfile'),$dockerfile,(New-Object Text.UTF8Encoding $false))
        Invoke-PackageDocker @('build','--platform','linux/amd64','-t','lnis-offline-runtime:build',(ConvertTo-NodeLinuxPath $context)) | Out-Host
        $container = (Invoke-PackageDocker @('create','lnis-offline-runtime:build')) -join ''
        try {
            Invoke-PackageDocker @('export','--output',(ConvertTo-NodeLinuxPath (Join-Path $dockerStage 'ubuntu-runtime.tar')),$container.Trim()) | Out-Host
            Copy-Item -LiteralPath (Join-Path $dockerStage 'ubuntu-runtime.tar') -Destination $rootfs -Force
            $metadata.rootfsSha256 = (Get-FileHash -LiteralPath $rootfs).Hash.ToLowerInvariant()
            $metadata | Add-Member -NotePropertyName dockerVersion -NotePropertyValue ((Invoke-PackageDocker @('run','--rm','lnis-offline-runtime:build','dockerd','--version')) -join '') -Force
            $metadata | Add-Member -NotePropertyName composeVersion -NotePropertyValue ((Invoke-PackageDocker @('run','--rm','lnis-offline-runtime:build','docker','compose','version','--short')) -join '') -Force
        } finally {
            if ($container) { Invoke-PackageDocker @('rm',$container.Trim()) | Out-Null }
        }
    }
    if ((Get-FileHash -LiteralPath $rootfs).Hash -ine $metadata.rootfsSha256) { throw '오프라인 WSL 실행 환경의 무결성이 다릅니다.' }
    $metadata | ConvertTo-Json | Set-Content -LiteralPath $metadataFile -Encoding UTF8
    if ($AssetsOnly) { Write-Host 'Offline runtime assets verified.'; return }

    if (Test-Path -LiteralPath $package) {
        $resolved = (Resolve-Path -LiteralPath $package).Path
        if ($resolved -ne (Join-Path $repo 'build\windows-node') -or (Get-Item $resolved).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw '잘못된 패키지 출력 경로입니다.' }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
    $app = Join-Path $package 'app'
    $offline = Join-Path $package 'offline'
    $installer = Join-Path $package 'installer'
    New-Item -ItemType Directory -Path $app,$offline,$installer -Force | Out-Null
    foreach ($file in @('START.cmd','STOP.cmd','start.ps1','stop.ps1','node-runtime.ps1','start-serial-bridge.ps1','stop-serial-bridge.ps1','docker-compose.windows-serial.yml','.env.example','README.md')) {
        Copy-Item -LiteralPath (Join-Path $repo ('deployment\node\' + $file)) -Destination $app
    }
    $compose = (Get-Content -LiteralPath (Join-Path $repo 'deployment\node\docker-compose.yml') -Raw) -replace '(?m)^    build:.*\r?\n',''
    [IO.File]::WriteAllText((Join-Path $app 'docker-compose.yml'),$compose,(New-Object Text.UTF8Encoding $false))
    Copy-Item -LiteralPath (Join-Path $repo 'deployment\node\install.ps1') -Destination $installer
    Copy-Item -LiteralPath (Join-Path $repo 'deployment\node\INSTALL.cmd') -Destination $package
    Copy-Item -LiteralPath $msi,$rootfs -Destination $offline
    Copy-Item -LiteralPath (Join-Path $repo 'build\windows-serial-bridge') -Destination (Join-Path $app 'serial-bridge') -Recurse
    if (!$JavaHome) { $JavaHome = $env:JAVA_HOME }
    if (!$JavaHome -or !(Test-Path -LiteralPath (Join-Path $JavaHome 'bin\jlink.exe'))) { throw '패키징에 사용할 Java 21 JDK 경로를 JavaHome으로 지정하세요.' }
    & (Join-Path $JavaHome 'bin\jlink.exe') --add-modules java.se,jdk.httpserver,jdk.unsupported --strip-debug --no-header-files --no-man-pages --output (Join-Path $app 'runtime')
    if ($LASTEXITCODE -ne 0) { throw 'COM 중계용 Java 실행 환경 생성 실패' }

    $jar = Join-Path $repo 'build\libs\lnis.jar'
    $image = 'lnis-node:1.0.0-' + (Get-FileHash -LiteralPath $jar).Hash.Substring(0,12).ToLowerInvariant()
    $build = Join-Path $dockerStage 'service-build'
    New-Item -ItemType Directory -Path $build -Force | Out-Null
    Copy-Item -LiteralPath $jar -Destination (Join-Path $build 'lnis.jar') -Force
    Copy-Item -LiteralPath (Join-Path $repo 'deployment\node\Dockerfile') -Destination $build -Force
    New-Item -ItemType Directory -Path (Join-Path $build 'native'),(Join-Path $build 'licenses') -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $repo 'build\native-linux\libLnisAfsCodec.so') -Destination (Join-Path $build 'native') -Force
    Copy-Item -LiteralPath (Join-Path $repo 'build\iq') -Destination $build -Recurse -Force
    Copy-Item -LiteralPath (Join-Path $repo 'build\distributions\native-sources.zip') -Destination (Join-Path $build 'licenses') -Force
    Copy-Item -LiteralPath (Join-Path $repo 'native\THIRD-PARTY-NOTICES.txt'),(Join-Path $repo 'native\RTKLIB-README.txt') -Destination (Join-Path $build 'licenses') -Force
    Copy-Item -LiteralPath (Join-Path $build 'licenses') -Destination $app -Recurse
    Invoke-PackageDocker @('build','--platform','linux/amd64','-t',$image,(ConvertTo-NodeLinuxPath $build)) | Out-Host
    $imageId = (Invoke-PackageDocker @('image','inspect','--format','{{.Id}}',$image)) -join ''
    Invoke-PackageDocker @('save','--output',(ConvertTo-NodeLinuxPath (Join-Path $dockerStage 'service-image.tar')),$image) | Out-Host
    Copy-Item -LiteralPath (Join-Path $dockerStage 'service-image.tar') -Destination $offline -Force
    $info = [ordered]@{
        version='1.0.0'; image=$image; imageId=$imageId.Trim()
        wslVersion=$metadata.wslVersion; dockerVersion=$metadata.dockerVersion; composeVersion=$metadata.composeVersion
        createdAt=[DateTime]::UtcNow.ToString('o')
    }
    function Write-PackageManifest([string]$Root) {
        $files = @(Get-ChildItem -LiteralPath $Root -Recurse -File | Sort-Object FullName | ForEach-Object {
            @{path=$_.FullName.Substring($Root.Length+1).Replace('\','/'); sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}
        })
        $manifest = [ordered]@{}
        foreach ($key in $info.Keys) { $manifest[$key] = $info[$key] }
        $manifest.files = $files
        $manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $Root 'release.json') -Encoding UTF8
    }
    Write-PackageManifest $app
    Write-PackageManifest $package
    Assert-NodeFiles $package | Out-Null
    Write-Host "Windows offline package prepared: $package"

} finally {
    $temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    $resolvedStage = [IO.Path]::GetFullPath($dockerStage)
    if ($resolvedStage.StartsWith($temporaryRoot,[StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path $resolvedStage -Leaf).StartsWith('lnis-offline-package-') -and (Test-Path -LiteralPath $resolvedStage)) {
        Remove-Item -LiteralPath $resolvedStage -Recurse -Force
    }
}
