package com.mitas.ppnam.station1aa

/**
 * When a login-card error has gone stale (skill-run critique 2026-11-06, P2-1).
 *
 * The error ("Please fill in all fields.", "Incorrect username or password.", a signed-out
 * reason) describes the username/password on screen when it was raised. Once the operator edits
 * either field it no longer describes what is there: "fill in all fields" sat over two filled
 * fields. The username is compared trimmed, as submitCredentials() sends it; the password as is.
 */
object LoginErrorPolicy {
    /** [shownFor] is the (username, password) the visible error was raised for, or null. */
    fun isStale(shownFor: Pair<String, String>?, username: String, password: String): Boolean =
        shownFor != null && (shownFor.first.trim() != username.trim() || shownFor.second != password)
}
