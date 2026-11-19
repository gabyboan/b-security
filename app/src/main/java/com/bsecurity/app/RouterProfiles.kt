package com.bsecurity.app

/** Public model references only. A selection is never evidence of the connected unit's identity. */
enum class RouterProfile(
    val label: String,
    val productUrl: String?,
    val certificationUrl: String?,
) {
    GENERIC("Sin modelo confirmado", null, null),
    FIBERHOME_HG6144F(
        "FiberHome HG6144F",
        "https://en.fiberhome.com/ONTsSeries/20240103/45831.html",
        "https://api.cert.wi-fi.org/api/certificate/download/public?variantId=113340",
    ),
}

object RouterProfiles {
    fun findings(profile: RouterProfile): List<Finding> = when (profile) {
        RouterProfile.GENERIC -> listOf(
            Finding("Identificación del router", "La dirección y los puertos no confirman marca, modelo ni firmware. Confirmalos en la etiqueta o en el panel de administración.", "unknown"),
        )
        RouterProfile.FIBERHOME_HG6144F -> listOf(
            Finding("Perfil de referencia", "FiberHome HG6144F seleccionado localmente. La prueba TCP no confirma el modelo, la revisión de hardware ni el firmware de la unidad conectada.", "unknown"),
            Finding("Wi-Fi del HG6144F", "El fabricante documenta Wi-Fi 6. La variante certificada WFA119165 incluye WPA3-Personal; esto no confirma que esté activo en tu red. Revisá el modo de protección que informa Android y si tu firmware ofrece WPA3 para tus dispositivos."),
            Finding("Firmware del HG6144F", "Confirmá con tu proveedor la revisión de hardware y la actualización compatible. El fabricante contempla aprovisionamiento y actualizaciones remotas mediante OMCI/TR-069. Esta prueba no verifica su estado ni una versión de firmware."),
            Finding("Revisión del panel", "Revisá la contraseña de administración, la disponibilidad de HTTPS y el estado de WPS en tu configuración. No se comprobaron credenciales ni se cambiaron ajustes."),
        )
    }
}
