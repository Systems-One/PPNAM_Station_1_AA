package com.mitas.ppnam.station1aa

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.View
import androidx.activity.addCallback
import com.google.android.material.textfield.TextInputLayout
import com.mitas.ppnam.station1aa.databinding.ActivitySettingsBinding

/**
 * Supervisor settings behind a persisted PIN gate. "Test & Apply" (audit §5) validates the
 * form, reconnects to the typed broker in place, reports the verdict inline and keeps the
 * operator's session — nothing relaunches. Canonical for the XML stations (S3/S5 copy this).
 */
class SettingsActivity : SessionActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var pinLockout: PinLockout
    private val mainHandler = Handler(Looper.getMainLooper())

    private val connectionStatusListener: (ConnectionStatus) -> Unit = { status ->
        runOnUiThread {
            binding.connectionPill.setStatus(status)
            updateDiagnostics(status)
        }
    }

    /** 1 s ticker while locked out: counts the message down and re-enables Unlock at the end. */
    private val lockoutTicker = object : Runnable {
        override fun run() { renderLockout() }
    }

    /** Test & Apply in flight: the one-shot connection listener, its deadline and what it tests. */
    private var applyListener: ((Boolean) -> Unit)? = null
    private var applySettings: BrokerSettings? = null
    /** Set synchronously at entry to testAndApply; [applyListener] only exists after the async disconnect. */
    private var applying = false
    private val applyTimeout = Runnable { finishApply(connected = false) }

    private enum class ApplyState { IDLE, TESTING, SUCCESS, FAILED }

    private companion object {
        const val CORRECT_PIN = "079545"
        const val KEY_UNLOCKED = "settings_unlocked"
        /** Same window as AuthClient/WorkflowClient: one attempt, 10 s. */
        const val APPLY_TIMEOUT_MS = 10_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyAppSystemBars()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        forceLightStatusBarIcons()
        binding.main.padForSystemBarsAndIme()

        setupToolbar()
        MqttManager.getInstance(this).addConnectionStatusListener(connectionStatusListener)

        binding.tvVersion.text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        binding.tvDeviceId.text = DeviceIdentity.deviceId(this)
        setupSessionSection()

        settingsRepository = SettingsRepository(this)
        val current = settingsRepository.brokerSettings()

        binding.etBrokerHost.setText(current.host)
        binding.etBrokerPort.setText(current.port.toString())
        binding.swBrokerWebSocket.isChecked = current.useWebSocket
        binding.swBrokerTls.isChecked = current.useTls
        binding.etBrokerUsername.setText(current.username)
        binding.etAutoLogout.setText(settingsRepository.autoLogoutMinutes().toString())
        // The password field stays empty: the stored credential is never echoed back into the UI.
        // A blank field on apply means "keep the provisioned password" (see testAndApply).

        binding.btnUnlock.setOnClickListener { submitPin() }
        binding.etPin.setOnSubmit { submitPin() }
        pinLockout = PinLockout(CORRECT_PIN, PrefsPinLockoutStore(this), System::currentTimeMillis)
        // A lockout in progress must be visible (and Unlock disabled) the moment the screen opens,
        // and an unlocked form must survive recreation without asking for the PIN again.
        if (savedInstanceState?.getBoolean(KEY_UNLOCKED) == true) showUnlocked() else renderLockout()

        binding.btnSaveSettings.setOnClickListener { testAndApply() }
        // Done on the last field applies (audit group b); Next chains through the ones above.
        binding.etAutoLogout.setOnSubmit { testAndApply() }

        // A field error clears as soon as the supervisor edits that field.
        clearErrorOnEdit(binding.tilBrokerHost)
        clearErrorOnEdit(binding.tilBrokerPort)
        clearErrorOnEdit(binding.tilBrokerUsername)
        clearErrorOnEdit(binding.tilBrokerPassword)
        clearErrorOnEdit(binding.tilAutoLogout)

        binding.btnUnlock.applyPressScaleFeedback()
        binding.btnSaveSettings.applyPressScaleFeedback()
        binding.btnLogOut.applyPressScaleFeedback()

        onBackPressedDispatcher.addCallback(this) { finishBackward() }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    private fun clearErrorOnEdit(layout: TextInputLayout) {
        layout.editText?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { layout.error = null }
        })
    }

    // ---- Diagnostics -----------------------------------------------------------------------------

    /**
     * The Diagnostics card, mirroring Station 2's SettingsScreen: broker link and station
     * presence are separate failures with separate remedies, and the composite pill can only
     * name one of them at a time — so both get their own row here. Row order (MQTT Broker,
     * Station 1, Version, Device ID) is the suite standard (audit §5).
     */
    private fun updateDiagnostics(status: ConnectionStatus) {
        val green = getColor(R.color.success)
        val amber = getColor(R.color.warning)
        val red = getColor(R.color.danger)
        val muted = getColor(R.color.text_muted)

        // Same three words and colours as the top-bar pill (audit §5 "Pill vocabulary").
        when (status) {
            ConnectionStatus.CONNECTED, ConnectionStatus.STATION_OFFLINE ->
                binding.pillBroker.setAppearance(green, getString(R.string.status_connected))
            ConnectionStatus.RECONNECTING ->
                binding.pillBroker.setAppearance(amber, getString(R.string.status_reconnecting))
            ConnectionStatus.OFFLINE ->
                binding.pillBroker.setAppearance(red, getString(R.string.status_offline))
        }

        // With the broker down, the retained presence value is stale rather than false — saying
        // "offline" there would blame the station for the broker's fault.
        when (status) {
            ConnectionStatus.CONNECTED ->
                binding.pillStation.setAppearance(green, getString(R.string.status_online))
            ConnectionStatus.STATION_OFFLINE ->
                binding.pillStation.setAppearance(amber, getString(R.string.status_offline))
            else -> binding.pillStation.setAppearance(muted, getString(R.string.status_unknown))
        }
    }

    // ---- Session card ----------------------------------------------------------------------------

    /**
     * The Session card, mirroring Station 2's: the home screen's operator label is one route to
     * switching users, and Settings is the obvious second home for it.
     */
    private fun setupSessionSection() {
        val session = OperatorSessionHolder.session
        if (session == null) {
            binding.groupSession.visibility = View.GONE
            return
        }
        binding.groupSession.visibility = View.VISIBLE
        binding.tvSignedInAs.text =
            if (session.role.isNotBlank()) "${session.operatorName} · ${session.role}"
            else session.operatorName
        binding.btnLogOut.setOnClickListener { showLogoutDialog() }
    }

    // ---- PIN gate --------------------------------------------------------------------------------

    private fun submitPin() {
        // Hardware Enter plus a tap (or a ticker race) can deliver two submits for one attempt;
        // a locked-out field must not burn another attempt.
        if (!binding.btnUnlock.isEnabled) return
        when (val outcome = pinLockout.submit(binding.etPin.text.toString())) {
            PinLockout.Outcome.Blank -> showErrorMessage(getString(R.string.pin_blank))
            PinLockout.Outcome.Unlocked -> {
                hidePinMessages()
                binding.etPin.setText("")
                showUnlocked()
            }
            is PinLockout.Outcome.Rejected -> {
                binding.etPin.setText("")
                showErrorMessage(
                    resources.getQuantityString(
                        R.plurals.pin_attempts_left, outcome.attemptsLeft, outcome.attemptsLeft,
                    )
                )
            }
            is PinLockout.Outcome.LockedOut -> {
                binding.etPin.setText("")
                renderLockout()
            }
        }
    }

    private fun showUnlocked() {
        binding.cardPinLock.visibility = View.GONE
        binding.groupSettingsFields.visibility = View.VISIBLE
    }

    /**
     * Reflects the persisted lockout: message with the live countdown, field and Unlock disabled,
     * re-armed every second until it expires (audit station1-16).
     */
    private fun renderLockout() {
        mainHandler.removeCallbacks(lockoutTicker)
        val remainingMs = pinLockout.remainingLockoutMs()
        if (remainingMs <= 0L) {
            binding.tvPinLockout.visibility = View.GONE
            binding.etPin.isEnabled = true
            binding.btnUnlock.isEnabled = true
            return
        }
        val seconds = ((remainingMs + 999) / 1_000).toInt()
        binding.tvPinLockout.text = getString(R.string.pin_locked_out, seconds)
        binding.tvPinLockout.visibility = View.VISIBLE
        binding.tvPinError.visibility = View.GONE
        binding.etPin.isEnabled = false
        binding.btnUnlock.isEnabled = false
        mainHandler.postDelayed(lockoutTicker, 1_000L)
    }

    private fun showErrorMessage(message: String) {
        binding.tvPinError.text = message
        binding.tvPinError.visibility = View.VISIBLE
        binding.tvPinLockout.visibility = View.GONE
    }

    private fun hidePinMessages() {
        binding.tvPinError.visibility = View.GONE
        binding.tvPinLockout.visibility = View.GONE
    }

    // ---- Test & Apply ----------------------------------------------------------------------------

    /**
     * Validate, then: disconnect from the old broker (its retained presence goes offline), save,
     * reconnect against the new values and wait for the verdict. Success and failure are both
     * shown inline; the screen, the form and the operator's session all stay (audit static-06).
     */
    private fun testAndApply() {
        if (applying) return // a test is already running

        val host = binding.etBrokerHost.text.toString().trim()
        if (host.isBlank()) return showFieldError(binding.tilBrokerHost, R.string.error_host_required)
        val port = BrokerSettings.parsePort(binding.etBrokerPort.text.toString())
            ?: return showFieldError(binding.tilBrokerPort, R.string.error_port_invalid)
        val autoLogoutMinutes = AutoLogout.parseMinutes(binding.etAutoLogout.text.toString())
            ?: return showFieldError(binding.tilAutoLogout, R.string.error_auto_logout_minutes)

        val typedPassword = binding.etBrokerPassword.text.toString()
        val newSettings = BrokerSettings(
            host = host,
            port = port,
            useWebSocket = binding.swBrokerWebSocket.isChecked,
            useTls = binding.swBrokerTls.isChecked,
            username = binding.etBrokerUsername.text.toString().trim(),
            // Blank field keeps the already-provisioned password: the repository only
            // writes a non-blank password to the Keystore.
            password = typedPassword.ifBlank { settingsRepository.brokerSettings().password },
        )
        // MqttManager.connect() refuses to dial without a credential and would never report
        // back — say so here instead of letting the test time out.
        if (!newSettings.hasBrokerCredential) {
            return showFieldError(binding.tilBrokerPassword, R.string.error_credentials_required)
        }

        // Persist first: the disconnect below can outlive this screen, and the reconnect must
        // dial the typed broker whether or not anyone is still looking.
        settingsRepository.saveAutoLogoutMinutes(autoLogoutMinutes)
        SessionGuard.applyTimeout()
        if (!settingsRepository.save(newSettings)) {
            return showFieldError(binding.tilBrokerPassword, R.string.error_password_store)
        }

        applying = true
        applySettings = newSettings
        showApplyState(ApplyState.TESTING, getString(R.string.apply_testing))
        // Deadline starts now, so a hung disconnect cannot leave "Testing..." up forever.
        mainHandler.postDelayed(applyTimeout, APPLY_TIMEOUT_MS)

        val mqtt = MqttManager.getInstance(this)
        // Properly disconnect from the OLD broker first (its retained presence goes offline).
        mqtt.disconnect {
            runOnUiThread {
                // disconnect() cancelled reconnects: always reconnect, even if the screen is gone
                // or the deadline already fired, or the handheld stays Offline.
                if (isDestroyed || isFinishing || !applying) {
                    mqtt.connect()
                    return@runOnUiThread
                }
                // Reconnect against the new broker and wait for the verdict. addConnectionListener
                // replays the current (disconnected) state synchronously - skip that first call.
                var replayed = false
                val listener: (Boolean) -> Unit = { connected ->
                    if (!replayed) {
                        replayed = true
                    } else {
                        runOnUiThread { finishApply(connected) }
                    }
                }
                applyListener = listener
                mqtt.addConnectionListener(listener)
                mqtt.connect()
            }
        }
    }

    private fun finishApply(connected: Boolean) {
        if (!applying) return
        applying = false
        mainHandler.removeCallbacks(applyTimeout)
        applyListener?.let { MqttManager.getInstance(this).removeConnectionListener(it) }
        applyListener = null
        val tested = applySettings
        applySettings = null
        if (isDestroyed || isFinishing) return
        if (connected) {
            showApplyState(ApplyState.SUCCESS, getString(R.string.apply_success))
        } else {
            showApplyState(
                ApplyState.FAILED,
                getString(R.string.apply_failed, tested?.host ?: "", tested?.port ?: 0),
            )
        }
    }

    private fun showApplyState(state: ApplyState, message: String) {
        binding.layoutApplyStatus.visibility = if (state == ApplyState.IDLE) View.GONE else View.VISIBLE
        binding.progressApply.visibility = if (state == ApplyState.TESTING) View.VISIBLE else View.GONE
        binding.tvApplyStatus.text = message
        binding.tvApplyStatus.setTextColor(
            getColor(
                when (state) {
                    ApplyState.SUCCESS -> R.color.success
                    ApplyState.FAILED -> R.color.danger
                    else -> R.color.text_muted
                }
            )
        )
        binding.btnSaveSettings.isEnabled = state != ApplyState.TESTING
        if (state != ApplyState.IDLE) binding.layoutApplyStatus.post { binding.layoutApplyStatus.scrollIntoView() }
    }

    /** Inline field error (not the floating EditText popup), focused and scrolled into view. */
    private fun showFieldError(layout: TextInputLayout, messageRes: Int) {
        layout.error = getString(messageRes)
        layout.editText?.requestFocus()
        layout.post { layout.scrollIntoView() }
    }

    // ---- lifecycle -------------------------------------------------------------------------------

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_UNLOCKED, binding.groupSettingsFields.visibility == View.VISIBLE)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finishBackward()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(lockoutTicker)
        mainHandler.removeCallbacks(applyTimeout)
        applyListener?.let { MqttManager.getInstance(this).removeConnectionListener(it) }
        applyListener = null
        MqttManager.getInstance(this).removeConnectionStatusListener(connectionStatusListener)
        super.onDestroy()
    }
}
