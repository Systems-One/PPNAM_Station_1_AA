package com.mitas.ppnam.station1aa

import android.os.SystemClock
import android.view.View

/**
 * Drops clicks that land within [windowMs] of an accepted one. A rapid double-tap on a
 * dashboard tile used to open the sub-screen twice (audit station1-06). Share one instance
 * across sibling controls so a tap on tile A followed by a tap on tile B also counts as one.
 */
class ClickDebouncer(
    private val windowMs: Long = 600L,
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private var lastAcceptedMs: Long? = null

    fun accept(): Boolean {
        val t = now()
        val last = lastAcceptedMs
        if (last != null && t - last < windowMs) return false
        lastAcceptedMs = t
        return true
    }
}

fun View.setDebouncedClickListener(debouncer: ClickDebouncer, action: () -> Unit) {
    setOnClickListener { if (debouncer.accept()) action() }
}
