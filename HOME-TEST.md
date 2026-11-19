# Hogar conectado con Google

Funciona con Internet en dos Android, sin Tailscale y sin la PC encendida. Usar el mismo APK actualizado en ambos. El JSON descargado de Google Cloud no se instala ni se comparte. Si Google está en modo prueba, agregar previamente la cuenta del otro teléfono en Google Auth Platform → Público → Usuarios de prueba.

## Vincular y ver la posición

1. En tu teléfono: **Familia → Crear o recuperar mi hogar → Crear**. Tu cuenta pasa a ser administradora por ser la creadora del hogar.
2. Tocar **Invitar otro teléfono** y compartir el código. Sirve una sola vez, durante 15 minutos.
3. Instalar el APK en el otro Android. **Familia → Continuar con Google → Unirme con código**. Ingresar el código y aceptar. Usar su propia cuenta para que sea integrante; si usa tu cuenta administradora tendrá tus mismos permisos.
4. En ese Android: **Compartir mi ubicación** desde Familia, o tocar el botón GPS del mapa en vivo. Aceptar la explicación y conceder ubicación y notificaciones. Activar ubicación e Internet en el sistema. Queda una notificación **B-Security · compartiendo ubicación**, con **Detener**.
5. En tu teléfono: **Familia → Mapa en vivo del hogar**. Elegir un teléfono en la fila inferior. El punto se actualiza automáticamente y **Seguir teléfono** centra la vista cuando llega una posición nueva. Al desplazar el mapa manualmente se desactiva el seguimiento; **Centrar y seguir** lo retoma. La ficha indica hora GPS, precisión y si la ubicación es simulada. No se necesita permiso GPS en el teléfono que solo consulta el mapa. También está disponible **Abrir punto en OpenStreetMap** en el listado de Familia.
6. En el otro Android, salir de la app sin forzar su detención. Esperar al menos un minuto y actualizar desde tu teléfono. Comprobar que sigue avanzando la hora de la posición. Con pantalla bloqueada, repetir tras varios minutos.
7. Probar **Detener** desde la notificación. Tras actualizar, la posición debe desaparecer. Sin Internet, vence como máximo 90 segundos después del último envío exitoso. Quitar el teléfono o eliminar el hogar revoca el vínculo y borra la posición compartida.

Se envía aproximadamente cada cinco segundos, con posiciones de hasta dos minutos de antigüedad. El mapa consulta cada cinco segundos mientras está visible; GPS, Internet y Android pueden agregar demora. Se muestra la hora de la posición y se retiran los puntos vencidos incluso sin otra respuesta. Solo se guarda la última posición; no hay historial de rutas, geocercas, QR ni activación remota del GPS en esta entrega. El integrante ve su propio teléfono; la cuenta administradora ve los teléfonos de su hogar.

El mapa integrado usa MapLibre Native 13.7.1 con OpenFreeMap y datos de OpenStreetMap/OpenMapTiles. No requiere clave de mapas ni facturación de Google. El proveedor recibe la IP y las zonas del mapa solicitadas, sin recibir nombres, correos ni tokens del hogar; las coordenadas de los marcadores se dibujan localmente. La zona solicitada puede permitir inferir la ubicación aproximada. La instancia pública de OpenFreeMap no ofrece SLA. Instrucciones y comprobaciones en [MAP-TEST.md](MAP-TEST.md).

## Segundo plano y batería

El servicio de ubicación se inicia por una acción visible del usuario y continúa mediante un servicio de primer plano con notificación. No solicita leer SMS ni activar GPS de forma remota, no se inicia al encender el teléfono y no se reinicia solo tras una detención del proceso. Android y MIUI pueden interrumpirlo por batería o permisos. Si ocurre, abrir B-Security y volver a compartir; comprobar primero los ajustes de batería de la app. No promete seguimiento ininterrumpido.

Una nueva instalación crea una identidad de dispositivo. La cuenta creadora puede recuperar su hogar explícitamente con **Crear o recuperar mi hogar**. Los integrantes necesitan un nuevo código. Borrar datos/reinstalar puede dejar una entrada antigua: el administrador puede quitarla. Hay límites de 32 instalaciones por cuenta/hogar y cinco invitaciones activas por hogar. Los datos del hogar anterior no se migran automáticamente; sus opciones siguen en **Opciones del grupo anterior**.

## Servidor y comprobaciones

Proyecto B-Security: `rxlkbsfjorutayyuqpys`. Migración: `supabase/migrations/20261119044518_home_devices_location.sql`. RPC: `public.bsecurity_home(payload jsonb)`. Tablas privadas con RLS y sin acceso directo de los roles API. Función interna con `SECURITY DEFINER`, ruta de búsqueda vacía y validación de sesión, instalación y propietario; API pública con `SECURITY INVOKER`. El cliente usa únicamente clave publicable y sesión del usuario.

La prueba SQL `supabase/tests/home_security.sql` crea identidades sintéticas, ejecuta la RPC con el rol `authenticated` y revierte toda la transacción. No crea hogares del usuario ni envía ubicaciones reales:

```powershell
supabase db query --project-ref rxlkbsfjorutayyuqpys --linked --file supabase/tests/home_security.sql --output-format json
supabase db advisors --project-ref rxlkbsfjorutayyuqpys --linked --type all --level warn
```

La instrumentación incluye HTTPS simulado para validar tokens, identidad de instalación y errores de la RPC. La opción adicional `-e home true` en `am instrument` comprueba la sesión Google real y `status` contra Supabase; no crea hogares ni inicia ubicación. Solo usarla después del ingreso manual. Las pruebas entre dos teléfonos físicos y el comportamiento prolongado de MIUI requieren los pasos manuales de arriba.

Referencias: [servicio de ubicación de Android](https://developer.android.com/develop/background-work/services/fgs/service-types#location), [funciones en Supabase](https://supabase.com/docs/guides/database/functions), [OpenStreetMap](https://www.openstreetmap.org/).
