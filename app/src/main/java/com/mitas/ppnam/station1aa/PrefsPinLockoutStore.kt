package com.mitas.ppnam.station1aa

import android.content.Context

/** SharedPreferences-backed lockout state: survives Back, rotation and process restart. */
class PrefsPinLockoutStore(context: Context) : PinLockoutStore {

    private val prefs = context.applicationContext
        .getSharedPreferences("supervisor_pin", Context.MODE_PRIVATE)

    override var failedAttempts: Int
        get() = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)
        set(value) = prefs.edit().putInt(KEY_FAILED_ATTEMPTS, value).apply()

    override var lockedOutUntilMs: Long
        get() = prefs.getLong(KEY_LOCKED_OUT_UNTIL_MS, 0L)
        set(value) = prefs.edit().putLong(KEY_LOCKED_OUT_UNTIL_MS, value).apply()

    private companion object {
        const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        const val KEY_LOCKED_OUT_UNTIL_MS = "locked_out_until_ms"
    }
}
