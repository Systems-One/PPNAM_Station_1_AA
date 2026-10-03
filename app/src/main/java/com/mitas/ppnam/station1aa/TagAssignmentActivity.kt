package com.mitas.ppnam.station1aa

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.activity.addCallback
import com.mitas.ppnam.station1aa.databinding.ActivityTagAssignmentBinding

/**
 * Tag Assignment (contract v3.0.0 §5): every scanned RFID tag is sent automatically as
 * `tag_scan`; the station decides what the tag means and answers `tag_scan_result` echoing the
 * tagId. The UI stays pending (spinner) until that result or the 10-second timeout — a PUBACK
 * is transport-only and never shown as success. A timeout or send failure offers Retry, which
 * re-sends the most recently scanned tag.
 */
class TagAssignmentActivity : SessionActivity() {

    private lateinit var binding: ActivityTagAssignmentBinding
    private lateinit var workflow: WorkflowClient
    private var lastScannedTag: String? = null

    /** What the status row shows, kept so recreation can restore it (audit station1-09). */
    private var statusText: String? = null
    private var statusColorRes: Int = R.color.text_muted
    private var statusPending = false
    private var statusRetry = false

    private val connectionStatusListener: (ConnectionStatus) -> Unit = { status ->
        runOnUiThread { binding.connectionPill.setStatus(status) }
    }

    private val rfidReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.rscja.scanner.action.scanner.RFID") {
                val data = intent.getStringExtra("data")
                if (!data.isNullOrEmpty()) {
                    // See OffloadActivity: only a read this screen consumes counts as activity.
                    SessionGuard.touch()
                    onTagScanned(data)
                }
            }
        }
    }

    private companion object {
        const val KEY_LAST_TAG = "last_tag"
        const val KEY_STATUS_TEXT = "status_text"
        const val KEY_STATUS_COLOR = "status_color"
        const val KEY_STATUS_PENDING = "status_pending"
        const val KEY_STATUS_RETRY = "status_retry"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyAppSystemBars()
        binding = ActivityTagAssignmentBinding.inflate(layoutInflater)
        setContentView(binding.root)
        forceLightStatusBarIcons()
        binding.main.padForSystemBarsAndIme()

        setupToolbar()
        workflow = WorkflowClient(this)
        MqttManager.getInstance(this).addConnectionStatusListener(connectionStatusListener)

        binding.btnRetrySend.setOnClickListener { lastScannedTag?.let { onTagScanned(it) } }

        restoreState(savedInstanceState)

        onBackPressedDispatcher.addCallback(this) { finishBackward() }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.tab_tag_assignment)
    }

    /**
     * Portrait lock means recreation is rare (locale/font changes, process restore), but the
     * last tag and its outcome must not vanish when it happens. A send that was still pending
     * cannot be resumed — its callback died with the old instance — so it is restored as the
     * timeout state with Retry, which is exactly what the operator should do.
     */
    private fun restoreState(saved: Bundle?) {
        if (saved == null) return
        lastScannedTag = saved.getString(KEY_LAST_TAG)
        lastScannedTag?.let { binding.tvLastTag.text = it }
        val text = saved.getString(KEY_STATUS_TEXT) ?: return
        if (saved.getBoolean(KEY_STATUS_PENDING)) {
            showStatus(getString(R.string.status_no_response), R.color.danger, retry = true)
        } else {
            showStatus(text, saved.getInt(KEY_STATUS_COLOR, R.color.text_muted), retry = saved.getBoolean(KEY_STATUS_RETRY))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_LAST_TAG, lastScannedTag)
        outState.putString(KEY_STATUS_TEXT, statusText)
        outState.putInt(KEY_STATUS_COLOR, statusColorRes)
        outState.putBoolean(KEY_STATUS_PENDING, statusPending)
        outState.putBoolean(KEY_STATUS_RETRY, statusRetry)
    }

    private fun onTagScanned(tagId: String) {
        runOnUiThread {
            lastScannedTag = tagId
            binding.tvLastTag.text = tagId
            showStatus(getString(R.string.status_sending), R.color.text_muted, pending = true)
        }
        sendTag(tagId)
    }

    private fun sendTag(tagId: String) {
        val payload = WorkflowMessages.tagScan(
            deviceId = DeviceIdentity.deviceId(this),
            operatorSessionId = OperatorSessionHolder.currentSessionIdOrEmpty(),
            tagId = tagId,
        )
        workflow.request(
            requestType = "tag_scan",
            responseType = "tag_scan_result",
            payload = payload,
            matches = { it.optString("tagId") == tagId },
        ) { result ->
            // A newer scan owns the status line by now — its own result will drive the UI.
            if (tagId != lastScannedTag) return@request
            result
                .onSuccess { json ->
                    if (json.optBoolean("accepted", false)) {
                        showStatus(
                            json.optString("reason", "").ifBlank { getString(R.string.status_tag_assigned) },
                            R.color.success,
                        )
                    } else {
                        if (handleSessionRejection(json)) return@request
                        showStatus(stationReason(json), R.color.danger)
                    }
                }
                .onFailure { e ->
                    val message = if (e is WorkflowTimeout) getString(R.string.status_no_response)
                    else getString(R.string.status_send_failed)
                    showStatus(message, R.color.danger, retry = true)
                }
        }
    }

    private fun stationReason(json: org.json.JSONObject): String =
        json.optString("reason", "").ifBlank {
            json.optString("errorCode", "").ifBlank { getString(R.string.status_send_failed) }
        }

    /** §8: a closed/expired session sends the operator back to login, with the reason. */
    private fun handleSessionRejection(json: org.json.JSONObject): Boolean {
        if (!WorkflowClient.isSessionRejection(json)) return false
        OperatorSessionHolder.clear()
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(LoginActivity.EXTRA_SIGNED_OUT_REASON, getString(R.string.signed_out_session_ended))
        })
        finish()
        return true
    }

    private fun showStatus(
        message: String,
        colorRes: Int,
        pending: Boolean = false,
        retry: Boolean = false,
    ) {
        statusText = message
        statusColorRes = colorRes
        statusPending = pending
        statusRetry = retry
        binding.layoutSendStatus.visibility = View.VISIBLE
        binding.progressSend.visibility = if (pending) View.VISIBLE else View.GONE
        binding.tvSendStatus.text = message
        binding.tvSendStatus.setTextColor(getColor(colorRes))
        binding.btnRetrySend.visibility = if (retry) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        val rfidFilter = IntentFilter("com.rscja.scanner.action.scanner.RFID")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(rfidReceiver, rfidFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(rfidReceiver, rfidFilter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(rfidReceiver)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finishBackward()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        MqttManager.getInstance(this).removeConnectionStatusListener(connectionStatusListener)
    }
}
