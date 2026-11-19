# Mapa del hogar

Versión demo **0.2.0-demo** (código Android 3). Instalar el mismo APK actualizado en ambos Android mediante actualización, sin borrar datos. En Familia, abrir **Mapa en vivo del hogar** o **Seguir en el mapa en vivo** bajo un teléfono. No se necesita Tailscale, clave de mapas ni cuenta de facturación de Google.

## Probar con dos teléfonos

1. Mantener vinculado el hogar y habilitar **Compartir mi ubicación** en el teléfono observado. Debe seguir visible la notificación que permite detener el envío.
2. Abrir el mapa desde la cuenta administradora. Comprobar que aparecen los teléfonos del hogar, con un punto solo para los que comparten una posición reciente. El integrante únicamente recibe su propio dispositivo según los permisos del servidor.
3. Seleccionar el otro teléfono en la fila inferior. Comprobar calle, nombre, hora GPS y precisión. Si no tiene posición, se muestra el motivo y no se dibuja un punto de ejemplo.
4. Desplazarse con el teléfono que comparte. Comprobar que cambia la hora GPS y que el marcador se mueve. Envío y consulta se intentan cada cinco segundos, con demora adicional según sensores y red. No se inventan posiciones entre lecturas ni se guardan recorridos.
5. Mover el mapa con el dedo: cambia a **Centrar y seguir**. Tocarlo: vuelve a centrar el teléfono elegido. Cambiar de teléfono debe cambiar el punto seleccionado.
6. Tocar el botón flotante GPS en el borde inferior derecho. Si el teléfono aún no comparte, confirmar el inicio y conceder ubicación y notificaciones. El servicio visible comparte la posición con el hogar y el mapa selecciona/centra este teléfono cuando llega una posición reciente. Si ya comparte, el botón vuelve a centrarlo. Al salir, el servicio continúa según el consentimiento y se detiene desde la notificación o Familia.
7. Bloquear la pantalla del teléfono que comparte y repetir tras unos minutos. Si MIUI interrumpe el servicio, revisar sus ajustes de batería y volver a compartir desde la app.
8. Detener desde la notificación y comprobar que desaparece el punto después de la próxima consulta. Sin conexión en el emisor, vence en hasta 90 segundos desde el último envío. El mapa aplica localmente la caducidad informada por el servidor incluso si no obtiene otra respuesta.
9. Quitar el teléfono o eliminar el hogar: después de la siguiente consulta ya no debe aparecer la posición. Cerrar sesión impide nuevas lecturas; no se reutilizan resultados de otra cuenta.
10. Salir y volver al mapa, recrear la actividad y cambiar orientación. Se deben conservar la selección y los controles y renovar los datos. El mapa no inicia GPS hasta que se toque el botón GPS.
11. Sin Internet, debe indicarse el error y retirarse la posición anterior. Si falla el proveedor de calles, tocar el aviso para reintentar. No se promete funcionamiento sin conexión.

## Comprobaciones automáticas

`HomeMapStateTest` comprueba caducidad exacta, demora de red, consentimiento y valores inválidos sin depender del reloj de pared. La instrumentación opcional `-e map true` verifica el parser con datos sintéticos, la carga real del estilo, fuente GeoJSON local, atribución, seguimiento y recreación. Descarga mapas públicos y consulta el hogar de la sesión existente; no crea hogares, no habilita GPS y no modifica permisos. Ejecutar solo con ambos APK compilados e instalados:

```powershell
adb shell am instrument -w -r -e visual true -e home true -e map true com.bsecurity.app.test/com.bsecurity.app.PilotInstrumentation
```

## Implementación y proveedores

MapLibre Native Android OpenGL **13.7.1**, fijado en Gradle, dibuja mapas vectoriales con el estilo Liberty de OpenFreeMap. OpenGL mantiene compatibilidad con teléfonos sin Vulkan. Las posiciones se solicitan mediante la RPC autenticada y ya protegida `bsecurity_home`; no se exponen tablas privadas, claves de servicio ni canales públicos de ubicación. No hay cambios de esquema en esta entrega.

La pantalla consulta cada cinco segundos mientras está visible. No solapa solicitudes, descarta respuestas de una actividad pausada y deja de consultar al salir. Después de un fallo se retiran los marcadores. Los puntos, nombres y tokens no se adjuntan a las solicitudes de mapas: la capa GeoJSON es local. El botón GPS inicia el servicio de ubicación compartida tras confirmación explícita, o centra el teléfono si ya comparte; el servicio muestra una notificación para detenerlo. OpenFreeMap recibe IP y teselas de la zona visible, de las que podría inferirse la zona aproximada del hogar. La atribución permanece visible. El uso de la instancia pública es gratuito y no tiene garantía de disponibilidad; el costo/cuota de Supabase depende del plan del proyecto.

Fuentes: [MapLibre: integración Android](https://maplibre.org/maplibre-native/android/examples/getting-started/), [OpenGL y Vulkan](https://maplibre.org/maplibre-native/android/examples/artifacts/), [OpenFreeMap: precios, datos y atribución](https://openfreemap.org/), [estilos para aplicaciones móviles](https://openfreemap.org/quick_start/).
