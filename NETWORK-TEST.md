# Reconocimiento y diagnóstico de red

En **Redes y dispositivos**, los resultados se separan en Wi-Fi, Dispositivos, Router y administración, y Bluetooth cercano. Escanear Wi-Fi muestra hasta 30 puntos de acceso ordenados de mayor a menor señal, los agrupa por calidad estimada, y resume canales por banda y número de redes. También muestra frecuencia, ancho anunciado, BSSID y antigüedad. Las categorías de señal son orientativas; los conteos por canal no miden ocupación del aire. Se mantienen las restricciones de ubicación y frecuencia de escaneo de Android.

## Alcance del reconocimiento

**Reconocer equipos de mi red** presenta la IPv4, prefijo, interfaz y puerta de enlace antes de comenzar. La lista separa «Este teléfono», cuyo fabricante/modelo lee Android localmente, de los demás equipos reconocidos por respuestas de red; no se sondea la propia IP. Trabaja sobre la Wi-Fi seleccionada y se detiene si cambia su dirección, interfaz o gateway. Al salir de Red o enviar la app a segundo plano se cierran las conexiones. También existe un botón para detenerlo.

- Hasta 256 destinos privados: la subred local, recortada al bloque de 256 direcciones que contiene al teléfono si es más amplia, más la puerta de enlace privada. Excluye el teléfono y las direcciones de red y broadcast del prefijo, salvo los casos punto a punto `/31` y `/32`.
- Un Echo por dirección y cuatro al gateway. Muestra paquetes enviados, respuestas, pérdida y RTT mínimo/promedio/máximo. Si Android impide crear ICMP, indica que la medición no estuvo disponible, sin atribuirle una pérdida del 100 %.
- Descubrimiento TCP en 22, 80, 443, 445, 3389 y 9100; consulta NetBIOS UDP 137; mDNS durante 2,5 segundos con respuestas unicast al puerto efímero de la consulta. Asocia anuncios mediante SRV, TXT y A, sin atribuirlos automáticamente al remitente UDP.
- En hasta 64 equipos con respuesta amplía TCP a 21, 22, 23, 80, 443, 445, 3389, 8080, 8443 y 9100. Lee saludos SSH/FTP/Telnet, cabeceras HTTP y negociación SMB1. Un puerto se identifica como conexión aceptada; el servicio necesita su propia evidencia de protocolo.
- Doce trabajadores y un máximo de 90 segundos. Un reconocimiento cancelado o vencido no se publica como un inventario completo.

**Probar otras subredes** admite hasta tres IPv4 privadas concretas, con cuatro Echo y los diez puertos por destino. Indica si cada IP pertenece a la subred del teléfono y si respondió. Alcanzar una IP no prueba acceso al resto de su red ni una falla de aislamiento: debe compararse con la segmentación prevista. **Elegir otra Wi-Fi en Android** abre los ajustes para conectarse con el mecanismo normal del sistema.

## Evidencia e identificación

Los modelos y sistemas se clasifican como probables a partir de metadatos anunciados o banners reconocidos: Android, Xiaomi Redmi Note 11 (cuando se anuncia su nombre o un código de modelo compatible), iPhone/iPad, PC Windows/Mac/Linux, impresoras, Cast y AirPlay. Las tarjetas muestran un icono según la clase detectada. Un nombre elegido por el usuario, como «iPhone de Ana», y un puerto abierto no bastan para atribuir un sistema. Los equipos sin evidencia quedan sin clasificar. No responder puede deberse a suspensión, firewall o aislamiento de clientes.

La comprobación del router incluye el puerto SSH 22 y puede identificar su saludo, pero no inicia sesión, prueba credenciales ni ejecuta comandos. El botón del panel web abre HTTPS o HTTP, a elección del usuario, en el navegador del sistema; B-Security no ve ni guarda las credenciales. La revisión de ajustes se realiza allí, porque los comandos SSH y las opciones disponibles dependen del modelo y firmware.

NetBIOS separa nombres de equipo de grupos de trabajo/dominios anunciados. Esto no es Grupo Hogar. Microsoft retiró Grupo Hogar desde Windows 10 1803; ver [la explicación de Microsoft](https://support.microsoft.com/en-gb/windows/experience/connectivity-networking/homegroup-removed-from-windows-10-version-1803).

La puerta de enlace indica una función de router. Un switch de capa 2 no es un salto IP: su presencia y conexiones físicas requieren información adicional de administración o protocolos como LLDP/SNMP. El inventario completo de programas de otras máquinas requiere un agente o credenciales; aquí solo se muestran los servicios, versiones y modelos que los equipos divulgan.

Se informan estas configuraciones inseguras únicamente tras recibir evidencia:

| Hallazgo | Evidencia exigida | Límite |
| --- | --- | --- |
| SMB1 habilitado | Respuesta SMB_COM_NEGOTIATE exitosa, ID correlacionado y selección de NT LM 0.12 | No implica una CVE ni acceso a archivos. |
| Basic ofrecido sin TLS | Cabeceras HTTP completas, estado 401 y desafío `WWW-Authenticate: Basic` | No se envían credenciales ni se prueba su aceptación. |
| Telnet sin TLS accesible | Secuencia IAC de negociación Telnet recibida en TCP | No se verifica el login ni el contenido posterior. |

No se ejecutan exploits, no se descargan archivos de los equipos ni se siguen redirecciones HTTP. Un banner de versión no basta para declarar una CVE. Quedan pendientes IPv6, MAC/OUI, firmware, inventario autenticado y pruebas específicas de vulnerabilidades. La app no certifica que una red esté libre de fallas.

## Pruebas reproducibles

Las pruebas JVM cubren límites de direcciones, identificación, canales y parsers, incluidos paquetes truncados, bucles de compresión DNS y respuestas que no deben generar hallazgos. Las cinco comprobaciones Android opcionales prueban los sockets reales dentro del propio dispositivo:

```powershell
.\verify.ps1
.\install-pilot.ps1 -VisualChecks -LanChecks
```

Estas comprobaciones no barren la red. Necesitan Wi-Fi para que el escáner seleccione la interfaz; HTTP y SMB usan servidores efímeros en `127.0.0.1`. No modifican el PIN ni los controles del usuario. Resultados de cada ejecución en [VALIDATION.md](VALIDATION.md).

Para verificar el comportamiento con equipos físicos de una red propia o autorizada:

1. Comparar los canales y el ancho con el panel del punto de acceso; revisar la antigüedad de resultados y repetir el escaneo.
2. Ejecutar el reconocimiento y comparar las direcciones con el inventario del router. Equipos dormidos o filtrados pueden faltar. Verificar las evidencias de identificación con el modelo real.
3. Comparar los Echo del gateway con una herramienta de ping en la misma red. Con solo cuatro paquetes, la pérdida es una muestra breve.
4. Probar IP conocidas de una subred permitida y una aislada. Confirmar los resultados contra las reglas y registros del firewall; una ausencia de respuesta sola no demuestra aislamiento.
5. En un laboratorio con servicios de prueba, habilitar y deshabilitar SMB1 o Basic por HTTP y comprobar que el hallazgo desaparezca al corregirlos. No habilitar protocolos inseguros en equipos de producción para esta prueba.
6. Detener durante el avance, navegar a otra sección, enviar la app a segundo plano y cambiar de Wi-Fi. Confirmar que la ejecución se detiene y no publica resultados posteriores como completos.

Referencias del protocolo y APIs: [mDNS, RFC 6762](https://www.rfc-editor.org/rfc/rfc6762.html), [negociación SMB de Microsoft](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-cifs/55ecb4c6-6fdb-459c-8688-b36ea1fc66e8), [sockets ligados a una red Android](https://developer.android.com/reference/android/net/Network), [API de sistema Android](https://developer.android.com/reference/android/system/Os), [ScanResult](https://developer.android.com/reference/android/net/wifi/ScanResult).
