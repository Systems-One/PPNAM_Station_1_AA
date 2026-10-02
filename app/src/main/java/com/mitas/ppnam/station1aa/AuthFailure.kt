package com.mitas.ppnam.station1aa

/**
 * Why a login produced no session, classified so the UI can speak to the operator instead of
 * echoing protocol text ("SCRAM proof rejected.", audit station1-07). The station's `reason`
 * and `errorCode` are kept in the exception message for logcat only — never shown.
 */
class AuthFailure(val kind: Kind, detail: String) : Exception(detail) {

    enum class Kind {
        /** Broker link down, or the publish itself failed. */
        NOT_CONNECTED,
        /** No correlated response within AuthClient's 10 s window. */
        TIMEOUT,
        /** Either SCRAM half rejected: the username/password pair did not verify. */
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
        /** `accepted: false` on the step's own response topic. */
        fun rejected(step: Step, errorCode: String, reason: String): AuthFailure {
            val detail = "$step rejected: ${errorCode.ifBlank { "-" }} $reason"
            return when (step) {
                // Which SCRAM half failed is a protocol detail the operator cannot act on.
                Step.SCRAM_START, Step.SCRAM_PROOF -> AuthFailure(Kind.INVALID_CREDENTIALS, detail)
                Step.BADGE_LOGIN -> AuthFailure(Kind.BADGE_REJECTED, detail)
                Step.OTHER -> AuthFailure(Kind.REJECTED, detail)
            }
        }

        /**
         * res/request_rejected: the envelope itself was refused (schema, replay, routing) — not a
         * credentials problem, so it must not be reported as one.
         */
        fun envelopeRejected(step: Step, errorCode: String, reason: String): AuthFailure =
            AuthFailure(Kind.REJECTED, "$step envelope rejected: ${errorCode.ifBlank { "-" }} $reason")
    }
}
