package com.mitas.ppnam.station1aa

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 1.5.1: one dialog after every recorded pallet. Next Pallet until the expected count is
 * reached, Complete from then on, and never a Cancel — except that a refused close must not
 * hold the operator in a dialog with no way back to scanning.
 */
class OffloadClosePolicyTest {

    @Test
    fun `pallets still expected offers Next Pallet, not Complete`() {
        assertEquals(
            OffloadCloseChoices(nextPallet = true, complete = false, backToScan = false),
            OffloadClosePolicy.choices(scanned = 1, expected = 2, closeRefused = false),
        )
    }

    @Test
    fun `count reached offers Complete, not Next Pallet`() {
        assertEquals(
            OffloadCloseChoices(nextPallet = false, complete = true, backToScan = false),
            OffloadClosePolicy.choices(scanned = 2, expected = 2, closeRefused = false),
        )
        assertEquals(
            OffloadCloseChoices(nextPallet = false, complete = true, backToScan = false),
            OffloadClosePolicy.choices(scanned = 3, expected = 2, closeRefused = false),
        )
    }

    @Test
    fun `unknown progress offers both`() {
        assertEquals(
            OffloadCloseChoices(nextPallet = true, complete = true, backToScan = false),
            OffloadClosePolicy.choices(scanned = -1, expected = -1, closeRefused = false),
        )
    }

    @Test
    fun `a refused close at full count adds the way back to scanning`() {
        assertEquals(
            OffloadCloseChoices(nextPallet = false, complete = true, backToScan = true),
            OffloadClosePolicy.choices(scanned = 2, expected = 2, closeRefused = true),
        )
    }

    @Test
    fun `a refused close with pallets still expected needs no extra exit`() {
        assertEquals(
            OffloadCloseChoices(nextPallet = true, complete = false, backToScan = false),
            OffloadClosePolicy.choices(scanned = 1, expected = 2, closeRefused = true),
        )
    }
}
