[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$exampleRoot = Join-Path $projectRoot 'build\dtn-example'
New-Item -ItemType Directory -Force -Path $exampleRoot | Out-Null
$sourceFile = Join-Path $exampleRoot 'F9T-L2-5min.ubx.gz'
$sourceUrl = 'https://raw.githubusercontent.com/nav-solutions/data/main/UBX/F9T-L2-5min.ubx.gz'
$expectedHash = '1ABCE8A84A82B4AD9DB45362244E54CFDCA85016A79962BB02B2DFC8B5D29B87'
if (!(Test-Path -LiteralPath $sourceFile)) {
    Invoke-WebRequest -UseBasicParsing $sourceUrl -OutFile $sourceFile
}
if ((Get-FileHash -LiteralPath $sourceFile -Algorithm SHA256).Hash -ne $expectedHash) {
    throw 'F9T source checksum mismatch. Do not use an unverified replacement.'
}
Invoke-WebRequest -UseBasicParsing 'https://raw.githubusercontent.com/nav-solutions/data/main/LICENSE' `
    -OutFile (Join-Path $exampleRoot 'LICENSE')
Push-Location $projectRoot
try {
    & .\gradlew.bat test '--tests=server.agent.codec.F9tExampleTest' `
        '-Pf9tExampleSource=build/dtn-example/F9T-L2-5min.ubx.gz' --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'F9T example verification failed.' }
} finally { Pop-Location }
Write-Host 'Ready: build/dtn-example/f9t-example.graw'
Write-Host 'Observation transfer only: the public source has no SFRBX navigation, so PVT is unavailable.'
