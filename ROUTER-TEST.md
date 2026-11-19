# Revisión del router

La prueba se ejecuta únicamente contra la puerta de enlace IPv4 privada de la Wi-Fi actual. No recorre otros equipos, no intenta contraseñas y no modifica el router. Los resultados permanecen en la app; no se envían a Familia.

## Qué se mide

Se intenta una conexión TCP a los puertos 21, 22, 23, 53, 80, 443, 445, 8080 y 8443. Una conexión aceptada confirma accesibilidad desde esa Wi-Fi; el número de puerto no identifica por sí solo el protocolo. Un tiempo agotado puede deberse a filtrado o falta de respuesta. Ninguno de estos resultados prueba ausencia de vulnerabilidades ni exposición desde Internet.

Si cambia la Wi-Fi o su puerta de enlace, la prueba se cancela. No se usan direcciones públicas como destino ni se analiza la interfaz de una VPN.

## Perfil FiberHome HG6144F

El perfil se elige localmente y se puede quitar. Es una referencia del modelo indicado, no una identificación automática ni una lectura de su firmware o configuración.

La [ficha oficial de FiberHome](https://en.fiberhome.com/ONTsSeries/20240103/45831.html) describe un ONT GPON con Wi-Fi 6 y gestión del proveedor mediante OMCI/TR-069. La [certificación de Wi-Fi Alliance](https://api.cert.wi-fi.org/api/certificate/download/public?variantId=113340) incluye WPA2/WPA3 Personal; esto no confirma qué opciones habilita un firmware del operador ni cuál está activa en una red.

Para completar la revisión desde el panel autorizado:

1. Anotar la versión exacta del firmware y consultar al proveedor por actualizaciones para esa revisión de hardware. Usar únicamente firmware suministrado por el proveedor o fabricante.
2. Revisar el modo de protección Wi-Fi. Preferir WPA3 Personal cuando el firmware y los dispositivos lo permitan; verificar también la configuración de la red de invitados.
3. Revisar WPS y desactivarlo si no se utiliza, mediante las opciones del panel.
4. Revisar las opciones de administración: acceso desde Internet, HTTPS y credenciales propias del administrador. Una conexión HTTP en la LAN no prueba acceso remoto.
5. Revisar UPnP y los reenvíos de puertos que ya existan. No desactivar la gestión del operador, cambiar WAN/GPON ni restaurar el equipo durante estas pruebas.

El modelo sin la revisión de hardware y firmware no permite atribuir una CVE al equipo. Estos pasos son manuales; B-Security los mantiene separados de las comprobaciones TCP.

## Prueba desde Android

Después de compilar ambos APK, con un único dispositivo conectado y permiso del administrador de la red:

```powershell
.\install-pilot.ps1 -RouterChecks
# Incluir también la navegación y el diagnóstico visual:
.\install-pilot.ps1 -VisualChecks -RouterChecks
```

`-RouterChecks` autoriza el sondeo limitado del router de esa Wi-Fi durante la instrumentación. No necesita acceso al panel. El resultado debe incluir `Router local: 1 aprobada; 0 fallidas.`; se comprueban el avance y la finalización del diagnóstico, independientemente de que el router acepte o filtre conexiones. Los casos con estados y direcciones sintéticos se ejecutan en la JVM mediante `verify.ps1`.

No publicar capturas del panel, SSID, direcciones, números de serie, credenciales ni resultados de una red particular. Las verificaciones locales adicionales se guardan en directorios excluidos de Git.
