package com.mitas.ppnam.station1aa

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Gboard's tick arrives as an IME action id; the C72 keypad's Enter (and a scanner-wedge
 * suffix) arrives as IME_NULL with a KEYCODE_ENTER event, once for key-down and once for
 * key-up. The submit must fire exactly once per press.
 */
class EditorActionsTest {

    private val none = KeyEvent.KEYCODE_UNKNOWN

    @Test
    fun `IME Done and Go submit`() {
        assertEquals(EditorActions.Result.SUBMIT, EditorActions.classify(EditorInfo.IME_ACTION_DONE, none, -1))
        assertEquals(EditorActions.Result.SUBMIT, EditorActions.classify(EditorInfo.IME_ACTION_GO, none, -1))
    }

    @Test
    fun `IME Next and Search are left to the framework`() {
        assertEquals(EditorActions.Result.IGNORE, EditorActions.classify(EditorInfo.IME_ACTION_NEXT, none, -1))
        assertEquals(EditorActions.Result.IGNORE, EditorActions.classify(EditorInfo.IME_ACTION_SEARCH, none, -1))
    }

    @Test
    fun `hardware Enter submits on key-down only and swallows key-up`() {
        assertEquals(
            EditorActions.Result.SUBMIT,
            EditorActions.classify(EditorInfo.IME_NULL, KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN),
        )
        assertEquals(
            EditorActions.Result.CONSUME,
            EditorActions.classify(EditorInfo.IME_NULL, KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_UP),
        )
    }

    @Test
    fun `a repeated key-down from a held Enter does not submit again`() {
        assertEquals(
            EditorActions.Result.CONSUME,
            EditorActions.classify(EditorInfo.IME_NULL, KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN, repeatCount = 1),
        )
    }

    @Test
    fun `numpad Enter behaves like Enter`() {
        assertEquals(
            EditorActions.Result.SUBMIT,
            EditorActions.classify(EditorInfo.IME_NULL, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.ACTION_DOWN),
        )
    }

    @Test
    fun `any other key is ignored`() {
        assertEquals(
            EditorActions.Result.IGNORE,
            EditorActions.classify(EditorInfo.IME_NULL, KeyEvent.KEYCODE_TAB, KeyEvent.ACTION_DOWN),
        )
        assertEquals(EditorActions.Result.IGNORE, EditorActions.classify(EditorInfo.IME_NULL, none, -1))
    }
}
