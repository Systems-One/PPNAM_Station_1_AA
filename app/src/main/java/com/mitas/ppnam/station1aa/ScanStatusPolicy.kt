package com.mitas.ppnam.station1aa

/**
 * When an Offload scan-step error has gone stale (visual critique 2026-11-06, P2-8).
 *
 * The error ("Pair mismatch.", "Tag unknown.", a timeout with Retry) describes the tag/barcode
 * pair that was sent. Once the operator rescans either value it no longer describes what is on
 * screen and must go. A repeated read of the same value — RFID readers fire several times per
 * trigger — is not a new pair, so it keeps the error.
 */
object ScanStatusPolicy {
    /** [shownFor] is the (tag, barcode) pair the visible error was raised for, or null. */
    fun isStale(shownFor: Pair<String, String>?, tag: String, barcode: String): Boolean =
        shownFor != null && (shownFor.first != tag.trim() || shownFor.second != barcode.trim())
}
