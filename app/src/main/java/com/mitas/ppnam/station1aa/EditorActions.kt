package com.mitas.ppnam.station1aa

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.TextView

/**
 * One rule for "the operator pressed submit in a text field" (audit group b). Gboard's tick
 * sends IME_ACTION_DONE/GO; the C72's hardware Enter and a scanner-wedge suffix arrive as
 * IME_NULL with a KEYCODE_ENTER event — once for key-down and once for key-up. Only the
 * key-down fires the action; the key-up is consumed so TextView does not also move focus.
 */
object EditorActions {

    enum class Result { SUBMIT, CONSUME, IGNORE }

    fun classify(actionId: Int, keyCode: Int, keyAction: Int, repeatCount: Int = 0): Result {
        // A held Enter auto-repeats key-downs; only the first press submits.
        if (repeatCount > 0) return Result.CONSUME
        if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
            return Result.SUBMIT
        }
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            return if (keyAction == KeyEvent.ACTION_DOWN) Result.SUBMIT else Result.CONSUME
        }
        return Result.IGNORE
    }
}

/** Runs [action] when the IME action button or a hardware Enter submits this field. */
fun TextView.setOnSubmit(action: () -> Unit) {
    setOnEditorActionListener { _, actionId, event ->
        val keyCode = event?.keyCode ?: KeyEvent.KEYCODE_UNKNOWN
        val keyAction = event?.action ?: -1
        when (EditorActions.classify(actionId, keyCode, keyAction, event?.repeatCount ?: 0)) {
            EditorActions.Result.SUBMIT -> { action(); true }
            EditorActions.Result.CONSUME -> true
            EditorActions.Result.IGNORE -> false
        }
    }
}
