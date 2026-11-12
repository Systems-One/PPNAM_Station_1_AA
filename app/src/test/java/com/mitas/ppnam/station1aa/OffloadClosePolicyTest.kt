package com.mitas.ppnam.station1aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One dialog after every recorded pallet, offering only what is valid at that count (1.5.2):
 * Next Pallet / Too Many Tags while pallets are still expected, Complete / Too Little Tags once
 * the count is reached, and never a Cancel — except that a refused close must not hold the
 * operator in a dialog with no way back to scanning.
 */
class OffloadClosePolicyTest {

    @Test
    fun `pallets still expected offers Next Pallet and Too Many Tags only`() {
        assertEquals(
            OffloadCloseChoices(
                nextPallet = true, complete = false, tooLittleTags = false, tooManyTags = true,
                backToScan = false,
            ),
            OffloadClosePolicy.choices(scanned = 1, expected = 2, closeRefused = false),
        )
    }

    @Test
    fun `count reached offers Complete and Too Little Tags only`() {
        val reached = OffloadCloseChoices(
            nextPallet = false, complete = true, tooLittleTags = true, tooManyTags = false,
            backToScan = false,
        )
        assertEquals(reached, OffloadClosePolicy.choices(scanned = 2, expected = 2, closeRefused = false))
        assertEquals(reached, OffloadClosePolicy.choices(scanned = 3, expected = 2, closeRefused = false))
    }

    @Test
    fun `unknown progress offers everything`() {
        assertEquals(
            OffloadCloseChoices(
                nextPallet = true, complete = true, tooLittleTags = true, tooManyTags = true,
                backToScan = false,
            ),
            OffloadClosePolicy.choices(scanned = -1, expected = -1, closeRefused = false),
        )
    }

    @Test
    fun `a refused close at full count adds the way back to scanning`() {
        assertEquals(
            OffloadCloseChoices(
                nextPallet = false, complete = true, tooLittleTags = true, tooManyTags = false,
                backToScan = true,
            ),
            OffloadClosePolicy.choices(scanned = 2, expected = 2, closeRefused = true),
        )
    }

    @Test
    fun `a refused close with pallets still expected needs no extra exit`() {
        assertEquals(
            OffloadCloseChoices(
                nextPallet = true, complete = false, tooLittleTags = false, tooManyTags = true,
                backToScan = false,
            ),
            OffloadClosePolicy.choices(scanned = 1, expected = 2, closeRefused = true),
        )
    }

    @Test
    fun `a short or over report the station keeps on record counts as done`() {
        assertTrue(OffloadClosePolicy.isRecordedReport(OffloadStatus.SHORT, "INCOMPLETE_PALLETS"))
        assertTrue(OffloadClosePolicy.isRecordedReport(OffloadStatus.OVER, "INCOMPLETE_PALLETS"))
        assertTrue(
            OffloadClosePolicy.isRecordedReport(OffloadStatus.SHORT, "OVER_RECEIPT_PENDING_AUTHORISATION"),
        )
    }

    @Test
    fun `any other refusal, and every refused Complete, is still a refusal`() {
        assertFalse(OffloadClosePolicy.isRecordedReport(OffloadStatus.SHORT, "INVALID_TAG_COUNT"))
        assertFalse(OffloadClosePolicy.isRecordedReport(OffloadStatus.OVER, "INTERNAL_ERROR"))
        assertFalse(OffloadClosePolicy.isRecordedReport(OffloadStatus.OVER, ""))
        assertFalse(OffloadClosePolicy.isRecordedReport(OffloadStatus.COMPLETE, "INCOMPLETE_PALLETS"))
    }
}
