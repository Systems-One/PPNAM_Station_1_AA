package com.mitas.ppnam.station1aa

import org.json.JSONObject

/** The two suite scan sounds (UI_Design/sounds/README.md). */
enum class ScanSound { SUCCESS, FAIL }

/** What a screen does with one raw scanner broadcast. */
enum class ReadVerdict(val sound: ScanSound?) {
    /** Nothing was read. */
    IGNORE(null),

    /** The screen takes the read. No sound: the scanner service already beeped on the read. */
    TAKE(null),

    /** A user badge on an item screen: refused locally. */
    BADGE(ScanSound.FAIL),

    /** The screen is sending or editing and cannot take a read: refused locally. */
    BLOCKED(ScanSound.FAIL),
}

/**
 * Suite rule S1: which sound a scan's outcome makes. A scan sounds once, on the station's
 * answer — success when it accepts, fail when it refuses or the request fails or times out —
 * or on a local refusal of the read. Non-scan actions (login, navigation, settings, closing a
 * document) make no sound. The screens play what this decides through [ScanFeedback].
 */
object ScanSoundPolicy {

    /** Tag Assignment: `tag_scan_result` (or the failure in its place). */
    fun forTagScan(result: Result<JSONObject>): ScanSound =
        result.fold({ accepted(it) }, { ScanSound.FAIL })

    /**
     * Offload step 1: `offload_scan_result`. A "matched" answer without usable prefill or
     * document is treated as no match by the screen, so it fails here too.
     */
    fun forOffloadScan(result: Result<JSONObject>): ScanSound =
        result.fold(
            { json ->
                val usable = json.optBoolean("matched", false) &&
                    OffloadPrefill.fromScanResult(json) != null &&
                    OffloadDocument.fromScanResult(json) != null
                if (usable) ScanSound.SUCCESS else ScanSound.FAIL
            },
            { ScanSound.FAIL },
        )

    /** Offload step 2: `offload_confirm_result` — the station records or refuses the pallet. */
    fun forOffloadConfirm(result: Result<JSONObject>): ScanSound =
        result.fold({ accepted(it) }, { ScanSound.FAIL })

    /** Offload step 3: closing a document is a dialog choice, not a scan. */
    @Suppress("UNUSED_PARAMETER", "FunctionOnlyReturningConstant")
    fun forOffloadComplete(result: Result<JSONObject>): ScanSound? = null

    /**
     * A raw read: [accepting] is whether the screen takes reads right now; only RFID reads can
     * be user badges (a barcode is never one).
     */
    fun classifyRead(data: String?, accepting: Boolean, isRfid: Boolean): ReadVerdict = when {
        data.isNullOrEmpty() -> ReadVerdict.IGNORE
        !accepting -> ReadVerdict.BLOCKED
        isRfid && UserTagPolicy.isUserTag(data) -> ReadVerdict.BADGE
        else -> ReadVerdict.TAKE
    }

    private fun accepted(json: JSONObject): ScanSound =
        if (json.optBoolean("accepted", false)) ScanSound.SUCCESS else ScanSound.FAIL
}
