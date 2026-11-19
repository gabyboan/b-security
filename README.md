# B-Security

Proyecto personal de ciberseguridad, basado en lo aprendido en un curso de Cisco y en proyectos de seguridad y sistemas embebidos.

Piloto Android independiente en Kotlin nativo. Android 8+ (API 26); compilado para API 35. Uso local sin cuenta e ingreso opcional con Google mediante Supabase Auth. Configuración y prueba del ingreso en [GOOGLE-AUTH.md](GOOGLE-AUTH.md).

Pruebas realizadas y comprobaciones pendientes en [VALIDATION.md](VALIDATION.md). Verificación automática en [TESTING.md](TESTING.md). Pasos para instalar y probar en el teléfono: [PILOT-TEST.md](PILOT-TEST.md).

## Funciones implementadas

- Interfaz de B-Software con presentación de inicio de aproximadamente 2,8 segundos, navegación persistente y transiciones más pausadas entre secciones. El diagnóstico muestra una animación y avance según las lecturas reales; respeta las animaciones desactivadas en Android.
- Google: selector nativo de cuentas con Credential Manager, validación de la credencial y nonce en Supabase, sesión cifrada con Android Keystore, renovación y cierre de la sesión de este teléfono. Disponible en la bienvenida y en Familia. El creador de un hogar conectado es administrador por autorización del servidor.
- Estado del teléfono: parche y antigüedad, bloqueo de pantalla, cifrado informado por Android, depuración USB, presencia de VPN y DNS privado.
- Wi-Fi: red actual y redes cercanas con canal, banda, frecuencia, ancho anunciado, BSSID, señal y antigüedad. Resumen de puntos de acceso por canal; no mide tráfico ni interferencia. Android exige ubicación precisa y puede limitar los escaneos.
- Red local: Echo Request ICMP con respuestas, pérdida y RTT; descubrimiento TCP, mDNS y NetBIOS; nombres, grupos de trabajo, servicios y clasificación orientativa de PC, teléfonos (incluido Xiaomi Redmi Note 11 cuando hay evidencia), iPhone/iPad, impresoras y equipos multimedia, con iconos por tipo. Prueba de hasta tres IP privadas para comprobar accesibilidad entre subredes. Todo ligado a la Wi-Fi, con avance y cancelación. Alcance y pruebas en [NETWORK-TEST.md](NETWORK-TEST.md).
- Hallazgos de red comprobados por protocolo: SMB1 negociable, Basic ofrecido por HTTP sin TLS y negociación Telnet sin TLS. Cada hallazgo identifica IP, puerto y evidencia; un puerto abierto o un banner de versión no confirman una CVE.
- Router: sondeo TCP de nueve puertos de la puerta de enlace IPv4 privada de la Wi-Fi actual, ligado a esa interfaz. Distingue conexiones aceptadas, no aceptadas, tiempos agotados y errores, y cancela si cambia la red o su puerta de enlace. Perfil opcional FiberHome HG6144F con recomendaciones separadas de los resultados. Requiere confirmar autorización. Alcance y pasos manuales en [ROUTER-TEST.md](ROUTER-TEST.md).
- Bluetooth: equipos vinculados y anuncios BLE durante diez segundos. Estar visible no demuestra una vulnerabilidad; no identifica CVE.
- GPS/GNSS: posición nueva, precisión, antigüedad, satélites visibles/usados y C/N₀ en la pantalla de diagnóstico. El hogar tiene un servicio de ubicación separado, iniciado por el usuario y con notificación visible; puede continuar al salir de la pantalla.
- Parental: PIN local de 6–12 dígitos con PBKDF2, cinco intentos y pausa temporal; bloqueo de apps y presupuesto diario. Acceso al uso y accesibilidad se habilitan desde Android. Excluye B-Security, Ajustes, launcher y telefonía de emergencia.
- Protección de la app: pantalla en Control con estado real de permisos, notificaciones y batería, guía Xiaomi/Redmi y continuidad parental opcional mediante servicio visible y silencioso. Recupera la continuidad solicitada después de reiniciar o actualizar; no inicia ubicación ni sincronización familiar. En un dispositivo preparado como Device Owner, permite aplicar políticas solo sobre B-Security: bloqueo de desinstalación, restricción de detención/borrado de datos desde Android 11 y permiso de notificaciones fijado desde Android 13. Cada cambio requiere PIN; conserva las políticas anteriores para restaurarlas. Preparación, salida y pruebas en [PROTECTION-TEST.md](PROTECTION-TEST.md).
- Hogar conectado: grupos en Supabase con cuenta Google, administrador por cuenta creadora, vinculación con código aleatorio de un uso (15 minutos) y listado de teléfonos. El botón GPS del mapa permite iniciar la ubicación compartida con confirmación y centrar este teléfono; envío cada cinco segundos mediante servicio visible con notificación. El texto compartido de una invitación contiene solo el código. MapLibre con OpenFreeMap, sin clave ni facturación de Google Maps. Sin Tailscale ni PC encendida. Pasos y límites en [HOME-TEST.md](HOME-TEST.md) y [MAP-TEST.md](MAP-TEST.md).
- Grupo anterior de Familia: conserva el servidor privado, sus reportes y las reglas remotas. Sincroniza manualmente y cada 30 segundos mientras Familia está abierta. El responsable solo puede editar apps compartidas por el integrante. Se accede desde «Opciones del grupo anterior».

Un teléfono común permite revocar accesibilidad, permisos, desinstalar y modificar la hora, incluso con continuidad activada. El modo administrado requiere preparar el dispositivo por separado; instalar la app o activar el antiguo permiso de administrador no lo obtiene. Retirar las políticas adicionales con PIN no elimina Device Owner: para salir completamente de una instalación administrada normalmente se debe restablecer el equipo. La app no ejecuta ese borrado ni bloquea el ajuste de restablecimiento. El bloqueo de otras apps sigue volviendo al inicio; no suspende sus paquetes y puede fallar en ventanas múltiples o según el fabricante. El presupuesto puede tardar unos segundos en actuar y se reinicia a medianoche local.

Las lecturas son indicadores limitados, no una certificación de ausencia de vulnerabilidades o malware. No se leen pantallas, contraseñas, mensajes ni tráfico de otras apps.

## Instalar y usar

Demo **0.2.0-demo** (código Android 3): [descargar desde GitHub Releases](https://github.com/gabyboan/b-security/releases/tag/v0.2.0-demo). APK de desarrollo: `app/build/outputs/apk/debug/app-debug.apk`. Actualizaciones mediante `adb install -r` conservan datos; no desinstalar para actualizar. La firma de desarrollo no es apta para distribuir una versión comercial.

1. Abrir B-Security y usar Estado, Red y GPS sin cuenta.
2. En Control, el responsable crea su PIN, habilita acceso al uso y el servicio B-Security en Accesibilidad, añade reglas y activa el control.
3. Android puede exigir «Permitir ajustes restringidos» en la ficha de la app por ser una instalación externa. No se cambian estas protecciones de forma oculta.
4. En Familia, ingresar con Google y **Crear o recuperar mi hogar → Invitar otro teléfono**. Instalar el mismo APK en el otro Android, ingresar con su cuenta y elegir **Unirme con código**.
5. En el teléfono que comparte, habilitar **Compartir mi ubicación** o iniciar la compartición desde el botón GPS del mapa; conceder ubicación y notificaciones. En el administrador, abrir **Mapa en vivo del hogar**, elegir el teléfono y activar **Seguir teléfono**. El envío y la consulta del mapa se realizan aproximadamente cada cinco segundos. Se puede detener desde la notificación o la app. La prueba completa está en [HOME-TEST.md](HOME-TEST.md).
6. El grupo anterior está en **Opciones del grupo anterior** y requiere Tailscale y su servidor HTTPS encendido. Sus reglas remotas necesitan sincronización en primer plano y control parental activo en el integrante. Es independiente del hogar conectado.

## Servidor privado de Familia

Node.js sin dependencias externas, base JSON local y un único proceso. TLS válido y escucha exclusivamente en la IPv4 Tailscale de esta PC, puerto 9443; no se altera Serve 443 de ControlB ni se abre un reenvío en el router. La PC debe estar encendida y conectada a Tailscale.

```powershell
.\server\start.ps1
.\server\start.ps1 -Stop
.\server\install-startup.ps1
# Para quitar el arranque al iniciar sesión:
.\server\install-startup.ps1 -Remove
node --test server/server.test.mjs
```

`start.ps1` protege `server/data` mediante ACL para el usuario y SYSTEM, obtiene el certificado de Tailscale y arranca Node oculto. Certificados, clave TLS, base y logs quedan fuera de Git. El proceso intenta renovar y recargar el certificado cada 12 horas. El certificado también se revisa al arrancar. Fallos de renovación quedan en `server/data/stderr.log`.

El certificado HTTPS usa el nombre público del nodo en Certificate Transparency; el servicio sigue siendo privado por su interfaz y la política de Tailscale. Ver [documentación de certificados Tailscale](https://tailscale.com/docs/how-to/set-up-https-certificates).

Tokens de dispositivo aleatorios de 256 bits, hash en servidor y cifrado con Android Keystore en el teléfono. Sin contraseña de usuario y sin recuperación de identidad en esta versión: reinstalar o borrar datos pierde el vínculo. Las sesiones inactivas vencen a los 90 días y se depuran al recibir solicitudes. El servidor guarda solo el último reporte por dispositivo. Los datos de la base están protegidos por ACL, no cifrados por la aplicación en disco; no guardar ni compartir esa carpeta.

Las respuestas no tienen caché; límites de tamaño, frecuencia, cantidad de integrantes y validación de reglas. HTTPS nunca desactiva validación de certificados. No es un servicio multiempresa ni tiene auditoría externa, respaldo automático o alta disponibilidad.

El servidor exige consentimiento booleano explícito para los informes. Retirar el permiso de controles elimina la política remota; dejar de compartir una app elimina su regla. Volver a consentir no recupera reglas retiradas. La renovación de sesiones se guarda también cuando el dispositivo todavía no pertenece a un grupo.

La app elimina las reglas remotas al revocar controles y guarda la revocación pendiente si no hay conexión. Antes de volver a compartir o recibir reglas, confirma esa retirada con el servidor, aunque ya se haya vuelto a aceptar. Si el grupo dejó de existir, restablece ambos permisos; una sesión vencida elimina la identidad y permisos locales, muestra el motivo y permite volver a vincularse tras actualizar. No repite automáticamente operaciones bajo otra identidad. El PIN y las reglas locales se conservan.

## Compilar y verificar

Requiere Node.js 22+, JDK 17 o 21, SDK Android 35 y acceso a repositorios Google/Maven.

La compilación limita Gradle a 1 GB de heap y un trabajador, con Kotlin en el mismo proceso, para reducir el consumo de memoria. `verify.ps1` selecciona un JDK 17/21, incluido el JBR de Android Studio. Si Java termina con `Native memory allocation failed`, revisar la memoria disponible y evitar compilar mientras corre el emulador. `argv.json` configura VS Code; sus opciones de renderizado no ajustan la memoria de Gradle.

En esta PC, `.vscode/settings.json` selecciona el JBR 21 de Android Studio para la extensión Gradle. Si el editor conserva `Unsupported class file major version 68`, recargar su ventana para que deje de usar Java 24, que requiere Gradle 8.14 o posterior; ver [compatibilidad de Java y Gradle](https://docs.gradle.org/current/userguide/compatibility.html). En otra PC, adaptar esa ruta al JDK instalado.

La dirección privada del servidor se guarda únicamente en `pilot.local.properties`, excluido de Git:

```properties
family.endpoint=https://tu-servidor-privado.example:9443
```

Si ese archivo no existe, el APK usa `https://family.example.invalid` y la dirección se puede configurar desde Familia. Los APK locales incluyen la dirección configurada al compilarlos; no deben publicarse como artefactos si esa dirección es privada. La demo pública se compila con `-PbsecurityDemo=true`, que fuerza el valor de ejemplo aunque exista `pilot.local.properties`. GitHub Actions compila con ese valor y no recibe la configuración, base, certificados ni claves locales.

```powershell
.\verify.ps1
# Solo pruebas y cobertura del servidor:
.\verify.ps1 -ServerOnly
# Instalar y ejecutar las comprobaciones Android:
.\install-pilot.ps1 -RunChecks
# Interfaz y router de una Wi-Fi propia o autorizada:
.\install-pilot.ps1 -VisualChecks -RouterChecks
# Transportes LAN aislados dentro del dispositivo, sin sondear otros equipos:
.\install-pilot.ps1 -VisualChecks -LanChecks
# Familia completa contra HTTPS temporal (también requiere OpenSSL):
.\test-family.ps1
```

La instrumentación ejecuta 26 casos sin red con preferencias temporales separadas: PIN, pausa y recuperación, apps protegidas, persistencia, combinación y revocación de reglas, cambio de servidor, límites, diagnóstico y ciclo de consentimiento/sesión. Incluye nueve casos de autenticación y tres de RPC del hogar con HTTPS simulado y Android Keystore real. No configura el PIN ni las reglas reales del usuario ni ingresa en una cuenta de Google. La consulta al servidor predeterminado es opcional con `-NetworkChecks`; `test-family.ps1` añade cinco casos completos contra un servidor HTTPS temporal, sin contactar el servicio privado. La opción de instrumentación `-e home true` verifica la sesión real y la consulta del hogar, sin crear un grupo ni activar GPS. El workflow de GitHub Actions ejecuta pruebas del servidor y JVM, compila ambos APK y ejecuta Lint; ver [TESTING.md](TESTING.md).

## Pendientes explícitos

- Red: IPv6, MAC/fabricante, inventario autenticado de software, identificación de switches físicos y comprobaciones de CVE específicas. La clasificación por anuncios es orientativa; una puerta de enlace indica función de router y no permite deducir los switches del recorrido.
- Hogar: validar vinculación, posiciones y batería con dos teléfonos físicos. El servicio visible ya está implementado; no garantiza continuidad bajo las restricciones de cada fabricante. QR, historial de rutas y avisos de llegada quedan pendientes.
- Google: el usuario confirmó el ingreso real con el APK de desarrollo el 19 de noviembre de 2026. Repetir esa prueba al registrar una nueva firma de distribución.
- Administración Device Owner para restricciones resistentes a cambios, mediante enrolamiento específico. No es parte de este APK personal.
- Publicación comercial: firma de producción, revisión de accesibilidad y datos de ubicación, consentimiento y requisitos de distribución aplicables.

Fuentes de APIs: [escaneo Wi-Fi](https://developer.android.com/develop/connectivity/wifi/wifi-scan), [permisos Bluetooth](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions), [GNSS](https://developer.android.com/reference/android/location/GnssStatus), [accesibilidad](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService).
