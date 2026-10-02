package com.mitas.ppnam.station1aa

import android.app.Activity
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The app's dialogs, matching Station 2's Material 3 style: rounded, inset from the edges,
 * dismiss in text colour, destructive confirm in danger red (audit station1-20 / static-15).
 * Every AlertDialog in the app goes through one of the two builders so the look cannot drift.
 */

/** Positive action is an ordinary step (Submit, Next Pallet, a close classification). */
fun Activity.neutralDialog(): MaterialAlertDialogBuilder =
    MaterialAlertDialogBuilder(this, R.style.AppAlertDialogTheme)

/** Positive action destroys something (closes the app, ends the session): red confirm. */
fun Activity.destructiveDialog(): MaterialAlertDialogBuilder =
    MaterialAlertDialogBuilder(this, R.style.AppAlertDialogTheme_Destructive)

/** "Close the app?" [Stay | Close] — the same dialog on Login and Home (audit station1-05). */
fun Activity.showExitAppDialog(): AlertDialog =
    destructiveDialog()
        .setTitle(R.string.exit_dialog_title)
        .setMessage(R.string.exit_dialog_message)
        .setPositiveButton(R.string.exit_dialog_close) { _, _ -> finishAffinity() }
        .setNegativeButton(R.string.exit_dialog_stay, null)
        .show()

/** "Log out?" [Cancel | Log out] — best-effort MQTT logout, session cleared, back to Login. */
fun Activity.showLogoutDialog(): AlertDialog =
    destructiveDialog()
        .setTitle(R.string.logout_dialog_title)
        .setMessage(R.string.logout_dialog_message)
        .setPositiveButton(R.string.btn_log_out) { _, _ ->
            AuthClient(this).logout {
                startActivity(Intent(this, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            }
        }
        .setNegativeButton(R.string.btn_cancel, null)
        .show()
