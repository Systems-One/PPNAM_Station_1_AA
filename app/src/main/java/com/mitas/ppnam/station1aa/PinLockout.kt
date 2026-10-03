package com.mitas.ppnam.station1aa

/** Where the attempt counter and lockout deadline live — outside any one screen instance. */
interface PinLockoutStore {
    var failedAttempts: Int
    /** Wall-clock millis (System.currentTimeMillis) until which the gate is locked; 0 = not locked. */
    var lockedOutUntilMs: Long
}

/** Test double and the shape of the real store. */
class InMemoryPinLockoutStore : PinLockoutStore {
    override var failedAttempts: Int = 0
    override var lockedOutUntilMs: Long = 0L
}

/**
 * Supervisor PIN gate (ported from Station 2's SettingsViewModel; same PIN, 5 attempts, 30 s).
 * The counter and the deadline are read from and written to [store] on every call, so leaving
 * Settings, rotating, or restarting the process cannot reset them (audit station1-01).
 */
class PinLockout(
    private val correctPin: String,
    private val store: PinLockoutStore,
    private val now: () -> Long,
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val lockoutMs: Long = LOCKOUT_MS,
) {
    sealed class Outcome {
        /** Nothing typed — not an attempt (audit station1-15). */
        object Blank : Outcome()
        object Unlocked : Outcome()
        data class Rejected(val attemptsLeft: Int) : Outcome()
        data class LockedOut(val remainingMs: Long) : Outcome()
    }

    companion object {
        const val MAX_ATTEMPTS = 5
        const val LOCKOUT_MS = 30_000L
    }

    fun remainingLockoutMs(): Long = (store.lockedOutUntilMs - now()).coerceAtLeast(0L)

    val isLockedOut: Boolean
        get() = remainingLockoutMs() > 0L

    fun submit(pin: String): Outcome {
        val remaining = remainingLockoutMs()
        if (remaining > 0L) return Outcome.LockedOut(remaining)
        if (pin.isBlank()) return Outcome.Blank
        if (pin == correctPin) {
            store.failedAttempts = 0
            store.lockedOutUntilMs = 0L
            return Outcome.Unlocked
        }
        val failed = store.failedAttempts + 1
        if (failed >= maxAttempts) {
            store.failedAttempts = 0
            store.lockedOutUntilMs = now() + lockoutMs
            return Outcome.LockedOut(lockoutMs)
        }
        store.failedAttempts = failed
        return Outcome.Rejected(maxAttempts - failed)
    }
}
