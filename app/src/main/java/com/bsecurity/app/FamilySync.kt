package com.bsecurity.app

import org.json.JSONObject

/** Shares only current consent and applies rules after any queued revocation succeeds. */
class FamilySync(private val family: FamilyClient, private val policy: PolicyStore) {
    fun sync(report: () -> JSONObject): JSONObject {
        var state = family.request("/v1/family")
        if (state.optJSONObject("family") != null) {
            family.flushControlsRevocation()
            family.request("/v1/report", report())
            state = family.request("/v1/family")
        }
        if (state.optJSONObject("family") == null) {
            family.clearMembership()
        } else {
            val rules = if (family.acceptControls) state.optJSONObject("policy")?.optJSONObject("rules") else null
            policy.setRemoteRules(rules ?: JSONObject())
        }
        return state
    }
}
