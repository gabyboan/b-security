param([switch]$Remove)
$ErrorActionPreference = 'Stop'
$shortcutPath = Join-Path ([Environment]::GetFolderPath('Startup')) 'B-Security Familia.lnk'
if ($Remove) {
    if (Test-Path -LiteralPath $shortcutPath) { Remove-Item -LiteralPath $shortcutPath }
    Write-Output 'Inicio automático de Familia quitado.'; return
}
$launcher = Join-Path $PSScriptRoot 'start.ps1'
$shell = New-Object -ComObject WScript.Shell
$link = $shell.CreateShortcut($shortcutPath)
$link.TargetPath = Join-Path $PSHOME 'powershell.exe'
$link.Arguments = '-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + $launcher + '"'
$link.WorkingDirectory = Split-Path -Parent $PSScriptRoot
$link.WindowStyle = 7
$link.Description = 'B-Security Familia por Tailscale privado'
$link.Save()
Write-Output 'Familia arrancará oculta al iniciar sesión de Windows.'
