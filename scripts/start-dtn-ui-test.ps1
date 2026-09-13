# Isolated local verification. Run after bootJar; never execute the mutable build/libs JAR directly.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$testRoot = Join-Path $projectRoot 'build\dtn-ui-test'
New-Item -ItemType Directory -Force $testRoot | Out-Null
$testJava = Join-Path $env:USERPROFILE '.jdks\ms-21.0.12.1\bin\java.exe'
if (!(Test-Path -LiteralPath $testJava)) { throw 'Set testJava to a Java 21 executable before running.' }
foreach ($port in @(18090,18091)) {
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) {
        throw "Test port $port is already in use. Stop the previous test nodes first."
    }
}
Copy-Item -LiteralPath (Join-Path $projectRoot 'build\libs\lnis.jar') -Destination (Join-Path $testRoot 'lnis.jar')
$common = @('-jar','build/dtn-ui-test/lnis.jar','node','--server.address=127.0.0.1',
    '--lnis.native.dir=native/bin/win-x64','--lnis.node.management-token=dtn-ui-local-test',
    '--lnis.dtn.receive-token=dtn-ui-local-test')
foreach ($role in @('sender','receiver')) {
    $port = if ($role -eq 'sender') { 18090 } else { 18091 }
    $peer = if ($role -eq 'sender') { 18091 } else { 18090 }
    $arguments = $common + @("--server.port=$port", "--lnis.node.role=$role",
        "--lnis.node.base-url=http://127.0.0.1:$port", "--lnis.node.peer-url=http://127.0.0.1:$peer",
        "--spring.datasource.url=jdbc:h2:file:./build/dtn-ui-test/$role-db",
        "--lnis.storage.data-directory=./build/dtn-ui-test/$role-data")
    if ($role -eq 'sender') {
        $arguments += @('--lnis.dtn.example-enabled=true','--lnis.dtn.example-file=build/dtn-example/f9t-example.graw')
    }
    $process = Start-Process $testJava -ArgumentList $arguments -WorkingDirectory $projectRoot -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $testRoot "$role.log") `
        -RedirectStandardError (Join-Path $testRoot "$role-error.log") -PassThru
    Write-Host "$role PID=$($process.Id) URL=http://127.0.0.1:$port"
}
