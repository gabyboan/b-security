# Prueba del piloto en Android

Para vincular otro Android con Google y ver su ubicación sin Tailscale, seguir [HOME-TEST.md](HOME-TEST.md). Las instrucciones del servidor privado de esta página corresponden al grupo anterior.

El APK está en `app/build/outputs/apk/debug/app-debug.apk`. Con el teléfono desbloqueado y visible en `adb devices`, desde PowerShell en esta carpeta:

```powershell
.\install-pilot.ps1 -RunChecks
```

Primero compilá y verificá con `.\verify.ps1`. El comando de instalación actualiza B-Security sin borrar sus datos, abre la app, ejecuta 26 comprobaciones aisladas sin red y quita el paquete temporal de pruebas. MIUI puede pedir confirmar ambas instalaciones. Para agregar la comprobación HTTPS del servidor predeterminado, usá `.\install-pilot.ps1 -NetworkChecks` con Tailscale conectado. Si no hay ADB, transferí el APK al teléfono y abrilo allí; las comprobaciones automáticas quedan para más adelante. Más detalles en [TESTING.md](TESTING.md).

Para comprobar además el flujo de Familia con identidades de prueba separadas y un servidor HTTPS descartable, ejecutá `.\test-family.ps1` después de compilar. Si ADB muestra más de una conexión, indicá `-Serial <serial>`. Requiere OpenSSL y ejecuta 31 casos en total sin tocar el grupo ni la ubicación reales. Esta prueba automática complementa la prueba manual con dos teléfonos.

1. **Estado:** verificar parche, bloqueo, cifrado, VPN y DNS privado. Son indicadores del sistema, no un diagnóstico de malware.
2. **Red:** conceder ubicación precisa y activarla en Android. Escanear Wi-Fi y observar si Android entrega resultados recientes o de caché. Sondear el router solo de la red propia y con la confirmación de la app. Conceder Bluetooth para ver vinculados y anuncios BLE.
3. **GPS:** iniciar al aire libre, esperar precisión y satélites y comprobar que los datos se actualicen. Al salir, la medición se detiene. Compartir ubicación con Familia es una opción aparte, apagada de inicio.
4. **Control:** crear un PIN que solo conozca el responsable. En Android, habilitar acceso al uso y el servicio B-Security de Accesibilidad; MIUI puede requerir «Permitir ajustes restringidos». Crear una regla para una app de prueba, activarla e intentar abrirla. Retirar la regla al terminar. El bloqueo personal puede revocarse desde Ajustes; no es Device Owner.
5. **Familia:** mantener PC y celulares conectados a Tailscale. El responsable crea un grupo e invitación; otro celular se une con el código de un uso. La ubicación y las reglas remotas requieren consentimientos separados en el integrante y sincronización con Familia abierta. Probar una regla, revocarla y sincronizar de nuevo. Con un solo teléfono no se puede completar esta prueba entre dos personas.

Si una prueba falla, anotar la pantalla, la acción y el mensaje exacto, sin compartir PIN, ubicación precisa, invitaciones ni tokens.
