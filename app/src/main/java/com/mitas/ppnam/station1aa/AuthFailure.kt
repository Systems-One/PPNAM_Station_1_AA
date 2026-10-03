package com.mitas.ppnam.station1aa

/**
 * Why a login produced no session, classified so the UI can speak to the operator instead of
 * echoing protocol text ("SCRAM proof rejected.", audit station1-07). The station's `reason`
 * and `errorCode` are kept in the exception message for logcat only — never shown.
 */
class AuthFailure(val kind: Kind, detail: String, val errorCode: String = "") : Exception(detail) {

    enum class Kind {
        /** Broker link down, or the publish itself failed. */
        NOT_CONNECTED,
        /** No correlated response within AuthClient's 10 s window. */
        TIMEOUT,
        /** A SCRAM half rejected with a credential-type code: the username/password pair did not verify. */
        INVALID_CREDENTIALS,
        /** `login_requested` rejected (`badge_rejected`). */
        BADGE_REJECTED,
        /** Any other station rejection, including a refused envelope on res/request_rejected. */
        REJECTED,
        /** The station answered something this client cannot use (missing fields, bad signature). */
        PROTOCOL,
    }

    /** Which round trip a rejection answered — decides what the rejection means. */
    enum class Step { SCRAM_START, SCRAM_PROOF, BADGE_LOGIN, OTHER }

    companion object {
        /** Contract v3 authentication codes that mean the username/password pair did not verify. */
        private val CREDENTIAL_ERROR_CODES = setOf(
            "scram_proof_invalid", "scram_client_final_invalid", "authentication_failed",
        )

        /** `accepted: false` on the step's own response topic. */
        fun rejected(step: Step, errorCode: String, reason: String): AuthFailure {
            val detail = "$step rejected: ${errorCode.ifBlank { "-" }} $reason"
            return when (step) {
                // Only a credential-type code means "wrong username/password". Anything else on a
                // SCRAM step (unknown operator state, rate limit, station error) is a refusal the
                // operator cannot fix by retyping. Which SCRAM half failed is a protocol detail.
                Step.SCRAM_START, Step.SCRAM_PROOF ->
                    if (errorCode in CREDENTIAL_ERROR_CODES) AuthFailure(Kind.INVALID_CREDENTIALS, detail, errorCode)
                    else AuthFailure(Kind.REJECTED, detail, errorCode)
                Step.BADGE_LOGIN -> AuthFailure(Kind.BADGE_REJECTED, detail, errorCode)
                Step.OTHER -> AuthFailure(Kind.REJECTED, detail, errorCode)
            }
        }

        /**
         * res/request_rejected: the envelope itself was refused (schema, replay, routing) — not a
         * credentials problem, so it must not be reported as one.
         */
        fun envelopeRejected(step: Step, errorCode: String, reason: String): AuthFailure =
            AuthFailure(Kind.REJECTED, "$step envelope rejected: ${errorCode.ifBlank { "-" }} $reason", errorCode)
    }
}
