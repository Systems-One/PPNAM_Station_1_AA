package com.mitas.ppnam.station1aa

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Visual critique 2026-11-06 (P2-8): "Pair mismatch." stayed on the Offload scan card after the
 * operator rescanned the right barcode, so a correct pair sat under a red mismatch. A scan-step
 * error belongs to the pair that produced it and goes stale once either value changes — but an
 * RFID reader repeats the same read, and that must not wipe the error.
 */
class ScanStatusPolicyTest {

    @Test
    fun `no status shown is never stale`() {
        assertFalse(ScanStatusPolicy.isStale(null, "TAG-1", "BC-1"))
    }

    @Test
    fun `the same pair again keeps the error`() {
        assertFalse(ScanStatusPolicy.isStale("TAG-1" to "BC-2", "TAG-1", "BC-2"))
        assertFalse(ScanStatusPolicy.isStale("TAG-1" to "BC-2", " TAG-1 ", "BC-2 "))
    }

    @Test
    fun `a new barcode makes the error stale`() {
        assertTrue(ScanStatusPolicy.isStale("TAG-1" to "BC-2", "TAG-1", "BC-1"))
    }

    @Test
    fun `a new tag makes the error stale`() {
        assertTrue(ScanStatusPolicy.isStale("TAG-1" to "BC-2", "TAG-9", "BC-2"))
    }

    @Test
    fun `a cleared field makes the error stale`() {
        assertTrue(ScanStatusPolicy.isStale("TAG-1" to "BC-2", "", "BC-2"))
    }
}
