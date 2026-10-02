package com.mitas.ppnam.station1aa

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.addCallback
import com.mitas.ppnam.station1aa.databinding.ActivityMainBinding

class MainActivity : SessionActivity() {

    private lateinit var binding: ActivityMainBinding

    /** One debouncer for the whole dashboard: a rapid double-tap opens one screen (station1-06). */
    private val tileDebouncer = ClickDebouncer()

    private val connectionStatusListener: (ConnectionStatus) -> Unit = { status ->
        runOnUiThread {
            binding.connectionPill.setStatus(status)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // No session (fresh process, or logged out) — the dashboard requires an operator.
        if (OperatorSessionHolder.session == null) {
            startActivity(Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
            return
        }

        applyAppSystemBars()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        forceLightStatusBarIcons()
        binding.main.padForSystemBarsAndIme()

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)

        setupDashboard()

        MqttManager.getInstance(this).addConnectionStatusListener(connectionStatusListener)

        // Back on the dashboard asks before leaving, exactly like Login (audit station1-05): on a
        // shared handheld an accidental Back dropped the operator into the launcher unannounced.
        onBackPressedDispatcher.addCallback(this) { showExitAppDialog() }
    }

    private fun setupDashboard() {
        binding.tileTagAssignment.setDebouncedClickListener(tileDebouncer) {
            startActivityForward(Intent(this, TagAssignmentActivity::class.java))
        }

        binding.tileOffload.setDebouncedClickListener(tileDebouncer) {
            startActivityForward(Intent(this, OffloadActivity::class.java))
        }

        binding.btnSettings.setDebouncedClickListener(tileDebouncer) {
            startActivityForward(Intent(this, SettingsActivity::class.java))
        }

        // Operator control, mirroring Station 2's top bar: shows "name · role", tapping it asks
        // to log out.
        OperatorSessionHolder.session?.let { session ->
            binding.tvOperator.text =
                if (session.role.isNotBlank()) "${session.operatorName} · ${session.role}"
                else session.operatorName
        }
        binding.layoutOperator.setOnClickListener { showLogoutDialog() }

        // The login response decides which sub-apps this operator gets (allowedTabs, fail-closed
        // on a missing/empty list — display gating only, the station re-checks server-side).
        val session = OperatorSessionHolder.session
        setTileEnabled(binding.tileTagAssignment, session?.canShow(StationTab.TAG_ASSIGNMENT) ?: false)
        setTileEnabled(binding.tileOffload, session?.canShow(StationTab.OFFLOAD) ?: false)

        binding.tileTagAssignment.applyPressScaleFeedback()
        binding.tileOffload.applyPressScaleFeedback()
    }

    private fun setTileEnabled(view: com.google.android.material.card.MaterialCardView, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1.0f else 0.5f
        view.isClickable = enabled
        view.isFocusable = enabled
    }

    override fun onDestroy() {
        super.onDestroy()
        MqttManager.getInstance(this).removeConnectionStatusListener(connectionStatusListener)
    }
}
