param([switch]$Stop)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$data = Join-Path $PSScriptRoot 'data'
$pidFile = Join-Path $data 'server.pid'
$server = Join-Path $PSScriptRoot 'server.mjs'
if (Test-Path -LiteralPath $pidFile) {
    $serverPid = [int](Get-Content -LiteralPath $pidFile -Raw)
    $existing = Get-CimInstance Win32_Process -Filter "ProcessId = $serverPid" -ErrorAction SilentlyContinue
    if ($existing -and $existing.Name -eq 'node.exe' -and $existing.CommandLine.Contains($server)) {
        if ($Stop) { Stop-Process -Id $serverPid; Remove-Item -LiteralPath $pidFile; Write-Output 'Familia detenida.'; return }
        Write-Output 'Familia ya está en ejecución.'; return
    }
}
if ($Stop) { Write-Output 'Familia no está en ejecución.'; return }
New-Item -ItemType Directory -Force -Path $data | Out-Null
# Limitar certificados y base familiar al usuario actual y SYSTEM.
$account = [Security.Principal.WindowsIdentity]::GetCurrent().Name
& icacls.exe $data /inheritance:r /grant:r "${account}:(OI)(CI)F" 'SYSTEM:(OI)(CI)F' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'No se pudieron proteger los datos de Familia.' }
$tailscale = Join-Path $env:ProgramFiles 'Tailscale\tailscale.exe'
$state = $null
for ($attempt = 0; $attempt -lt 10; $attempt++) {
    $state = & $tailscale status --json | ConvertFrom-Json
    if ($state.BackendState -eq 'Running') { break }
    Start-Sleep -Seconds 3
}
if ($state.BackendState -ne 'Running') { throw 'Tailscale debe estar conectado.' }
$dns = $state.Self.DNSName.TrimEnd('.')
$address = @($state.TailscaleIPs | Where-Object { $_ -match '^100\.' })[0]
if (!$address -or !$dns) { throw 'No se obtuvo la identidad Tailscale.' }
$cert = Join-Path $data 'tls.crt'
$key = Join-Path $data 'tls.key'
& $tailscale cert "--cert-file=$cert" "--key-file=$key" '--min-validity=240h' $dns
if ($LASTEXITCODE -ne 0) { throw 'No se pudo obtener el certificado HTTPS.' }
$env:BSECURITY_BIND = $address
$env:BSECURITY_TLS_CERT = $cert
$env:BSECURITY_TLS_KEY = $key
$env:BSECURITY_TLS_HOST = $dns
$env:BSECURITY_RENEW_CLI = $tailscale
$env:BSECURITY_DATA = Join-Path $data 'family.json'
$env:PORT = '9443'
$node = (Get-Command node.exe).Source
$started = Start-Process -FilePath $node -ArgumentList ('"' + $server + '"') -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardOutput (Join-Path $data 'stdout.log') -RedirectStandardError (Join-Path $data 'stderr.log') -PassThru
[IO.File]::WriteAllText($pidFile, $started.Id.ToString())
Write-Output "Familia iniciada: https://${dns}:9443"
