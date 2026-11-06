package com.mitas.ppnam.station1aa

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Skill-run critique 2026-11-06 (P2-1): "Please fill in all fields" stayed on the login card after
 * the operator had filled both fields, and was still there after a wrong-password retry and after
 * the station came back online. A login error describes the username/password it was raised for;
 * once the operator edits either one it no longer applies and must go.
 */
class LoginErrorPolicyTest {

    @Test
    fun `no error shown is never stale`() {
        assertFalse(LoginErrorPolicy.isStale(null, "op.both", "secret"))
    }

    @Test
    fun `unchanged inputs keep the error`() {
        assertFalse(LoginErrorPolicy.isStale("" to "", "", ""))
        assertFalse(LoginErrorPolicy.isStale("op.both" to "wrong", "op.both", "wrong"))
    }

    @Test
    fun `surrounding spaces in the username are not an edit`() {
        // submitCredentials() trims the username, so " op.both " is the same login.
        assertFalse(LoginErrorPolicy.isStale("op.both" to "wrong", " op.both ", "wrong"))
    }

    @Test
    fun `filling a field after an empty-field error makes it stale`() {
        assertTrue(LoginErrorPolicy.isStale("" to "", "op.both", ""))
        assertTrue(LoginErrorPolicy.isStale("op.both" to "", "op.both", "b"))
    }

    @Test
    fun `retyping the password after a rejection makes it stale`() {
        assertTrue(LoginErrorPolicy.isStale("op.both" to "wrong", "op.both", "wrong1"))
    }

    @Test
    fun `picking another operator makes it stale`() {
        assertTrue(LoginErrorPolicy.isStale("op.both" to "wrong", "op.tag", "wrong"))
    }

    @Test
    fun `password whitespace is significant`() {
        assertTrue(LoginErrorPolicy.isStale("op.both" to "pw", "op.both", "pw "))
    }
}
