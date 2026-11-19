package com.bsecurity.app

import android.net.wifi.WifiInfo

// Android's constants are inlined; this mapping itself has no API-level requirement.
object WifiSecurity {
    fun finding(type: Int): Finding {
        val label = when (type) {
            WifiInfo.SECURITY_TYPE_OPEN -> "Abierta"
            WifiInfo.SECURITY_TYPE_WEP -> "WEP"
            WifiInfo.SECURITY_TYPE_PSK -> "WPA/WPA2 Personal"
            WifiInfo.SECURITY_TYPE_EAP -> "WPA/WPA2 Enterprise"
            WifiInfo.SECURITY_TYPE_SAE -> "WPA3 Personal"
            WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT -> "WPA3 Enterprise 192-bit"
            WifiInfo.SECURITY_TYPE_OWE -> "OWE (cifrado sin autenticación)"
            WifiInfo.SECURITY_TYPE_WAPI_PSK -> "WAPI PSK"
            WifiInfo.SECURITY_TYPE_WAPI_CERT -> "WAPI certificado"
            WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE -> "WPA3 Enterprise"
            WifiInfo.SECURITY_TYPE_OSEN -> "OSEN"
            WifiInfo.SECURITY_TYPE_PASSPOINT_R1_R2 -> "Passpoint R1/R2"
            WifiInfo.SECURITY_TYPE_PASSPOINT_R3 -> "Passpoint R3"
            WifiInfo.SECURITY_TYPE_DPP -> "DPP"
            else -> return Finding("Protección del enlace", "Tipo no expuesto ($type)", "unknown")
        }
        val level = when (type) {
            WifiInfo.SECURITY_TYPE_OPEN, WifiInfo.SECURITY_TYPE_WEP -> "risk"
            WifiInfo.SECURITY_TYPE_OSEN -> "info"
            else -> "ok"
        }
        return Finding("Protección del enlace", label, level)
    }
}
