package com.mitas.ppnam.station1aa

/**
 * Which choices the dialog after an accepted offload offers (1.5.1).
 *
 * One dialog follows every recorded pallet. Too Little Tags and Too Many Tags are always on it; the
 * middle slot is Next Pallet while pallets are still expected and becomes Complete once the
 * count is reached. Unknown progress (the station sent no usable counts) offers both.
 *
 * The dialog has no Cancel. The one exit it still needs is after a refused close with no Next
 * Pallet on offer: Station 1 answers most Too Little / Too Many Tags reports with "recorded, the receipt
 * remains open", and without a way back to scanning the operator would be held in the dialog.
 */
data class OffloadCloseChoices(
    val nextPallet: Boolean,
    val complete: Boolean,
    val backToScan: Boolean,
)

object OffloadClosePolicy {
    /** [scanned]/[expected] are the station's counts, or negative when it sent none. */
    fun choices(scanned: Int, expected: Int, closeRefused: Boolean): OffloadCloseChoices {
        val known = hasProgress(scanned, expected)
        val reached = known && scanned >= expected
        return OffloadCloseChoices(
            nextPallet = !reached,
            complete = reached || !known,
            backToScan = closeRefused && reached,
        )
    }

    fun hasProgress(scanned: Int, expected: Int): Boolean = scanned >= 0 && expected > 0
}
