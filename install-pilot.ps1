param([switch]$RunChecks, [switch]$NetworkChecks, [switch]$VisualChecks, [switch]$RouterChecks, [switch]$LanChecks)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$apk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
$testApk = Join-Path $projectRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
if (-not (Test-Path -LiteralPath $apk)) { throw "Falta el APK. Compilá con .\gradlew.bat :app:assembleDebug" }
if (($RunChecks -or $NetworkChecks -or $VisualChecks -or $RouterChecks -or $LanChecks) -and -not (Test-Path -LiteralPath $testApk)) {
    throw 'Falta el APK de prueba. Compilá con .\gradlew.bat :app:assembleDebugAndroidTest'
}

$adbCommand = Get-Command adb -ErrorAction SilentlyContinue
$adbPath = if ($adbCommand) { $adbCommand.Source } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe' }
if (-not (Test-Path -LiteralPath $adbPath)) { throw 'No se encontró ADB. Instalá Android SDK Platform-Tools.' }

$deviceLines = @(& $adbPath devices -l 2>&1 | ForEach-Object { $_.ToString() })
$devices = @($deviceLines | Where-Object { $_ -match '^(\S+)\s+device(?:\s|$)' } | ForEach-Object { ([regex]::Match($_, '^(\S+)')).Groups[1].Value })
if ($devices.Count -ne 1) {
    Write-Host 'Dispositivos detectados:'
    $deviceLines | Select-Object -First 12 | ForEach-Object { Write-Host $_ }
    throw 'Conectá exactamente un teléfono por ADB y aceptá la depuración en Android.'
}
$deviceSerial = $devices[0]
if ($RunChecks -or $NetworkChecks -or $VisualChecks -or $RouterChecks -or $LanChecks) {
    $installedPackages = @(& $adbPath -s $deviceSerial shell pm list packages 'com.bsecurity.app.test' 2>&1 | ForEach-Object { $_.ToString() })
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo comprobar si el paquete de pruebas ya está instalado. No se modificó ningún paquete.' }
    if ($installedPackages -contains 'package:com.bsecurity.app.test') {
        throw 'El paquete com.bsecurity.app.test ya está instalado. Quitalo antes de continuar si ya no lo necesitás, o usá otro teléfono. No se modificó ningún paquete.'
    }
}
Write-Host "Instalando B-Security en $deviceSerial (conserva datos)..."
& $adbPath -s $deviceSerial install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'Android rechazó o canceló la instalación. Desbloqueá el teléfono y aceptá el aviso de MIUI.' }
& $adbPath -s $deviceSerial shell am start -n 'com.bsecurity.app/.MainActivity'
if ($LASTEXITCODE -ne 0) { throw 'Se instaló el APK, pero no se pudo abrir B-Security.' }
Write-Host 'B-Security instalada y abierta.'

if ($RunChecks -or $NetworkChecks -or $VisualChecks -or $RouterChecks -or $LanChecks) {
    $testInstalled = $false
    try {
        & $adbPath -s $deviceSerial install -r $testApk
        if ($LASTEXITCODE -ne 0) { throw 'Android canceló la instalación del paquete temporal de pruebas.' }
        $testInstalled = $true
        $instrumentArgs = @('-s', $deviceSerial, 'shell', 'am', 'instrument', '-w', '-r')
        if ($NetworkChecks) { $instrumentArgs += @('-e', 'network', 'true') }
        if ($VisualChecks) { $instrumentArgs += @('-e', 'visual', 'true') }
        if ($RouterChecks) { $instrumentArgs += @('-e', 'router', 'true') }
        if ($LanChecks) { $instrumentArgs += @('-e', 'lan', 'true') }
        $instrumentArgs += 'com.bsecurity.app.test/com.bsecurity.app.PilotInstrumentation'
        $checkOutput = & $adbPath @instrumentArgs 2>&1 | Out-String
        Write-Host ($checkOutput.Substring(0, [Math]::Min(4000, $checkOutput.Length)))
        if ($LASTEXITCODE -ne 0 -or $checkOutput -notmatch 'INSTRUMENTATION_CODE:\s*-1' -or $checkOutput -match 'FALLO:') {
            throw 'Falló alguna comprobación. Revisá el resumen anterior.'
        }
        if ($VisualChecks -and $checkOutput -notmatch 'Interfaz visual: [1-9]\d* aprobadas; 0 fallidas\.') {
            throw 'El APK de pruebas no confirmó las comprobaciones visuales. Volvé a compilarlo.'
        }
        if ($RouterChecks -and $checkOutput -notmatch 'Router local: 1 aprobada; 0 fallidas\.') {
            throw 'El APK de pruebas no confirmó la comprobación del router. Volvé a compilarlo.'
        }
        if ($LanChecks -and $checkOutput -notmatch 'Transporte LAN en loopback: 5 aprobadas; 0 fallidas\.') {
            throw 'El APK de pruebas no confirmó las pruebas de transporte LAN. Volvé a compilarlo.'
        }
        Write-Host 'Comprobaciones del teléfono: OK.'
    } finally {
        if ($testInstalled) { & $adbPath -s $deviceSerial uninstall 'com.bsecurity.app.test' | Out-Null }
    }
}
