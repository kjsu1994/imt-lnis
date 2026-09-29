$ErrorActionPreference = 'Stop'
$composeRoot = $PSScriptRoot
if ($composeRoot -notmatch '^[A-Za-z]:\\') { throw 'Windows 로컬 드라이브에서 실행하세요.' }
$linuxRoot = '/mnt/' + $composeRoot.Substring(0, 1).ToLowerInvariant() + $composeRoot.Substring(2).Replace('\', '/')
& wsl.exe --cd $linuxRoot -- docker compose down
if ($LASTEXITCODE -ne 0) { throw '독립 노드 종료에 실패했습니다.' }
& (Join-Path $composeRoot 'stop-serial-bridge.ps1')
Write-Host '독립 노드와 Windows 중계를 종료했습니다. DB는 유지됩니다.'
