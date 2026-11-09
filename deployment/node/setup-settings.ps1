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
    $json = @{destination=$Path;values=$Values} | ConvertTo-Json -Depth 4 -Compress
    $protected = ConvertTo-SecureString $json -AsPlainText -Force | ConvertFrom-SecureString
    [IO.File]::WriteAllText($file, $protected, [Text.Encoding]::ASCII)
}

function Get-SetupAddresses {
    $addresses = New-Object 'Collections.Generic.List[string]'
    try {
        foreach ($item in Get-NetIPConfiguration -ErrorAction Stop) {
            if ($item.IPv4DefaultGateway -and $item.NetAdapter.Status -eq 'Up' -and
                    $item.InterfaceAlias -notmatch 'vEthernet|WSL|Docker|Loopback') {
                foreach ($ip in $item.IPv4Address.IPAddress) {
                    if ($ip -and !$addresses.Contains($ip)) { $addresses.Add($ip) }
                }
            }
        }
    } catch {}
    foreach ($adapter in [Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()) {
        if ($adapter.OperationalStatus -ne 'Up' -or
                $adapter.NetworkInterfaceType -in @('Loopback','Tunnel') -or
                $adapter.Name -match 'vEthernet|WSL|Docker|Loopback') { continue }
        foreach ($item in $adapter.GetIPProperties().UnicastAddresses) {
            $ip = $item.Address.ToString()
            if ($item.Address.AddressFamily -eq 'InterNetwork' -and $ip -notmatch '^(127\.|169\.254\.)' -and
                    !$addresses.Contains($ip)) { $addresses.Add($ip) }
        }
    }
    return $addresses.ToArray()
}

function New-SetupToken {
    $bytes = New-Object byte[] 32
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    return [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
}

function Get-SetupValues([string]$Path) {
    $existing = Join-Path $Path 'data\.env'
    $values = if (Test-Path -LiteralPath $existing) { Read-NodeEnvironment $existing } else { @{} }
    $pending = Read-SetupPending
    if ($pending -and $pending.destination -ieq $Path) {
        foreach ($property in $pending.values.PSObject.Properties) { $values[$property.Name] = [string]$property.Value }
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
        LNIS_NODE_MANAGEMENT_TOKEN=(New-SetupToken)
        LNIS_DTN_SEND_TOKEN='';LNIS_DTN_RECEIVE_TOKEN=(New-SetupToken)
        LNIS_IQ_HOST_PATH=(Join-Path $Path 'data\exchange')
        LNIS_NTP_SERVER=''
    }
    foreach ($key in $defaults.Keys) { if (!$values.ContainsKey($key)) { $values[$key]=$defaults[$key] } }
    $lines = @('[setup]', "destination=$Path", ('existing=' + [int](Test-Path -LiteralPath (Join-Path $Path 'app\installation.json'))),
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
            'LNIS_IQ_HOST_PATH','LNIS_NTP_SERVER')) {
        $values[$key] = Read-SetupIni $InputFile 'env' $key
    }
    Assert-InstallSettings $values
    if ((Get-SetupAddresses) -notcontains ([Uri]$values.LNIS_NODE_BASE_URL).Host) {
        throw '현재 PC에 할당된 IPv4 주소를 선택하세요. 네트워크 연결을 확인한 뒤 설치 프로그램을 다시 실행하세요.'
    }
    $installed = Join-Path $path 'app\installation.json'
    $existing = Join-Path $path 'data\.env'
    if (Test-Path -LiteralPath $installed) {
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
