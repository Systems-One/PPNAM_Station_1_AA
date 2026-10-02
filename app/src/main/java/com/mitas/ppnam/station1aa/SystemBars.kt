package com.mitas.ppnam.station1aa

import android.app.Activity
import android.graphics.Rect
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Forces light (white) status bar icons, matching this app's always-dark background.
 * enableEdgeToEdge()'s own light/dark heuristic doesn't resolve consistently across every
 * screen, leaving status bar icons unreadable on some activities - this makes it explicit.
 */
fun Activity.forceLightStatusBarIcons() {
    WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
}

/**
 * One edge-to-edge setup for every screen: both system bars painted window_background with
 * light icons. Before this, Login/Settings drew a black navigation bar while the edge-to-edge
 * screens got the system's light-grey contrast scrim, so the bottom edge flashed on every
 * navigation (audit station1-14). Call before setContentView.
 */
fun ComponentActivity.applyAppSystemBars() {
    val scrim = getColor(R.color.window_background)
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.dark(scrim),
        navigationBarStyle = SystemBarStyle.dark(scrim),
    )
}

/**
 * Pads this root view by the system bars AND the soft keyboard. Once a window is edge-to-edge
 * (decorFitsSystemWindows = false) `adjustResize` no longer shrinks it for the IME, so the
 * keyboard inset has to be applied here — otherwise the scroll container keeps its full height
 * and the primary button stays under the keyboard (audit station1-02). Padding declared in XML
 * is preserved underneath the insets. [onImeVisibilityChanged] fires on each change so a screen
 * can bring its primary button into view when the keyboard opens.
 */
fun View.padForSystemBarsAndIme(onImeVisibilityChanged: ((visible: Boolean) -> Unit)? = null) {
    val base = Rect(paddingLeft, paddingTop, paddingRight, paddingBottom)
    var imeWasVisible = false
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
        )
        v.setPadding(
            base.left + bars.left,
            base.top + bars.top,
            base.right + bars.right,
            base.bottom + bars.bottom,
        )
        val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
        if (imeVisible != imeWasVisible) {
            imeWasVisible = imeVisible
            onImeVisibilityChanged?.invoke(imeVisible)
        }
        insets
    }
}

/** Asks the enclosing scroll container to scroll just enough that this whole view is on screen. */
fun View.scrollIntoView() {
    requestRectangleOnScreen(Rect(0, 0, width, height), false)
}
