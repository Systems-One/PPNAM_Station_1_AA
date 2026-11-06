package com.mitas.ppnam.station1aa

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Suite rule S1 (UI_Design/sounds/README.md): a scan sounds once, on the station's answer —
 * success when it accepts, fail when it refuses, times out or the app refuses the read locally.
 * The raw scanner read, and non-scan actions, make no sound.
 */
class ScanSoundPolicyTest {

    private fun ok(json: String) = Result.success(JSONObject(json))
    private val timeout: Result<JSONObject> = Result.failure(WorkflowTimeout())
    private val sendFailed: Result<JSONObject> = Result.failure(Exception("Not connected to the station"))

    private val matchedJson = """
        {"tagId":"T1","barcode":"B1","matched":true,"bagWeight":25,"bagCount":40,
         "batchReference":"BR-1","documentType":"purchase_order","documentNumber":"PO-9",
         "palletsScanned":1,"palletsExpected":4}
    """.trimIndent()

    // ---- Tag Assignment: tag_scan_result ----

    @Test
    fun `accepted tag scan sounds success`() {
        assertEquals(ScanSound.SUCCESS, ScanSoundPolicy.forTagScan(ok("""{"tagId":"T1","accepted":true}""")))
    }

    @Test
    fun `refused tag scan sounds fail`() {
        assertEquals(
            ScanSound.FAIL,
            ScanSoundPolicy.forTagScan(ok("""{"tagId":"T1","accepted":false,"errorCode":"TAG_UNKNOWN"}""")),
        )
    }

    @Test
    fun `tag scan without an accepted flag is a refusal`() {
        assertEquals(ScanSound.FAIL, ScanSoundPolicy.forTagScan(ok("""{"tagId":"T1"}""")))
    }

    @Test
    fun `tag scan refused for an ended session sounds fail`() {
        assertEquals(
            ScanSound.FAIL,
            ScanSoundPolicy.forTagScan(ok("""{"accepted":false,"errorCode":"OPERATOR_SESSION_INVALID"}""")),
        )
    }

    @Test
    fun `timed out or unsent tag scan sounds fail`() {
        assertEquals(ScanSound.FAIL, ScanSoundPolicy.forTagScan(timeout))
        assertEquals(ScanSound.FAIL, ScanSoundPolicy.forTagScan(sendFailed))
    }

    // ---- Offload: offload_scan_result ----

    @Test
    fun `matched pair with usable prefill sounds success`() {
        assertEquals(ScanSound.SUCCESS, ScanSoundPolicy.forOffloadScan(ok(matchedJson)))
    }

    @Test
    fun `pair mismatch sounds fail`() {
        assertEquals(
            ScanSound.FAIL,
            ScanSoundPolicy.forOffloadScan(ok("""{"matched":false,"errorCode":"PAIR_MISMATCH"}""")),
        )
    }

    @Test
    fun `matched pair without usable prefill or document sounds fail`() {
        val noPrefill = JSONObject(matchedJson).apply { remove("bagWeight") }
        val noDocument = JSONObject(matchedJson).apply { remove("documentNumber") }
        assertEquals(ScanSound.FAIL, ScanSoundPolicy.forOffloadScan(Result.success(noPrefill)))
        assertEquals(ScanSound.FAIL, ScanSoundPolicy.forOffloadScan(Result.success(noDocument)))
    }

    @Test
    fun `timed out or unsent pair match sounds fail`() {
        assertEquals(ScanSound.FAIL, ScanSoundPolicy.forOffloadScan(timeout))
        assertEquals(ScanSound.FAIL, ScanSoundPolicy.forOffloadScan(sendFailed))
    }

    // ---- Offload: offload_confirm_result ----

    @Test
    fun `accepted pallet confirm sounds success`() {
        assertEquals(ScanSound.SUCCESS, ScanSoundPolicy.forOffloadConfirm(ok("""{"accepted":true}""")))
    }

    @Test
    fun `refused pallet confirm sounds fail, value validation included`() {
        assertEquals(
            ScanSound.FAIL,
            ScanSoundPolicy.forOffloadConfirm(ok("""{"accepted":false,"errorCode":"PAIR_MISMATCH"}""")),
        )
        assertEquals(
            ScanSound.FAIL,
            ScanSoundPolicy.forOffloadConfirm(ok("""{"accepted":false,"errorCode":"INVALID_BAG_WEIGHT"}""")),
        )
    }

    @Test
    fun `timed out pallet confirm sounds fail`() {
        assertEquals(ScanSound.FAIL, ScanSoundPolicy.forOffloadConfirm(timeout))
    }

    // ---- non-scan actions: no sound ----

    @Test
    fun `closing a document is a button action, never a scan sound`() {
        assertNull(ScanSoundPolicy.forOffloadComplete(ok("""{"accepted":true}""")))
        assertNull(ScanSoundPolicy.forOffloadComplete(ok("""{"accepted":false}""")))
        assertNull(ScanSoundPolicy.forOffloadComplete(timeout))
    }

    // ---- raw reads: forwarded silently, refused locally with a fail ----

    @Test
    fun `an item read the screen takes makes no sound (the scanner already beeped)`() {
        val read = ScanSoundPolicy.classifyRead("E2801170000002", accepting = true, isRfid = true)
        assertEquals(ReadVerdict.TAKE, read)
        assertNull(read.sound)
        assertEquals(ReadVerdict.TAKE, ScanSoundPolicy.classifyRead("BC-1", accepting = true, isRfid = false))
    }

    @Test
    fun `a user badge on an item screen is refused locally with a fail`() {
        val read = ScanSoundPolicy.classifyRead("505055ABCDEF", accepting = true, isRfid = true)
        assertEquals(ReadVerdict.BADGE, read)
        assertEquals(ScanSound.FAIL, read.sound)
    }

    @Test
    fun `a barcode is never treated as a badge`() {
        assertEquals(ReadVerdict.TAKE, ScanSoundPolicy.classifyRead("505055-BC", accepting = true, isRfid = false))
    }

    @Test
    fun `a read while the screen is sending or editing is refused locally with a fail`() {
        val read = ScanSoundPolicy.classifyRead("E2801170000002", accepting = false, isRfid = true)
        assertEquals(ReadVerdict.BLOCKED, read)
        assertEquals(ScanSound.FAIL, read.sound)
        assertEquals(ReadVerdict.BLOCKED, ScanSoundPolicy.classifyRead("BC-1", accepting = false, isRfid = false))
        assertEquals(ReadVerdict.BLOCKED, ScanSoundPolicy.classifyRead("505055ABCDEF", accepting = false, isRfid = true))
    }

    @Test
    fun `an empty broadcast is ignored without a sound`() {
        assertEquals(ReadVerdict.IGNORE, ScanSoundPolicy.classifyRead(null, accepting = true, isRfid = true))
        assertEquals(ReadVerdict.IGNORE, ScanSoundPolicy.classifyRead("", accepting = false, isRfid = false))
        assertNull(ReadVerdict.IGNORE.sound)
    }
}
