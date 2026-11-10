[CmdletBinding()]
param(
    [ValidateSet('Inspect','Prepare','Clear')][string]$Action,
    [string]$Destination,
    [string]$Request,
    [string]$Hints,
    [string]$Configuration
)
$ErrorActionPreference = 'Stop'
. (Join-Path (Split-Path $PSScriptRoot) 'app\node-runtime.ps1')

function Get-SetupPendingPath {
    return Join-Path $env:LOCALAPPDATA 'imtplus\Setup\pending.dat'
}

function Read-SetupPending {
    $path = Get-SetupPendingPath
    if (!(Test-Path -LiteralPath $path)) { return $null }
    try {
        $secret = ConvertTo-SecureString (Get-Content -LiteralPath $path -Raw)
        $json = (New-Object Net.NetworkCredential('', $secret)).Password
        return $json | ConvertFrom-Json
    } catch { return $null }
}

function Save-SetupPending([string]$Path, $Values) {
    $file = Get-SetupPendingPath
    New-Item -ItemType Directory -Path (Split-Path $file) -Force | Out-Null
    $json = @{destination=$Path;values=$Values;authDefaults='imt123'} | ConvertTo-Json -Depth 4 -Compress
    $protected = ConvertTo-SecureString $json -AsPlainText -Force | ConvertFrom-SecureString
    [IO.File]::WriteAllText($file, $protected, [Text.Encoding]::ASCII)
}

function Get-SetupAddresses { return Get-NodeAddresses }

function Get-SetupValues([string]$Path) {
    $existing = (Get-NodeInstallLayout $Path).EnvFile
    $values = if (Test-Path -LiteralPath $existing) { Read-NodeEnvironment $existing } else { @{} }
    $pending = Read-SetupPending
    if ($pending -and $pending.destination -ieq $Path) {
        foreach ($property in $pending.values.PSObject.Properties) {
            if (!(Test-Path -LiteralPath $existing) -and $pending.authDefaults -ne 'imt123' -and
                    $property.Name -in @('LNIS_NODE_MANAGEMENT_TOKEN','LNIS_DTN_SEND_TOKEN','LNIS_DTN_RECEIVE_TOKEN')) { continue }
            $values[$property.Name] = [string]$property.Value
        }
    }
    return $values
}

function Write-SetupHints([string]$Path, [string]$Output) {
    $pending = Read-SetupPending
    if (!$Path) {
        $Path = if ($pending) { $pending.destination } else { Join-Path $env:SystemDrive 'compose' }
    }
    $values = Get-SetupValues $Path
    $addresses = @(Get-SetupAddresses)
    $first = if ($addresses.Count) { $addresses[0] } else { '' }
    $defaults = @{
        LNIS_NODE_ROLE='sender';LNIS_SERVER_PORT='8088'
        LNIS_NODE_BASE_URL=$(if ($first) { 'http://' + $first + ':8088' } else { '' })
        LNIS_NODE_PEER_URL='';dtn_adapter=''
        LNIS_NODE_MANAGEMENT_TOKEN='imt123'
        LNIS_DTN_SEND_TOKEN='imt123';LNIS_DTN_RECEIVE_TOKEN='imt123'
        LNIS_IQ_HOST_PATH=(Join-Path $Path 'exchange')
    }
    foreach ($key in $defaults.Keys) { if (!$values.ContainsKey($key)) { $values[$key]=$defaults[$key] } }
    if ($addresses.Count -and ($addresses -notcontains ([Uri]$values.LNIS_NODE_BASE_URL).Host)) {
        $values.LNIS_NODE_BASE_URL = 'http://' + $first + ':' + $values.LNIS_SERVER_PORT
    }
    $lines = @('[setup]', "destination=$Path", ('existing=' + [int](Get-NodeInstallLayout $Path).Existing),
        ('ipCount=' + $addresses.Count))
    for ($i=0; $i -lt $addresses.Count; $i++) { $lines += ('ip' + $i + '=' + $addresses[$i]) }
    $lines += '[network]'
    foreach ($item in @(@('LNIS_NODE_BASE_URL','local','8088'),@('LNIS_NODE_PEER_URL','peer','8088'),@('dtn_adapter','adapter','8080'))) {
        $uri = if ($values[$item[0]]) { [Uri]$values[$item[0]] } else { $null }
        $lines += ($item[1] + 'Ip=' + $(if ($uri) { $uri.Host } else { '' }))
        $lines += ($item[1] + 'Port=' + $(if ($uri) { $uri.Port } else { $item[2] }))
    }
    $lines += '[env]'
    foreach ($key in $values.Keys | Sort-Object) { $lines += "$key=$($values[$key])" }
    [IO.File]::WriteAllLines($Output, $lines, [Text.Encoding]::Unicode)
}

function Read-SetupIni([string]$File, [string]$Section, [string]$Key) {
    if (!('SetupIni' -as [type])) {
        Add-Type -TypeDefinition @'
using System.Text;
using System.Runtime.InteropServices;
public static class SetupIni {
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode)]
    public static extern uint GetPrivateProfileString(string section, string key, string value, StringBuilder result, uint size, string file);
}
'@
    }
    $buffer = New-Object Text.StringBuilder 32768
    [void][SetupIni]::GetPrivateProfileString($Section,$Key,'',$buffer,32768,$File)
    return $buffer.ToString()
}

function Prepare-SetupConfiguration([string]$InputFile, [string]$Output) {
    $path = Assert-InstallLocation (Read-SetupIni $InputFile 'setup' 'destination')
    $values = Get-SetupValues $path
    foreach ($key in @('LNIS_NODE_ROLE','LNIS_SERVER_PORT','LNIS_NODE_BASE_URL','LNIS_NODE_PEER_URL',
            'dtn_adapter','LNIS_NODE_MANAGEMENT_TOKEN','LNIS_DTN_SEND_TOKEN','LNIS_DTN_RECEIVE_TOKEN',
            'LNIS_IQ_HOST_PATH')) {
        $values[$key] = Read-SetupIni $InputFile 'env' $key
    }
    if (!(Get-NodeInstallLayout $path).Existing -and !$values.ContainsKey('LNIS_IQ_ENABLED')) {
        $values.LNIS_IQ_ENABLED = [string]($values.LNIS_NODE_ROLE -eq 'sender')
    }
    Assert-InstallSettings $values
    if ((Get-SetupAddresses) -notcontains ([Uri]$values.LNIS_NODE_BASE_URL).Host) {
        throw '현재 PC에 할당된 IPv4 주소를 선택하세요. 네트워크 연결을 확인한 뒤 설치 프로그램을 다시 실행하세요.'
    }
    $layout = Get-NodeInstallLayout $path
    $existing = $layout.EnvFile
    if ($layout.Existing) {
        if ((Read-NodeEnvironment $existing).LNIS_NODE_ROLE -ne $values.LNIS_NODE_ROLE) {
            throw '기존 설치의 송신·수신 역할은 변경할 수 없습니다. 다른 설치 경로를 선택하세요.'
        }
    } elseif ([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners().Port -contains [int]$values.LNIS_SERVER_PORT) {
        throw '선택한 서비스 포트를 다른 프로그램이 사용 중입니다. 포트를 변경하세요.'
    }
    $template = Join-Path (Split-Path $PSScriptRoot) 'app\.env.example'
    Write-InstallEnvironment $template $Output $values
    Save-SetupPending $path $values
}

if ($Action) {
    try {
        switch ($Action) {
            'Inspect' { Write-SetupHints $Destination $Hints }
            'Prepare' { Prepare-SetupConfiguration $Request $Configuration }
            'Clear' {
                $pending = Get-SetupPendingPath
                if (Test-Path -LiteralPath $pending) { Remove-Item -LiteralPath $pending }
            }
        }
    } catch { [Console]::Error.WriteLine($_.Exception.Message); exit 1 }
}
