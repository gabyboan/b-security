#Requires -Version 5.1
<#
.SYNOPSIS
Prueba Familia desde Android contra un servidor HTTPS local y descartable.
.DESCRIPTION
Requiere Node.js 22+, Android SDK Platform-Tools y OpenSSL 1.1.1+ (PATH o Git for Windows).
Compilar antes ambos APK con .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest.
Conectar un emulador o teléfono autorizado por ADB; usar -Serial si hay varios.
Conserva los datos de la app al instalar. Las comprobaciones usan identidades y preferencias
temporales; el servidor, certificado y base se crean dentro del directorio temporal del sistema.
No utiliza el servidor privado ni server/data. No activa la comprobación del servidor por defecto.
.EXAMPLE
.\test-family.ps1
.EXAMPLE
.\test-family.ps1 -Serial emulator-5580
#>
[CmdletBinding()]
param([string]$Serial)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-Adb {
    param([string[]]$Arguments)
    $previousErrorAction = $ErrorActionPreference
    try {
        # PowerShell 5 can turn harmless native stderr into ErrorRecords.
        $ErrorActionPreference = 'Continue'
        $result = @(& $script:adbPath @Arguments 2>&1 | ForEach-Object { $_.ToString() })
        $commandExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousErrorAction }
    if ($commandExit -ne 0) { throw "ADB terminó con código $commandExit. $($result -join [Environment]::NewLine)" }
    return $result
}

function Find-OpenSsl {
    $command = Get-Command openssl -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    $candidates = @()
    if ($env:ProgramFiles) { $candidates += Join-Path $env:ProgramFiles 'Git/usr/bin/openssl.exe' }
    if ($env:LOCALAPPDATA) { $candidates += Join-Path $env:LOCALAPPDATA 'Programs/Git/usr/bin/openssl.exe' }
    $git = Get-Command git -ErrorAction SilentlyContinue
    if ($git) { $candidates += Join-Path (Split-Path -Parent (Split-Path -Parent $git.Source)) 'usr/bin/openssl.exe' }
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    }
    throw 'Se requiere OpenSSL 1.1.1 o superior en PATH, o la instalación de Git for Windows.'
}

$apk = Join-Path $PSScriptRoot 'app/build/outputs/apk/debug/app-debug.apk'
$testApk = Join-Path $PSScriptRoot 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
foreach ($file in @($apk, $testApk)) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
        throw 'Faltan los APK. Compilá con .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest'
    }
}
$node = (Get-Command node -ErrorAction Stop).Source
$nodeVersion = & $node --version
if ($LASTEXITCODE -ne 0 -or $nodeVersion -notmatch '^v(\d+)\.' -or [int]$Matches[1] -lt 22) {
    throw 'Se requiere Node.js 22 o superior.'
}
$openssl = Find-OpenSsl
$adbCommand = Get-Command adb -ErrorAction SilentlyContinue
$script:adbPath = if ($adbCommand) { $adbCommand.Source } else { Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe' }
if (-not (Test-Path -LiteralPath $script:adbPath -PathType Leaf)) { throw 'No se encontró Android SDK Platform-Tools (ADB).' }
$deviceLines = @(Invoke-Adb -Arguments @('devices', '-l'))
$devices = @($deviceLines | Where-Object { $_ -match '^(\S+)\s+device(?:\s|$)' } | ForEach-Object { ([regex]::Match($_, '^(\S+)')).Groups[1].Value })
if ($Serial) {
    if ($Serial -notin $devices) { throw "El dispositivo $Serial no está conectado y autorizado por ADB." }
} elseif ($devices.Count -eq 1) { $Serial = $devices[0] }
else { throw 'Conectá exactamente un dispositivo autorizado, o elegí uno con -Serial.' }

# Do not replace or later uninstall a test package that belongs to an earlier session.
$installedPackages = @(Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'pm', 'list', 'packages', 'com.bsecurity.app.test'))
if ($installedPackages -contains 'package:com.bsecurity.app.test') {
    throw 'El paquete com.bsecurity.app.test ya está instalado. Quitalo antes de continuar si ya no lo necesitás, o elegí otro dispositivo con -Serial. No se modificó ningún paquete.'
}

$tempParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar)
$tempName = 'BSecurityFamily-' + [Guid]::NewGuid().ToString('N')
$tempDirectory = Join-Path $tempParent $tempName
$null = New-Item -ItemType Directory -Path $tempDirectory
$readyPath = Join-Path $tempDirectory 'ready.json'
$fixture = $null
$reverseCreated = $false
$testInstalled = $false
$port = 0
try {
    $fixturePath = Join-Path $PSScriptRoot 'server/android-fixture.mjs'
    # Arguments are executable/file paths, never commands interpreted by a shell.
    $start = @{
        FilePath = $node
        ArgumentList = @(('"{0}"' -f $fixturePath), '--ready', ('"{0}"' -f $readyPath), '--openssl', ('"{0}"' -f $openssl))
        PassThru = $true
        RedirectStandardOutput = (Join-Path $tempDirectory 'fixture.out.log')
        RedirectStandardError = (Join-Path $tempDirectory 'fixture.err.log')
    }
    if ($env:OS -eq 'Windows_NT') { $start.WindowStyle = 'Hidden' }
    $fixture = Start-Process @start
    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    while (-not (Test-Path -LiteralPath $readyPath)) {
        $fixture.Refresh()
        if ($fixture.HasExited) { throw 'El servidor HTTPS temporal no pudo iniciar. Comprobá que OpenSSL pueda generar certificados con -addext.' }
        if ([DateTime]::UtcNow -ge $deadline) { throw 'El servidor HTTPS temporal no inició dentro de 45 segundos.' }
        Start-Sleep -Milliseconds 100
    }
    $ready = Get-Content -LiteralPath $readyPath -Raw | ConvertFrom-Json
    $port = [int]$ready.port
    if ($port -lt 1 -or $port -gt 65535 -or -not $ready.ca) { throw 'El servidor temporal devolvió una configuración inválida.' }
    Write-Host "Comprobando Familia en $Serial mediante HTTPS local..."
    $null = Invoke-Adb -Arguments @('-s', $Serial, 'reverse', '--no-rebind', "tcp:$port", "tcp:$port")
    $reverseCreated = $true
    $null = Invoke-Adb -Arguments @('-s', $Serial, 'install', '-r', $apk)
    $null = Invoke-Adb -Arguments @('-s', $Serial, 'install', '-r', $testApk)
    $testInstalled = $true
    $output = (Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'am', 'instrument', '-w', '-r',
        '-e', 'familyEndpoint', "https://localhost:$port", '-e', 'familyCa', [string]$ready.ca,
        'com.bsecurity.app.test/com.bsecurity.app.PilotInstrumentation')) -join [Environment]::NewLine
    Write-Host $output
    # A previous test APK can ignore the fixture arguments and pass only its offline cases.
    $familyMarker = '(?m)^Familia HTTPS aislada: 5 pruebas ejecutadas\.\r?$'
    if ($output -notmatch 'INSTRUMENTATION_CODE:\s*-1(?:\s|$)' -or $output -match 'FALLO:' -or
        $output -notmatch '\d+ aprobadas; 0 fallidas\.' -or $output -notmatch $familyMarker) {
        throw 'Falló alguna comprobación de Familia. Revisá el resumen anterior.'
    }
    Write-Host 'Familia: comprobaciones HTTPS completadas correctamente.'
} finally {
    if ($testInstalled) {
        try { $null = Invoke-Adb -Arguments @('-s', $Serial, 'uninstall', 'com.bsecurity.app.test') }
        catch { Write-Warning 'No se pudo quitar el paquete temporal de pruebas.' }
    }
    if ($reverseCreated) {
        try { $null = Invoke-Adb -Arguments @('-s', $Serial, 'reverse', '--remove', "tcp:$port") }
        catch { Write-Warning 'No se pudo quitar el reenvío ADB temporal.' }
    }
    if ($fixture) {
        $fixture.Refresh()
        if (-not $fixture.HasExited) {
            Stop-Process -Id $fixture.Id -ErrorAction SilentlyContinue
            $fixture.WaitForExit(5000) | Out-Null
        }
        $fixture.Dispose()
    }
    # Verify the resolved absolute target is the unique direct child created above.
    if (Test-Path -LiteralPath $tempDirectory) {
        $resolved = (Resolve-Path -LiteralPath $tempDirectory).Path.TrimEnd([IO.Path]::DirectorySeparatorChar)
        $resolvedParent = [IO.Path]::GetDirectoryName($resolved)
        if ($resolvedParent -ne $tempParent -or [IO.Path]::GetFileName($resolved) -ne $tempName -or $tempName -notmatch '^BSecurityFamily-[0-9a-f]{32}$') {
            throw 'Se rechazó la limpieza: el directorio no corresponde al temporal de esta ejecución.'
        }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
