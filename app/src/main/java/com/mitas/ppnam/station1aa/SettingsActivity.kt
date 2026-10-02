package com.mitas.ppnam.station1aa

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.View
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import com.mitas.ppnam.station1aa.databinding.ActivitySettingsBinding

class SettingsActivity : SessionActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val connectionStatusListener: (ConnectionStatus) -> Unit = { status ->
        runOnUiThread {
            binding.connectionPill.setStatus(status)
            updateDiagnostics(status)
        }
    }

    // Ported from Station 2's SettingsViewModel so both apps' supervisor lock behave identically;
    // the counter and the lockout deadline are persisted (audit station1-01).
    private lateinit var pinLockout: PinLockout
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 1 s ticker while locked out: counts the message down and re-enables Unlock at the end. */
    private val lockoutTicker = object : Runnable {
        override fun run() { renderLockout() }
    }

    private companion object {
        const val CORRECT_PIN = "079545"
        const val KEY_UNLOCKED = "settings_unlocked"
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

        val settingsRepository = SettingsRepository(this)
        val current = settingsRepository.brokerSettings()

        binding.etBrokerHost.setText(current.host)
        binding.etBrokerPort.setText(current.port.toString())
        binding.swBrokerWebSocket.isChecked = current.useWebSocket
        binding.swBrokerTls.isChecked = current.useTls
        binding.etBrokerUsername.setText(current.username)
        binding.etAutoLogout.setText(settingsRepository.autoLogoutMinutes().toString())
        // The password field stays empty: the stored credential is never echoed back into the UI.
        // A blank field on save means "keep the provisioned password" (see save below).

        binding.btnUnlock.setOnClickListener { submitPin() }
        binding.etPin.setOnSubmit { submitPin() }
        pinLockout = PinLockout(CORRECT_PIN, PrefsPinLockoutStore(this), System::currentTimeMillis)
        // A lockout in progress must be visible (and Unlock disabled) the moment the screen opens,
        // and an unlocked form must survive recreation without asking for the PIN again.
        if (savedInstanceState?.getBoolean(KEY_UNLOCKED) == true) showUnlocked() else renderLockout()

        binding.btnSaveSettings.setOnClickListener {
            val host = binding.etBrokerHost.text.toString().trim()
            val port = BrokerSettings.parsePort(binding.etBrokerPort.text.toString())
            if (host.isBlank()) {
                binding.etBrokerHost.error = "Host required"
                return@setOnClickListener
            }
            if (port == null) {
                binding.etBrokerPort.error = "Invalid port (1–65535)"
                return@setOnClickListener
            }

            val autoLogoutMinutes = AutoLogout.parseMinutes(binding.etAutoLogout.text.toString())
            if (autoLogoutMinutes == null) {
                binding.etAutoLogout.error = getString(R.string.error_auto_logout_minutes)
                return@setOnClickListener
            }
            settingsRepository.saveAutoLogoutMinutes(autoLogoutMinutes)
            SessionGuard.applyTimeout()

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

            // 1. Properly disconnect from the OLD broker first
            MqttManager.getInstance(this).disconnect {
                runOnUiThread {
                    // 2. Save the new settings after the old presence is offline
                    if (!settingsRepository.save(newSettings)) {
                        binding.etBrokerPassword.error = "Could not store the password securely"
                        MqttManager.getInstance(this).connect()
                        return@runOnUiThread
                    }

                    // 3. Reconnect against the new broker
                    MqttManager.getInstance(this).connect()

                    // Restart app to apply changes
                    val intent = Intent(this, MainActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    startActivity(intent)
                    finish()
                }
            }
        }

        binding.btnUnlock.applyPressScaleFeedback()
        binding.btnSaveSettings.applyPressScaleFeedback()
        binding.btnLogOut.applyPressScaleFeedback()

        onBackPressedDispatcher.addCallback(this) { finishBackward() }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    /**
     * The Diagnostics card, mirroring Station 2's SettingsScreen: broker link and station
     * presence are separate failures with separate remedies, and the composite pill can only
     * name one of them at a time — so both get their own row here.
     */
    private fun updateDiagnostics(status: ConnectionStatus) {
        val green = getColor(R.color.success)
        val blue = getColor(R.color.primary_action)
        val red = getColor(R.color.danger)
        val muted = getColor(R.color.text_muted)

        when (status) {
            ConnectionStatus.CONNECTED, ConnectionStatus.STATION_OFFLINE ->
                binding.pillBroker.setAppearance(green, "Connected")
            ConnectionStatus.RECONNECTING ->
                binding.pillBroker.setAppearance(blue, "Reconnecting")
            ConnectionStatus.OFFLINE ->
                binding.pillBroker.setAppearance(red, "Disconnected")
        }

        // With the broker down, the retained presence value is stale rather than false — saying
        // "offline" there would blame the station for the broker's fault.
        when (status) {
            ConnectionStatus.CONNECTED -> binding.pillStation.setAppearance(green, "Online")
            ConnectionStatus.STATION_OFFLINE -> binding.pillStation.setAppearance(blue, "Offline")
            else -> binding.pillStation.setAppearance(muted, "Unknown")
        }
    }

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

    private fun submitPin() {
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
        super.onDestroy()
        MqttManager.getInstance(this).removeConnectionStatusListener(connectionStatusListener)
    }
}
