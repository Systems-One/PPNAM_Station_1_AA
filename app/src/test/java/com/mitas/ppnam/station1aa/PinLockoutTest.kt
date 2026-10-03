package com.mitas.ppnam.station1aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Supervisor PIN gate rules (audit station1-01/15/16): five wrong PINs lock the gate for 30 s,
 * the lockout and the attempt counter live in the store (so Back + reopen or a process restart
 * cannot reset them), a blank submit is not an attempt, and the correct PIN during a lockout is
 * refused without clearing the lockout.
 */
class PinLockoutTest {

    private var now = 1_000_000L
    private val store = InMemoryPinLockoutStore()
    private val gate = PinLockout(correctPin = "079545", store = store, now = { now })

    @Test
    fun `correct PIN unlocks and resets the counter`() {
        gate.submit("111111")
        assertEquals(PinLockout.Outcome.Unlocked, gate.submit("079545"))
        assertEquals(0, store.failedAttempts)
    }

    @Test
    fun `wrong PINs count down from four attempts left`() {
        assertEquals(PinLockout.Outcome.Rejected(4), gate.submit("000000"))
        assertEquals(PinLockout.Outcome.Rejected(3), gate.submit("000000"))
        assertEquals(PinLockout.Outcome.Rejected(2), gate.submit("000000"))
        assertEquals(PinLockout.Outcome.Rejected(1), gate.submit("000000"))
        assertEquals(4, store.failedAttempts)
    }

    @Test
    fun `blank submit is not an attempt`() {
        assertEquals(PinLockout.Outcome.Blank, gate.submit(""))
        assertEquals(PinLockout.Outcome.Blank, gate.submit("   "))
        assertEquals(0, store.failedAttempts)
    }

    @Test
    fun `fifth wrong PIN locks the gate for thirty seconds`() {
        repeat(4) { gate.submit("000000") }
        assertEquals(PinLockout.Outcome.LockedOut(30_000L), gate.submit("000000"))
        assertTrue(gate.isLockedOut)
        assertEquals(now + 30_000L, store.lockedOutUntilMs)
    }

    @Test
    fun `correct PIN during lockout is refused and does not clear the lockout`() {
        repeat(5) { gate.submit("000000") }
        now += 5_000
        assertEquals(PinLockout.Outcome.LockedOut(25_000L), gate.submit("079545"))
        assertEquals(now + 25_000L, store.lockedOutUntilMs)
    }

    @Test
    fun `lockout survives a new PinLockout over the same store`() {
        repeat(5) { gate.submit("000000") }
        now += 3_000
        val reopened = PinLockout(correctPin = "079545", store = store, now = { now })
        assertTrue(reopened.isLockedOut)
        assertEquals(27_000L, reopened.remainingLockoutMs())
        assertEquals(PinLockout.Outcome.LockedOut(27_000L), reopened.submit("079545"))
    }

    @Test
    fun `attempt counter survives a new PinLockout over the same store`() {
        repeat(4) { gate.submit("000000") }
        val reopened = PinLockout(correctPin = "079545", store = store, now = { now })
        assertEquals(PinLockout.Outcome.LockedOut(30_000L), reopened.submit("000000"))
    }

    @Test
    fun `after the lockout expires the gate accepts fresh attempts`() {
        repeat(5) { gate.submit("000000") }
        now += 30_000
        assertFalse(gate.isLockedOut)
        assertEquals(0L, gate.remainingLockoutMs())
        assertEquals(PinLockout.Outcome.Rejected(4), gate.submit("000000"))
        assertEquals(PinLockout.Outcome.Unlocked, gate.submit("079545"))
    }
}
