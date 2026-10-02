package com.mitas.ppnam.station1aa

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClickDebouncerTest {

    private var now = 10_000L
    private val debouncer = ClickDebouncer(windowMs = 600L, now = { now })

    @Test
    fun `first click is accepted`() {
        assertTrue(debouncer.accept())
    }

    @Test
    fun `a second click inside the window is dropped`() {
        assertTrue(debouncer.accept())
        now += 100
        assertFalse(debouncer.accept())
        now += 499
        assertFalse(debouncer.accept())
    }

    @Test
    fun `a click after the window is accepted and restarts it`() {
        assertTrue(debouncer.accept())
        now += 600
        assertTrue(debouncer.accept())
        now += 10
        assertFalse(debouncer.accept())
    }
}
