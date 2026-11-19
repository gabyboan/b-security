#Requires -Version 5.1
[CmdletBinding()]
param([switch]$ServerOnly)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-Checked {
    param([string]$Executable, [string[]]$Arguments)
    Write-Host "`n> $Executable $($Arguments -join ' ')"
    $previousErrorAction = $ErrorActionPreference
    try {
        # Windows PowerShell can represent native stderr as an ErrorRecord even on success.
        $ErrorActionPreference = 'Continue'
        & $Executable @Arguments
        $commandExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousErrorAction }
    if ($commandExit -ne 0) { throw "Falló el comando (código $commandExit): $Executable" }
}

function Find-Java {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    if ($env:ProgramFiles) { $candidates += Join-Path $env:ProgramFiles 'Android/Android Studio/jbr' }
    if ($env:LOCALAPPDATA) { $candidates += Join-Path $env:LOCALAPPDATA 'Programs/Android Studio/jbr' }
    $candidates += '/Applications/Android Studio.app/Contents/jbr/Contents/Home'
    $javaCommand = Get-Command java -ErrorAction SilentlyContinue
    if ($javaCommand) { $candidates += Split-Path -Parent (Split-Path -Parent $javaCommand.Source) }
    $javaName = if ($env:OS -eq 'Windows_NT') { 'java.exe' } else { 'java' }
    foreach ($candidate in $candidates) {
        $release = Join-Path $candidate 'release'
        $executable = Join-Path $candidate "bin/$javaName"
        if ((Test-Path -LiteralPath $release) -and (Test-Path -LiteralPath $executable)) {
            $metadata = Get-Content -LiteralPath $release -Raw
            if ($metadata -match 'JAVA_VERSION="(17|21)(?:\.|\")') {
                $env:JAVA_HOME = $candidate
                return $executable
            }
        }
    }
    throw 'No se encontró JDK 17 o 21. Configurá JAVA_HOME o instalá Android Studio con su JBR.'
}

$previousJavaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Process')
Push-Location -LiteralPath $PSScriptRoot
try {
    $nodeCommand = Get-Command node -ErrorAction Stop
    Invoke-Checked -Executable $nodeCommand.Source -Arguments @('--version')
    $nodeVersion = & $nodeCommand.Source --version
    if ($LASTEXITCODE -ne 0 -or $nodeVersion -notmatch '^v(\d+)\.' -or [int]$Matches[1] -lt 22) {
        throw 'Se requiere Node.js 22 o superior.'
    }
    Invoke-Checked -Executable $nodeCommand.Source -Arguments @('--test', '--experimental-test-coverage', '--test-coverage-include=server/server.mjs', 'server/server.test.mjs')
    if (-not $ServerOnly) {
        $javaExecutable = Find-Java
        Invoke-Checked -Executable $javaExecutable -Arguments @('--version')
        $wrapper = if ($env:OS -eq 'Windows_NT') { 'gradlew.bat' } else { 'gradlew' }
        Invoke-Checked -Executable (Join-Path $PSScriptRoot $wrapper) -Arguments @('--no-daemon', '--console=plain', ':app:testDebugUnitTest', ':app:assembleDebug', ':app:assembleDebugAndroidTest', ':app:lintDebug')
    }
    Write-Host "`nVerificación completada correctamente."
} finally {
    [Environment]::SetEnvironmentVariable('JAVA_HOME', $previousJavaHome, 'Process')
    Pop-Location
}
