# Resultados de validación

## Comprobaciones ejecutadas

- `verify.ps1`: las 23 pruebas del servidor pasaron; también finalizaron correctamente las pruebas unitarias Android, la compilación debug, la compilación del APK de pruebas instrumentadas y lint.
- APK demo compilada con `-PbsecurityDemo=true`: endpoint familiar de ejemplo (`https://family.example.invalid`), `versionCode 3` y `versionName 0.2.0-demo`.
- La configuración cliente pública de Google OAuth y Supabase está incluida en los recursos de la app. No se incluyen credenciales privadas ni el endpoint local.

## Alcance

`assembleDebugAndroidTest` compila el APK de pruebas instrumentadas, pero no ejecuta esas pruebas en un dispositivo o emulador. Para las comprobaciones manuales, seguir [PILOT-TEST.md](PILOT-TEST.md) y registrar los resultados aquí.
