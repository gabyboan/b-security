# Protección de B-Security

La pantalla **Control → Protección de la app y segundo plano** distingue el modo común del modo administrado consultando `DevicePolicyManager.isDeviceOwnerApp`. No convierte el equipo ni borra datos. La ubicación del hogar mantiene su propio consentimiento, servicio y notificación.

## Instalación común

1. Crear el PIN del responsable (o usar el existente) y habilitar Accesibilidad, acceso al uso y reglas locales desde Control.
2. Abrir Protección y habilitar las notificaciones. Android 13+ pide `POST_NOTIFICATIONS`; si ya se rechazó, revisar la ficha de la app en Ajustes.
3. Activar continuidad con PIN. Debe aparecer el aviso silencioso **B-Security · protección parental**. Tocarlo abre Protección. El aviso informa si las reglas están pausadas o faltan Accesibilidad o acceso al uso; no afirma que las reglas estén funcionando cuando faltan esos permisos.
4. Revisar optimización de batería. En Xiaomi/Redmi, configurar Inicio automático, Batería → Sin restricciones y el candado para excluir la app de Limpiar todo. Los nombres dependen de MIUI/HyperOS; esas opciones del fabricante no se pueden verificar mediante una API pública, por lo que la app las presenta como revisión manual.
5. Volver a Inicio, quitar la actividad de Recientes y comprobar que continúa el servicio y su aviso. Su persistencia reduce cierres por memoria; no garantiza ejecución permanente. Detener continuidad desde Protección requiere verificar de nuevo el PIN y cancela también su recuperación automática.
6. Con continuidad solicitada, reiniciar el equipo y desbloquearlo. El receptor `BOOT_COMPLETED` intenta recuperarla; una actualización hace lo mismo mediante `MY_PACKAGE_REPLACED`. El servicio usa `START_STICKY` para la recuperación del proceso permitida por Android, sin alarmas, bucles de resurrección ni bloqueo de Ajustes.
7. Forzar detención desde Android en modo común sigue siendo posible. Android puede impedir el arranque posterior hasta que se abra de nuevo la app. Protección muestra «solicitada, pero no está en ejecución» y ofrece Reanudar. No afirma poder detectar inequívocamente una desinstalación o distinguirla de un teléfono apagado/sin conexión.

La continuidad usa `specialUse` en Android 14+, con subtipo declarado: continuidad del control parental local y aviso visible sobre sus permisos. No captura pantalla, ubicación ni tráfico; no transmite reportes. Una distribución en Google Play requiere declarar y justificar ese uso de FGS para su revisión. Las notificaciones no se ocultan deliberadamente.

## Dispositivo administrado

Usar un equipo de pruebas dedicado. El registro Device Owner es una operación separada de la instalación normal. El equipo personal con cuentas, datos y ubicación activa no debe usarse para este procedimiento.

Para desarrollo, Android permite registrar un DPC por ADB si el equipo reúne sus requisitos, entre ellos no tener cuentas. En un emulador nuevo o equipo dedicado compatible, con el APK ya instalado:

```powershell
adb -s <serial-del-equipo-dedicado> shell dpm set-device-owner com.bsecurity.app/.ProtectionAdminReceiver
```

No ejecutar en un teléfono personal ni eliminar sus cuentas para intentar forzar la elegibilidad. Si Android rechaza el registro, usar un entorno de pruebas nuevo. Para despliegue, preparar el equipo mediante el flujo oficial de aprovisionamiento, normalmente tras restablecerlo. Están declarados el receptor DPC y los puntos de entrada de Android 12+ `GET_PROVISIONING_MODE` y `ADMIN_POLICY_COMPLIANCE`, limitados al modo completamente administrado y protegidos por `BIND_DEVICE_ADMIN`. El asistente QR completo depende del alojamiento HTTPS del APK, su checksum y del fabricante; todavía debe validarse antes de un despliegue real.

Después del registro:

1. Abrir Protección: debe informar **Modo administrado · Device Owner**. El registro por sí solo no solicita las políticas adicionales ni activa GPS, reglas o continuidad. Android puede impedir desinstalar al propietario incluso antes de aplicar políticas adicionales.
2. Crear el PIN y elegir **Activar protección administrada con PIN**. La confirmación explica el alcance; a continuación se exige el PIN. Si el canal parental ya estaba bloqueado, la pantalla pide habilitarlo antes de fijar permisos. Se conserva en almacenamiento privado el estado anterior antes de cambiar Android.
3. Comprobar en la pantalla y en Ajustes: desinstalación bloqueada; detención forzada y borrado de datos restringidos desde API 30; permiso de notificaciones fijado desde API 33. El canal de protección se declara no bloqueable cuando Android tiene el permiso fijado. No se promete que todos los fabricantes muestren una notificación imposible de descartar; se muestran los permisos realmente vigentes.
4. El cambio solo afecta al paquete de B-Security. Se conserva cualquier otro paquete que ya estuviera en la lista `getUserControlDisabledPackages`. No se restringen restablecimiento, modo seguro, depuración, telefonía, launcher ni Ajustes.
5. Simular un fallo de una política en las pruebas JVM: el estado debe conservar la configuración anterior y mostrar que el cambio está pendiente/parcial. No se anuncia éxito sin comprobar las políticas de vuelta. El responsable puede reintentar aplicar o retirar las políticas.
6. **Retirar políticas adicionales con PIN** restaura el estado anterior, incluido el valor previo de la política de notificaciones. No equivale a revocar el permiso que el usuario hubiera concedido: devuelve su control según el estado anterior. Si una restauración falla, se intentan las demás y se conserva la información para reintentar.

## Salida de la administración

Retirar las políticas no elimina la propiedad del dispositivo, por lo que Android puede continuar impidiendo desinstalar B-Security. La pantalla lo advierte antes de registrar cambios. No se usa `clearDeviceOwnerApp`, una API de pruebas obsoleta que no garantiza retirar todas las políticas, ni se incorpora un botón que borre el equipo. Para salir completamente de una instalación administrada, respaldar los datos y seguir el restablecimiento de Android. En pruebas, eliminar únicamente el emulador desechable después de terminar.

## Pruebas automáticas

Las pruebas JVM cubren alta sin propietario o sin PIN, fallo del guardado previo, recuperación después de interrupciones, políticas no disponibles en versiones antiguas, fallos parciales, restauración y conservación del estado anterior.

El runner normal agrega comprobaciones de preferencias aisladas, componentes privados/protegidos y mensajes del aviso sin modificar las políticas del dispositivo real:

```powershell
adb -s <serial> shell am instrument -w -r com.bsecurity.app.test/com.bsecurity.app.PilotInstrumentation
```

Las siguientes variantes modifican configuración parental temporal y están limitadas en código a **un emulador desechable sin PIN ni continuidad previos**:

```powershell
# Emulador sin propietario, permisos/notificación y arranque/detención reales del servicio.
adb -s <emulador> shell am instrument -w -r -e protection true com.bsecurity.app.test/com.bsecurity.app.PilotInstrumentation
# Tras registrar Device Owner en ese mismo emulador: aplicación y restauración de políticas reales.
adb -s <emulador> shell am instrument -w -r -e managedProtection true com.bsecurity.app.test/com.bsecurity.app.PilotInstrumentation
```

Las comprobaciones opcionales restauran sus políticas y eliminan sus preferencias parentales al terminar. No ejecutarlas en el emulador de uso habitual. La creación, persistencia tras reinicio, efectos en Ajustes y características de MIUI deben registrarse aparte en VALIDATION.md, distinguiendo lo ejecutado de lo pendiente.

El caso de continuidad verifica también que quitar la tarea de Recientes conserve el servicio y su notificación. El caso administrado conserva una restricción previa sobre otro paquete durante la aplicación y retirada. Para repetir únicamente esos dos casos después de ejecutar la suite normal, agregar `-e protectionOnly true` junto con `protection` o `managedProtection`; se mantienen las mismas comprobaciones de emulador sin configuración previa.

## Referencias oficiales

- [DevicePolicyManager: políticas, propietario y aprovisionamiento](https://developer.android.com/reference/android/app/admin/DevicePolicyManager).
- [Preparar dispositivos administrados](https://developer.android.com/work/dpc/dedicated-devices).
- [Entorno de desarrollo DPC por ADB](https://developer.android.com/work/dpc/dedicated-devices/cookbook#dev-setup).
- [Tipos de servicio en primer plano, specialUse](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use).
- [Restricciones y excepciones para arrancar FGS](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).
- [Permiso de notificaciones](https://developer.android.com/develop/ui/compose/notifications/notification-permission) y [canales bloqueables con permiso fijado](https://developer.android.com/reference/android/app/NotificationChannel#setBlockable(boolean)).
