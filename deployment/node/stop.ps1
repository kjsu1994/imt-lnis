$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'node-runtime.ps1')
$context = Get-NodeContext $PSScriptRoot
$lock = Enter-NodeLock $context
try {
    Invoke-NodeWsl $context (@('docker') + (Get-NodeCompose $context) + @('down')) | Out-Host
    $deadline = (Get-Date).AddSeconds(35)
    do {
        try {
            & (Join-Path $context.App 'stop-serial-bridge.ps1') -DataDirectory $context.Data
            break
        } catch {
            if ((Get-Date) -ge $deadline) { throw }
            Start-Sleep -Milliseconds 500
        }
    } while ($true)
    Stop-NodeKeepAlive $context
    Write-Host '서비스를 종료했습니다. DB와 공유 파일은 유지됩니다.'
} finally { $lock.ReleaseMutex(); $lock.Dispose() }
