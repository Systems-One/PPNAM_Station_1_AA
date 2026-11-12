package com.mitas.ppnam.station1aa

/**
 * Which choices the dialog after an accepted offload offers (1.5.2).
 *
 * One dialog follows every recorded pallet, and it only offers what Station 1 can act on at
 * that pallet count:
 *
 *  - pallets still expected: Next Pallet, or Too Many Tags (prepared tags are left over
 *    because fewer pallets arrived);
 *  - count reached: Complete, or Too Little Tags (more pallets arrived than tags were
 *    prepared — the station only takes that report once every prepared pallet is confirmed).
 *
 * Unknown progress (the station sent no usable counts) offers all four.
 *
 * The dialog has no Cancel. The one exit it still needs is after a refused close with no Next
 * Pallet on offer: without a way back to scanning the operator would be held in the dialog.
 */
data class OffloadCloseChoices(
    val nextPallet: Boolean,
    val complete: Boolean,
    val tooLittleTags: Boolean,
    val tooManyTags: Boolean,
    val backToScan: Boolean,
)

object OffloadClosePolicy {
    /**
     * Station 1's answers to a Too Little / Too Many Tags report it has taken: the report is on
     * record for the desktop to resolve and the receipt stays open, so the answer is
     * `accepted: false` with one of these codes rather than an accepted close.
     */
    private val reportRecordedCodes = setOf("INCOMPLETE_PALLETS", "OVER_RECEIPT_PENDING_AUTHORISATION")

    /** [scanned]/[expected] are the station's counts, or negative when it sent none. */
    fun choices(scanned: Int, expected: Int, closeRefused: Boolean): OffloadCloseChoices {
        val known = hasProgress(scanned, expected)
        val reached = known && scanned >= expected
        return OffloadCloseChoices(
            nextPallet = !reached,
            complete = reached || !known,
            tooLittleTags = reached || !known,
            tooManyTags = !reached,
            backToScan = closeRefused && reached,
        )
    }

    fun hasProgress(scanned: Int, expected: Int): Boolean = scanned >= 0 && expected > 0

    /**
     * True when a not-accepted `offload_complete_result` still means the operator's part is
     * done: a short/over report the station has on record. Complete is never such a report.
     */
    fun isRecordedReport(status: String, errorCode: String): Boolean =
        status != OffloadStatus.COMPLETE && errorCode in reportRecordedCodes
}
