# Pruebas y verificación

Desde la raíz del proyecto, con Node.js 22+, JDK 17 o 21 y Android SDK 35:

```powershell
.\verify.ps1
```

El script busca Java en `JAVA_HOME`, el JBR de Android Studio y el PATH. Restaura `JAVA_HOME` al terminar y se detiene si falla un comando. Ejecuta las pruebas del servidor con cobertura, las pruebas JVM, la compilación de ambos APK y Android Lint. La primera ejecución necesita descargar Gradle y dependencias desde Google/Maven. En Linux/macOS se ejecuta con `pwsh ./verify.ps1` y el wrapper debe tener permiso de ejecución.

Para verificar únicamente el servidor, sin SDK ni Java:

```powershell
.\verify.ps1 -ServerOnly
```

## Cobertura funcional

- **Servidor:** 23 casos de autenticación, aislamiento de grupos, invitaciones concurrentes y de un uso, consentimiento estricto, retirada de reglas, caducidad exacta, límites de frecuencia y tamaño, persistencia tras reinicio y rollback si falla el disco. Se utilizan un reloj controlado y bases temporales; no se modifica `server/data` ni se necesita arrancar el servidor privado.
- **JVM:** reglas y presupuestos, intervalos de uso, medianoche local y cambios de horario, validación del endpoint HTTPS, configuración pública de Google/Supabase y regresiones en los tipos de seguridad Wi-Fi. No requieren dispositivo.
- **Router sin red:** estados TCP diferenciados, cancelación, alcance de puertos y direcciones, resultados incompletos y límites de las conclusiones. Todos los destinos de estos casos son sintéticos.
- **LAN sin red:** alcance IPv4 y prefijos, destinos privados, paquetes ICMP, respuestas HTTP completas, negociación SMB1, Telnet, compresión DNS y asociación SRV/TXT/A, NetBIOS, canales Wi-Fi y clasificación de dispositivos. Incluye respuestas truncadas y datos engañosos para evitar hallazgos e identidades sin evidencia.
- **LAN en Android:** cinco casos opcionales con sockets reales a `127.0.0.1`: cuatro Echo, HTTP fragmentado, SMB1 confirmado, rechazo de SMB2 como SMB1 y cancelación de una conexión bloqueada. Requieren una Wi-Fi estable para seleccionar la interfaz, pero no envían pruebas a otros equipos.
- **Protección administrada sin Android:** 12 casos JVM de autorización, guardado previo, recuperación de cambios interrumpidos, fallos parciales y restauración del estado anterior. No requieren Device Owner ni cambian políticas reales.
- **Android sin red:** 29 casos con las APIs reales de preferencias, PBKDF2, diagnóstico, Android Keystore y reglas protegidas. Incluyen revocación sin conexión, recuperación de sesión, respuestas HTTP y permisos de un grupo eliminado. Nueve casos de Google/Supabase comprueban el canje con nonce, cifrado, rotación persistente de refresh tokens, rechazo de credenciales, fallas transitorias, sesión revocada, cierre local y separación de proyectos. Tres casos verifican identidad, autorización y errores de la RPC del hogar; otros tres comprueban almacenamiento de protección, permisos de sus componentes y avisos del servicio. El transporte HTTPS se simula; no abren Google ni consultan Supabase. Cada ejecución usa preferencias con un identificador aleatorio, reinicia los datos entre casos y los elimina al terminar. No cambia el PIN, reglas ni consentimientos del usuario.
- **Familia con HTTPS local:** cinco casos adicionales con `FamilyClient`, Android Keystore y el servidor Node reales. Cubren certificado desconocido, identidad cifrada y persistente, invitaciones, reglas, revocación sin conexión, privacidad, salida, disolución del grupo y vencimiento de sesión. Se usan identidades lógicas separadas dentro de un mismo dispositivo.

La cobertura de Node corresponde a `server/server.mjs`; no incluye los archivos de pruebas. No implica cobertura de TLS, sensores físicos ni de la interfaz Android.

## Comprobaciones en Android

Con exactamente un dispositivo o emulador conectado por ADB, después de compilar:

```powershell
.\install-pilot.ps1 -RunChecks
```

Instala mediante actualización, abre la app, instala el paquete temporal de pruebas, ejecuta los 29 casos sin red y desinstala el paquete de pruebas. Si el paquete de pruebas ya estaba instalado, se detiene antes de modificar el dispositivo para conservarlo. Android puede solicitar confirmación de instalación.

Para incluir seis pruebas de la interfaz real:

```powershell
.\install-pilot.ps1 -VisualChecks
```

Se comprueban la carga con `bsoftware`, las cinco pestañas, los cambios rápidos, la conservación del desplazamiento y recreación, el regreso desde Inicio y el progreso y finalización del diagnóstico local. Solo se prepara temporalmente `interface.entered`, conservando su existencia y valor originales y restaurándolos al terminar. No se cambian PIN, reglas, permisos ni consentimientos. El resumen debe incluir `Interfaz visual: 6 aprobadas; 0 fallidas.`; junto a los 29 casos básicos se esperan 35 aprobadas. Si ya existe una sesión de Google real, la actividad puede verificarla, renovarla y consultar el hogar al abrirse; para aislar también ese tráfico, ejecutar en un dispositivo sin sesión de Google en B-Security.

El diagnóstico del router real requiere una opción adicional explícita:

```powershell
.\install-pilot.ps1 -VisualChecks -RouterChecks
```

Ejecuta nueve intentos TCP contra la puerta de enlace privada de la Wi-Fi actual y verifica su progreso y finalización. Solo usarlo en una red propia o autorizada. Se esperan 36 casos aprobados y `Router local: 1 aprobada; 0 fallidas.`. No accede al panel ni prueba credenciales. El alcance y la revisión manual del modelo están en [ROUTER-TEST.md](ROUTER-TEST.md).

Para probar ICMP y los transportes de reconocimiento dentro del propio dispositivo:

```powershell
.\install-pilot.ps1 -VisualChecks -LanChecks
```

Se esperan 40 casos aprobados: 29 básicos, seis visuales y cinco de transporte, con `Transporte LAN en loopback: 5 aprobadas; 0 fallidas.`. Esta ejecución no hace el barrido de la red ni consulta el servidor privado. El inventario y los cambios de Wi-Fi se verifican aparte con el procedimiento de [NETWORK-TEST.md](NETWORK-TEST.md).

Para incluir una consulta HTTPS de solo lectura al `/health` del servidor predeterminado del APK:

```powershell
.\install-pilot.ps1 -NetworkChecks
```

Esta comprobación adicional requiere conectividad y resolución DNS hacia ese endpoint; en el piloto privado, Tailscale. No crea grupos ni envía reportes. También se puede invocar directamente el runner, con los dos APK ya instalados:

```powershell
adb shell am instrument -w -r com.bsecurity.app.test/com.bsecurity.app.PilotInstrumentation
adb shell am instrument -w -r -e network true com.bsecurity.app.test/com.bsecurity.app.PilotInstrumentation
```

El resultado correcto contiene `0 fallidas` e `INSTRUMENTATION_CODE: -1`. El runner es una instrumentación propia; su resumen se entrega por ADB. Los pasos de sensores, permisos y Familia con dos teléfonos se documentan en [PILOT-TEST.md](PILOT-TEST.md).

## Familia completa contra un servidor temporal

Para el hogar con Google y el mapa integrado, seguir [HOME-TEST.md](HOME-TEST.md) y [MAP-TEST.md](MAP-TEST.md). La instrumentación opcional `-e home true -e map true` agrega una consulta autenticada de Supabase y cuatro comprobaciones de parser/mapa nativo. Con `-e visual true` se esperan **40 aprobadas** en total. Esta opción descarga mapas y consulta la sesión existente; no activa GPS ni modifica el hogar. Las pruebas JVM incluyen caducidad de marcadores y coordenadas inválidas.

La aplicación y restauración de políticas Device Owner y el servicio real se prueban únicamente con las opciones explícitas de [PROTECTION-TEST.md](PROTECTION-TEST.md), en un emulador desechable sin configuración parental previa. La verificación normal no registra un propietario ni aplica restricciones al teléfono conectado.

En Windows, además de Node.js 22+ y ADB, se necesita OpenSSL 1.1.1+ en PATH o incluido en Git for Windows. Después de compilar ambos APK, conectar y autorizar el teléfono o emulador:

```powershell
.\test-family.ps1
# Si ADB muestra varios equipos o dos conexiones al mismo teléfono:
.\test-family.ps1 -Serial <serial-mostrado-por-adb>
```

El script inicia `server/android-fixture.mjs` con certificado, clave y base efímeros, escucha solo en loopback y usa un reenvío ADB sin reemplazar ninguno existente. Instala los APK mediante actualización, ejecuta **34 casos** (29 sin red y cinco con HTTPS local) y elimina su paquete de pruebas, reenvío, proceso y archivos temporales al terminar. Si el paquete de pruebas ya existía, se detiene antes de modificar el dispositivo.

El resultado debe incluir `Familia HTTPS aislada: 5 pruebas ejecutadas.`, `34 aprobadas; 0 fallidas.` e `INSTRUMENTATION_CODE: -1`. El marcador evita aceptar un APK de pruebas antiguo que ignore los argumentos nuevos.

Las conexiones de prueba confían únicamente en el certificado generado para esa ejecución, sin instalarlo en Android ni cambiar la confianza o comprobación de hostname de la app normal. Se comprueba también que el cliente normal lo rechace. No se usa `server/data`, el servidor privado, el grupo real ni la ubicación del teléfono; los reportes contienen apps y coordenadas sintéticas. El endpoint de avance del reloj existe solo en el fixture y no cambia la hora de Android.

Esto valida el protocolo y la aplicación de reglas con Android real. La navegación, permisos de sensores, funcionamiento entre dos teléfonos físicos y bloqueo en MIUI requieren sus comprobaciones adicionales.

## Resultados y CI

- Pruebas JVM: `app/build/reports/tests/testDebugUnitTest/index.html` y XML en `app/build/test-results/testDebugUnitTest/`.
- Lint: `app/build/reports/lint-results-debug.html`.
- APK principal: `app/build/outputs/apk/debug/app-debug.apk`.
- APK de instrumentación: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.
- Servidor: resumen y cobertura en la salida del comando.

El workflow [.github/workflows/verify.yml](.github/workflows/verify.yml) ejecuta la verificación en pushes y pull requests y conserva APKs e informes durante 14 días. Compila la instrumentación; su ejecución requiere un dispositivo y se realiza aparte. CI no sondea una red real ni utiliza la configuración privada local.

Las comprobaciones realizadas y sus límites están en [VALIDATION.md](VALIDATION.md).
