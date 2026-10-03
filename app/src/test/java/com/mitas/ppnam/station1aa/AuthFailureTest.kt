package com.mitas.ppnam.station1aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Login failures are classified by the step they came from — never by parsing `reason`. */
class AuthFailureTest {

    @Test
    fun `a rejected SCRAM proof is bad credentials`() {
        val f = AuthFailure.rejected(AuthFailure.Step.SCRAM_PROOF, "scram_proof_invalid", "SCRAM proof rejected.")
        assertEquals(AuthFailure.Kind.INVALID_CREDENTIALS, f.kind)
    }

    @Test
    fun `a rejected SCRAM start with a credential code is bad credentials too`() {
        val f = AuthFailure.rejected(AuthFailure.Step.SCRAM_START, "authentication_failed", "Authentication failed.")
        assertEquals(AuthFailure.Kind.INVALID_CREDENTIALS, f.kind)
    }

    @Test
    fun `credential codes on the proof step are bad credentials`() {
        for (code in listOf("scram_proof_invalid", "scram_client_final_invalid", "authentication_failed")) {
            val f = AuthFailure.rejected(AuthFailure.Step.SCRAM_PROOF, code, "x")
            assertEquals(code, AuthFailure.Kind.INVALID_CREDENTIALS, f.kind)
        }
    }

    @Test
    fun `non-credential SCRAM rejections are refusals carrying the code`() {
        for (code in listOf("scram_challenge_expired", "operator_session_invalid", "authentication_unavailable", "")) {
            val f = AuthFailure.rejected(AuthFailure.Step.SCRAM_PROOF, code, "x")
            assertEquals(code, AuthFailure.Kind.REJECTED, f.kind)
            assertEquals(code, f.errorCode)
        }
        val start = AuthFailure.rejected(AuthFailure.Step.SCRAM_START, "scram_start_invalid", "x")
        assertEquals(AuthFailure.Kind.REJECTED, start.kind)
    }

    @Test
    fun `a rejected badge login is a badge problem`() {
        val f = AuthFailure.rejected(AuthFailure.Step.BADGE_LOGIN, "badge_rejected", "Unknown badge.")
        assertEquals(AuthFailure.Kind.BADGE_REJECTED, f.kind)
    }

    @Test
    fun `an envelope rejection is never reported as bad credentials`() {
        val f = AuthFailure.envelopeRejected(AuthFailure.Step.SCRAM_PROOF, "invalid_envelope", "Bad schema.")
        assertEquals(AuthFailure.Kind.REJECTED, f.kind)
        assertNotEquals(AuthFailure.Kind.INVALID_CREDENTIALS, f.kind)
    }

    @Test
    fun `the station's reason is kept for logs, not shown`() {
        val f = AuthFailure.rejected(AuthFailure.Step.SCRAM_PROOF, "scram_proof_invalid", "SCRAM proof rejected.")
        assertTrue(f.message!!.contains("SCRAM proof rejected."))
        assertTrue(f.message!!.contains("scram_proof_invalid"))
    }
}
