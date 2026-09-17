package com.mitas.ppnam.station1aa

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

class ScannerApp : Application() {

    private val SETTINGS_RFID = "E28011700000021B2F6E9827"

    private val rfidShortcutReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.rscja.scanner.action.scanner.RFID") {
                val data = intent.getStringExtra("data")
                if (data == SETTINGS_RFID) {
                    val settingsIntent = Intent(context, SettingsActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    startActivity(settingsIntent)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Register Global RFID Shortcut Receiver
        val rfidFilter = IntentFilter("com.rscja.scanner.action.scanner.RFID")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(rfidShortcutReceiver, rfidFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(rfidShortcutReceiver, rfidFilter)
        }

        // Initialize and connect MQTT globally
        MqttManager.getInstance(this).connect()

        // Station-offline and inactivity sign-outs (spec §2-§3).
        SessionGuard.install(this)
    }
}
