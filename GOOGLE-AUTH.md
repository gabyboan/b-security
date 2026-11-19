# Ingreso con Google

La app usa el selector nativo de Google mediante Credential Manager. Supabase valida el ID token y el nonce antes de crear una sesión. En Familia se puede crear un hogar en Supabase o unirse con código; el creador es administrador por una autorización del servidor. El grupo anterior del servidor privado conserva su identidad y sus reglas. Pasos de vinculación y ubicación: [HOME-TEST.md](HOME-TEST.md).

## Configurar Google Cloud y Supabase

Crear ambos clientes OAuth dentro del mismo proyecto de Google Cloud:

1. **Aplicación web**, para Supabase. En las URI de redireccionamiento autorizadas, agregar la Callback URL que muestra Authentication → Sign In / Providers → Google. Para B-Security es `https://rxlkbsfjorutayyuqpys.supabase.co/auth/v1/callback`. Los orígenes de JavaScript pueden quedar vacíos para este ingreso nativo.
2. **Android**, para identificar el APK. Paquete `com.bsecurity.app` y SHA-1 del certificado que firma ese APK. Para el APK de desarrollo de esta PC: `28:C6:EC:70:4B:7C:FB:27:CD:F7:1F:69:46:BB:6C:63:A1:79:CF:7D`. Dejar desactivados el esquema de URI personalizado y la opción de agente de IA. La verificación de propiedad indicada como opcional no es necesaria para esta prueba local.

En Supabase, activar Google y guardar el ID y secreto del cliente **web**. Mantener **Skip nonce checks** y **Allow users without an email** desactivados. El secreto se introduce únicamente en Supabase. Si Google Auth Platform está en estado de prueba, agregar las cuentas que se usarán como usuarios de prueba en Público. Los cambios pueden tardar en propagarse.

Cada certificado de firma distinto requiere su propio registro Android con el mismo paquete y su SHA-1 correspondiente. Al publicar en Google Play, usar la firma de distribución de Play. No distribuir la clave de desarrollo ni el JSON con el secreto del cliente web.

## Configuración pública del APK

Guardar en `google.properties`, excluido de Git:

```properties
supabase.url=https://tu-proyecto.supabase.co
supabase.publishableKey=sb_publishable_TU_CLAVE_PUBLICA
google.webClientId=123456789-TU_CLIENTE_WEB.apps.googleusercontent.com
```

El ID es el del cliente **web**, aunque el APK sea Android. La compilación solo acepta claves `sb_publishable_`; no usar `sb_secret_`, `service_role` ni el secreto de Google. Si no se incluye configuración, el botón de Google queda deshabilitado y se puede completar desde «Configurar acceso». Las funciones locales siguen disponibles.

La configuración local de esta PC ya contiene los valores públicos del proyecto B-Security. El JSON descargado de Google Cloud no necesita incorporarse al repositorio ni al APK.

## Probar en el teléfono

1. Compilar e instalar mediante actualización para conservar datos:

   ```powershell
   .\verify.ps1
   .\install-pilot.ps1 -RunChecks
   ```

2. En la bienvenida o en **Familia**, tocar **Continuar con Google** y elegir la cuenta en el selector del sistema. El dispositivo necesita Google Play Services y conexión.
3. Confirmar que la app muestra el correo y «Sesión verificada con Supabase». En Supabase → Authentication → Users debe aparecer el mismo usuario con Google como proveedor.
4. Cerrar y abrir la app: verifica la sesión guardada contra Supabase y renueva el token cuando corresponde. Una falla transitoria conserva los datos y muestra el error; no afirma haber verificado la identidad sin conexión.
5. **Cerrar sesión en este teléfono** revoca esta sesión en Supabase y borra sus tokens locales. Las sesiones de otros teléfonos se conservan. Si no hay conexión, **Quitar sesión sin conexión** elimina solamente la copia local y explica que no se revocó en el servidor.

Cancelar el selector o rechazar una credencial no debe mostrar una sesión exitosa. Si Google no reconoce la app, comprobar el proyecto, el paquete, la firma del APK instalado y los usuarios de prueba. Si Supabase rechaza el canje, comprobar que Google esté habilitado y que el ID del cliente web coincida con el del APK.

## Sesión y alcance

La app envía a Google un nonce SHA-256 y a Supabase el nonce original junto al ID token, mediante HTTPS sin redirecciones. La sesión se guarda cifrada con AES-GCM y una clave de Android Keystore; el cifrado queda asociado a la configuración del proyecto. Los refresh tokens rotados se guardan antes de reutilizar la sesión. No se escriben tokens ni respuestas de autenticación completas en logs.

El nombre y correo se usan para mostrar la cuenta. Los metadatos editables del usuario no conceden roles. El hogar guarda el propietario por `auth.uid()` y valida que `session_id` siga existiendo en Auth antes de cada operación. Cerrar sesión oculta las posiciones asociadas a esa sesión; compartir requiere habilitarlo expresamente en cada teléfono.

Las pruebas automáticas de autenticación usan HTTPS simulado y Keystore real, sin ingresar con cuentas personales. El 19 de noviembre de 2026, el usuario confirmó el ingreso real en el Redmi con el APK de desarrollo: selector de cuenta, correo visible y «Sesión verificada con Supabase». Repetir la prueba manual al cambiar el proyecto, el cliente OAuth o la firma de distribución.

Referencias: [Google en Supabase](https://supabase.com/docs/guides/auth/social-sign-in/auth-google), [Credential Manager y Sign in with Google](https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation).
